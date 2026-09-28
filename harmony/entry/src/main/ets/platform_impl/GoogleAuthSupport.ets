import { GoogleAuthError, googleOAuthBinding } from '@amber/chat-domain';
import type { ProviderSettingGoogle, GoogleOAuthBinding, GoogleOAuthTokens, GoogleOAuthMode,
  GoogleAuthStatus, GoogleLoginAttempt, GoogleRequestAuth, GoogleModelCatalog } from '@amber/chat-domain';
import type { AbortSignalLike, HttpClient } from '@amber/deepread-domain';
import { EntryAbortController } from './EntryAbortController.ets';
import { readGoogleOAuthTokens, writeGoogleOAuthTokens, removeGoogleOAuthTokens,
  removeGoogleOAuthTokensForProvider } from './GoogleSecureStore.ets';
import { GoogleServiceAccountToken } from './GoogleServiceAccountToken.ets';
import { buildGoogleAuthorizationUrl, exchangeGoogleAuthorizationCode, refreshGoogleOAuthTokens,
  onboardGoogleOAuthTokens, loadGoogleOAuthCatalog } from './GoogleOAuthClient.ets';
import { parseGoogleRedirect, startGoogleOAuthCallback } from './GoogleOAuthCallback.ets';
import type { GoogleRedirect, GoogleCallbackAttempt } from './GoogleOAuthCallback.ets';
import { GoogleAuthFlight, googleRandom, googleSha256, checkGoogleAbort, googleCancelled } from './GoogleAuthHttp.ets';

export { stageGoogleSecret, loadGoogleSecret, deleteGoogleSecret } from './GoogleSecureStore.ets';

interface OAuthState {
  binding: GoogleOAuthBinding;
  generation: number;
  loaded: boolean;
  tokens: GoogleOAuthTokens | null;
  writeTail: Promise<void>;
  refresh: GoogleAuthFlight<GoogleOAuthTokens> | null;
  onboarding: GoogleAuthFlight<GoogleOAuthTokens> | null;
  controller: EntryAbortController | null;
  callback: GoogleCallbackAttempt | null;
  status: GoogleAuthStatus;
}

type GoogleStatusPhase = 'unconfigured' | 'configured' | 'signed_out' | 'authorizing' | 'onboarding_required' | 'ready' | 'error';

const statusOf = (
  mode: GoogleOAuthMode | 'service_account' | 'api_key', phase: GoogleStatusPhase, message: string,
  tokens: GoogleOAuthTokens | null = null, validationUrl: string | null = null,
): GoogleAuthStatus => ({ mode, phase, message, projectId: tokens?.projectId ?? '', tierId: tokens?.tierId ?? '',
  expiresAt: tokens?.expiresAt ?? 0, validationUrl });

const stateKey = (providerId: string, mode: GoogleOAuthMode): string => JSON.stringify([providerId, mode]);
const sameBinding = (left: GoogleOAuthBinding, right: GoogleOAuthBinding): boolean =>
  left.providerId === right.providerId && left.mode === right.mode && left.clientId === right.clientId
  && left.redirectUri === right.redirectUri && left.clientSecretRef === right.clientSecretRef;

export class EntryGoogleAuthRuntime {
  public readonly http: HttpClient;
  private readonly serviceAccount: GoogleServiceAccountToken;
  private readonly states: Map<string, OAuthState> = new Map<string, OAuthState>();

  constructor(http: HttpClient) { this.http = http; this.serviceAccount = new GoogleServiceAccountToken(http); }

  private invalidate(state: OAuthState): number {
    state.generation++;
    state.controller?.abort();
    state.refresh?.cancel();
    state.onboarding?.cancel();
    state.controller = null;
    state.refresh = null;
    state.onboarding = null;
    return state.generation;
  }

  private state(setting: ProviderSettingGoogle, replace: boolean = false): OAuthState {
    const binding: GoogleOAuthBinding = googleOAuthBinding(setting);
    const key: string = stateKey(binding.providerId, binding.mode);
    const current: OAuthState | undefined = this.states.get(key);
    if (current !== undefined && sameBinding(current.binding, binding)) return current;
    if (current !== undefined && !replace) {
      throw new GoogleAuthError('credential_changed', 'Google 客户端配置已改变，请使用已保存的新配置。');
    }
    if (current !== undefined) this.invalidate(current);
    const writeTail: Promise<void> = current === undefined ? Promise.resolve() : current.writeTail.then(
      async (): Promise<void> => { await current.callback?.close(); });
    const next: OAuthState = { binding, generation: 0, loaded: false, tokens: null,
      writeTail, refresh: null, onboarding: null,
      controller: null, callback: null, status: statusOf(binding.mode, 'signed_out', '尚未登录 Google。') };
    this.states.set(key, next);
    return next;
  }

