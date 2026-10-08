import { getProductKind } from '../platform_impl/ProductIdentity.ets';
// SettingDeepReadPage — 深度阅读设置(AI 配置 + 热榜源开关 + 搜索源配置面板)
//   原误置于 HistoryPage,归位独立页(首页功能区/设置入口可达)。
//
// 2026-09-27 二级页 iOS 对齐(无截图,沿用规范):仅换视觉骨架
//   CenteredTitleBar / PlainSection / CardGroup(r18+描边)+ h16 /
//   表单控件装入卡片(标题 16 medium sans、字段标 mono meta、值 12 muted)/
//   FooterNote 组下脚注,逻辑零改动(读写/测试连通/保存/模型库增删全原样)。
import {
  ACCENT, BG, INK, INK3, INK4, SURFACE, SURFACE2, ERROR, SIGNAL, LINE,
  TEXT_STYLES, GUTTER, MOTION_OVERLAY, accentSoftBg, lineHeightForSize,
} from '../design/tokens.ets';
import { FONT_SANS_MEDIUM, FONT_MONO_MEDIUM } from '../design/fonts.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardRow, CardToggleRow, FooterNote,
  BtnFill, BtnGhost, PageSpacer,
} from '../components/PageTemplate.ets';
import { PillSelect } from '../components/ui/Select.ets';
import { Icon } from '../components/ui/Icon.ets';
import { BUILTIN_TEMPLATES, loadCustomTemplates } from '../platform_impl/DeepReadTemplate.ets';
import type { DeepReadTemplateDef, CustomTemplateDef } from '../platform_impl/DeepReadTemplate.ets';
import { router, promptAction, curves } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { getAppContainer, getChatKvStore, getModelRegistry, resolveChatProviderChoice } from '../di/AppContainer.ets';
import type { DeepReadModelOption } from '../di/AppContainer.ets';
import { defaultSearchPrefs, loadSearchPrefs, updateSearchPrefs } from '@amber/chat-domain';
import type { SearchSettings, SearchServiceOptions } from '@amber/chat-domain';
import type { TaskModelReference } from '@amber/chat-domain';
import { loadDeepReadModelSelection, saveDeepReadModelSelection } from '../platform_impl/DeepReadModelSelection.ets';
import type { DeepReadModelSelectionState } from '../platform_impl/DeepReadModelSelection.ets';
import { chatChoiceToDeepReadDraft } from '../platform_impl/ChatDeepReadDraft.ets';
import type { ChatDeepReadDraftResult } from '../platform_impl/ChatDeepReadDraft.ets';
import type { Storage, ModelConfig } from '@amber/deepread-domain';
import { makeModelConfig } from '@amber/deepread-domain';
import { HOTLIST_PRESETS } from '../platform_impl/NewsNowProvider.ets';
import type { NewsNowPreset } from '../platform_impl/NewsNowProvider.ets';
import { DeepReadHaptics } from '../design/DeepReadHaptics.ets';
import { DeepReadDesign as D } from '../design/DeepReadDesign.ets';
import { markWantRoutingReady } from '../entryability/EntryAbility.ets';
import { DeepReadTabBar } from '../components/deepread/DeepReadTabBar.ets';
import { DeepReadPaperBackground } from '../components/deepread/DeepReadPaperBackground.ets';
import { DeepReadSettingsHeader } from '../components/deepread/DeepReadSettingsVisual.ets';
import { loadDeepReadAppearance } from '../platform_impl/DeepReadAppearance.ets';
import { DeepReadDockScroll } from '../components/deepread/DeepReadDockScroll.ets';
import { DeepReadSettingsForm } from '../components/deepread/DeepReadSettingsForm.ets';
import { writeDeepReadSearchEditorDraft, consumeDeepReadSearchEditorDraft } from '../platform_impl/DeepReadSearchDraft.ets';
import type { DeepReadSearchEditorDraft } from '../platform_impl/DeepReadSearchDraft.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Settings';


interface SourceToggle {
  id: string;
  displayName: string;
  enabled: boolean;
}

interface DeepReadSettingsRouteParams {
  standaloneTabRoot?: boolean;
}

// This AppStorage snapshot is process-local; it is never registered with PersistentStorage.
interface DeepReadSettingsSearchDraft {
  settings: SearchSettings;
  selectedId: string;
}
interface DeepReadSettingsReadingDraft {
  fontModeIndex: number;
  fontScalePercent: number;
  templateId: string;
  persistedTemplateId: string;
}
interface DeepReadSettingsSessionDraft {
  search: DeepReadSettingsSearchDraft | null;
  reading: DeepReadSettingsReadingDraft | null;
  modelReference: TaskModelReference | null;
  modelLabel: string;
  modelDetail: string;
}
const SETTINGS_SESSION_DRAFT: string = 'deepreadSettingsSessionDraft';

// 搜索源定义(需 key 的)
interface SearchSourceDef {
  id: string;
  displayName: string;
  keyPlaceholder: string;
}

const KEYED_SOURCES: SearchSourceDef[] = [
  { id: 'tavily', displayName: 'Tavily', keyPlaceholder: 'tvly-...' },
  { id: 'bocha', displayName: 'Bocha', keyPlaceholder: 'bocha-...' },
  { id: 'brave', displayName: 'Brave', keyPlaceholder: 'brave-...' },
  { id: 'exa', displayName: 'Exa', keyPlaceholder: 'exa-...' },
];

@Component
export struct SettingDeepReadPageContent {
  onBackHandler: (handler: () => boolean) => void = (_handler: () => boolean): void => {};
  @Prop embeddedRoot: boolean = false;
  @Prop @Watch('rootVisibilityChanged') rootVisible: boolean = false;

  private rootVisibilityChanged(): void {
    if (this.rootVisible) this.onPageShow(); else this.onPageHide();
  }

  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('appBackgrounded') @Watch('motionPolicyChanged') appBackgrounded: boolean = false;
  @State standaloneRoot: boolean = true;
  @StorageProp('reduceMotion') @Watch('motionPolicyChanged') reduceMotion: boolean = false;
  @StorageProp('deepreadActiveTab') @Watch('motionPolicyChanged') activeTab: number = 2;
  @State standaloneSearch: SearchSettings = defaultSearchPrefs();
  @State standaloneSearchLoaded: boolean = false;
  @State standaloneSearchError: string = '';
  @State selectedSearchId: string = '';
  @State modelDraftDirty: boolean = false;
  private searchLoadToken: number = 0;
  private persistedTemplateId: string = 'none';
  private settingsEdited: boolean = false;
  private readingSettingsLoaded: boolean = false;
  private restoredReadingDraft: boolean = false;
  private pendingSearchDraft: DeepReadSettingsSearchDraft | null = null;
  @State arrivalOffset: number = 0;
  @State aiKey: string = '';
  @State aiBaseUrl: string = 'https://api.deepseek.com';
  @State model: string = 'deepseek-chat';
  @State saved: string = '';
  @State chatCopyMessage: string = '';
  @State copyingChat: boolean = false;
  @State hotlistToggles: SourceToggle[] = [];
  @State hotlistRefreshMinutes: number = 60;
  @State hotlistWifiOnly: boolean = false;
  @State hotlistTranslateZh: boolean = false;
  @State selectedSource: string = 'tavily';
  @State showSourcePicker: boolean = false;
  @State currentKey: string = '';
  @State testResult: string = '';
  @State testStatus: string = '';
  // 旧模型库供议会席位；小说已经复用 Chat providers。
  @State models: ModelConfig[] = [];
  // P5:关注筛选 / 阅读体验 / 看板模型
  @State focusKeywords: string = '';
  @State focusModeIndex: number = 0;
  @State fontModeIndex: number = 0;
  @State fontScalePercent: number = 100;
  @State templateId: string = 'none';
  @State customTemplates: CustomTemplateDef[] = [];
  @State boardModelId: string = '';
  private focusModes: string[] = ['全部', '关注优先', '仅关注'];
  private fontModes: string[] = ['默认', '衬线'];
  @State newLabel: string = '';
  @State newBaseUrl: string = 'https://api.deepseek.com';
  @State newApiKey: string = '';
  @State newModel: string = 'deepseek-chat';
  @State modelSaved: string = '';
  @State modelSheetOpen: boolean = false;
  @State taskModelReference: TaskModelReference | null = null;
  @State taskModelOptions: DeepReadModelOption[] = [];
  @State taskModelLabel: string = '正在读取模型';
  @State taskModelDetail: string = '';
  @State modelError: string = '';
  @State savingModel: boolean = false;

