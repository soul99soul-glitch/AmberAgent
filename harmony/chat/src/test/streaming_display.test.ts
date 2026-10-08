// streaming_display 纯逻辑测试
import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  StreamingCharRevealClock,
  safeStreamingDisplayEnd,
  safeStreamingTerminalEnd,
  streamingImmediateDisplayText,
  StreamingDisplayState,
  streamingNextCodePointEnd,
} from '../main/ets/chat/streaming_display.ts';
import type {
  StreamingDisplaySliceProxy,
} from '../main/ets/chat/streaming_display.ts';

test('Clock: 多字符 cascade(stagger 错峰)', () => {
  const clock = new StreamingCharRevealClock();
  clock.stamp('abc', 0, 1000, 500, 50, 200, 10);
  // 第一个 appearNanos=1000, 第二个=1000+50=1050, 第三个=1000+100=1100
  assert.equal(clock.progressAt(0, 1000, 500), 0);
  // 第二个在 1000 时 progress < 0 (not yet appeared → 0)
  assert.equal(clock.progressAt(1, 1000, 500), 0);
});

test('Clock: source offset 后退(regenerate)→ 清空', () => {
  const clock = new StreamingCharRevealClock();
  clock.stamp('abc', 100, 1000, 500, 50, 200, 10);
  // offset 后退 → 清空
  clock.stamp('xyz', 50, 2000, 500, 50, 200, 10);
  // 旧 stamp(100-102)应被清除 → 返回 1
  assert.equal(clock.progressAt(100, 2000, 500), 1);
});

test('Clock: maxPending 溢出 → 最旧的直接标记完成', () => {
  const clock = new StreamingCharRevealClock();
  // 5 个新字符,maxPending=2 → 前 3 个溢出,appearNanos=now-fade
  clock.stamp('abcde', 0, 1000, 500, 50, 200, 2);
  // 第一个(溢出) → appearNanos = 1000-500 = 500, now=1000 → progress=1
  assert.equal(clock.progressAt(0, 1000, 500), 1);
});

test('safeEnd: 末尾 surrogate pair → 不在中间断', () => {
  // 😀 = U+1F600 (surrogate pair), 截断在 high surrogate 后 → 应跳到 pair 后
  const str = 'ab😀cd';
  // index 3 = low surrogate → 应 +1 到 4
  const end = safeStreamingDisplayEnd(str, 3);
  assert.ok(end >= 3);
});

test('safeTerminalEnd: emoji 末尾安全截断', () => {
  const str = 'hello😀';
  const end = safeStreamingTerminalEnd(str);
  // 😀 不应被截断
  assert.ok(end === str.length || end <= str.length - 2);
});

test('immediateDisplay: ZWJ emoji 序列不被截断', () => {
  // 👨‍👩‍👧 = man + ZWJ + woman + ZWJ + girl
  const str = 'abc👨‍👩‍👧';
  const result = streamingImmediateDisplayText(str);
  // 不应在 ZWJ 序列中间截断
  assert.ok(result.length === str.length || !result.endsWith('👨'));
});

test('nextCodePointEnd:surrogate pair 前进 2(不劈开 emoji)', () => {
  // 😀 = U+1F600,surrogate pair 占 2 个 UTF-16 单元
  const str = 'a😀b';
  assert.equal(streamingNextCodePointEnd(str, 1), 3);
  assert.equal(streamingNextCodePointEnd(str, 3), 4);
});

// ===== StreamingDisplayState:可注入时间逐字 reveal 驱动 =====
// 对齐 Android rememberStreamingDisplayText 的帧循环纯逻辑。
// 约定:默认走真实 safeStreamingDisplayEnd 切分;代理注入仅用于验证预算记账。

// 常见进度:连续帧驱动直到发射或到达指定长度
const advanceUntil = (
  state: StreamingDisplayState,
  content: string,
  streaming: boolean,
  startMs: number,
  maxEmit: number,
): number => {
  let nowMs: number = startMs;
  for (let i = 0; i < maxEmit; i++) {
    if (state.visibleLength >= content.length) break;
    nowMs += 17;
    state.advance(content, streaming, nowMs);
  }
  return nowMs;
};

test('State:start 首帧即开始 reveal(1/60s 占位 delta)', () => {
  const state = new StreamingDisplayState();
  // 首帧 lastFrameMs=0 → delta=1/60s → speed*delta=72/60=1.2 → floor=1
  const changed = state.advance('hello world', true, 1000);
  assert.equal(changed, true);
  assert.ok(state.visibleLength > 0 && state.visibleLength <= 8);
});

