// ChatExportSheet — 导出格式选择 sheet(G4-S4)
// Android 基准: Export.kt(823 行)
// 必要: Markdown 导出(一键) + 图片导出选项(展开推理开关)
// 降级(跳过): ExportedChatImage bitmap 渲染(componentSnapshot) / FileProvider 分享
//   图片导出降级为"功能入口"(提示需设备支持),Markdown 导出完整实现
import type { UIMessage, Conversation } from '@amber/chat-domain';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, BODY, META, LABEL } from '../design/tokens.ets';
import { Icon } from './ui/Icon.ets';
import { pasteboard } from '@kit.BasicServicesKit';
import { promptAction } from '@kit.ArkUI';

@Component
export struct ChatExportSheet {
  @Prop show: boolean = false;
  @Prop conversation: Conversation | null = null;
  @Prop messages: UIMessage[] = [];
  onDismiss: () => void = (): void => {};
  @State expandReasoning: boolean = false;

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

          // 图片导出卡片(降级:提示)
          Column() {
            Row() {
              Icon({ name: 'image', iconSize: 20, color: INK2 }).margin({ right: 12 })
              Column() {
                Text('图片').fontSize(BODY).fontColor(INK).fontWeight(FontWeight.Medium)
                Text('导出为图片分享(需设备支持)').fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
              }
              .alignItems(HorizontalAlign.Start)
              .layoutWeight(1)
            }
            .width('100%')

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
      }
      .width('100%')
      .height('100%')
      .backgroundColor('rgba(0,0,0,0.4)')
      .alignContent(Alignment.Center)
    }
  }
}