  private storage: Storage | null = null;
  private pageAlive: boolean = false;
  private pageVisible: boolean = false;
  private loadToken: number = 0;
  private pageLoad: Promise<void> = Promise.resolve();
  private modelLoadToken: number = 0;
  private readonly settingsScroller: Scroller = new Scroller();
  private readonly dockScroll: DeepReadDockScroll = new DeepReadDockScroll();
  private firstArrival: boolean = true;
  private arrivalTimer: number = -1;
  private restoreScrollPending: boolean = true;
  private confirmationTimer: number = -1;

  onBackPress(): boolean {
    if (!this.modelSheetOpen) return false;
    this.modelSheetOpen = false;
    return true;
  }

  aboutToAppear(): void {
    this.onBackHandler((): boolean => { return this.onBackPress(); });
    this.pageAlive = true;
    if (getProductKind() === 'deepread') {
      this.standaloneRoot = this.readStandaloneRoot();
      if (this.standaloneRoot) this.restoreSessionDraft();
    }
    const token: number = ++this.loadToken;
    this.pageLoad = this.loadPage(token).catch((e: Error): void => {
      if (this.pageAlive && token === this.loadToken) {
        this.saved = `加载失败：${e.message}`;
      }
    });
    if (this.rootVisible) this.onPageShow();
  }

  aboutToDisappear(): void {
    this.dockScroll.reset();
    this.saveSessionDraft();
    this.pageAlive = false;
    this.pageVisible = false;
    this.settleArrival();
    this.loadToken++;
    this.modelLoadToken++;
    this.searchLoadToken++;
    if (this.confirmationTimer >= 0) clearTimeout(this.confirmationTimer);
  }

  // 从模板工作台返回时刷新自定义模板列表(aboutToAppear 在栈内返回不重跑)
  onPageShow(): void {
    this.pageVisible = true;
    this.dockScroll.reset();
    if (getProductKind() === 'deepread') {
      this.standaloneRoot = this.readStandaloneRoot();
      if (!this.embeddedRoot) markWantRoutingReady(this.getUIContext().getRouter());
      if (this.standaloneRoot && !this.embeddedRoot) AppStorage.setOrCreate<number>('deepreadActiveTab', 2);
      this.refreshStandaloneSearch();
      if (!this.embeddedRoot) this.arrive();
    }
    const storage: Storage | null = this.storage;
    if (storage === null) return;
    if (getProductKind() === 'deepread') this.refreshTemplateSelection();
    loadCustomTemplates(storage).then((list: CustomTemplateDef[]): void => {
      if (this.pageAlive) this.customTemplates = list;
    }).catch((): void => {});
    this.refreshTaskModelSelection();
  }

  private readStandaloneRoot(): boolean {
    const params: DeepReadSettingsRouteParams | null = router.getParams() as DeepReadSettingsRouteParams | null;
    return this.embeddedRoot || parseInt(router.getLength(), 10) <= 1 || params?.standaloneTabRoot === true;
  }

  onPageHide(): void {
    this.dockScroll.reset();
    this.saveSessionDraft();
    this.pageVisible = false;
    this.settleArrival();
    if (getProductKind() === 'deepread' && this.standaloneRoot) {
      AppStorage.setOrCreate<number>('deepreadSettingsOffset', this.settingsScroller.currentOffset().yOffset);
    }
  }

  private markSettingsEdited(): void {
    this.settingsEdited = true;
    this.clearSavedNotice();
  }

  private saveSessionDraft(): void {
    if (getProductKind() !== 'deepread' || !this.standaloneRoot) return;
    if (!this.settingsEdited && !this.modelDraftDirty) { AppStorage.delete(SETTINGS_SESSION_DRAFT); return; }
    const search: SearchSettings = this.standaloneSearch;
    const draft: DeepReadSettingsSessionDraft = {
      search: this.standaloneSearchLoaded ? {
        settings: search, selectedId: this.selectedSearchId,
      } : this.pendingSearchDraft,
      reading: this.readingSettingsLoaded ? {
        fontModeIndex: this.fontModeIndex, fontScalePercent: this.fontScalePercent,
        templateId: this.templateId, persistedTemplateId: this.persistedTemplateId,
      } : null,
      modelReference: this.modelDraftDirty ? this.taskModelReference : null,
      modelLabel: this.taskModelLabel, modelDetail: this.taskModelDetail,
    };
    // Retain an isolated draft only in process memory, including unsaved search credentials.
    AppStorage.setOrCreate<DeepReadSettingsSessionDraft>(SETTINGS_SESSION_DRAFT,
      JSON.parse(JSON.stringify(draft)) as DeepReadSettingsSessionDraft);
  }

  private restoreSessionDraft(): void {
    const cached: DeepReadSettingsSessionDraft | undefined = AppStorage.get<DeepReadSettingsSessionDraft>(SETTINGS_SESSION_DRAFT);
    if (cached === undefined) return;
    const draft: DeepReadSettingsSessionDraft = JSON.parse(JSON.stringify(cached)) as DeepReadSettingsSessionDraft;
    this.settingsEdited = true;
    this.pendingSearchDraft = draft.search;
    if (draft.reading !== null) {
      this.fontModeIndex = draft.reading.fontModeIndex;
      this.fontScalePercent = draft.reading.fontScalePercent;
      this.templateId = draft.reading.templateId;
      this.persistedTemplateId = draft.reading.persistedTemplateId;
      this.readingSettingsLoaded = true;
      this.restoredReadingDraft = true;
    }
    if (draft.modelReference !== null) {
      this.taskModelReference = draft.modelReference;
      this.modelDraftDirty = true;
      this.taskModelLabel = draft.modelLabel;
      this.taskModelDetail = draft.modelDetail;
    }
  }

  private arrive(): void {
    if (!this.firstArrival || !this.standaloneRoot) return;
    this.firstArrival = false;
    const direction: number = AppStorage.get<number>('deepreadTabDirection') ?? 0;
    if (direction === 0 || this.reduceMotion || this.appBackgrounded || this.activeTab !== 2) return;
    this.arrivalOffset = direction * 30;
    // Commit the starting position before the first animation transaction.
    this.arrivalTimer = setTimeout((): void => {
      if (this.arrivalTimer < 0) return;
      this.arrivalTimer = -1;
      if (!this.pageAlive || !this.pageVisible || this.reduceMotion || this.appBackgrounded || this.activeTab !== 2) {
        this.arrivalOffset = 0;
        return;
      }
      this.getUIContext().animateTo({ duration: 260, curve: curves.springCurve(0, 1, 240, 30) }, (): void => {
        this.arrivalOffset = 0;
      });
    }, 16);
  }

  private motionPolicyChanged(): void {
    if (this.reduceMotion || this.appBackgrounded || this.activeTab !== 2) this.settleArrival();
  }

  private settleArrival(): void {
    if (this.arrivalTimer >= 0) clearTimeout(this.arrivalTimer);
    this.arrivalTimer = -1;
    this.arrivalOffset = 0;
  }

  private restoreSettingsScroll(): void {
    if (!this.restoreScrollPending || !this.standaloneRoot) return;
    this.restoreScrollPending = false;
    const offset: number = AppStorage.get<number>('deepreadSettingsOffset') ?? 0;
    this.settingsScroller.scrollTo({ xOffset: 0, yOffset: offset, animation: false });
  }

