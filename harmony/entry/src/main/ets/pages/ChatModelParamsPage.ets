import {
  ACCENT, BG, ERROR, INK, INK3, LINE, RAISED, TEXT_STYLES,
} from '../design/tokens.ets';
import { PageTitleBar, SectionHeader, PaperSeparator, BtnFill, BtnGhost, Seg, ToggleRow } from '../components/PageTemplate.ets';
import { FONT_MONO, FONT_MONO_MEDIUM } from '../design/fonts.ets';
import { router } from '@kit.ArkUI';
import type {
  CustomBody, CustomHeader, JsonValue, ProviderModel, ProviderSetting,
  ModelType, ModalityFull, BuiltInTools, ProviderSettingOpenAIVariant, ModelAbility,
} from '@amber/chat-domain';
import { loadProviders, saveProviders, resolveModelDefaults } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';

interface ModelFieldOptions {
  label: string;
  value: string;
  placeholder: string;
  onChange: (value: string) => void;
}


// UA 伪装预设(iOS OpenAICompatUserAgents / Android SettingProviderModelSettingsForm 同源)
const UA_PRESETS: string[][] = [
  ['OpenCode', 'opencode/1.18.18'],
  ['Claude Code', 'claude-cli/1.0.85'],
  ['Cursor', 'Cursor/1.7.52'],
  ['Cline', 'cline/3.18.0'],
];

interface RouterParams {
  providerId?: string;
  modelId?: string;
}

// 模型类型 Seg 值(ModelType:chat/image/embedding)
const MODEL_TYPE_OPTIONS: string[] = ['聊天', '生图', '嵌入'];
const MODEL_TYPE_VALUES: string[] = ['chat', 'image', 'embedding'];

class ModelParameterColors {
  background: string = BG;
  ink: string = INK;
  muted: string = INK3;
  line: string = LINE;
  raised: string = RAISED;
  error: string = ERROR;
}

