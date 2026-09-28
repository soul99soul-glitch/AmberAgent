// WebMountOAuthRuntime — 站点 OAuth 运行时(E12 冻结合同 B exports)
//
// 配置:KV 'webmount_oauth_applications'(siteId → 校验后应用,含 bindingHash 与
//   clientSecretRef);secret/token 走 WebMountSecureStore 安全引用。
// 连接:先起 native loopback(GoogleOAuthCallback 的通用传输)再开系统浏览器;
//   connect 返回 awaiting_callback,兑换/提交在内部完成,状态经 subscribe 观察;
//   错误 state 零兑换;绑定变更使 pending 与 token 失效;重启不复活 PKCE。
// HAR:导入即解析校验(HAR 1.2,≤2MiB/200 entries),archive 仅会话内内存登记,
//   原始 HAR 永不进工具输出。

import type { Context, common } from '@kit.AbilityKit';
import type { AbortControllerLike, AbortSignalLike } from '@amber/deepread-domain';
import type { KeyValueStore, JsonObject, JsonValue, WebMountCryptoPort } from '@amber/chat-domain';
import {
  GoogleAuthError, newId, parseWebMountHar, validateWebMountOAuthApplication,
  canonicalWebMountOAuthBinding, buildWebMountAuthorizationUrl, buildWebMountTokenRequest,
  parseWebMountTokenResponse, webMountTokenUsable, createWebMountPkce,
} from '@amber/chat-domain';
import type {
  WebMountHarArchive, WebMountOAuthApplication, WebMountOAuthApplicationDraft,
  WebMountOAuthState, WebMountOAuthStatus, WebMountOAuthToken, WebMountPkce,
} from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { createEntryAbortController } from './EntryAbortController.ets';
import { parseGoogleRedirect, startGoogleOAuthCallback } from './GoogleOAuthCallback.ets';
import type { GoogleCallbackAttempt, GoogleRedirect } from './GoogleOAuthCallback.ets';
import { createWebMountCryptoPort } from './WebMountOAuthSupport.ets';
import {
  deleteWebMountSecret, loadWebMountSecret, removeWebMountOAuthToken, stageWebMountSecret,
  readWebMountOAuthToken, writeWebMountOAuthToken,
} from './WebMountSecureStore.ets';
import { webMountOAuthTokenPost } from './WebMountRestrictedHttp.ets';

export interface WebMountHarImportResult { archiveId: string; entryCount: number; }
export interface WebMountOAuthRuntime {
  loadApplications: () => Promise<WebMountOAuthApplication[]>;
  saveApplication: (draft: WebMountOAuthApplicationDraft) => Promise<WebMountOAuthApplication>;
  readState: (siteId: string) => Promise<WebMountOAuthState>;
  subscribe: (siteId: string, listener: (state: WebMountOAuthState) => void) => (() => void);
  connect: (siteId: string, signal?: AbortSignalLike) => Promise<WebMountOAuthState>;
  refresh: (siteId: string, signal?: AbortSignalLike) => Promise<WebMountOAuthState>;
  cancel: (siteId: string) => void;
  cancelAll: () => void;
  disconnect: (siteId: string) => Promise<void>;
}

const APPS_KEY: string = 'webmount_oauth_applications';
const TOKEN_SKEW_MS: number = 60_000;

interface PendingAttempt {
  siteId: string;
  bindingHash: string;
  pkce: WebMountPkce;
  controller: AbortControllerLike;
  callback: GoogleCallbackAttempt;
}
interface AttemptFailure { code: string; message: string; }

const cryptoPort: WebMountCryptoPort = createWebMountCryptoPort();
const pendings: Map<string, PendingAttempt> = new Map();
const failures: Map<string, AttemptFailure> = new Map();
const listeners: Map<string, Set<(state: WebMountOAuthState) => void>> = new Map();
const harArchives: Map<string, WebMountHarArchive> = new Map();

// ===== 配置存取(KV 仅存公开配置与安全引用) =====

