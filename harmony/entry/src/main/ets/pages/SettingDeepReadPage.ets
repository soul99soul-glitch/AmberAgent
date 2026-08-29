// SettingDeepReadPage — 深度阅读设置(AI 配置 + 热榜源开关 + 搜索源配置面板)
//   原误置于 HistoryPage,归位独立页(首页功能区/设置入口可达)。
import { ACCENT, BG, INK, INK2, INK3, INK4, SURFACE, SURFACE2, ERROR, SIGNAL, TEXT_STYLES, BODY, BODY2 } from '../design/tokens.ets';
import { FONT_SANS_SEMIBOLD, FONT_MONO_MEDIUM } from '../design/fonts.ets';
import { PageTitleBar, SectionHeader, ToggleRow, SelectRow, BtnFill, BtnGhost, PaperSeparator, PageSpacer } from '../components/PageTemplate.ets';
import { PillSelect } from '../components/ui/Select.ets';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { getAppContainer, getModelRegistry } from '../di/AppContainer.ets';
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
  @State aiKey: string = '';
  @State aiBaseUrl: string = 'https://api.deepseek.com';
  @State model: string = 'deepseek-chat';
  @State saved: string = '';
  @State hotlistToggles: SourceToggle[] = [];
  @State selectedSource: string = 'tavily';
  @State showSourcePicker: boolean = false;
  @State currentKey: string = '';
  @State testResult: string = '';
  @State testStatus: string = '';
  // 模型库(多模型配置,供议会席位 / 小说生成选择)
  @State models: ModelConfig[] = [];
  // P5:关注筛选 / 阅读体验 / 看板模型
  @State focusKeywords: string = '';
  @State focusModeIndex: number = 0;
  @State fontModeIndex: number = 0;
  @State fontScalePercent: number = 100;
  @State boardModelId: string = '';
  private focusModes: string[] = ['全部', '关注优先', '仅关注'];
  private fontModes: string[] = ['默认', '衬线'];
  @State newLabel: string = '';
  @State newBaseUrl: string = 'https://api.deepseek.com';
  @State newApiKey: string = '';
  @State newModel: string = 'deepseek-chat';
  @State modelSaved: string = '';
  private storage: Storage | null = null;
  private pageAlive: boolean = false;
  private loadToken: number = 0;

  aboutToAppear(): void {
    this.pageAlive = true;
    const token: number = ++this.loadToken;
    this.loadPage(token).catch((e: Error): void => {
      if (this.pageAlive && token === this.loadToken) {
        this.saved = `加载失败：${e.message}`;
      }
    });
  }

  aboutToDisappear(): void {
    this.pageAlive = false;
    this.loadToken++;
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

  private async saveBoardModel(): Promise<void> {
    await this.storage!.set<string>('deepread_model_id', this.boardModelId.trim());
    this.saved = '看板模型已保存 ✓';
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

  build(): void {
    Column() {
      PageTitleBar({ title: '深度阅读设置' })
      Scroll() {
        Column() {
          SectionHeader({ label: '关注筛选' })
          Column() {
            TextInput({ text: this.focusKeywords, placeholder: 'AI、大模型、机器人、数码 3C...' })
              .fontSize(BODY).height(44)
              .backgroundColor(SURFACE2).borderRadius(8)
              .onChange((v: string): void => { this.focusKeywords = v; })
            Row() {
              Text('筛选模式').fontSize(BODY).fontColor(INK).layoutWeight(1)
              PillSelect({
                options: this.focusModes, selectedIndex: this.focusModeIndex,
                onSelect: (i: number): void => { this.focusModeIndex = i; },
              })
            }
            .width('100%').margin({ top: 8 })
            Row() {
              Text('应用').fontSize(BODY2).fontColor(ACCENT)
                .padding({ left: 14, right: 14, top: 10, bottom: 10 })
                .onClick((): void => { this.saveFocus(); })
              Blank()
            }
            .width('100%')
          }
          .width('100%').padding({ left: 16, right: 16, top: 12, bottom: 12 })

          SectionHeader({ label: '阅读体验' })
          Column() {
            Row() {
              Text('阅读字体').fontSize(BODY).fontColor(INK)
              Blank()
              Row() {
                ForEach(this.fontModes, (m: string, i: number): void => {
                  Text(m)
                    .fontSize(BODY2).fontColor(this.fontModeIndex === i ? ACCENT : INK3)
                    .padding({ left: 12, right: 12, top: 6, bottom: 6 })
                    .borderRadius(8)
                    .backgroundColor(this.fontModeIndex === i ? SURFACE2 : Color.Transparent)
                    .onClick((): void => { this.fontModeIndex = i; })
                }, (m: string): string => m)
              }
            }
            .width('100%')
            Row() {
              Text('字号').fontSize(BODY).fontColor(INK)
              Blank()
              Text(`${this.fontScalePercent}%`).fontSize(BODY2).fontColor(INK3)
            }
            .width('100%').margin({ top: 10 })
            Slider({ value: this.fontScalePercent, min: 80, max: 130, step: 5, style: SliderStyle.OutSet })
              .width('100%')
              .selectedColor(ACCENT)
              .onChange((v: number): void => { this.fontScalePercent = Math.round(v); })
            Row() {
              Blank()
              Text('保存阅读体验').fontSize(BODY2).fontColor(ACCENT)
                .padding({ left: 14, right: 14, top: 10, bottom: 10 })
                .onClick((): void => { this.saveReading(); })
            }
            .width('100%')
          }
          .width('100%').padding({ left: 16, right: 16, top: 12, bottom: 12 })

          SectionHeader({ label: '看板模型' })
          Column() {
            TextInput({ text: this.boardModelId, placeholder: '留空跟随聊天模型(如 deepseek-chat)' })
              .fontSize(BODY).height(44)
              .backgroundColor(SURFACE2).borderRadius(8)
              .onChange((v: string): void => { this.boardModelId = v; })
            Row() {
              Blank()
              Text('保存模型').fontSize(BODY2).fontColor(ACCENT)
                .padding({ left: 14, right: 14, top: 10, bottom: 10 })
                .onClick((): void => { this.saveBoardModel(); })
            }
            .width('100%').margin({ top: 6 })
          }
          .width('100%').padding({ left: 16, right: 16, top: 12, bottom: 12 })

          SectionHeader({ label: 'AI 配置' })
          Column() {
            Text('API Base URL')
              .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
              .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
            TextInput({ text: this.aiBaseUrl })
              .height(44).fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
              .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family).fontColor(INK)
              .backgroundColor(SURFACE2).borderRadius(8).padding({ left: 14, right: 14 })
              .onChange((v: string): void => { this.aiBaseUrl = v; })
            Text('API Key')
              .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
              .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
              .margin({ top: 16 })
            TextInput({ text: this.aiKey, placeholder: 'sk-...' })
              .height(44).fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
              .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family).fontColor(INK)
              .placeholderColor(INK4).backgroundColor(SURFACE2).borderRadius(8)
              .padding({ left: 14, right: 14 }).onChange((v: string): void => { this.aiKey = v; })
            Text('模型名称')
              .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
              .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
              .margin({ top: 16 })
            TextInput({ text: this.model, placeholder: 'deepseek-chat' })
              .height(44).fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
              .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family).fontColor(INK)
              .placeholderColor(INK4).backgroundColor(SURFACE2).borderRadius(8)
              .padding({ left: 14, right: 14 }).onChange((v: string): void => { this.model = v; })
          }
          .width('100%').padding({ left: 16, right: 16, bottom: 16 })

          SectionHeader({ label: '模型库' })
          if (this.models.length === 0) {
            Text('暂无模型,请在下方添加。议会的每个席位需指定一个模型。')
              .fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
              .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
              .fontColor(INK3).padding({ left: 16, right: 16, bottom: 12 })
          }
          ForEach(this.models, (m: ModelConfig, idx: number): void => {
            Row() {
              Column() {
                Text(m.label.length > 0 ? m.label : m.model)
                  .fontSize(TEXT_STYLES.body.size).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
                  .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
                Text(m.model + '  ·  ' + m.baseUrl)
                  .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
                  .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
                  .margin({ top: 3 }).maxLines(1)
                  .textOverflow({ overflow: TextOverflow.Ellipsis })
              }
              .alignItems(HorizontalAlign.Start).layoutWeight(1)
              BtnGhost({ label: '删除', onTap: (): void => { this.deleteModel(m.id); } })
            }
            .width('100%').constraintSize({ minHeight: 64 })
            .padding({ left: 16, right: 16, top: 12, bottom: 12 })
            if (idx < this.models.length - 1) {
              PaperSeparator()
            }
          }, (m: ModelConfig): string => m.id)
          Column() {
            Text('名称(可选)').fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
              .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
            TextInput({ text: this.newLabel, placeholder: '如:GPT-4o / 备用 DeepSeek' })
              .height(44).fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
              .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family).fontColor(INK)
              .placeholderColor(INK4).backgroundColor(SURFACE2).borderRadius(8)
              .padding({ left: 14, right: 14 }).onChange((v: string): void => { this.newLabel = v; })
            Text('API Base URL').fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
              .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(INK3).margin({ top: 12 })
            TextInput({ text: this.newBaseUrl }).height(44).fontSize(TEXT_STYLES.secondary.size)
              .fontWeight(TEXT_STYLES.secondary.weight).lineHeight(TEXT_STYLES.secondary.lineHeight)
              .fontFamily(TEXT_STYLES.secondary.family).fontColor(INK).backgroundColor(SURFACE2).borderRadius(8)
              .padding({ left: 14, right: 14 }).onChange((v: string): void => { this.newBaseUrl = v; })
            Text('API Key').fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
              .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(INK3).margin({ top: 12 })
            TextInput({ text: this.newApiKey, placeholder: 'sk-...' }).height(44).fontSize(TEXT_STYLES.secondary.size)
              .fontWeight(TEXT_STYLES.secondary.weight).lineHeight(TEXT_STYLES.secondary.lineHeight)
              .fontFamily(TEXT_STYLES.secondary.family).fontColor(INK).placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(8).padding({ left: 14, right: 14 })
              .onChange((v: string): void => { this.newApiKey = v; })
            Text('模型名称').fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
              .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(INK3).margin({ top: 12 })
            TextInput({ text: this.newModel, placeholder: 'deepseek-chat' }).height(44)
              .fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
              .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family).fontColor(INK)
              .placeholderColor(INK4).backgroundColor(SURFACE2).borderRadius(8).padding({ left: 14, right: 14 })
              .onChange((v: string): void => { this.newModel = v; })
            Row() {
              BtnFill({ label: '添加模型', onTap: (): void => { this.addModel(); } })
              Text(this.modelSaved).fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
                .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(INK3).margin({ left: 12 })
            }
            .width('100%').margin({ top: 12 })
          }
          .width('100%').padding({ left: 16, right: 16, top: 16, bottom: 16 })

          SectionHeader({ label: '热榜源' })
          ForEach(this.hotlistToggles, (t: SourceToggle, idx: number): void => {
            ToggleRow({ label: t.displayName, checked: t.enabled, isLast: idx === this.hotlistToggles.length - 1,
              onToggle: (): void => { this.toggleHotlist(t.id); } })
          }, (t: SourceToggle): string => t.id)

          SectionHeader({ label: '搜索源' })
          SelectRow({ label: '选择搜索源', valueText: KEYED_SOURCES.find((s) => s.id === this.selectedSource)?.displayName ?? '选择',
            isLast: false, onRowClick: (): void => { this.showSourcePicker = !this.showSourcePicker; } })
          if (this.showSourcePicker) {
            Column() {
              ForEach(KEYED_SOURCES, (s: SearchSourceDef, idx: number): void => {
                Row() {
                  Text(s.displayName).fontSize(TEXT_STYLES.body.size).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
                    .fontFamily(FONT_SANS_SEMIBOLD).fontColor(s.id === this.selectedSource ? ACCENT : INK2)
                }
                .width('100%').constraintSize({ minHeight: 64 })
                .padding({ left: 32, right: 16, top: 12, bottom: 12 })
                .onClick((): void => {
                  this.selectSource(s.id);
                  this.showSourcePicker = false;
                })
                if (idx < KEYED_SOURCES.length - 1) {
                  PaperSeparator()
                }
              }, (s: SearchSourceDef): string => s.id)
            }
            .width('100%')
          }
          Column() {
            Text('API Key').fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
              .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
            TextInput({ text: this.currentKey, placeholder: KEYED_SOURCES.find((s) => s.id === this.selectedSource)?.keyPlaceholder ?? 'api-key-...' })
              .height(44).fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
              .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family).fontColor(INK)
              .placeholderColor(INK4).backgroundColor(SURFACE2).borderRadius(8).padding({ left: 14, right: 14 })
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
            .width('100%').margin({ top: 12 })
            Row() {
              BtnFill({ label: '保存此源配置', onTap: (): void => { this.saveSourceConfig(); } })
            }
            .width('100%').margin({ top: 8 })
          }
          .width('100%').padding({ left: 16, right: 16, top: 12, bottom: 12 })

          Text('NewsNow/DuckDuckGo/HackerNews/Jina 始终可用,无需 key。')
            .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(INK4)
            .padding({ left: 16, right: 16, top: 12, bottom: 12 })
          Row() {
            BtnFill({ label: '保存全部', onTap: (): void => { this.save(); } })
          }
          .width('100%').justifyContent(FlexAlign.Center).margin({ top: 8 })
          Text(this.saved.length > 0 ? this.saved : '')
            .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(ACCENT)
            .padding({ left: 16, top: 8, bottom: 24 })
          PageSpacer()
        }
        .width('100%').alignItems(HorizontalAlign.Start)
      }
      .layoutWeight(1).align(Alignment.TopStart).backgroundColor(BG)
      .align(Alignment.TopStart)
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
