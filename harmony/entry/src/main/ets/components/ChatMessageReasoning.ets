// ChatMessageReasoning — reasoning 思考卡片组件(阶段 C, audit 1.6)
//
// Android 基准: ChatMessageReasoning.kt(451 行)— collapsed/preview/expanded 三态
// Harmony 后端: reasoning_display.ts(D-053 逐行复刻)
// 从 ChatPage.ets MessageRow.build() 内联抽出(line 339-379)
//
// 三态:
//   collapsed — 仅头部(脑图标+title/秒数+展开箭头)
//   preview — 1600 字尾窗,5 行截断
//   expanded — 18000 字尾窗全文
// 头部可点切换;2dp 左竖线+14px 缩进

import type { UIMessagePartReasoning } from '@amber/chat-domain';
import {
  toDisplayReasoningText, reasoningDurationMs, formatThoughtSeconds,
  extractThinkingTitle,
} from '@amber/chat-domain';
import { INK2, INK3, ACCENT } from '../design/tokens.ets';

@Component
export struct ChatMessageReasoning {
  @Prop part: UIMessagePartReasoning;
  @Prop state: string; // 'collapsed' | 'preview' | 'expanded'
  @Prop loading: boolean; // streaming && finishedAt === null
  onToggle: () => void = (): void => {};

  build(): void {
    Column() {
      Row() {
        Text('🧠').fontSize(12)
        Text(this.headerText())
          .fontSize(12).fontColor(ACCENT)
          .margin({ left: 6 })
          .layoutWeight(1)
        Text(this.state === 'expanded' ? '▾' : '▸')
          .fontSize(11).fontColor(INK3)
      }
      .width('100%')
      .padding({ left: 2, top: 6, bottom: 6 })
      .onClick((): void => { this.onToggle(); })

      if (this.state === 'preview') {
        Text(toDisplayReasoningText(this.part.reasoning, this.loading, false))
          .fontSize(13).fontColor(INK2)
          .maxLines(5)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
          .padding({ left: 14, top: 2, bottom: 2 })
          .border({ width: { left: 2 }, color: { left: '#D8D2C6' } })
      } else if (this.state === 'expanded') {
        Text(toDisplayReasoningText(this.part.reasoning, this.loading, true))
          .fontSize(13).fontColor(INK2)
          .width('100%')
          .padding({ left: 14, top: 2, bottom: 2 })
          .border({ width: { left: 2 }, color: { left: '#D8D2C6' } })
      }
    }
    .width('100%')
    .margin({ bottom: 4 })
  }

  // 与原始 ChatPage:306-317 reasoningHeader 一致 — 流式标题/秒数/默认三态
  // loading 有 title → title;loading 无 title 或 finished → 秒数(build 驱动,非 ticker);
  // 非 loading 且 finishedAt==null → '深度思考'
  private headerText(): string {
    const reasoning: UIMessagePartReasoning = this.part;
    if (this.loading) {
      const title: string | null = extractThinkingTitle(reasoning.reasoning);
      if (title !== null) return title;
    }
    if (reasoning.finishedAt !== null || this.loading) {
      const durationMs: number = reasoningDurationMs(
        reasoning.createdAt, reasoning.finishedAt, this.loading, Date.now());
      if (durationMs > 0) {
        return formatThoughtSeconds(durationMs / 1000);
      }
    }
    return '深度思考';
  }
}
