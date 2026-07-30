// ChatMessageBranch — 分支选择器组件(第二梯队 F)
// Android 基准: ChatMessageBranch.kt(93 行)— ◀ n/total ▶ 分支切换
// 从 ChatPage MessageRow.build() 内联抽出(line 384-396)
import { INK3, ACCENT } from '../design/tokens.ets';

@Component
export struct ChatMessageBranch {
  @Prop branchCount: number = 1;
  @Prop selectIndex: number = 0;
  onSwitch: (index: number) => void = (_i: number): void => {};
  build(): void {
    if (this.branchCount > 1) {
      Row() {
        Text('◀').fontSize(11).fontColor(this.selectIndex > 0 ? ACCENT : INK3)
          .onClick((): void => {
            if (this.selectIndex > 0) this.onSwitch(this.selectIndex - 1);
          })
        Text(`${this.selectIndex + 1}/${this.branchCount}`)
          .fontSize(10).fontColor(INK3).margin({ left: 6, right: 6 })
        Text('▶').fontSize(11)
          .fontColor(this.selectIndex < this.branchCount - 1 ? ACCENT : INK3)
          .margin({ right: 12 })
          .onClick((): void => {
            if (this.selectIndex < this.branchCount - 1) this.onSwitch(this.selectIndex + 1);
          })
      }
    }
  }
}