  private checkCurrent(state: OAuthState, generation: number, signal?: AbortSignalLike): void {
    checkGoogleAbort(signal);
    if (state.generation !== generation || this.states.get(stateKey(state.binding.providerId, state.binding.mode)) !== state) {
      throw googleCancelled();
    }
  }

  private queueWrite(state: OAuthState, generation: number, write: () => Promise<void>): Promise<void> {
    const operation: Promise<void> = state.writeTail.then(async (): Promise<void> => {
      this.checkCurrent(state, generation);
      await write();
      this.checkCurrent(state, generation);
    });
    state.writeTail = operation.catch((): void => {});
    return operation;
  }

  private async persist(state: OAuthState, generation: number, tokens: GoogleOAuthTokens): Promise<void> {
    await this.queueWrite(state, generation, (): Promise<void> => writeGoogleOAuthTokens(state.binding, tokens));
    this.checkCurrent(state, generation);
    state.tokens = tokens;
    state.loaded = true;
  }

  private async read(state: OAuthState, generation: number): Promise<GoogleOAuthTokens | null> {
    await state.writeTail;
    this.checkCurrent(state, generation);
    if (!state.loaded) {
      const tokens: GoogleOAuthTokens | null = await readGoogleOAuthTokens(state.binding);
      this.checkCurrent(state, generation);
      state.tokens = tokens;
      state.loaded = true;
    }
    return state.tokens;
  }

  private async clear(state: OAuthState, generation: number): Promise<void> {
    await this.queueWrite(state, generation, (): Promise<void> => removeGoogleOAuthTokens(state.binding));
    this.checkCurrent(state, generation);
    state.tokens = null;
    state.loaded = true;
  }

  private cancelLogin(state: OAuthState, generation: number, controller: EntryAbortController): void {
    if (state.generation !== generation || state.controller !== controller) return;
    const next: number = this.invalidate(state);
    state.tokens = null;
    state.loaded = true;
    state.status = statusOf(state.binding.mode, 'signed_out', 'Google 登录已取消。');
    this.clear(state, next).catch((): void => {
      if (state.generation === next) state.status = statusOf(state.binding.mode, 'error', 'Google 登录已取消，但安全凭据清理失败，请重试退出登录。');
    });
  }

  private validateOAuth(setting: ProviderSettingGoogle): GoogleRedirect {
    if (setting.oauthClientId.trim().length === 0) throw new GoogleAuthError('client_unconfigured', '请先保存你自己的 OAuth 客户端 ID 与回调地址。');
    return parseGoogleRedirect(setting.oauthRedirectUri);
  }

  private async tokens(state: OAuthState, generation: number, signal?: AbortSignalLike): Promise<GoogleOAuthTokens> {
    this.checkCurrent(state, generation, signal);
    const stored: GoogleOAuthTokens | null = await this.read(state, generation);
    this.checkCurrent(state, generation, signal);
    if (stored === null) throw new GoogleAuthError('login_required', '请先登录 Google。');
    if (stored.expiresAt > Date.now() + 60000) return stored;
    if (state.refresh === null) {
      const flight: GoogleAuthFlight<GoogleOAuthTokens> = new GoogleAuthFlight<GoogleOAuthTokens>(
        async (sharedSignal: AbortSignalLike): Promise<GoogleOAuthTokens> => {
          try {
            const refreshed: GoogleOAuthTokens = await refreshGoogleOAuthTokens(this.http, state.binding, stored, sharedSignal);
            this.checkCurrent(state, generation, sharedSignal);
            await this.persist(state, generation, refreshed);
            this.checkCurrent(state, generation, sharedSignal);
            return refreshed;
          } catch (error) {
            this.checkCurrent(state, generation, sharedSignal);
            if (error instanceof GoogleAuthError && error.code === 'invalid_grant') {
              await this.clear(state, generation);
              state.status = statusOf(state.binding.mode, 'signed_out', error.message);
            }
            throw (error as Error);
          }
        });
      state.refresh = flight;
      flight.onSettled((): void => { if (state.refresh === flight) state.refresh = null; });
    }
    const refreshed: GoogleOAuthTokens = await state.refresh.wait(signal);
    this.checkCurrent(state, generation, signal);
    return refreshed;
  }

