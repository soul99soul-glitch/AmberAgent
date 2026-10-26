import assert from 'node:assert/strict';
import test from 'node:test';
import { makeMemoryRecord, memoryKindFromWireName } from '../main/ets/chat/memory_models.ts';
import type { MemoryRecord } from '../main/ets/chat/memory_models.ts';
import {
  applyMemoryTopicSuggestions, buildMemoryTopicPrompt, decodeMemoryTopicSuggestions, pruneMemoryTopics,
} from '../main/ets/chat/memory_topics.ts';
import { renderMemoryDocuments } from '../main/ets/chat/memory_documents.ts';
import {
  appendMemoryRecord, deleteMemoryRecord, updateMemoryRecordContent, upsertMemoryRecord,
} from '../main/ets/chat/memory_write.ts';
import { parseMemoryCandidates } from '../main/ets/chat/memory_extractor.ts';

test('saved source → topic/documents → edit invalidation/refresh', async () => {
  const now: number = 1780000000000;
  const sources: MemoryRecord[] = [1, 2, 3].map((id: number): MemoryRecord => makeMemoryRecord({
    id, content: `保留源记忆正文 ${id}`, scope: 'long_term', kind: 'reference',
    sourceConversationId: `conversation-${id}`, sourceMessageIds: [`message-${id}`],
    createdAt: now - 1000, updatedAt: now - 1000,
  }));
  assert.equal(memoryKindFromWireName('topic'), 'topic');
  assert.equal(sources[0].topicTitle, null);
  assert.deepEqual(sources[0].memberIds, []);
  const suggestion = decodeMemoryTopicSuggestions(JSON.stringify({ topics: [{
    title: '读书😀', summary: '保存了读书资料。', memberIds: [1, 2, 2],
  }] }));
  assert.throws(() => decodeMemoryTopicSuggestions('{"topics":"bad"}'));
  assert.throws(() => decodeMemoryTopicSuggestions(JSON.stringify({ topics: [{
    title: '😀'.repeat(13), summary: '不应生成', memberIds: [1, 2],
  }] })));
  assert.throws(() => decodeMemoryTopicSuggestions(JSON.stringify({ topics: [
    { title: 'Reading', summary: '一', memberIds: [1, 2] },
    { title: ' rE aD ing ', summary: '二', memberIds: [2, 3] },
  ] })));
  assert.ok(buildMemoryTopicPrompt(sources, now).includes('conversation-') === false);
  const applied = applyMemoryTopicSuggestions(sources, sources, suggestion, now);
  assert.equal(applied.changed, true);
  assert.deepEqual(applied.records.slice(0, 3), sources);
  const topic: MemoryRecord = applied.records[3];
  assert.equal(topic.kind, 'topic');
  assert.equal(topic.sourceConversationId, null);
  assert.equal(topic.assistantId, '__long_term__');
  assert.deepEqual(topic.memberIds, [1, 2]);
  const persisted: MemoryRecord[] = JSON.parse(JSON.stringify(applied.records)) as MemoryRecord[];
  const idempotent = applyMemoryTopicSuggestions(persisted, persisted, suggestion, now + 1);
  assert.equal(idempotent.changed, false);
  assert.equal(idempotent.records[3].updatedAt, now);
  const docs = renderMemoryDocuments(persisted, now + 1);
  assert.equal(docs[0].relativePath, 'index.md');
  assert.match(docs[1].relativePath, /^topics\/4-[^/]+\.md$/);
  assert.ok(docs[1].content.includes('保留源记忆正文 1'));
  assert.ok(docs[1].content.includes('conversation-1'));
  assert.ok(docs[1].content.includes('message-1'));
  assert.ok(docs[0].content.includes('已归组 2 条，未归组 1 条'));
  assert.throws(() => appendMemoryRecord(persisted, { content: '伪主题', scope: 'long_term', kind: 'topic' }, now));
  assert.throws(() => updateMemoryRecordContent(persisted, topic.id, '篡改', now));
  assert.throws(() => upsertMemoryRecord(persisted, { ...topic, kind: 'note' }, now));
  assert.throws(() => deleteMemoryRecord(persisted, topic.id, now));
  assert.deepEqual(parseMemoryCandidates('{"candidates":[{"content":"伪主题","kind":"topic"}]}', 'c', [], now), []);
  assert.equal(parseMemoryCandidates('{"candidates":[{"content":"伪主题","kind":"topic"},{"content":"正常源记忆","kind":"user"}]}', 'c', [], now).length, 1);

  const edited = updateMemoryRecordContent(persisted, 1, '用户修改后的源正文', now + 10).records;
  assert.equal(edited[3].archived, true);
  assert.deepEqual(edited[3].memberIds, [1, 2]);
  assert.equal(renderMemoryDocuments(edited, now + 10).length, 1);
  const late = applyMemoryTopicSuggestions(edited, persisted, suggestion, now + 11);
  assert.equal(late.staleCount, 1);
  assert.deepEqual(late.topicIds, []);
  const refreshed = applyMemoryTopicSuggestions(edited, edited, [{
    title: '读书😀', summary: '重新概括修改后的资料。', memberIds: [1, 2, 3],
  }], now + 12);
  assert.deepEqual(refreshed.topicIds, [topic.id]);
  assert.deepEqual(refreshed.records[3].memberIds, [1, 2, 3]);
  assert.equal(refreshed.records[3].archived, false);
  const conflict = applyMemoryTopicSuggestions(refreshed.records, refreshed.records, [{
    title: '其他主题', summary: '重分源资料', memberIds: [2, 3],
  }], now + 13);
  assert.equal(conflict.changed, false);
  assert.equal(conflict.rejectedReasons.length, 1);
  const unavailable = pruneMemoryTopics(refreshed.records.map((record: MemoryRecord): MemoryRecord =>
    record.id === 1 ? { ...record, expiresAt: now + 13 } : record), now + 13);
  assert.deepEqual(unavailable.records[3].memberIds, [2, 3]);
  assert.equal(unavailable.records[3].archived, true);
  const deleted = deleteMemoryRecord(refreshed.records, 2, now + 14);
  assert.deepEqual(deleted.find((record: MemoryRecord): boolean => record.id === topic.id)?.memberIds, [1, 3]);
  assert.equal(deleted.find((record: MemoryRecord): boolean => record.id === topic.id)?.archived, true);
  assert.deepEqual(renderMemoryDocuments([], now), []);
});
