// ChatExportSheet — 导出格式选择 sheet(G4-S4)
// Android 基准: Export.kt(823 行)
// 必要: Markdown 导出(一键) + 图片导出选项(展开推理开关)
// 降级(跳过): ExportedChatImage bitmap 渲染(componentSnapshot) / FileProvider 分享
//   图片导出降级为"功能入口"(提示需设备支持),Markdown 导出完整实现
import type { UIMessage, Conversation } from '@amber/chat-domain';
import { toText } from '@amber/chat-domain';
import { ACCENT, BODY, INK, INK2, INK3, INK4, LABEL, LINE, META, OVERLAY, SURFACE, SURFACE2, BG, USER_BG, USER_INK } from '../design/tokens.ets';
import { Icon } from './ui/Icon.ets';
import { pasteboard } from '@kit.BasicServicesKit';
import { promptAction } from '@kit.ArkUI';
import { image } from '@kit.ImageKit';
import { savePixelMapToAlbum } from '../platform_impl/ImageSaveSupport.ets';

// D7-4:长图渲染条数上限(超出提示仅最近 N 条)
const IMAGE_EXPORT_MAX_MESSAGES: number = 40;

@Component
export struct ChatExportSheet {
  @Prop show: boolean = false;
  @Prop conversation: Conversation | null = null;
  @Prop messages: UIMessage[] = [];
  onDismiss: () => void = (): void => {};
  @State expandReasoning: boolean = false;
  @State renderImageMode: boolean = false;

  // 导出为 Markdown 文本
  private exportToMarkdown(): string {
    let md: string = '';
    // 标题
    const title: string = this.conversation !== null && this.conversation.title.length > 0
      ? this.conversation.title : '未命名对话';
    md += `# ${title}\n\n`;
    md += `> 导出时间: ${new Date().toLocaleString()}\n\n`;
    md += `---\n\n`;

    // 消息
    for (const msg of this.messages) {
      const role: string = msg.role === 'user' ? '👤 **用户**' : '🤖 **助手**';
      md += `### ${role}\n\n`;
      for (const part of msg.parts) {
        if (part.type === 'text') {
          md += `${part.text}\n\n`;
        } else if (part.type === 'reasoning' && this.expandReasoning) {
          md += `> **思考过程:**\n> ${part.reasoning.replace(/\n/g, '\n> ')}\n\n`;
        } else if (part.type === 'tool') {
          md += `**工具调用:** \`${part.toolName}\`\n\n`;
          if (part.output.length > 0) {
            md += '```\n' + JSON.stringify(part.output, null, 2) + '\n```\n\n';
          }
        }
      }
      md += `---\n\n`;
    }
    return md;
  }