  async status(setting: ProviderSettingGoogle): Promise<GoogleAuthStatus> {
    if (setting.vertexAI && setting.useServiceAccount) {
      try {
        const cached: GoogleOAuthTokens | null = await this.serviceAccount.cached(setting);
        return statusOf('service_account', cached === null ? 'configured' : 'ready',
          cached === null ? '服务账号已配置，首次请求将验证凭据。' : '服务账号令牌有效。', cached);
      } catch { return statusOf('service_account', 'unconfigured', '请配置有效的服务账号邮箱、私钥、项目与区域。'); }
    }
    if (setting.authMode === 'api_key') return statusOf('api_key', setting.apiKey.trim().length > 0 ? 'configured' : 'unconfigured',
      setting.apiKey.trim().length > 0 ? 'API Key 已配置。' : '请配置 API Key。');
    try { this.validateOAuth(setting); }
    catch (error) { return statusOf(setting.authMode, 'unconfigured', (error as Error).message); }
    const state: OAuthState = this.state(setting);
    const generation: number = state.generation;
    if (state.controller !== null) return state.status;
    try {
      const tokens: GoogleOAuthTokens | null = await this.read(state, generation);
      if (tokens === null) return state.status.phase === 'error' ? state.status : statusOf(state.binding.mode, 'signed_out', '尚未登录 Google。');
      if (tokens.projectId === null) return statusOf(state.binding.mode, 'onboarding_required',
        state.status.phase === 'onboarding_required' ? state.status.message : '令牌已保存，请完成 Google 项目初始化。', tokens, state.status.validationUrl);
      if (tokens.expiresAt <= Date.now() + 60000 && tokens.refreshToken === null) return statusOf(state.binding.mode, 'signed_out', '令牌已过期，请重新登录。');
      if (tokens.expiresAt <= Date.now() + 60000) return statusOf(state.binding.mode, 'configured', '登录已保存，下一次请求将刷新令牌。', tokens);
      return statusOf(state.binding.mode, 'ready', 'Google 令牌与项目已就绪。', tokens);
    } catch (error) {
      if (error instanceof GoogleAuthError && error.code === 'cancelled') throw error;
      return statusOf(state.binding.mode, 'error', '无法读取 Google 安全凭据，请检查后重试。');
    }
  }

  async begin(setting: ProviderSettingGoogle): Promise<GoogleLoginAttempt> {
    const redirect: GoogleRedirect = this.validateOAuth(setting);
    const state: OAuthState = this.state(setting, true);
    const generation: number = this.invalidate(state);
    await state.callback?.close();
    state.callback = null;
    await this.clear(state, generation);
    const verifier: string = googleRandom();
    const expectedState: string = googleRandom();
    const authorizationUrl: string = buildGoogleAuthorizationUrl(state.binding, expectedState, googleSha256(verifier));
    const controller: EntryAbortController = new EntryAbortController();
    state.controller = controller;
    state.status = statusOf(state.binding.mode, 'authorizing', '请在系统浏览器完成 Google 登录。');
    let callback: GoogleCallbackAttempt;
    try {
      callback = await startGoogleOAuthCallback(redirect, expectedState, controller.signal);
      this.checkCurrent(state, generation, controller.signal);
      state.callback = callback;
    } catch (error) {
      if (state.generation === generation) {
        state.controller = null;
        state.status = statusOf(state.binding.mode, 'error', 'Google 回调监听未能启动，请检查端口配置。');
      }
      throw (error as Error);
    }
    const completion: Promise<GoogleAuthStatus> = this.completeLogin(setting, state, generation, controller, callback, verifier);
    completion.catch((): void => {});
    return { authorizationUrl,
      completion, cancel: (): void => this.cancelLogin(state, generation, controller) };
  }

