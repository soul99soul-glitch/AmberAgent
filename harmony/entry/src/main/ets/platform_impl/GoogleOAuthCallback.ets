import native from 'libamber_native.so';
import type { GoogleLoopbackHandle, GoogleLoopbackRequest } from 'libamber_native.so';
import { url } from '@kit.ArkTS';
import { GoogleAuthError } from '@amber/chat-domain';
import type { AbortSignalLike } from '@amber/deepread-domain';
import { checkGoogleAbort, googleCancelled } from './GoogleAuthHttp.ets';

export interface GoogleRedirect { uri: string; port: number; path: string; }
export interface GoogleCallbackResult { status: number; code: string | null; error: string | null; }
export interface GoogleCallbackAttempt { code: Promise<string>; close: () => Promise<void>; }

export const parseGoogleRedirect = (uri: string): GoogleRedirect => {
  const match: RegExpMatchArray | null = uri.match(/^http:\/\/(127\.0\.0\.1|localhost):([0-9]{1,5})(\/[^?#]*)$/);
  if (match === null) throw new GoogleAuthError('redirect_invalid', '回调必须是包含固定端口和路径的 http://127.0.0.1 或 localhost 地址。');
  const port: number = Number(match[2]);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new GoogleAuthError('redirect_invalid', 'OAuth 回调端口无效。');
  let parsed: url.URL;
  try { parsed = url.URL.parseURL(uri); }
  catch { throw new GoogleAuthError('redirect_invalid', 'OAuth 回调地址无效。'); }
  return { uri, port, path: parsed.pathname };
};

export const parseGoogleCallbackTarget = (
  target: string, redirect: GoogleRedirect, expectedState: string,
): GoogleCallbackResult => {
  if (!target.startsWith('/') || target.startsWith('//') || /[\r\n\x00]/.test(target)) return { status: 400, code: null, error: null };
  let parsed: url.URL;
  try { parsed = url.URL.parseURL(target, redirect.uri); }
  catch { return { status: 400, code: null, error: null }; }
  if (parsed.pathname !== redirect.path) return { status: 404, code: null, error: null };
  const states: string[] = parsed.params.getAll('state');
  const codes: string[] = parsed.params.getAll('code');
  const errors: string[] = parsed.params.getAll('error');
  if (states.length !== 1 || states[0] !== expectedState || parsed.hash.length > 0
    || codes.length + errors.length !== 1 || (codes.length === 1 && codes[0].length === 0)) {
    return { status: 400, code: null, error: null };
  }
  return { status: 200, code: codes.length === 1 ? codes[0] : null,
    error: errors.length === 1 ? (errors[0] === 'access_denied' ? 'access_denied' : 'authorization_failed') : null };
};

export const startGoogleOAuthCallback = async (
  redirect: GoogleRedirect, state: string, signal?: AbortSignalLike,
): Promise<GoogleCallbackAttempt> => {
  checkGoogleAbort(signal);
  let handleId: string = '';
  let consumed: boolean = false;
  let closed: boolean = false;
  let closePromise: Promise<void> | null = null;
  let timer: number = -1;
  let pending: GoogleLoopbackRequest[] = [];
  let acceptCode: (value: string) => void = (_value: string): void => {};
  let rejectCode: (error: Error) => void = (_error: Error): void => {};
  const code: Promise<string> = new Promise<string>((resolve, reject): void => { acceptCode = resolve; rejectCode = reject; });
  // A cancellation before the caller obtains the attempt still has a handled result.
  code.catch((): void => {});
  const close = (): Promise<void> => {
    closed = true;
    if (timer !== -1) { clearTimeout(timer); timer = -1; }
    signal?.removeEventListener?.('abort', abort);
    if (handleId.length === 0) return Promise.resolve();
    if (closePromise === null) closePromise = native.closeGoogleLoopback(handleId);
    return closePromise;
  };
  const fail = (error: Error): void => {
    if (consumed) return;
    consumed = true;
    close().then((): void => rejectCode(error), (): void => rejectCode(new GoogleAuthError('callback_close_failed', 'OAuth 回调端口关闭失败。') as Error));
  };
  const abort = (): void => {
    if (!consumed) { fail(googleCancelled() as Error); return; }
    close().then((): void => rejectCode(googleCancelled() as Error),
      (): void => rejectCode(new GoogleAuthError('callback_close_failed', 'OAuth 回调端口关闭失败。') as Error));
  };
  const receive = (request: GoogleLoopbackRequest): void => {
    if (handleId.length === 0) { pending.push(request); return; }
    if (closed) return;
    if (request.errorCode !== null) { fail(new GoogleAuthError('callback_failed', 'OAuth 回调监听失败。') as Error); return; }
    const result: GoogleCallbackResult = parseGoogleCallbackTarget(request.requestTarget, redirect, state);
    if (consumed || result.status !== 200) {
      native.replyGoogleLoopback(handleId, request.connectionId, consumed ? 400 : result.status)
        .catch((): void => fail(new GoogleAuthError('callback_failed', 'OAuth 回调响应失败。') as Error));
      return;
    }
    consumed = true;
    native.replyGoogleLoopback(handleId, request.connectionId, 200).then(async (): Promise<void> => {
      await close();
      if (signal?.aborted === true) { rejectCode(googleCancelled() as Error); return; }
      if (result.error !== null) { rejectCode(new GoogleAuthError(result.error, 'Google 登录未完成，请重试。') as Error); return; }
      if (result.code === null) { rejectCode(new GoogleAuthError('authorization_failed', 'Google 登录未返回授权码。') as Error); return; }
      acceptCode(result.code);
    }).catch((): void => {
      const reject: () => void = (): void => rejectCode(new GoogleAuthError('callback_failed', 'OAuth 回调关闭或响应失败。') as Error);
      close().then(reject, reject);
    });
  };
  signal?.addEventListener?.('abort', abort);
  try {
    checkGoogleAbort(signal);
    const handle: GoogleLoopbackHandle = await native.startGoogleLoopback(redirect.port, receive);
    handleId = handle.handleId;
    if (handle.port !== redirect.port) throw new GoogleAuthError('callback_port_mismatch', '实际 OAuth 回调端口与配置不一致。');
    if (closed || signal?.aborted === true) { await close(); throw googleCancelled(); }
    timer = setTimeout((): void => fail(new GoogleAuthError('callback_timeout', 'Google 登录已超时，请重试。') as Error), 120000);
    const buffered: GoogleLoopbackRequest[] = pending;
    pending = [];
    for (const request of buffered) receive(request);
    return { code, close };
  } catch (error) {
    consumed = true;
    await close();
    rejectCode(error as Error);
    if (error instanceof GoogleAuthError) throw error;
    throw new GoogleAuthError('callback_unavailable', '无法监听 OAuth 回调端口，请检查端口占用后重试。');
  }
};
