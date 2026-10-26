import assert from 'node:assert/strict';
import test from 'node:test';
import { makeMemoryRecord } from '../main/ets/chat/memory_models.ts';
import type { MemoryRecord } from '../main/ets/chat/memory_models.ts';
import { appendMemoryRecord, upsertMemoryRecord } from '../main/ets/chat/memory_write.ts';
import { filterActiveMemoryRecords, touchMemoryRecords, reinforceMemoryRecords } from '../main/ets/chat/memory_recall_store.ts';
import { buildMemoryContext } from '../main/ets/chat/memory_prompt_builder.ts';
const day = 86400000;
const now = new Date(2026, 9, 3, 12).getTime();
const record = (opts: Partial<MemoryRecord> = {}): MemoryRecord => makeMemoryRecord({
  id: 1, content: '项目采用第二个部署方案', kind: 'project', scope: 'short_term',
  createdAt: now - 20 * day, updatedAt: now - 20 * day, ...opts,
});

test('recall touch is activity only; independent references reinforce once per turn', () => {
  const touched = touchMemoryRecords([record()], [1], now);
  assert.equal(touched[0].reinforcementCount, 0);
  const first = reinforceMemoryRecords(touched, [1, 1], now, 'turn-1');
  const duplicate = reinforceMemoryRecords(first, [1], now + 1, 'turn-1');
  const second = reinforceMemoryRecords(duplicate, [1], now + 2, 'turn-2');
  assert.equal(first[0].reinforcementCount, 1);
  assert.equal(duplicate[0].reinforcementCount, 1);
  assert.equal(second[0].reinforcementCount, 2);
  assert.equal(second[0].lastReinforcedAt, now + 2);
});

test('invalidated and expired records excluded from direct prompt and recall', () => {
  const records = [record({ invalidatedAt: now }), record({ id: 2, scope: 'core', expiresAt: now }), record({ id: 3, archived: true })];
  assert.deepEqual(filterActiveMemoryRecords(records, ['core', 'short_term'], now), []);
  assert.equal(buildMemoryContext(records, false, {}, now), '');
});

test('time-aware memory context identifies today, record date, expiry, and citation id', () => {
  const expiry = new Date(2026, 9, 9).getTime();
  const prompt = buildMemoryContext([record({ expiresAt: expiry })], false, {}, now);
  assert.ok(prompt.includes('今天是 2026-10-03'));
  assert.ok(prompt.includes('记录日期=2026-09-13'));
  assert.ok(prompt.includes('有效期至=2026-10-09'));
  assert.ok(prompt.includes('[memory:1]'));
});

test('write paths preserve new metadata and old records default safely', () => {
  const old = record();
  assert.equal(old.invalidatedAt, null);
  assert.equal(old.evidence, null);
  const enriched = record({ evidence: '用户原话', reinforcementCount: 3, lastReinforcedAt: now, lastReinforcementSource: 'turn-1', invalidatedAt: now });
  const updated = upsertMemoryRecord([enriched], enriched, now).record;
  const keys: Array<keyof MemoryRecord> = ['evidence', 'reinforcementCount', 'lastReinforcedAt', 'lastReinforcementSource', 'invalidatedAt'];
  for (const key of keys) assert.equal(updated[key], enriched[key]);
  assert.equal(appendMemoryRecord([], { content: old.content, kind: old.kind, scope: old.scope, evidence: '用户原话' }, now).record.evidence, '用户原话');
});

test('expired and invalidated records cannot be reinforced', () => {
  const unavailable = [record({ expiresAt: now }), record({ id: 2, invalidatedAt: now }),
    record({ id: 3, archived: true })];
  assert.deepEqual(reinforceMemoryRecords(unavailable, [1, 2, 3], now, 'turn'), unavailable);
});
