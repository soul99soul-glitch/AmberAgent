import { promptAction } from '@kit.ArkUI';
import type { UIContext } from '@kit.ArkUI';
import { defaultSearchPrefs, loadSearchPrefs, updateSearchPrefs } from '@amber/chat-domain';
import type { SearchSettings, SearchCommonOptions, SearchServiceOptions, TaskModelReference, KeyValueStore } from '@amber/chat-domain';
import type { Storage } from '@amber/deepread-domain';
import { BUILTIN_TEMPLATES, loadCustomTemplates } from '../DeepReadTemplate.ets';
import type { DeepReadTemplateDef, CustomTemplateDef } from '../DeepReadTemplate.ets';
import type { DeepReadModelOption } from '../../di/ProviderModelRuntime.ets';
import type { DeepReadModelSelectionState } from '../DeepReadModelSelection.ets';
import type { DeepReadReaderAppearance } from '../DeepReadAppearance.ets';
import { DeepReadHaptics } from '../../design/DeepReadHaptics.ets';
import { writeDeepReadSearchEditorDraft, consumeDeepReadSearchEditorDraft } from '../DeepReadSearchDraft.ets';
import type { DeepReadSearchEditorDraft } from '../DeepReadSearchDraft.ets';

export interface DeepReadSettingsPorts {
  storage: Storage;
  kv: KeyValueStore;
  standalone: boolean;
  reduceMotion: () => boolean;
  uiContext: () => UIContext;
  loadAppearance: (storage: Storage) => Promise<DeepReadReaderAppearance>;
  loadHost: (token: number) => Promise<void>;
  loadModelSelection: () => Promise<DeepReadModelSelectionState>;
  saveModelSelection: (option: DeepReadModelOption | null) => Promise<TaskModelReference>;
  openSearchEditor: (serviceId: string) => Promise<void>;
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


@Observed
export class DeepReadSettingsSession {
  standaloneRoot: boolean = true;
  standaloneSearch: SearchSettings = defaultSearchPrefs();
  standaloneSearchLoaded: boolean = false;
  standaloneSearchError: string = '';
  selectedSearchId: string = '';
  modelDraftDirty: boolean = false;
  private searchLoadToken: number = 0;
  private persistedTemplateId: string = 'none';
  private settingsEdited: boolean = false;
  private readingSettingsLoaded: boolean = false;
  private restoredReadingDraft: boolean = false;
  private pendingSearchDraft: DeepReadSettingsSearchDraft | null = null;
  saved: string = '';
  saving: boolean = false;
  error: string = '';
  fontModeIndex: number = 0;
  fontScalePercent: number = 100;
  templateId: string = 'none';
  customTemplates: CustomTemplateDef[] = [];
  modelSheetOpen: boolean = false;
  taskModelReference: TaskModelReference | null = null;
  taskModelOptions: DeepReadModelOption[] = [];
  taskModelLabel: string = '正在读取模型';
  taskModelDetail: string = '';
  modelError: string = '';
  savingModel: boolean = false;
  private storage: Storage | null = null;
  private pageAlive: boolean = false;
  private loadToken: number = 0;
  pageLoad: Promise<void> = Promise.resolve();
  private modelLoadToken: number = 0;
  private confirmationTimer: number = -1;
  private ports: DeepReadSettingsPorts | null = null;
  private get deps(): DeepReadSettingsPorts { return this.ports as DeepReadSettingsPorts; }
  start(ports: DeepReadSettingsPorts): void { this.ports = ports; this.pageAlive = true; }
  isCurrent(token: number): boolean { return this.pageAlive && token === this.loadToken; }
  isAlive(): boolean { return this.pageAlive; }
  load(): void {
    const token: number = ++this.loadToken;
    this.pageLoad = this.loadPage(token).catch((error: Error): void => {
      if (this.isCurrent(token)) this.saved = `加载失败：${error.message}`;
    });
  }
  show(): void {
    if (this.deps.standalone) this.refreshStandaloneSearch();
    const storage: Storage | null = this.storage;
    if (storage === null) return;
    if (this.deps.standalone) this.refreshTemplateSelection();
    loadCustomTemplates(storage).then((list: CustomTemplateDef[]): void => {
      if (this.pageAlive) this.customTemplates = list;
    }).catch((): void => {});
    this.refreshTaskModelSelection();
  }
  hide(): void { this.saveSessionDraft(); }
  dispose(): void {
    this.saveSessionDraft();
    this.pageAlive = false;
    this.loadToken++; this.modelLoadToken++; this.searchLoadToken++;
    if (this.confirmationTimer >= 0) clearTimeout(this.confirmationTimer);
  }

  async loadPage(token: number): Promise<void> {
    const storage: Storage = this.deps.storage;
    this.storage = storage;
    if (this.deps.standalone) await this.deps.loadAppearance(storage);
    await this.deps.loadHost(token);
    // P5:关注筛选/阅读体验/看板模型
    const fontMode: string = await storage.get<string>('deepread_font_mode', this.deps.standalone ? 'serif' : 'default');
    const fontScale: number = Number(await storage.get<number>('deepread_font_scale', 100));
    const tplId: string = await storage.get<string>('deepread_template_id', 'none');
    if (!this.pageAlive || token !== this.loadToken) return;
    if (!this.restoredReadingDraft) {
      this.fontModeIndex = fontMode === 'serif' ? 1 : 0;
      this.fontScalePercent = Math.max(70, Math.min(180, Number.isFinite(fontScale) ? fontScale : 100));
    }
    this.readingSettingsLoaded = true;
    if (!this.restoredReadingDraft || tplId !== this.persistedTemplateId) this.templateId = tplId;
    this.persistedTemplateId = tplId;
    this.customTemplates = await loadCustomTemplates(storage);
    await this.refreshTaskModelSelection();
  }

