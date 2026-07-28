// reasoning_display.test.ts — reasoning 卡片显示纯逻辑(TDD 先行)
//
// Android 基准:
//   ChatMessageReasoning.kt(全文 452 行):
//     显示上限(:69-71)/ReasoningCardState(:77-81)/onExpandedChange(:92-99)/
//     duration 计算(:114-120/:165-172)/toDisplayReasoningText(:398-406)/
//     isReasoningTailTrimmed(:408-411)/reasoningDisplayLimit(:413-424)/
//     budgetLabel(:387-396)/流式始末状态迁移(:122-149)
//   MarkdownUtils.kt extractThinkingTitle(:35-55)
//   strings.xml:260-261 / values-zh:260-261(深度思考/思考了 %1$.1f 秒)
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  REASONING_PREVIEW_CHAR_LIMIT, REASONING_EXPANDED_STREAM_CHAR_LIMIT,
  REASONING_EXPANDED_FINAL_CHAR_LIMIT,
  reasoningDisplayLimit, toDisplayReasoningText, isReasoningTailTrimmed,
  reasoningCardStateExpanded, onReasoningExpandedChange,
  reasoningDurationMs, formatThoughtSeconds, reasoningBudgetLabel,
  extractThinkingTitle, resolveOnStreamStart, resolveOnStreamEnd,
} from '../main/ets/chat/reasoning_display.ts';

describe('reasoningDisplayLimit(:413-424 分支序忠实)', () => {
  it('loading+expanded → 6000;loading → 1600;expanded → 18000;else 1600', () => {
    assert.equal(reasoningDisplayLimit(true, true), REASONING_EXPANDED_STREAM_CHAR_LIMIT);
    assert.equal(reasoningDisplayLimit(true, false), REASONING_PREVIEW_CHAR_LIMIT);
    assert.equal(reasoningDisplayLimit(false, true), REASONING_EXPANDED_FINAL_CHAR_LIMIT);
    assert.equal(reasoningDisplayLimit(false, false), REASONING_PREVIEW_CHAR_LIMIT);
  });
});

describe('toDisplayReasoningText(:398-406)', () => {
  it('未超限 → 原文', () => {
    assert.equal(toDisplayReasoningText('短文本', false, false), '短文本');
  });

  it('超限 → 「… 已省略前 N 字，以保持流式思考界面流畅。」+ takeLast(limit)', () => {
    const text: string = 'x'.repeat(1700);
    const out: string = toDisplayReasoningText(text, false, false);
    assert.equal(out.startsWith('… 已省略前 100 字，以保持流式思考界面流畅。\n\n'), true);
    assert.equal(out.length, '… 已省略前 100 字，以保持流式思考界面流畅。\n\n'.length + 1600);
    assert.ok(out.endsWith('x'.repeat(1600)));
  });

  it('expanded 终态上限 18000(省略文案 N = len - 18000)', () => {
    const text: string = 'y'.repeat(18100);
    const out: string = toDisplayReasoningText(text, false, true);
    assert.ok(out.startsWith('… 已省略前 100 字，以保持流式思考界面流畅。\n\n'));
  });
});

describe('isReasoningTailTrimmed(:408-411)', () => {
  it('length > limit ↔ trimmed', () => {
    assert.equal(isReasoningTailTrimmed('x'.repeat(1600), false, false), false);
    assert.equal(isReasoningTailTrimmed('x'.repeat(1601), false, false), true);
    assert.equal(isReasoningTailTrimmed('x'.repeat(1601), false, true), false);
  });
});

describe('ReasoningCardState(:77-81)+ onExpandedChange(:92-99)', () => {
  it('expanded 标志:preview/expanded = true,collapsed = false', () => {
    assert.equal(reasoningCardStateExpanded('collapsed'), false);
    assert.equal(reasoningCardStateExpanded('preview'), true);
    assert.equal(reasoningCardStateExpanded('expanded'), true);
  });

  it('点击迁移:loading ? (expand→expanded : preview) : (expand→expanded : collapsed)', () => {
    assert.equal(onReasoningExpandedChange(true, true), 'expanded');
    assert.equal(onReasoningExpandedChange(false, true), 'preview');
    assert.equal(onReasoningExpandedChange(true, false), 'expanded');
    assert.equal(onReasoningExpandedChange(false, false), 'collapsed');
  });
});

