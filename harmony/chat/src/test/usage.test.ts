// TokenUsage.merge 规格测试
// 基准: ai/src/main/java/app/amber/ai/core/Usage.kt
// 规则(STREAMING_SEMANTICS_MATRIX merge_rule/usage):
//   prompt/completion/cached 各自: other > 0 取 other,否则取 this(无 this 取 0)
//   total 重新计算 = prompt + completion(不信 other.totalTokens)

import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { mergeUsage } from '../main/ets/chat/usage.ts';
import type { TokenUsage } from '../main/ets/chat/usage.ts';

const u = (p: number, c: number, cached: number = 0, total: number = 0): TokenUsage => ({
  promptTokens: p,
  completionTokens: c,
  cachedTokens: cached,
  totalTokens: total,
});

test('merge: this 为 null 时全部取 other(>0 字段)', () => {
  const r = mergeUsage(null, u(10, 20, 3));
  assert.deepEqual(r, { promptTokens: 10, completionTokens: 20, cachedTokens: 3, totalTokens: 30 });
});

test('merge: other 字段 >0 覆盖 this', () => {
  const r = mergeUsage(u(1, 2, 1), u(10, 20, 5));
  assert.equal(r.promptTokens, 10);
  assert.equal(r.completionTokens, 20);
  assert.equal(r.cachedTokens, 5);
});

test('merge: other 字段为 0 时保留 this', () => {
  const r = mergeUsage(u(7, 8, 9), u(0, 0, 0));
  assert.equal(r.promptTokens, 7);
  assert.equal(r.completionTokens, 8);
  assert.equal(r.cachedTokens, 9);
});

test('merge: total 重新计算,不信任 other.totalTokens', () => {
  // other.totalTokens=999 是假值,必须被 prompt+completion 覆盖
  const r = mergeUsage(null, u(10, 20, 0, 999));
  assert.equal(r.totalTokens, 30);
});

test('merge: this 为 null 且 other 全 0 → 全 0', () => {
  const r = mergeUsage(null, u(0, 0, 0));
  assert.deepEqual(r, { promptTokens: 0, completionTokens: 0, cachedTokens: 0, totalTokens: 0 });
});