const decodeApplication = (raw: JsonValue): WebMountOAuthApplication | null => {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return null;
  const map: JsonObject = raw as JsonObject;
  const str = (key: string): string | null => typeof map[key] === 'string' ? map[key] as string : null;
  const siteId: string | null = str('siteId');
  const bindingHash: string | null = str('bindingHash');
  const authorizationEndpoint: string | null = str('authorizationEndpoint');
  const tokenEndpoint: string | null = str('tokenEndpoint');
  const clientId: string | null = str('clientId');
  const redirectUri: string | null = str('redirectUri');
  const scope: string | null = str('scope');
  const tokenEncoding: string | null = str('tokenEncoding');
  const clientAuthentication: string | null = str('clientAuthentication');
  const origins: JsonValue | undefined = map['apiOrigins'];
  if (siteId === null || bindingHash === null || authorizationEndpoint === null || tokenEndpoint === null
    || clientId === null || redirectUri === null || scope === null
    || (tokenEncoding !== 'form' && tokenEncoding !== 'json')
    || (clientAuthentication !== 'none' && clientAuthentication !== 'body' && clientAuthentication !== 'basic')
    || !Array.isArray(origins)) return null;
  const apiOrigins: string[] = [];
  for (const origin of origins) {
    if (typeof origin !== 'string') return null;
    apiOrigins.push(origin);
  }
  const secretRef: JsonValue | undefined = map['clientSecretRef'];
  return {
    siteId: siteId, bindingHash: bindingHash,
    authorizationEndpoint: authorizationEndpoint, tokenEndpoint: tokenEndpoint,
    clientId: clientId, redirectUri: redirectUri, scope: scope,
    tokenEncoding: tokenEncoding, clientAuthentication: clientAuthentication,
    apiOrigins: apiOrigins,
    clientSecretRef: typeof secretRef === 'string' ? secretRef : null,
  };
};

const loadApplicationMap = async (kv: KeyValueStore): Promise<Map<string, WebMountOAuthApplication>> => {
  const out: Map<string, WebMountOAuthApplication> = new Map();
  const raw: string | null = await kv.get(APPS_KEY);
  if (raw === null || raw.length === 0) return out;
  try {
    const parsed: JsonValue = JSON.parse(raw) as JsonValue;
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return out;
    const map: JsonObject = parsed as JsonObject;
    for (const siteId of Object.keys(map)) {
      const application: WebMountOAuthApplication | null = decodeApplication(map[siteId]);
      if (application !== null && application.siteId === siteId) out.set(siteId, application);
    }
  } catch (_error) {
    // 坏配置当空表;不据此发起任何授权
  }
  return out;
};

const encodeApplication = (application: WebMountOAuthApplication): JsonObject => {
  const origins: JsonValue[] = [];
  for (const origin of application.apiOrigins) origins.push(origin);
  return {
    'siteId': application.siteId,
    'bindingHash': application.bindingHash,
    'authorizationEndpoint': application.authorizationEndpoint,
    'tokenEndpoint': application.tokenEndpoint,
    'clientId': application.clientId,
    'redirectUri': application.redirectUri,
    'scope': application.scope,
    'tokenEncoding': application.tokenEncoding,
    'clientAuthentication': application.clientAuthentication,
    'apiOrigins': origins,
    'clientSecretRef': application.clientSecretRef,
  };
};

const saveApplicationMap = async (
  kv: KeyValueStore, applications: Map<string, WebMountOAuthApplication>,
): Promise<void> => {
  const payload: JsonObject = {};
  applications.forEach((application: WebMountOAuthApplication, siteId: string): void => {
    payload[siteId] = encodeApplication(application);
  });
  await kv.put(APPS_KEY, JSON.stringify(payload));
};

const loadApplication = async (siteId: string): Promise<WebMountOAuthApplication | null> =>
  (await loadApplicationMap(getChatKvStore())).get(siteId) ?? null;

// ===== 状态推导与通知 =====

const stateOf = (
  siteId: string, bindingHash: string | null, status: WebMountOAuthStatus,
  expiresAtMillis: number | null = null, errorCode: string | null = null, message: string | null = null,
): WebMountOAuthState => ({
  siteId: siteId, bindingHash: bindingHash, status: status,
  expiresAtMillis: expiresAtMillis, errorCode: errorCode, message: message,
});