describe('reasoningDurationMs(:114-120)', () => {
  const created: string = '2026-07-28T10:00:00.000Z';
  const createdMs: number = Date.parse(created);

  it('finishedAt 非空 → finished - created', () => {
    const finished: string = '2026-07-28T10:00:05.400Z';
    assert.equal(reasoningDurationMs(created, finished, false, 0), 5400);
  });

  it('loading 且未结束 → now - created', () => {
    assert.equal(reasoningDurationMs(created, null, true, createdMs + 2300), 2300);
  });

  it('非 loading 且无 finishedAt → 0', () => {
    assert.equal(reasoningDurationMs(created, null, false, createdMs + 9999), 0);
  });
});

describe('formatThoughtSeconds(values-zh:261 逐字)', () => {
  it('一位小数:思考了 5.4 秒', () => {
    assert.equal(formatThoughtSeconds(5.432), '思考了 5.4 秒');
    assert.equal(formatThoughtSeconds(0), '思考了 0.0 秒');
  });
});

describe('reasoningBudgetLabel(:387-396)', () => {
  it('null/off → null;auto → auto;其余 ≤ NK tokens', () => {
    assert.equal(reasoningBudgetLabel(null), null);
    assert.equal(reasoningBudgetLabel('off'), null);
    assert.equal(reasoningBudgetLabel('auto'), 'auto');
    assert.equal(reasoningBudgetLabel('low'), '≤ 1K tokens');
    assert.equal(reasoningBudgetLabel('medium'), '≤ 2K tokens');
    assert.equal(reasoningBudgetLabel('high'), '≤ 8K tokens');
    assert.equal(reasoningBudgetLabel('xhigh'), '≤ 16K tokens');
    assert.equal(reasoningBudgetLabel('max'), '≤ 32K tokens');
  });
});

describe('extractThinkingTitle(MarkdownUtils.kt:35-55)', () => {
  it('最后一行独占加粗 → 取内文', () => {
    assert.equal(extractThinkingTitle('分析中\n**用户意图识别**\n继续'), '用户意图识别');
  });

  it('多个加粗行 → 从后往前取最后匹配', () => {
    assert.equal(extractThinkingTitle('**第一步**\n文本\n**第二步**'), '第二步');
  });

  it('非独占加粗(行内有其他文本)→ 不匹配', () => {
    assert.equal(extractThinkingTitle('前缀 **加粗** 后缀'), null);
  });

  it('内文空白 → null(继续向前找?Android takeUnless 直接返回 null)', () => {
    assert.equal(extractThinkingTitle('**  **'), null);
  });

  it('无匹配 → null', () => {
    assert.equal(extractThinkingTitle('普通文本'), null);
  });
});

describe('流式始末状态迁移(:122-149)', () => {
  it('流式开始:!expanded 且 showThinking → preview;否则不变(null)', () => {
    assert.equal(resolveOnStreamStart(false, true), 'preview');
    assert.equal(resolveOnStreamStart(true, true), null);
    assert.equal(resolveOnStreamStart(false, false), null);
  });

  it('流式结束:sawStreaming → 不变(注释:保持已流式思考稳定)', () => {
    assert.equal(resolveOnStreamEnd(true, true, true), null);
    assert.equal(resolveOnStreamEnd(true, false, true), null);
  });

  it('流式结束(非流式消息):expanded → autoClose ? collapsed : expanded;!expanded → 不变', () => {
    assert.equal(resolveOnStreamEnd(false, true, true), 'collapsed');
    assert.equal(resolveOnStreamEnd(false, true, false), 'expanded');
    assert.equal(resolveOnStreamEnd(false, false, true), null);
    assert.equal(resolveOnStreamEnd(false, false, false), null);
  });
});
