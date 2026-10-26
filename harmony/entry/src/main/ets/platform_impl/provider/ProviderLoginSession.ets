import type { ProviderSetting, ProviderSettingOpenAIVariant, OpenAIAuthMode } from '@amber/chat-domain';
import { openAIBrandAvailableAuthModes, openAIAuthModeFixedBaseUrl } from '@amber/chat-domain';
import type { common } from '@kit.AbilityKit';
import { promptAction } from '@kit.ArkUI';
import { pasteboard } from '@kit.BasicServicesKit';
import type { CodexDeviceAuthorization, CodexModelItem, CodexTokens } from '../CodexOAuthClient.ets';
import type { GrokAuthorization, GrokModelItem, GrokTokens } from '../GrokOAuthClient.ets';

export interface ProviderLoginContext {
  provider: ProviderSetting | null;
  authMode: string;
  baseUrl: string;
}
export interface ProviderLoginPorts {
  currentContext: () => ProviderLoginContext;
  setAuthMode: (mode: string) => void;
  setApiKey: (value: string) => void;
  setBaseUrl: (value: string) => void;
  setUseResponseApi: (value: boolean) => void;
  setModelsText: (value: string) => void;
  setUnsupportedAuth: (message: string) => void;
  save: () => Promise<boolean>;
  abilityContext: () => common.UIAbilityContext;
  requestCodexDeviceCode: () => Promise<CodexDeviceAuthorization>;
  pollCodexDeviceCode: (providerId: string, auth: CodexDeviceAuthorization, cancelled: () => boolean) => Promise<CodexTokens>;
  fetchCodexModels: (providerId: string) => Promise<CodexModelItem[]>;
  loadCodexTokens: (providerId: string) => Promise<CodexTokens | null>;
  loadGrokTokens: (providerId: string) => Promise<GrokTokens | null>;
  buildGrokAuthorization: () => GrokAuthorization;
  extractGrokCode: (paste: string) => string;
  exchangeGrokCode: (providerId: string, code: string, verifier: string, cancelled: () => boolean) => Promise<GrokTokens>;
  grokFallbackModels: GrokModelItem[];
}

@Observed
export class ProviderLoginSession {
  codexLoginOpen: boolean = false;
  codexUserCode: string = '';
  codexStatus: string = '';
  codexBusy: boolean = false;
  codexAccount: string = '';
  grokLoginOpen: boolean = false;
  grokPasteUrl: string = '';
  grokStatus: string = '';
  grokBusy: boolean = false;
  grokAccount: string = '';
  private codexAuth: CodexDeviceAuthorization | null = null;
  private codexCancelled: boolean = false;
  private grokAuth: GrokAuthorization | null = null;
  private pageAlive: boolean = false;
  private loginSequence: number = 0;
  private ports: ProviderLoginPorts | null = null;
  private get deps(): ProviderLoginPorts { return this.ports as ProviderLoginPorts; }
  private get provider(): ProviderSetting | null { return this.deps.currentContext().provider; }
  private get authMode(): string { return this.deps.currentContext().authMode; }
  private set authMode(value: string) { this.deps.setAuthMode(value); }
  private get baseUrl(): string { return this.deps.currentContext().baseUrl; }
  private set baseUrl(value: string) { this.deps.setBaseUrl(value); }
  private set apiKey(value: string) { this.deps.setApiKey(value); }
  private set useResponseApi(value: boolean) { this.deps.setUseResponseApi(value); }
  private set modelsText(value: string) { this.deps.setModelsText(value); }
  private set unsupportedAuthMsg(value: string) { this.deps.setUnsupportedAuth(value); }

  start(ports: ProviderLoginPorts): void { this.ports = ports; this.pageAlive = true; }
  dispose(): void {
    this.pageAlive = false;
    this.cancelCodexLogin();
    this.cancelGrokLogin();
  }

  loadAccount(p: ProviderSetting): void {
    if (p.type !== 'openai') return;
    const v: ProviderSettingOpenAIVariant = p as ProviderSettingOpenAIVariant;
    const sequence: number = this.loginSequence;
    // 历史 OAuth:已支持 codex(设备码)与 grok(PKCE);token 失效时提示重新登录
    if (v.authMode === 'codex_oauth') {
      this.deps.loadCodexTokens(v.id).then((tokens): void => {
        if (!this.loginCurrent(v.id, 'codex_oauth', sequence)) return;
        this.codexAccount = tokens !== null && tokens.email.length > 0 ? tokens.email : '';
        if (tokens === null) {
          this.unsupportedAuthMsg = 'Codex 登录已失效,请重新登录。';
        }
      }).catch((): void => {});
    } else if (v.authMode === 'grok_oauth') {
      this.deps.loadGrokTokens(v.id).then((tokens): void => {
        if (!this.loginCurrent(v.id, 'grok_oauth', sequence)) return;
        this.grokAccount = tokens !== null && tokens.email.length > 0 ? tokens.email : '';
        if (tokens === null) {
          this.unsupportedAuthMsg = 'Grok 登录已失效,请重新登录。';
        }
      }).catch((): void => {});
    }
  }

