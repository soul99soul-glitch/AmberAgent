// ChatMessageTools — 工具调用卡片组件(阶段 C, audit 1.7)
//
// Android 基准: ChatMessageTools.kt(985 行)— 工具胶囊+审批+output 预览
// Harmony 后端: tool_activity.ts(D-122)+ tool_approval.ts(D-057)+ message.ts
// 从 ChatPage.ets MessageRow.build() 内联抽出(line 380-548)
//
// 功能:
//   - 工具名+状态标签
//   - pending → 批准/拒绝按钮
//   - denied → 拒绝原因
//   - 已执行 → 首条 output 文本 3 行预览(展开折叠 = 后续迭代)

import type { UIMessagePartTool, UIMessagePartText } from '@amber/chat-domain';
import { INK, INK2, INK3, ACCENT, SURFACE2, LINE } from '../design/tokens.ets';

@Component
export struct ChatMessageTools {
  @Prop part: UIMessagePartTool;
  onApprove: (toolCallId: string) => void = (): void => {};
  onDeny: (toolCallId: string) => void = (): void => {};
  deniedLabel: string = '';

  build(): void {
    Column() {
      Row() {
        Text(`工具:${this.part.toolName}`)
          .fontSize(11).fontColor(ACCENT)
          .layoutWeight(1)
        Text(this.stateLabel())
          .fontSize(10).fontColor(INK3)
      }
      .width('100%')

      // pending → 批准/拒绝按钮
      if (this.part.approvalState.type === 'pending') {
        Row() {
          Text('批准')
            .fontSize(12).fontColor(ACCENT)
            .padding({ left: 12, right: 12, top: 4, bottom: 4 })
            .onClick((): void => { this.onApprove(this.part.toolCallId); })
          Text('拒绝')
            .fontSize(12).fontColor(INK2)
            .padding({ left: 12, right: 12, top: 4, bottom: 4 })
            .margin({ left: 8 })
            .onClick((): void => { this.onDeny(this.part.toolCallId); })
        }
        .margin({ top: 4 })
      }

      // denied → 拒绝原因
      if (this.part.approvalState.type === 'denied' && this.deniedLabel.length > 0) {
        Text(this.deniedLabel)
          .fontSize(11).fontColor('#B43A3A')
          .width('100%').margin({ top: 4 })
      }

      // output 预览:首条 text part,3 行截断
      if (this.part.output.length > 0 && this.part.output[0].type === 'text') {
        Text((this.part.output[0] as UIMessagePartText).text)
          .fontSize(11).fontColor(INK2)
          .maxLines(3)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
          .margin({ top: 4 })
      }
    }
    .width('100%')
    .padding({ left: 8, right: 8, top: 6, bottom: 6 })
    .backgroundColor(SURFACE2)
    .borderRadius(6)
    .margin({ bottom: 4 })
  }

  // 与原始 ChatPage:192-199 toolStateLabel 一致 — output 优先判定
  private stateLabel(): string {
    if (this.part.output.length > 0) return '已执行';
    const state: string = this.part.approvalState.type;
    if (state === 'pending') return '待审批';
    if (state === 'approved') return '已批准';
    if (state === 'denied') return '已拒绝';
    if (state === 'answered') return '已应答';
    return '';
  }
}
