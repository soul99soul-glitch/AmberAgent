import { rcp } from '@kit.RemoteCommunicationKit';
import { url, util } from '@kit.ArkTS';
import type { AbortSignalLike } from '@amber/deepread-domain';
import { PluginHttpError } from '@amber/chat-domain';
import type { JsonObject, JsonValue, PluginHttpRequest, PluginHttpResult, PluginHttpPort } from '@amber/chat-domain';

const MAX_REDIRECTS: number = 3;
const MAX_REQUEST_BYTES: number = 256 * 1024;

interface HopResponse { status: number; headers: rcp.ResponseHeaders; text: string; }

const checkedUrl = (raw: string, domains: string[], base?: string): url.URL => {
  let parsed: url.URL;
  try { parsed = url.URL.parseURL(raw, base); }
  catch { throw new PluginHttpError('remote_url_invalid', '插件远端 URL 无效。', false); }
  if (parsed.protocol.toLowerCase() !== 'https:' || parsed.username.length > 0 || parsed.password.length > 0) {
    throw new PluginHttpError('remote_url_invalid', '插件远端 URL 无效。', false);
  }
  let host: string = parsed.hostname.toLowerCase();
  if (host.endsWith('.')) host = host.slice(0, -1);
  if (host.length === 0 || !domains.some((domain: string): boolean => host === domain || host.endsWith(`.${domain}`))) {
    throw new PluginHttpError('network_domain_denied', '插件未声明此网络域。', false);
  }
  parsed.hash = '';
  return parsed;
};

const queryValue = (value: JsonValue): string => typeof value === 'string' ? value : JSON.stringify(value);

const fetchHop = (
  session: rcp.Session, target: string, method: rcp.HttpMethod, headers: rcp.RequestHeaders,
  content: string | undefined, deadline: number, maxOutputChars: number, mutating: boolean,
  signal?: AbortSignalLike,
): Promise<HopResponse> => new Promise<HopResponse>((resolve, reject): void => {
  let settled: boolean = false;
  let sent: boolean = false;
  let timer: number = -1;
  let bytes: number = 0;
  const chunks: Uint8Array[] = [];
  const cleanup = (): void => {
    if (timer !== -1) clearTimeout(timer);
    signal?.removeEventListener?.('abort', onAbort);
  };
  const fail = (code: string): void => {
    if (settled) return;
    settled = true;
    cleanup();
    try { session.cancel(); } catch { /* Preserve the call failure. */ }
    reject(new PluginHttpError(code, code, sent && mutating));
  };
  const onAbort = (): void => fail('cancelled');
  if (signal?.aborted) { onAbort(); return; }
  const remaining: number = deadline - Date.now();
  if (remaining <= 0) { fail('timed_out'); return; }
  signal?.addEventListener?.('abort', onAbort);
  if (signal?.aborted) { onAbort(); return; }
  timer = setTimeout((): void => fail('timed_out'), remaining);
  const configuration: rcp.Configuration = {
    transfer: { autoRedirect: false, timeout: { connectMs: remaining, transferMs: remaining } },
    tracing: { httpEventsHandler: {
      onDataReceive: (data: ArrayBuffer): number => {
        if (settled) return 0;
        if (bytes + data.byteLength > maxOutputChars * 4) { fail('output_limit'); return 0; }
        bytes += data.byteLength;
        chunks.push(new Uint8Array(data).slice());
        return data.byteLength;
      },
    } },
  };
  let responsePromise: Promise<rcp.Response>;
  try {
    const request: rcp.Request = new rcp.Request(target, method, headers, content,
      undefined, undefined, configuration);
    sent = true;
    responsePromise = session.fetch(request);
  } catch { fail('remote_request_failed'); return; }
  responsePromise.then((response: rcp.Response): void => {
    if (settled) return;
    const data: Uint8Array = new Uint8Array(bytes);
    let offset: number = 0;
    for (const chunk of chunks) { data.set(chunk, offset); offset += chunk.byteLength; }
    let text: string;
    try { text = util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(data); }
    catch { fail('remote_invalid_utf8'); return; }
    if (text.length > maxOutputChars) { fail('output_limit'); return; }
    settled = true;
    cleanup();
    resolve({ status: response.statusCode, headers: response.headers, text });
  }).catch((): void => fail('remote_request_failed'));
});

const executeHttp = async (
  endpoint: string, method: string, inputs: JsonObject, networkDomains: string[],
  timeoutMs: number, maxOutputChars: number, signal?: AbortSignalLike,
): Promise<PluginHttpResult> => {
  if (!['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) throw new PluginHttpError('remote_method_invalid', '插件远端方法无效。', false);
  const mutating: boolean = method !== 'GET' && method !== 'HEAD';
  const parsed: url.URL = checkedUrl(endpoint, networkDomains);
  const headers: rcp.RequestHeaders = { accept: 'application/json' };
  let content: string | undefined;
  if (!mutating) {
    for (const key of Object.keys(inputs).sort()) parsed.params.append(key, queryValue(inputs[key]));
  } else {
    content = JSON.stringify(inputs);
    if (util.TextEncoder.create('utf-8').encodeInto(content).byteLength > MAX_REQUEST_BYTES) {
      throw new PluginHttpError('request_too_large', '插件请求超过 256 KiB。', false);
    }
    headers['content-type'] = 'application/json';
  }
  const deadline: number = Date.now() + timeoutMs;
  const session: rcp.Session = rcp.createSession();
  let target: string = parsed.href;
  let sent: boolean = false;
  try {
    for (let hop: number = 0; ; hop++) {
      target = checkedUrl(target, networkDomains).href;
      const response: HopResponse = await fetchHop(session, target, method, headers, content,
        deadline, maxOutputChars, mutating, signal);
      sent = true;
      if ([301, 302, 303, 307, 308].includes(response.status)) {
        const location: string | undefined = response.headers['location'];
        if (hop >= MAX_REDIRECTS) throw new PluginHttpError('redirect_limit', '插件远端重定向超过三跳。', mutating);
        if (location === undefined || location.trim().length === 0) throw new PluginHttpError('redirect_invalid', '插件远端重定向缺少有效 Location。', mutating);
        if (mutating && response.status !== 307 && response.status !== 308) {
          throw new PluginHttpError('redirect_method_change', '重定向要求改变固定请求方法，已停止。', true);
        }
        target = checkedUrl(location, networkDomains, target).href;
        continue;
      }
      if (response.status < 200 || response.status >= 300) throw new PluginHttpError('remote_http_status', `插件远端返回 HTTP ${response.status}。`, mutating);
      return { status: response.status, body: response.text.length === 0 ? 'null' : response.text };
    }
  } catch (error) {
    if (error instanceof PluginHttpError) {
      if (sent && mutating && !error.mayHaveApplied) throw new PluginHttpError(error.code, error.message, true);
      throw error;
    }
    throw new PluginHttpError('remote_request_failed', '插件远端请求失败。', sent && mutating);
  } finally {
    session.close();
  }
};

class EntryPluginHttpPort implements PluginHttpPort {
  execute(request: PluginHttpRequest, signal?: AbortSignalLike): Promise<PluginHttpResult> {
    return executeHttp(request.url, request.method, request.input, request.networkDomains.slice(),
      request.timeoutMs, request.maxOutputChars, signal);
  }
}

export const createEntryPluginHttpPort = (): PluginHttpPort => new EntryPluginHttpPort();
