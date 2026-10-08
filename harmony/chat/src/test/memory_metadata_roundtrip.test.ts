import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeMemoryRecord } from '../main/ets/chat/memory_models.ts';
import { encodeMemoryFrontmatter, decodeMemoryFrontmatter } from '../main/ets/chat/memory_frontmatter.ts';

test('export/import keeps temporal history, evidence and independent reinforcement metadata', () => {
  const record = makeMemoryRecord({ id: 7, content: '用户目前不吃素，已更正旧偏好', scope: 'long_term', kind: 'user',
    createdAt: 1700000000000, updatedAt: 1700000000000, supersedesIds: [3], archived: true,
    evidence: '我不吃素了\n以后按新的来', invalidatedAt: 1700000001000,
    reinforcementCount: 2, lastReinforcedAt: 1700000002000, lastReinforcementSource: 'turn-7' });
  const decoded = decodeMemoryFrontmatter(encodeMemoryFrontmatter(record));
  assert.equal(decoded.evidence, record.evidence);
  assert.equal(decoded.invalidatedAt, record.invalidatedAt);
  assert.equal(decoded.reinforcementCount, 2);
  assert.equal(decoded.lastReinforcedAt, record.lastReinforcedAt);
  assert.equal(decoded.lastReinforcementSource, 'turn-7');
  assert.deepEqual(decoded.supersedesIds, [3]);
});

test('derived index includes a current profile with source evidence, drops it after editing source', async () => {
  const { renderMemoryDocuments } = await import('../main/ets/chat/memory_documents.ts');
  const { buildMemoryProfile } = await import('../main/ets/chat/memory_profile.ts');
  const records = [1,2,3].map(id => makeMemoryRecord({id,content: `用户喜欢茶${id}`, kind:'user',scope:'long_term',createdAt:1,updatedAt:1,evidence:'我喜欢茶'}));
  const profile = buildMemoryProfile([{text:'用户喜欢茶1',memoryIds:[1]}],records,100);
  assert.ok(profile);
  const valid = renderMemoryDocuments(records,100,profile);
  assert.match(valid[0].content,/用户画像/);
  assert.match(valid[0].content,/用户原话/);
  const edited = records.map(row => row.id === 1 ? {...row,content:'用户现在喜欢咖啡'} : row);
  assert.doesNotMatch(renderMemoryDocuments(edited,100,profile)[0].content,/## 用户画像/);
});