  private async loadPage(token: number): Promise<void> {
    const storage: Storage = getAppContainer().storage;
    this.storage = storage;
    if (getProductKind() === 'deepread') await loadDeepReadAppearance(storage);
    const aiKey: string = await storage.get<string>('ai_api_key', '');
    const aiBaseUrl: string = await storage.get<string>('ai_base_url', 'https://api.deepseek.com');
    const model: string = await storage.get<string>('ai_model', 'deepseek-chat');
    // 加载热榜源开关
    const hotlist: SourceToggle[] = [];
    for (let i = 0; i < HOTLIST_PRESETS.length; i++) {
      const p: NewsNowPreset = HOTLIST_PRESETS[i];
      const enabled: string = await storage.get<string>('source_' + p.id, p.defaultEnabled === false ? 'false' : 'true');
      hotlist.push({ id: p.id, displayName: p.displayName, enabled: enabled === 'true' });
    }
    const hotlistRefreshMinutes: number = await storage.get<number>('deepread_hotlist_refresh_minutes', 60);
    const hotlistWifiOnly: string = await storage.get<string>('deepread_hotlist_wifi_only', 'false');
    const hotlistTranslateZh: string = await storage.get<string>('deepread_hotlist_translate_zh', 'false');
    // 加载当前选中的搜索源和 key
    const selectedSource: string = await storage.get<string>('active_search_source', 'tavily');
    const currentKey: string = await storage.get<string>(selectedSource + '_api_key', '');
    // 加载模型库
    const models: ModelConfig[] = await getModelRegistry().list();
    // P5:关注筛选/阅读体验/看板模型
    const focusKeywords: string = await storage.get<string>('deepread_focus_keywords', '');
    const focusMode: string = await storage.get<string>('deepread_focus_mode', 'all');
    const fontMode: string = await storage.get<string>('deepread_font_mode', getProductKind() === 'deepread' ? 'serif' : 'default');
    const fontScale: number = Number(await storage.get<number>('deepread_font_scale', 100));
    const boardModelId: string = await storage.get<string>('deepread_model_id', '');
    const tplId: string = await storage.get<string>('deepread_template_id', 'none');
    if (!this.pageAlive || token !== this.loadToken) return;
    this.aiKey = aiKey;
    this.aiBaseUrl = aiBaseUrl;
    this.model = model;
    this.hotlistToggles = hotlist;
    this.hotlistRefreshMinutes = hotlistRefreshMinutes;
    this.hotlistWifiOnly = hotlistWifiOnly === 'true';
    this.hotlistTranslateZh = hotlistTranslateZh === 'true';
    this.selectedSource = selectedSource;
    this.currentKey = currentKey;
    this.models = models;
    this.focusKeywords = focusKeywords;
    this.focusModeIndex = focusMode === 'focus_first' ? 1 : focusMode === 'focus_only' ? 2 : 0;
    if (!this.restoredReadingDraft) {
      this.fontModeIndex = fontMode === 'serif' ? 1 : 0;
      this.fontScalePercent = Math.max(70, Math.min(180, Number.isFinite(fontScale) ? fontScale : 100));
    }
    this.readingSettingsLoaded = true;
    this.boardModelId = boardModelId;
    if (!this.restoredReadingDraft || tplId !== this.persistedTemplateId) this.templateId = tplId;
    this.persistedTemplateId = tplId;
    this.customTemplates = await loadCustomTemplates(storage);
    await this.refreshTaskModelSelection();
  }

  private async refreshStandaloneSearch(): Promise<void> {
    const editor: DeepReadSearchEditorDraft | null = consumeDeepReadSearchEditorDraft();
    if (editor !== null) {
      this.searchLoadToken++;
      this.standaloneSearch = editor.settings;
      this.selectedSearchId = editor.selectedId;
      this.standaloneSearchLoaded = true;
      this.standaloneSearchError = '';
      if (editor.dirty) this.markSettingsEdited();
      return;
    }
    // Returning from another settings page must not replace an unsaved service or credential.
    if (this.standaloneSearchLoaded) return;
    const token: number = ++this.searchLoadToken;
    try {
      const current: SearchSettings = await loadSearchPrefs(getChatKvStore());
      if (!this.pageAlive || token !== this.searchLoadToken) return;
      const draft: DeepReadSettingsSearchDraft | null = this.pendingSearchDraft;
      this.standaloneSearch = draft === null ? current : draft.settings;
      this.selectedSearchId = draft === null
        ? current.searchServices[current.searchServiceSelected]?.id ?? '' : draft.selectedId;
      this.standaloneSearch.searchServiceSelected = this.standaloneSearch.searchServices.findIndex(
        (service: SearchServiceOptions): boolean => service.id === this.selectedSearchId);
      this.pendingSearchDraft = null;
      this.standaloneSearchLoaded = true;
      this.standaloneSearchError = this.selectedSearchId.length > 0 && this.standaloneSearch.searchServiceSelected < 0
        ? '首选搜索服务已删除，请重新选择' : '';
    } catch (error) {
      if (this.pageAlive && token === this.searchLoadToken) this.standaloneSearchError = '读取搜索配置失败：' + String(error);
    }
  }

  private openSearchServiceEditor(serviceId: string): void {
    if (!this.standaloneSearchLoaded) return;
    writeDeepReadSearchEditorDraft({ settings: this.standaloneSearch,
      selectedId: this.selectedSearchId, dirty: false });
    router.pushUrl({ url: 'pages/DeepReadSearchServiceEditorPage', params: { serviceId } }).catch((error: Error): void => {
      consumeDeepReadSearchEditorDraft();
      if (this.pageAlive) this.standaloneSearchError = '无法打开搜索服务：' + error.message;
    });
  }

  private async saveStandaloneSettings(): Promise<void> {
    this.clearSavedNotice();
    if (!this.standaloneSearchLoaded) throw new Error('搜索设置尚未读取完成');
    const draft: SearchSettings = JSON.parse(JSON.stringify(this.standaloneSearch)) as SearchSettings;
    const selected: number = draft.searchServices.findIndex(
      (service: SearchServiceOptions): boolean => service.id === this.selectedSearchId);
    if (this.selectedSearchId.length > 0 && selected < 0) throw new Error('首选搜索服务已删除，请重新选择');
    draft.searchServiceSelected = Math.max(0, selected);
    await updateSearchPrefs(getChatKvStore(), (current: SearchSettings): SearchSettings => {
      draft.enableWebSearch = current.enableWebSearch;
      const resultSize: number = draft.searchCommonOptions.resultSize;
      draft.searchCommonOptions = current.searchCommonOptions;
      draft.searchCommonOptions.resultSize = resultSize;
      return draft;
    });
    await this.saveStandaloneReading();
  }

  private async refreshTemplateSelection(): Promise<void> {
    await this.pageLoad;
    const storage: Storage | null = this.storage;
    if (storage === null || !this.pageAlive) return;
    const token: number = this.loadToken;
    const selected: string = await storage.get<string>('deepread_template_id', 'none');
    if (!this.pageAlive || token !== this.loadToken) return;
    if (selected !== this.persistedTemplateId) {
      this.templateId = selected;
      this.persistedTemplateId = selected;
      this.clearSavedNotice();
    }
  }

  private async refreshTaskModelSelection(): Promise<void> {
    const token: number = ++this.modelLoadToken;
    try {
      const selection: DeepReadModelSelectionState = await loadDeepReadModelSelection();
      if (!this.pageAlive || token !== this.modelLoadToken) return;
      this.taskModelOptions = selection.options;
      if (getProductKind() === 'deepread' && this.modelDraftDirty) {
        const draft: TaskModelReference | null = this.taskModelReference;
        const option: DeepReadModelOption | undefined = selection.options.find((item: DeepReadModelOption): boolean =>
          draft !== null && draft.kind === 'fixed' && item.providerId === draft.pair.providerId && item.modelId === draft.pair.modelId);
        if (draft !== null && draft.kind === 'auto') {
          this.taskModelLabel = '跟随当前模型';
          this.modelError = '';
        } else {
          this.taskModelLabel = option === undefined ? '所选模型（不可用）' : `${option.providerLabel} / ${option.label}`;
          this.modelError = option === undefined || !option.available ? '所选模型已不可用，请重新选择' : '';
        }
        return;
      }
      this.taskModelReference = selection.reference;
      this.taskModelLabel = selection.label;
      this.taskModelDetail = selection.detail;
      this.modelError = '';
    } catch (error) {
      if (!this.pageAlive || token !== this.modelLoadToken) return;
      this.modelError = `读取模型设置失败：${error instanceof Error ? error.message : String(error)}`;
    }
  }

