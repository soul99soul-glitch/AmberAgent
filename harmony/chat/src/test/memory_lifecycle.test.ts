import assert from 'node:assert/strict';
import test from 'node:test';
import { makeMemoryRecord } from '../main/ets/chat/memory_models.ts';
import type { MemoryRecord } from '../main/ets/chat/memory_models.ts';
import { appendMemoryRecord, upsertMemoryRecord } from '../main/ets/chat/memory_write.ts';
import { planDreamMaintenance, dreamPlanOnlyApplicable, makeMemoryDreamPlan, runMemoryDreamApply } from '../main/ets/chat/memory_dream.ts';
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

test('short-term promotion requires two real reinforcements after durability period', () => {
  const injected = record({ lastUsedAt: now });
  assert.deepEqual(planDreamMaintenance([injected], [], now).promoteMemoryIds, []);
  const stale = record({ createdAt: now - 40 * day, updatedAt: now - 40 * day });
  assert.deepEqual(planDreamMaintenance([stale], [], now).archiveMemoryIds, [1]);
  const reinforced = record({ reinforcementCount: 2, lastReinforcedAt: now, lastUsedAt: now });
  assert.deepEqual(planDreamMaintenance([reinforced], [], now).promoteMemoryIds, [1]);
  assert.deepEqual(planDreamMaintenance([record({ reinforcementCount: 1, lastReinforcedAt: now })], [], now).promoteMemoryIds, []);
  assert.deepEqual(planDreamMaintenance([record({ reinforcementCount: 2, createdAt: now - day, lastReinforcedAt: now })], [], now).promoteMemoryIds, []);
});

test('model Dream cannot promote expired, archived, or unreinforced records', () => {
  const records = [record(), record({ id: 2, reinforcementCount: 3, lastReinforcedAt: now, expiresAt: now }), record({ id: 3, reinforcementCount: 2, lastReinforcedAt: now, archived: true })];
  assert.deepEqual(dreamPlanOnlyApplicable(makeMemoryDreamPlan({ promoteMemoryIds: [1, 2, 3] }), records, now).promoteMemoryIds, []);
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

test('exact maintenance cannot merge core, pinned, different scope/kind or long prefix collisions', () => {
  const content = '同一条重要的用户偏好';
  const safe = record({ content, kind: 'user' });
  const protectedRows = [safe, record({ id: 2, content, pinned: true }),
    record({ id: 3, content, scope: 'core' }), record({ id: 4, content, scope: 'long_term', kind: 'user' }),
    record({ id: 5, content, kind: 'feedback' })];
  assert.deepEqual(planDreamMaintenance(protectedRows, [], now).mergeSuggestions, []);
  const prefix = '项目内容'.repeat(60);
  assert.deepEqual(planDreamMaintenance([record({ content: prefix + 'A' }),
    record({ id: 2, content: prefix + 'B' })], [], now).mergeSuggestions, []);
  assert.equal(planDreamMaintenance([safe, record({ id: 6, content, kind: 'user' })], [], now).mergeSuggestions.length, 1);
});

test('expired all scopes are archived; short-term idle rule respects edit, retrieval and future expiry', () => {
  const expired = [record({ expiresAt: now }), record({ id: 2, scope: 'long_term', expiresAt: now }),
    record({ id: 3, scope: 'core', expiresAt: now, pinned: true })];
  assert.deepEqual(planDreamMaintenance(expired, [], now).archiveMemoryIds, [1, 2, 3]);
  const old = { createdAt: now - 40 * day, updatedAt: now - 40 * day };
  const active = [record({ ...old, updatedAt: now }), record({ ...old, id: 2, lastUsedAt: now }),
    record({ ...old, id: 3, expiresAt: now + day }), record({ ...old, id: 4, pinned: true })];
  assert.deepEqual(planDreamMaintenance(active, [], now).archiveMemoryIds, []);
});

test('expired and invalidated records cannot be reinforced', () => {
  const unavailable = [record({ expiresAt: now }), record({ id: 2, invalidatedAt: now }),
    record({ id: 3, archived: true })];
  assert.deepEqual(reinforceMemoryRecords(unavailable, [1, 2, 3], now, 'turn'), unavailable);
});

test('Dream emits one lifecycle action for a record when model requests promote and archive together', () => {
  const reinforced = record({ reinforcementCount: 2, lastReinforcedAt: now });
  const plan = dreamPlanOnlyApplicable(makeMemoryDreamPlan({ promoteMemoryIds: [1], archiveMemoryIds: [1] }), [reinforced], now);
  assert.deepEqual(plan.promoteMemoryIds, [1]);
  assert.deepEqual(plan.archiveMemoryIds, []);
});

test('restored versions are excluded from both exact maintenance and model merges', () => {
  const rows = [record({ supersedesIds: [2] }), record({ id: 2 })];
  assert.deepEqual(planDreamMaintenance(rows, [], now).mergeSuggestions, []);
  assert.deepEqual(dreamPlanOnlyApplicable(makeMemoryDreamPlan({ mergeSuggestions: [{
    targetMemoryId: 1, duplicateMemoryIds: [2], mergedContent: null, reason: '',
  }] }), rows, now).mergeSuggestions, []);
});

test('exact merge saves superseded ids so restoring a duplicate prevents repeat archival', async () => {
  let rows = [record(), record({ id: 2, supersedesIds: [9] })];
  await runMemoryDreamApply(makeMemoryDreamPlan({ mergeSuggestions: [{
    targetMemoryId: 1, duplicateMemoryIds: [2], mergedContent: null, reason: '',
  }] }), {
    getAllRecords: async () => rows, getAllCandidates: async () => [],
    upsertRecord: async (updated) => { rows = rows.map(r => r.id === updated.id ? updated : r); return updated; },
    addMemory: async () => { throw new Error('unexpected add'); },
    updateCandidate: async () => {}, addEvent: async () => {}, now: () => now,
  });
  assert.deepEqual(rows[0].supersedesIds, [2, 9]);
  rows = rows.map(row => row.id === 2 ? { ...row, archived: false } : row);
  assert.deepEqual(planDreamMaintenance(rows, [], now).mergeSuggestions, []);
});

test('exact maintenance preserves punctuation and case that distinguish technical facts', () => {
  const rows = [record({ content: '我主要使用 C++ 开发后端服务' }),
    record({ id: 2, content: '我主要使用 C 开发后端服务' }),
    record({ id: 3, content: 'CLI 配置中的 TOKEN 是环境变量' }),
    record({ id: 4, content: 'CLI 配置中的 token 是环境变量' })];
  assert.deepEqual(planDreamMaintenance(rows, [], now).mergeSuggestions, []);
});