  grokOffered(): boolean {
    const raw: ProviderSetting | null = this.provider;
    if (raw === null || raw.type !== 'openai') return false;
    const v: ProviderSettingOpenAIVariant = raw as ProviderSettingOpenAIVariant;
    if (v.authMode === 'grok_oauth') return true;
    const url: string = this.baseUrl.toLowerCase();
    return url.indexOf('x.ai') >= 0 || url.indexOf('grok.com') >= 0;
  }

  authModeOptions(): string[] {
    const raw: ProviderSetting | null = this.provider;
    if (raw === null || raw.type !== 'openai') return ['API Key'];
    const v: ProviderSettingOpenAIVariant = raw as ProviderSettingOpenAIVariant;
    const modes: OpenAIAuthMode[] = openAIBrandAvailableAuthModes(v.brand);
    const labels: string[] = modes.map((m: OpenAIAuthMode): string => this.authModeLabel(m));
    if (this.grokOffered() && modes.indexOf('grok_oauth') < 0) labels.push('Grok 登录');
    return labels;
  }

  authModeLabel(mode: OpenAIAuthMode): string {
    if (mode === 'api_key') return 'API Key';
    if (mode === 'codex_oauth') return 'Codex 登录';
    if (mode === 'grok_oauth') return 'Grok 登录';
    return 'Coding Plan';
  }

  authModesAvailable(): OpenAIAuthMode[] {
    const raw: ProviderSetting | null = this.provider;
    if (raw === null || raw.type !== 'openai') return ['api_key'];
    const v: ProviderSettingOpenAIVariant = raw as ProviderSettingOpenAIVariant;
    const modes: OpenAIAuthMode[] = openAIBrandAvailableAuthModes(v.brand);
    if (this.grokOffered() && modes.indexOf('grok_oauth') < 0) {
      return [...modes, 'grok_oauth'];
    }
    return modes;
  }

  authModeAt(idx: number): OpenAIAuthMode {
    const modes: OpenAIAuthMode[] = this.authModesAvailable();
    return idx >= 0 && idx < modes.length ? modes[idx] : 'api_key';
  }

  authModeIndex(): number {
    const modes: OpenAIAuthMode[] = this.authModesAvailable();
    const idx: number = modes.indexOf(this.authMode as OpenAIAuthMode);
    return idx >= 0 ? idx : 0;
  }

  selectAuthMode(idx: number): void {
    const mode: OpenAIAuthMode = this.authModeAt(idx);
    if (mode !== this.authMode) {
      this.cancelCodexLogin();
      this.cancelGrokLogin();
      this.unsupportedAuthMsg = '';
    }
    this.authMode = mode;
    const fixed: string | null = openAIAuthModeFixedBaseUrl(mode);
    if (fixed !== null) {
      this.baseUrl = fixed;
      if (mode === 'codex_oauth') this.useResponseApi = true;
    }
  }

  pinnedBaseUrl(): string {
    if (this.provider === null || this.provider.type !== 'openai') return '';
    const fixed: string | null = openAIAuthModeFixedBaseUrl(this.authMode as OpenAIAuthMode);
    return fixed !== null ? fixed : '';
  }

  loginCurrent(providerId: string, mode: OpenAIAuthMode, sequence: number): boolean {
    return this.pageAlive && this.provider?.id === providerId && this.authMode === mode && sequence === this.loginSequence;
  }

