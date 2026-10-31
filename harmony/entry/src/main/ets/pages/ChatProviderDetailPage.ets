
// ChatProviderDetailPage — 提供商详情(Android SettingProviderDetailPage 1:1 结构)
// 头部:// PROVIDERS eyebrow + slug 16 Bold + 在线点 + name·auth·N 个模型;
// 配置 / 模型 双 tab(accent 2dp 下划线);配置 tab = 启用/名称/API Key/端点等;
// 模型 tab = 模型列表 + 参数入口 + 逗号编辑;保存显式按钮;删除置底。
// 新增流:列表页模板选择后经暂存函数交接对象进入本页,保存时插入。
import { getProductKind } from '../platform_impl/ProductIdentity.ets';

import { router, promptAction } from '@kit.ArkUI';

import { common } from '@kit.AbilityKit';

import type { ProviderSetting, ProviderModel, GoogleCatalogModel } from '@amber/chat-domain';
import { loadProviders } from '@amber/chat-domain';
import { getChatKvStore, getAppContainer } from '../di/AppContainer.ets';
import { loadGoogleModelCatalog } from '../platform_impl/GoogleAuthSupport.ets';
import { cleanupDeletedGoogleProvider } from '../platform_impl/GoogleProviderConfigSupport.ets';

import {
  requestCodexDeviceCode, pollCodexDeviceCode, fetchCodexModels, loadCodexTokens, clearCodexTokens
} from '../platform_impl/CodexOAuthClient.ets';
import {
  buildGrokAuthorization, extractGrokCode, exchangeGrokCode, loadGrokTokens, clearGrokTokens, GROK_FALLBACK_MODELS
} from '../platform_impl/GrokOAuthClient.ets';

import { ERROR } from '../design/tokens.ets';

import { ProviderEditSession } from '../platform_impl/provider/ProviderEditSession.ets';
import type { ProviderLoginContext } from '../platform_impl/provider/ProviderLoginSession.ets';
import type { ProviderCatalogDraft } from '../platform_impl/provider/ProviderModelCatalogSession.ets';
import { ProviderLoginSession } from '../platform_impl/provider/ProviderLoginSession.ets';
import { ProviderModelCatalogSession } from '../platform_impl/provider/ProviderModelCatalogSession.ets';
import { takePendingProvider } from '../platform_impl/provider/PendingProviderDraft.ets';
import { exportProvider } from '../platform_impl/provider/ProviderExport.ets';
import { ProviderReadingForm } from '../components/provider/ProviderReadingForm.ets';
import { ProviderConnectionForm } from '../components/provider/ProviderConnectionForm.ets';
import { ProviderLoginOverlays } from '../components/provider/ProviderLoginOverlays.ets';

