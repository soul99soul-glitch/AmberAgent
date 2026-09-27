// SettingDisplayPage — 显示与字体设置页(G3-S2)
// 高频 DisplaySetting 字段的卡片分组编辑。
// 2026-09-27 二级页 iOS 对齐(基准 IMG_1984):仅换视觉骨架
// (CenteredTitleBar/PlainSection/CardGroup r18+描边/CardRow/CardToggleRow),逻辑零改动。
import { loadDisplaySetting, saveDisplaySetting, makeDisplaySetting } from '@amber/chat-domain';
import type { DisplaySetting } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardRow, CardToggleRow, Seg,
} from '../components/PageTemplate.ets';
import { INK, INK3, BG, ACCENT, GUTTER, TEXT_STYLES } from '../design/tokens.ets';
import { FONT_MONO_MEDIUM, FONT_SANS_MEDIUM } from '../design/fonts.ets';

const FONT_RATIO_OPTIONS: string[] = ['85%', '100%', '115%', '130%'];
const ratios: number[] = [0.85, 1.0, 1.15, 1.3];

@Entry
@Component
struct SettingDisplayPage {
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State setting: DisplaySetting = makeDisplaySetting();
  @State loaded: boolean = false;

  aboutToAppear(): void {
    loadDisplaySetting(getChatKvStore()).then((s: DisplaySetting): void => {
      this.setting = s;
      this.loaded = true;
    }).catch((): void => { this.loaded = true; });
  }

  private update(patch: Partial<DisplaySetting>): void {
    // ArkTS 禁止对象 spread(arkts-no-spread)→ 手动合并
    this.setting = makeDisplaySetting({
      showAssistantBubble: patch.showAssistantBubble ?? this.setting.showAssistantBubble,
      showModelIcon: patch.showModelIcon ?? this.setting.showModelIcon,
      showModelName: patch.showModelName ?? this.setting.showModelName,
      showDateBelowName: patch.showDateBelowName ?? this.setting.showDateBelowName,
      showThinkingContent: patch.showThinkingContent ?? this.setting.showThinkingContent,
      autoCloseThinking: patch.autoCloseThinking ?? this.setting.autoCloseThinking,
      showUpdates: patch.showUpdates ?? this.setting.showUpdates,
      showMessageJumper: patch.showMessageJumper ?? this.setting.showMessageJumper,
      messageJumperOnLeft: patch.messageJumperOnLeft ?? this.setting.messageJumperOnLeft,
      fontSizeRatio: patch.fontSizeRatio ?? this.setting.fontSizeRatio,
      enableMessageGenerationHapticEffect: patch.enableMessageGenerationHapticEffect ?? this.setting.enableMessageGenerationHapticEffect,
      skipCropImage: patch.skipCropImage ?? this.setting.skipCropImage,
      enableNotificationOnMessageGeneration: patch.enableNotificationOnMessageGeneration ?? this.setting.enableNotificationOnMessageGeneration,
      enableLiveUpdateNotification: patch.enableLiveUpdateNotification ?? this.setting.enableLiveUpdateNotification,
      codeBlockAutoWrap: patch.codeBlockAutoWrap ?? this.setting.codeBlockAutoWrap,
      codeBlockAutoCollapse: patch.codeBlockAutoCollapse ?? this.setting.codeBlockAutoCollapse,
      showLineNumbers: patch.showLineNumbers ?? this.setting.showLineNumbers,
      pasteLongTextAsFile: patch.pasteLongTextAsFile ?? this.setting.pasteLongTextAsFile,
      pasteLongTextThreshold: patch.pasteLongTextThreshold ?? this.setting.pasteLongTextThreshold,
      sendOnEnter: patch.sendOnEnter ?? this.setting.sendOnEnter,
      enableAutoScroll: patch.enableAutoScroll ?? this.setting.enableAutoScroll,
      showBottomFollowAnimation: patch.showBottomFollowAnimation ?? this.setting.showBottomFollowAnimation,
      enableLatexRendering: patch.enableLatexRendering ?? this.setting.enableLatexRendering,
      enableBlurEffect: patch.enableBlurEffect ?? this.setting.enableBlurEffect,
      chatFontFamily: patch.chatFontFamily ?? this.setting.chatFontFamily,
      enableVolumeKeyScroll: patch.enableVolumeKeyScroll ?? this.setting.enableVolumeKeyScroll,
      volumeKeyScrollRatio: patch.volumeKeyScrollRatio ?? this.setting.volumeKeyScrollRatio,
      chatThemeChoice: patch.chatThemeChoice ?? this.setting.chatThemeChoice,
      amberBaseFamily: patch.amberBaseFamily ?? this.setting.amberBaseFamily,
      accentColor: patch.accentColor ?? this.setting.accentColor,
      userNickname: patch.userNickname ?? this.setting.userNickname,
      userAvatar: patch.userAvatar ?? this.setting.userAvatar,
    });
    saveDisplaySetting(getChatKvStore(), this.setting);
  }

