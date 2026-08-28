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
  BG, ERROR, INK, INK3, LINE, RAISED, TEXT_STYLES,
} from '../design/tokens.ets';
import { PageTitleBar, SectionHeader, PaperSeparator, BtnFill, BtnGhost } from '../components/PageTemplate.ets';
import { FONT_MONO, FONT_MONO_MEDIUM } from '../design/fonts.ets';
import { router } from '@kit.ArkUI';
import type {
  CustomBody, CustomHeader, JsonValue, ProviderModel, ProviderSetting,
} from '@amber/chat-domain';
import { loadProviders, saveProviders } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';


interface RouterParams {
  providerId?: string;
  modelId?: string;
}

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
      }
      this.loaded = true;
    });
  }

  // ===== Headers(:327-394) =====

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
    const models: ProviderModel[] = provider.models.map((m: ProviderModel): ProviderModel => {
      if (m.id !== this.modelRowId) return m;
      // arkts-no-spread:显式字段拷贝
      return {
        modelId: m.modelId, displayName: m.displayName, id: m.id, type: m.type,
        customHeaders: headers, customBodies: bodies,
        inputModalities: m.inputModalities, outputModalities: m.outputModalities,
        abilities: m.abilities, tools: m.tools,
        contextWindowTokens: m.contextWindowTokens, providerOverwrite: m.providerOverwrite,
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