  private async selectTaskModel(option: DeepReadModelOption | null): Promise<void> {
    if (this.savingModel) return;
    if (getProductKind() === 'deepread') {
      if (option !== null && !option.available) { this.modelError = '此模型当前不可用，请先检查服务商配置'; return; }
      this.modelLoadToken++;
      this.taskModelReference = option === null ? { kind: 'auto' }
        : { kind: 'fixed', pair: { providerId: option.providerId, modelId: option.modelId } };
      this.taskModelLabel = option === null ? '跟随当前模型' : `${option.providerLabel} / ${option.label}`;
      this.taskModelDetail = option === null ? '每轮任务开始时读取最新聊天选择。' : '固定使用此服务商与模型。';
      DeepReadHaptics.selection();
      this.modelDraftDirty = true;
      this.modelError = '';
      this.modelSheetOpen = false;
      this.clearSavedNotice();
      return;
    }
    const token: number = this.loadToken;
    this.modelLoadToken++;
    this.savingModel = true;
    this.modelError = '';
    this.saved = '';
    try {
      const reference: TaskModelReference = await saveDeepReadModelSelection(option);
      if (!this.pageAlive || token !== this.loadToken) return;
      this.taskModelReference = reference;
      this.taskModelLabel = option === null ? '跟随当前模型' : `${option.providerLabel} / ${option.label}`;
      this.taskModelDetail = option === null ? '每轮任务开始时读取最新聊天选择。' : '固定使用此服务商与模型。';
      this.saved = '深度阅读模型已保存 ✓';
      this.modelSheetOpen = false;
      await this.refreshTaskModelSelection();
    } catch (error) {
      if (this.pageAlive && token === this.loadToken) {
        this.modelError = `模型保存失败：${error instanceof Error ? error.message : String(error)}`;
        promptAction.showToast({ message: this.modelError });
      }
    } finally {
      this.savingModel = false;
    }
  }

  private taskModelSelected(option: DeepReadModelOption): boolean {
    const reference: TaskModelReference | null = this.taskModelReference;
    return reference !== null && reference.kind === 'fixed'
      && reference.pair.providerId === option.providerId && reference.pair.modelId === option.modelId;
  }

  private async fillCurrentChatDraft(): Promise<void> {
    if (this.copyingChat) return;
    const token: number = this.loadToken;
    this.copyingChat = true;
    this.chatCopyMessage = '';
    try {
      // A first-page load must finish before the requested draft replaces its fields.
      await this.pageLoad;
      const result: ChatDeepReadDraftResult = chatChoiceToDeepReadDraft(await resolveChatProviderChoice());
      if (!this.pageAlive || token !== this.loadToken) return;
      if (result.draft !== null) {
        this.aiBaseUrl = result.draft.baseUrl;
        this.aiKey = result.draft.apiKey;
        this.model = result.draft.model;
        this.saved = '';
      }
      this.chatCopyMessage = result.message;
    } catch (_error) {
      if (this.pageAlive && token === this.loadToken) {
        this.chatCopyMessage = '读取当前聊天配置失败，请返回聊天服务商配置检查。';
      }
    } finally {
      this.copyingChat = false;
    }
  }

  // 模板 chips:内置 + 自定义(自定义无 description,chips 只用 id/name)
  private allTemplateChips(): DeepReadTemplateDef[] {
    const out: DeepReadTemplateDef[] = [];
    for (const t of BUILTIN_TEMPLATES) out.push(t);
    for (const c of this.customTemplates) {
      out.push({ id: c.id, name: c.name, description: '' });
    }
    if (this.templateId.length > 0 && !out.some((template: DeepReadTemplateDef): boolean => template.id === this.templateId)) {
      out.push({ id: this.templateId, name: '当前自定义模板', description: '此模板当前未在模板库中' });
    }
    return out;
  }

  private async saveTemplate(id: string): Promise<void> {
    await this.storage!.set<string>('deepread_template_id', id);
    this.templateId = id;
    this.saved = '模板已选择 ✓';
  }

  // P5:保存关注筛选(关键词逗号分隔 → KV)
  private async saveFocus(): Promise<void> {
    const kv: Storage = this.storage!;
    await kv.set<string>('deepread_focus_keywords', this.focusKeywords.trim());
    const mode: string = this.focusModeIndex === 1 ? 'focus_first'
      : this.focusModeIndex === 2 ? 'focus_only' : 'all';
    await kv.set<string>('deepread_focus_mode', mode);
    this.saved = '关注筛选已保存 ✓';
  }

  private async saveReading(): Promise<void> {
    const kv: Storage = this.storage!;
    await kv.set<string>('deepread_font_mode', this.fontModeIndex === 1 ? 'serif' : 'default');
    await kv.set<number>('deepread_font_scale', this.fontScalePercent);
    this.saved = '阅读体验已保存 ✓';
  }

  // 历史快照字段与 helpers 保留参考；当前模型入口只写 deepread_task_model。

  private currentBoardModelLabel(): string {
    if (this.boardModelId.trim().length > 0) return this.boardModelId.trim();
    return this.model.length > 0 ? `跟随聊天配置（${this.model}）` : '跟随聊天配置';
  }

  private showModelMenu(): void {
    this.modelSheetOpen = true;
    this.refreshTaskModelSelection();
  }