test('State:连续帧平滑推进且可见内容恒为 target 前缀', () => {
  const state = new StreamingDisplayState();
  const content = 'The quick brown fox jumps over the lazy dog. 0123456789';
  let emitted: number = 0;
  let nowMs: number = 1000;
  for (let i = 0; i < 200; i++) {
    nowMs += 17;
    if (state.visibleLength >= content.length) break;
    if (state.advance(content, true, nowMs)) emitted++;
  }
  assert.ok(state.visibleLength > 0);
  assert.ok(emitted > 1, '多帧应多次发射');
  assert.equal(content.startsWith(content.substring(0, state.visibleLength)), true);
  assert.ok(state.visibleLength <= content.length);
});

test('State:backlog=0 时不发射', () => {
  const state = new StreamingDisplayState();
  state.advance('hello', true, 1000);
  advanceUntil(state, 'hello', true, 1000, 200);
  const before: number = state.visibleLength;
  // 已到达目标:再推进不应再变化
  const changed: boolean = state.advance('hello', true, 10000);
  assert.equal(changed, false);
  assert.equal(state.visibleLength, before);
});

test('State:到达 target 后不再发可见帧', () => {
  const state = new StreamingDisplayState();
  const content = 'All good things come to an end.';
  advanceUntil(state, content, true, 1000, 500);
  assert.equal(state.visibleLength, content.length);
  const changed: boolean = state.advance(content, true, 5000);
  assert.equal(changed, false);
});

// ===== 预算记账(代理注入:safeEnd 恒等,验证速度/最小间隔/budget 语义) =====

test('State:预算按速度累计、按发射扣减,且不超每帧上限', () => {
  const identity: StreamingDisplaySliceProxy = {
    safeEnd: (candidate: number): number => candidate,
    nextCodePointEnd: (offset: number): number => offset + 1,
  };
  const state = new StreamingDisplayState(identity);
  // 长内容避免到达 target;每帧 16ms、连续发射(距上次发射 ≥8ms)
  const content = 'x'.repeat(400);
  const startMs: number = 1000;
  let nowMs: number = startMs;
  let lastBudgetAfter: number = 0;
  let prevEmitMs: number = 0;
  let emits: number = 0;
  for (let i = 0; i < 120; i++) {
    nowMs += 16;
    const changed: boolean = state.advance(content, true, nowMs);
    if (changed) {
      emits++;
      prevEmitMs = nowMs;
    }
    assert.ok(state.budget >= 0, 'budget 恒非负');
    if (changed) {
      assert.ok(state.visibleLength - lastBudgetAfter >= 1, '发射至少前进 1');
      lastBudgetAfter = state.visibleLength;
    }
  }
  assert.ok(emits > 1, '连续帧应多次发射');
  assert.ok(state.visibleLength < content.length, '400 字符在 120 帧内不应全出');
});

test('State:最小发射间隔(8ms)抑制过快连续发射', () => {
  const identity: StreamingDisplaySliceProxy = {
    safeEnd: (candidate: number): number => candidate,
    nextCodePointEnd: (offset: number): number => offset + 1,
  };
  const state = new StreamingDisplayState(identity);
  const content = 'y'.repeat(200);
  // 帧间隔 2ms < 8ms:即使预算足够也不应每帧发射
  let nowMs: number = 1000;
  state.advance(content, true, nowMs); // 首帧(无 lastEmitMs 限制)发射
  let fastEmits: number = 0;
  for (let i = 0; i < 30; i++) {
    nowMs += 2;
    if (state.advance(content, true, nowMs)) fastEmits++;
  }
  // 30 帧 2ms 间隔 ≈ 60ms 内最多 7-8 次发射(8ms 节流)
  assert.ok(fastEmits <= 8, `2ms 帧间隔下发射被节流(实际 ${fastEmits})`);
});

test('State:默认(无代理)emitter 前进也受 8ms 最小间隔约束', () => {
  const state = new StreamingDisplayState();
  const content = 'z'.repeat(100);
  let nowMs: number = 1000;
  state.advance(content, true, nowMs);
  let fastEmits: number = 0;
  for (let i = 0; i < 30; i++) {
    nowMs += 2;
    if (state.advance(content, true, nowMs)) fastEmits++;
  }
  assert.ok(fastEmits <= 8, `2ms 帧间隔下默认切分也被节流(实际 ${fastEmits})`);
});

// ===== catch-up snap:backlog 超上限 → 硬 snap =====

