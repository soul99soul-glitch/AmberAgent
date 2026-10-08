import { DeepReadSubpageDock, DeepReadDockLayout } from '../components/deepread/DeepReadSubpageDock.ets';
// ChatProviderDetailPage — 提供商详情(Android SettingProviderDetailPage 1:1 结构)
// 头部:// PROVIDERS eyebrow + slug 16 Bold + 在线点 + name·auth·N 个模型;
// 配置 / 模型 双 tab(accent 2dp 下划线);配置 tab = 启用/名称/API Key/端点等;
// 模型 tab = 模型列表 + 参数入口 + 逗号编辑;保存显式按钮;删除置底。
// 新增流:列表页模板选择后经暂存函数交接对象进入本页,保存时插入。
import { getProductKind } from '../platform_impl/ProductIdentity.ets';
import { DeepReadDesign as D } from '../design/DeepReadDesign.ets';
import { DeepReadSettingsHeader, DeepReadSettingsSection, DeepReadSettingsGroup, DeepReadSettingsRow,
  DeepReadSettingsToggle, DeepReadSettingsPicker, DeepReadSettingsNote, DeepReadSettingsButton,
} from '../components/deepread/DeepReadSettingsVisual.ets';
import { router, promptAction } from '@kit.ArkUI';
import { picker, fileIo } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import type {
  ProviderSetting, ProviderSettingOpenAIVariant, ProviderSettingGoogle,
  ProviderSettingClaude, ProviderModel, OpenAIAuthMode,
  GoogleModelCatalog, GoogleCatalogModel,
} from '@amber/chat-domain';
import {
  loadProviders, saveProviders, makeProviderSettingOpenAIVariant,
  makeProviderSettingGoogle, makeProviderSettingClaude, makeProviderModel,
  openAIBrandAvailableAuthModes, openAIAuthModeFixedBaseUrl, openAIAuthHeaders,
} from '@amber/chat-domain';
import { getChatKvStore, getAppContainer } from '../di/AppContainer.ets';
import { loadGoogleModelCatalog } from '../platform_impl/GoogleAuthSupport.ets';
import { cleanupDeletedGoogleProvider } from '../platform_impl/GoogleProviderConfigSupport.ets';
import { EntryAbortController } from '../platform_impl/EntryAbortController.ets';
import { toProviderMonogram, providerSlug, providerAuthLabel } from '../di/ProviderDefaults.ets';
import {
  requestCodexDeviceCode, pollCodexDeviceCode, fetchCodexModels,
  loadCodexTokens, clearCodexTokens,
  CodexDeviceAuthorization, CodexModelItem,
} from '../platform_impl/CodexOAuthClient.ets';
import {
  buildGrokAuthorization, extractGrokCode, exchangeGrokCode, loadGrokTokens,
  clearGrokTokens, GROK_FALLBACK_MODELS,
  GrokAuthorization, GrokTokens, GrokModelItem,
} from '../platform_impl/GrokOAuthClient.ets';
import { pasteboard } from '@kit.BasicServicesKit';
import {
  ACCENT, BG, INK, INK2, INK3, INK4, SURFACE, SURFACE2, RAISED, LINE, BODY, BODY2, META, MONO_SMALL, LABEL, ERROR, RADIUS,
  TEXT_STYLES, lineHeightForSize, OVERLAY, MOTION_OVERLAY,
} from '../design/tokens.ets';
import { FlatTextField } from '../components/ui/FlatTextField.ets';
import { Icon } from '../components/ui/Icon.ets';
import { BtnFill, BtnGhost, Chip, PaperSeparator, ToggleRow, SectionHeader, Seg } from '../components/PageTemplate.ets';
import { FONT_MONO, FONT_MONO_MEDIUM, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';

// 模板新增流使用进程 AppStorage 单槽,避免路由页面间模块变量不共享。
const PENDING_PROVIDER_KEY: string = 'chatPendingProviderDraft';

export const setPendingProvider = (p: ProviderSetting | null): void => {
  if (p === null) AppStorage.delete(PENDING_PROVIDER_KEY);
  else AppStorage.setOrCreate<ProviderSetting>(PENDING_PROVIDER_KEY, p);
};

export const takePendingProvider = (): ProviderSetting | null => {
  const provider: ProviderSetting | null = AppStorage.get<ProviderSetting>(PENDING_PROVIDER_KEY) ?? null;
  AppStorage.delete(PENDING_PROVIDER_KEY);
  return provider;
};

// Provider 导出载荷(脱敏:不含 apiKey/OAuth 凭据;format 标记供导入方识别)
interface ProviderExportModel {
  modelId: string;
  displayName: string;
  type: string;
}
interface ProviderFieldOptions {
  label: string;
  placeholder: string;
  value: string;
  onChange: (value: string) => void;
  obscure: boolean;
  mono: boolean;
}
interface ProviderExportPayload {
  format: string;
  version: number;
  type: string;
  name: string;
  baseUrl: string;
  chatCompletionsPath?: string;
  useResponseApi?: boolean;
  vertexAI?: boolean;
  location?: string;
  projectId?: string;
  authMode?: string;
  useServiceAccount?: boolean;
  serviceAccountEmail?: string;
  oauthClientId?: string;
  oauthRedirectUri?: string;
  promptCaching?: boolean;
  models: ProviderExportModel[];
}

@Entry
@Component
struct ChatProviderDetailPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('reduceMotion') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') appBackgrounded: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State provider: ProviderSetting | null = null;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State advancedOpen: boolean = false;
  @State loadError: string = '';
  @State loading: boolean = true;
  @State tab: number = 0; // 0 配置 / 1 模型
  // 表单字段
  @State providerEnabled: boolean = true;
  @State name: string = '';
  @State baseUrl: string = '';
  @State apiKey: string = '';
  @State modelsText: string = '';
  @State chatPath: string = '/chat/completions';
  @State useResponseApi: boolean = false;
  @State vertexAI: boolean = false;
  @State useServiceAccount: boolean = false;
  @State serviceAccountEmail: string = '';
  @State privateKey: string = '';
  @State location: string = 'us-central1';
  @State projectId: string = '';
  @State promptCaching: boolean = false;
  @State savedMsg: string = '';
  @State unsupportedAuthMsg: string = '';
  // 模型目录拉取(Android fetch 模型列表)
  @State fetchBusy: boolean = false;
  @State fetchMsg: string = '';
  @State fetchedIds: string[] = [];
  // 认证方式(openai 变体:api_key/codex_oauth/各 Coding Plan;C3-2/C3-4)
  @State authMode: string = 'api_key';
  @State codexLoginOpen: boolean = false;
  @State codexUserCode: string = '';
  @State codexStatus: string = '';
  @State codexBusy: boolean = false;
  @State codexAccount: string = '';
  private codexAuth: CodexDeviceAuthorization | null = null;
  private codexCancelled: boolean = false;
  // Grok 登录(PKCE + 粘贴重定向 URL;C3-3)
  @State grokLoginOpen: boolean = false;
  @State grokPasteUrl: string = '';
  @State grokStatus: string = '';
  @State grokBusy: boolean = false;
  @State grokAccount: string = '';
  private grokAuth: GrokAuthorization | null = null;
  private isNew: boolean = false;
  private pageAlive: boolean = false;
  private loginSequence: number = 0;
  private googleCatalogController: EntryAbortController | null = null;
  private googleFetchedModels: GoogleCatalogModel[] = [];

  aboutToAppear(): void {
    this.pageAlive = true;
    const params: Record<string, string> | undefined =
      router.getParams() as Record<string, string> | undefined;
    const id: string = params !== undefined && params['providerId'] !== undefined ? params['providerId'] : '';
    this.isNew = params !== undefined && params['isNew'] === '1';
    const draft: ProviderSetting | null = this.isNew ? takePendingProvider() : null;
    loadProviders(getChatKvStore()).then((list: ProviderSetting[] | null): void => {
      let target: ProviderSetting | null = null;
      if (id.length > 0) {
        const hit = (list ?? []).find((p: ProviderSetting): boolean => p.id === id);
        if (hit !== undefined) target = hit;
      }
      if (target === null && draft !== null) {
        target = draft;
      }
      if (target !== null) this.applyProvider(target);
      else this.loadError = '模型服务不存在，请返回重新选择。';
      this.loading = false;
    }).catch((): void => { this.loadError = '无法读取模型服务，请返回后重试。'; this.loading = false; });
  }

  // 返回自模型参数页等场景:仅刷新 provider 引用(携带最新 models 参数),
  // 不重置用户正在编辑的表单草稿
  onPageShow(): void {
    const raw: ProviderSetting | null = this.provider;
    if (raw === null) return;
    loadProviders(getChatKvStore()).then((list: ProviderSetting[] | null): void => {
      const hit = (list ?? []).find((p: ProviderSetting): boolean => p.id === raw.id);
      if (hit !== undefined && this.provider !== null && this.provider.id === hit.id) {
        this.provider = hit;
        if (hit.type === 'google') {
          const current: ProviderSettingGoogle = hit as ProviderSettingGoogle;
          this.authMode = current.authMode;
          this.useServiceAccount = current.useServiceAccount;
          if (raw.type === 'google') {
            const prior: ProviderSettingGoogle = raw as ProviderSettingGoogle;
            if (prior.authMode !== current.authMode || prior.useServiceAccount !== current.useServiceAccount
              || prior.privateKeyRef !== current.privateKeyRef || prior.oauthClientId !== current.oauthClientId
              || prior.projectId !== current.projectId || prior.location !== current.location) {
              this.apiKey = current.apiKey;
              this.vertexAI = current.vertexAI;
              this.projectId = current.projectId;
              this.location = current.location;
            }
          }
        }
      }
    }).catch((): void => {});
  }

  onBackPress(): boolean { return this.ioBusy; }

  aboutToDisappear(): void {
    this.pageAlive = false;
    this.cancelCodexLogin();
    this.cancelGrokLogin();
    if (this.googleCatalogController !== null) this.googleCatalogController.abort();
    this.googleCatalogController = null;
  }

  private isGoogleApiKey(): boolean {
    return this.provider !== null && this.provider.type === 'google'
      && this.authMode === 'api_key' && !this.useServiceAccount;
  }

  private openGoogleAuth(): void {
    const raw: ProviderSetting | null = this.provider;
    if (raw === null || raw.type !== 'google' || this.ioBusy) return;
    loadProviders(getChatKvStore()).then((all: ProviderSetting[] | null): void => {
      if (!(all ?? []).some((item: ProviderSetting): boolean => item.id === raw.id)) {
        this.savedMsg = '请先点击保存，建立服务商配置后再设置 Google 认证。';
        return;
      }
      router.pushUrl({ url: 'pages/GoogleProviderAuthPage', params: { providerId: raw.id } });
    }).catch((): void => { this.savedMsg = '无法读取已保存的服务商配置。'; });
  }

  private async openModelParameters(modelId: string): Promise<void> {
    const raw: ProviderSetting | null = this.provider;
    if (raw === null || this.ioBusy) return;
    try {
      const all: ProviderSetting[] | null = await loadProviders(getChatKvStore());
      if (!(all ?? []).some((item: ProviderSetting): boolean => item.id === raw.id)) {
        this.savedMsg = '请先点击保存，再编辑模型参数。';
        return;
      }
      router.pushUrl({ url: 'pages/ChatModelParamsPage', params: { providerId: raw.id, modelId } });
    } catch (error) {
      this.savedMsg = `无法读取服务商配置：${(error as Error).message}`;
    }
  }

  private applyProvider(p: ProviderSetting): void {
    this.provider = p;
    this.providerEnabled = p.enabled;
    this.name = p.name;
    this.baseUrl = p.baseUrl;
    this.modelsText = p.models.map((m: ProviderModel): string => m.modelId).join(', ');
    this.unsupportedAuthMsg = '';
    this.apiKey = p.apiKey;
    if (p.type === 'openai') {
      const v: ProviderSettingOpenAIVariant = p as ProviderSettingOpenAIVariant;
      this.chatPath = v.chatCompletionsPath;
      this.useResponseApi = v.useResponseApi;
      this.authMode = v.authMode;
      const sequence: number = this.loginSequence;
      // 历史 OAuth:已支持 codex(设备码)与 grok(PKCE);token 失效时提示重新登录
      if (v.authMode === 'codex_oauth') {
        loadCodexTokens(v.id).then((tokens): void => {
          if (!this.loginCurrent(v.id, 'codex_oauth', sequence)) return;
          this.codexAccount = tokens !== null && tokens.email.length > 0 ? tokens.email : '';
          if (tokens === null) {
            this.unsupportedAuthMsg = 'Codex 登录已失效,请重新登录。';
          }
        }).catch((): void => {});
      } else if (v.authMode === 'grok_oauth') {
        loadGrokTokens(v.id).then((tokens): void => {
          if (!this.loginCurrent(v.id, 'grok_oauth', sequence)) return;
          this.grokAccount = tokens !== null && tokens.email.length > 0 ? tokens.email : '';
          if (tokens === null) {
            this.unsupportedAuthMsg = 'Grok 登录已失效,请重新登录。';
          }
        }).catch((): void => {});
      }
    } else if (p.type === 'google') {
      const v: ProviderSettingGoogle = p as ProviderSettingGoogle;
      this.vertexAI = v.vertexAI;
      this.useServiceAccount = v.useServiceAccount;
      this.authMode = v.authMode;
      this.serviceAccountEmail = v.serviceAccountEmail;
      this.privateKey = '';
      this.location = v.location;
      this.projectId = v.projectId;
    } else {
      const c: ProviderSettingClaude = p as ProviderSettingClaude;
      this.promptCaching = c.promptCaching;
    }
  }

  private parseModels(existing: ProviderModel[]): ProviderModel[] {
    return this.modelsText
      .split(/[,\n]/)
      .map((s: string): string => s.trim())
      .filter((s: string, index: number, ids: string[]): boolean => s.length > 0 && ids.indexOf(s) === index)
      .map((id: string): ProviderModel => {
        const hit: ProviderModel | undefined = existing.find(
          (m: ProviderModel): boolean => m.modelId === id);
        const catalog: GoogleCatalogModel | undefined = this.googleFetchedModels.find(
          (model: GoogleCatalogModel): boolean => model.modelId === id);
        return hit !== undefined ? hit : makeProviderModel({ modelId: id, displayName: catalog !== undefined ? catalog.displayName : id });
      });
  }

  // ── 模型目录拉取(GET {baseUrl}/models;OpenAI/Claude data[].id,Google models[].name) ──

  private fetchModels(): void {
    const raw: ProviderSetting | null = this.provider;
    if (raw === null || this.fetchBusy) return;
    if (raw.type === 'google' && raw.authMode === 'api_key' && !raw.useServiceAccount && this.vertexAI) {
      this.fetchMsg = 'Vertex API Key 模式请手动填写模型 ID。';
      return;
    }
    if (raw.type === 'openai' && (this.authMode === 'codex_oauth' || this.authMode === 'grok_oauth')) {
      this.fetchMsg = '账号登录时会添加可用模型，也可以手动填写模型 ID。';
      return;
    }
    this.fetchBusy = true;
    this.fetchMsg = '正在拉取…';
    if (raw.type === 'google' && (raw.authMode !== 'api_key' || raw.useServiceAccount)) {
      const controller: EntryAbortController = new EntryAbortController();
      this.googleCatalogController = controller;
      loadGoogleModelCatalog(raw as ProviderSettingGoogle, controller.signal).then((catalog: GoogleModelCatalog): void => {
        if (controller.signal.aborted) return;
        this.googleFetchedModels = catalog.models;
        const existing: string[] = this.modelsText.split(',').map((id: string): string => id.trim());
        this.fetchedIds = catalog.models.map((model): string => model.modelId)
          .filter((id: string): boolean => existing.indexOf(id) < 0);
        this.fetchMsg = !catalog.supported ? catalog.message
          : (this.fetchedIds.length > 0 ? `发现 ${catalog.models.length} 个模型，${this.fetchedIds.length} 个可添加`
            : `发现 ${catalog.models.length} 个模型，全部已在列表中`);
      }).catch((error: Error): void => {
        if (!controller.signal.aborted) this.fetchMsg = error instanceof Error ? `拉取失败：${error.message}` : '拉取失败';
      }).finally((): void => {
        if (this.googleCatalogController === controller) this.googleCatalogController = null;
        this.fetchBusy = false;
      });
      return;
    }
    let headers: Record<string, string> = {};
    if (raw.type === 'claude') {
      headers['x-api-key'] = this.apiKey.trim();
      headers['anthropic-version'] = '2023-06-01';
    } else if (raw.type === 'google') {
      headers['x-goog-api-key'] = this.apiKey.trim();
    } else {
      headers = openAIAuthHeaders(this.authMode as OpenAIAuthMode, this.baseUrl.trim(), this.apiKey.trim());
    }
    getAppContainer().httpClient.fetch({
      url: `${this.baseUrl.trim()}/models`,
      method: 'GET',
      headers,
    }, { signal: undefined }).then((resp): void => {
      this.fetchBusy = false;
      if (resp.status < 200 || resp.status >= 300) {
        this.fetchMsg = `拉取失败(${resp.status}): ${resp.body.substring(0, 120)}`;
        return;
      }
      const ids: string[] = this.parseModelIds(resp.body, raw.type);
      if (ids.length === 0) {
        this.fetchMsg = '返回为空或无法解析';
        return;
      }
      const existing: string[] = this.modelsText.split(',').map((s: string): string => s.trim());
      const fresh: string[] = ids.filter((id: string): boolean => existing.indexOf(id) < 0);
      this.fetchedIds = fresh;
      this.fetchMsg = fresh.length > 0
        ? `发现 ${ids.length} 个模型,${fresh.length} 个可添加`
        : `发现 ${ids.length} 个模型,全部已在列表中`;
    }).catch((e: Error): void => {
      this.fetchBusy = false;
      this.fetchMsg = `拉取失败:${e.message}`;
    });
  }

  private parseModelIds(body: string, providerType: string): string[] {
    try {
      const parsed = JSON.parse(body) as Record<string, Object>;
      if (providerType === 'google') {
        const models = parsed['models'] as Object[];
        if (!Array.isArray(models)) return [];
        const out: string[] = [];
        for (const item of models) {
          const obj = item as Record<string, Object>;
          const name: string = String(obj['name'] ?? '');
          if (name.length > 0) out.push(name.startsWith('models/') ? name.substring(7) : name);
        }
        return out;
      }
      const data = parsed['data'] as Object[];
      if (!Array.isArray(data)) return [];
      const out: string[] = [];
      for (const item of data) {
        const obj = item as Record<string, Object>;
        const id: string = String(obj['id'] ?? '');
        if (id.length > 0) out.push(id);
      }
      return out;
    } catch (_e) {
      return [];
    }
  }

  // 添加单个拉取 id 到逗号编辑框(保存时随 parseModels 入库)
  private addFetchedId(id: string): void {
    const parts: string[] = this.modelsText.split(',')
      .map((s: string): string => s.trim())
      .filter((s: string): boolean => s.length > 0);
    if (parts.indexOf(id) >= 0) return;
    parts.push(id);
    this.modelsText = parts.join(', ');
    this.fetchedIds = this.fetchedIds.filter((s: string): boolean => s !== id);
  }

  private addAllFetched(): void {
    const parts: string[] = this.modelsText.split(',')
      .map((s: string): string => s.trim())
      .filter((s: string): boolean => s.length > 0);
    for (const id of this.fetchedIds) {
      if (parts.indexOf(id) < 0) parts.push(id);
    }
    this.modelsText = parts.join(', ');
    this.fetchedIds = [];
  }

  @State ioBusy: boolean = false;

  private async save(): Promise<boolean> {
    const raw: ProviderSetting | null = this.provider;
    if (raw === null || this.ioBusy) return false;
    this.ioBusy = true;
    // api_key/plan 模式必须填 Key;codex/grok 的凭据由登录态承载(apiKey 字段同步)
    if (raw.type === 'openai' && this.authMode !== 'codex_oauth' && this.authMode !== 'grok_oauth'
      && this.apiKey.trim().length === 0) {
      this.savedMsg = '请先填写 API Key';
      setTimeout((): void => { this.savedMsg = ''; }, 2000);
      this.ioBusy = false;
      return false;
    }
    if (raw.type !== 'openai' && this.unsupportedAuthMsg.length > 0 && this.apiKey.trim().length === 0) {
      this.savedMsg = '请先填写 API Key';
      setTimeout((): void => { this.savedMsg = ''; }, 2000);
      this.ioBusy = false;
      return false;
    }
    const apiKeySnapshot: string = this.apiKey;
    const models: ProviderModel[] = this.parseModels(raw.models);
    const trimmedName: string = this.name.trim();
    let updated: ProviderSetting;
    if (raw.type === 'google') {
      const g: ProviderSettingGoogle = raw as ProviderSettingGoogle;
      updated = makeProviderSettingGoogle({
        id: raw.id,
        enabled: this.providerEnabled,
        name: trimmedName.length > 0 ? trimmedName : 'Google',
        baseUrl: this.baseUrl.trim(),
        apiKey: this.isGoogleApiKey() ? this.apiKey.trim() : g.apiKey,
        models,
        vertexAI: this.isGoogleApiKey() ? this.vertexAI : g.vertexAI,
        useServiceAccount: g.useServiceAccount,
        serviceAccountEmail: g.serviceAccountEmail,
        privateKey: g.privateKey,
        privateKeyRef: g.privateKeyRef,
        location: this.isGoogleApiKey() ? this.location.trim() : g.location,
        projectId: this.isGoogleApiKey() ? this.projectId.trim() : g.projectId,
        authMode: g.authMode,
        oauthClientId: g.oauthClientId,
        oauthRedirectUri: g.oauthRedirectUri,
        oauthClientSecretRef: g.oauthClientSecretRef,
        balanceOption: g.balanceOption,
      });
    } else if (raw.type === 'claude') {
      const c: ProviderSettingClaude = raw as ProviderSettingClaude;
      updated = makeProviderSettingClaude({
        id: raw.id,
        enabled: this.providerEnabled,
        name: trimmedName.length > 0 ? trimmedName : 'Claude',
        baseUrl: this.baseUrl.trim(),
        apiKey: this.apiKey.trim(),
        models,
        promptCaching: this.promptCaching,
        balanceOption: c.balanceOption,
      });
    } else {
      const o: ProviderSettingOpenAIVariant = raw as ProviderSettingOpenAIVariant;
      const fixed: string | null = openAIAuthModeFixedBaseUrl(this.authMode as OpenAIAuthMode);
      updated = makeProviderSettingOpenAIVariant({
        id: raw.id,
        enabled: this.providerEnabled,
        name: trimmedName.length > 0 ? trimmedName : 'OpenAI',
        // plan/codex/grok 模式钉官方地址;api_key 模式用表单值
        baseUrl: fixed !== null ? fixed : this.baseUrl.trim(),
        // codex/grok 的 access token 由登录/刷新流程维护,不读表单
        apiKey: (this.authMode === 'codex_oauth' || this.authMode === 'grok_oauth')
          ? o.apiKey : this.apiKey.trim(),
        chatCompletionsPath: this.chatPath.trim(),
        useResponseApi: this.authMode === 'codex_oauth' ? true : this.useResponseApi,
        models,
        authMode: this.authMode as OpenAIAuthMode,
        brand: o.brand,
        balanceOption: o.balanceOption,
      });
    }
    return await loadProviders(getChatKvStore()).then(async (list: ProviderSetting[] | null): Promise<boolean> => {
      const all: ProviderSetting[] = list ?? [];
      if (updated.type === 'openai') {
        const openAi: ProviderSettingOpenAIVariant = updated as ProviderSettingOpenAIVariant;
        if (openAi.authMode === 'codex_oauth' || openAi.authMode === 'grok_oauth') {
          const tokens = openAi.authMode === 'codex_oauth' ? await loadCodexTokens(updated.id) : await loadGrokTokens(updated.id);
          const latest: ProviderSetting | undefined = all.find((item: ProviderSetting): boolean => item.id === updated.id);
          updated.apiKey = tokens !== null ? tokens.accessToken : latest !== undefined ? latest.apiKey : apiKeySnapshot;
        }
      }
      const idx: number = all.findIndex((p: ProviderSetting): boolean => p.id === updated.id);
      if (idx < 0 && !this.isNew) throw new Error('模型服务已被删除，请返回重新添加。');
      if (idx >= 0) {
        const latestModels: ProviderModel[] = all[idx].models;
        // Only the catalog IDs are edited here; retain parameters saved by the model editor.
        updated.models = updated.models.map((model: ProviderModel): ProviderModel =>
          latestModels.find((latest: ProviderModel): boolean => latest.modelId === model.modelId) ?? model);
      }
      if (idx >= 0) {
        all[idx] = updated;
      } else {
        all.unshift(updated);
      }
      return saveProviders(getChatKvStore(), all).then((): boolean => {
        this.provider = updated;
        this.isNew = false;
        this.unsupportedAuthMsg = '';
        this.savedMsg = '已保存';
        setTimeout((): void => { this.savedMsg = ''; }, 1500);
        return true;
      });
    }).catch((e: Error): boolean => {
      this.savedMsg = `保存失败：${e.message}`;
      return false;
    }).finally((): void => {
      this.ioBusy = false;
    });
  }

  // ── 认证方式(openai 变体;C3-2 Codex 设备码 / C3-4 Token Plan) ──

  private grokOffered(): boolean {
    const raw: ProviderSetting | null = this.provider;
    if (raw === null || raw.type !== 'openai') return false;
    const v: ProviderSettingOpenAIVariant = raw as ProviderSettingOpenAIVariant;
    if (v.authMode === 'grok_oauth') return true;
    const url: string = this.baseUrl.toLowerCase();
    return url.indexOf('x.ai') >= 0 || url.indexOf('grok.com') >= 0;
  }

  private authModeOptions(): string[] {
    const raw: ProviderSetting | null = this.provider;
    if (raw === null || raw.type !== 'openai') return ['API Key'];
    const v: ProviderSettingOpenAIVariant = raw as ProviderSettingOpenAIVariant;
    const modes: OpenAIAuthMode[] = openAIBrandAvailableAuthModes(v.brand);
    const labels: string[] = modes.map((m: OpenAIAuthMode): string => this.authModeLabel(m));
    if (this.grokOffered() && modes.indexOf('grok_oauth') < 0) labels.push('Grok 登录');
    return labels;
  }

  private authModeLabel(mode: OpenAIAuthMode): string {
    if (mode === 'api_key') return 'API Key';
    if (mode === 'codex_oauth') return 'Codex 登录';
    if (mode === 'grok_oauth') return 'Grok 登录';
    return 'Coding Plan';
  }

  private authModesAvailable(): OpenAIAuthMode[] {
    const raw: ProviderSetting | null = this.provider;
    if (raw === null || raw.type !== 'openai') return ['api_key'];
    const v: ProviderSettingOpenAIVariant = raw as ProviderSettingOpenAIVariant;
    const modes: OpenAIAuthMode[] = openAIBrandAvailableAuthModes(v.brand);
    if (this.grokOffered() && modes.indexOf('grok_oauth') < 0) {
      return [...modes, 'grok_oauth'];
    }
    return modes;
  }

  private authModeAt(idx: number): OpenAIAuthMode {
    const modes: OpenAIAuthMode[] = this.authModesAvailable();
    return idx >= 0 && idx < modes.length ? modes[idx] : 'api_key';
  }

  private authModeIndex(): number {
    const modes: OpenAIAuthMode[] = this.authModesAvailable();
    const idx: number = modes.indexOf(this.authMode as OpenAIAuthMode);
    return idx >= 0 ? idx : 0;
  }

  private selectAuthMode(idx: number): void {
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

  // plan/codex/grok 模式的钉死地址('' = api_key 自由编辑);
  //   固定地址用只读行展示,避免修改认证模式要求的官方端点(Android 锁字段同语义)
  private pinnedBaseUrl(): string {
    if (this.provider === null || this.provider.type !== 'openai') return '';
    const fixed: string | null = openAIAuthModeFixedBaseUrl(this.authMode as OpenAIAuthMode);
    return fixed !== null ? fixed : '';
  }

  private loginCurrent(providerId: string, mode: OpenAIAuthMode, sequence: number): boolean {
    return this.pageAlive && this.provider?.id === providerId && this.authMode === mode && sequence === this.loginSequence;
  }

  private startCodexLogin(): void {
    const raw: ProviderSetting | null = this.provider;
    if (raw === null || !this.pageAlive || this.authMode !== 'codex_oauth' || this.codexBusy) return;
    const sequence: number = ++this.loginSequence;
    this.codexBusy = true;
    this.codexStatus = '正在获取设备码…';
    this.codexCancelled = false;
    const providerId: string = raw.id;
    requestCodexDeviceCode().then((auth: CodexDeviceAuthorization): void => {
      if (!this.loginCurrent(providerId, 'codex_oauth', sequence)) return;
      this.codexAuth = auth;
      this.codexUserCode = auth.userCode;
      this.codexLoginOpen = true;
      this.codexStatus = '';
      pollCodexDeviceCode(providerId, auth, (): boolean => this.codexCancelled ||
        !this.loginCurrent(providerId, 'codex_oauth', sequence))
        .then((tokens): void => {
          if (!this.loginCurrent(providerId, 'codex_oauth', sequence)) return;
          this.codexBusy = false;
          this.codexLoginOpen = false;
          this.codexAccount = tokens.email.length > 0 ? tokens.email : '已登录';
          this.apiKey = tokens.accessToken;
          this.useResponseApi = true;
          this.baseUrl = openAIAuthModeFixedBaseUrl('codex_oauth') ?? this.baseUrl;
          fetchCodexModels(providerId).then((models: CodexModelItem[]): void => {
            if (!this.loginCurrent(providerId, 'codex_oauth', sequence)) return;
            if (models.length > 0) {
              this.modelsText = models.map((m: CodexModelItem): string => m.modelId).join(', ');
            }
            this.save();
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

  private cancelCodexLogin(): void {
    this.loginSequence++;
    this.codexAuth = null;
    this.codexCancelled = true;
    this.codexBusy = false;
    this.codexLoginOpen = false;
  }

  // ── Grok 登录(PKCE:浏览器授权 → 粘贴重定向 URL → 换 token) ──

  private cancelGrokLogin(): void {
    this.loginSequence++;
    this.grokAuth = null;
    this.grokBusy = false;
    this.grokLoginOpen = false;
  }

  private startGrokLogin(): void {
    if (!this.pageAlive || this.authMode !== 'grok_oauth' || this.grokBusy) return;
    this.loginSequence++;
    const auth: GrokAuthorization = buildGrokAuthorization();
    this.grokAuth = auth;
    this.grokBusy = true;
    this.grokStatus = '';
    this.grokPasteUrl = '';
    try {
      const ctx: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
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

  private confirmGrokLogin(): void {
    const raw: ProviderSetting | null = this.provider;
    const auth: GrokAuthorization | null = this.grokAuth;
    if (raw === null || auth === null || !this.pageAlive || this.authMode !== 'grok_oauth' || this.grokBusy) return;
    const sequence: number = this.loginSequence;
    const code: string = extractGrokCode(this.grokPasteUrl.trim());
    if (code.length === 0) {
      this.grokStatus = '未从链接中解析到 code 参数,请粘贴完整的重定向地址';
      return;
    }
    this.grokBusy = true;
    this.grokStatus = '正在交换令牌…';
    exchangeGrokCode(raw.id, code, auth.codeVerifier,
      (): boolean => !this.loginCurrent(raw.id, 'grok_oauth', sequence)).then((tokens: GrokTokens): void => {
      if (!this.loginCurrent(raw.id, 'grok_oauth', sequence)) return;
      this.grokBusy = false;
      this.grokLoginOpen = false;
      this.grokAccount = tokens.email.length > 0 ? tokens.email : '已登录';
      this.apiKey = tokens.accessToken;
      this.baseUrl = openAIAuthModeFixedBaseUrl('grok_oauth') ?? this.baseUrl;
      this.modelsText = GROK_FALLBACK_MODELS.map((m: GrokModelItem): string => m.modelId).join(', ');
      this.save();
    }).catch((e: Error): void => {
      if (!this.loginCurrent(raw.id, 'grok_oauth', sequence)) return;
      this.grokBusy = false;
      this.grokStatus = e.message;
    });
  }

  private loginOverlayDuration(): number {
    return getProductKind() === 'deepread' && (this.reduceMotion || this.appBackgrounded) ? 0 : MOTION_OVERLAY;
  }

  private loginOverlayScale(): number {
    return getProductKind() === 'deepread' && (this.reduceMotion || this.appBackgrounded) ? 1 : 0.96;
  }

  @Builder
  GrokLoginOverlay(): void {
    Column() {
      Column() {
        Text('登录 xAI(Grok)')
          .fontSize(17).fontWeight(600).fontColor((getProductKind() === 'deepread' ? D.ink : INK)).margin({ bottom: 12 })
        Text('已在浏览器打开授权页。登录完成后页面会跳转到一个打不开的本地地址,请把地址栏里的完整链接复制回来粘贴到下面:')
          .fontSize(TEXT_STYLES.secondary.size)
          .lineHeight(TEXT_STYLES.secondary.lineHeight)
          .fontColor((getProductKind() === 'deepread' ? D.muted : INK3)).margin({ bottom: 12 })
        TextArea({ text: this.grokPasteUrl, placeholder: 'http://127.0.0.1:8787/callback?code=…' })
          .fontSize(TEXT_STYLES.meta.size).fontFamily(FONT_MONO).fontColor((getProductKind() === 'deepread' ? D.ink : INK))
          .backgroundColor(SURFACE2).borderRadius(8)
          .padding({ left: 12, right: 12, top: 10, bottom: 10 })
          .height(72)
          .onChange((v: string): void => { this.grokPasteUrl = v; })
        if (this.grokStatus.length > 0) {
          Text(this.grokStatus)
            .fontSize(META).fontColor(ERROR).width('100%').margin({ top: 8 })
        }
        Row() {
          Text('取消')
            .fontSize(14).fontColor((getProductKind() === 'deepread' ? D.muted : INK3))
            .layoutWeight(1).textAlign(TextAlign.Center)
            .padding({ top: 8, bottom: 8 })
            .onClick((): void => { this.cancelGrokLogin(); })
          Text(this.grokBusy ? '交换中…' : '完成登录')
            .fontSize(14).fontColor(this.grokBusy ? (getProductKind() === 'deepread' ? D.muted : INK3) : this.accentColorStore)
            .layoutWeight(1).textAlign(TextAlign.Center)
            .padding({ top: 8, bottom: 8 })
            .onClick((): void => { this.confirmGrokLogin(); })
        }
        .width('100%').margin({ top: 12 })
      }
      .width('80%').padding(22).backgroundColor((getProductKind() === 'deepread' ? D.card : SURFACE)).borderRadius(16)
      .shadow({ radius: 24, color: OVERLAY, offsetY: 8 })
      .transition(TransitionEffect.scale({ x: this.loginOverlayScale(), y: this.loginOverlayScale() })
        .animation({ duration: this.loginOverlayDuration(), curve: Curve.EaseOut }))
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
    .justifyContent(FlexAlign.Center)
    .transition(TransitionEffect.OPACITY.animation({ duration: this.loginOverlayDuration(), curve: Curve.EaseOut }))
  }

  private copyCodexUserCode(): void {
    const pb: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
    pb.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, this.codexUserCode));
    promptAction.showToast({ message: '设备码已复制', duration: 1400 });
  }

  private openCodexVerification(): void {
    const auth: CodexDeviceAuthorization | null = this.codexAuth;
    if (auth === null) return;
    try {
      const ctx: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
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

  @Builder
  CodexLoginOverlay(): void {
    Column() {
      Column() {
        Text('登录 ChatGPT')
          .fontSize(17).fontWeight(600).fontColor((getProductKind() === 'deepread' ? D.ink : INK)).margin({ bottom: 12 })
        Text('在浏览器中打开登录页,输入以下设备码完成授权:')
          .fontSize(TEXT_STYLES.secondary.size)
          .lineHeight(TEXT_STYLES.secondary.lineHeight)
          .fontColor((getProductKind() === 'deepread' ? D.muted : INK3)).margin({ bottom: 12 })
        Text(this.codexUserCode)
          .fontSize(26).fontWeight(FontWeight.Bold).fontFamily(FONT_MONO)
          .fontColor((getProductKind() === 'deepread' ? D.ink : INK)).letterSpacing(2)
          .margin({ bottom: 18 })
        Row() {
          Text('复制设备码')
            .fontSize(14).fontColor(getProductKind() === 'deepread' ? D.accent : this.accentColorStore)
            .layoutWeight(1).textAlign(TextAlign.Center)
            .padding({ top: 8, bottom: 8 })
            .onClick((): void => { this.copyCodexUserCode(); })
          Text('打开登录页')
            .fontSize(14).fontColor(getProductKind() === 'deepread' ? D.accent : this.accentColorStore)
            .layoutWeight(1).textAlign(TextAlign.Center)
            .padding({ top: 8, bottom: 8 })
            .onClick((): void => { this.openCodexVerification(); })
        }
        .width('100%')
        Row() {
          Text('取消登录')
            .fontSize(14).fontColor((getProductKind() === 'deepread' ? D.muted : INK3))
            .layoutWeight(1).textAlign(TextAlign.Center)
            .padding({ top: 8, bottom: 8 })
            .onClick((): void => { this.cancelCodexLogin(); })
        }
        .width('100%').margin({ top: 8 })
      }
      .width('80%').padding(22).backgroundColor((getProductKind() === 'deepread' ? D.card : SURFACE)).borderRadius(16)
      .shadow({ radius: 24, color: OVERLAY, offsetY: 8 })
      .transition(TransitionEffect.scale({ x: this.loginOverlayScale(), y: this.loginOverlayScale() })
        .animation({ duration: this.loginOverlayDuration(), curve: Curve.EaseOut }))
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
    .justifyContent(FlexAlign.Center)
    .transition(TransitionEffect.OPACITY.animation({ duration: this.loginOverlayDuration(), curve: Curve.EaseOut }))
  }

  // Provider 导出(脱敏 JSON,不含 apiKey;Android 导出分享对齐)
  private exportProvider(): void {
    const raw: ProviderSetting | null = this.provider;
    if (raw === null) {
      promptAction.showToast({ message: '请先保存后再导出', duration: 1600 });
      return;
    }
    const payload: ProviderExportPayload = {
      format: 'amber-provider',
      version: 1,
      type: raw.type,
      name: raw.name,
      baseUrl: raw.baseUrl,
      models: raw.models.map((m: ProviderModel): ProviderExportModel => ({
        modelId: m.modelId, displayName: m.displayName, type: m.type,
      })),
    };
    if (raw.type === 'openai') {
      const v: ProviderSettingOpenAIVariant = raw as ProviderSettingOpenAIVariant;
      payload.chatCompletionsPath = v.chatCompletionsPath;
      payload.useResponseApi = v.useResponseApi;
    } else if (raw.type === 'google') {
      const v: ProviderSettingGoogle = raw as ProviderSettingGoogle;
      payload.vertexAI = v.vertexAI;
      payload.location = v.location;
      payload.projectId = v.projectId;
      payload.authMode = v.authMode;
      payload.useServiceAccount = v.useServiceAccount;
      payload.serviceAccountEmail = v.serviceAccountEmail;
      payload.oauthClientId = v.oauthClientId;
      payload.oauthRedirectUri = v.oauthRedirectUri;
    } else {
      const v: ProviderSettingClaude = raw as ProviderSettingClaude;
      payload.promptCaching = v.promptCaching;
    }
    const json: string = JSON.stringify(payload, null, 2);
    const safeName: string = (raw.name.trim().length > 0 ? raw.name.trim() : 'provider')
      .replace(/[\\/:*?"<>|]/g, '_');
    const fileName: string = `provider-${safeName}.json`;
    const ctx: common.Context = getContext(this) as common.Context;
    const docPicker = new picker.DocumentViewPicker(ctx);
    docPicker.save({ newFileNames: [fileName] }).then((uris: string[]): void => {
      if (uris.length > 0) {
        this.writeExportFile(uris[0], json, fileName);
      } else {
        this.exportToAppDir(json, fileName);
      }
    }).catch((): void => {
      this.exportToAppDir(json, fileName);
    });
  }

  private writeExportFile(uri: string, content: string, fileName: string): void {
    try {
      const file: fileIo.File = fileIo.openSync(
        uri, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
      fileIo.writeSync(file.fd, util.TextEncoder.create('utf-8').encodeInto(content));
      fileIo.closeSync(file.fd);
      promptAction.showToast({ message: `已导出 ${fileName}`, duration: 2000 });
    } catch (e) {
      const msg: string = (e as Error).message;
      promptAction.showToast({ message: `导出失败：${msg}`, duration: 2000 });
    }
  }

  private exportToAppDir(content: string, fileName: string): void {
    const ctx: common.Context = getContext(this) as common.Context;
    const dir: string = `${ctx.filesDir}/exports`;
    try {
      fileIo.mkdirSync(dir, true);
    } catch {
      // 已存在
    }
    const path: string = `${dir}/${fileName}`;
    try {
      const file: fileIo.File = fileIo.openSync(
        path, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
      fileIo.writeSync(file.fd, util.TextEncoder.create('utf-8').encodeInto(content));
      fileIo.closeSync(file.fd);
      promptAction.showToast({ message: `已保存到应用目录 ${fileName}`, duration: 2200 });
    } catch (e) {
      const msg: string = (e as Error).message;
      promptAction.showToast({ message: `导出失败：${msg}`, duration: 2000 });
    }
  }

  private removeSelf(): void {
    const raw: ProviderSetting | null = this.provider;
    if (raw === null || this.ioBusy) return;
    this.ioBusy = true;
    AlertDialog.show({
      title: getProductKind() === 'deepread' ? '删除模型服务' : '删除 Provider',
      message: `删除「${this.name}」?其 API Key 与模型配置将从本机移除。`,
      primaryButton: { value: '取消', action: (): void => { this.ioBusy = false; } },
      secondaryButton: {
        value: '删除',
        fontColor: ERROR,
        action: (): void => {
          let deletedGoogle: ProviderSettingGoogle | null = null;
          loadProviders(getChatKvStore()).then((list: ProviderSetting[] | null): Promise<void> => {
            const current: ProviderSetting | undefined = (list ?? []).find((item: ProviderSetting): boolean => item.id === raw.id);
            if (current !== undefined && current.type === 'google') deletedGoogle = current as ProviderSettingGoogle;
            const next: ProviderSetting[] = (list ?? []).filter(
              (p: ProviderSetting): boolean => p.id !== raw.id);
            return saveProviders(getChatKvStore(), next);
          }).then(async (): Promise<void> => {
            if (deletedGoogle !== null) {
              try { await cleanupDeletedGoogleProvider(deletedGoogle); }
              catch (error) {
                promptAction.showToast({ message: error instanceof Error ? error.message : '服务商已删除，但 Google 凭据清理失败。', duration: 2400 });
              }
            }
            this.ioBusy = false;
            router.back();
          }).catch((e: Error): void => {
            this.ioBusy = false;
            this.savedMsg = `删除失败：${e.message}`;
          });
        },
      },
    });
  }

  private readingModelIds(): string[] {
    const ids: string[] = [];
    for (const part of this.modelsText.split(/[,\n]/)) {
      const id: string = part.trim();
      if (id.length > 0 && ids.indexOf(id) < 0) ids.push(id);
    }
    return ids;
  }

  private readingModelName(id: string): string {
    const model: ProviderModel | undefined = this.provider?.models.find((item: ProviderModel): boolean => item.modelId === id);
    return model !== undefined && model.displayName.length > 0 ? model.displayName : id;
  }

  private async openReadingModel(id: string): Promise<void> {
    const model: ProviderModel | undefined = this.provider?.models.find((item: ProviderModel): boolean => item.modelId === id);
    if (model === undefined) { this.savedMsg = '请先保存新模型，再编辑模型参数。'; return; }
    await this.openModelParameters(model.id);
  }

  private async saveAndBack(): Promise<void> {
    if (await this.save()) router.back();
  }

  private async configureReadingGoogle(): Promise<void> {
    if (await this.save()) this.openGoogleAuth();
  }

  @Builder
  readingField(options: ProviderFieldOptions): void {
    Column({ space: 6 }) {
      Text(options.label).fontSize(13).fontColor(D.muted).width('100%')
      TextInput({ text: options.value, placeholder: options.placeholder })
        .type(options.obscure ? InputType.Password : InputType.Normal)
        .fontSize(16).fontColor(D.ink).placeholderColor(D.muted)
        .height(46).backgroundColor(D.paper).borderRadius(10)
        .padding({ left: 12, right: 12 }).enabled(!this.ioBusy)
        .onChange(options.onChange)
    }.width('100%').padding({ left: 16, right: 16, top: 10, bottom: 10 })
  }

  @Builder
  ReadingConnection(): void {
    DeepReadSettingsToggle({ label: '启用服务', checked: this.providerEnabled,
      onChange: (value: boolean): void => { this.providerEnabled = value; } })
    this.readingField({ label: '名称', value: this.name, placeholder: '模型服务名称', obscure: false, mono: false,
      onChange: (value: string): void => { this.name = value; } })
    if (this.provider !== null && this.provider.type === 'openai') {
      DeepReadSettingsPicker({ label: '认证方式', options: this.authModeOptions(), selected: this.authModeIndex(),
        onSelect: (index: number): void => { this.selectAuthMode(index); } })
      if (this.authMode === 'codex_oauth') {
        DeepReadSettingsRow({ label: this.codexBusy ? '等待登录…' : this.codexAccount.length > 0 ? '重新登录 ChatGPT' : '登录 ChatGPT',
          detail: this.codexAccount, valueAccent: true,
          onTap: (): void => { this.startCodexLogin(); } })
        if (this.codexStatus.length > 0) { DeepReadSettingsNote({ text: this.codexStatus }) }
      } else if (this.authMode === 'grok_oauth') {
        DeepReadSettingsRow({ label: this.grokBusy ? '等待登录…' : this.grokAccount.length > 0 ? '重新登录 Grok' : '登录 Grok',
          detail: this.grokAccount, onTap: (): void => { this.startGrokLogin(); } })
        if (this.grokStatus.length > 0) { DeepReadSettingsNote({ text: this.grokStatus }) }
      }
    }
    if ((this.provider?.type !== 'google' || this.isGoogleApiKey())
      && this.authMode !== 'codex_oauth' && this.authMode !== 'grok_oauth') {
      this.readingField({ label: 'API Key', value: this.apiKey, placeholder: '填写服务商提供的密钥', obscure: true, mono: true,
        onChange: (value: string): void => { this.apiKey = value; } })
    }
    if (this.provider?.type !== 'google' || this.isGoogleApiKey()) {
      if (this.pinnedBaseUrl().length > 0) {
        Text(`服务地址 · ${this.pinnedBaseUrl()}`).fontSize(13).fontColor(D.muted)
          .width('100%').padding(16).wordBreak(WordBreak.BREAK_ALL)
      } else {
        this.readingField({ label: '服务地址', value: this.baseUrl, placeholder: 'https://api.example.com/v1', obscure: false, mono: true,
          onChange: (value: string): void => { this.baseUrl = value; } })
      }
    }
    if (this.provider?.type === 'google') {
      DeepReadSettingsRow({ label: '保存并配置 Google 认证', detail: 'API Key、服务账号或账号授权', last: true,
        onTap: (): void => { this.configureReadingGoogle(); } })
    }
  }

  @Builder
  ReadingModels(): void {
    ForEach(this.readingModelIds(), (id: string, index: number): void => {
      DeepReadSettingsRow({ label: this.readingModelName(id), detail: id,
        value: this.provider?.models.some((model: ProviderModel): boolean => model.modelId === id) ? '参数' : '待保存',
        last: index === this.readingModelIds().length - 1,
        onTap: (): void => { this.openReadingModel(id); } })
    }, (id: string): string => id)
    if (this.readingModelIds().length === 0) {
      Text('填写模型 ID，或从服务商拉取模型。').fontSize(15).fontColor(D.muted)
        .width('100%').padding(16)
    }
    Column({ space: 6 }) {
      Text('模型 ID').fontSize(13).fontColor(D.muted).width('100%')
      TextArea({ text: this.modelsText, placeholder: '每行一个模型 ID，也可用逗号分隔' })
        .fontSize(15).fontColor(D.ink).placeholderColor(D.muted).backgroundColor(D.paper)
        .borderRadius(10).height(84).padding(12).enabled(!this.ioBusy)
        .onChange((value: string): void => { this.modelsText = value; })
    }.width('100%').padding(16)
    DeepReadSettingsRow({ label: this.fetchBusy ? '正在拉取模型…' : '拉取可用模型', last: true,
      onTap: (): void => { this.fetchModels(); } }).enabled(!this.fetchBusy && !this.ioBusy)
    if (this.fetchMsg.length > 0) {
      Text(this.fetchMsg).fontSize(13).fontColor(this.fetchMsg.startsWith('拉取失败') ? D.danger : D.muted)
        .lineHeight(19).width('100%').padding({ left: 16, right: 16, bottom: 12 })
    }
    if (this.fetchedIds.length > 0) {
      DeepReadSettingsRow({ label: '添加全部发现的模型', value: `${this.fetchedIds.length} 个`,
        onTap: (): void => { this.addAllFetched(); } })
      ForEach(this.fetchedIds, (id: string, index: number): void => {
        DeepReadSettingsRow({ label: id, value: '添加', valueAccent: true,
          last: index === this.fetchedIds.length - 1,
          onTap: (): void => { this.addFetchedId(id); } })
      }, (id: string): string => `found-${id}`)
    }
  }

  @Builder
  ReadingRequestOptions(): void {
    if (this.provider?.type === 'openai') {
      DeepReadSettingsToggle({ label: 'Responses API', checked: this.useResponseApi,
        onChange: (value: boolean): void => { this.useResponseApi = value; } })
      this.readingField({ label: '请求路径', value: this.chatPath, placeholder: '/chat/completions', obscure: false, mono: true,
        onChange: (value: string): void => { this.chatPath = value; } })
    } else if (this.provider?.type === 'google' && this.isGoogleApiKey()) {
      DeepReadSettingsToggle({ label: 'Vertex AI', checked: this.vertexAI,
        onChange: (value: boolean): void => { this.vertexAI = value; } })
      if (this.vertexAI) {
        this.readingField({ label: 'Google Cloud 项目', value: this.projectId, placeholder: 'project-id', obscure: false, mono: true,
          onChange: (value: string): void => { this.projectId = value; } })
        this.readingField({ label: '区域', value: this.location, placeholder: 'us-central1 或 global', obscure: false, mono: true,
          onChange: (value: string): void => { this.location = value; } })
      }
    } else if (this.provider?.type === 'claude') {
      DeepReadSettingsToggle({ label: '提示词缓存', checked: this.promptCaching, last: true,
        onChange: (value: boolean): void => { this.promptCaching = value; } })
    }
    DeepReadSettingsRow({ label: '导出已保存配置', detail: '不包含 API Key 或账号令牌', last: true,
      onTap: (): void => { this.exportProvider(); } })
  }

  private toggleReadingAdvanced(): void {
    if (this.ioBusy) return;
    if (this.reduceMotion || this.appBackgrounded) {
      this.advancedOpen = !this.advancedOpen;
      return;
    }
    this.getUIContext().animateTo({ duration: 220, curve: Curve.EaseOut }, (): void => {
      this.advancedOpen = !this.advancedOpen;
    });
  }

  @Builder
  ReadingProviderPage(): void {
    Stack({ alignContent: Alignment.Bottom }) {
      Column() {
        DeepReadSettingsHeader({ title: '服务配置', onBack: (): void => { if (!this.ioBusy) router.back(); } })
        Scroll() {
          Column() {
            if (this.loading || this.provider === null) {
              DeepReadSettingsNote({ text: this.loading ? '正在读取…' : this.loadError })
            } else {
              DeepReadSettingsSection({ title: '连接信息' })
              DeepReadSettingsGroup({ content: (): void => { this.ReadingConnection(); } }).enabled(!this.ioBusy)
              if (this.unsupportedAuthMsg.length > 0) { DeepReadSettingsNote({ text: this.unsupportedAuthMsg }) }
              DeepReadSettingsSection({ title: '模型' })
              DeepReadSettingsGroup({ content: (): void => { this.ReadingModels(); } }).enabled(!this.ioBusy)
              DeepReadSettingsNote({ text: '模型及连接信息统一保存。选择文章生成使用的模型，请返回设置页。' })
              DeepReadSettingsSection({ title: '其他选项' })
              DeepReadSettingsRow({ label: '高级设置', value: this.advancedOpen ? '收起' : '展开', last: true,
                onTap: (): void => { this.toggleReadingAdvanced(); } })
                .backgroundColor(D.card).borderRadius(D.radius)
              if (this.advancedOpen) {
                DeepReadSettingsGroup({ content: (): void => { this.ReadingRequestOptions(); } })
                  .margin({ top: 8 }).enabled(!this.ioBusy)
                  .transition(TransitionEffect.OPACITY.animation({ duration: this.reduceMotion || this.appBackgrounded ? 0 : 160 }))
              }
              if (this.savedMsg.length > 0) {
                Text(this.savedMsg).fontSize(13).lineHeight(19).fontColor(this.savedMsg === '已保存' ? D.done : D.danger)
                  .width('100%').padding({ left: 16, right: 16, top: 16 })
              }
              DeepReadSettingsButton({ label: this.ioBusy ? '正在保存…' : '保存并返回', icon: 'check', disabled: this.ioBusy,
                onTap: (): void => { this.saveAndBack(); } }).margin({ top: 24 })
              DeepReadSettingsRow({ label: '删除模型服务', destructive: true, chevron: false, last: true,
                onTap: (): void => { this.removeSelf(); } }).margin({ top: 16 }).enabled(!this.ioBusy)
            }
          }.width('100%').padding({ left: D.gutter, right: D.gutter, bottom: DeepReadDockLayout.bottomInset(this.navBarHeight) })
        }.align(Alignment.TopStart).layoutWeight(1).scrollBar(BarState.Off)
      }.width('100%').height('100%').backgroundColor(D.paper)
      DeepReadSubpageDock({ onBeforeLeave: (): boolean => { return !this.ioBusy; } }).align(Alignment.Bottom).hitTestBehavior(HitTestMode.Transparent)
    }.width('100%').height('100%')
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }

  @Builder
  field(options: ProviderFieldOptions) {
    Column() {
      Text(options.label)
        .fontSize(TEXT_STYLES.meta.size)
        .fontWeight(TEXT_STYLES.meta.weight)
        .lineHeight(TEXT_STYLES.meta.lineHeight)
        .fontFamily(TEXT_STYLES.meta.family)
        .fontColor(INK3)
        .margin({ left: 2, bottom: 6 })
      FlatTextField({
        value: options.value,
        placeholder: options.placeholder,
        mono: options.mono,
        obscure: options.obscure,
        onChange: options.onChange,
      })
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .margin({ bottom: 12 })
  }

  @Builder
  tabHeader() {
    Row() {
      Column() {
        Text('配置')
          .fontSize(TEXT_STYLES.body.size)
          .fontWeight(this.tab === 0 ? 600 : TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight)
          .fontFamily(TEXT_STYLES.body.family)
          .letterSpacing(1.3)
          .fontColor(this.tab === 0 ? INK : INK3)
        Column()
          .width('100%').height(2).borderRadius(1)
          .backgroundColor(this.tab === 0 ? this.accentColorStore : Color.Transparent)
          .margin({ top: 6, left: 12, right: 12 })
      }
      .layoutWeight(1)
      .onClick((): void => {
        animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => { this.tab = 0; });
      })

      Column() {
        Text('模型')
          .fontSize(TEXT_STYLES.body.size)
          .fontWeight(this.tab === 1 ? 600 : TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight)
          .fontFamily(TEXT_STYLES.body.family)
          .letterSpacing(1.3)
          .fontColor(this.tab === 1 ? INK : INK3)
        Column()
          .width('100%').height(2).borderRadius(1)
          .backgroundColor(this.tab === 1 ? this.accentColorStore : Color.Transparent)
          .margin({ top: 6, left: 12, right: 12 })
      }
      .layoutWeight(1)
      .onClick((): void => {
        animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => { this.tab = 1; });
      })
    }
    .width('100%')
    .padding({ left: 18, right: 18 })
    .border({ width: { bottom: 1 }, color: { bottom: LINE } })
  }

  build() {
    Stack() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      if (getProductKind() === 'deepread') {
        this.ReadingProviderPage()
      } else {
      Column() {
      // 头部:back + // PROVIDERS + slug16 Bold/点/摘要 + (占位)
      Row() {
        Row() {
          Icon({ name: 'back', iconSize: 24, color: INK })
        }
        .width(48).height(48)
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .margin({ left: -10 })
        .onClick((): void => { router.back(); })
        .accessibilityText('返回')

        Column() {
          Row() {
            Text('//').fontSize(11).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(11))
              .fontFamily(FONT_MONO_MEDIUM).fontColor(this.accentColorStore)
            Text(' 提供商').fontSize(11).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(11))
              .fontFamily(FONT_MONO_MEDIUM).fontColor(INK3).letterSpacing(1.3)
          }
          .alignItems(VerticalAlign.Center)
          Row({ space: 7 }) {
            Text(this.provider !== null ? providerSlug(this.provider) : '--')
              .fontSize(16).fontWeight(FontWeight.Bold).lineHeight(lineHeightForSize(16)).fontFamily(FONT_MONO)
              .fontColor(INK)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            if (this.provider !== null && this.provider.enabled) {
              Column().width(6).height(6).borderRadius(3)
                .backgroundColor(this.accentColorStore)
            }
          }
          .alignItems(VerticalAlign.Center)
          Row({ space: 6 }) {
            Text(`${this.name} · ${(this.provider !== null ? providerAuthLabel(this.provider) : '—')} · ${this.provider !== null ? this.provider.models.length : 0} 个模型`)
              .fontSize(MONO_SMALL).fontWeight(FontWeight.Normal).lineHeight(lineHeightForSize(MONO_SMALL))
              .fontFamily(FONT_MONO).fontColor(INK3)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          }
          .alignItems(VerticalAlign.Center)
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        .margin({ left: 8 })

        // 导出脱敏 JSON(不含 API Key)
        BtnGhost({
          label: '导出',
          onTap: (): void => { this.exportProvider(); },
        })
      }
      .width('100%')
      .constraintSize({ minHeight: 64 })
      .padding({ left: 20, right: 14, top: this.statusBarHeight + 8, bottom: 8 })
      .alignItems(VerticalAlign.Center)

      this.tabHeader()

      Scroll() {
        Column() {
          if (this.tab === 0) {
            SectionHeader({ label: '认证与端点' })
            ToggleRow({
              label: '启用', checked: this.providerEnabled, isLast: false,
              onToggle: (): void => { this.providerEnabled = !this.providerEnabled; },
            })

            if (this.provider !== null && this.provider.type === 'openai') {
              ToggleRow({
                label: '使用 Responses API',
                checked: this.useResponseApi,
                isLast: false,
                onToggle: (): void => { this.useResponseApi = !this.useResponseApi; },
              })
              // 认证方式(brand 决定可选:API Key / Codex 登录 / Coding Plan)
              Column() {
                Text('认证方式')
                  .fontSize(TEXT_STYLES.meta.size)
                  .fontWeight(TEXT_STYLES.meta.weight)
                  .lineHeight(TEXT_STYLES.meta.lineHeight)
                  .fontFamily(TEXT_STYLES.meta.family)
                  .fontColor(INK3)
                  .margin({ left: 2, bottom: 6 })
                Seg({
                  options: this.authModeOptions(),
                  selected: this.authModeIndex(),
                  onSelect: (idx: number): void => { this.selectAuthMode(idx); },
                })
                if (this.authMode === 'codex_oauth') {
                  Row() {
                    Text(this.codexAccount.length > 0 ? `已登录:${this.codexAccount}` : '设备码授权,免 API Key')
                      .fontSize(LABEL).fontColor(INK3).layoutWeight(1)
                      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                    Text(this.codexBusy ? '等待登录…' : (this.codexAccount.length > 0 ? '重新登录' : '登录 ChatGPT'))
                      .fontSize(META).fontColor(this.codexBusy ? INK3 : this.accentColorStore)
                      .padding({ left: 12, top: 8, bottom: 8 })
                      .onClick((): void => { this.startCodexLogin(); })
                  }
                  .width('100%').margin({ top: 10 })
                  if (this.codexStatus.length > 0) {
                    Text(this.codexStatus)
                      .fontSize(META).fontColor(ERROR).width('100%').margin({ top: 4 })
                  }
                }
                if (this.authMode === 'grok_oauth') {
                  Row() {
                    Text(this.grokAccount.length > 0 ? `已登录:${this.grokAccount}` : '浏览器授权,免 API Key')
                      .fontSize(LABEL).fontColor(INK3).layoutWeight(1)
                      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                    Text(this.grokAccount.length > 0 ? '重新登录' : '登录 xAI')
                      .fontSize(META).fontColor(this.accentColorStore)
                      .padding({ left: 12, top: 8, bottom: 8 })
                      .onClick((): void => { this.startGrokLogin(); })
                  }
                  .width('100%').margin({ top: 10 })
                  if (this.grokStatus.length > 0) {
                    Text(this.grokStatus)
                      .fontSize(META).fontColor(ERROR).width('100%').margin({ top: 4 })
                  }
                }
              }
              .width('100%').alignItems(HorizontalAlign.Start)
              .padding({ left: 16, right: 16, top: 12 })
            }

            this.field({
              label: '名称', placeholder: 'Provider 名称', value: this.name,
              onChange: (v: string): void => { this.name = v; }, obscure: false, mono: false,
            })

            if (this.unsupportedAuthMsg.length > 0) {
              Column() {
                Text(this.unsupportedAuthMsg)
                  .fontSize(LABEL).fontWeight(FontWeight.Normal).lineHeight(lineHeightForSize(LABEL))
                    .fontFamily(TEXT_STYLES.eyebrow.family).fontColor(ERROR)
              }
              .width('100%')
              .padding({ left: 16, right: 16, top: 12, bottom: 12 })
              .backgroundColor(SURFACE)
              .border({ width: 1, color: ERROR, radius: RADIUS })
              .margin({ bottom: 12 })
            }

            if (this.provider === null || this.provider.type !== 'google' || this.isGoogleApiKey()) {
              this.field({
                label: 'API Key',
                placeholder: this.provider !== null && this.provider.type === 'claude' ? 'sk-ant-…' : 'sk-…',
                value: this.apiKey, onChange: (v: string): void => { this.apiKey = v; },
                obscure: true, mono: true,
              })
            }

            if (this.provider !== null && this.provider.type === 'openai') {
              if (this.pinnedBaseUrl().length > 0) {
                // 只读展示当前认证模式要求的固定官方地址
                Column() {
                  Text('Base URL')
                    .fontSize(TEXT_STYLES.meta.size)
                    .fontWeight(TEXT_STYLES.meta.weight)
                    .lineHeight(TEXT_STYLES.meta.lineHeight)
                    .fontFamily(TEXT_STYLES.meta.family)
                    .fontColor(INK3)
                    .margin({ left: 2, bottom: 6 })
                  Row() {
                    Text(this.pinnedBaseUrl())
                      .fontSize(BODY).fontFamily(FONT_MONO).fontColor(INK2)
                      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                  }
                  .width('100%')
                  .padding({ left: 14, right: 14, top: 12, bottom: 12 })
                  .backgroundColor(SURFACE2)
                  .borderRadius(12)
                  .border({ width: 1, color: LINE })
                }
                .width('100%')
                .alignItems(HorizontalAlign.Start)
                .margin({ bottom: 12 })
              } else {
                this.field({
                  label: 'Base URL', placeholder: 'https://api.openai.com/v1', value: this.baseUrl,
                  onChange: (v: string): void => { this.baseUrl = v; }, obscure: false, mono: true,
                })
              }
              this.field({
                label: 'API Path', placeholder: '/chat/completions', value: this.chatPath,
                onChange: (v: string): void => { this.chatPath = v; }, obscure: false, mono: true,
              })
            } else if (this.provider !== null && this.provider.type === 'google') {
              BtnGhost({ label: 'Google 认证与服务账号', onTap: (): void => { this.openGoogleAuth(); } })
                .width('100%').margin({ bottom: 12 })
              if (this.isGoogleApiKey()) {
                ToggleRow({
                label: 'Vertex AI', checked: this.vertexAI, isLast: this.vertexAI,
                onToggle: (): void => { this.vertexAI = !this.vertexAI; },
                })
                if (!this.vertexAI) {
                  this.field({
                    label: 'Base URL', placeholder: 'https://generativelanguage.googleapis.com/v1beta',
                    value: this.baseUrl, onChange: (v: string): void => { this.baseUrl = v; },
                    obscure: false, mono: true,
                  })
                }
              }
            } else if (this.provider !== null && this.provider.type === 'claude') {
              this.field({
                label: 'Base URL', placeholder: 'https://api.anthropic.com/v1', value: this.baseUrl,
                onChange: (v: string): void => { this.baseUrl = v; }, obscure: false, mono: true,
              })
              ToggleRow({
                label: 'Prompt Caching', checked: this.promptCaching, isLast: true,
                onToggle: (): void => { this.promptCaching = !this.promptCaching; },
              })
            }

            // 保存(Android ProviderSplitBar 语义:显式提交)
            Row() {
              Text(this.savedMsg.length > 0 ? this.savedMsg : ' ')
                .fontSize(BODY2).lineHeight(TEXT_STYLES.secondary.lineHeight)
                .fontFamily(TEXT_STYLES.secondary.family)
                .fontColor(this.savedMsg.startsWith('保存失败') ? ERROR : this.accentColorStore)
                .layoutWeight(1)
              BtnFill({ label: '保存', onTap: (): void => { this.save(); } })
            }
            .width('100%')
            .alignItems(VerticalAlign.Center)
            .margin({ top: 8, bottom: 20 })

            // 删除置底
            Row() {
              Text('删除此 Provider')
                .fontSize(BODY2).fontColor(ERROR).lineHeight(lineHeightForSize(BODY2)).fontFamily(TEXT_STYLES.secondary.family)
                .padding({ top: 12, bottom: 12 })
                .onClick((): void => this.removeSelf())
              Blank()
            }
            .width('100%')
          } else {
            SectionHeader({ label: '模型列表' })
            Column() {
              ForEach(this.provider !== null ? this.provider.models : [],
                (m: ProviderModel, mIdx: number): void => {
                  Column() {
                    Row() {
                      Column() {
                        Text(m.displayName.length > 0 ? m.displayName : m.modelId)
                          .fontSize(BODY).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
                          .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
                          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                        Text(m.modelId)
                          .fontSize(META).fontWeight(FontWeight.Medium).lineHeight(TEXT_STYLES.meta.lineHeight)
                          .fontFamily(FONT_MONO).fontColor(INK3)
                          .margin({ top: 3 })
                          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                      }
                      .alignItems(HorizontalAlign.Start).layoutWeight(1)
                      if (m.type === 'image') {
                        Chip({ text: '生图' }).margin({ right: 8 })
                      }
                      Text('参数')
                        .fontSize(META).fontWeight(FontWeight.Medium).lineHeight(TEXT_STYLES.meta.lineHeight)
                        .fontFamily(FONT_MONO_MEDIUM).fontColor(this.accentColorStore)
                    }
                    .width('100%').alignItems(VerticalAlign.Center)
                    .constraintSize({ minHeight: 64 })
                    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
                    .onClick((): void => {
                      this.openModelParameters(m.id);
                    })
                    if (mIdx < (this.provider !== null ? this.provider.models.length : 0) - 1) {
                      PaperSeparator()
                    }
                  }
                  .width('100%')
                }, (m: ProviderModel): string => m.id)
              if (this.provider !== null && this.provider.models.length === 0) {
                Text('暂无模型 — 在下方以逗号分隔添加 modelId')
                  .fontSize(LABEL).lineHeight(TEXT_STYLES.eyebrow.lineHeight)
                  .fontFamily(TEXT_STYLES.eyebrow.family).fontColor(INK4)
                  .width('100%').textAlign(TextAlign.Center)
                  .padding({ top: 16, bottom: 16 })
              }
            }
            .width('100%')

            Column() {
              Text('添加模型(逗号分隔 modelId,保存后生效)')
                .fontSize(META).fontWeight(FontWeight.Medium).lineHeight(TEXT_STYLES.meta.lineHeight)
                .fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
                .margin({ left: 16, bottom: 8 })
              TextArea({ text: this.modelsText, placeholder: 'gpt-4o, gpt-4o-mini' })
                .fontSize(BODY2).lineHeight(TEXT_STYLES.secondary.lineHeight)
                .fontFamily(FONT_MONO).fontColor(INK)
                .backgroundColor(SURFACE2).borderRadius(8)
                .border({ width: 1, color: LINE })
                .padding({ left: 14, right: 14, top: 12, bottom: 12 })
                .height(80)
                .onChange((v: string): void => { this.modelsText = v; })
              Row() {
                Text(this.fetchMsg)
                  .fontSize(META).fontColor(INK3).layoutWeight(1)
                  .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                Text(this.fetchBusy ? '拉取中…' : '拉取可用模型')
                  .fontSize(META).fontColor(this.fetchBusy ? INK3 : this.accentColorStore)
                  .padding({ left: 12, top: 6, bottom: 6 })
                  .onClick((): void => { this.fetchModels(); })
              }
              .width('100%').padding({ left: 16, right: 16 }).margin({ top: 10 })
              if (this.fetchedIds.length > 0) {
                Column() {
                  Row() {
                    Text('可添加')
                      .fontSize(META).fontColor(INK3).layoutWeight(1)
                    Text('全部添加')
                      .fontSize(META).fontColor(this.accentColorStore)
                      .padding({ left: 12, top: 6, bottom: 6 })
                      .onClick((): void => { this.addAllFetched(); })
                  }
                  .width('100%')
                  Flex({ wrap: FlexWrap.Wrap }) {
                    ForEach(this.fetchedIds, (id: string): void => {
                      Text(id)
                        .fontSize(META).fontFamily(FONT_MONO).fontColor(INK)
                        .padding({ left: 10, right: 10, top: 6, bottom: 6 })
                        .backgroundColor(RAISED)
                        .border({ width: 1, color: LINE, radius: 8 })
                        .margin({ right: 8, top: 8 })
                        .onClick((): void => { this.addFetchedId(id); })
                    }, (id: string): string => id)
                  }
                  .width('100%')
                }
                .width('100%').alignItems(HorizontalAlign.Start)
                .padding({ left: 16, right: 16, top: 4, bottom: 4 })
              }
              Row() {
                Blank()
                BtnFill({ label: '保存模型', onTap: (): void => { this.save(); } })
              }
              .width('100%').padding({ left: 16, right: 16 }).margin({ top: 10 })
            }
            .width('100%').alignItems(HorizontalAlign.Start).margin({ top: 12 })
          }
        }
        .width('100%')
        .padding({ left: 18, right: 18, bottom: 40 })
      }
      .align(Alignment.TopStart)
      .layoutWeight(1)
      .backgroundColor(BG)
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])

      }

    // Codex 设备码登录叠层
    if (this.codexLoginOpen) {
      this.CodexLoginOverlay()
    }
    // Grok 授权粘贴叠层
    if (this.grokLoginOpen) {
      this.GrokLoginOverlay()
    }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%')
  }
}
