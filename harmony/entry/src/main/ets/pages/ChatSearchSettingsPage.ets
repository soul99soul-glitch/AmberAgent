// ChatSearchSettingsPage — 搜索设置面(D-099)
// Android 锚点:
//   SettingSearchPage.kt(全文)— hero(master switch;enabledCount = 服务启用数 +
//     六内置 true 数;serviceCount = services.size + 6)、move/delete/edit/新增
//     语义逐字(:68-119,:215-251)
//   SettingSearchListCards.kt — hero(:96-176)、内置六行 zh 串逐字(:267-326)、
//     已配置行 rank+开关(:352-372)、通用选项(:551-590)、stepper
//     (:592-620)、推荐组合(:726-778)、resolveSelectedSearchIndex(:804-811)
//   SettingSearchServiceEditorSheet.kt — 结构/按钮(:46-152)、per-type 字段
//     (:155-933)、类型切换 = primaryConstructor.callBy 全新默认(:87)
// 偏差登记:
//   - ReorderableColumn 长按拖拽 → 上移/下移 按钮(ArkUI 无现成拖拽排序;
//     move 语义逐字保留);trailing '拖动调整优先级' → '调整优先级'
//   - ModalBottomSheet → 全屏 overlay(同 ChatTaskModelsPage overlay 先例)
//   - StepperNumberField 直输 → 仅 −/＋ 按钮,上限与搜索执行器一致
//   - 每 provider Description() composable 未移植(P1 既有登记)
// 视觉:2026-09-27 二级页 iOS 对齐(基准截图 IMG_1991)——居中标题「搜索服务」+
//   accent 圆添加、PlainSection 组标、r18 发丝描边卡组、CardToggleRow、
//   hero 状态行(绿点 + 文字)、已配置行序号胶囊 + 行内上移/下移 + 开关;
//   逻辑(加载/启停/排序/key 编辑/删除)零改动,编辑器 overlay 原样保留。
import {
  ACCENT, BG, INK, INK3, INK4, LINE, LINE2, RAISED, SURFACE2,
  SUCCESS_FG, ERROR, TEXT_STYLES, lineHeightForSize, accentSoftBg,
} from '../design/tokens.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardToggleRow, FooterNote,
} from '../components/PageTemplate.ets';
import {
  FONT_MONO, FONT_MONO_MEDIUM, FONT_SANS_MEDIUM, FONT_SANS_SEMIBOLD,
} from '../design/fonts.ets';
import { Icon } from '../components/ui/Icon.ets';
import { KeyboardAvoidMode, router } from '@kit.ArkUI';
import type { SearchSettings, SearchServiceOptions } from '@amber/chat-domain';
import { MAX_SEARCH_RESULTS, SEARCH_SERVICE_TYPES } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';

import { ChatSearchSettingsSession } from '../platform_impl/search_settings/ChatSearchSettingsSession.ets';
import { ChatSearchServiceEditorSession } from '../platform_impl/search_settings/ChatSearchServiceEditorSession.ets';
import type { ChatSearchServiceEditorPorts } from '../platform_impl/search_settings/ChatSearchServiceEditorSession.ets';
import { ChatSearchServiceEditor } from '../components/search/ChatSearchServiceEditor.ets';