  private fontSizeRatioIndex(): number {
    let closest: number = 0;
    let distance: number = Math.abs(this.setting.fontSizeRatio - ratios[0]);
    for (let i = 1; i < ratios.length; i++) {
      const nextDistance: number = Math.abs(this.setting.fontSizeRatio - ratios[i]);
      if (nextDistance < distance) {
        closest = i;
        distance = nextDistance;
      }
    }
    return closest;
  }

  // ── 卡片内容(@Builder:组件构造必须在 builder 上下文,
  //    裸 lambda 直接 new 组件会炸 "class constructor cannot called without 'new'")──

  // 字体组:字号比例 Seg(原有实现装入卡片容器,iOS 同位为聊天字体大小卡)
  @Builder
  FontCard(): void {
    Column() {
      Row() {
        Text('字号比例')
          .fontSize(16)
          .fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.body.lineHeight)
          .fontFamily(FONT_SANS_MEDIUM)
          .fontColor(INK)
        Blank()
        Text(`${Math.round(this.setting.fontSizeRatio * 100)}%`)
          .fontSize(TEXT_STYLES.meta.size)
          .fontWeight(TEXT_STYLES.meta.weight)
          .lineHeight(TEXT_STYLES.meta.lineHeight)
          .fontFamily(FONT_MONO_MEDIUM)
          .fontColor(INK3)
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      Seg({
        options: FONT_RATIO_OPTIONS,
        selected: this.fontSizeRatioIndex(),
        onSelect: (idx: number): void => { this.update({ fontSizeRatio: ratios[idx] }); },
      })
        .margin({ top: 12 })
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 12, bottom: 12 })
  }

  @Builder
  MessageDisplayCard(): void {
    CardToggleRow({
      label: '助手气泡', desc: '为助手消息显示气泡背景',
      checked: this.setting.showAssistantBubble,
      onToggle: (): void => { this.update({ showAssistantBubble: !this.setting.showAssistantBubble }); },
    })
    CardToggleRow({
      label: '显示模型名', desc: '在消息头部显示模型名称',
      checked: this.setting.showModelName,
      onToggle: (): void => { this.update({ showModelName: !this.setting.showModelName }); },
    })
    CardToggleRow({
      // F16:原「显示用户头像」错绑 showModelIcon;该字段实际控制模型图标
      label: '显示模型图标', desc: '在助手消息头部显示模型首字母标识',
      checked: this.setting.showModelIcon,
      onToggle: (): void => { this.update({ showModelIcon: !this.setting.showModelIcon }); },
    })
    CardToggleRow({
      label: '自动滚动', desc: '新消息到达时自动滚动到底部',
      checked: this.setting.enableAutoScroll, isLast: true,
      onToggle: (): void => { this.update({ enableAutoScroll: !this.setting.enableAutoScroll }); },
    })
  }

  @Builder
  InputCard(): void {
    // F16:回车发送 / 长文本粘贴为文件 —— 鸿蒙输入层无对应管线,
    //   显式标记「未支持」且不可编辑(不写盘、不制造已保存的错觉)。
    CardRow({
      label: '回车发送', desc: '当前版本未支持,输入层暂无此行为开关',
      valueText: '未支持', showChevron: false,
    })
    CardRow({
      label: '长文本粘贴为文件', desc: '当前版本未支持,输入层暂无此行为开关',
      valueText: '未支持', showChevron: false, isLast: true,
    })
  }

  @Builder
  CodeBlockCard(): void {
    CardToggleRow({
      label: '自动折叠长代码', desc: '超过 10 行的代码块自动折叠',
      checked: this.setting.codeBlockAutoCollapse,
      onToggle: (): void => { this.update({ codeBlockAutoCollapse: !this.setting.codeBlockAutoCollapse }); },
    })
    CardToggleRow({
      label: '显示行号', desc: '在代码块中显示行号',
      checked: this.setting.showLineNumbers, isLast: true,
      onToggle: (): void => { this.update({ showLineNumbers: !this.setting.showLineNumbers }); },
    })
  }

  build(): void {
    Column() {
      CenteredTitleBar({ title: '显示与字体' })
      if (!this.loaded) {
        Text('加载中…')
          .fontSize(TEXT_STYLES.body.size)
          .fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight)
          .fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK3)
          .padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            // ── 字体(对齐 iOS「字体」组)──
            PlainSection({ label: '字体' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.FontCard(); } })
              .margin({ left: GUTTER, right: GUTTER })

            // ── 消息显示 ──
            PlainSection({ label: '消息显示' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.MessageDisplayCard(); } })
              .margin({ left: GUTTER, right: GUTTER })

            // ── 输入 ──
            PlainSection({ label: '输入' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.InputCard(); } })
              .margin({ left: GUTTER, right: GUTTER })

            // ── 代码块 ──
            PlainSection({ label: '代码块' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.CodeBlockCard(); } })
              .margin({ left: GUTTER, right: GUTTER })

            Column() {}.height(40)
          }
          .width('100%')
        }
        .align(Alignment.TopStart)
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
    .opacity(this.accentColorStore.length >= 0 ? 1 : 1)
  }
}
