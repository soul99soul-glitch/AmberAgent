// ChatModelParamsPage — 模型级自定义参数编辑面(D-109)
// Android 锚点:
//   SettingProviderModelSettingsForm.kt:259-271(高级设置 tab 的
//     ModelCustomHeaders/ModelCustomBodies 两节;ProviderOverrideSettings
//     = overwrite 编辑面,单独 P1 未移植)
//   :315-395(ModelCustomHeaders:节标 '自定义 Headers' + count;
//     行 'HEADER N' + 删除;name/value 字段,存储 = trim)
//   :397-499(ModelCustomBodies:'BODY N';key trim;value = JSON 文本,
//     逐键解析(:458-472)— 合法 → 更新草稿 + 清错;非法 → 行内
//     '无效的 JSON: <msg.take(100)>' 并保留旧值;添加 = CustomBody("",""))
//   SettingProviderModelTabPage.kt:694-721(草稿语义:编辑中改 dialogState,
//     确认才落库,取消丢弃;displayName 非空使能 — 本页不改 displayName,N/A)
//   values-zh/strings.xml:41-42,49-50,69-70,79-80,82
// 偏差登记:
//   - Android 逐键 trim 入草稿(显示保留原始按键)→ 确认时统一 trim,
//     持久化结果等价;显示态 raw 保留
//   - 高级设置 tab 的 ProviderOverwriteSettings(:251-257)/内置工具 tab
//     (:275-283)未移植(P1 已登),本页仅 customHeaders/customBodies 两节
import {
  ACCENT, BG, ERROR, INK, INK3, LINE, RAISED, TEXT_STYLES,
} from '../design/tokens.ets';
import { PageTitleBar, SectionHeader, PaperSeparator, BtnFill, BtnGhost, Seg, ToggleRow } from '../components/PageTemplate.ets';
import { FONT_MONO, FONT_MONO_MEDIUM } from '../design/fonts.ets';
import { router } from '@kit.ArkUI';
import type {
  CustomBody, CustomHeader, JsonValue, ProviderModel, ProviderSetting,
  ModelType, ModalityFull,
} from '@amber/chat-domain';
import { loadProviders, saveProviders } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';


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