@Entry
@Component
struct ChatSearchSettingsPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State settings: ChatSearchSettingsSession = new ChatSearchSettingsSession();
  @State editor: ChatSearchServiceEditorSession = new ChatSearchServiceEditorSession();
  private previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  private keyboardAvoidanceActive: boolean = false;

  aboutToAppear(): void {
    this.settings.start(getChatKvStore());
    const ports: ChatSearchServiceEditorPorts = {
      applyPatch: (patch: (settings: SearchSettings) => void): Promise<void> => this.settings.applyPatch(patch),
      serviceCount: (): number => this.settings.s.searchServices.length,
    };
    this.editor.start(ports);
    this.enableKeyboardAvoidance();
    this.settings.refresh();
  }
  onPageShow(): void { this.enableKeyboardAvoidance(); }
  onPageHide(): void { this.restoreKeyboardAvoidance(); }
  aboutToDisappear(): void {
    this.settings.dispose();
    this.editor.dispose();
    this.restoreKeyboardAvoidance();
  }
  onBackPress(): boolean {
    if (!this.editor.editorVisible) return false;
    if (this.editor.typeMenuOpen) this.editor.typeMenuOpen = false;
    else this.editor.closeEditor();
    return true;
  }
  private enableKeyboardAvoidance(): void {
    if (this.keyboardAvoidanceActive) return;
    this.previousKeyboardAvoidMode = this.getUIContext().getKeyboardAvoidMode();
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    this.keyboardAvoidanceActive = true;
  }
  private restoreKeyboardAvoidance(): void {
    if (!this.keyboardAvoidanceActive) return;
    this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardAvoidMode);
    this.keyboardAvoidanceActive = false;
  }

  // ===== UI =====

  // iOS hero 状态行(IMG_1991):绿点 + 「N / M 个服务已启用 · 多源召回」
  @Builder
  heroStatusRow() {
    Row() {
      Column()
        .width(8).height(8).borderRadius(4)
        .backgroundColor(SUCCESS_FG)
      Text(`${this.settings.enabledCount()} / ${this.settings.s.searchServices.length + 6} 个服务已启用 · 多源召回`)
        .fontSize(13)
        .lineHeight(lineHeightForSize(13))
        .fontFamily(FONT_SANS_MEDIUM)
        .fontColor(INK3)
        .margin({ left: 8 })
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)
    .constraintSize({ minHeight: 44 })
    .padding({ left: 14, right: 14 })
  }

  // hero 卡内容:总开关 + 状态行(同卡,iOS 基准)
  @Builder
  heroCardContent(): void {
    CardToggleRow({
      label: 'Agent 网络搜索',
      desc: '允许聊天调用 search_web 和 scrape_web，使用已启用服务。',
      checked: this.settings.s.enableWebSearch,
      isLast: false,
      onToggle: (): void => { this.settings.setMaster(!this.settings.s.enableWebSearch); },
    })
    this.heroStatusRow()
  }

  @Builder
  inlineToggle(checked: boolean, onToggle: () => void) {
    Stack({ alignContent: Alignment.Start }) {
      Column().width(42).height(24).borderRadius(99)
        .backgroundColor(checked ? this.accentColorStore : LINE2)
      Column().width(20).height(20).borderRadius(10).backgroundColor(RAISED)
        .margin({ left: checked ? 22 : 2 })
        .animation({ duration: 150, curve: Curve.EaseOut })
    }
    .clip(true).margin({ left: 12 }).onClick(onToggle)
    .accessibilityText(checked ? '已开启' : '已关闭')
  }

  @Builder
  builtinRow(title: string, desc: string, field: string, isLast: boolean) {
    CardToggleRow({
      label: title, desc: desc, checked: this.settings.builtinValue(field), isLast: isLast,
      onToggle: (): void => { this.settings.setBuiltin(field, !this.settings.builtinValue(field)); },
    })
  }

  // 内置免费源卡内容:六行开关(iOS 基准顺序)
  @Builder
  builtinCardContent(): void {
    this.builtinRow('Jina Reader',
      '无需 Key 读取网页全文，供 scrape_web 工具使用。', 'jina', false)
    this.builtinRow('免费网页聚合',
      'DuckDuckGo、Brave、360、夸克并发查询，被拦截的引擎自动跳过。', 'duckduckgo', false)
    this.builtinRow('Bing HTML',
      '免费公共兜底，遇到验证页会自动跳过。', 'bing', false)
    this.builtinRow('Wikipedia',
      '实体和背景知识源，普通话题默认参与。', 'wikipedia', false)
    this.builtinRow('Hacker News',
      '技术、开源和 AI 工具生态讨论源。', 'hackernews', false)
    this.builtinRow('Google WebView 兜底',
      '普通搜索弱或深度搜索时，提供搜索页链接；浏览器工具与站点权限可用时可打开。', 'webview', true)
  }

  // 已配置卡内容(iOS:拖柄 + 序号胶囊 + 名称 + id + 开关;鸿蒙无拖拽 → 行内上移/下移)
  @Builder
  configuredCardContent(): void {
    ForEach(this.settings.s.searchServices, (svc: SearchServiceOptions, index: number) => {
      this.serviceRow(svc, index)
    }, (svc: SearchServiceOptions): string => svc.id)
  }

  @Builder
  serviceRow(svc: SearchServiceOptions, index: number) {
    Column() {
      Row() {
        // 序号胶囊(iOS:软底 accent 圆)
        Text(`${index + 1}`)
          .fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
          .fontColor(this.accentColorStore)
          .width(24).height(24).textAlign(TextAlign.Center)
          .borderRadius(12)
          .backgroundColor(accentSoftBg(this.accentColorStore))
        Column() {
          Text(SEARCH_SERVICE_TYPES[svc.type])
            .fontSize(TEXT_STYLES.body.size).fontWeight(600)
            .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(svc.id).fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
            .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO).fontColor(INK3)
            .margin({ top: 3 }).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 10 })
        Text('上移').fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
          .fontColor(index > 0 ? this.accentColorStore : INK4).padding({ left: 6, right: 6 })
          .onClick((): void => { if (index > 0) this.settings.moveService(svc.id, -1); })
        Text('下移').fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
          .fontColor(index < this.settings.s.searchServices.length - 1 ? this.accentColorStore : INK4)
          .padding({ left: 6, right: 6 })
          .onClick((): void => {
            if (index < this.settings.s.searchServices.length - 1) this.settings.moveService(svc.id, 1);
          })
        this.inlineToggle(this.settings.s.searchEnabledServiceIds.indexOf(svc.id) >= 0,
          (): void => {
            this.settings.toggleService(svc, this.settings.s.searchEnabledServiceIds.indexOf(svc.id) < 0);
          })
        Icon({ name: 'chevron_right', iconSize: 16, color: INK4 }).margin({ left: 4 })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 56 })
      .padding({ left: 14, right: 10, top: 8, bottom: 8 })
      .onClick((): void => { this.editor.openEdit(svc, index); })
      if (index < this.settings.s.searchServices.length - 1) {
        // 分隔线内缩对齐文字起点(行 padding 14 + 胶囊 24 + 间距 10)
        Row() {
          Row()
            .width('100%')
            .height(1)
            .backgroundColor(LINE)
        }
        .width('100%')
        .padding({ left: 48, right: 14 })
      }
    }
    .width('100%')
  }

  @Builder
  commonOptionsRow() {
    Row() {
      Column() {
        Text('结果数量').fontSize(TEXT_STYLES.body.size).fontWeight(600)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
        Text('每次搜索最多返回的结果条数').fontSize(TEXT_STYLES.secondary.size)
          .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
          .fontColor(INK3).margin({ top: 4 }).maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      Row() {
        Text('−').fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
          .fontColor(this.settings.s.searchCommonOptions.resultSize > 1 ? INK : INK4)
          .width(32).height(32).textAlign(TextAlign.Center)
          .onClick((): void => {
            if (this.settings.s.searchCommonOptions.resultSize > 1) {
              this.settings.adjustResultSize(-1);
            }
          })
        Text(`${this.settings.s.searchCommonOptions.resultSize}`)
          .fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
          .fontColor(INK).width(36).textAlign(TextAlign.Center)
        Text('＋').fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
          .fontColor(this.settings.s.searchCommonOptions.resultSize < MAX_SEARCH_RESULTS ? INK : INK4)
          .width(32).height(32).textAlign(TextAlign.Center)
          .onClick((): void => {
            if (this.settings.s.searchCommonOptions.resultSize < MAX_SEARCH_RESULTS) {
              this.settings.adjustResultSize(1);
            }
          })
      }
      .backgroundColor(SURFACE2).borderRadius(8).border({ width: 1, color: LINE })
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)
    .constraintSize({ minHeight: 56 })
    .padding({ left: 14, right: 14, top: 10, bottom: 10 })
  }

  @Builder
  recommendRow(title: string, detail: string, isLast: boolean) {
    Column() {
      Row() {
        Text(title).fontSize(TEXT_STYLES.body.size).fontWeight(600)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(FONT_SANS_SEMIBOLD)
          .fontColor(this.accentColorStore)
        Text(detail).fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
          .fontColor(INK3).layoutWeight(1).textAlign(TextAlign.End)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      if (!isLast) {
        Row() {
          Row()
            .width('100%')
            .height(1)
            .backgroundColor(LINE)
        }
        .width('100%')
        .padding({ left: 14, right: 14 })
      }
    }
    .width('100%')
  }


  build() {
    Stack() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      Column() {
        CenteredTitleBar({
          title: '搜索服务',
          actionIcon: 'plus',
          onAction: (): void => { this.editor.openAdd(); },
          onBack: (): void => { if (!this.onBackPress()) router.back(); },
        })

        if (this.settings.error.length > 0) {
          Text(this.settings.error).fontSize(13).fontColor(ERROR)
            .width('100%').padding({ left: 16, right: 16, top: 8, bottom: 8 })
        }

        if (!this.settings.loaded) {
          Text('加载中…').fontSize(13).fontColor(INK3).margin({ top: 32 })
        } else {
          Scroll() {
            Column() {
              // ── AGENT 网络搜索(IMG_1991:总开关 + 状态行同卡)──
              PlainSection({ label: 'Agent 网络搜索' })
              Column() {
                CardGroup({ radius: 18, stroke: true, content: (): void => { this.heroCardContent(); } })
              }
              .width('100%').padding({ left: 16, right: 16 })

              // ── 内置免费源(六行开关)──
              PlainSection({ label: '内置免费源' })
              Column() {
                CardGroup({ radius: 18, stroke: true, content: (): void => { this.builtinCardContent(); } })
              }
              .width('100%').padding({ left: 16, right: 16 })

              // ── 已配置(序号胶囊 + 行内上移/下移 + 开关;鸿蒙无拖拽,保留既有排序交互)──
              PlainSection({ label: '已配置' })
              Column() {
                CardGroup({ radius: 18, stroke: true, content: (): void => { this.configuredCardContent(); } })
              }
              .width('100%').padding({ left: 16, right: 16 })
              FooterNote({ text: '使用行内「上移 / 下移」调整召回优先级，点按行编辑服务。' })

              // ── 通用选项(鸿蒙保留,iOS 无此组,按规范排布)──
              PlainSection({ label: '通用选项' })
              Column() {
                CardGroup({ radius: 18, stroke: true, content: (): void => { this.commonOptionsRow(); } })
              }
              .width('100%').padding({ left: 16, right: 16 })

              // ── 推荐组合(鸿蒙保留,iOS 无此组,按规范排布)──
              PlainSection({ label: '推荐组合' })
              Column() {
                CardGroup({ radius: 18, stroke: true, content: (): void => {
                  this.recommendRow('零成本', 'Jina + DuckDuckGo + Bing', false)
                  this.recommendRow('Google 结果', 'Serper / SerpAPI', false)
                  this.recommendRow('高质量多源', 'Tavily 或 Brave', true)
                } })
              }
              .width('100%').padding({ left: 16, right: 16 })

              Column().height(24)
            }
            .width('100%')
          }
          .align(Alignment.TopStart)
          .width('100%').layoutWeight(1)
        }
      }
      .width('100%').height('100%').backgroundColor(BG)

      if (this.editor.editorVisible) {
        ChatSearchServiceEditor({ editor: this.editor, serviceCount: this.settings.s.searchServices.length })
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%')
  }
}