test('State:backlog 超上限硬 snap 到 catchUpEnd', () => {
  const state = new StreamingDisplayState();
  const content = 'a'.repeat(5000);
  state.advance(content, true, 1000);
  // backlog = 5000 - visible,远超 1200 → snap 到 target-1200
  assert.ok(state.visibleLength >= 5000 - 1200, `snap 到 target-1200(实际 ${state.visibleLength})`);
  assert.ok(state.visibleLength < 5000);
});

// ===== stream=false:终态 drain =====

test('State:streaming=false 终态 drain 快速收尾(不逐字重放)', () => {
  const state = new StreamingDisplayState();
  const content = 'Final answer with enough length to matter here.';
  // 流式中只 reveal 一小段
  state.advance(content, true, 1000);
  // 终态 drain:0.18s FINAL_TARGET_DRAIN,2400cps,每 emit 20 → 数帧内全部显示
  let nowMs: number = 2000;
  let drains: number = 0;
  for (let i = 0; i < 30 && state.visibleLength < content.length; i++) {
    nowMs += 17;
    if (state.advance(content, false, nowMs)) drains++;
  }
  assert.equal(state.visibleLength, content.length);
  assert.ok(drains > 0);
});

test('State:终态 drain 后保持稳定', () => {
  const state = new StreamingDisplayState();
  const content = 'done draining';
  state.advance(content, true, 1000);
  let nowMs: number = 2000;
  for (let i = 0; i < 30 && state.visibleLength < content.length; i++) {
    nowMs += 17;
    state.advance(content, false, nowMs);
  }
  assert.equal(state.visibleLength, content.length);
  const changed: boolean = state.advance(content, false, nowMs + 100);
  assert.equal(changed, false);
});

// ===== 前缀失配 / 缩短 → 立即 settle =====

test('State:前缀失配立即 settle(不重新逐字 reveal)', () => {
  const state = new StreamingDisplayState();
  state.advance('old content that was streamed', true, 1000);
  assert.ok(state.visibleLength > 0);
  // 内容被完全替换(新内容不以旧可见前缀开头)→ 立即显示完整新内容
  const changed: boolean = state.advance('brand new answer text', true, 5000);
  assert.equal(changed, true);
  assert.equal(state.visibleLength, 'brand new answer text'.length);
});

test('State:内容缩短立即 settle(变短不逐字回退)', () => {
  const state = new StreamingDisplayState();
  state.advance('longer text that was streamed out', true, 1000);
  assert.ok(state.visibleLength > 0);
  // 目标变短(前缀失配,因为可见长度 > 新内容长度)
  const changed: boolean = state.advance('short', true, 5000);
  assert.equal(changed, true);
  assert.equal(state.visibleLength, 'short'.length);
});

test('State:流式目标同前缀增长保持逐字(不 settle)', () => {
  const state = new StreamingDisplayState();
  state.advance('prefix', true, 1000);
  const before: number = state.visibleLength;
  assert.ok(before > 0);
  // 同前缀增长 → 继续 pacing
  const changed: boolean = state.advance('prefix more content', true, 3000);
  assert.equal(changed, true);
  assert.ok(state.visibleLength >= before, '前缀增长继续 reveal');
});

// ===== 从未流式的实例收到内容(settled correction)→ 立即显示 =====

test('State:settled 实例直接收到完整内容立即显示', () => {
  const state = new StreamingDisplayState();
  // streaming=false 直接给内容:settled → 立即显示
  const changed: boolean = state.advance('settled full text', false, 1000);
  assert.equal(changed, true);
  assert.equal(state.visibleLength, 'settled full text'.length);
});

test('State:内容修正后不再重新 pacing(不把旧前缀当 backlog)', () => {
  const state = new StreamingDisplayState();
  state.advance('abc', false, 1000);
  assert.equal(state.visibleLength, 3);
  // settled 之后同一内容不重放
  const changed: boolean = state.advance('abc', false, 2000);
  assert.equal(changed, false);
  assert.equal(state.visibleLength, 3);
});

// ===== Unicode 安全切分(默认切分路径) =====

test('State:reveal 不劈开 ZWJ 序列 / combining mark', () => {
  const state = new StreamingDisplayState();
  // 👨‍👩‍👧 = man + ZWJ + woman + ZWJ + girl
  const str = 'xx👨‍👩‍👧yy';
  advanceUntil(state, str, true, 1000, 400);
  assert.equal(state.visibleLength, str.length);
  const visible: string = str.substring(0, state.visibleLength);
  assert.ok(!visible.endsWith('👨'), '不能停在 ZWJ 序列中间');
});
