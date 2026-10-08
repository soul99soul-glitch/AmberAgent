// memory_extractor + candidate_filter + extraction_prompt 测试(D-085d)
// 锚点:MemoryExtractor.kt / MemoryCandidateFilter.kt / MemoryExtractionPrompt.kt
import assert from 'node:assert/strict';
import test from 'node:test';
import { makeUIMessage } from '../main/ets/chat/message.ts';
import type { UIMessage } from '../main/ets/chat/message.ts';
import { makeConversation, toMessageNode } from '../main/ets/chat/conversation.ts';
import type { Conversation } from '../main/ets/chat/conversation.ts';
import { makeMemoryCandidate, makeMemoryRecord } from '../main/ets/chat/memory_models.ts';
import type {
  MemoryCandidate, MemoryEvent, MemoryEventType, MemoryRecord,
} from '../main/ets/chat/memory_models.ts';
import { buildMemoryExtractionPrompt } from '../main/ets/chat/memory_extraction_prompt.ts';
import { filterMemoryCandidates } from '../main/ets/chat/memory_candidate_filter.ts';
import type { MemoryExtractionDeps, MemoryWorkerModelResolution } from '../main/ets/chat/memory_extractor.ts';
import {
  autoWriteEventMessage, isDurableAutoWriteCandidate, parseMemoryCandidates,
  resetMemoryExtractionDebounce, resolveCandidateExpiresAt, runMemoryExtraction,
  shouldAutoWriteCandidate,
} from '../main/ets/chat/memory_extractor.ts';
import type { MemoryAddParams } from '../main/ets/chat/memory_write.ts';

const NOW = new Date(2026, 6, 28, 12).getTime();

const userMsg = (text: string, id?: string): UIMessage =>
  makeUIMessage('user', [{ type: 'text', text, metadata: null }], id !== undefined ? { id } : {});

const candidate = (over: Partial<MemoryCandidate>): MemoryCandidate => makeMemoryCandidate({
  content: over.content ?? '足够长的候选内容文本',
  scope: over.scope ?? 'long_term',
  kind: over.kind ?? 'user',
  confidence: over.confidence ?? 0.9,
  reason: over.reason ?? '',
  sensitive: over.sensitive ?? false,
  sourceConversationId: over.sourceConversationId ?? null,
  sourceMessageIds: over.sourceMessageIds ?? [],
  expiresAt: over.expiresAt ?? null,
});

const record = (over: Partial<MemoryRecord>): MemoryRecord => makeMemoryRecord({
  id: over.id ?? 1, content: over.content ?? '既有记忆内容',
  scope: over.scope ?? 'long_term', kind: over.kind ?? 'note',
  assistantId: '__long_term__', sourceConversationId: null,
  sourceMessageIds: [], supersedesIds: [], expiresAt: null,
  confidence: 1, pinned: false, archived: false,
  createdAt: NOW, updatedAt: NOW, lastUsedAt: null,
});

// ===== 提示词 =====

test('extraction prompt: 用户证据带时间 + 上文只读 + text take(4000)', () => {
  const longText: string = 'x'.repeat(5000);
  const prompt: string = buildMemoryExtractionPrompt(
    [userMsg('你好', 'm-1'), userMsg(longText, 'm-2')], ['m-1', 'm-2'], '中文 (中国)');
  assert.ok(prompt.startsWith('You extract durable memory candidates for AmberAgent.\nLocale: 中文 (中国)\n'));
  assert.ok(prompt.includes('"expires_on":null'));
  assert.ok(prompt.includes('Read-only previous assistant context'));
  assert.ok(prompt.includes('createdAt'));
  assert.ok(prompt.includes('- At most 5 candidates.'));
  assert.ok(prompt.indexOf('Use these source_message_ids when relevant: m-1, m-2.\n') >= 0);
  assert.ok(prompt.includes('x'.repeat(4000)));
  assert.ok(!prompt.includes('x'.repeat(4001)));
});

// ===== 过滤器 =====