const deriveState = async (siteId: string): Promise<WebMountOAuthState> => {
  const application: WebMountOAuthApplication | null = await loadApplication(siteId);
  if (application === null) return stateOf(siteId, null, 'unconfigured');
  if (pendings.has(siteId)) {
    return stateOf(application.siteId, application.bindingHash, 'awaiting_callback', null, null,
      '请在系统浏览器完成授权。');
  }
  const failure: AttemptFailure | undefined = failures.get(siteId);
  const token: WebMountOAuthToken | null = await readWebMountOAuthToken(siteId, application.bindingHash);
  if (token === null) {
    return failure !== undefined
      ? stateOf(application.siteId, application.bindingHash, 'failed', null, failure.code, failure.message)
      : stateOf(application.siteId, application.bindingHash, 'disconnected');
  }
  if (!webMountTokenUsable(token, Date.now(), TOKEN_SKEW_MS)) {
    return stateOf(application.siteId, application.bindingHash, 'expired', token.expiresAtMillis,
      failure?.code ?? null, failure?.message ?? null);
  }
  return stateOf(application.siteId, application.bindingHash, 'connected', token.expiresAtMillis);
};

const notify = (siteId: string): void => {
  const set: Set<(state: WebMountOAuthState) => void> | undefined = listeners.get(siteId);
  if (set === undefined || set.size === 0) return;
  deriveState(siteId).then((state: WebMountOAuthState): void => {
    set.forEach((listener: (state: WebMountOAuthState) => void): void => listener(state));
  }).catch((): void => {});
};

// ===== 系统浏览器 =====

const openSystemBrowser = async (target: string): Promise<void> => {
  const context: Context | undefined = AppStorage.get<Context>('abilityContext');
  if (context === undefined) throw new Error('webmount_oauth_context_unavailable');
  await (context as common.UIAbilityContext).startAbility({
    action: 'ohos.want.action.viewData', entities: ['entity.system.browsable'], uri: target,
  });
};

// ===== 连接完成(内部;状态可观察) =====

const failureCode = (error: Error): AttemptFailure => {
  if (error instanceof GoogleAuthError) {
    if (error.code === 'callback_timeout') return { code: 'oauth_timeout', message: '授权回调超时,请重试。' };
    if (error.code === 'access_denied') return { code: 'access_denied', message: '用户拒绝了授权。' };
    if (error.code === 'authorization_failed') return { code: 'authorization_failed', message: '站点授权未完成。' };
    if (error.code === 'callback_port_mismatch' || error.code === 'callback_unavailable') {
      return { code: 'callback_unavailable', message: '回调端口不可用,请检查配置。' };
    }
  }
  const message: string = error.message;
  if (message.startsWith('oauth_redirect_refused')) return { code: 'oauth_redirect_refused', message: 'token 端点返回重定向,已拒绝跟随。' };
  if (message.startsWith('oauth_binding_changed')) return { code: 'oauth_binding_changed', message: '站点配置已变更,本次授权作废。' };
  if (message.startsWith('oauth_secret_missing')) return { code: 'oauth_secret_missing', message: '客户端密钥引用失效,请重新配置。' };
  if (/invalid_grant/.test(message)) return { code: 'oauth_reconnect_required', message: '授权已失效,请重新连接。' };
  return { code: 'oauth_connect_failed', message: message.substring(0, 200) };
};

