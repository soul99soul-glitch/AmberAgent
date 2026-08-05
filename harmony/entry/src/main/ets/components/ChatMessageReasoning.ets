// ChatMessageReasoning — reasoning 思考卡片组件(G4-S2 升级)
//
// Android 基准: ChatMessageReasoning.kt(451 行)
// 升级: 三态完整(collapsed/preview/expanded) + budget 标签 + MarkdownText 渲染内容
//   + duration 实时显示 + preview 限高滚动
// 降级(跳过): shimmer / AnimatedContent 标题切换 / 自动折叠 / 250ms ticker / 主题色变体

import type { UIMessagePartReasoning } from '@amber/chat-domain';
import {
  toDisplayReasoningText, reasoningDurationMs, formatThoughtSeconds,
  extractThinkingTitle,
} from '@amber/chat-domain';
import { INK, INK2, INK3, INK4, ACCENT, SURFACE, SURFACE2, LINE, META } from '../design/tokens.ets';
import { MarkdownText } from './MarkdownText.ets';
import { Icon } from './ui/Icon.ets';

@Component
export struct ChatMessageReasoning {
  @Prop part: UIMessagePartReasoning;
  @Prop state: string; // 'collapsed' | 'preview' | 'expanded'
  @Prop loading: boolean;
  onToggle: () => void = (): void => {};

  build(): void {
    Column() {
      // 头部: 图标 + 标题文本 + 展开箭头
      Row() {
        Icon({ name: 'brain', iconSize: 14, color: ACCENT })
        Text(this.headerText())
          .fontSize(META)
          .fontColor(ACCENT)
          .fontWeight(FontWeight.Medium)
          .fontFamily('JetBrainsMono')
          .margin({ left: 6 })
          .layoutWeight(1)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })

        // duration(右侧)
        if (this.shouldShowDuration()) {
          Text(this.durationLabel())
            .fontSize(11)
            .fontColor(INK3)
            .fontFamily('JetBrainsMono')
            .margin({ right: 6 })
        }

        Text(this.state === 'collapsed' ? '▸' : '▾')
          .fontSize(11)
          .fontColor(INK3)
      }
      .width('100%')
      .padding({ left: 2, top: 6, bottom: 6 })
      .onClick((): void => { this.onToggle(); })

      // 内容(preview / expanded)
      if (this.state === 'preview') {
        // preview: 限高 100px + 滚动 + 渐隐
        Scroll() {
          Column() {
            this.RenderReasoningContent(false)
          }
          .width('100%')
        }
        .width('100%')
        .height(100)
        .padding({ left: 14, top: 2, bottom: 2 })
        .border({ width: { left: 2 }, color: { left: LINE } })
      } else if (this.state === 'expanded') {
        // expanded: 全文
        Column() {
          this.RenderReasoningContent(true)
        }
        .width('100%')
        .padding({ left: 14, top: 2, bottom: 2 })
        .border({ width: { left: 2 }, color: { left: LINE } })
      }
    }
    .width('100%')
    .margin({ bottom: 4 })
  }

  @Builder
  RenderReasoningContent(isExpanded: boolean): void {
    MarkdownText({
      content: toDisplayReasoningText(this.part.reasoning, this.loading, isExpanded),
      fontSize: 13,
      fontColor: INK2,
    })
  }

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
        let label: string = formatThoughtSeconds(durationMs / 1000);
        // budget 标签(如果有)
        const budget: string = this.budgetLabel();
        if (budget.length > 0) label = `${label} · ${budget}`;
        return label;
      }
    }
    const budget: string = this.budgetLabel();
    if (budget.length > 0) return `深度思考 · ${budget}`;
    return '深度思考';
  }

  private budgetLabel(): string {
    // reasoning budget 从 part 元数据获取(如果有 budgetTokens 字段)
    // 简化:暂不提取,返回空(后续从 reasoning_display.ts 接入)
    return '';
  }

  private shouldShowDuration(): boolean {
    if (!this.loading) return false;
    const title: string | null = extractThinkingTitle(this.part.reasoning);
    return title !== null;
  }

  private durationLabel(): string {
    const durationMs: number = reasoningDurationMs(
      this.part.createdAt, this.part.finishedAt, this.loading, Date.now());
    return formatThoughtSeconds(durationMs / 1000);
  }
}
