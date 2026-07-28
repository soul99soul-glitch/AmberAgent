// reasoning_display.ts — reasoning 卡片显示纯逻辑(折叠/预览/展开)
//
// Android 基准:
//   ChatMessageReasoning.kt(全文 452 行):
//     显示上限(:69-71)/ReasoningCardState(:77-81)/onExpandedChange(:92-99)/
//     duration(:114-120 + 250ms ticker :165-172 — ticker 属 UI 层)/
//     toDisplayReasoningText(:398-406)/isReasoningTailTrimmed(:408-411)/
//     reasoningDisplayLimit(:413-424)/budgetLabel(:387-396)/
//     流式始末状态迁移(:122-149)
//   MarkdownUtils.kt extractThinkingTitle(:35-55)
//   strings.xml:260-261 / values-zh:260-261
//
// 裁剪登记:
//   - Compose LaunchedEffect 生命周期/250ms ticker/300ms 自动折叠延迟/
//     scrollTo 尾随 → 由 ArkUI 页面层以定时器等价实现(非域逻辑)
//   - shimmer/fade 渐变/MarkdownBlock 渲染 → 视觉层,设备验证 Spike C 项

import type { ReasoningLevel } from './provider_model.ts';
import { reasoningLevelBudgetTokens } from './provider_model.ts';

// ChatMessageReasoning.kt:69-71
export const REASONING_PREVIEW_CHAR_LIMIT: number = 1600;
export const REASONING_EXPANDED_STREAM_CHAR_LIMIT: number = 6000;
export const REASONING_EXPANDED_FINAL_CHAR_LIMIT: number = 18000;

// ChatMessageReasoning.kt:77-81(expanded 标志经 reasoningCardStateExpanded 取)
export type ReasoningCardState = 'collapsed' | 'preview' | 'expanded';

export const reasoningCardStateExpanded = (s: ReasoningCardState): boolean =>
  s !== 'collapsed';

// ChatMessageReasoning.kt:92-99
export const onReasoningExpandedChange = (
  nextExpanded: boolean, loading: boolean,
): ReasoningCardState => {
  if (loading) return nextExpanded ? 'expanded' : 'preview';
  return nextExpanded ? 'expanded' : 'collapsed';
};

// ChatMessageReasoning.kt:413-424(分支序忠实:loading+expanded → loading → expanded → else)
export const reasoningDisplayLimit = (loading: boolean, expanded: boolean): number => {
  if (loading && expanded) return REASONING_EXPANDED_STREAM_CHAR_LIMIT;
  if (loading) return REASONING_PREVIEW_CHAR_LIMIT;
  if (expanded) return REASONING_EXPANDED_FINAL_CHAR_LIMIT;
  return REASONING_PREVIEW_CHAR_LIMIT;
};

// ChatMessageReasoning.kt:398-406(省略文案逐字)
export const toDisplayReasoningText = (
  text: string, loading: boolean, expanded: boolean,
): string => {
  const limit: number = reasoningDisplayLimit(loading, expanded);
  if (text.length <= limit) return text;
  const omitted: number = text.length - limit;
  return `… 已省略前 ${omitted} 字，以保持流式思考界面流畅。\n\n${text.slice(-limit)}`;
};

// ChatMessageReasoning.kt:408-411
export const isReasoningTailTrimmed = (
  text: string, loading: boolean, expanded: boolean,
): boolean => text.length > reasoningDisplayLimit(loading, expanded);

// ChatMessageReasoning.kt:114-120(ISO 字符串 → ms;finishedAt/loading/else 三分支)
export const reasoningDurationMs = (
  createdAtIso: string, finishedAtIso: string | null,
  loading: boolean, nowMs: number,
): number => {
  const createdMs: number = Date.parse(createdAtIso);
  if (finishedAtIso !== null) return Date.parse(finishedAtIso) - createdMs;
  if (loading) return nowMs - createdMs;
  return 0;
};

// values-zh:261 逐字「思考了 %1$.1f 秒」(toDouble(SECONDS).toFloat() → 一位小数)
export const formatThoughtSeconds = (seconds: number): string =>
  `思考了 ${seconds.toFixed(1)} 秒`;

// ChatMessageReasoning.kt:387-396(≥1000 → NK,否则原数;auto/off/null 特判)
export const reasoningBudgetLabel = (level: ReasoningLevel | null): string | null => {
  if (level === null || level === 'off') return null;
  if (level === 'auto') return 'auto';
  const budget: number = reasoningLevelBudgetTokens(level);
  const formatted: string = budget >= 1000 ? `${Math.floor(budget / 1000)}K` : `${budget}`;
  return `≤ ${formatted} tokens`;
};

// MarkdownUtils.kt:35-55 — 从后往前首个独占加粗行;内文 trim 后空白 → null(不继续找)
export const extractThinkingTitle = (text: string): string | null => {
  const lines: string[] = text.split('\n');
  const boldPattern: RegExp = /^\*\*(.+?)\*\*$/;
  for (let i = lines.length - 1; i >= 0; i--) {
    const m: RegExpMatchArray | null = lines[i].trim().match(boldPattern);
    if (m !== null) {
      const inner: string = m[1].trim();
      return inner.length === 0 ? null : inner;
    }
  }
  return null;
};

// ===== 流式始末状态迁移(:122-149 LaunchedEffect 语义的纯函数化) =====
// 返回 null = 状态不变(Android 分支无赋值)

// 流式开始:!expanded 且 showThinkingContent → preview(:125-126)
export const resolveOnStreamStart = (
  expanded: boolean, showThinkingContent: boolean,
): ReasoningCardState | null => {
  if (!expanded && showThinkingContent) return 'preview';
  return null;
};

// 流式结束(:127-147):sawStreaming → 不变(保持已流式思考稳定,:129-133);
//   否则 expanded → autoCloseThinking ? collapsed : expanded
export const resolveOnStreamEnd = (
  sawStreaming: boolean, expanded: boolean, autoCloseThinking: boolean,
): ReasoningCardState | null => {
  if (sawStreaming) return null;
  if (expanded) return autoCloseThinking ? 'collapsed' : 'expanded';
  return null;
};