test('filter: sensitive(自带或内容命中)→ rejected + reason 拼接顺序', () => {
  const out = filterMemoryCandidates([
    candidate({ content: '把银行卡号记住 123456', reason: '原始理由' }),
    candidate({ content: '普通偏好内容文本甲乙丙丁', sensitive: true }),
  ], []);
  assert.equal(out.accepted.length, 0);
  assert.equal(out.rejected.length, 2);
  assert.equal(out.rejected[0].status, 'filtered');
  assert.equal(out.rejected[0].sensitive, true);
  assert.equal(out.rejected[0].reason, '原始理由; sensitive'); // 原 reason 在前
  assert.equal(out.rejected[1].reason, 'sensitive');
});

test('filter: tooWeak(trim<12 或 confidence<0.45)与 duplicate(既有/本批)', () => {
  const out = filterMemoryCandidates([
    candidate({ content: '太短' }),                                    // trim < 12
    candidate({ content: '足够长但置信不足文本', confidence: 0.4 }),     // < 0.45
    candidate({ content: '既有记忆内容甲乙丙丁戊己' }),                    // 与 existing 重
    candidate({ content: '重复项内容文本甲乙丙丁戊' }),
    candidate({ content: '重复项内容文本甲乙丙丁戊' }),                    // 本批内重
  ], [record({ content: '既有记忆内容甲乙丙丁戊己' })]);
  assert.equal(out.rejected.length, 4);
  assert.equal(out.accepted.length, 1);
  assert.equal(out.rejected[0].reason, 'low_value');
  assert.equal(out.rejected[2].reason, 'duplicate');
  assert.equal(out.rejected[3].reason, 'duplicate'); // 本批内重(首条已收)
  // normalize:大小写/符号忽略
  const out2 = filterMemoryCandidates(
    [candidate({ content: 'Hello, WORLD!! 你好' })],
    [record({ content: 'hello world你好' })]);
  assert.equal(out2.rejected.length, 1);
});

test('filter: 接受副本 kind NOTE→PROJECT,其余不动;候选本体不突变', () => {
  const c1: MemoryCandidate = candidate({ kind: 'note', content: '接受的候选内容甲乙丙丁戊' });
  const c2: MemoryCandidate = candidate({ kind: 'feedback', content: '接受的反馈内容甲乙丙丁戊' });
  const out = filterMemoryCandidates([c1, c2], []);
  assert.equal(out.accepted[0].kind, 'project');
  assert.equal(out.accepted[1].kind, 'feedback');
  assert.equal(c1.kind, 'note');
});

// ===== parse =====

test('parse: 围栏剥离 + 前后噪声截取 + take(5) + 字段默认(confidence 0.55)', () => {
  const raw: string = '好的，结果如下:\n```json\n{"candidates":[' +
    '{"content":"  用户偏好简短回复  ","scope":"long_term","kind":"user","confidence":0.9,"reason":"r1","expires_in_days":null},' +
    '{"content":"无 scope/kind 的候选内容","confidence":"0.7"},' +
    '{"content":"第三"},' +
    '{"content":"第四"},' +
    '{"content":"第五"},' +
    '{"content":"第六(应被 take(5) 截掉)"}' +
    ']}```\n以上。';
  const out = parseMemoryCandidates(raw, 'conv-1', ['s1'], NOW);
  assert.equal(out.length, 5);
  assert.equal(out[0].candidate.content, '用户偏好简短回复'); // trim
  assert.equal(out[0].explicitScope, true);
  assert.equal(out[0].explicitKind, true);
  assert.equal(out[0].candidate.reason, 'r1');
  assert.equal(out[1].explicitScope, false);
  assert.equal(out[1].explicitKind, false);
  assert.equal(out[1].candidate.scope, 'long_term'); // fromWireName 兜底
  assert.equal(out[1].candidate.kind, 'note');
  assert.equal(out[1].candidate.confidence, 0.7); // 字符串 float 可解
  assert.equal(out[2].candidate.confidence, 0.55); // 默认
  assert.deepEqual(out[0].candidate.sourceMessageIds, ['s1']);
  assert.equal(out[0].candidate.sourceConversationId, 'conv-1');
});