const completeConnect = async (pending: PendingAttempt, authorizationUrl: string): Promise<void> => {
  const siteId: string = pending.siteId;
  const finish = async (failure: AttemptFailure | null): Promise<void> => {
    pendings.delete(siteId);
    if (failure !== null) failures.set(siteId, failure);
    else failures.delete(siteId);
    try { await pending.callback.close(); } catch (_error) { /* close is best-effort */ }
    notify(siteId);
  };
  try {
    // 绑定监听后到打开前再核一次取消,已取消的尝试不再打开浏览器
    if (pending.controller.signal.aborted) throw new Error('cancelled');
    // 浏览器只在 native 监听真正绑定后打开
    await openSystemBrowser(authorizationUrl);
    const code: string = await pending.callback.code;
    if (pending.controller.signal.aborted) throw new Error('cancelled');
    // 兑换前重核:配置必须仍是发起时的绑定
    const fresh: WebMountOAuthApplication | null = await loadApplication(siteId);
    if (fresh === null || fresh.bindingHash !== pending.bindingHash) {
      throw new Error('oauth_binding_changed');
    }
    let secret: string | null = null;
    if (fresh.clientAuthentication !== 'none') {
      if (fresh.clientSecretRef === null) throw new Error('oauth_secret_missing');
      secret = await loadWebMountSecret(siteId, fresh.clientSecretRef);
      if (secret === null) throw new Error('oauth_secret_missing');
    }
    const spec = buildWebMountTokenRequest(fresh, secret, {
      grantType: 'authorization_code', code: code, codeVerifier: pending.pkce.codeVerifier, refreshToken: null,
    });
    const response = await webMountOAuthTokenPost(
      spec.url, spec.contentType, spec.body, spec.authorizationHeader, 30000, pending.controller.signal);
    const token: WebMountOAuthToken = parseWebMountTokenResponse(response.text, Date.now());
    // 提交前再核一次绑定
    const again: WebMountOAuthApplication | null = await loadApplication(siteId);
    if (again === null || again.bindingHash !== pending.bindingHash) throw new Error('oauth_binding_changed');
    if (pending.controller.signal.aborted) throw new Error('cancelled');
    await writeWebMountOAuthToken(siteId, pending.bindingHash, token);
    await finish(null);
  } catch (error) {
    const err: Error = error instanceof Error ? error : new Error(String(error));
    if (err.name === 'AbortError' || err.message === 'cancelled'
      || (err instanceof GoogleAuthError && err.code === 'cancelled')) {
      // 取消不是失败:回到由 token 推导的状态
      pendings.delete(siteId);
      try { await pending.callback.close(); } catch (_error) { /* best-effort */ }
      notify(siteId);
      return;
    }
    await finish(failureCode(err));
  }
};

// arkts-no-spread:接口不可对象展开;显式逐字段重建
const withSecretAndBinding = (
  application: WebMountOAuthApplication, clientSecretRef: string | null, bindingHash: string,
): WebMountOAuthApplication => ({
  siteId: application.siteId,
  bindingHash: bindingHash,
  authorizationEndpoint: application.authorizationEndpoint,
  tokenEndpoint: application.tokenEndpoint,
  clientId: application.clientId,
  redirectUri: application.redirectUri,
  scope: application.scope,
  tokenEncoding: application.tokenEncoding,
  clientAuthentication: application.clientAuthentication,
  apiOrigins: application.apiOrigins,
  clientSecretRef: clientSecretRef,
});

// ===== Runtime 单例 =====

class WebMountOAuthRuntimeImpl implements WebMountOAuthRuntime {
  public async loadApplications(): Promise<WebMountOAuthApplication[]> {
    const map: Map<string, WebMountOAuthApplication> = await loadApplicationMap(getChatKvStore());
    const out: WebMountOAuthApplication[] = [];
    map.forEach((application: WebMountOAuthApplication): void => { out.push(application); });
    return out;
  }

  public async saveApplication(draft: WebMountOAuthApplicationDraft): Promise<WebMountOAuthApplication> {
    const validated: WebMountOAuthApplication = validateWebMountOAuthApplication(draft.application);
    const siteId: string = validated.siteId;
    const previous: WebMountOAuthApplication | null = await loadApplication(siteId);
    let secretRef: string | null = validated.clientSecretRef;
    if (validated.clientAuthentication === 'none') {
      secretRef = null;
    } else if (draft.secretAction === 'replace') {
      if (draft.clientSecret === null || draft.clientSecret.trim().length === 0) {
        throw new Error('webmount_oauth_secret_required');
      }
      // 先写新安全引用,配置提交失败时回收;旧引用在提交成功后才删除
      secretRef = await stageWebMountSecret(siteId, draft.clientSecret.trim());
    } else if (draft.secretAction === 'clear') {
      throw new Error('webmount_oauth_secret_required');
    } else {
      secretRef = previous?.clientSecretRef ?? null;
      if (secretRef === null) throw new Error('webmount_oauth_secret_required');
    }
    const stagedNew: boolean = draft.secretAction === 'replace' && secretRef !== previous?.clientSecretRef;
    const bound: WebMountOAuthApplication = withSecretAndBinding(validated, secretRef, '');
    const bindingHash: string = await cryptoPort.sha256Base64Url(
      canonicalWebMountOAuthBinding(bound, secretRef));
    const saved: WebMountOAuthApplication = withSecretAndBinding(validated, secretRef, bindingHash);
    try {
      const kv: KeyValueStore = getChatKvStore();
      const map: Map<string, WebMountOAuthApplication> = await loadApplicationMap(kv);
      map.set(siteId, saved);
      await saveApplicationMap(kv, map);
    } catch (error) {
      if (stagedNew && secretRef !== null) {
        try { await deleteWebMountSecret(siteId, secretRef); } catch (_cleanup) { /* best-effort */ }
      }
      throw error as Error;
    }
    if (previous !== null && previous.bindingHash !== bindingHash) {
      // 配置换绑定:作废旧 pending 与旧绑定 token/不再引用的旧 secret
      this.cancel(siteId);
      try { await removeWebMountOAuthToken(siteId, previous.bindingHash); } catch (_error) { /* best-effort */ }
      if (previous.clientSecretRef !== null && previous.clientSecretRef !== secretRef) {
        try { await deleteWebMountSecret(siteId, previous.clientSecretRef); } catch (_error) { /* best-effort */ }
      }
    }
    failures.delete(siteId);
    notify(siteId);
    return saved;
  }

