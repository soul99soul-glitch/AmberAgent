// ChatMessageToolPreviewSheet — 工具详情 sheet 组件(第三梯队)
// Android 基准: ChatMessageToolPreviewSheet.kt(365 行)— 搜索/抓取/通用 JSON 预览
//   ArkUI MVP: 工具名 + input JSON + output 文本展示 + 复制
//   语法高亮/搜索结果结构化/memory 删除 = 后续迭代(依赖 HighlightText/MemoryRepository)
//   待接入:ChatPage 用 bindSheet 套法接入
import type { UIMessagePartTool } from '@amber/chat-domain';
import { toolInputAsJson, toolOutputText } from '@amber/chat-domain';
import { INK, INK2, INK3, ACCENT, SURFACE, SURFACE2, LINE, BODY, META, LABEL } from '../design/tokens.ets';
import { pasteboard } from '@kit.BasicServicesKit';
import { promptAction } from '@kit.ArkUI';

@Component
export struct ChatMessageToolPreviewSheet {
  @Prop part: UIMessagePartTool;
  onDismiss: () => void = (): void => {};

  private copyToClipboard(text: string): void {
    const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
    board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text));
    // 复制反馈(ToolPreviewSheet 不关闭 sheet,用 toast 反馈)
    promptAction.showToast({ message: '已复制', duration: 1500 });
  }

  private getInputJson(): string {
    try {
      return JSON.stringify(toolInputAsJson(this.part), null, 2);
    } catch (_e) {
      return '{}';
    }
  }

  build(): void {
    Column() {
      // 标题行
      Row() {
        Text(`工具详情: ${this.part.toolName}`)
          .fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
        Blank()
        Text('关闭').fontSize(META).fontColor(INK3)
          .onClick((): void => { this.onDismiss(); })
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 12, bottom: 8 })

      Scroll() {
        Column() {
          // Input JSON
          Row() {
            Text('输入参数').fontSize(META).fontColor(INK3).layoutWeight(1)
            Text('复制').fontSize(LABEL).fontColor(ACCENT)
              .onClick((): void => { this.copyToClipboard(this.getInputJson()); })
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 8, bottom: 4 })

          Text(this.getInputJson())
            .fontSize(LABEL).fontColor(INK2)
            .fontFamily('monospace')
            .width('100%')
            .padding(12)
            .backgroundColor(SURFACE2)
            .borderRadius(8)
            .margin({ left: 16, right: 16, bottom: 8 })

          // Output 文本
          Row() {
            Text('输出结果').fontSize(META).fontColor(INK3).layoutWeight(1)
            Text('复制').fontSize(LABEL).fontColor(ACCENT)
              .onClick((): void => { this.copyToClipboard(toolOutputText(this.part)); })
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 8, bottom: 4 })

          Text(toolOutputText(this.part))
            .fontSize(LABEL).fontColor(INK2)
            .fontFamily('monospace')
            .width('100%')
            .padding(12)
            .backgroundColor(SURFACE2)
            .borderRadius(8)
            .margin({ left: 16, right: 16, bottom: 16 })
        }
        .alignItems(HorizontalAlign.Start)
      }
      .layoutWeight(1)
    }
    .width('100%')
    .height('70%')
    .backgroundColor(SURFACE)
    .borderRadius({ topLeft: 16, topRight: 16 })
  }
}
