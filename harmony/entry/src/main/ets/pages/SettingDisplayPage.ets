// SettingDisplayPage — 显示设置页(G3-S2)
// 高频 DisplaySetting 字段的纸面列表编辑。
import { loadDisplaySetting, saveDisplaySetting, makeDisplaySetting } from '@amber/chat-domain';
import type { DisplaySetting } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import {
  PageTitleBar, SectionHeader, ToggleRow, Seg,
} from '../components/PageTemplate.ets';
import { INK3, BG, ACCENT, GUTTER, TEXT_STYLES } from '../design/tokens.ets';
import { FONT_MONO_MEDIUM } from '../design/fonts.ets';

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
      ttsOnlyReadQuoted: patch.ttsOnlyReadQuoted ?? this.setting.ttsOnlyReadQuoted,
      autoPlayTTSAfterGeneration: patch.autoPlayTTSAfterGeneration ?? this.setting.autoPlayTTSAfterGeneration,
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

  build(): void {
    Column() {
      PageTitleBar({ title: '显示设置' })
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
            SectionHeader({ label: '消息显示' })
            ToggleRow({
              label: '助手气泡', desc: '为助手消息显示气泡背景',
              checked: this.setting.showAssistantBubble,
              onToggle: (): void => { this.update({ showAssistantBubble: !this.setting.showAssistantBubble }); },
            })
            ToggleRow({
              label: '显示模型名', desc: '在消息头部显示模型名称',
              checked: this.setting.showModelName,
              onToggle: (): void => { this.update({ showModelName: !this.setting.showModelName }); },
            })
            ToggleRow({
              label: '显示用户头像', desc: '在用户消息旁显示头像',
              checked: this.setting.showModelIcon,
              onToggle: (): void => { this.update({ showModelIcon: !this.setting.showModelIcon }); },
            })
            ToggleRow({
              label: '自动滚动', desc: '新消息到达时自动滚动到底部',
              checked: this.setting.enableAutoScroll, isLast: true,
              onToggle: (): void => { this.update({ enableAutoScroll: !this.setting.enableAutoScroll }); },
            })

            SectionHeader({ label: '输入' })
            ToggleRow({
              label: '回车发送', desc: '按 Enter 键发送消息(而非换行)',
              checked: this.setting.sendOnEnter,
              onToggle: (): void => { this.update({ sendOnEnter: !this.setting.sendOnEnter }); },
            })
            ToggleRow({
              label: '长文本粘贴为文件', desc: '粘贴超长文本时自动转为文件附件',
              checked: this.setting.pasteLongTextAsFile, isLast: true,
              onToggle: (): void => { this.update({ pasteLongTextAsFile: !this.setting.pasteLongTextAsFile }); },
            })

            SectionHeader({ label: '代码块' })
            ToggleRow({
              label: '自动折叠长代码', desc: '超过 10 行的代码块自动折叠',
              checked: this.setting.codeBlockAutoCollapse,
              onToggle: (): void => { this.update({ codeBlockAutoCollapse: !this.setting.codeBlockAutoCollapse }); },
            })
            ToggleRow({
              label: '显示行号', desc: '在代码块中显示行号',
              checked: this.setting.showLineNumbers, isLast: true,
              onToggle: (): void => { this.update({ showLineNumbers: !this.setting.showLineNumbers }); },
            })

            SectionHeader({ label: '字号' })
            Text('字号比例')
              .fontSize(TEXT_STYLES.body.size)
              .fontWeight(600)
              .lineHeight(TEXT_STYLES.body.lineHeight)
              .fontFamily(TEXT_STYLES.body.family)
              .fontColor(INK3)
              .margin({ left: 16, bottom: 8 })
              .width('100%')
            Seg({
              options: FONT_RATIO_OPTIONS,
              selected: this.fontSizeRatioIndex(),
              onSelect: (idx: number): void => { this.update({ fontSizeRatio: ratios[idx] }); },
            })
            Text(`${Math.round(this.setting.fontSizeRatio * 100)}%`)
              .fontSize(TEXT_STYLES.meta.size)
              .fontWeight(TEXT_STYLES.meta.weight)
              .lineHeight(TEXT_STYLES.meta.lineHeight)
              .fontFamily(FONT_MONO_MEDIUM)
              .fontColor(INK3)
              .width('100%')
              .textAlign(TextAlign.End)
              .margin({ top: 8, right: 16 })
            Column() {}.height(40)
          }
          .width('100%')
          .padding({ left: GUTTER, right: GUTTER })
        }
        .align(Alignment.TopStart)
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
    .opacity(this.accentColorStore.length >= 0 ? 1 : 1)
  }
}
