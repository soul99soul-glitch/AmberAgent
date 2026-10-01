// ChatMessageCopySheet — 复制消息 sheet 组件(第三梯队)
// Android 基准: ChatMessageCopySheet.kt(130 行)— ModalBottomSheet 列出全部 Text part
//   完整消息放在单个原生 Text 中，可跨段选区复制/全选。
import type { UIMessage } from '@amber/chat-domain';
import { messageCopyText } from '@amber/chat-domain';
import {
  INK, INK2, INK3, ACCENT, ACCENT_INK, SURFACE, SURFACE2, RAISED, LINE, HEADER, BODY, BODY2,
} from '../design/tokens.ets';
import { pasteboard } from '@kit.BasicServicesKit';
import { Icon } from './ui/Icon.ets';

// ChatPage 的 bindSheet 内容。
@Component
export struct ChatMessageCopySheet {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop message: UIMessage;
  // D7-4:复制格式(false = 纯文本,true = Markdown)
  @State formatMarkdown: boolean = false;
  onDismiss: () => void = (): void => {};

  private copyToClipboard(text: string): void {
    // 照搬 EntryLocalToolPorts.ets:30 正确用法
    const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
    board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text));
  }

  // 选区与全部复制共用格式转换;Markdown 保留 reasoning/tool 结构。
  private wholeText(): string {
    return messageCopyText(this.message, this.formatMarkdown);
  }

  @Builder
  private formatOption(label: string, markdown: boolean): void {
    Text(label)
      .fontSize(BODY2).fontWeight(FontWeight.Medium)
      .fontColor(this.formatMarkdown === markdown ? INK : INK3)
      .layoutWeight(1).height(40).textAlign(TextAlign.Center)
      .backgroundColor(this.formatMarkdown === markdown ? RAISED : Color.Transparent)
      .borderRadius(10)
      .onClick((): void => { this.formatMarkdown = markdown; })
  }

  build(): void {
    Column() {
      // 标题和关闭入口共用一条基线，左右遵循 20vp 内容边距。
      Row() {
        Text('选择并复制').fontSize(HEADER).fontWeight(FontWeight.Medium).fontColor(INK)
        Blank()
        Row() {
          Icon({ name: 'close', iconSize: 18, color: INK2 })
        }
          .width(44).height(44)
          .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
          .accessibilityText('关闭')
          .onClick((): void => { this.onDismiss(); })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .padding({ left: 20, right: 12, top: 16, bottom: 12 })

      // D7-4:复制格式(纯文本 / Markdown)
      Row() {
        this.formatOption('纯文本', false)
        this.formatOption('Markdown', true)
      }
      .height(44).padding(2)
      .backgroundColor(SURFACE2).borderRadius(12)
      .margin({ left: 20, right: 20 })

      if (this.wholeText().trim().length > 0) {
        Scroll() {
          Text(this.wholeText())
            .fontSize(BODY).fontColor(INK2)
            .lineHeight(22)
            .copyOption(CopyOptions.LocalDevice)
            .width('100%')
            .padding({ left: 20, right: 20, top: 20, bottom: 20 })
        }
        .width('100%')
        .layoutWeight(1)
        .scrollBar(BarState.Auto)

        // 主操作固定在底部，不与格式切换或原生选区菜单争抢正文顶部。
        Divider().color(LINE).strokeWidth(0.5)
        Column() {
          Row({ space: 8 }) {
            Icon({ name: 'copy', iconSize: 16, color: ACCENT_INK })
            Text('全部复制').fontSize(BODY2).fontWeight(FontWeight.Medium).fontColor(ACCENT_INK)
          }
          .width('100%').height(44)
          .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
          .backgroundColor(ACCENT).borderRadius(12)
          .accessibilityText('全部复制')
          .onClick((): void => {
            this.copyToClipboard(this.wholeText());
            this.onDismiss();
          })
        }
        .width('100%')
        .padding({ left: 20, right: 20, top: 12, bottom: 16 })
      } else {
        // 空状态(Android :95-110)
        Text('无可复制的文本内容').fontSize(BODY2).fontColor(INK3)
          .width('100%').textAlign(TextAlign.Center)
          .layoutWeight(1)
          .padding({ top: 24, bottom: 24 })
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .height('100%')
    .backgroundColor(SURFACE)
    .borderRadius({ topLeft: 16, topRight: 16 })
  }
}
