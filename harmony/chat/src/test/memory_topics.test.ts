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
import { decodeMemoryFrontmatter, encodeMemoryFrontmatter } from '../main/ets/chat/memory_frontmatter.ts';
import { parseMemoryCandidates } from '../main/ets/chat/memory_extractor.ts';
import {
  dreamPlanOnlyApplicable, parseDreamModelPlanJson, planDreamMaintenance,
} from '../main/ets/chat/memory_dream.ts';
import { exportMemoriesTo, importMemoriesFrom } from '../main/ets/chat/memory_import_export.ts';
import type { MemoryImportExportDeps } from '../main/ets/chat/memory_import_export.ts';

test('saved source → topic/documents → edit invalidation/refresh → source-only roundtrip', async () => {
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
  assert.throws(() => encodeMemoryFrontmatter(topic));
  assert.throws(() => decodeMemoryFrontmatter('---\nkind: "topic"\n---\n派生摘要'));
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
  const embedded = [{ ...sources[0], content: encodeMemoryFrontmatter(sources[0]) }, sources[1]];
  assert.throws(() => decodeMemoryFrontmatter(renderMemoryDocuments(embedded, now)[0].content));

  const maliciousPlan = parseDreamModelPlanJson(JSON.stringify({
    merge: [{ target_memory_id: topic.id, duplicate_memory_ids: [1], merged_content: '错误合并主题到源' }],
    archive: [topic.id], supersede: [{ old_memory_ids: [1], new_content: '不能生成一个派生主题', kind: 'topic' }],
  }), persisted, []);
  assert.deepEqual(maliciousPlan.mergeSuggestions, []);
  assert.deepEqual(maliciousPlan.archiveMemoryIds, []);
  assert.deepEqual(maliciousPlan.supersedeSuggestions, []);
  const maintenance = dreamPlanOnlyApplicable(planDreamMaintenance([
    ...sources, { ...topic, content: sources[0].content },
  ], [], now), persisted);
  assert.deepEqual(maintenance.mergeSuggestions, []);

  const files: Map<string, string> = new Map<string, string>();
  let roundtripRecords: MemoryRecord[] = [];
  const deps: MemoryImportExportDeps = {
    fs: {
      exists: async (path: string): Promise<boolean> => files.has(path)
        || [...files.keys()].some((key: string): boolean => key.startsWith(path + '/')),
      mkdirs: async (): Promise<void> => {},
      writeText: async (path: string, text: string): Promise<void> => { files.set(path, text); },
      readText: async (path: string): Promise<string> => { const text = files.get(path); if (text === undefined) throw new Error(path); return text; },
      walkFiles: async (path: string): Promise<string[]> => [...files.keys()].filter((key: string): boolean => key.startsWith(path + '/')),
    },
    getAllRecords: async (): Promise<MemoryRecord[]> => roundtripRecords.length > 0 ? roundtripRecords : persisted,
    getRecentEvents: async () => [],
    upsertRecord: async (record: MemoryRecord): Promise<MemoryRecord> => {
      const result = upsertMemoryRecord(roundtripRecords, record, now);
      roundtripRecords = result.records;
      return result.record;
    },
    now: (): number => now,
  };
  const exported = await exportMemoriesTo('workspace/export', deps);
  assert.equal(exported.memoryCount, 3);
  assert.equal(files.size > 0, true);
  assert.equal([...files.keys()].some((path: string): boolean => path.includes('/topic/')), false);
  assert.equal(JSON.parse(files.get(exported.root + '/manifest.json') ?? '{}').count, '3');
  const importDeps: MemoryImportExportDeps = { ...deps, getAllRecords: async (): Promise<MemoryRecord[]> => roundtripRecords };
  const imported = await importMemoriesFrom(exported.root, importDeps);
  assert.equal(imported.importedCount, 3);
  assert.deepEqual(roundtripRecords.map((record: MemoryRecord): string => record.content), sources.map((record: MemoryRecord): string => record.content));
  assert.ok(roundtripRecords.every((record: MemoryRecord): boolean => record.topicTitle === null && record.memberIds.length === 0));
});