@Entry
@Component
struct ChatModelParamsPage {
  @State loaded: boolean = false;
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
  private hasOverwrite: boolean = false;
  // 模型属性(类型/输入模态/上下文窗口;Android 单模型表单对齐)
  @State modelType: string = 'chat';
  @State inputText: boolean = true;
  @State inputImage: boolean = false;
  @State inputAudio: boolean = false;
  @State contextWindowText: string = '';

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
        this.modelType = model.type;
        this.inputText = model.inputModalities.indexOf('text') >= 0;
        this.inputImage = model.inputModalities.indexOf('image') >= 0;
        this.inputAudio = model.inputModalities.indexOf('audio') >= 0;
        this.contextWindowText = model.contextWindowTokens !== null
          ? `${model.contextWindowTokens}` : '';
      }
      this.loaded = true;
    });
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
    if (!Number.isFinite(n) || n <= 0) return null;
    return Math.trunc(n);
  }

  private modelTypeIndex(): number {
    const idx: number = MODEL_TYPE_VALUES.indexOf(this.modelType);
    return idx >= 0 ? idx : 0;
  }

  private confirm(): void {
    if (this.provider === null) return;
    const provider: ProviderSetting = this.provider;
    const headers: CustomHeader[] = this.headerNames.map(
      (n: string, i: number): CustomHeader => ({
        name: n.trim(), value: (this.headerValues[i] ?? '').trim(),
      }));
    const bodies: CustomBody[] = this.bodyKeys.map(
      (k: string, i: number): CustomBody => ({
        key: k.trim(), value: this.bodyValues[i] ?? '',
      }));
    const inputModalities: ModalityFull[] = this.draftInputModalities();
    const contextWindow: number | null = this.draftContextWindow();
    const modelType: string = this.modelType;
    const models: ProviderModel[] = provider.models.map((m: ProviderModel): ProviderModel => {
      if (m.id !== this.modelRowId) return m;
      // arkts-no-spread:显式字段拷贝
      return {
        modelId: m.modelId, displayName: m.displayName, id: m.id,
        type: modelType as ModelType,
        customHeaders: headers, customBodies: bodies,
        inputModalities: inputModalities, outputModalities: m.outputModalities,
        abilities: m.abilities, tools: m.tools,
        contextWindowTokens: contextWindow, providerOverwrite: m.providerOverwrite,
      };
    });
    loadProviders(getChatKvStore()).then((list: ProviderSetting[] | null): void => {
      const all: ProviderSetting[] = (list ?? []).map((p: ProviderSetting): ProviderSetting => {
        if (p.id !== provider.id) return p;
        return this.rebuildProvider(p, models);
      });
      saveProviders(getChatKvStore(), all).then((): void => {
        router.back();
      });
    });
  }

  // provider 为 sealed 三变体 — 经 JSON 序列化 round-trip 替换 models
  // (线格式无损,provider_settings_serialize D-017 保证)
  private rebuildProvider(p: ProviderSetting, models: ProviderModel[]): ProviderSetting {
    const clone: ProviderSetting = JSON.parse(JSON.stringify(p)) as ProviderSetting;
    clone.models = models;
    return clone;
  }

  // ProviderOverrideSettings(:251-257)对齐 — 移除覆写(置 null 即时落库)
  private removeOverwrite(): void {
    if (this.provider === null) return;
    const provider: ProviderSetting = this.provider;
    const models: ProviderModel[] = provider.models.map((m: ProviderModel): ProviderModel => {
      if (m.id !== this.modelRowId) return m;
      return {
        modelId: m.modelId, displayName: m.displayName, id: m.id, type: m.type,
        customHeaders: m.customHeaders, customBodies: m.customBodies,
        inputModalities: m.inputModalities, outputModalities: m.outputModalities,
        abilities: m.abilities, tools: m.tools,
        contextWindowTokens: m.contextWindowTokens, providerOverwrite: null,
      };
    });
    loadProviders(getChatKvStore()).then((list: ProviderSetting[] | null): void => {
      const all: ProviderSetting[] = (list ?? []).map((p: ProviderSetting): ProviderSetting => {
        if (p.id !== provider.id) return p;
        return this.rebuildProvider(p, models);
      });
      saveProviders(getChatKvStore(), all).then((): void => {
        this.hasOverwrite = false;
        this.overwriteName = '';
      });
    });
  }

  @Builder
  textField(label: string, value: string, placeholder: string, onChange: (v: string) => void) {
    Column() {
      Text(label).fontSize(TEXT_STYLES.secondary.size).fontWeight(FontWeight.Medium)
        .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
        .fontColor(INK3).margin({ bottom: 4 })
      TextInput({ text: value, placeholder: placeholder })
        .fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
        .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(INK).height(40)
        .backgroundColor(RAISED).borderRadius(8).border({ width: 1, color: LINE })
        .onChange(onChange)
    }
    .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 10 })
  }

  build() {
    Column() {
      PageTitleBar({ title: `模型参数 · ${this.modelLabel}` })

      if (!this.loaded) {
        Text('加载中…').fontSize(13).fontColor(INK3).margin({ top: 32 })
      } else if (this.provider === null) {
        Text('模型不存在').fontSize(13).fontColor(INK3).margin({ top: 32 })
      } else {
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
                      .lineHeight(TEXT_STYLES.body.lineHeight).fontColor(INK)
                      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                    Text('override')
                      .fontSize(TEXT_STYLES.tinyTag.size)
                      .lineHeight(TEXT_STYLES.tinyTag.lineHeight).fontFamily(FONT_MONO)
                      .fontColor(INK3).margin({ top: 2 })
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
              .fontColor(INK3).width('100%')
              .padding({ left: 16, right: 16, top: this.hasOverwrite ? 8 : 12, bottom: 8 })

            // ── 模型属性(类型/输入模态/上下文窗口;Android 单模型表单对齐) ──
            SectionHeader({ label: '模型属性' })
            Column() {
              Text('类型')
                .fontSize(TEXT_STYLES.secondary.size).fontWeight(FontWeight.Medium)
                .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
                .fontColor(INK3).margin({ bottom: 4 })
              Seg({
                options: MODEL_TYPE_OPTIONS,
                selected: this.modelTypeIndex(),
                onSelect: (idx: number): void => { this.modelType = MODEL_TYPE_VALUES[idx]; },
              })
              Text('输入模态')
                .fontSize(TEXT_STYLES.secondary.size).fontWeight(FontWeight.Medium)
                .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
                .fontColor(INK3).margin({ top: 12, bottom: 4 })
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
              Column().height(12)
              this.textField('上下文窗口(tokens)', this.contextWindowText, '留空 = 未知',
                (v: string): void => { this.contextWindowText = v; })
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding({ left: 16, right: 16, top: 12, bottom: 4 })

            // ── 请求伪装(UA 预设;iOS/Android 同源,写入下方自定义 Headers) ──
            SectionHeader({ label: '请求伪装' })
            Column() {
              Text('User-Agent 预设(草稿语义,确定后生效)')
                .fontSize(TEXT_STYLES.secondary.size)
                .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
                .fontColor(INK3).width('100%')
              Flex({ wrap: FlexWrap.Wrap }) {
                ForEach(UA_PRESETS, (p: string[]): void => {
                  Text(p[0])
                    .fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
                    .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
                    .fontColor(this.uaCurrent() === p[1] ? ACCENT : INK3)
                    .padding({ left: 10, right: 10, top: 6, bottom: 6 })
                    .backgroundColor(RAISED)
                    .border({ width: 1, color: this.uaCurrent() === p[1] ? ACCENT : LINE, radius: 8 })
                    .margin({ right: 8, top: 8 })
                    .onClick((): void => { this.applyUaPreset(p[1]); })
                }, (p: string[]): string => p[0])
                if (this.uaIndex() >= 0) {
                  Text('清除')
                    .fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
                    .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
                    .fontColor(ERROR)
                    .padding({ left: 10, right: 10, top: 6, bottom: 6 })
                    .backgroundColor(RAISED)
                    .border({ width: 1, color: LINE, radius: 8 })
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
                    .fontColor(INK3)
                  Blank()
                  BtnGhost({ label: '删除', onTap: (): void => { this.removeHeader(i); } })
                }
                .width('100%').margin({ bottom: 8 })
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
                    .fontColor(INK3)
                  Blank()
                  BtnGhost({ label: '删除', onTap: (): void => { this.removeBody(i); } })
                }
                .width('100%').margin({ bottom: 8 })
                this.textField('Body Key', this.bodyKeys[i] ?? '', 'Body Key',
                  (v: string): void => { this.setBodyKey(i, v); })
                Column() {
                  Text('Body 值 (JSON)').fontSize(TEXT_STYLES.secondary.size).fontWeight(FontWeight.Medium)
                    .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
                    .fontColor(INK3).margin({ bottom: 4 })
                  TextArea({ text: this.bodyTexts[i] ?? '', placeholder: 'Body 值 (JSON)' })
                    .fontSize(TEXT_STYLES.meta.size).lineHeight(TEXT_STYLES.meta.lineHeight)
                    .fontFamily(FONT_MONO).fontColor(INK).backgroundColor(RAISED)
                    .borderRadius(8).border({ width: 1, color: LINE }).height(98)
                    .onChange((v: string): void => { this.setBodyText(i, v); })
                  if ((this.bodyErrors[i] ?? '').length > 0) {
                    Text(this.bodyErrors[i]).fontSize(TEXT_STYLES.meta.size)
                      .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO)
                      .fontColor(ERROR).width('100%').margin({ top: 6 })
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
          .width('100%').padding({ left: 16, right: 16, bottom: 32 })
        }
        .align(Alignment.TopStart)
        .layoutWeight(1)

        Row() {
          Blank()
          BtnGhost({ label: '取消', onTap: (): void => { router.back(); } })
          Row() {
            BtnFill({ label: '确定', onTap: (): void => { this.confirm(); } })
          }
          .margin({ left: 8 })
        }
        .width('100%').padding({ left: 16, right: 16, top: 8, bottom: 16 })
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
