// ChatMessageActions — 消息操作按钮组件(第二梯队 F)
// Android 基准: ChatMessageActions.kt(416 行)— 分叉/编辑/重生成/复制/删除
// 从 ChatPage MessageRow.build() 内联抽出(line 398-409)
// 设备验证:按钮触摸目标需 ≥44dp(11sp 文字太小,加 padding 扩大)
import { INK3, ACCENT, BODY, META, LABEL } from '../design/tokens.ets';

@Component
export struct ChatMessageActions {
  @Prop isUser: boolean = false;
  @Prop isLastAssistant: boolean = false;
  onFork: () => void = (): void => {};
  onEdit: () => void = (): void => {};
  onRegenerate: () => void = (): void => {};
  onCopy: () => void = (): void => {};
  build(): void {
    Row() {
      Text('复制')
        .fontSize(11).fontColor(INK3)
        .padding({ left: 8, right: 8, top: 8, bottom: 8 })
        .margin({ right: 4 })
        .onClick((): void => this.onCopy())
      Text('分叉')
        .fontSize(11).fontColor(INK3)
        .padding({ left: 8, right: 8, top: 8, bottom: 8 })
        .margin({ right: 4 })
        .onClick((): void => this.onFork())
      if (this.isUser) {
        Text('编辑')
          .fontSize(11).fontColor(INK3)
          .padding({ left: 8, right: 8, top: 8, bottom: 8 })
          .margin({ right: 4 })
          .onClick((): void => this.onEdit())
      }
      if (this.isLastAssistant || this.isUser) {
        Text('重新生成')
          .fontSize(11).fontColor(ACCENT)
          .padding({ left: 8, right: 8, top: 8, bottom: 8 })
          .onClick((): void => this.onRegenerate())
      }
    }
    .width('100%')
    .padding({ top: 4, bottom: 4 })
  }
}
