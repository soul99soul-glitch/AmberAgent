// ChatMessageActions — 消息操作按钮组件(第二梯队 F)
// Android 基准: ChatMessageActions.kt(416 行)— 分叉/编辑/重生成/复制/删除
// 从 ChatPage MessageRow.build() 内联抽出(line 398-409)
import { INK3, ACCENT } from '../design/tokens.ets';

@Component
export struct ChatMessageActions {
  @Prop isUser: boolean = false;
  @Prop isLastAssistant: boolean = false;
  onFork: () => void = (): void => {};
  onEdit: () => void = (): void => {};
  onRegenerate: () => void = (): void => {};
  build(): void {
    Row() {
      Text('分叉').fontSize(11).fontColor(INK3)
        .margin({ right: 12 })
        .onClick((): void => this.onFork())
      if (this.isUser) {
        Text('编辑').fontSize(11).fontColor(INK3)
          .margin({ right: 12 })
          .onClick((): void => this.onEdit())
      }
      if (this.isLastAssistant || this.isUser) {
        Text('重新生成').fontSize(11).fontColor(ACCENT)
          .onClick((): void => this.onRegenerate())
      }
    }
  }
}
