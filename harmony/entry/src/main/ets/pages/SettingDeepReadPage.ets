// SettingDeepReadPage — 深度阅读设置(AI 配置 + 热榜源开关 + 搜索源配置面板)
//   原误置于 HistoryPage,归位独立页(首页功能区/设置入口可达)。
//
// 2026-09-27 二级页 iOS 对齐(无截图,沿用规范):仅换视觉骨架
//   CenteredTitleBar / PlainSection / CardGroup(r18+描边)+ h16 /
//   表单控件装入卡片(标题 16 medium sans、字段标 mono meta、值 12 muted)/
//   FooterNote 组下脚注,逻辑零改动(读写/测试连通/保存/模型库增删全原样)。
import {
  ACCENT, BG, INK, INK3, INK4, SURFACE, SURFACE2, ERROR, SIGNAL, LINE,
  TEXT_STYLES, GUTTER, accentSoftBg, lineHeightForSize,
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
import { router, promptAction } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { getAppContainer, getModelRegistry, resolveChatProviderChoice } from '../di/AppContainer.ets';
import { chatChoiceToDeepReadDraft } from '../platform_impl/ChatDeepReadDraft.ets';
import type { ChatDeepReadDraftResult } from '../platform_impl/ChatDeepReadDraft.ets';
import type { Storage, ModelConfig } from '@amber/deepread-domain';
import { makeModelConfig } from '@amber/deepread-domain';
import { NEWSNOW_PRESETS } from '../platform_impl/NewsNowProvider.ets';
import type { NewsNowPreset } from '../platform_impl/NewsNowProvider.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Settings';


interface SourceToggle {
  id: string;
  displayName: string;
  enabled: boolean;
}

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

@Entry
@Component
struct SettingDeepReadPage {
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State aiKey: string = '';
  @State aiBaseUrl: string = 'https://api.deepseek.com';
  @State model: string = 'deepseek-chat';
  @State saved: string = '';
  @State chatCopyMessage: string = '';
  @State copyingChat: boolean = false;
  @State hotlistToggles: SourceToggle[] = [];
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
  private storage: Storage | null = null;
  private pageAlive: boolean = false;
  private loadToken: number = 0;
  private pageLoad: Promise<void> = Promise.resolve();

  aboutToAppear(): void {
    this.pageAlive = true;
    const token: number = ++this.loadToken;
    this.pageLoad = this.loadPage(token).catch((e: Error): void => {
      if (this.pageAlive && token === this.loadToken) {
        this.saved = `加载失败：${e.message}`;
      }
    });
  }

  aboutToDisappear(): void {
    this.pageAlive = false;
    this.loadToken++;
  }

  // 从模板工作台返回时刷新自定义模板列表(aboutToAppear 在栈内返回不重跑)
  onPageShow(): void {
    const storage: Storage | null = this.storage;
    if (storage === null) return;
    loadCustomTemplates(storage).then((list: CustomTemplateDef[]): void => {
      if (this.pageAlive) this.customTemplates = list;
    }).catch((): void => {});
  }

  private async loadPage(token: number): Promise<void> {
    const storage: Storage = getAppContainer().storage;
    this.storage = storage;
    const aiKey: string = await storage.get<string>('ai_api_key', '');
    const aiBaseUrl: string = await storage.get<string>('ai_base_url', 'https://api.deepseek.com');
    const model: string = await storage.get<string>('ai_model', 'deepseek-chat');
    // 加载热榜源开关
    const hotlist: SourceToggle[] = [];
    for (let i = 0; i < NEWSNOW_PRESETS.length; i++) {
      const p: NewsNowPreset = NEWSNOW_PRESETS[i];
      const enabled: string = await storage.get<string>('source_' + p.id, 'true');
      hotlist.push({ id: p.id, displayName: p.displayName, enabled: enabled === 'true' });
    }
    // 加载当前选中的搜索源和 key
    const selectedSource: string = await storage.get<string>('active_search_source', 'tavily');
    const currentKey: string = await storage.get<string>(selectedSource + '_api_key', '');
    // 加载模型库
    const models: ModelConfig[] = await getModelRegistry().list();
    // P5:关注筛选/阅读体验/看板模型
    const focusKeywords: string = await storage.get<string>('deepread_focus_keywords', '');
    const focusMode: string = await storage.get<string>('deepread_focus_mode', 'all');
    const fontMode: string = await storage.get<string>('deepread_font_mode', 'default');
    const fontScale: number = await storage.get<number>('deepread_font_scale', 100);
    const boardModelId: string = await storage.get<string>('deepread_model_id', '');
    const tplId: string = await storage.get<string>('deepread_template_id', 'none');
    if (!this.pageAlive || token !== this.loadToken) return;
    this.aiKey = aiKey;
    this.aiBaseUrl = aiBaseUrl;
    this.model = model;
    this.hotlistToggles = hotlist;
    this.selectedSource = selectedSource;
    this.currentKey = currentKey;
    this.models = models;
    this.focusKeywords = focusKeywords;
    this.focusModeIndex = focusMode === 'focus_first' ? 1 : focusMode === 'focus_only' ? 2 : 0;
    this.fontModeIndex = fontMode === 'serif' ? 1 : 0;
    this.fontScalePercent = fontScale;
    this.boardModelId = boardModelId;
    this.templateId = tplId;
    this.customTemplates = await loadCustomTemplates(storage);
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

  // ── 深读模型选择(对齐 iOS BoardSettings 模型区:跟随聊天配置 / 模型库快照)──
  // 引擎链静态读 ai_base_url/ai_api_key + (deepread_model_id || ai_model),
  // 选择即把凭据快照写入既有键,不改动引擎。

  private currentBoardModelLabel(): string {
    if (this.boardModelId.trim().length > 0) return this.boardModelId.trim();
    return this.model.length > 0 ? `跟随聊天配置（${this.model}）` : '跟随聊天配置';
  }

  private showModelMenu(): void {
    this.modelSheetOpen = true;
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
            this.ModelSheetRow('跟随当前聊天配置', this.boardModelId.trim().length === 0, (): void => {
              this.followChatConfig();
            })
            ForEach(this.models, (m: ModelConfig): void => {
              this.ModelSheetRow(m.label.length > 0 ? `${m.label}（${m.model}）` : m.model,
                this.boardModelId.trim() === m.model, (): void => {
                  this.applyRegistryModel(m);
                })
            }, (m: ModelConfig): string => m.id)
            if (this.models.length === 0) {
              Text('模型库为空，可在下方「高级 · 模型库」添加。')
                .fontSize(12).lineHeight(17).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
                .width('100%').padding({ left: 20, right: 20, top: 8, bottom: 8 })
            }
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
    }
    .width('100%').height('100%')
    .backgroundColor('#66000000')
    .justifyContent(FlexAlign.End)
  }

  @Builder
  ModelSheetRow(label: string, selected: boolean, onTap: () => void): void {
    Row() {
      Text(label)
        .fontSize(15).lineHeight(lineHeightForSize(15)).fontFamily(FONT_SANS_MEDIUM)
        .fontColor(selected ? this.accentColorStore : INK)
        .layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
      if (selected) {
        Icon({ name: 'check', iconSize: 16, color: this.accentColorStore })
      }
    }
    .width('100%')
    .constraintSize({ minHeight: 48 })
    .padding({ left: 20, right: 20, top: 10, bottom: 10 })
    .alignItems(VerticalAlign.Center)
    .onClick((): void => {
      this.modelSheetOpen = false;
      onTap();
    })
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
    for (let i = 0; i < this.hotlistToggles.length; i++) {
      const t: SourceToggle = this.hotlistToggles[i];
      if (t.id === id) {
        const newEnabled: boolean = !t.enabled;
        newToggles.push({ id: t.id, displayName: t.displayName, enabled: newEnabled });
        await this.storage!.set<string>('source_' + id, newEnabled ? 'true' : 'false');
      } else {
        newToggles.push(t);
      }
    }
    this.hotlistToggles = newToggles;
  }

  async saveSourceConfig(): Promise<void> {
    await this.storage!.set<string>('active_search_source', this.selectedSource);
    await this.storage!.set<string>(this.selectedSource + '_api_key', this.currentKey);
    this.saved = '已保存 ✓';
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

  // 卡片内输入框统一样式(向卡片行靠:SURFACE2 底 r8,正文 15)
  private inputFontSize(): number {
    return TEXT_STYLES.body.size;
  }

  build(): void {
    Stack() {
      Column() {
        CenteredTitleBar({ title: '深度阅读设置' })
      Scroll() {
        Column() {
          PlainSection({ label: '模型' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.ModelPickerCard(); } })
            .margin({ left: GUTTER, right: GUTTER })
          FooterNote({ text: '从模型库选择会把该模型的地址与密钥快照给深度阅读；跟随则使用当前聊天配置。' })

          PlainSection({ label: '热榜源' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.HotlistCard(); } })
            .margin({ left: GUTTER, right: GUTTER })

          PlainSection({ label: '关注筛选' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.FocusCard(); } })
            .margin({ left: GUTTER, right: GUTTER })

          PlainSection({ label: '阅读体验' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.ReadingCard(); } })
            .margin({ left: GUTTER, right: GUTTER })

          PlainSection({ label: '搜索源' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.SourceCard(); } })
            .margin({ left: GUTTER, right: GUTTER })
          FooterNote({ text: 'NewsNow/DuckDuckGo/HackerNews/Jina 始终可用,无需 key。' })

          PlainSection({ label: '高级 · 手动 AI 配置' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.AiConfigCard(); } })
            .margin({ left: GUTTER, right: GUTTER })

          PlainSection({ label: '高级 · 模型库' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.ModelsCard(); } })
            .margin({ left: GUTTER, right: GUTTER })
          FooterNote({ text: '模型库用于议会席位；聊天和小说使用聊天服务商配置。' })
          if (this.models.length === 0) {
            FooterNote({ text: '暂无模型,请在下方添加。议会的每个席位需指定一个模型。' })
          }

          Row() {
            BtnFill({ label: '保存全部', onTap: (): void => { this.save(); } })
          }
          .width('100%').justifyContent(FlexAlign.Center).margin({ top: 16 })
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
    }
    .width('100%').height('100%')
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
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

  // 卡片行级 accent 动作(保存/应用;44 热区,右对齐)
  @Builder
  CardAction(label: string, onTap: () => void): void {
    Row() {
      Blank()
      Text(label)
        .fontSize(15).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(15))
        .fontFamily(FONT_SANS_MEDIUM).fontColor(this.accentColorStore)
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
            onSelect: (i: number): void => { this.focusModeIndex = i; },
          })
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
                .fontColor(this.fontModeIndex === i ? this.accentColorStore : INK3)
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
        Slider({ value: this.fontScalePercent, min: 80, max: 130, step: 5, style: SliderStyle.OutSet })
          .width('100%')
          .selectedColor(this.accentColorStore)
          .onChange((v: number): void => { this.fontScalePercent = Math.round(v); })
      }
      .width('100%').padding({ left: 14, right: 14, top: 12, bottom: 4 })
      this.CardHairline(14)
      this.CardAction('保存阅读体验', (): void => { this.saveReading(); })
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
              .fontColor(this.templateId === t.id ? this.accentColorStore : INK3)
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
          .fontFamily(FONT_SANS_MEDIUM).fontColor(this.accentColorStore)
          .margin({ top: 10 })
          .constraintSize({ minHeight: 36 })
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
      CardRow({ label: '深度阅读模型', valueText: this.currentBoardModelLabel(),
        onRowClick: (): void => { this.showModelMenu(); } })
      this.CardHairline(14)
      CardRow({ label: '聊天服务商配置',
        desc: '聊天和小说共用；任务模型可另选服务商', isLast: true,
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
    Column() {
      CardRow({
        label: '选择搜索源',
        valueText: KEYED_SOURCES.find((s) => s.id === this.selectedSource)?.displayName ?? '选择',
        onRowClick: (): void => { this.showSourcePicker = !this.showSourcePicker; },
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
                this.showSourcePicker = false;
              })
              if (idx < KEYED_SOURCES.length - 1) {
                this.CardHairline(32)
              }
            }
            .width('100%')
          }, (s: SearchSourceDef): string => s.id)
        }
        .width('100%')
        this.CardHairline(14)
      }
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