  private async completeLogin(
    setting: ProviderSettingGoogle, state: OAuthState, generation: number, controller: EntryAbortController,
    callback: GoogleCallbackAttempt, verifier: string,
  ): Promise<GoogleAuthStatus> {
    const timer: number = setTimeout((): void => this.cancelLogin(state, generation, controller), 180000);
    try {
      const code: string = await callback.code;
      this.checkCurrent(state, generation, controller.signal);
      const tokens: GoogleOAuthTokens = await exchangeGoogleAuthorizationCode(this.http, state.binding, code, verifier, controller.signal);
      this.checkCurrent(state, generation, controller.signal);
      await this.persist(state, generation, tokens);
      const status: GoogleAuthStatus = await this.onboard(setting, state, generation, controller.signal);
      this.checkCurrent(state, generation, controller.signal);
      state.status = status;
      return status;
    } catch (error) {
      this.checkCurrent(state, generation);
      const status: GoogleAuthStatus = error instanceof GoogleAuthError && error.code === 'cancelled'
        ? statusOf(state.binding.mode, state.tokens === null ? 'signed_out' : 'onboarding_required', 'Google 登录已取消。', state.tokens)
        : statusOf(state.binding.mode, 'error', error instanceof GoogleAuthError ? error.message : 'Google 登录失败，请重试。');
      state.status = status;
      return status;
    } finally {
      clearTimeout(timer);
      await callback.close();
      if (state.generation === generation) { state.controller = null; state.callback = null; }
    }
  }

  private async onboard(
    setting: ProviderSettingGoogle, state: OAuthState, generation: number, signal?: AbortSignalLike,
  ): Promise<GoogleAuthStatus> {
    const tokens: GoogleOAuthTokens = await this.tokens(state, generation, signal);
    if (state.onboarding === null) {
      const flight: GoogleAuthFlight<GoogleOAuthTokens> = new GoogleAuthFlight<GoogleOAuthTokens>(
        async (sharedSignal: AbortSignalLike): Promise<GoogleOAuthTokens> => {
          const result: GoogleOAuthTokens = await onboardGoogleOAuthTokens(this.http, state.binding, tokens, setting.projectId, sharedSignal);
          this.checkCurrent(state, generation, sharedSignal);
          await this.persist(state, generation, result);
          this.checkCurrent(state, generation, sharedSignal);
          return result;
        });
      state.onboarding = flight;
      flight.onSettled((): void => { if (state.onboarding === flight) state.onboarding = null; });
    }
    try {
      const result: GoogleOAuthTokens = await state.onboarding.wait(signal);
      this.checkCurrent(state, generation, signal);
      return statusOf(state.binding.mode, 'ready', 'Google 令牌与项目已就绪。', result);
    } catch (error) {
      this.checkCurrent(state, generation, signal);
      return statusOf(state.binding.mode, 'onboarding_required', error instanceof GoogleAuthError
        ? error.message : 'Google 项目初始化失败，请重试。', state.tokens,
        error instanceof GoogleAuthError ? error.validationUrl : null);
    }
  }

  async retry(setting: ProviderSettingGoogle, signal?: AbortSignalLike): Promise<GoogleAuthStatus> {
    this.validateOAuth(setting);
    const state: OAuthState = this.state(setting);
    const generation: number = state.generation;
    const status: GoogleAuthStatus = await this.onboard(setting, state, generation, signal);
    this.checkCurrent(state, generation, signal);
    state.status = status;
    return status;
  }

  async configure(setting: ProviderSettingGoogle): Promise<void> {
    if (setting.vertexAI && setting.useServiceAccount || setting.authMode === 'api_key') return;
    const state: OAuthState = this.state(setting, true);
    await state.writeTail;
    this.checkCurrent(state, state.generation);
  }

  async resolve(setting: ProviderSettingGoogle, signal?: AbortSignalLike): Promise<GoogleRequestAuth> {
    if (setting.vertexAI && setting.useServiceAccount) return this.serviceAccount.resolve(setting, signal);
    this.validateOAuth(setting);
    const state: OAuthState = this.state(setting);
    const generation: number = state.generation;
    const tokens: GoogleOAuthTokens = await this.tokens(state, generation, signal);
    if (tokens.projectId === null) throw new GoogleAuthError('onboarding_required', 'Google 项目尚未就绪，请在提供商设置中重试项目初始化。', state.status.validationUrl);
    return { kind: state.binding.mode === 'antigravity_oauth' ? 'antigravity' : 'code_assist',
      accessToken: tokens.accessToken, projectId: tokens.projectId, location: setting.location };
  }

