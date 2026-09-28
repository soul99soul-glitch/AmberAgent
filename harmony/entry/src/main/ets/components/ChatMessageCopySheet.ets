// ChatMessageCopySheet — 复制消息 sheet 组件(第三梯队)
// Android 基准: ChatMessageCopySheet.kt(130 行)— ModalBottomSheet 列出全部 Text part
//   供选区复制/全部复制;ArkUI 用 bindSheet 实现(待接入 ChatPage)
//   空状态:无 text part 时显示提示
import type { UIMessage, UIMessagePartText } from '@amber/chat-domain';
import { messageCopyText, textPartCopyText } from '@amber/chat-domain';
import { INK, INK2, INK3, ACCENT, SURFACE, SURFACE2, LINE, BODY, META } from '../design/tokens.ets';
import { pasteboard } from '@kit.BasicServicesKit';

// 待接入:ChatPage 用 bindSheet 套法接入,此组件为 sheet builder 内容
@Component
export struct ChatMessageCopySheet {
  @Prop message: UIMessage;
  // D7-4:复制格式(false = 纯文本,true = Markdown)
  @State formatMarkdown: boolean = false;
  onDismiss: () => void = (): void => {};

  private copyToClipboard(text: string): void {
    // 照搬 EntryLocalToolPorts.ets:30 正确用法
    const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
    board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text));
  }

  // 全部复制与逐段复制共用格式转换;Markdown 保留 reasoning/tool 结构。
  private wholeText(): string {
    return messageCopyText(this.message, this.formatMarkdown);
  }

  build(): void {
    Column() {
      // 标题行
      Row() {
        Text('复制消息').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
        Blank()
        Text('关闭').fontSize(META).fontColor(INK3)
          .onClick((): void => { this.onDismiss(); })
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 12, bottom: 8 })

      // D7-4:复制格式(纯文本 / Markdown)
      Row() {
        Text('纯文本')
          .fontSize(META)
          .fontColor(this.formatMarkdown ? INK3 : ACCENT)
          .padding({ left: 12, right: 12, top: 6, bottom: 6 })
          .backgroundColor(this.formatMarkdown ? Color.Transparent : SURFACE2)
          .borderRadius(8)
          .onClick((): void => { this.formatMarkdown = false; })
        Text('Markdown')
          .fontSize(META)
          .fontColor(this.formatMarkdown ? ACCENT : INK3)
          .padding({ left: 12, right: 12, top: 6, bottom: 6 })
          .backgroundColor(this.formatMarkdown ? SURFACE2 : Color.Transparent)
          .borderRadius(8)
          .margin({ left: 8 })
          .onClick((): void => { this.formatMarkdown = true; })
      }
      .width('100%')
      .padding({ left: 16, right: 16, bottom: 4 })

      // 收集非空 text parts(Android isNotBlank 过滤)
      if (this.hasTextParts()) {
        // 全部复制按钮
        Text('全部复制').fontSize(BODY).fontColor(ACCENT)
          .width('100%')
          .padding({ left: 16, right: 16, top: 10, bottom: 10 })
          .onClick((): void => {
            this.copyToClipboard(this.wholeText());
            this.onDismiss();
          })

        Divider().color(LINE).strokeWidth(0.5)

        // 逐条 Text part(非空)
        ForEach(this.getNonBlankTextParts(), (p: UIMessagePartText, i: number) => {
          Column() {
            Text(p.text)
              .fontSize(META).fontColor(INK2)
              .maxLines(3)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .width('100%')
              .padding({ left: 16, right: 16, top: 8, bottom: 4 })
            Text('复制此段').fontSize(META).fontColor(ACCENT)
              .padding({ left: 16, bottom: 8 })
              .onClick((): void => {
                this.copyToClipboard(textPartCopyText(p.text, this.formatMarkdown));
                this.onDismiss();
              })
          }
        }, (p: UIMessagePartText, i: number): string => `copy-${i}`)
      } else {
        // 空状态(Android :95-110)
        Text('无可复制的文本内容').fontSize(META).fontColor(INK3)
          .width('100%').textAlign(TextAlign.Center)
          .padding({ top: 24, bottom: 24 })
      }
    }
    .width('100%')
    .backgroundColor(SURFACE)
    .borderRadius({ topLeft: 16, topRight: 16 })
  }

  private hasTextParts(): boolean {
    return this.getNonBlankTextParts().length > 0;
  }

  private getNonBlankTextParts(): UIMessagePartText[] {
    const parts: UIMessagePartText[] = [];
    for (const p of this.message.parts) {
      if (p.type === 'text' && p.text.trim().length > 0) parts.push(p);
    }
    return parts;
  }
}
