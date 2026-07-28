// format_number — K/M/B 缩写规格测试(D-110)
//
// Android 基准: core/utils/StringUtils.kt:56-87 / ChatInputUsage.kt:164-168
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { formatNumberInt, formatContextTokens } from '../main/ets/chat/format_number.ts';

test('formatNumberInt: <1000 原样(含负数)', () => {
  assert.equal(formatNumberInt(0), '0');
  assert.equal(formatNumberInt(999), '999');
  assert.equal(formatNumberInt(-999), '-999');
});

test('formatNumberInt: K 档 — 整数无小数,非整 toFixed(1)', () => {
  assert.equal(formatNumberInt(1000), '1K');
  assert.equal(formatNumberInt(1500), '1.5K');
  assert.equal(formatNumberInt(128000), '128K');
  assert.equal(formatNumberInt(-1500), '-1.5K');
  assert.equal(formatNumberInt(999499), '999.5K');
});

test('formatNumberInt: M/B 档', () => {
  assert.equal(formatNumberInt(1000000), '1M');
  assert.equal(formatNumberInt(1500000), '1.5M');
  assert.equal(formatNumberInt(1000000000), '1B');
  assert.equal(formatNumberInt(2500000000), '2.5B');
});

test('formatContextTokens: ≤0 → 0;<1000 → <1K;否则 formatNumber', () => {
  assert.equal(formatContextTokens(0), '0');
  assert.equal(formatContextTokens(-5), '0');
  assert.equal(formatContextTokens(999), '<1K');
  assert.equal(formatContextTokens(1000), '1K');
  assert.equal(formatContextTokens(128000), '128K');
});