  private copyMarkdown(): void {
    const md: string = this.exportToMarkdown();
    const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
    board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, md));
    promptAction.showToast({ message: '已复制 Markdown 到剪贴板', duration: 2000 });
    this.onDismiss();
  }

  // D7-4:长图导出 — 离屏渲染最近 N 条文本消息 → componentSnapshot → 相册
  private startImageExport(): void {
    if (this.messages.length === 0) {
      promptAction.showToast({ message: '没有可导出的消息', duration: 1500 });
      return;
    }
    this.renderImageMode = true;
    setTimeout((): void => {
      this.getUIContext().getComponentSnapshot().get('chat_export_image')
        .then((pm: image.PixelMap): void => {
          savePixelMapToAlbum(pm).catch((): void => {});
          this.renderImageMode = false;
          this.onDismiss();
        })
        .catch((): void => {
          this.renderImageMode = false;
          promptAction.showToast({ message: '导出失败', duration: 1500 });
        });
    }, 500);
  }

  private imageExportMessages(): UIMessage[] {
    if (this.messages.length <= IMAGE_EXPORT_MAX_MESSAGES) return this.messages;
    return this.messages.slice(this.messages.length - IMAGE_EXPORT_MAX_MESSAGES);
  }

  private messagePlainText(msg: UIMessage): string {
    const t: string = toText(msg).trim();
    return t.length > 0 ? t : '[非文本内容]';
  }

  @Builder
  ExportImageRender(): void {
    Column() {
      Text(this.conversation !== null && this.conversation.title.length > 0
        ? this.conversation.title : '未命名对话')
        .fontSize(17).fontWeight(FontWeight.Bold).fontColor(INK)
        .width('100%').margin({ bottom: 6 })
      Text(this.messages.length > IMAGE_EXPORT_MAX_MESSAGES
        ? `仅最近 ${IMAGE_EXPORT_MAX_MESSAGES} 条 · ${new Date().toLocaleDateString()}`
        : `${new Date().toLocaleDateString()}`)
        .fontSize(11).fontColor(INK3)
        .width('100%').margin({ bottom: 14 })
      ForEach(this.imageExportMessages(), (msg: UIMessage): void => {
        Column() {
          Text(msg.role === 'user' ? '用户' : '助手')
            .fontSize(11).fontColor(INK3).margin({ bottom: 4 })
          Column() {
            Text(this.messagePlainText(msg))
              .fontSize(14).fontColor(msg.role === 'user' ? USER_INK : INK)
              .lineHeight(21)
          }
          .padding(12)
          .backgroundColor(msg.role === 'user' ? USER_BG : SURFACE)
          .borderRadius(12)
          .border({ width: msg.role === 'user' ? 0 : 0.5, color: LINE })
          .constraintSize({ maxWidth: '85%' })
        }
        .width('100%')
        .alignItems(msg.role === 'user' ? HorizontalAlign.End : HorizontalAlign.Start)
        .margin({ bottom: 10 })
      }, (msg: UIMessage): string => msg.id)
    }
    .width(720)
    .padding(20)
    .backgroundColor(BG)
    .id('chat_export_image')
    .position({ x: -2400, y: 0 })
  }

  build(): void {
    if (this.show) {
      Stack() {
        Column() {
          // 标题
          Text('导出格式')
            .fontSize(BODY)
            .fontWeight(FontWeight.Medium)
            .fontColor(INK)
            .width('100%')
            .margin({ bottom: 16 })

          // Markdown 导出卡片
          Row() {
            Icon({ name: 'doc', iconSize: 20, color: INK2 }).margin({ right: 12 })
            Column() {
              Text('Markdown').fontSize(BODY).fontColor(INK).fontWeight(FontWeight.Medium)
              Text('复制为 Markdown 文本到剪贴板').fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
            }
            .alignItems(HorizontalAlign.Start)
            .layoutWeight(1)
          }
          .width('100%')
          .padding(16)
          .backgroundColor(SURFACE2)
          .borderRadius(12)
          .border({ width: 1, color: LINE })
          .onClick((): void => { this.copyMarkdown(); })
          .margin({ bottom: 8 })

          // 图片导出卡片(D7-4:长图 → 相册)
          Column() {
            Row() {
              Icon({ name: 'image', iconSize: 20, color: INK2 }).margin({ right: 12 })
              Column() {
                Text('图片').fontSize(BODY).fontColor(INK).fontWeight(FontWeight.Medium)
                Text(this.messages.length > IMAGE_EXPORT_MAX_MESSAGES
                  ? `导出最近 ${IMAGE_EXPORT_MAX_MESSAGES} 条为长图,存到相册`
                  : '导出为长图,存到相册')
                  .fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
              }
              .alignItems(HorizontalAlign.Start)
              .layoutWeight(1)
            }
            .width('100%')
            .onClick((): void => { this.startImageExport(); })

            Divider().color(LINE).strokeWidth(0.5).margin({ top: 12, bottom: 12 })

            // 展开推理开关
            Row() {
              Text('展开推理过程').fontSize(BODY).fontColor(INK2).layoutWeight(1)
              Text(this.expandReasoning ? '开启' : '关闭')
                .fontSize(LABEL)
                .fontColor(this.expandReasoning ? ACCENT : INK3)
                .onClick((): void => { this.expandReasoning = !this.expandReasoning; })
            }
            .width('100%')
          }
          .width('100%')
          .padding(16)
          .backgroundColor(SURFACE2)
          .borderRadius(12)
          .border({ width: 1, color: LINE })
          .margin({ bottom: 16 })

          // 关闭按钮
          Text('关闭')
            .fontSize(BODY)
            .fontColor(INK3)
            .width('100%')
            .textAlign(TextAlign.Center)
            .padding({ top: 10, bottom: 10 })
            .onClick((): void => { this.onDismiss(); })
        }
        .width('85%')
        .backgroundColor(SURFACE)
        .borderRadius(16)
        .padding({ left: 20, right: 20, top: 24, bottom: 12 })
        if (this.renderImageMode) {
          this.ExportImageRender()
        }
      }
      .width('100%')
      .height('100%')
      .backgroundColor(OVERLAY)
      .alignContent(Alignment.Center)
    }
  }
}
