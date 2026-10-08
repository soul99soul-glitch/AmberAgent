// memory_recall_store 编排测试 — MemoryRecallStore.kt:14-53 语义锚定
import assert from 'node:assert/strict';
import test from 'node:test';
import { makeUIMessage } from '../main/ets/chat/message.ts';
import type { UIMessage } from '../main/ets/chat/message.ts';
import type { MemoryRecord, MemoryScope } from '../main/ets/chat/memory_models.ts';
import { makeMemoryRecord } from '../main/ets/chat/memory_models.ts';

import type { MemoryReadRepository, MemoryRecallRuntimeGate } from '../main/ets/chat/memory_recall_store.ts';
import { buildMemoryRecallPrompt, recallMemorySelections } from '../main/ets/chat/memory_recall_store.ts';

const NOW: number = new Date(2026, 6, 28, 12).getTime();

const record = (over: Partial<MemoryRecord>): MemoryRecord => makeMemoryRecord({
  id: over.id ?? 1,
  content: over.content ?? '内容',
  scope: over.scope ?? 'long_term',
  kind: over.kind ?? 'note',
  assistantId: over.assistantId ?? '__long_term__',
  sourceConversationId: over.sourceConversationId ?? null,
  sourceMessageIds: over.sourceMessageIds ?? [],
  supersedesIds: over.supersedesIds ?? [],
  expiresAt: over.expiresAt ?? null,
  confidence: over.confidence ?? 1,
  pinned: over.pinned ?? false,
  archived: over.archived ?? false,
  createdAt: over.createdAt ?? NOW,
  updatedAt: over.updatedAt ?? NOW,
  lastUsedAt: over.lastUsedAt ?? null,
});

const gate = (over: Partial<MemoryRecallRuntimeGate> = {}): MemoryRecallRuntimeGate => ({
  enableCoreMemory: over.enableCoreMemory ?? true,
  enableShortTermMemory: over.enableShortTermMemory ?? true,
  enableLongTermMemory: over.enableLongTermMemory ?? true,
  memoryRecall: over.memoryRecall ?? { maxItems: 12, maxPromptChars: 2000, debug: false },
});

interface RepoSpy {
  repo: MemoryReadRepository;
  scopeCalls: MemoryScope[][];
  touched: number[][];
}

const spyRepo = (records: MemoryRecord[]): RepoSpy => {
  const scopeCalls: MemoryScope[][] = [];
  const touched: number[][] = [];
  return {
    scopeCalls, touched,
    repo: {
      getActiveRecords: (scopes: MemoryScope[], _now: number): Promise<MemoryRecord[]> => {
        scopeCalls.push([...scopes]);
        return Promise.resolve(records.filter(
          (r: MemoryRecord): boolean => scopes.indexOf(r.scope) >= 0));
      },
      touchMemories: (ids: number[]): Promise<void> => {
        touched.push([...ids]);
        return Promise.resolve();
      },
    },
  };
};

const userMsg = (text: string): UIMessage =>
  makeUIMessage('user', [{ type: 'text', text, metadata: null }]);

test('recallSelections: scopes 门 — 三开关全开按 core/short/long 序传仓', async () => {
  const spy: RepoSpy = spyRepo([]);
  await recallMemorySelections(gate(), [], spy.repo, NOW);
  assert.deepEqual(spy.scopeCalls, [['core', 'short_term', 'long_term']]);
});

test('recallSelections: 部分开关 — 仅开启的 scope 入仓查询', async () => {
  const spy: RepoSpy = spyRepo([]);
  await recallMemorySelections(
    gate({ enableCoreMemory: false, enableShortTermMemory: true, enableLongTermMemory: false }),
    [], spy.repo, NOW);
  assert.deepEqual(spy.scopeCalls, [['short_term']]);
});

test('recallSelections: 三开关全关 → 空且**不触仓**(:44 scopes.isEmpty)', async () => {
  const spy: RepoSpy = spyRepo([record({ id: 1 })]);
  const out = await recallMemorySelections(
    gate({ enableCoreMemory: false, enableShortTermMemory: false, enableLongTermMemory: false }),
    [], spy.repo, NOW);
  assert.deepEqual(out, []);
  assert.deepEqual(spy.scopeCalls, []);
});

