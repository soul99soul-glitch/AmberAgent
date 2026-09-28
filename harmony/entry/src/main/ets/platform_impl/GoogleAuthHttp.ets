import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { util } from '@kit.ArkTS';
import { GoogleAuthError } from '@amber/chat-domain';
import type { JsonObject, JsonValue } from '@amber/chat-domain';
import type { AbortSignalLike, HttpClient, HttpRequest, HttpResponse } from '@amber/deepread-domain';
import { EntryAbortController } from './EntryAbortController.ets';

export const googleFormEncode = (fields: Record<string, string>): string => {
  const pairs: string[] = [];
  for (const key of Object.keys(fields)) pairs.push(`${encodeURIComponent(key)}=${encodeURIComponent(fields[key])}`);
  return pairs.join('&');
};

export const googleBase64Url = (bytes: Uint8Array): string => new util.Base64Helper()
  .encodeToStringSync(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

export const googleUtf8 = (text: string): Uint8Array => util.TextEncoder.create('utf-8').encodeInto(text);

export const googleRandom = (): string => googleBase64Url(cryptoFramework.createRandom().generateRandomSync(32).data);

export const googleSha256 = (text: string): string => {
  const digest = cryptoFramework.createMd('SHA256');
  digest.updateSync({ data: googleUtf8(text) });
  return googleBase64Url(digest.digestSync().data);
};

export const googleCancelled = (): GoogleAuthError => new GoogleAuthError('cancelled', 'Google 授权已取消。');

export const checkGoogleAbort = (signal?: AbortSignalLike): void => {
  if (signal?.aborted === true) throw googleCancelled();
};

export const fetchGoogleJson = async (
  http: HttpClient, request: HttpRequest, signal?: AbortSignalLike,
): Promise<JsonObject> => {
  checkGoogleAbort(signal);
  let response: HttpResponse;
  try { response = await http.fetch(request, { signal }); }
  catch { checkGoogleAbort(signal); throw new GoogleAuthError('network_error', 'Google 授权请求失败，请检查网络后重试。'); }
  checkGoogleAbort(signal);
  let parsed: JsonValue;
  try {
    if (response.body.length > 1024 * 1024) throw new Error('oversized');
    parsed = JSON.parse(response.body) as JsonValue;
  } catch { throw new GoogleAuthError('invalid_response', 'Google 返回的授权数据无效。'); }
  if (parsed === null || Array.isArray(parsed) || typeof parsed !== 'object') {
    throw new GoogleAuthError('invalid_response', 'Google 返回的授权数据无效。');
  }
  const object: JsonObject = parsed as JsonObject;
  if (response.status < 200 || response.status >= 300) {
    const code: JsonValue | undefined = object['error'];
    if (code === 'invalid_grant') throw new GoogleAuthError('invalid_grant', 'Google 授权已失效，请重新登录。');
    if (code === 'invalid_client' || code === 'unauthorized_client') {
      throw new GoogleAuthError('invalid_client', 'OAuth 客户端未获准，请检查客户端与回调配置。');
    }
    throw new GoogleAuthError('remote_http_error', `Google 授权请求返回 HTTP ${response.status}。`);
  }
  return object;
};

// E6 permits two conversations to refresh the same provider. One caller's cancellation
// leaves the shared refresh alive for the other; the last waiter closes its HTTP request.
export class GoogleAuthFlight<T> {
  private readonly controller: EntryAbortController = new EntryAbortController();
  private readonly result: Promise<T>;
  private waiters: number = 0;
  private settled: boolean = false;

  constructor(work: (signal: AbortSignalLike) => Promise<T>) {
    this.result = Promise.resolve().then((): Promise<T> => work(this.controller.signal));
    this.result.then((): void => { this.settled = true; }, (): void => { this.settled = true; });
  }

  cancel(): void { this.controller.abort(); }

  onSettled(callback: () => void): void { this.result.then(callback, callback); }

  wait(signal?: AbortSignalLike): Promise<T> {
    return new Promise<T>((resolve, reject): void => {
      let finished: boolean = false;
      this.waiters++;
      const finish = (): boolean => {
        if (finished) return false;
        finished = true;
        signal?.removeEventListener?.('abort', abort);
        this.waiters--;
        if (this.waiters === 0 && !this.settled) this.controller.abort();
        return true;
      };
      const abort = (): void => { if (finish()) reject(googleCancelled()); };
      signal?.addEventListener?.('abort', abort);
      if (signal?.aborted === true) { abort(); return; }
      this.result.then((value: T): void => { if (finish()) resolve(value); },
        (error: Error): void => { if (finish()) reject(error); });
    });
  }
}

export const googleDelay = (millis: number, signal?: AbortSignalLike): Promise<void> =>
  new Promise<void>((resolve, reject): void => {
    let done: boolean = false;
    let timer: number = -1;
    const finish = (): boolean => {
      if (done) return false;
      done = true;
      if (timer !== -1) clearTimeout(timer);
      signal?.removeEventListener?.('abort', abort);
      return true;
    };
    const abort = (): void => { if (finish()) reject(googleCancelled()); };
    signal?.addEventListener?.('abort', abort);
    if (signal?.aborted === true) { abort(); return; }
    timer = setTimeout((): void => { if (finish()) resolve(); }, millis);
  });