  markSettingsEdited(): void {
    this.settingsEdited = true;
    this.clearSavedNotice();
  }

  saveSessionDraft(): void {
    if (!this.deps.standalone || !this.standaloneRoot) return;
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

  restoreSessionDraft(): void {
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

  async refreshStandaloneSearch(): Promise<void> {
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
      const current: SearchSettings = await loadSearchPrefs(this.deps.kv);
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

  openSearchServiceEditor(serviceId: string): void {
    if (!this.standaloneSearchLoaded) return;
    writeDeepReadSearchEditorDraft({ settings: this.standaloneSearch,
      selectedId: this.selectedSearchId, dirty: false });
    this.deps.openSearchEditor(serviceId).catch((error: Error): void => {
      consumeDeepReadSearchEditorDraft();
      if (this.pageAlive) this.standaloneSearchError = '无法打开搜索服务：' + error.message;
    });
  }

  async submit(): Promise<void> {
    if (!this.standaloneSearchLoaded || this.saving) return;
    this.saving = true;
    this.error = '';
    try {
      await this.saveStandaloneSettings();
    } catch (error) {
      if (this.pageAlive) this.error = '保存设置失败：' + String(error);
    } finally {
      if (this.pageAlive) this.saving = false;
    }
  }

  async saveStandaloneSettings(): Promise<void> {
    this.clearSavedNotice();
    if (!this.standaloneSearchLoaded) throw new Error('搜索设置尚未读取完成');
    const draft: SearchSettings = JSON.parse(JSON.stringify(this.standaloneSearch)) as SearchSettings;
    const selected: number = draft.searchServices.findIndex(
      (service: SearchServiceOptions): boolean => service.id === this.selectedSearchId);
    if (this.selectedSearchId.length > 0 && selected < 0) throw new Error('首选搜索服务已删除，请重新选择');
    draft.searchServiceSelected = Math.max(0, selected);
    await updateSearchPrefs(this.deps.kv, (current: SearchSettings): SearchSettings => {
      draft.enableWebSearch = current.enableWebSearch;
      const resultSize: number = draft.searchCommonOptions.resultSize;
      const common: SearchCommonOptions = JSON.parse(JSON.stringify(current.searchCommonOptions)) as SearchCommonOptions;
      common.resultSize = resultSize;
      draft.searchCommonOptions = common;
      return draft;
    });
    await this.saveStandaloneReading();
  }

  async refreshTemplateSelection(): Promise<void> {
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

  async refreshTaskModelSelection(): Promise<void> {
    const token: number = ++this.modelLoadToken;
    try {
      const selection: DeepReadModelSelectionState = await this.deps.loadModelSelection();
      if (!this.pageAlive || token !== this.modelLoadToken) return;
      this.taskModelOptions = selection.options;
      if (this.deps.standalone && this.modelDraftDirty) {
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

  async selectTaskModel(option: DeepReadModelOption | null): Promise<void> {
    if (this.savingModel) return;
    if (this.deps.standalone) {
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
      const reference: TaskModelReference = await this.deps.saveModelSelection(option);
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

  taskModelSelected(option: DeepReadModelOption): boolean {
    const reference: TaskModelReference | null = this.taskModelReference;
    return reference !== null && reference.kind === 'fixed'
      && reference.pair.providerId === option.providerId && reference.pair.modelId === option.modelId;
  }

  allTemplateChips(): DeepReadTemplateDef[] {
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

  async saveTemplate(id: string): Promise<void> {
    await this.storage!.set<string>('deepread_template_id', id);
    this.templateId = id;
    this.saved = '模板已选择 ✓';
  }

  async saveReading(): Promise<void> {
    const kv: Storage = this.storage!;
    await kv.set<string>('deepread_font_mode', this.fontModeIndex === 1 ? 'serif' : 'default');
    await kv.set<number>('deepread_font_scale', this.fontScalePercent);
    this.saved = '阅读体验已保存 ✓';
  }

  async saveStandaloneReading(): Promise<void> {
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
      this.taskModelReference = await this.deps.saveModelSelection(option ?? null);
    }
    await storage.set<string>('deepread_font_mode', this.fontModeIndex === 1 ? 'serif' : 'default');
    await storage.set<number>('deepread_font_scale', this.fontScalePercent);
    await storage.set<string>('deepread_template_id', this.templateId);
    if (this.pageAlive && token === this.loadToken) {
      DeepReadHaptics.success();
      this.modelDraftDirty = false;
      this.settingsEdited = false;
      this.restoredReadingDraft = false;
      if (this.deps.standalone && this.standaloneRoot) AppStorage.delete(SETTINGS_SESSION_DRAFT);
      this.persistedTemplateId = this.templateId;
      this.deps.uiContext().animateTo({ duration: this.deps.reduceMotion() ? 0 : 300, curve: Curve.EaseOut }, (): void => {
        this.saved = '已保存，下一次生成将使用此配置。';
      });
      this.confirmationTimer = setTimeout((): void => { this.clearSavedNotice(); }, 3000);
    }
  }

  clearSavedNotice(): void {
    if (this.confirmationTimer >= 0) clearTimeout(this.confirmationTimer);
    this.confirmationTimer = -1;
    this.deps.uiContext().animateTo({ duration: this.deps.reduceMotion() ? 0 : 300, curve: Curve.EaseOut }, (): void => { this.saved = ''; });
  }

  showModelMenu(): void {
    this.modelSheetOpen = true;
    this.refreshTaskModelSelection();
  }
}
