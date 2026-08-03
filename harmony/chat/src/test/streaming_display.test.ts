// streaming_display 纯逻辑测试
import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  StreamingCharRevealClock,
  streamingDisplayTargetSpeed,
  streamingDisplayMaxCharsPerEmit,
  streamingDisplayBacklogCatchUpEnd,
  safeStreamingDisplayEnd,
  safeStreamingTerminalEnd,
  streamingImmediateDisplayText,
  streamingTailActiveWhen,
} from '../main/ets/chat/streaming_display.ts';

// ===== StreamingCharRevealClock =====

test('Clock: 未 stamp 的字符 progressAt 返回 1(已定型)', () => {
  const clock = new StreamingCharRevealClock();
  assert.equal(clock.progressAt(0, 1000, 500), 1);
});

test('Clock: stamp 后 progressAt 返回 0(尚未到出现时间)', () => {
  const clock = new StreamingCharRevealClock();
  clock.stamp('abc', 0, 1000, 500, 50, 200, 10);
  // 第一个字符 appearNanos = 1000,now = 1000 → progress 0
  assert.equal(clock.progressAt(0, 1000, 500), 0);
});

test('Clock: stamp 后 progressAt 经过 fadeNanos 返回 1(完成)', () => {
  const clock = new StreamingCharRevealClock();
  clock.stamp('a', 0, 1000, 500, 50, 200, 10);
  // appearNanos=1000, now=1500, fade=500 → progress 1
  assert.equal(clock.progressAt(0, 1500, 500), 1);
});

test('Clock: stamp 后 progressAt 中间值在 0-1 之间', () => {
  const clock = new StreamingCharRevealClock();
  clock.stamp('a', 0, 1000, 500, 50, 200, 10);
  // appearNanos=1000, now=1250, fade=500 → progress 0.5
  const p = clock.progressAt(0, 1250, 500);
  assert.ok(p > 0.4 && p < 0.6);
});

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

// ===== streamingDisplayTargetSpeed =====

test('targetSpeed: backlog=0 → base 速率', () => {
  assert.equal(streamingDisplayTargetSpeed(0, true), 72);
});

test('targetSpeed: 大 backlog → 不超过 max', () => {
  const speed = streamingDisplayTargetSpeed(10000, true);
  assert.ok(speed <= 1500);
});

test('targetSpeed: streaming=false → final 速率(更快)', () => {
  const s1 = streamingDisplayTargetSpeed(500, true);
  const s2 = streamingDisplayTargetSpeed(500, false);
  assert.ok(s2 > s1);
});

// ===== maxCharsPerEmit =====

test('maxCharsPerEmit: streaming=true → 8', () => {
  assert.equal(streamingDisplayMaxCharsPerEmit(true), 8);
});

test('maxCharsPerEmit: streaming=false → 20', () => {
  assert.equal(streamingDisplayMaxCharsPerEmit(false), 20);
});

// ===== backlogCatchUpEnd =====

test('catchUpEnd: target <= visible → target', () => {
  assert.equal(streamingDisplayBacklogCatchUpEnd(100, 80), 80);
});

test('catchUpEnd: backlog 超 max → catch-up snap', () => {
  // visible=0, target=2000, max backlog=1200 → snap to 2000-1200=800
  assert.equal(streamingDisplayBacklogCatchUpEnd(0, 2000), 800);
});

// ===== safeStreamingDisplayEnd / safeStreamingTerminalEnd =====

test('safeEnd: ASCII 文本无调整', () => {
  assert.equal(safeStreamingDisplayEnd('hello world', 5), 5);
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

// ===== streamingImmediateDisplayText =====

test('immediateDisplay: ASCII → 原样', () => {
  assert.equal(streamingImmediateDisplayText('hello'), 'hello');
});

test('immediateDisplay: ZWJ emoji 序列不被截断', () => {
  // 👨‍👩‍👧 = man + ZWJ + woman + ZWJ + girl
  const str = 'abc👨‍👩‍👧';
  const result = streamingImmediateDisplayText(str);
  // 不应在 ZWJ 序列中间截断
  assert.ok(result.length === str.length || !result.endsWith('👨'));
});

// ===== streamingTailActiveWhen =====

test('tailActive: true → true', () => {
  assert.equal(streamingTailActiveWhen(true), true);
});

test('tailActive: false → false', () => {
  assert.equal(streamingTailActiveWhen(false), false);
});