  private modelSheetTransition(card: boolean): TransitionEffect {
    if (getProductKind() === 'deepread') {
      if (this.appBackgrounded) return TransitionEffect.IDENTITY;
      if (this.reduceMotion) return TransitionEffect.OPACITY.animation({ duration: 120, curve: Curve.EaseOut });
    }
    const effect: TransitionEffect = card ? TransitionEffect.translate({ y: 160 }) : TransitionEffect.OPACITY;
    return effect.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut });
  }

  @Builder
  ModelSheet(): void {
    Column() {
      Blank()
        .onClick((): void => { this.modelSheetOpen = false; })
      Column() {
        Text('深度阅读模型')
          .fontSize(15).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(15))
          .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
          .width('100%').padding({ left: 20, right: 20, top: 18, bottom: 10 })
        Scroll() {
          Column() {
            this.ModelSheetRow('跟随当前模型', this.taskModelReference?.kind === 'auto', (): void => {
              this.selectTaskModel(null);
            })
            ForEach(this.taskModelOptions, (option: DeepReadModelOption): void => {
              this.ModelSheetRow(`${option.providerLabel} / ${option.label}${option.available ? '' : '（不可用）'}`,
                this.taskModelSelected(option), (): void => { this.selectTaskModel(option); }, option.available)
            }, (option: DeepReadModelOption): string => `${option.providerId}:${option.modelId}`)
            if (this.taskModelOptions.length === 0) {
              Text('暂无已配置模型，请先添加模型服务商。')
                .fontSize(12).lineHeight(17).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
                .width('100%').padding({ left: 20, right: 20, top: 8, bottom: 8 })
            }
            if (this.modelError.length > 0) {
              Text(this.modelError).fontSize(13).lineHeight(18).fontFamily(FONT_SANS_MEDIUM).fontColor(ERROR)
                .width('100%').padding({ left: 20, right: 20, top: 8, bottom: 8 })
            }
            this.ModelSheetRow('配置模型服务商', false, (): void => {
              this.modelSheetOpen = false;
              router.pushUrl({ url: 'pages/ChatProviderSettingsPage' });
            })
          }
          .width('100%')
        }
        .constraintSize({ maxHeight: 420 })
        .scrollBar(BarState.Auto)
      }
      .width('100%')
      .padding({ bottom: 12 })
      .backgroundColor(SURFACE)
      .borderRadius({ topLeft: 20, topRight: 20 })
      .transition(this.modelSheetTransition(true))
    }
    .width('100%').height('100%')
    .backgroundColor('#66000000')
    .justifyContent(FlexAlign.End)
    .transition(this.modelSheetTransition(false))
  }

  @Builder
  ModelSheetRow(label: string, selected: boolean, onTap: () => void, available: boolean = true): void {
    Row() {
      Text(label)
        .fontSize(15).lineHeight(lineHeightForSize(15)).fontFamily(FONT_SANS_MEDIUM)
        .fontColor(!available ? INK3 : selected ? this.accentColorStore : INK)
        .layoutWeight(1).maxLines(3).wordBreak(WordBreak.BREAK_ALL)
      if (selected) {
        Icon({ name: 'check', iconSize: 16, color: this.accentColorStore })
      }
    }
    .width('100%')
    .constraintSize({ minHeight: 48 })
    .padding({ left: 20, right: 20, top: 10, bottom: 10 })
    .alignItems(VerticalAlign.Center)
    .enabled(available && !this.savingModel)
    .onClick((): void => { onTap(); })
  }

  private async followChatConfig(): Promise<void> {
    try {
      const result: ChatDeepReadDraftResult = chatChoiceToDeepReadDraft(await resolveChatProviderChoice());
      if (result.draft !== null) {
        await this.storage!.set<string>('ai_base_url', result.draft.baseUrl);
        await this.storage!.set<string>('ai_api_key', result.draft.apiKey);
        await this.storage!.set<string>('ai_model', result.draft.model);
        this.aiBaseUrl = result.draft.baseUrl;
        this.aiKey = result.draft.apiKey;
        this.model = result.draft.model;
      }
      await this.storage!.set<string>('deepread_model_id', '');
      this.boardModelId = '';
      this.saved = result.draft !== null ? '已跟随当前聊天配置 ✓' : result.message;
    } catch (e) {
      this.saved = `跟随失败：${String(e)}`;
    }
  }

  private async applyRegistryModel(m: ModelConfig): Promise<void> {
    try {
      await this.storage!.set<string>('ai_base_url', m.baseUrl);
      await this.storage!.set<string>('ai_api_key', m.apiKey);
      await this.storage!.set<string>('ai_model', m.model);
      await this.storage!.set<string>('deepread_model_id', m.model);
      this.aiBaseUrl = m.baseUrl;
      this.aiKey = m.apiKey;
      this.model = m.model;
      this.boardModelId = m.model;
      this.saved = `深度阅读模型已切换为 ${m.label.length > 0 ? m.label : m.model} ✓`;
    } catch (e) {
      this.saved = `切换失败：${String(e)}`;
    }
  }

  async loadModels(): Promise<void> {
    this.models = await getModelRegistry().list();
  }

  async addModel(): Promise<void> {
    if (this.newApiKey.length === 0) {
      this.modelSaved = 'API Key 为空,无法添加';
      return;
    }
    const cfg: ModelConfig = makeModelConfig({
      label: this.newLabel.length > 0 ? this.newLabel : this.newModel,
      baseUrl: this.newBaseUrl,
      apiKey: this.newApiKey,
      model: this.newModel,
    });
    const next: ModelConfig[] = [...this.models, cfg];
    await getModelRegistry().save(next);
    await this.loadModels();
    this.newLabel = '';
    this.newApiKey = '';
    this.modelSaved = '已添加 ✓';
  }

  async deleteModel(id: string): Promise<void> {
    const next: ModelConfig[] = [];
    for (let i = 0; i < this.models.length; i++) {
      if (this.models[i].id !== id) next.push(this.models[i]);
    }
    await getModelRegistry().save(next);
    await this.loadModels();
    this.modelSaved = '已删除';
  }

  async selectSource(id: string): Promise<void> {
    // 先保存当前 key
    await this.storage!.set<string>(this.selectedSource + '_api_key', this.currentKey);
    // 切换到新源
    this.selectedSource = id;
    this.currentKey = await this.storage!.get<string>(id + '_api_key', '');
    this.testResult = '';
    this.testStatus = '';
  }

  async toggleHotlist(id: string): Promise<void> {
    const newToggles: SourceToggle[] = [];
    let newEnabled: boolean = false;
    for (let i = 0; i < this.hotlistToggles.length; i++) {
      const t: SourceToggle = this.hotlistToggles[i];
      if (t.id === id) {
        newEnabled = !t.enabled;
        newToggles.push({ id: t.id, displayName: t.displayName, enabled: newEnabled });
      } else {
        newToggles.push(t);
      }
    }
    this.hotlistToggles = newToggles;
    try {
      await this.storage!.set<string>('source_' + id, newEnabled ? 'true' : 'false');
    } catch (_error) {
      this.hotlistToggles = this.hotlistToggles.map((toggle: SourceToggle): SourceToggle =>
        toggle.id === id ? { id: toggle.id, displayName: toggle.displayName, enabled: !newEnabled } : toggle);
      this.saved = '热榜源设置保存失败，请重试';
      promptAction.showToast({ message: this.saved });
    }
  }

  async saveSourceConfig(): Promise<void> {
    await this.storage!.set<string>('active_search_source', this.selectedSource);
    await this.storage!.set<string>(this.selectedSource + '_api_key', this.currentKey);
    this.saved = '已保存 ✓';
  }

  private async saveHotlistRefresh(index: number): Promise<void> {
    const minutes: number = [15, 30, 60, 120][index];
    try {
      await this.storage!.set<number>('deepread_hotlist_refresh_minutes', minutes);
      this.hotlistRefreshMinutes = minutes;
    } catch (error) {
      this.saved = `刷新设置保存失败：${String(error)}`;
    }
  }

  private async toggleHotlistWifi(): Promise<void> {
    const previous: boolean = this.hotlistWifiOnly;
    this.hotlistWifiOnly = !previous;
    try {
      await this.storage!.set<string>('deepread_hotlist_wifi_only', this.hotlistWifiOnly ? 'true' : 'false');
    } catch (error) {
      this.hotlistWifiOnly = previous;
      this.saved = `网络设置保存失败：${String(error)}`;
    }
  }

  private async toggleHotlistTranslation(): Promise<void> {
    const previous: boolean = this.hotlistTranslateZh;
    this.hotlistTranslateZh = !previous;
    try {
      await this.storage!.set<string>('deepread_hotlist_translate_zh', this.hotlistTranslateZh ? 'true' : 'false');
    } catch (error) {
      this.hotlistTranslateZh = previous;
      this.saved = `标题设置保存失败：${String(error)}`;
    }
  }

  async testSource(): Promise<void> {
    this.testStatus = 'testing';
    this.testResult = '';
    // 简化测试:检查 key 是否非空
    if (this.currentKey.length === 0) {
      this.testStatus = 'error';
      this.testResult = 'API Key 为空,请先填写';
    } else {
      this.testStatus = 'ok';
      this.testResult = 'Key 格式检查通过（未联网验证）';
    }
  }

  async save(): Promise<void> {
    hilog.info(DOMAIN, TAG, 'save() called: storage=%{public}s key=%{public}s baseUrl=%{public}s model=%{public}s',
      this.storage === null ? 'null' : 'ok',
      this.aiKey.length > 0 ? `len=${this.aiKey.length}` : 'empty',
      this.aiBaseUrl,
      this.model);
    if (this.storage === null) {
      this.saved = '存储未初始化';
      return;
    }
    try {
      await this.storage.set<string>('ai_api_key', this.aiKey);
      await this.storage.set<string>('ai_base_url', this.aiBaseUrl);
      await this.storage.set<string>('ai_model', this.model);
      // 验证写入
      const verify: string = await this.storage.get<string>('ai_api_key', '');
      hilog.info(DOMAIN, TAG, 'save() verify: key len=%{public}d', verify.length);
      this.saved = verify.length > 0 ? '已保存 ✓' : '保存失败';
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'save() ERROR: %{public}s', String(e).substring(0, 100));
      this.saved = '保存失败: ' + String(e).substring(0, 30);
    }
  }

  private async saveStandaloneReading(): Promise<void> {
    this.clearSavedNotice();
    await this.pageLoad;
    const storage: Storage | null = this.storage;
    if (storage === null) throw new Error('设置尚未读取完成');
    const token: number = this.loadToken;
    if (this.modelDraftDirty) {
      const draft: TaskModelReference | null = this.taskModelReference;
      const option: DeepReadModelOption | undefined = this.taskModelOptions.find((item: DeepReadModelOption): boolean =>
        draft !== null && draft.kind === 'fixed' && item.providerId === draft.pair.providerId && item.modelId === draft.pair.modelId);
      if (draft !== null && draft.kind === 'fixed' && (option === undefined || !option.available)) {
        throw new Error('所选模型已不可用，请重新选择');
      }
      this.taskModelReference = await saveDeepReadModelSelection(option ?? null);
    }
    await storage.set<string>('deepread_font_mode', this.fontModeIndex === 1 ? 'serif' : 'default');
    await storage.set<number>('deepread_font_scale', this.fontScalePercent);
    await storage.set<string>('deepread_template_id', this.templateId);
    if (this.pageAlive && token === this.loadToken) {
      DeepReadHaptics.success();
      this.modelDraftDirty = false;
      this.settingsEdited = false;
      this.restoredReadingDraft = false;
      if (getProductKind() === 'deepread' && this.standaloneRoot) AppStorage.delete(SETTINGS_SESSION_DRAFT);
      this.persistedTemplateId = this.templateId;
      this.getUIContext().animateTo({ duration: this.reduceMotion ? 0 : 300, curve: Curve.EaseOut }, (): void => {
        this.saved = '已保存，下一次生成将使用此配置。';
      });
      this.confirmationTimer = setTimeout((): void => { this.clearSavedNotice(); }, 3000);
    }
  }

  private clearSavedNotice(): void {
    if (this.confirmationTimer >= 0) clearTimeout(this.confirmationTimer);
    this.confirmationTimer = -1;
    this.getUIContext().animateTo({ duration: this.reduceMotion ? 0 : 300, curve: Curve.EaseOut }, (): void => { this.saved = ''; });
  }

  // 卡片内输入框统一样式(向卡片行靠:SURFACE2 底 r8,正文 15)
  private inputFontSize(): number {
    return TEXT_STYLES.body.size;
  }

  @Builder
  HostSettings(): void {
    Stack() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      Column() {
        CenteredTitleBar({ title: '深度阅读设置' })
      Scroll() {
        Column() {
          PlainSection({ label: '模型' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.ModelPickerCard(); } })
            .margin({ left: GUTTER, right: GUTTER })
          FooterNote({ text: '深度阅读与模板 AI 共用此选择；跟随当前模型或固定已配置的服务商与模型，每轮开始时读取配置。' })

          PlainSection({ label: '热榜源' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.HotlistCard(); } })
            .margin({ left: GUTTER, right: GUTTER })
          PlainSection({ label: '热榜刷新' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.HotlistRefreshCard(); } })
            .margin({ left: GUTTER, right: GUTTER })
          FooterNote({ text: '进入热榜时按缓存时间刷新；下拉可立即刷新。中文标题仅调整显示，原始标题和来源保持不变。' })

          PlainSection({ label: '关注筛选' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.FocusCard(); } })
            .margin({ left: GUTTER, right: GUTTER })

          PlainSection({ label: '阅读体验' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.ReadingCard(); } })
            .margin({ left: GUTTER, right: GUTTER })

          PlainSection({ label: '搜索源' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.SourceCard(); } })
            .margin({ left: GUTTER, right: GUTTER })
          FooterNote({ text: '深度阅读使用搜索开关、已启用服务与免费引擎配置；来源收集在下一轮任务中读取最新设置。' })

          if (getProductKind() === 'agent') {
          PlainSection({ label: '高级 · 议会模型库' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.ModelsCard(); } })
            .margin({ left: GUTTER, right: GUTTER })
          FooterNote({ text: '此模型库用于议会席位；深度阅读、聊天和小说使用聊天服务商配置。' })
          if (this.models.length === 0) {
            FooterNote({ text: '暂无模型,请在下方添加。议会的每个席位需指定一个模型。' })
          }

          }

          if (this.saved.length > 0) {
            Text(this.saved)
              .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
              .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
              .fontColor(this.accentColorStore)
              .width('100%').textAlign(TextAlign.Center)
              .padding({ left: 16, right: 16, top: 8, bottom: 16 })
          }
          PageSpacer()
        }
        .width('100%').alignItems(HorizontalAlign.Start)
        .padding({ bottom: 20 })
      }
      .layoutWeight(1).align(Alignment.TopStart).backgroundColor(BG)
      .align(Alignment.TopStart)
      }
      .width('100%').height('100%').backgroundColor(BG)

      if (this.modelSheetOpen) {
        this.ModelSheet()
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%')
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }

  @Builder
  StandaloneSettings(): void {
    Stack({ alignContent: Alignment.Bottom }) {
      DeepReadPaperBackground({ nightReading: true })
      Column() {
        DeepReadSettingsHeader({ title: '设置', root: this.standaloneRoot })
        Scroll(this.settingsScroller) {
          DeepReadSettingsForm({ modelLabel: this.taskModelLabel, modelDetail: this.taskModelDetail,
            modelOptions: this.taskModelOptions, modelError: this.modelError, templates: this.allTemplateChips(),
            search: $standaloneSearch, searchLoaded: $standaloneSearchLoaded, selectedSearchId: $selectedSearchId,
            searchError: $standaloneSearchError, fontModeIndex: $fontModeIndex,
            fontScalePercent: $fontScalePercent, templateId: $templateId, notice: this.saved,
            onModel: (): void => { this.showModelMenu(); },
            onEdited: (): void => { this.markSettingsEdited(); },
            onSearchEdit: (serviceId: string): void => { this.openSearchServiceEditor(serviceId); },
            onSearchRetry: (): void => { this.refreshStandaloneSearch(); },
            onSave: async (): Promise<void> => { await this.saveStandaloneSettings(); } })
            .width('100%').padding({ left: D.gutter, right: D.gutter,
              bottom: this.standaloneRoot ? this.navBarHeight + 104 : this.navBarHeight + 28 })
            .onAreaChange((_oldArea: Area, _newArea: Area): void => { this.restoreSettingsScroll(); })
        }.width('100%').layoutWeight(1).scrollBar(BarState.Off).align(Alignment.TopStart)
          .onDidScroll((_x: number, y: number, state: ScrollState): void => {
            if (this.standaloneRoot && this.pageVisible && this.activeTab === 2) {
              if (!this.embeddedRoot) this.dockScroll.update(y, this.settingsScroller.currentOffset().yOffset, state !== ScrollState.Idle);
            }
          })
      }.width('100%').height('100%').translate({ x: this.reduceMotion ? 0 : this.arrivalOffset })
        .opacity(this.embeddedRoot || !this.standaloneRoot || this.activeTab === 2 ? 1 : 0)
      if (this.standaloneRoot && !this.embeddedRoot) { DeepReadTabBar({ selected: 2 }) }
    }.width('100%').height('100%').backgroundColor(D.paper)
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }

  build(): void {
    Stack() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
        if (getProductKind() === 'deepread') {
          this.StandaloneSettings();
          if (this.modelSheetOpen) { this.ModelSheet(); }
        } else { this.HostSettings(); }
      }, (epoch: number): string => `theme-${epoch}`)
    }.width('100%').height('100%').backgroundColor(D.paper)
  }

  // ── 卡片内容(@Builder:组件构造必须在 builder 上下文,铁律 4b)──

  // 卡片内发丝分隔线(内缩 14,对齐卡片行)
  @Builder
  CardHairline(inset: number): void {
    Row() {
      Row().width('100%').height(1).backgroundColor(LINE)
    }
    .width('100%')
    .padding({ left: inset, right: 14 })
  }

  // 关注筛选行级动作(应用;实际 Text 44 热区,右对齐)
  @Builder
  CardAction(label: string, onTap: () => void): void {
    Row() {
      Blank()
      Text(label)
        .fontSize(15).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(15))
        .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
        .constraintSize({ minHeight: 44 })
        .padding({ left: 14, right: 14, top: 10, bottom: 10 })
        .onClick((): void => { onTap(); })
    }
    .width('100%').alignItems(VerticalAlign.Center)
    .constraintSize({ minHeight: 44 })
    .padding({ left: 14, right: 4 })
  }

  // 字段标(mono meta:机器信息;top 与上一字段间距)
  @Builder
  FieldLabel(label: string, top: number): void {
    Text(label)
      .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
      .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
      .fontColor(INK3)
      .width('100%')
      .margin({ top: top })
  }

  @Builder
  FocusCard(): void {
    Column() {
      Column() {
        TextInput({ text: this.focusKeywords, placeholder: 'AI、大模型、机器人、数码 3C...' })
          .fontSize(this.inputFontSize()).fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK).height(44)
          .placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(8)
          .padding({ left: 14, right: 14 })
          .onChange((v: string): void => { this.focusKeywords = v; })
        Row() {
          Text('筛选模式')
            .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
            .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
          Blank()
          PillSelect({
            options: this.focusModes, selectedIndex: this.focusModeIndex,
            triggerColor: INK,
            triggerMinHeight: 44,
            onSelect: (i: number): void => { this.focusModeIndex = i; },
          })
          .width(112)
        }
        .width('100%').alignItems(VerticalAlign.Center)
        .constraintSize({ minHeight: 44 })
        .margin({ top: 8 })
      }
      .width('100%').padding({ left: 14, right: 14, top: 12, bottom: 12 })
      this.CardHairline(14)
      this.CardAction('应用', (): void => { this.saveFocus(); })
    }
    .width('100%')
  }

  @Builder
  ReadingCard(): void {
    Column() {
      Column() {
        Row() {
          Text('阅读字体')
            .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
            .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
          Blank()
          Row() {
            ForEach(this.fontModes, (m: string, i: number): void => {
              Text(m)
                .fontSize(13).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(13))
                .fontFamily(FONT_SANS_MEDIUM)
                .fontColor(this.fontModeIndex === i ? INK : INK3)
                .constraintSize({ minHeight: 44 })
                .padding({ left: 12, right: 12, top: 6, bottom: 6 })
                .borderRadius(8)
                .backgroundColor(this.fontModeIndex === i
                  ? accentSoftBg(this.accentColorStore) : Color.Transparent)
                .onClick((): void => { this.fontModeIndex = i; })
            }, (m: string): string => m)
          }
        }
        .width('100%').alignItems(VerticalAlign.Center)
        Row() {
          Text('字号')
            .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
            .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
          Blank()
          Text(`${this.fontScalePercent}%`)
            .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
            .fontColor(INK3)
        }
        .width('100%').alignItems(VerticalAlign.Center)
        .margin({ top: 12 })
        Slider({ value: this.fontScalePercent, min: 70, max: 180, step: 5, style: SliderStyle.OutSet })
          .width('100%')
          .selectedColor(this.accentColorStore)
          .onChange((v: number): void => { this.fontScalePercent = Math.round(v); })
      }
      .width('100%').padding({ left: 14, right: 14, top: 12, bottom: 4 })
      this.CardHairline(14)
      Row() {
        Blank()
        Text('保存阅读体验')
          .fontSize(15).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(15))
          .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
          .constraintSize({ minHeight: 44 })
          .padding({ left: 14, right: 14, top: 10, bottom: 10 })
          .onClick((): void => { this.saveReading(); })
      }
      .width('100%').alignItems(VerticalAlign.Center)
      .padding({ left: 14, right: 4 })
      this.CardHairline(14)
      Column() {
        Text('文章模板')
          .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
          .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
          .width('100%')
        Row() {
          ForEach(this.allTemplateChips(), (t: DeepReadTemplateDef): void => {
            Text(t.name)
              .fontSize(13).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(13))
              .fontFamily(FONT_SANS_MEDIUM)
              .fontColor(this.templateId === t.id ? INK : INK3)
              .constraintSize({ minHeight: 44 })
              .padding({ left: 12, right: 12, top: 6, bottom: 6 })
              .borderRadius(8)
              .backgroundColor(this.templateId === t.id
                ? accentSoftBg(this.accentColorStore) : Color.Transparent)
              .onClick((): void => { this.saveTemplate(t.id); })
          }, (t: DeepReadTemplateDef): string => t.id)
        }
        .margin({ top: 6 })
        Text('管理自定义模板')
          .fontSize(13).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(13))
          .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
          .margin({ top: 10 })
          .constraintSize({ minHeight: 44 })
          .onClick((): void => {
            router.pushUrl({ url: 'pages/DeepReadTemplateWorkbenchPage' });
          })
      }
      .width('100%').padding({ left: 14, right: 14, top: 8, bottom: 12 })
    }
    .width('100%')
  }

  @Builder
  ModelPickerCard(): void {
    Column() {
      CardRow({ label: '深度阅读模型', desc: '点按选择跟随或固定模型', isLast: true,
        onRowClick: (): void => { this.showModelMenu(); } }).enabled(!this.savingModel)
      Column({ space: 6 }) {
        Text(this.taskModelLabel).fontSize(14).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(14))
          .fontFamily(FONT_SANS_MEDIUM).fontColor(INK).width('100%').wordBreak(WordBreak.BREAK_ALL)
        if (this.taskModelDetail.length > 0) {
          Text(this.taskModelDetail).fontSize(12).lineHeight(17).fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK3).width('100%')
        }
        if (this.modelError.length > 0) {
          Text(this.modelError).fontSize(12).lineHeight(17).fontFamily(FONT_SANS_MEDIUM)
            .fontColor(ERROR).width('100%')
        }
      }
      .width('100%').alignItems(HorizontalAlign.Start).padding({ left: 14, right: 14, bottom: 12 })
      this.CardHairline(14)
      CardRow({ label: '聊天服务商配置',
        desc: '管理共用服务商、认证方式与模型', isLast: true,
        onRowClick: (): void => { router.pushUrl({ url: 'pages/ChatProviderSettingsPage' }); } })
    }
    .width('100%')
  }

  @Builder
  AiConfigCard(): void {
    Column() {
      Column() {
        Text('这里用于深度阅读，支持 OpenAI 兼容的 API Key 与 Chat Completions。聊天和小说使用聊天服务商配置。')
          .fontSize(13).lineHeight(lineHeightForSize(13)).fontFamily(FONT_SANS_MEDIUM)
          .fontColor(INK3).width('100%').margin({ bottom: 12 })
        BtnGhost({ label: this.copyingChat ? '正在读取聊天配置…' : '填入当前 Chat 配置',
          onTap: (): void => { this.fillCurrentChatDraft(); } })
          .width('100%').enabled(!this.copyingChat)
        if (this.chatCopyMessage.length > 0) {
          Text(this.chatCopyMessage).fontSize(13).lineHeight(lineHeightForSize(13))
            .fontFamily(FONT_SANS_MEDIUM).fontColor(INK3).width('100%').margin({ top: 8 })
        }
        Text('填入后请检查三个字段，再点击“保存全部”。深读模型非空时仍优先使用该模型。')
          .fontSize(12).lineHeight(17).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
          .width('100%').margin({ top: 8, bottom: 16 })
        this.FieldLabel('API Base URL', 0)
        TextInput({ text: this.aiBaseUrl })
          .height(44).fontSize(this.inputFontSize()).fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(8).padding({ left: 14, right: 14 })
          .margin({ top: 6 })
          .onChange((v: string): void => { this.aiBaseUrl = v; })
        this.FieldLabel('API Key', 16)
        TextInput({ text: this.aiKey, placeholder: 'sk-...' })
          .height(44).fontSize(this.inputFontSize()).fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(8).padding({ left: 14, right: 14 })
          .margin({ top: 6 })
          .onChange((v: string): void => { this.aiKey = v; })
        this.FieldLabel('模型名称', 16)
        TextInput({ text: this.model, placeholder: 'deepseek-chat' })
          .height(44).fontSize(this.inputFontSize()).fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(8).padding({ left: 14, right: 14 })
          .margin({ top: 6 })
          .onChange((v: string): void => { this.model = v; })
      }
      .width('100%')
      .padding({ left: 14, right: 14, top: 12, bottom: 12 })
    }
    .width('100%')
  }

  @Builder
  ModelsCard(): void {
    Column() {
      ForEach(this.models, (m: ModelConfig, idx: number): void => {
        Column() {
          Row() {
            Column() {
              Text(m.label.length > 0 ? m.label : m.model)
                .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
                .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
                .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
              Text(m.model + '  ·  ' + m.baseUrl)
                .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
                .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
                .fontColor(INK3)
                .margin({ top: 2 }).maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
            }
            .alignItems(HorizontalAlign.Start).layoutWeight(1)
            BtnGhost({ label: '删除', onTap: (): void => { this.deleteModel(m.id); } })
          }
          .width('100%').alignItems(VerticalAlign.Center)
          .constraintSize({ minHeight: 52 })
          .padding({ left: 14, right: 14, top: 10, bottom: 10 })
          if (idx < this.models.length - 1) {
            this.CardHairline(14)
          }
        }
        .width('100%')
      }, (m: ModelConfig): string => m.id)
      if (this.models.length > 0) {
        this.CardHairline(14)
      }
      Column() {
        this.FieldLabel('名称(可选)', 0)
        TextInput({ text: this.newLabel, placeholder: '如:GPT-4o / 备用 DeepSeek' })
          .height(44).fontSize(this.inputFontSize()).fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(8).padding({ left: 14, right: 14 })
          .margin({ top: 6 })
          .onChange((v: string): void => { this.newLabel = v; })
        this.FieldLabel('API Base URL', 12)
        TextInput({ text: this.newBaseUrl })
          .height(44).fontSize(this.inputFontSize()).fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(8).padding({ left: 14, right: 14 })
          .margin({ top: 6 })
          .onChange((v: string): void => { this.newBaseUrl = v; })
        this.FieldLabel('API Key', 12)
        TextInput({ text: this.newApiKey, placeholder: 'sk-...' })
          .height(44).fontSize(this.inputFontSize()).fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(8).padding({ left: 14, right: 14 })
          .margin({ top: 6 })
          .onChange((v: string): void => { this.newApiKey = v; })
        this.FieldLabel('模型名称', 12)
        TextInput({ text: this.newModel, placeholder: 'deepseek-chat' })
          .height(44).fontSize(this.inputFontSize()).fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(8).padding({ left: 14, right: 14 })
          .margin({ top: 6 })
          .onChange((v: string): void => { this.newModel = v; })
        Row() {
          BtnFill({ label: '添加模型', onTap: (): void => { this.addModel(); } })
          Text(this.modelSaved)
            .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
            .fontColor(INK3).margin({ left: 12 })
        }
        .width('100%').alignItems(VerticalAlign.Center)
        .margin({ top: 12 })
      }
      .width('100%')
      .padding({ left: 14, right: 14, top: 12, bottom: 12 })
    }
    .width('100%')
  }

  @Builder
  HotlistCard(): void {
    Column() {
      ForEach(this.hotlistToggles, (t: SourceToggle, idx: number): void => {
        CardToggleRow({
          label: t.displayName,
          checked: t.enabled,
          isLast: idx === this.hotlistToggles.length - 1,
          onToggle: (): void => { this.toggleHotlist(t.id); },
        })
      }, (t: SourceToggle): string => t.id)
    }
    .width('100%')
  }

  @Builder
  SourceCard(): void {
    CardRow({ label: '搜索服务配置', desc: '首选服务、Google 补充搜索与免费来源', isLast: true,
      onRowClick: (): void => { router.pushUrl({ url: 'pages/DeepReadSearchSettingsPage' }); } })
  }

  @Builder
  HotlistRefreshCard(): void {
    Column() {
      Row() {
        Text('缓存刷新间隔')
          .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
          .fontFamily(FONT_SANS_MEDIUM).fontColor(INK).layoutWeight(1)
        PillSelect({ options: ['15 分钟', '30 分钟', '1 小时', '2 小时'],
          selectedIndex: [15, 30, 60, 120].indexOf(this.hotlistRefreshMinutes),
          triggerColor: INK, triggerMinHeight: 44,
          onSelect: (index: number): void => { this.saveHotlistRefresh(index); } })
          .width(112)
      }
      .width('100%').alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 52 }).padding({ left: 14, right: 14, top: 8, bottom: 8 })
      this.CardHairline(14)
      CardToggleRow({ label: '仅在 Wi-Fi 下刷新', desc: '移动网络下保留缓存与现有榜单',
        checked: this.hotlistWifiOnly, onToggle: (): void => { this.toggleHotlistWifi(); } })
      CardToggleRow({ label: '显示中文标题', desc: '使用深读模型翻译英文标题', isLast: true,
        checked: this.hotlistTranslateZh, onToggle: (): void => { this.toggleHotlistTranslation(); } })
    }
    .width('100%')
  }

  // 历史单源表单保留兼容参考；当前入口只使用统一搜索服务设置。
  @Builder
  LegacySourceCard(): void {
    Column() {
      Column() {
        CardRow({
          label: '选择搜索源',
          valueText: KEYED_SOURCES.find((s) => s.id === this.selectedSource)?.displayName ?? '选择',
          onRowClick: (): void => {
            this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
              this.showSourcePicker = !this.showSourcePicker;
            });
          },
        })
        if (this.showSourcePicker) {
          Column() {
            ForEach(KEYED_SOURCES, (s: SearchSourceDef, idx: number): void => {
              Column() {
                Row() {
                  Text(s.displayName)
                    .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
                    .fontFamily(FONT_SANS_MEDIUM)
                    .fontColor(s.id === this.selectedSource ? this.accentColorStore : INK)
                }
                .width('100%').alignItems(VerticalAlign.Center)
                .constraintSize({ minHeight: 44 })
                .padding({ left: 32, right: 14, top: 8, bottom: 8 })
                .onClick((): void => {
                  this.selectSource(s.id);
                  this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
                    this.showSourcePicker = false;
                  });
                })
                if (idx < KEYED_SOURCES.length - 1) {
                  this.CardHairline(32)
                }
              }
              .width('100%')
            }, (s: SearchSourceDef): string => s.id)
            this.CardHairline(14)
          }
          .width('100%')
          .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
            .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
        }
      }
      .width('100%')
      .clip(true)
      Column() {
        this.FieldLabel('API Key', 0)
        TextInput({
          text: this.currentKey,
          placeholder: KEYED_SOURCES.find((s) => s.id === this.selectedSource)?.keyPlaceholder ?? 'api-key-...',
        })
          .height(44).fontSize(this.inputFontSize()).fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(8).padding({ left: 14, right: 14 })
          .margin({ top: 6 })
          .onChange((v: string): void => { this.currentKey = v; })
        Row() {
          BtnGhost({ label: '测试连通', onTap: (): void => { this.testSource(); } })
          if (this.testStatus.length > 0) {
            Text(this.testStatus === 'testing' ? '测试中...' : this.testResult)
              .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
              .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
              .fontColor(this.testStatus === 'error' ? ERROR : (this.testStatus === 'ok' ? SIGNAL : INK3))
              .margin({ left: 12 })
          }
        }
        .width('100%').alignItems(VerticalAlign.Center)
        .margin({ top: 12 })
        Row() {
          BtnFill({ label: '保存此源配置', onTap: (): void => { this.saveSourceConfig(); } })
        }
        .width('100%')
        .margin({ top: 12 })
      }
      .width('100%').padding({ left: 14, right: 14, top: 12, bottom: 12 })
    }
    .width('100%')
  }
}