test('parse: content 空白跳过;expires_in_days → now+days*86400000', () => {
  const raw: string = '{"candidates":[' +
    '{"content":"   "},' +
    '{"content":"短住酒店行程甲乙丙丁","scope":"short_term","kind":"project","expires_in_days":3}]}';
  const out = parseMemoryCandidates(raw, 'c', [], NOW);
  assert.equal(out.length, 1);
  assert.equal(out[0].candidate.expiresAt, NOW + 3 * 86_400_000);
});

test('parse: 非对象根/非数组失败,单条坏项跳过避免整批停摆', () => {
  assert.throws((): void => { parseMemoryCandidates('[1,2]', 'c', []); }, /not a JsonObject/);
  assert.throws((): void => { parseMemoryCandidates('{"candidates": 5}', 'c', []); }, /not a JsonArray/);
  assert.deepEqual(parseMemoryCandidates('{"candidates": [7]}', 'c', []), []);
  assert.throws((): void => { parseMemoryCandidates('not json at all', 'c', []); });
  // candidates 缺失 → 空(orEmpty)
  assert.deepEqual(parseMemoryCandidates('{"other": 1}', 'c', []), []);
});

// ===== auto-write / expiresAt =====

test('shouldAutoWriteCandidate: 门序列逐字(敏感→false,非 explicit→false,双阈值)', () => {
  const durable = candidate({ scope: 'long_term', kind: 'user', confidence: 0.85 });
  assert.equal(shouldAutoWriteCandidate(durable, true, true), true);
  assert.equal(shouldAutoWriteCandidate(durable, false, true), false); // 非 explicit
  assert.equal(shouldAutoWriteCandidate(
    candidate({ scope: 'long_term', kind: 'user', confidence: 0.84 }), true, true), false);
  assert.equal(shouldAutoWriteCandidate(
    candidate({ scope: 'short_term', kind: 'project', confidence: 0.72 }), true, true), true);
  assert.equal(shouldAutoWriteCandidate(
    candidate({ scope: 'short_term', kind: 'project', confidence: 0.71 }), true, true), false);
  assert.equal(shouldAutoWriteCandidate(
    candidate({ scope: 'long_term', kind: 'user', confidence: 0.9, sensitive: true }),
    true, true), false); // 敏感优先短路
  assert.equal(shouldAutoWriteCandidate(
    candidate({ scope: 'long_term', kind: 'user', confidence: 0.9, content: '含 password 的内容文本' }),
    true, true), false); // 内容敏感词命中
});

test('isDurableAutoWrite/autoWriteEventMessage: 三分支逐字', () => {
  const user = candidate({ scope: 'long_term', kind: 'user', confidence: 0.9 });
  const feedback = candidate({ scope: 'long_term', kind: 'feedback', confidence: 0.9 });
  const project = candidate({ scope: 'short_term', kind: 'project', confidence: 0.8 });
  assert.equal(isDurableAutoWriteCandidate(user), true);
  assert.equal(isDurableAutoWriteCandidate(project), false);
  assert.equal(autoWriteEventMessage(user), 'Auto-created durable user memory.');
  assert.equal(autoWriteEventMessage(feedback), 'Auto-created durable feedback memory.');
  assert.equal(autoWriteEventMessage(project), 'Auto-created short-term project memory.');
});

test('resolveCandidateExpiresAt: expiresInDays 优先;short_term 回退时间锚;其他 null', () => {
  assert.equal(resolveCandidateExpiresAt('任意', 'core', 5, NOW), NOW + 5 * 86_400_000);
  assert.equal(resolveCandidateExpiresAt('没有日期的内容', 'short_term', null, NOW), null);
  assert.equal(resolveCandidateExpiresAt('没有日期的内容', 'long_term', null, NOW), null);
  // short_term + 含过去日期锚 → deriveExpiresAt(锚早于 now → null)
  assert.equal(resolveCandidateExpiresAt('2020-01-01 的事', 'short_term', null, NOW), null);
});

// ===== 编排 =====

interface Harness {
  deps: MemoryExtractionDeps;
  events: MemoryEvent[];
  addedCandidates: MemoryCandidate[];
  addedMemories: MemoryAddParams[];
  prompts: string[];
  activeRecords: MemoryRecord[];
}

