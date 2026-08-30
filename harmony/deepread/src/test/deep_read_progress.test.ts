// deepReadProgressSnapshot / isCacheEntryExpired 单测(照搬 Android DeepReadProgress 行为)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { deepReadProgressSnapshot, isCacheEntryExpired } from '../main/ets/platform/deep_read_progress.ts';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';
import type { DeepReadOutput } from '../main/ets/domain/models.ts';
import type { DeepReadCacheEntry } from '../main/ets/platform/repository.ts';

const entry = (overrides: Partial<DeepReadCacheEntry>): DeepReadCacheEntry => ({
  topicId: 't1', title: 'T', sourceUrl: null,
  output: makeEmptyDeepReadOutput(), phase: 'IDLE', attemptCount: 0, lastError: null,
  createdAt: 0, updatedAt: 0, expiresAt: 0, ...overrides,
});

test('快照: null output — running=6 正在准备 / 非运行=0 未开始', () => {
  assert.equal(deepReadProgressSnapshot(null, true).percent, 6);
  assert.equal(deepReadProgressSnapshot(null, true).label, '正在准备');
  assert.equal(deepReadProgressSnapshot(null, false).percent, 0);
});

test('快照: phase 逐态映射', () => {
  const base = makeEmptyDeepReadOutput();
  assert.equal(deepReadProgressSnapshot({ ...base, generationPhase: 'COLLECTING' }, true).percent, 10);
  assert.equal(deepReadProgressSnapshot({ ...base, generationPhase: 'PLANNING' }, true).percent, 24);
  assert.equal(deepReadProgressSnapshot({ ...base, generationPhase: 'VERIFYING' }, true).percent, 96);
  assert.equal(deepReadProgressSnapshot({ ...base, generationPhase: 'VERIFYING' }, true).label, '正在补漏');
  assert.equal(deepReadProgressSnapshot({ ...base, generationPhase: 'COMPLETE' }, false).percent, 100);
});

test('快照: WRITING 按段 RUNNING 取段中值', () => {
  const base = makeEmptyDeepReadOutput();
  const writing = (stage: string): DeepReadOutput => ({
    ...base, generationPhase: 'WRITING' as never,
    sectionStates: { [stage]: { status: 'RUNNING', errorMessage: null } },
  } as DeepReadOutput);
  assert.equal(deepReadProgressSnapshot(writing('OVERVIEW'), true).percent, 44);
  assert.equal(deepReadProgressSnapshot(writing('NARRATIVE'), true).percent, 66);
  assert.equal(deepReadProgressSnapshot(writing('ANALYSIS'), true).percent, 88);
  assert.equal(deepReadProgressSnapshot(writing('EXTENDED_READING'), true).percent, 98);
});

test('快照: 全段 READY 未 COMPLETE → 94 正在收尾', () => {
  const base = makeEmptyDeepReadOutput();
  const states = Object.fromEntries(
    ['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING'].map((s) =>
      [s, { status: 'READY', errorMessage: null }]),
  );
  const out = { ...base, sectionStates: states } as DeepReadOutput;
  assert.equal(deepReadProgressSnapshot(out, true).percent, 94);
  assert.equal(deepReadProgressSnapshot(out, true).label, '正在收尾');
});

test('expired: expiresAt 权威,缺省按 createdAt+24h 推;边界 23h/25h', () => {
  const now = Date.now();
  assert.equal(isCacheEntryExpired(entry({ expiresAt: now - 1 }), now), true);
  assert.equal(isCacheEntryExpired(entry({ expiresAt: now + 1 }), now), false);
  const born = now - 25 * 3600_000;
  assert.equal(isCacheEntryExpired(entry({ createdAt: born, expiresAt: 0 }), now), true);
  const young = now - 23 * 3600_000;
  assert.equal(isCacheEntryExpired(entry({ createdAt: young, expiresAt: 0 }), now), false);
  assert.equal(isCacheEntryExpired(null, now), false);
});