  startCodexLogin(): void {
    const raw: ProviderSetting | null = this.provider;
    if (raw === null || !this.pageAlive || this.authMode !== 'codex_oauth' || this.codexBusy) return;
    const sequence: number = ++this.loginSequence;
    this.codexBusy = true;
    this.codexStatus = '正在获取设备码…';
    this.codexCancelled = false;
    const providerId: string = raw.id;
    this.deps.requestCodexDeviceCode().then((auth: CodexDeviceAuthorization): void => {
      if (!this.loginCurrent(providerId, 'codex_oauth', sequence)) return;
      this.codexAuth = auth;
      this.codexUserCode = auth.userCode;
      this.codexLoginOpen = true;
      this.codexStatus = '';
      this.deps.pollCodexDeviceCode(providerId, auth, (): boolean => this.codexCancelled ||
        !this.loginCurrent(providerId, 'codex_oauth', sequence))
        .then((tokens): void => {
          if (!this.loginCurrent(providerId, 'codex_oauth', sequence)) return;
          this.codexBusy = false;
          this.codexLoginOpen = false;
          this.codexAccount = tokens.email.length > 0 ? tokens.email : '已登录';
          this.apiKey = tokens.accessToken;
          this.useResponseApi = true;
          this.baseUrl = openAIAuthModeFixedBaseUrl('codex_oauth') ?? this.baseUrl;
          this.deps.fetchCodexModels(providerId).then((models: CodexModelItem[]): void => {
            if (!this.loginCurrent(providerId, 'codex_oauth', sequence)) return;
            if (models.length > 0) {
              this.modelsText = models.map((m: CodexModelItem): string => m.modelId).join(', ');
            }
            this.deps.save();
          });
        })
        .catch((e: Error): void => {
          if (!this.loginCurrent(providerId, 'codex_oauth', sequence)) return;
          this.codexBusy = false;
          this.codexLoginOpen = false;
          this.codexStatus = e.message;
        });
    }).catch((e: Error): void => {
      if (!this.loginCurrent(providerId, 'codex_oauth', sequence)) return;
      this.codexBusy = false;
      this.codexStatus = e.message;
    });
  }

  cancelCodexLogin(): void {
    this.loginSequence++;
    this.codexAuth = null;
    this.codexCancelled = true;
    this.codexBusy = false;
    this.codexLoginOpen = false;
  }

  cancelGrokLogin(): void {
    this.loginSequence++;
    this.grokAuth = null;
    this.grokBusy = false;
    this.grokLoginOpen = false;
  }

  startGrokLogin(): void {
    if (!this.pageAlive || this.authMode !== 'grok_oauth' || this.grokBusy) return;
    this.loginSequence++;
    const auth: GrokAuthorization = this.deps.buildGrokAuthorization();
    this.grokAuth = auth;
    this.grokBusy = true;
    this.grokStatus = '';
    this.grokPasteUrl = '';
    try {
      const ctx: common.UIAbilityContext = this.deps.abilityContext();
      ctx.startAbility({
        action: 'ohos.want.action.viewData',
        entities: ['entity.system.browsable'],
        uri: auth.url,
      }).catch((): void => {
        promptAction.showToast({ message: '无法打开浏览器', duration: 1400 });
      });
    } catch (_e) {
      promptAction.showToast({ message: '无法打开浏览器', duration: 1400 });
    }
    this.grokLoginOpen = true;
    this.grokBusy = false;
  }

  confirmGrokLogin(): void {
    const raw: ProviderSetting | null = this.provider;
    const auth: GrokAuthorization | null = this.grokAuth;
    if (raw === null || auth === null || !this.pageAlive || this.authMode !== 'grok_oauth' || this.grokBusy) return;
    const sequence: number = this.loginSequence;
    const code: string = this.deps.extractGrokCode(this.grokPasteUrl.trim());
    if (code.length === 0) {
      this.grokStatus = '未从链接中解析到 code 参数,请粘贴完整的重定向地址';
      return;
    }
    this.grokBusy = true;
    this.grokStatus = '正在交换令牌…';
    this.deps.exchangeGrokCode(raw.id, code, auth.codeVerifier,
      (): boolean => !this.loginCurrent(raw.id, 'grok_oauth', sequence)).then((tokens: GrokTokens): void => {
      if (!this.loginCurrent(raw.id, 'grok_oauth', sequence)) return;
      this.grokBusy = false;
      this.grokLoginOpen = false;
      this.grokAccount = tokens.email.length > 0 ? tokens.email : '已登录';
      this.apiKey = tokens.accessToken;
      this.baseUrl = openAIAuthModeFixedBaseUrl('grok_oauth') ?? this.baseUrl;
      this.modelsText = this.deps.grokFallbackModels.map((m: GrokModelItem): string => m.modelId).join(', ');
      this.deps.save();
    }).catch((e: Error): void => {
      if (!this.loginCurrent(raw.id, 'grok_oauth', sequence)) return;
      this.grokBusy = false;
      this.grokStatus = e.message;
    });
  }

  copyCodexUserCode(): void {
    const pb: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
    pb.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, this.codexUserCode));
    promptAction.showToast({ message: '设备码已复制', duration: 1400 });
  }

  openCodexVerification(): void {
    const auth: CodexDeviceAuthorization | null = this.codexAuth;
    if (auth === null) return;
    try {
      const ctx: common.UIAbilityContext = this.deps.abilityContext();
      ctx.startAbility({
        action: 'ohos.want.action.viewData',
        entities: ['entity.system.browsable'],
        uri: auth.verificationUrl,
      }).catch((): void => {
        promptAction.showToast({ message: '无法打开浏览器', duration: 1400 });
      });
    } catch (_e) {
      promptAction.showToast({ message: '无法打开浏览器', duration: 1400 });
    }
  }
}
