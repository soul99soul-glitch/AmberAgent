// Reasoning display: bounded text and expansion/stream lifecycle transitions.
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  toDisplayReasoningText,
  onReasoningExpandedChange,
  resolveOnStreamStart,
  resolveOnStreamEnd,
} from '../main/ets/chat/reasoning_display.ts';

describe('toDisplayReasoningText(:398-406)', () => {

  it('超限 → 「… 已省略前 N 字，以保持流式思考界面流畅。」+ takeLast(limit)', () => {
    const text: string = 'x'.repeat(1700);
    const out: string = toDisplayReasoningText(text, false, false);
    assert.equal(out.startsWith('… 已省略前 100 字，以保持流式思考界面流畅。\n\n'), true);
    assert.equal(out.length, '… 已省略前 100 字，以保持流式思考界面流畅。\n\n'.length + 1600);
    assert.ok(out.endsWith('x'.repeat(1600)));
  });
});

describe('ReasoningCardState(:77-81)+ onExpandedChange(:92-99)', () => {

  it('点击迁移:loading ? (expand→expanded : preview) : (expand→expanded : collapsed)', () => {
    assert.equal(onReasoningExpandedChange(true, true), 'expanded');
    assert.equal(onReasoningExpandedChange(false, true), 'preview');
    assert.equal(onReasoningExpandedChange(true, false), 'expanded');
    assert.equal(onReasoningExpandedChange(false, false), 'collapsed');
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
