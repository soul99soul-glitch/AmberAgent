// WebMountRestrictedHttp — OAuth token 交换与 Bearer 注入的受限 HTTP(E12)
//
// E5 先例(rcp autoRedirect=false):token 兑换/刷新绝不自动跟随重定向——3xx
//   可见失败,client 凭据不会被送往未配置目标。Bearer 注入仅当每跳 origin 与
//   初始请求 origin(已核 ∈ apiOrigins)完全一致;跨 origin 重定向停跳并可见
//   返回 3xx,旧 Bearer/Cookie 永不跨源。不注入 Cookie/额外 header。

import { rcp } from '@kit.RemoteCommunicationKit';
import { url, util } from '@kit.ArkTS';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type { WebMountOAuthApplication } from '@amber/chat-domain';
import { webMountOriginOf } from '@amber/chat-domain';

export interface WebMountRestrictedResponse { status: number; location: string | null; text: string; }

const MAX_RESPONSE_BYTES: number = 256 * 1024;
const MAX_BEARER_REDIRECTS: number = 3;

const abortedError = (): Error => {
  const error: Error = new Error('WebMount OAuth cancelled');
  error.name = 'AbortError';
  return error;
};

const locationOf = (headers: rcp.ResponseHeaders): string | null => {
  const value: string | string[] | undefined = headers.location;
  if (Array.isArray(value)) {
    const items: string[] = value as string[];
    return items.length > 0 && items[0].length > 0 ? items[0] : null;
  }
  return typeof value === 'string' && value.length > 0 ? value : null;
};

const fetchOnce = (
  target: string, method: rcp.HttpMethod, headers: rcp.RequestHeaders, content: string | undefined,
  deadlineMs: number, signal?: AbortSignalLike,
): Promise<WebMountRestrictedResponse> => new Promise<WebMountRestrictedResponse>((resolve, reject): void => {
  let settled: boolean = false;
  let timer: number = -1;
  let bytes: number = 0;
  const chunks: Uint8Array[] = [];
  const session: rcp.Session = rcp.createSession();
  const cleanup = (): void => {
    if (timer !== -1) clearTimeout(timer);
    signal?.removeEventListener?.('abort', onAbort);
    try { session.close(); } catch { /* already closed */ }
  };
  const fail = (error: Error): void => {
    if (settled) return;
    settled = true;
    cleanup();
    try { session.cancel(); } catch { /* preserve the original failure */ }
    reject(error);
  };
  const onAbort = (): void => fail(abortedError());
  if (signal?.aborted === true) { onAbort(); return; }
  const remaining: number = deadlineMs - Date.now();
  if (remaining <= 0) { fail(new Error('WebMount OAuth request timed out')); return; }
  signal?.addEventListener?.('abort', onAbort);
  timer = setTimeout((): void => fail(new Error('WebMount OAuth request timed out')), remaining);
  const configuration: rcp.Configuration = {
    transfer: { autoRedirect: false, timeout: { connectMs: remaining, transferMs: remaining } },
    tracing: { httpEventsHandler: {
      onDataReceive: (data: ArrayBuffer): number => {
        if (settled) return 0;
        if (bytes + data.byteLength > MAX_RESPONSE_BYTES) { fail(new Error('WebMount OAuth response too large')); return 0; }
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
    responsePromise = session.fetch(request);
  } catch (_error) { fail(new Error('WebMount OAuth request failed')); return; }
  responsePromise.then((response: rcp.Response): void => {
    if (settled) return;
    const data: Uint8Array = new Uint8Array(bytes);
    let offset: number = 0;
    for (const chunk of chunks) { data.set(chunk, offset); offset += chunk.byteLength; }
    let text: string;
    try { text = util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(data); }
    catch (_error) { fail(new Error('WebMount OAuth response is not UTF-8')); return; }
    settled = true;
    const location: string | null = locationOf(response.headers);
    cleanup();
    resolve({ status: response.statusCode ?? 0, location: location, text: text });
  }).catch((): void => fail(new Error('WebMount OAuth request failed')));
});

// token 兑换/刷新:固定 https endpoint,3xx 一律可见失败(不跟随)
export const webMountOAuthTokenPost = async (
  endpoint: string, contentType: string, body: string, authorizationHeader: string | null,
  timeoutMs: number = 30000, signal?: AbortSignalLike,
): Promise<WebMountRestrictedResponse> => {
  const headers: rcp.RequestHeaders = { 'content-type': contentType, 'accept': 'application/json' };
  if (authorizationHeader !== null) headers['authorization'] = authorizationHeader;
  const response: WebMountRestrictedResponse = await fetchOnce(
    endpoint, 'POST', headers, body, Date.now() + timeoutMs, signal);
  if (response.status >= 300 && response.status < 400) {
    throw new Error(`oauth_redirect_refused: token endpoint answered ${response.status}`);
  }
  return response;
};

export interface WebMountBearerResult {
  status: number;
  text: string;
  finalUrl: string;
  followedRedirects: number;
  stoppedAtCrossOriginRedirect: boolean;
}

const resolveRedirect = (location: string, base: string): string | null => {
  try {
    const resolved: url.URL = url.URL.parseURL(location, base);
    if (resolved.protocol.toLowerCase() !== 'https:' && resolved.protocol.toLowerCase() !== 'http:') return null;
    if (resolved.username.length > 0 || resolved.password.length > 0) return null;
    return resolved.href;
  } catch (_error) {
    return null;
  }
};

// Bearer 注入 GET/HEAD:初始 origin 已由调用方核验 ∈ apiOrigins;手动逐跳,
//   仅同源跳转发送 Bearer;跨 origin Location 停跳并可见返回 3xx
export const webMountOAuthBearerFetch = async (
  application: WebMountOAuthApplication, accessToken: string, startUrl: string,
  method: 'GET' | 'HEAD' = 'GET', timeoutMs: number = 30000, signal?: AbortSignalLike,
): Promise<WebMountBearerResult> => {
  const initialOrigin: string | null = webMountOriginOf(startUrl);
  if (initialOrigin === null || !application.apiOrigins.includes(initialOrigin)) {
    throw new Error('oauth_origin_denied: request origin is not a configured API origin');
  }
  const deadline: number = Date.now() + timeoutMs;
  let currentUrl: string = startUrl;
  for (let hop: number = 0; hop <= MAX_BEARER_REDIRECTS; hop++) {
    const headers: rcp.RequestHeaders = { 'accept': '*/*' };
    // 当前 URL 与初始 origin 一致才注入;跳转后不一致则不携带任何凭据
    if (webMountOriginOf(currentUrl) === initialOrigin) {
      headers['authorization'] = `Bearer ${accessToken}`;
    }
    const response: WebMountRestrictedResponse = await fetchOnce(currentUrl, method, headers, undefined, deadline, signal);
    if (response.status < 300 || response.status >= 400 || method === 'HEAD' || response.location === null) {
      return { status: response.status, text: response.text, finalUrl: currentUrl,
        followedRedirects: hop, stoppedAtCrossOriginRedirect: false };
    }
    const next: string | null = resolveRedirect(response.location, currentUrl);
    if (next === null || webMountOriginOf(next) !== initialOrigin) {
      return { status: response.status, text: response.text, finalUrl: currentUrl,
        followedRedirects: hop, stoppedAtCrossOriginRedirect: true };
    }
    currentUrl = next;
  }
  throw new Error('oauth_redirect_limit: too many redirects');
};