  public readState(siteId: string): Promise<WebMountOAuthState> {
    return deriveState(siteId);
  }

  public subscribe(siteId: string, listener: (state: WebMountOAuthState) => void): () => void {
    let set: Set<(state: WebMountOAuthState) => void> | undefined = listeners.get(siteId);
    if (set === undefined) {
      set = new Set();
      listeners.set(siteId, set);
    }
    set.add(listener);
    return (): void => {
      const current: Set<(state: WebMountOAuthState) => void> | undefined = listeners.get(siteId);
      if (current !== undefined) current.delete(listener);
    };
  }

  public async connect(siteId: string, signal?: AbortSignalLike): Promise<WebMountOAuthState> {
    const application: WebMountOAuthApplication | null = await loadApplication(siteId);
    if (application === null) return stateOf(siteId, null, 'unconfigured');
    if (pendings.has(siteId)) {
      return stateOf(siteId, application.bindingHash, 'awaiting_callback', null, null, '请在系统浏览器完成授权。');
    }
    const controller: AbortControllerLike = createEntryAbortController();
    if (signal !== undefined) {
      if (signal.aborted) return stateOf(siteId, application.bindingHash, 'disconnected');
      signal.addEventListener?.('abort', (): void => controller.abort());
    }
    try {
      const pkce: WebMountPkce = await createWebMountPkce(cryptoPort);
      const redirect: GoogleRedirect = parseGoogleRedirect(application.redirectUri);
      // 先绑定 native 监听,再开浏览器
      const callback: GoogleCallbackAttempt = await startGoogleOAuthCallback(redirect, pkce.state, controller.signal);
      const authorizationUrl: string = buildWebMountAuthorizationUrl(application, {
        state: pkce.state, codeChallenge: pkce.codeChallenge,
      });
      const pending: PendingAttempt = {
        siteId: siteId, bindingHash: application.bindingHash, pkce: pkce,
        controller: controller, callback: callback,
      };
      pendings.set(siteId, pending);
      notify(siteId);
      const completion: Promise<void> = completeConnect(pending, authorizationUrl);
      completion.catch((): void => {});
      return stateOf(siteId, application.bindingHash, 'awaiting_callback', null, null, '请在系统浏览器完成授权。');
    } catch (error) {
      controller.abort();
      const err: Error = error instanceof Error ? error : new Error(String(error));
      failures.set(siteId, failureCode(err));
      notify(siteId);
      return deriveState(siteId);
    }
  }