@Entry
@Component
struct ChatModelParamsPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('reduceMotion') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') appBackgrounded: boolean = false;
  @State loaded: boolean = false;
  @State saving: boolean = false;
  @State error: string = '';
  @State advancedExpanded: boolean = false;
  @State modelLabel: string = '';
  // 草稿态(dialogState.currentState 语义;raw 文本 + 最近合法 body 值)
  @State headerNames: string[] = [];
  @State headerValues: string[] = [];
  @State bodyKeys: string[] = [];
  @State bodyTexts: string[] = [];
  @State bodyErrors: string[] = [];
  private bodyValues: JsonValue[] = [];
  private provider: ProviderSetting | null = null;
  private modelRowId: string = '';
  // 提供商覆写(D-109 补:展示 + 移除;新增/编辑待提供商编辑器支持 detached 模式)
  @State overwriteName: string = '';
  @State hasOverwrite: boolean = false;
  // 模型属性(类型/输入模态/上下文窗口;Android 单模型表单对齐)
  @State modelType: string = 'chat';
  @State inputText: boolean = true;
  @State inputImage: boolean = false;
  @State inputAudio: boolean = false;
  @State contextWindowText: string = '';
  @State toolCallingEnabled: boolean = true;
  @State reasoningEnabled: boolean = false;
  @State rawModelId: string = '';
  @State hasRegistryDefaults: boolean = false;
  // 同一 model.tools 草稿；不因当前协议不支持而移除已存选项。
  @State toolSearch: boolean = false;
  @State toolUrlContext: boolean = false;
  @State toolImageGen: boolean = false;
  @State toolProtocol: string = 'other';
  @State toolProviderName: string = '';
  @State colors: ModelParameterColors = new ModelParameterColors();
  @StorageProp('darkMode') @Watch('refreshColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshColors') themePackId: string = '';
  @StorageProp('accentColor') @Watch('refreshColors') accentColor: string = ACCENT;
  @StorageProp('navBarHeight') navBarHeight: number = 0;

  aboutToAppear(): void {
    const params = router.getParams() as RouterParams;
    const providerId: string = params !== null && params.providerId !== undefined
      ? params.providerId : '';
    this.modelRowId = params !== null && params.modelId !== undefined
      ? params.modelId : '';
    loadProviders(getChatKvStore()).then((list: ProviderSetting[] | null): void => {
      const provider: ProviderSetting | undefined = (list ?? []).find(
        (p: ProviderSetting): boolean => p.id === providerId);
      const model: ProviderModel | undefined = provider !== undefined
        ? provider.models.find((m: ProviderModel): boolean => m.id === this.modelRowId)
        : undefined;
      if (provider !== undefined && model !== undefined) {
        this.provider = provider;
        this.modelLabel = model.displayName.length > 0 ? model.displayName : model.modelId;
        this.headerNames = model.customHeaders.map((h: CustomHeader): string => h.name);
        this.headerValues = model.customHeaders.map((h: CustomHeader): string => h.value);
        this.bodyKeys = model.customBodies.map((b: CustomBody): string => b.key);
        // :413-415 — 初值 = encodeToString(value)(紧凑 JSON,同 JSON.stringify)
        this.bodyTexts = model.customBodies.map((b: CustomBody): string => JSON.stringify(b.value));
        this.bodyErrors = model.customBodies.map((): string => '');
        this.bodyValues = model.customBodies.map((b: CustomBody): JsonValue => b.value);
        this.hasOverwrite = model.providerOverwrite !== null;
        this.overwriteName = model.providerOverwrite !== null
          ? model.providerOverwrite.name : '';
        // 模型属性草稿
        this.rawModelId = model.modelId;
        this.hasRegistryDefaults = resolveModelDefaults(model.modelId).matched;
        this.modelType = model.type;
        this.toolCallingEnabled = model.abilities.indexOf('tool') >= 0;
        this.reasoningEnabled = model.abilities.indexOf('reasoning') >= 0;
        this.inputText = model.inputModalities.indexOf('text') >= 0;
        this.inputImage = model.inputModalities.indexOf('image') >= 0;
        this.inputAudio = model.inputModalities.indexOf('audio') >= 0;
        this.contextWindowText = model.contextWindowTokens !== null
          ? `${model.contextWindowTokens}` : '';
        this.toolSearch = model.tools.indexOf('search') >= 0;
        this.toolUrlContext = model.tools.indexOf('url_context') >= 0;
        this.toolImageGen = model.tools.indexOf('image_generation') >= 0;
        this.updateToolProtocol(model.providerOverwrite ?? provider);
      }
      this.loaded = true;
    }).catch((e: Error): void => {
      this.error = `加载失败：${e.message}`;
      this.loaded = true;
    });
  }

  onBackPress(): boolean { return this.saving; }

  private applyRecommendedDefaults(): void {
    const defaults = resolveModelDefaults(this.rawModelId);
    if (!defaults.matched) return;
    this.modelType = defaults.type;
    this.toolCallingEnabled = defaults.abilities.indexOf('tool') >= 0;
    this.reasoningEnabled = defaults.abilities.indexOf('reasoning') >= 0;
    this.inputText = defaults.inputModalities.indexOf('text') >= 0;
    this.inputImage = defaults.inputModalities.indexOf('image') >= 0;
    this.inputAudio = defaults.inputModalities.indexOf('audio') >= 0;
    this.contextWindowText = defaults.contextWindowTokens !== null
      ? `${defaults.contextWindowTokens}` : '';
  }

  private refreshColors(): void { this.colors = new ModelParameterColors(); }
  private updateToolProtocol(provider: ProviderSetting): void {
    this.toolProviderName = provider.name;
    this.toolProtocol = provider.type === 'google' ? 'google'
      : provider.type === 'openai' && (provider as ProviderSettingOpenAIVariant).useResponseApi ? 'responses' : 'other';
  }

  // ===== Headers(:327-394) =====

  // User-Agent 在 customHeaders 中的下标(大小写不敏感),-1 = 未设置
  private uaIndex(): number {
    for (let i = 0; i < this.headerNames.length; i++) {
      if (this.headerNames[i].trim().toLowerCase() === 'user-agent') return i;
    }
    return -1;
  }

  private uaCurrent(): string {
    const i: number = this.uaIndex();
    return i >= 0 ? (this.headerValues[i] ?? '') : '';
  }

  // UA 预设 → 写入 User-Agent 头(已存在则覆盖值;草稿语义,确定后落库)
  private applyUaPreset(value: string): void {
    const i: number = this.uaIndex();
    if (i >= 0) {
      const names: string[] = [...this.headerNames];
      const values: string[] = [...this.headerValues];
      names[i] = 'User-Agent';
      values[i] = value;
      this.headerNames = names;
      this.headerValues = values;
    } else {
      this.headerNames = [...this.headerNames, 'User-Agent'];
      this.headerValues = [...this.headerValues, value];
    }
  }

  private clearUa(): void {
    const i: number = this.uaIndex();
    if (i >= 0) this.removeHeader(i);
  }

  private setHeaderName(i: number, v: string): void {
    const next: string[] = [...this.headerNames];
    next[i] = v;
    this.headerNames = next;
  }

  private setHeaderValue(i: number, v: string): void {
    const next: string[] = [...this.headerValues];
    next[i] = v;
    this.headerValues = next;
  }

  private removeHeader(i: number): void {
    this.headerNames = this.headerNames.filter((_v: string, idx: number): boolean => idx !== i);
    this.headerValues = this.headerValues.filter((_v: string, idx: number): boolean => idx !== i);
  }

  // :387-391 — add(CustomHeader("",""))
  private addHeader(): void {
    this.headerNames = [...this.headerNames, ''];
    this.headerValues = [...this.headerValues, ''];
  }

  // ===== Bodies(:411-498) =====

  private setBodyKey(i: number, v: string): void {
    const next: string[] = [...this.bodyKeys];
    next[i] = v;
    this.bodyKeys = next;
  }

  // :458-472 — 逐键解析:合法 → 更新草稿值 + 清错;非法 → 行内错(保留旧值)
  private setBodyText(i: number, v: string): void {
    const texts: string[] = [...this.bodyTexts];
    texts[i] = v;
    this.bodyTexts = texts;
    const errors: string[] = [...this.bodyErrors];
    try {
      const parsed: JsonValue = JSON.parse(v) as JsonValue;
      this.bodyValues[i] = parsed;
      errors[i] = '';
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      errors[i] = `无效的 JSON: ${msg.substring(0, 100)}`;
    }
    this.bodyErrors = errors;
  }

  private removeBody(i: number): void {
    this.bodyKeys = this.bodyKeys.filter((_v: string, idx: number): boolean => idx !== i);
    this.bodyTexts = this.bodyTexts.filter((_v: string, idx: number): boolean => idx !== i);
    this.bodyErrors = this.bodyErrors.filter((_v: string, idx: number): boolean => idx !== i);
    this.bodyValues = this.bodyValues.filter((_v: JsonValue, idx: number): boolean => idx !== i);
  }

  // :489-496 — add(CustomBody("", JsonPrimitive(""))) → 文本 '""'
  private addBody(): void {
    this.bodyKeys = [...this.bodyKeys, ''];
    this.bodyTexts = [...this.bodyTexts, '""'];
    this.bodyErrors = [...this.bodyErrors, ''];
    this.bodyValues = [...this.bodyValues, ''];
  }

  // ===== 确认/取消(:706-720) =====

  private draftInputModalities(): ModalityFull[] {
    const out: ModalityFull[] = [];
    if (this.inputText) out.push('text');
    if (this.inputImage) out.push('image');
    if (this.inputAudio) out.push('audio');
    // 至少保留 text(空模态无意义;Android 表单同样不允许全空)
    return out.length > 0 ? out : ['text'];
  }

  private draftContextWindow(): number | null {
    const raw: string = this.contextWindowText.trim();
    if (raw.length === 0) return null;
    const n: number = Number(raw);
    if (!Number.isSafeInteger(n) || n <= 0) return null;
    return Math.trunc(n);
  }

  private modelTypeIndex(): number {
    const idx: number = MODEL_TYPE_VALUES.indexOf(this.modelType);
    return idx >= 0 ? idx : 0;
  }

  private async confirm(): Promise<void> {
    if (this.provider === null || this.saving) return;
    this.error = '';
    if (this.bodyErrors.some((error: string): boolean => error.length > 0)) {
      this.error = '请先修正自定义 Body 中无效的 JSON。';
      this.advancedExpanded = true;
      return;
    }
    const contextWindow: number | null = this.draftContextWindow();
    if (this.contextWindowText.trim().length > 0 && contextWindow === null) {
      this.error = '上下文窗口请填写大于 0 的整数，或留空。';
      return;
    }
    const providerId: string = this.provider.id;
    const headers: CustomHeader[] = this.headerNames.map(
      (n: string, i: number): CustomHeader => ({ name: n.trim(), value: (this.headerValues[i] ?? '').trim() }));
    const bodies: CustomBody[] = this.bodyKeys.map(
      (k: string, i: number): CustomBody => ({ key: k.trim(), value: this.bodyValues[i] ?? '' }));
    const tools: BuiltInTools[] = [];
    if (this.toolSearch) tools.push('search');
    if (this.toolUrlContext) tools.push('url_context');
    if (this.toolImageGen) tools.push('image_generation');
    this.saving = true;
    try {
      const all: ProviderSetting[] = (await loadProviders(getChatKvStore())) ?? [];
      const provider: ProviderSetting | undefined = all.find((p: ProviderSetting): boolean => p.id === providerId);
      const model: ProviderModel | undefined = provider?.models.find((m: ProviderModel): boolean => m.id === this.modelRowId);
      if (provider === undefined || model === undefined) throw new Error('该模型已被删除，请返回刷新。');
      // 只更新当前模型的草稿字段，保留刚读取的服务商与其他模型。
      const updated: ProviderModel = JSON.parse(JSON.stringify(model)) as ProviderModel;
      updated.type = this.modelType as ModelType;
      updated.customHeaders = headers;
      updated.customBodies = bodies;
      updated.inputModalities = this.draftInputModalities();
      updated.contextWindowTokens = contextWindow;
      updated.tools = tools;
      updated.abilities = model.abilities.filter(
        (ability: ModelAbility): boolean => ability !== 'tool' && ability !== 'reasoning');
      if (this.toolCallingEnabled) updated.abilities.push('tool');
      if (this.reasoningEnabled) updated.abilities.push('reasoning');
      const models: ProviderModel[] = provider.models.map((m: ProviderModel): ProviderModel => m.id === this.modelRowId ? updated : m);
      const next: ProviderSetting[] = all.map((p: ProviderSetting): ProviderSetting => p.id === providerId ? this.rebuildProvider(p, models) : p);
      await saveProviders(getChatKvStore(), next);
      router.back();
    } catch (e) {
      this.error = `保存失败：${e instanceof Error ? e.message : String(e)}`;
    } finally {
      this.saving = false;
    }
  }

  // provider 为 sealed 三变体 — 经 JSON 序列化 round-trip 替换 models
  // (线格式无损,provider_settings_serialize D-017 保证)
  private rebuildProvider(p: ProviderSetting, models: ProviderModel[]): ProviderSetting {
    const clone: ProviderSetting = JSON.parse(JSON.stringify(p)) as ProviderSetting;
    clone.models = models;
    return clone;
  }

  // ProviderOverrideSettings(:251-257)对齐 — 移除覆写(置 null 即时落库)
  private async removeOverwrite(): Promise<void> {
    if (this.provider === null || this.saving) return;
    const providerId: string = this.provider.id;
    this.saving = true;
    this.error = '';
    try {
      const all: ProviderSetting[] = (await loadProviders(getChatKvStore())) ?? [];
      const provider: ProviderSetting | undefined = all.find((p: ProviderSetting): boolean => p.id === providerId);
      const model: ProviderModel | undefined = provider?.models.find((m: ProviderModel): boolean => m.id === this.modelRowId);
      if (provider === undefined || model === undefined) throw new Error('该模型已被删除，请返回刷新。');
      const updated: ProviderModel = JSON.parse(JSON.stringify(model)) as ProviderModel;
      updated.providerOverwrite = null;
      const models: ProviderModel[] = provider.models.map((m: ProviderModel): ProviderModel => m.id === this.modelRowId ? updated : m);
      const next: ProviderSetting[] = all.map((p: ProviderSetting): ProviderSetting => p.id === providerId ? this.rebuildProvider(p, models) : p);
      await saveProviders(getChatKvStore(), next);
      this.provider = this.rebuildProvider(provider, models);
      this.hasOverwrite = false;
      this.overwriteName = '';
      this.updateToolProtocol(provider);
    } catch (e) {
      this.error = `移除失败：${e instanceof Error ? e.message : String(e)}`;
    } finally {
      this.saving = false;
    }
  }

  @Builder
  private builtInToolRow(tool: BuiltInTools, last: boolean): void {
    Row() {
      Text(tool === 'search' ? '联网搜索' : tool === 'url_context' ? '网址上下文（Google）' : '生成图片')
        .fontSize(TEXT_STYLES.body.size).fontWeight(600).fontColor(this.colors.ink).layoutWeight(1)
      Row() {
        Toggle({ type: ToggleType.Switch, isOn: tool === 'search' ? this.toolSearch : tool === 'url_context' ? this.toolUrlContext : this.toolImageGen })
          .width(44).height(44).selectedColor(this.accentColor).onChange((value: boolean): void => {
            if (tool === 'search') this.toolSearch = value;
            else if (tool === 'url_context') this.toolUrlContext = value;
            else this.toolImageGen = value;
          })
      }.constraintSize({ minWidth: 44, minHeight: 44 }).justifyContent(FlexAlign.End).alignItems(VerticalAlign.Center).margin({ left: 12 })
    }.width('100%').alignItems(VerticalAlign.Center).constraintSize({ minHeight: 64 }).padding({ top: 12, bottom: 12 })
    if (!last) Row().width('100%').height(1).backgroundColor(this.colors.line)
  }

  @Builder
  private builtInTools(): void {
    Column({ space: 8 }) {
      Text(`实际请求服务商：${this.toolProviderName}`).fontSize(TEXT_STYLES.secondary.size)
        .fontColor(this.colors.muted).width('100%').wordBreak(WordBreak.BREAK_ALL)
      if (this.toolProtocol === 'google') {
        Text('Google 联网搜索与网址上下文由服务商执行，模型是否支持以实际返回为准。')
          .fontSize(TEXT_STYLES.secondary.size).fontColor(this.colors.muted).width('100%')
        if (this.toolSearch || this.toolUrlContext) {
          Text('当前为 Google 内置工具模式，不注册本地工具或 MCP 函数。需要这些函数工具时，请关闭联网搜索和网址上下文。')
            .fontSize(TEXT_STYLES.secondary.size).fontColor(this.colors.ink).width('100%')
        } else {
          Text('未启用 Google 搜索或网址上下文，保留原有函数工具模式；本地和 MCP 工具是否可用仍由助手配置决定。')
            .fontSize(TEXT_STYLES.secondary.size).fontColor(this.colors.muted).width('100%')
        }
        if (this.toolImageGen) Text('生成图片选项会保留，但此处不映射为 Google 内置工具，也不会单独改变函数工具模式。')
          .fontSize(TEXT_STYLES.secondary.size).fontColor(this.colors.muted).width('100%')
      } else if (this.toolProtocol === 'responses') {
        Text('OpenAI Responses 使用现有联网搜索与生成图片映射；网址上下文仅适用于 Google，在当前协议下不发送，该选项仍会保存。')
          .fontSize(TEXT_STYLES.secondary.size).fontColor(this.colors.muted).width('100%')
      } else {
        Text('当前服务商协议不映射这些内置工具。已选项仍会保存，但不会因此变成可用工具；网址上下文仅适用于 Google。')
          .fontSize(TEXT_STYLES.secondary.size).fontColor(this.colors.muted).width('100%')
      }
      this.builtInToolRow('search', false)
      this.builtInToolRow('url_context', false)
      this.builtInToolRow('image_generation', true)
      Text('服务商错误会如实显示。自定义 Body 沿原规则最后覆盖请求字段，包括 tools；请确认它与所选模式一致。')
        .fontSize(TEXT_STYLES.secondary.size).fontColor(this.colors.muted).width('100%')
    }.width('100%').alignItems(HorizontalAlign.Start).padding({ left: 16, right: 16, top: 12, bottom: 4 })
  }

  @Builder
  textField(label: string, value: string, placeholder: string, onChange: (v: string) => void) {
    Column() {
      Text(label).fontSize(TEXT_STYLES.secondary.size).fontWeight(FontWeight.Medium)
        .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
        .fontColor(this.colors.muted).margin({ bottom: 4 })
      TextInput({ text: value, placeholder: placeholder })
        .fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
        .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(this.colors.ink).placeholderColor(this.colors.muted).height(44)
        .backgroundColor(this.colors.raised).borderRadius(8).border({ width: 1, color: this.colors.line })
        .onChange(onChange)
    }
    .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 10 })
  }

  private advancedSummary(): string {
    const parts: string[] = [];
    if (this.hasOverwrite) parts.push(`服务商覆写：${this.overwriteName}`);
    if (this.modelType !== 'chat') parts.push(this.modelType === 'image' ? '生图模型' : '嵌入模型');
    if (this.inputImage || this.inputAudio) parts.push('扩展输入模态');
    if (this.toolSearch || this.toolUrlContext || this.toolImageGen) parts.push('已设内置工具');
    if (this.headerNames.length > 0) parts.push(`${this.headerNames.length} 项 Header`);
    if (this.bodyKeys.length > 0) parts.push(`${this.bodyKeys.length} 项 Body`);
    return parts.length > 0 ? parts.join(' · ') : '类型、输入模态与自定义请求';
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      PageTitleBar({ title: `模型参数 · ${this.modelLabel}` })

      if (!this.loaded) {
        Text('加载中…').fontSize(13).fontColor(this.colors.muted).margin({ top: 32 })
      } else if (this.provider === null) {
        Text('模型不存在').fontSize(13).fontColor(this.colors.muted).margin({ top: 32 })
      } else {
        if (this.error.length > 0) { Text(this.error).fontSize(13).fontColor(this.colors.error).width('100%').padding(16) }
        Scroll() {
          Column() {
            // ── 提供商覆写(SettingProviderModelSettingsForm.kt:734-821 对齐) ──
            SectionHeader({ label: '提供商覆写' })
            if (this.hasOverwrite) {
              Column() {
                Row() {
                  Column() {
                    Text(this.overwriteName)
                      .fontSize(TEXT_STYLES.body.size).fontWeight(600)
                      .lineHeight(TEXT_STYLES.body.lineHeight).fontColor(this.colors.ink)
                      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                    Text('override')
                      .fontSize(TEXT_STYLES.tinyTag.size)
                      .lineHeight(TEXT_STYLES.tinyTag.lineHeight).fontFamily(FONT_MONO)
                      .fontColor(this.colors.muted).margin({ top: 2 })
                  }
                  .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ right: 12 })
                  BtnGhost({ label: '移除', onTap: (): void => { this.removeOverwrite(); } })
                }
                .width('100%').alignItems(VerticalAlign.Center)
                .padding({ left: 16, right: 16, top: 12, bottom: 12 })
                PaperSeparator()
              }
              .width('100%')
            }
            Text('该模型使用独立的提供商参数;新增与编辑覆写随提供商编辑器扩展提供。')
              .fontSize(TEXT_STYLES.secondary.size)
              .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
              .fontColor(this.colors.muted).width('100%')
              .padding({ left: 16, right: 16, top: this.hasOverwrite ? 8 : 12, bottom: 8 })

            // ── 模型属性(类型/输入模态/上下文窗口;Android 单模型表单对齐) ──
            Row() {
              SectionHeader({ label: '模型属性' })
              Blank()
              if (this.hasRegistryDefaults) {
                Text('恢复推荐默认')
                  .fontSize(TEXT_STYLES.meta.size)
                  .fontWeight(FontWeight.Medium)
                  .fontColor(this.accentColor)
                  .padding({ left: 10, right: 10, top: 6, bottom: 6 })
                  .margin({ right: 16, top: 10 })
                  .backgroundColor(this.colors.raised)
                  .borderRadius(8)
                  .border({ width: 0.75, color: this.colors.line })
                  .onClick((): void => { this.applyRecommendedDefaults(); })
              }
            }
            .width('100%')
            .alignItems(VerticalAlign.Bottom)
            Column() {
              Text('类型')
                .fontSize(TEXT_STYLES.secondary.size).fontWeight(FontWeight.Medium)
                .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
                .fontColor(this.colors.muted).margin({ bottom: 4 })
              Seg({
                options: MODEL_TYPE_OPTIONS,
                selected: this.modelTypeIndex(),
                onSelect: (idx: number): void => { this.modelType = MODEL_TYPE_VALUES[idx]; },
              })
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding({ left: 16, right: 16, top: 12, bottom: 4 })
            if (this.modelType === 'chat') {
              ToggleRow({
                label: '工具调用', checked: this.toolCallingEnabled, isLast: false,
                onToggle: (): void => { this.toolCallingEnabled = !this.toolCallingEnabled; },
              })
              ToggleRow({
                label: '深度思考 / 推理', checked: this.reasoningEnabled, isLast: true,
                onToggle: (): void => { this.reasoningEnabled = !this.reasoningEnabled; },
              })
              Text('默认按模型规格自动识别，模型不支持工具或思考时可手动关闭。')
                .fontSize(TEXT_STYLES.secondary.size).fontColor(this.colors.muted)
                .width('100%').padding({ left: 16, right: 16, bottom: 8 })
            }
            Text('输入模态')
              .fontSize(TEXT_STYLES.secondary.size).fontWeight(FontWeight.Medium)
              .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
              .fontColor(this.colors.muted).width('100%').padding({ left: 16, right: 16 }).margin({ top: 12, bottom: 4 })
            ToggleRow({
              label: '文本', checked: this.inputText, isLast: false,
              onToggle: (): void => { this.inputText = !this.inputText; },
            })
            ToggleRow({
              label: '图片', checked: this.inputImage, isLast: false,
              onToggle: (): void => { this.inputImage = !this.inputImage; },
            })
            ToggleRow({
              label: '音频', checked: this.inputAudio, isLast: true,
              onToggle: (): void => { this.inputAudio = !this.inputAudio; },
            })
            Column() {
              this.textField('上下文窗口(tokens)', this.contextWindowText, '留空 = 未知',
                (v: string): void => { this.contextWindowText = v; })
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding({ left: 16, right: 16, top: 12, bottom: 4 })

            // ── 内置工具：按实际 effective 提供商展示模式，保存所有草稿选项 ──
            SectionHeader({ label: '内置工具' })
            this.builtInTools()

            // ── 请求伪装(UA 预设;iOS/Android 同源,写入下方自定义 Headers) ──
            SectionHeader({ label: '请求伪装' })
            Column() {
              Text('User-Agent 预设(草稿语义,确定后生效)')
                .fontSize(TEXT_STYLES.secondary.size)
                .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
                .fontColor(this.colors.muted).width('100%')
              Flex({ wrap: FlexWrap.Wrap }) {
                ForEach(UA_PRESETS, (p: string[]): void => {
                  Text(p[0])
                    .fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
                    .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
                    .fontColor(this.uaCurrent() === p[1] ? this.accentColor : this.colors.muted)
                    .constraintSize({ minHeight: 44 }).padding({ left: 10, right: 10, top: 6, bottom: 6 })
                    .backgroundColor(this.colors.raised)
                    .border({ width: 1, color: this.uaCurrent() === p[1] ? this.accentColor : this.colors.line, radius: 8 })
                    .margin({ right: 8, top: 8 })
                    .onClick((): void => { this.applyUaPreset(p[1]); })
                }, (p: string[]): string => p[0])
                if (this.uaIndex() >= 0) {
                  Text('清除')
                    .fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
                    .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
                    .fontColor(this.colors.error)
                    .constraintSize({ minHeight: 44 }).padding({ left: 10, right: 10, top: 6, bottom: 6 })
                    .backgroundColor(this.colors.raised)
                    .border({ width: 1, color: this.colors.line, radius: 8 })
                    .margin({ right: 8, top: 8 })
                    .onClick((): void => { this.clearUa(); })
                }
              }
              .width('100%')
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding({ left: 16, right: 16, top: 12, bottom: 4 })

            // ── 自定义 Headers(:315-395) ──
            SectionHeader({ label: `自定义 Headers · ${this.headerNames.length}` })
            ForEach(this.headerNames, (_n: string, i: number) => {
              Column() {
                Row() {
                  Text(`HEADER ${i + 1}`).fontSize(TEXT_STYLES.meta.size).fontWeight(600)
                    .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
                    .fontColor(this.colors.muted)
                  Blank()
                  BtnGhost({ label: '删除', onTap: (): void => { this.removeHeader(i); } })
                }
                .width('100%').alignItems(VerticalAlign.Center).margin({ bottom: 8 })
                this.textField('Header 名称', this.headerNames[i] ?? '', 'Header 名称',
                  (v: string): void => { this.setHeaderName(i, v); })
                this.textField('Header 值', this.headerValues[i] ?? '', 'Header 值',
                  (v: string): void => { this.setHeaderValue(i, v); })
              }
              .width('100%').alignItems(HorizontalAlign.Start)
              .padding({ left: 16, right: 16, top: 12, bottom: 12 })
              if (i < this.headerNames.length - 1) {
                PaperSeparator()
              }
            }, (_n: string, i: number): string => `h-${i}`)
            Row() {
              BtnGhost({ label: '添加 Header', onTap: (): void => { this.addHeader(); } })
            }
            .padding({ left: 16, right: 16, top: 8, bottom: 8 })

            // ── 自定义 Body(:397-499) ──
            SectionHeader({ label: `自定义 Body · ${this.bodyKeys.length}` })
            ForEach(this.bodyKeys, (_k: string, i: number) => {
              Column() {
                Row() {
                  Text(`BODY ${i + 1}`).fontSize(TEXT_STYLES.meta.size).fontWeight(600)
                    .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
                    .fontColor(this.colors.muted)
                  Blank()
                  BtnGhost({ label: '删除', onTap: (): void => { this.removeBody(i); } })
                }
                .width('100%').alignItems(VerticalAlign.Center).margin({ bottom: 8 })
                this.textField('Body Key', this.bodyKeys[i] ?? '', 'Body Key',
                  (v: string): void => { this.setBodyKey(i, v); })
                Column() {
                  Text('Body 值 (JSON)').fontSize(TEXT_STYLES.secondary.size).fontWeight(FontWeight.Medium)
                    .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
                    .fontColor(this.colors.muted).margin({ bottom: 4 })
                  TextArea({ text: this.bodyTexts[i] ?? '', placeholder: 'Body 值 (JSON)' })
                    .fontSize(TEXT_STYLES.meta.size).lineHeight(TEXT_STYLES.meta.lineHeight)
                    .fontFamily(FONT_MONO).fontColor(this.colors.ink).placeholderColor(this.colors.muted).backgroundColor(this.colors.raised)
                    .borderRadius(8).border({ width: 1, color: this.colors.line }).height(98)
                    .onChange((v: string): void => { this.setBodyText(i, v); })
                  if ((this.bodyErrors[i] ?? '').length > 0) {
                    Text(this.bodyErrors[i]).fontSize(TEXT_STYLES.meta.size)
                      .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO)
                      .fontColor(this.colors.error).width('100%').margin({ top: 6 })
                  }
                }
                .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 10 })
              }
              .width('100%').alignItems(HorizontalAlign.Start)
              .padding({ left: 16, right: 16, top: 12, bottom: 12 })
              if (i < this.bodyKeys.length - 1) {
                PaperSeparator()
              }
            }, (_k: string, i: number): string => `b-${i}`)
            Row() {
              BtnGhost({ label: '添加 Body', onTap: (): void => { this.addBody(); } })
            }
            .padding({ left: 16, right: 16, top: 8, bottom: 24 })
          }
          .width('100%').padding({ bottom: 16 }).justifyContent(FlexAlign.Start)
        }
        .align(Alignment.TopStart)
        .layoutWeight(1).enabled(!this.saving)

        Row() {
          Blank()
          BtnGhost({ label: '取消', onTap: (): void => { if (!this.saving) router.back(); } })
          Row() {
            BtnFill({ label: this.saving ? '保存中…' : '确定', onTap: (): void => { this.confirm(); } })
          }
          .margin({ left: 8 })
        }
        .width('100%').padding({ left: 16, right: 16, top: 8, bottom: 16 + this.navBarHeight })
      }
    
      }, (e: number): string => `theme-${e}`)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(this.colors.background)
  }
}
