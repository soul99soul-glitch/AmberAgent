import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeMemoryRecord } from '../main/ets/chat/memory_models.ts';
import { restoreMemoryRecord } from '../main/ets/chat/memory_restore.ts';

test('restore expired archived version clears invalidation and expiry, keeps evidence and history', () => {
  const row = makeMemoryRecord({id: 1, content: '用户喜欢茶', scope: 'long_term', kind: 'user',
    archived: true, expiresAt: 1, invalidatedAt: 2, evidence: '我喜欢茶', supersedesIds: [8]});
  const next = restoreMemoryRecord([row], 1, 100);
  assert.equal(next[0].archived, false);
  assert.equal(next[0].expiresAt, null);
  assert.equal(next[0].invalidatedAt, null);
  assert.equal(next[0].evidence, '我喜欢茶');
  assert.deepEqual(next[0].supersedesIds, [8]);
  assert.equal(row.archived, true);
});
test('restore keeps a future expiry and rejects topic mutation', () => {
  const row = makeMemoryRecord({id: 1, content: '出差计划', scope: 'short_term', kind: 'project', archived: true, expiresAt: 200});
  assert.equal(restoreMemoryRecord([row], 1, 100)[0].expiresAt, 200);
  assert.throws(() => restoreMemoryRecord([{...row, kind: 'topic'}], 1, 100), /主题/);
  assert.throws(() => restoreMemoryRecord([], 1, 100), /not found/);
});