  async logout(setting: ProviderSettingGoogle): Promise<void> {
    if (setting.vertexAI && setting.useServiceAccount) { this.serviceAccount.forget(setting.id); return; }
    if (setting.authMode === 'api_key') return;
    const binding: GoogleOAuthBinding = googleOAuthBinding(setting);
    const state: OAuthState | undefined = this.states.get(stateKey(binding.providerId, binding.mode));
    if (state === undefined) { await removeGoogleOAuthTokens(binding); return; }
    if (!sameBinding(state.binding, binding)) {
      const cleanup: Promise<void> = state.writeTail.then((): Promise<void> => removeGoogleOAuthTokens(binding));
      state.writeTail = cleanup.catch((): void => {});
      await cleanup;
      return;
    }
    const generation: number = this.invalidate(state);
    await state.callback?.close();
    state.callback = null;
    await this.clear(state, generation);
    this.checkCurrent(state, generation);
    state.status = statusOf(state.binding.mode, 'signed_out', '已退出 Google 登录。');
  }

  async forget(setting: ProviderSettingGoogle): Promise<void> {
    const modes: GoogleOAuthMode[] = ['gemini_code_assist_oauth', 'antigravity_oauth'];
    const pending: Promise<void>[] = [];
    for (const mode of modes) {
      const key: string = stateKey(setting.id, mode);
      const state: OAuthState | undefined = this.states.get(key);
      if (state === undefined) continue;
      this.invalidate(state);
      if (state.callback !== null) pending.push(state.callback.close());
      pending.push(state.writeTail);
      this.states.delete(key);
    }
    this.serviceAccount.forget(setting.id);
    await Promise.all(pending);
    await removeGoogleOAuthTokensForProvider(setting.id);
  }

  async catalog(setting: ProviderSettingGoogle, signal?: AbortSignalLike): Promise<GoogleModelCatalog> {
    if (setting.vertexAI && setting.useServiceAccount) return this.serviceAccount.catalog(setting, signal);
    if (setting.authMode === 'api_key') return { supported: false, models: [], message: '请使用 API Key 模型列表入口。' };
    if (setting.authMode === 'gemini_code_assist_oauth') return { supported: false, models: [],
      message: 'Code Assist 未提供模型目录接口，请手动填写有权使用的模型 ID。' };
    const state: OAuthState = this.state(setting);
    await this.resolve(setting, signal);
    const tokens: GoogleOAuthTokens = await this.tokens(state, state.generation, signal);
    return loadGoogleOAuthCatalog(this.http, state.binding, tokens, signal);
  }
}

let entryRuntime: EntryGoogleAuthRuntime | null = null;

export const initializeEntryGoogleAuth = (http: HttpClient): void => {
  if (entryRuntime === null) { entryRuntime = new EntryGoogleAuthRuntime(http); return; }
  if (entryRuntime.http !== http) throw new Error('Google auth is already initialized with a different HTTP client');
};

const runtime = (): EntryGoogleAuthRuntime => {
  if (entryRuntime === null) throw new GoogleAuthError('runtime_unavailable', 'Google 授权服务尚未初始化。');
  return entryRuntime;
};

export const resolveEntryGoogleAuth = (setting: ProviderSettingGoogle, signal?: AbortSignalLike): Promise<GoogleRequestAuth> => runtime().resolve(setting, signal);
export const loadGoogleAuthStatus = (setting: ProviderSettingGoogle): Promise<GoogleAuthStatus> => runtime().status(setting);
export const beginGoogleLogin = (setting: ProviderSettingGoogle): Promise<GoogleLoginAttempt> => runtime().begin(setting);
export const retryGoogleOnboarding = (setting: ProviderSettingGoogle, signal?: AbortSignalLike): Promise<GoogleAuthStatus> => runtime().retry(setting, signal);
export const logoutGoogle = (setting: ProviderSettingGoogle): Promise<void> => runtime().logout(setting);
export const forgetGoogleProvider = (setting: ProviderSettingGoogle): Promise<void> => runtime().forget(setting);
export const updateGoogleAuthConfiguration = (setting: ProviderSettingGoogle): Promise<void> => runtime().configure(setting);
export const loadGoogleModelCatalog = (setting: ProviderSettingGoogle, signal?: AbortSignalLike): Promise<GoogleModelCatalog> => runtime().catalog(setting, signal);