@Entry
@Component
struct SettingDeepReadPage {
  @State private visible: boolean = false;
  private backHandler: () => boolean = (): boolean => false;
  onBackPress(): boolean { return this.backHandler(); }
  onPageShow(): void { this.visible = true; }
  onPageHide(): void { this.visible = false; }

  aboutToAppear(): void {
    if (getProductKind() === 'deepread') {
      const boundRouter = this.getUIContext().getRouter();
      for (let index: number = 1; index <= parseInt(router.getLength(), 10); index++) {
        const state: router.RouterState | undefined = boundRouter.getStateByIndex(index);
        if (state?.name !== 'DeepReadRootPage' && state?.path !== 'pages/DeepReadRootPage') continue;
        AppStorage.setOrCreate<number>('deepreadDesiredTab', 2);
        boundRouter.back({ url: 'pages/DeepReadRootPage' });
        return;
      }
      boundRouter.replaceUrl({ url: 'pages/DeepReadRootPage', params: { tab: 2 } }).then((): void => { boundRouter.clear(); });
    }
  }

  build(): void {
    if (getProductKind() !== 'deepread') { SettingDeepReadPageContent({ rootVisible: this.visible, onBackHandler: (handler: () => boolean): void => { this.backHandler = handler; } }) }
    else { Column().width('100%').height('100%').backgroundColor(D.paper) }
  }
}