test('recallSelections: 结果经 rankRecords(过滤/排序/预算)', async () => {
  const records: MemoryRecord[] = [
    record({ id: 1, content: '毫无关联zz' }),
    record({ id: 2, content: '中文偏好', kind: 'user' }),
    record({ id: 3, content: '无关但置顶', pinned: true }),
  ];
  const spy: RepoSpy = spyRepo(records);
  const out = await recallMemorySelections(gate(), [userMsg('中文')], spy.repo, NOW);
  assert.deepEqual(out.map((s) => s.record.id), [3, 2]);
});

test('buildPrompt: touchMemories 按召回序传 id + 非 debug 无 details', async () => {
  const records: MemoryRecord[] = [
    record({ id: 2, content: '中文偏好', kind: 'user' }),
    record({ id: 3, content: '无关但置顶', pinned: true }),
  ];
  const spy: RepoSpy = spyRepo(records);
  const prompt: string = await buildMemoryRecallPrompt(
    gate(), [userMsg('中文')], spy.repo, NOW);
  assert.deepEqual(spy.touched, [[3, 2]]);
  assert.ok(prompt.startsWith('<memory_context>\n'));
  assert.ok(prompt.indexOf('- [long_term/note/pinned] 无关但置顶') >= 0);
  assert.ok(prompt.indexOf('(id=') < 0);
});

test('buildPrompt: debug=true → details 携带 score 调试文本(:23-27)', async () => {
  const records: MemoryRecord[] = [record({ id: 5, content: ' pinned 项', pinned: true })];
  const spy: RepoSpy = spyRepo(records);
  const prompt: string = await buildMemoryRecallPrompt(
    gate({ memoryRecall: { maxItems: 12, maxPromptChars: 2000, debug: true } }),
    [], spy.repo, NOW);
  assert.ok(prompt.indexOf('(id=5, confidence=1.00, score=') >= 0);
  assert.ok(prompt.indexOf('reasons=pinned') >= 0);
});

test('buildPrompt: 召回为空 → 空串 且仍 touchMemories(空 ids,:20)', async () => {
  const spy: RepoSpy = spyRepo([]);
  const prompt: string = await buildMemoryRecallPrompt(gate(), [], spy.repo, NOW);
  assert.equal(prompt, '');
  assert.deepEqual(spy.touched, [[]]);
});

// ===== DAO 查询语义纯函数 =====

test('filterActiveMemoryRecords: scope 过滤 + archived 排除 + expiresAt null/未来保留', async () => {
  const { filterActiveMemoryRecords } = await import('../main/ets/chat/memory_recall_store.ts');
  const records: MemoryRecord[] = [
    record({ id: 1, scope: 'core' }),
    record({ id: 2, scope: 'short_term', archived: true }),
    record({ id: 3, scope: 'long_term', expiresAt: NOW - 1 }),
    record({ id: 4, scope: 'long_term', expiresAt: NOW + 1000 }),
    record({ id: 5, scope: 'long_term', expiresAt: null }),
  ];
  const out: MemoryRecord[] = filterActiveMemoryRecords(records, ['core', 'long_term'], NOW);
  assert.deepEqual(out.map((r: MemoryRecord): number => r.id), [1, 4, 5]);
  // expiresAt == now 不算 > now → 排除
  const edge: MemoryRecord[] = filterActiveMemoryRecords(
    [record({ id: 6, expiresAt: NOW })], ['long_term'], NOW);
  assert.equal(edge.length, 0);
});

test('filterActiveMemoryRecords: 插入序保留(DAO 无 ORDER BY)', async () => {
  const { filterActiveMemoryRecords } = await import('../main/ets/chat/memory_recall_store.ts');
  const records: MemoryRecord[] = [
    record({ id: 9 }), record({ id: 2 }), record({ id: 7 }),
  ];
  const out: MemoryRecord[] = filterActiveMemoryRecords(records, ['long_term'], NOW);
  assert.deepEqual(out.map((r: MemoryRecord): number => r.id), [9, 2, 7]);
});

test('touchMemoryRecords: 命中置 lastUsedAt,未命中原样,不改输入数组', async () => {
  const { touchMemoryRecords } = await import('../main/ets/chat/memory_recall_store.ts');
  const records: MemoryRecord[] = [record({ id: 1 }), record({ id: 2 }), record({ id: 3 })];
  const out: MemoryRecord[] = touchMemoryRecords(records, [3, 1], NOW + 5000);
  assert.equal(out[0].lastUsedAt, NOW + 5000);
  assert.equal(out[1].lastUsedAt, null);
  assert.equal(out[2].lastUsedAt, NOW + 5000);
  assert.equal(records[0].lastUsedAt, null); // 输入不被突变
});