const harness = (over: {
  worker?: Partial<MemoryExtractionDeps['worker']>;
  resolution?: MemoryWorkerModelResolution;
  llmText?: string;
  activeRecords?: MemoryRecord[];
  runsToday?: number;
  now?: number;
} = {}): Harness => {
  const events: MemoryEvent[] = [];
  const addedCandidates: MemoryCandidate[] = [];
  const addedMemories: MemoryAddParams[] = [];
  const prompts: string[] = [];
  const activeRecords: MemoryRecord[] = over.activeRecords ?? [];
  const deps: MemoryExtractionDeps = {
    worker: {
      enabled: over.worker?.enabled ?? true,
      extractionEnabled: over.worker?.extractionEnabled ?? true,
      maxDailyRuns: over.worker?.maxDailyRuns ?? 8,
    },
    locale: '中文 (中国)',
    resolveWorkerModel: (): Promise<MemoryWorkerModelResolution> =>
      Promise.resolve(over.resolution ?? { kind: 'ok', modelId: 'model-x' }),
    generateText: (prompt: string): Promise<string> => {
      prompts.push(prompt);
      return Promise.resolve(over.llmText ?? '{"candidates": []}');
    },
    getAllActiveRecords: (_now: number): Promise<MemoryRecord[]> => Promise.resolve(activeRecords),
    addCandidates: (cs: MemoryCandidate[]): Promise<void> => {
      addedCandidates.push(...cs);
      return Promise.resolve();
    },
    addCandidate: (c: MemoryCandidate): Promise<void> => {
      addedCandidates.push(c);
      return Promise.resolve();
    },
    addMemory: (p: MemoryAddParams): Promise<MemoryRecord> => {
      addedMemories.push(p);
      return Promise.resolve(record({ id: 42, content: p.content, scope: p.scope, kind: p.kind }));
    },
    addEvent: (e: MemoryEvent): Promise<void> => {
      events.push(e);
      return Promise.resolve();
    },
    countEventsSince: (_t: MemoryEventType, _after: number): Promise<number> =>
      Promise.resolve(over.runsToday ?? 0),
    now: over.now !== undefined ? (): number => over.now as number : undefined,
  };
  return { deps, events, addedCandidates, addedMemories, prompts, activeRecords };
};

const conv = (): Conversation =>
  makeConversation('conv-1', [toMessageNode(userMsg('聊了一些偏好内容'))]);

test('编排: worker 关闭 → skipped(Memory worker disabled.)且不触模型', async () => {
  resetMemoryExtractionDebounce();
  const h = harness({ worker: { enabled: false } });
  await runMemoryExtraction(conv(), h.deps);
  assert.equal(h.events.length, 1);
  assert.equal(h.events[0].type, 'extraction_skipped');
  assert.equal(h.events[0].message, 'Memory worker disabled.');
  assert.equal(h.events[0].messageCount, 1);
  assert.equal(h.prompts.length, 0);
});

test('编排: 去抖 120s — 同会话二次调用 → Debounced.', async () => {
  resetMemoryExtractionDebounce();
  const h = harness({ now: NOW });
  const c: Conversation = conv();
  await runMemoryExtraction(c, h.deps);
  await runMemoryExtraction(c, h.deps);
  const types: MemoryEventType[] = h.events.map((e: MemoryEvent): MemoryEventType => e.type);
  assert.deepEqual(types, ['extraction_started', 'extraction_skipped']);
  assert.equal(h.events[1].message, 'Debounced.');
});

test('编排: 日配额 — runsToday ≥ maxDailyRuns(coerce≥1) → limit reached', async () => {
  resetMemoryExtractionDebounce();
  const h = harness({ runsToday: 8, now: NOW });
  await runMemoryExtraction(conv(), h.deps);
  assert.equal(h.events[0].message, 'Daily memory worker limit reached.');
  // maxDailyRuns=0 → coerceAtLeast(1):runsToday=1 即触发
  resetMemoryExtractionDebounce();
  const h2 = harness({ runsToday: 1, worker: { maxDailyRuns: 0 }, now: NOW });
  await runMemoryExtraction(conv(), h2.deps);
  assert.equal(h2.events[0].message, 'Daily memory worker limit reached.');
});