  public async refresh(siteId: string, signal?: AbortSignalLike): Promise<WebMountOAuthState> {
    const application: WebMountOAuthApplication | null = await loadApplication(siteId);
    if (application === null) return stateOf(siteId, null, 'unconfigured');
    const token: WebMountOAuthToken | null = await readWebMountOAuthToken(siteId, application.bindingHash);
    if (token === null) return stateOf(siteId, application.bindingHash, 'disconnected');
    if (token.refreshToken === null) {
      failures.set(siteId, { code: 'oauth_reconnect_required', message: '该授权没有刷新令牌,请重新连接。' });
      notify(siteId);
      return deriveState(siteId);
    }
    try {
      let secret: string | null = null;
      if (application.clientAuthentication !== 'none') {
        if (application.clientSecretRef === null) throw new Error('oauth_secret_missing');
        secret = await loadWebMountSecret(siteId, application.clientSecretRef);
        if (secret === null) throw new Error('oauth_secret_missing');
      }
      const spec = buildWebMountTokenRequest(application, secret, {
        grantType: 'refresh_token', code: null, codeVerifier: null, refreshToken: token.refreshToken,
      });
      const response = await webMountOAuthTokenPost(
        spec.url, spec.contentType, spec.body, spec.authorizationHeader, 30000, signal);
      const next: WebMountOAuthToken = parseWebMountTokenResponse(response.text, Date.now());
      const rotated: WebMountOAuthToken = {
        accessToken: next.accessToken,
        refreshToken: next.refreshToken !== null ? next.refreshToken : token.refreshToken,
        expiresAtMillis: next.expiresAtMillis, tokenType: next.tokenType,
        scope: next.scope !== null ? next.scope : token.scope,
      };
      // 轮换成功才替换旧 token;提交前重核绑定
      const again: WebMountOAuthApplication | null = await loadApplication(siteId);
      if (again === null || again.bindingHash !== application.bindingHash) throw new Error('oauth_binding_changed');
      await writeWebMountOAuthToken(siteId, application.bindingHash, rotated);
      failures.delete(siteId);
    } catch (error) {
      const err: Error = error instanceof Error ? error : new Error(String(error));
      if (err.name !== 'AbortError') failures.set(siteId, failureCode(err));
    }
    notify(siteId);
    return deriveState(siteId);
  }

  public cancel(siteId: string): void {
    const pending: PendingAttempt | undefined = pendings.get(siteId);
    if (pending !== undefined) pending.controller.abort();
  }

  public cancelAll(): void {
    pendings.forEach((pending: PendingAttempt): void => pending.controller.abort());
  }

  public async disconnect(siteId: string): Promise<void> {
    this.cancel(siteId);
    const application: WebMountOAuthApplication | null = await loadApplication(siteId);
    if (application !== null) {
      try { await removeWebMountOAuthToken(siteId, application.bindingHash); } catch (_error) { /* best-effort */ }
    }
    failures.delete(siteId);
    notify(siteId);
  }
}

let runtime: WebMountOAuthRuntime | null = null;

export const getWebMountOAuthRuntime = (): WebMountOAuthRuntime => {
  if (runtime === null) runtime = new WebMountOAuthRuntimeImpl();
  return runtime;
};

// ===== HAR 导入(设置页文件选择器入口;原始 archive 只在宿主内存) =====

export const importWebMountHar = async (text: string): Promise<WebMountHarImportResult> => {
  const archiveId: string = newId();
  const archive: WebMountHarArchive = parseWebMountHar(text, archiveId, Date.now());
  harArchives.set(archiveId, archive);
  const log: JsonObject = archive.har['log'] as JsonObject;
  const entries: JsonValue | undefined = log['entries'];
  return { archiveId: archiveId, entryCount: Array.isArray(entries) ? entries.length : 0 };
};

export const listWebMountHarArchives = (): WebMountHarArchive[] => {
  const out: WebMountHarArchive[] = [];
  harArchives.forEach((archive: WebMountHarArchive): void => { out.push(archive); });
  return out;
};

export const removeWebMountHarArchive = (archiveId: string): boolean => harArchives.delete(archiveId);

// Bearer 消费者用:当前绑定下可用的 access token(过期则先尝试刷新)
export const loadWebMountUsableAccessToken = async (siteId: string): Promise<WebMountOAuthToken | null> => {
  const application: WebMountOAuthApplication | null = await loadApplication(siteId);
  if (application === null) return null;
  const token: WebMountOAuthToken | null = await readWebMountOAuthToken(siteId, application.bindingHash);
  if (token !== null && webMountTokenUsable(token, Date.now(), TOKEN_SKEW_MS)) return token;
  if (token !== null && token.refreshToken !== null) {
    await getWebMountOAuthRuntime().refresh(siteId);
    const applicationAgain: WebMountOAuthApplication | null = await loadApplication(siteId);
    if (applicationAgain === null || applicationAgain.bindingHash !== application.bindingHash) return null;
    const refreshed: WebMountOAuthToken | null = await readWebMountOAuthToken(siteId, application.bindingHash);
    return refreshed !== null && webMountTokenUsable(refreshed, Date.now(), TOKEN_SKEW_MS) ? refreshed : null;
  }
  return null;
};

// 供 WebMountAdvancedTools 核 origin:应用存在且 URL 落在已配置 API origin
export const loadWebMountOAuthApplication = loadApplication;
