import { getProductKind } from '../../platform_impl/ProductIdentity.ets';
// SettingDeepReadPage — 深度阅读设置(AI 配置 + 热榜源开关 + 搜索源配置面板)
//   原误置于 HistoryPage,归位独立页(首页功能区/设置入口可达)。
//
// 2026-09-27 二级页 iOS 对齐(无截图,沿用规范):仅换视觉骨架
//   CenteredTitleBar / PlainSection / CardGroup(r18+描边)+ h16 /
//   表单控件装入卡片(标题 16 medium sans、字段标 mono meta、值 12 muted)/
//   FooterNote 组下脚注,逻辑零改动(读写/测试连通/保存/模型库增删全原样)。
import { ACCENT, BG, INK, INK3, INK4, SURFACE2, ERROR, SIGNAL, LINE, TEXT_STYLES, GUTTER, MOTION_OVERLAY, accentSoftBg, lineHeightForSize } from '../../design/tokens.ets';
import { FONT_SANS_MEDIUM, FONT_MONO_MEDIUM } from '../../design/fonts.ets';
import { CenteredTitleBar, PlainSection, CardGroup, CardRow, CardToggleRow, FooterNote, BtnFill, BtnGhost, PageSpacer } from '../../components/PageTemplate.ets';
import { PillSelect } from '../../components/ui/Select.ets';
import type { DeepReadTemplateDef } from '../../platform_impl/DeepReadTemplate.ets';
import { router } from '@kit.ArkUI';
import type { ModelConfig } from '@amber/deepread-domain';
import { DeepReadSettingsSession } from '../../platform_impl/deepread_settings/DeepReadSettingsSession.ets';
import { DeepReadHostSettings } from '../../platform_impl/deepread_settings/DeepReadHostSettings.ets';
import type { SourceToggle } from '../../platform_impl/deepread_settings/DeepReadHostSettings.ets';
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
export struct DeepReadHostSettingsView {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @ObjectLink settings: DeepReadSettingsSession;
  @ObjectLink host: DeepReadHostSettings;
  onModel: () => void = (): void => {};
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
          if (this.host.models.length === 0) {
            FooterNote({ text: '暂无模型,请在下方添加。议会的每个席位需指定一个模型。' })
          }

          }

          if (this.settings.saved.length > 0) {
            Text(this.settings.saved)
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


    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%')
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }

  @Builder
  CardHairline(inset: number): void {
    Row() {
      Row().width('100%').height(1).backgroundColor(LINE)
    }
    .width('100%')
    .padding({ left: inset, right: 14 })
  }

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
        TextInput({ text: this.host.focusKeywords, placeholder: 'AI、大模型、机器人、数码 3C...' })
          .fontSize(15).fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK).height(44)
          .placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(8)
          .padding({ left: 14, right: 14 })
          .onChange((v: string): void => { this.host.focusKeywords = v; })
        Row() {
          Text('筛选模式')
            .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
            .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
          Blank()
          PillSelect({
            options: this.host.focusModes, selectedIndex: this.host.focusModeIndex,
            triggerColor: INK,
            triggerMinHeight: 44,
            onSelect: (i: number): void => { this.host.focusModeIndex = i; },
          })
          .width(112)
        }
        .width('100%').alignItems(VerticalAlign.Center)
        .constraintSize({ minHeight: 44 })
        .margin({ top: 8 })
      }
      .width('100%').padding({ left: 14, right: 14, top: 12, bottom: 12 })
      this.CardHairline(14)
      this.CardAction('应用', (): void => { this.host.saveFocus(); })
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
            ForEach(this.host.fontModes, (m: string, i: number): void => {
              Text(m)
                .fontSize(13).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(13))
                .fontFamily(FONT_SANS_MEDIUM)
                .fontColor(this.settings.fontModeIndex === i ? INK : INK3)
                .constraintSize({ minHeight: 44 })
                .padding({ left: 12, right: 12, top: 6, bottom: 6 })
                .borderRadius(8)
                .backgroundColor(this.settings.fontModeIndex === i
                  ? accentSoftBg(this.accentColorStore) : Color.Transparent)
                .onClick((): void => { this.settings.fontModeIndex = i; })
            }, (m: string): string => m)
          }
        }
        .width('100%').alignItems(VerticalAlign.Center)
        Row() {
          Text('字号')
            .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
            .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
          Blank()
          Text(`${this.settings.fontScalePercent}%`)
            .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
            .fontColor(INK3)
        }
        .width('100%').alignItems(VerticalAlign.Center)
        .margin({ top: 12 })
        Slider({ value: this.settings.fontScalePercent, min: 70, max: 180, step: 5, style: SliderStyle.OutSet })
          .width('100%')
          .selectedColor(this.accentColorStore)
          .onChange((v: number): void => { this.settings.fontScalePercent = Math.round(v); })
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
          .onClick((): void => { this.settings.saveReading(); })
      }
      .width('100%').alignItems(VerticalAlign.Center)
      .padding({ left: 14, right: 4 })
      this.CardHairline(14)
      Column() {
        Text('文章模板')
          .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
          .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
          .width('100%')
        Flex({ wrap: FlexWrap.Wrap }) {
          ForEach(this.settings.allTemplateChips(), (t: DeepReadTemplateDef): void => {
            Text(t.name)
              .fontSize(13).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(13))
              .fontFamily(FONT_SANS_MEDIUM)
              .fontColor(this.settings.templateId === t.id ? INK : INK3)
              .constraintSize({ minHeight: 44 })
              .padding({ left: 12, right: 12, top: 6, bottom: 6 })
              .borderRadius(8)
              .backgroundColor(this.settings.templateId === t.id
                ? accentSoftBg(this.accentColorStore) : Color.Transparent)
              .margin({ right: 6, bottom: 6 })
              .onClick((): void => { this.settings.saveTemplate(t.id); })
          }, (t: DeepReadTemplateDef): string => t.id)
        }
        .width('100%')
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
        onRowClick: (): void => { this.onModel(); } }).enabled(!this.settings.savingModel)
      Column({ space: 6 }) {
        Text(this.settings.taskModelLabel).fontSize(14).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(14))
          .fontFamily(FONT_SANS_MEDIUM).fontColor(INK).width('100%').wordBreak(WordBreak.BREAK_ALL)
        if (this.settings.taskModelDetail.length > 0) {
          Text(this.settings.taskModelDetail).fontSize(12).lineHeight(17).fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK3).width('100%')
        }
        if (this.settings.modelError.length > 0) {
          Text(this.settings.modelError).fontSize(12).lineHeight(17).fontFamily(FONT_SANS_MEDIUM)
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
        BtnGhost({ label: this.host.copyingChat ? '正在读取聊天配置…' : '填入当前 Chat 配置',
          onTap: (): void => { this.host.fillCurrentChatDraft(); } })
          .width('100%').enabled(!this.host.copyingChat)
        if (this.host.chatCopyMessage.length > 0) {
          Text(this.host.chatCopyMessage).fontSize(13).lineHeight(lineHeightForSize(13))
            .fontFamily(FONT_SANS_MEDIUM).fontColor(INK3).width('100%').margin({ top: 8 })
        }
        Text('填入后请检查三个字段，再点击“保存全部”。深读模型非空时仍优先使用该模型。')
          .fontSize(12).lineHeight(17).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
          .width('100%').margin({ top: 8, bottom: 16 })
        this.FieldLabel('API Base URL', 0)
        TextInput({ text: this.host.aiBaseUrl })
          .height(44).fontSize(15).fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(8).padding({ left: 14, right: 14 })
          .margin({ top: 6 })
          .onChange((v: string): void => { this.host.aiBaseUrl = v; })
        this.FieldLabel('API Key', 16)
        TextInput({ text: this.host.aiKey, placeholder: 'sk-...' })
          .height(44).fontSize(15).fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(8).padding({ left: 14, right: 14 })
          .margin({ top: 6 })
          .onChange((v: string): void => { this.host.aiKey = v; })
        this.FieldLabel('模型名称', 16)
        TextInput({ text: this.host.model, placeholder: 'deepseek-chat' })
          .height(44).fontSize(15).fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(8).padding({ left: 14, right: 14 })
          .margin({ top: 6 })
          .onChange((v: string): void => { this.host.model = v; })
      }
      .width('100%')
      .padding({ left: 14, right: 14, top: 12, bottom: 12 })
    }
    .width('100%')
  }

  @Builder
  ModelsCard(): void {
    Column() {
      ForEach(this.host.models, (m: ModelConfig, idx: number): void => {
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
            BtnGhost({ label: '删除', onTap: (): void => { this.host.deleteModel(m.id); } })
          }
          .width('100%').alignItems(VerticalAlign.Center)
          .constraintSize({ minHeight: 52 })
          .padding({ left: 14, right: 14, top: 10, bottom: 10 })
          if (idx < this.host.models.length - 1) {
            this.CardHairline(14)
          }
        }
        .width('100%')
      }, (m: ModelConfig): string => m.id)
      if (this.host.models.length > 0) {
        this.CardHairline(14)
      }
      Column() {
        this.FieldLabel('名称(可选)', 0)
        TextInput({ text: this.host.newLabel, placeholder: '如:GPT-4o / 备用 DeepSeek' })
          .height(44).fontSize(15).fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(8).padding({ left: 14, right: 14 })
          .margin({ top: 6 })
          .onChange((v: string): void => { this.host.newLabel = v; })
        this.FieldLabel('API Base URL', 12)
        TextInput({ text: this.host.newBaseUrl })
          .height(44).fontSize(15).fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(8).padding({ left: 14, right: 14 })
          .margin({ top: 6 })
          .onChange((v: string): void => { this.host.newBaseUrl = v; })
        this.FieldLabel('API Key', 12)
        TextInput({ text: this.host.newApiKey, placeholder: 'sk-...' })
          .height(44).fontSize(15).fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(8).padding({ left: 14, right: 14 })
          .margin({ top: 6 })
          .onChange((v: string): void => { this.host.newApiKey = v; })
        this.FieldLabel('模型名称', 12)
        TextInput({ text: this.host.newModel, placeholder: 'deepseek-chat' })
          .height(44).fontSize(15).fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(8).padding({ left: 14, right: 14 })
          .margin({ top: 6 })
          .onChange((v: string): void => { this.host.newModel = v; })
        Row() {
          BtnFill({ label: '添加模型', onTap: (): void => { this.host.addModel(); } })
          Text(this.host.modelSaved)
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
      ForEach(this.host.hotlistToggles, (t: SourceToggle, idx: number): void => {
        CardToggleRow({
          label: t.displayName,
          checked: t.enabled,
          isLast: idx === this.host.hotlistToggles.length - 1,
          onToggle: (): void => { this.host.toggleHotlist(t.id); },
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
          selectedIndex: [15, 30, 60, 120].indexOf(this.host.hotlistRefreshMinutes),
          triggerColor: INK, triggerMinHeight: 44,
          onSelect: (index: number): void => { this.host.saveHotlistRefresh(index); } })
          .width(112)
      }
      .width('100%').alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 52 }).padding({ left: 14, right: 14, top: 8, bottom: 8 })
      this.CardHairline(14)
      CardToggleRow({ label: '仅在 Wi-Fi 下刷新', desc: '移动网络下保留缓存与现有榜单',
        checked: this.host.hotlistWifiOnly, onToggle: (): void => { this.host.toggleHotlistWifi(); } })
      CardToggleRow({ label: '显示中文标题', desc: '使用深读模型翻译英文标题', isLast: true,
        checked: this.host.hotlistTranslateZh, onToggle: (): void => { this.host.toggleHotlistTranslation(); } })
    }
    .width('100%')
  }

  @Builder
  LegacySourceCard(): void {
    Column() {
      Column() {
        CardRow({
          label: '选择搜索源',
          valueText: KEYED_SOURCES.find((s) => s.id === this.host.selectedSource)?.displayName ?? '选择',
          onRowClick: (): void => {
            this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
              this.host.showSourcePicker = !this.host.showSourcePicker;
            });
          },
        })
        if (this.host.showSourcePicker) {
          Column() {
            ForEach(KEYED_SOURCES, (s: SearchSourceDef, idx: number): void => {
              Column() {
                Row() {
                  Text(s.displayName)
                    .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
                    .fontFamily(FONT_SANS_MEDIUM)
                    .fontColor(s.id === this.host.selectedSource ? this.accentColorStore : INK)
                }
                .width('100%').alignItems(VerticalAlign.Center)
                .constraintSize({ minHeight: 44 })
                .padding({ left: 32, right: 14, top: 8, bottom: 8 })
                .onClick((): void => {
                  this.host.selectSource(s.id);
                  this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
                    this.host.showSourcePicker = false;
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
          text: this.host.currentKey,
          placeholder: KEYED_SOURCES.find((s) => s.id === this.host.selectedSource)?.keyPlaceholder ?? 'api-key-...',
        })
          .height(44).fontSize(15).fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(8).padding({ left: 14, right: 14 })
          .margin({ top: 6 })
          .onChange((v: string): void => { this.host.currentKey = v; })
        Row() {
          BtnGhost({ label: '测试连通', onTap: (): void => { this.host.testSource(); } })
          if (this.host.testStatus.length > 0) {
            Text(this.host.testStatus === 'testing' ? '测试中...' : this.host.testResult)
              .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
              .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
              .fontColor(this.host.testStatus === 'error' ? ERROR : (this.host.testStatus === 'ok' ? SIGNAL : INK3))
              .margin({ left: 12 })
          }
        }
        .width('100%').alignItems(VerticalAlign.Center)
        .margin({ top: 12 })
        Row() {
          BtnFill({ label: '保存此源配置', onTap: (): void => { this.host.saveSourceConfig(); } })
        }
        .width('100%')
        .margin({ top: 12 })
      }
      .width('100%').padding({ left: 14, right: 14, top: 12, bottom: 12 })
    }
    .width('100%')
  }
  build(): void { this.HostSettings(); }
}
