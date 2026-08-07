// SettingDisplayPage — 显示设置页(G3-S2)
// Android 基准: SettingDisplayPage.kt(746 行)
// MVP: 编辑高频 DisplaySetting 字段(sendOnEnter/pasteLongTextAsFile/showAssistantBubble/
//   showModelName/showUserAvatar/fontSizeRatio/codeBlockAutoCollapse/showLineNumbers)
import { router } from '@kit.ArkUI';
import { loadDisplaySetting, saveDisplaySetting, makeDisplaySetting } from '@amber/chat-domain';
import type { DisplaySetting } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { PageTitleBar, SectionHeader } from '../components/PageTemplate.ets';
import { INK, INK2, INK3, SURFACE, LINE, BG, BODY, META, LABEL, ACCENT } from '../design/tokens.ets';

@Entry
@Component
struct SettingDisplayPage {
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
    });
    saveDisplaySetting(getChatKvStore(), this.setting);
  }

  @Builder toggleRow(label: string, desc: string, checked: boolean, onToggle: () => void): void {
    Row() {
      Column() {
        Text(label).fontSize(BODY).fontColor(INK)
        Text(desc).fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      Toggle({ type: ToggleType.Switch, isOn: checked })
        .selectedColor(ACCENT)
        .onChange((_v: boolean): void => { onToggle(); })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }

  @Builder selectRow(label: string, valueText: string, onClick: () => void): void {
    Row() {
      Text(label).fontSize(BODY).fontColor(INK).layoutWeight(1)
      Text(valueText).fontSize(META).fontColor(ACCENT)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
    .onClick((): void => { onClick(); })
  }

  build(): void {
    Column() {
      PageTitleBar({ title: '显示设置' })

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            // 输入
            SectionHeader({ label: '输入' })
            this.toggleRow('回车发送', '按 Enter 键发送消息(而非换行)', this.setting.sendOnEnter,
              (): void => { this.update({ sendOnEnter: !this.setting.sendOnEnter }); })

            this.toggleRow('长文本粘贴为文件', '粘贴超长文本时自动转为文件附件',
              this.setting.pasteLongTextAsFile,
              (): void => { this.update({ pasteLongTextAsFile: !this.setting.pasteLongTextAsFile }); })

            // 消息显示
            SectionHeader({ label: '消息显示' })

            this.toggleRow('助手气泡', '为助手消息显示气泡背景', this.setting.showAssistantBubble,
              (): void => { this.update({ showAssistantBubble: !this.setting.showAssistantBubble }); })

            this.toggleRow('显示模型名', '在消息头部显示模型名称', this.setting.showModelName,
              (): void => { this.update({ showModelName: !this.setting.showModelName }); })

            this.toggleRow('显示用户头像', '在用户消息旁显示头像', this.setting.showModelIcon,
              (): void => { this.update({ showModelIcon: !this.setting.showModelIcon }); })

            this.toggleRow('自动滚动', '新消息到达时自动滚动到底部', this.setting.enableAutoScroll,
              (): void => { this.update({ enableAutoScroll: !this.setting.enableAutoScroll }); })

            // 代码块
            SectionHeader({ label: '代码块' })

            this.toggleRow('自动折叠长代码', '超过 10 行的代码块自动折叠', this.setting.codeBlockAutoCollapse,
              (): void => { this.update({ codeBlockAutoCollapse: !this.setting.codeBlockAutoCollapse }); })

            this.toggleRow('显示行号', '在代码块中显示行号', this.setting.showLineNumbers,
              (): void => { this.update({ showLineNumbers: !this.setting.showLineNumbers }); })

            // 字号
            this.selectRow('字号比例', `${Math.round(this.setting.fontSizeRatio * 100)}%`,
              (): void => {
                const ratios: number[] = [0.85, 1.0, 1.15, 1.3];
                const idx: number = ratios.indexOf(this.setting.fontSizeRatio);
                this.update({ fontSizeRatio: ratios[(idx + 1) % ratios.length] });
              })

            Column() {}.height(40)
          }
          .width('100%')
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