test('编排: 模型/provider 缺失 → 各自 skipped 消息(modelId 仅 provider 缺失带)', async () => {
  resetMemoryExtractionDebounce();
  const h1 = harness({ resolution: { kind: 'no_model' }, now: NOW });
  await runMemoryExtraction(conv(), h1.deps);
  assert.equal(h1.events[0].message, 'No memory worker model available.');
  assert.equal(h1.events[0].modelId, null);
  resetMemoryExtractionDebounce();
  const h2 = harness({ resolution: { kind: 'no_provider', modelId: 'm-9' }, now: NOW });
  await runMemoryExtraction(conv(), h2.deps);
  assert.equal(h2.events[0].message, 'Memory worker model provider not found.');
  assert.equal(h2.events[0].modelId, 'm-9');
});

test('编排: 全链路 — started → autoWrite(durable)/candidate 分流 + 事件类型与字段', async () => {
  resetMemoryExtractionDebounce();
  const llm: string = '{"candidates":[' +
    '{"content":"用户偏好简短回复且周末活跃","source_message_id":"m1","scope":"long_term","kind":"user","confidence":0.95,"reason":"稳定偏好"},' +
    '{"content":"可能有点用但不明确的内容","source_message_id":"m1","scope":"long_term","kind":"user","confidence":0.6,"reason":"不确定"}' +
    ']}';
  const h = harness({ llmText: llm, now: NOW });
  await runMemoryExtraction(makeConversation('conv-1', [toMessageNode(userMsg('用户偏好简短回复且周末活跃。可能有点用但不明确的内容', 'm1'))]), h.deps);
  const types: MemoryEventType[] = h.events.map((e: MemoryEvent): MemoryEventType => e.type);
  assert.deepEqual(types, ['extraction_started', 'candidate_created', 'durable_memory_created']);
  // autoWrite:显式 scope+kind+confidence 0.95 ≥ 0.85 → addMemory + durable 事件
  assert.equal(h.addedMemories.length, 1);
  assert.equal(h.addedMemories[0].content, '用户偏好简短回复且周末活跃');
  assert.equal(h.events[2].memoryId, 42);
  assert.equal(h.events[2].message, 'Auto-created durable user memory.');
  assert.equal(h.events[2].modelId, 'model-x');
  // 第二候选:无显式 scope → 不 autoWrite → addCandidate + reason 为事件 message
  assert.equal(h.addedCandidates.length, 1);
  assert.equal(h.events[1].message, '不确定');
  assert.equal(h.events[1].candidateId, h.addedCandidates[0].id);
  // started 事件带 modelId/messageCount
  assert.equal(h.events[0].modelId, 'model-x');
  assert.equal(h.events[0].messageCount, 1);
});

test('编排: LLM 输出非法 → EXTRACTION_FAILED(durationMs + message)', async () => {
  resetMemoryExtractionDebounce();
  const h = harness({ llmText: '完全不是 JSON', now: NOW });
  await runMemoryExtraction(conv(), h.deps);
  const types: MemoryEventType[] = h.events.map((e: MemoryEvent): MemoryEventType => e.type);
  assert.deepEqual(types, ['extraction_started', 'extraction_failed']);
  assert.ok(h.events[1].message.length > 0);
  assert.equal(h.events[1].durationMs, 0); // now 固定 → 0ms
  assert.equal(h.addedCandidates.length, 0);
});

test('编排: rejected 候选批量 addCandidates(filtered 状态)', async () => {
  resetMemoryExtractionDebounce();
  const llm: string = '{"candidates":[' +
    '{"content":"短","scope":"long_term","kind":"note","confidence":0.9}]}';
  const h = harness({ llmText: llm, now: NOW });
  await runMemoryExtraction(conv(), h.deps);
  assert.equal(h.addedCandidates.length, 1);
  assert.equal(h.addedCandidates[0].status, 'filtered');
});