@Entry
@Component
struct ChatProviderDetailPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('reduceMotion') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') appBackgrounded: boolean = false;
  @State edit: ProviderEditSession = new ProviderEditSession();
  @State login: ProviderLoginSession = new ProviderLoginSession();
  @State catalog: ProviderModelCatalogSession = new ProviderModelCatalogSession();
  @State tab: number = 0;
  @State advancedOpen: boolean = false;

  aboutToAppear(): void {
    this.edit.start({ kvStore: getChatKvStore(), loadCodexTokens, loadGrokTokens, clearCodexTokens, clearGrokTokens,
      catalogModel: (id: string): GoogleCatalogModel | undefined => this.catalog.catalogModel(id),
      onProviderApplied: (provider: ProviderSetting): void => this.login.loadAccount(provider),
      cleanupDeletedGoogleProvider,
      cleanupWarning: (message: string): void => { promptAction.showToast({ message, duration: 2400 }); },
    });
    this.login.start({
      currentContext: (): ProviderLoginContext => ({ provider: this.edit.provider, authMode: this.edit.authMode, baseUrl: this.edit.baseUrl }),
      setAuthMode: (value: string): void => { this.edit.authMode = value; },
      setApiKey: (value: string): void => { this.edit.apiKey = value; },
      setBaseUrl: (value: string): void => { this.edit.baseUrl = value; },
      setUseResponseApi: (value: boolean): void => { this.edit.useResponseApi = value; },
      setModelsText: (value: string): void => { this.edit.modelsText = value; },
      setUnsupportedAuth: (message: string): void => { this.edit.unsupportedAuthMsg = message; },
      save: (): Promise<boolean> => this.edit.save(),
      abilityContext: (): common.UIAbilityContext => getContext(this) as common.UIAbilityContext,
      requestCodexDeviceCode, pollCodexDeviceCode, fetchCodexModels, loadCodexTokens, loadGrokTokens,
      buildGrokAuthorization, extractGrokCode, exchangeGrokCode, grokFallbackModels: GROK_FALLBACK_MODELS,
    });
    this.catalog.start({
      currentDraft: (): ProviderCatalogDraft => ({ provider: this.edit.provider, authMode: this.edit.authMode, apiKey: this.edit.apiKey,
        baseUrl: this.edit.baseUrl, modelsText: this.edit.modelsText, vertexAI: this.edit.vertexAI }),
      setModelsText: (value: string): void => { this.edit.modelsText = value; },
      httpClient: getAppContainer().httpClient, loadGoogleModelCatalog,
    });
    const params: Record<string, string> | undefined = router.getParams() as Record<string, string> | undefined;
    const id: string = params !== undefined && params['providerId'] !== undefined ? params['providerId'] : '';
    const isNew: boolean = params !== undefined && params['isNew'] === '1';
    // Consume synchronously, before the first asynchronous provider read.
    const draft: ProviderSetting | null = isNew ? takePendingProvider() : null;
    this.edit.load(id, isNew, draft);
  }

  onPageShow(): void { this.edit.reloadReference(); }
  onBackPress(): boolean { return this.edit.ioBusy; }
  aboutToDisappear(): void { this.login.dispose(); this.catalog.dispose(); }

  openGoogleAuth(): void {
    const raw: ProviderSetting | null = this.edit.provider;
    if (raw === null || raw.type !== 'google' || this.edit.ioBusy) return;
    loadProviders(getChatKvStore()).then((all: ProviderSetting[] | null): void => {
      if (!(all ?? []).some((item: ProviderSetting): boolean => item.id === raw.id)) {
        this.edit.savedMsg = '请先点击保存，建立服务商配置后再设置 Google 认证。';
        return;
      }
      router.pushUrl({ url: 'pages/GoogleProviderAuthPage', params: { providerId: raw.id } });
    }).catch((): void => { this.edit.savedMsg = '无法读取已保存的服务商配置。'; });
  }

  async openModelParameters(modelId: string): Promise<void> {
    const raw: ProviderSetting | null = this.edit.provider;
    if (raw === null || this.edit.ioBusy) return;
    try {
      const all: ProviderSetting[] | null = await loadProviders(getChatKvStore());
      if (!(all ?? []).some((item: ProviderSetting): boolean => item.id === raw.id)) {
        this.edit.savedMsg = '请先点击保存，再编辑模型参数。';
        return;
      }
      router.pushUrl({ url: 'pages/ChatModelParamsPage', params: { providerId: raw.id, modelId } });
    } catch (error) {
      this.edit.savedMsg = `无法读取服务商配置：${(error as Error).message}`;
    }
  }

  async openReadingModel(id: string): Promise<void> {
    const model: ProviderModel | undefined = this.edit.provider?.models.find((item: ProviderModel): boolean => item.modelId === id);
    if (model === undefined) { this.edit.savedMsg = '请先保存新模型，再编辑模型参数。'; return; }
    await this.openModelParameters(model.id);
  }

  async saveAndBack(): Promise<void> {
    if (await this.edit.save()) router.back();
  }

  async configureReadingGoogle(): Promise<void> {
    if (await this.edit.save()) this.openGoogleAuth();
  }

  toggleReadingAdvanced(): void {
    if (this.edit.ioBusy) return;
    if (this.reduceMotion || this.appBackgrounded) {
      this.advancedOpen = !this.advancedOpen;
      return;
    }
    this.getUIContext().animateTo({ duration: 220, curve: Curve.EaseOut }, (): void => {
      this.advancedOpen = !this.advancedOpen;
    });
  }

  private removeSelf(): void {
    const raw: ProviderSetting | null = this.edit.provider;
    if (raw === null || this.edit.ioBusy) return;
    this.edit.ioBusy = true;
    AlertDialog.show({
      title: getProductKind() === 'deepread' ? '删除模型服务' : '删除 Provider',
      message: `删除「${this.edit.name}」?其 API Key 与模型配置将从本机移除。`,
      primaryButton: { value: '取消', action: (): void => { this.edit.ioBusy = false; } },
      secondaryButton: { value: '删除', fontColor: ERROR, action: (): void => {
        this.edit.deleteProvider(raw.id).then((deleted: boolean): void => { if (deleted) router.back(); });
      } },
    });
  }

  private exportCurrentProvider(): void {
    exportProvider(this.edit.provider, getContext(this) as common.Context);
  }

  build(): void {
    Stack() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
        if (getProductKind() === 'deepread') {
          ProviderReadingForm({ edit: this.edit, login: this.login, catalog: this.catalog,
            onOpenReadingModel: (id: string): void => { this.openReadingModel(id); },
            onSaveAndBack: (): void => { this.saveAndBack(); },
            onConfigureReadingGoogle: (): void => { this.configureReadingGoogle(); },
            onRemoveSelf: (): void => { this.removeSelf(); },
            onExportProvider: (): void => { this.exportCurrentProvider(); },
            onBack: (): void => { router.back(); },
            advancedOpen: this.advancedOpen,
            onToggleReadingAdvanced: (): void => { this.toggleReadingAdvanced(); },
          })
        } else {
          ProviderConnectionForm({ edit: this.edit, login: this.login, catalog: this.catalog,
            onOpenGoogleAuth: (): void => { this.openGoogleAuth(); },
            onOpenModelParameters: (id: string): void => { this.openModelParameters(id); },
            onRemoveSelf: (): void => { this.removeSelf(); },
            onExportProvider: (): void => { this.exportCurrentProvider(); },
            onBack: (): void => { router.back(); },
            tab: this.tab,
          })
        }
        ProviderLoginOverlays({ login: this.login })
      }, (epoch: number): string => `theme-${epoch}`)
    }.width('100%').height('100%')
  }
}
