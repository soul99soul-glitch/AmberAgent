import { promptAction } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { Storage, ModelConfig } from '@amber/deepread-domain';
import { makeModelConfig } from '@amber/deepread-domain';
import { HOTLIST_PRESETS } from '../NewsNowProvider.ets';
import type { NewsNowPreset } from '../NewsNowProvider.ets';
import { chatChoiceToDeepReadDraft } from '../ChatDeepReadDraft.ets';
import type { ChatDeepReadDraftResult } from '../ChatDeepReadDraft.ets';
import type { ChatProviderChoice } from '../../di/ChatRuntimeContracts.ets';
const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Settings';
export interface SourceToggle { id: string; displayName: string; enabled: boolean; }
export interface DeepReadHostSettingsPorts {
  storage: Storage;
  isAlive: () => boolean;
  isCurrent: (token: number) => boolean;
  waitForLoad: () => Promise<void>;
  setNotice: (message: string) => void;
  getNotice: () => string;
  listModels: () => Promise<ModelConfig[]>;
  saveModels: (models: ModelConfig[]) => Promise<void>;
  resolveChatChoice: () => Promise<ChatProviderChoice | null>;
}
@Observed
export class DeepReadHostSettings {
  aiKey: string = '';
  aiBaseUrl: string = 'https://api.deepseek.com';
  model: string = 'deepseek-chat';
  chatCopyMessage: string = '';
  copyingChat: boolean = false;
  hotlistToggles: SourceToggle[] = [];
  hotlistRefreshMinutes: number = 60;
  hotlistWifiOnly: boolean = false;
  hotlistTranslateZh: boolean = false;
  selectedSource: string = 'tavily';
  showSourcePicker: boolean = false;
  currentKey: string = '';
  testResult: string = '';
  testStatus: string = '';
  models: ModelConfig[] = [];
  focusKeywords: string = '';
  focusModeIndex: number = 0;
  boardModelId: string = '';
  focusModes: string[] = ['全部', '关注优先', '仅关注'];
  fontModes: string[] = ['默认', '衬线'];
  newLabel: string = '';
  newBaseUrl: string = 'https://api.deepseek.com';
  newApiKey: string = '';
  newModel: string = 'deepseek-chat';
  modelSaved: string = '';
  private ports: DeepReadHostSettingsPorts | null = null;
  private loadRevision: number = 0;
  private get deps(): DeepReadHostSettingsPorts { return this.ports as DeepReadHostSettingsPorts; }
  private get storage(): Storage { return this.deps.storage; }
  private get pageAlive(): boolean { return this.deps.isAlive(); }
  private get saved(): string { return this.deps.getNotice(); }
  private set saved(value: string) { this.deps.setNotice(value); }
  start(ports: DeepReadHostSettingsPorts): void { this.ports = ports; }
  async loadPage(token: number): Promise<void> {
    this.loadRevision = token;
    const storage: Storage = this.storage;
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
    const models: ModelConfig[] = await this.deps.listModels();
    const focusKeywords: string = await storage.get<string>('deepread_focus_keywords', '');
    const focusMode: string = await storage.get<string>('deepread_focus_mode', 'all');
    const boardModelId: string = await storage.get<string>('deepread_model_id', '');
    if (!this.deps.isCurrent(token)) return;
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
    this.boardModelId = boardModelId;
  }

  async fillCurrentChatDraft(): Promise<void> {
    if (this.copyingChat) return;
    const token: number = this.loadRevision;
    this.copyingChat = true;
    this.chatCopyMessage = '';
    try {
      // A first-page load must finish before the requested draft replaces its fields.
      await this.deps.waitForLoad();
      const result: ChatDeepReadDraftResult = chatChoiceToDeepReadDraft(await this.deps.resolveChatChoice());
      if (!this.pageAlive || !this.deps.isCurrent(token)) return;
      if (result.draft !== null) {
        this.aiBaseUrl = result.draft.baseUrl;
        this.aiKey = result.draft.apiKey;
        this.model = result.draft.model;
        this.saved = '';
      }
      this.chatCopyMessage = result.message;
    } catch (_error) {
      if (this.pageAlive && this.deps.isCurrent(token)) {
        this.chatCopyMessage = '读取当前聊天配置失败，请返回聊天服务商配置检查。';
      }
    } finally {
      this.copyingChat = false;
    }
  }

  async saveFocus(): Promise<void> {
    const kv: Storage = this.storage!;
    await kv.set<string>('deepread_focus_keywords', this.focusKeywords.trim());
    const mode: string = this.focusModeIndex === 1 ? 'focus_first'
      : this.focusModeIndex === 2 ? 'focus_only' : 'all';
    await kv.set<string>('deepread_focus_mode', mode);
    this.saved = '关注筛选已保存 ✓';
  }

  currentBoardModelLabel(): string {
    if (this.boardModelId.trim().length > 0) return this.boardModelId.trim();
    return this.model.length > 0 ? `跟随聊天配置（${this.model}）` : '跟随聊天配置';
  }

  async followChatConfig(): Promise<void> {
    try {
      const result: ChatDeepReadDraftResult = chatChoiceToDeepReadDraft(await this.deps.resolveChatChoice());
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

  async applyRegistryModel(m: ModelConfig): Promise<void> {
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
    this.models = await this.deps.listModels();
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
    await this.deps.saveModels(next);
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
    await this.deps.saveModels(next);
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

  async saveHotlistRefresh(index: number): Promise<void> {
    const minutes: number = [15, 30, 60, 120][index];
    try {
      await this.storage!.set<number>('deepread_hotlist_refresh_minutes', minutes);
      this.hotlistRefreshMinutes = minutes;
    } catch (error) {
      this.saved = `刷新设置保存失败：${String(error)}`;
    }
  }

  async toggleHotlistWifi(): Promise<void> {
    const previous: boolean = this.hotlistWifiOnly;
    this.hotlistWifiOnly = !previous;
    try {
      await this.storage!.set<string>('deepread_hotlist_wifi_only', this.hotlistWifiOnly ? 'true' : 'false');
    } catch (error) {
      this.hotlistWifiOnly = previous;
      this.saved = `网络设置保存失败：${String(error)}`;
    }
  }

  async toggleHotlistTranslation(): Promise<void> {
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
}
