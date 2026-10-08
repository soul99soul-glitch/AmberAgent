// memory_dream + dream_prompt 测试(D-085f)
// 锚点:MemoryDreamPlanner.kt / MemoryDreamApplier.kt / MemoryDreamPlanStore.kt(summary)
//   / MemoryWorkerDreamGate.kt / MemoryDreamPrompt.kt
import assert from 'node:assert/strict';
import test from 'node:test';
import { makeMemoryCandidate, makeMemoryRecord } from '../main/ets/chat/memory_models.ts';
import type {
  MemoryCandidate, MemoryEvent, MemoryRecord,
} from '../main/ets/chat/memory_models.ts';
import { buildMemoryDreamPrompt } from '../main/ets/chat/memory_dream_prompt.ts';
import type {
  MemoryDreamApplierDeps, MemoryDreamPlan, MemoryDreamPlannerDeps, MemoryMergeSuggestion, MemorySupersedeSuggestion
} from '../main/ets/chat/memory_dream.ts';
import {
  dreamPlanOnlyApplicable, isAnyDreamEnabled, isDreamMaintenanceEnabled, isDreamModelEnabled, makeMemoryDreamPlan,
  memoryDreamPlanHasChanges, mergeDreamPlanWith, parseDreamModelPlanJson, planDreamMaintenance, runMemoryDreamApply,
  runMemoryDreamPlan,
} from '../main/ets/chat/memory_dream.ts';
import type { MemoryAddParams } from '../main/ets/chat/memory_write.ts';

const NOW = new Date(2026, 6, 28, 12).getTime();

const record = (over: Partial<MemoryRecord> & Pick<MemoryRecord, 'id'>): MemoryRecord =>
  makeMemoryRecord({
    content: '既有记忆内容甲乙丙丁', scope: 'short_term', kind: 'project',
    assistantId: 'asst-1', sourceConversationId: null,
    sourceMessageIds: [], supersedesIds: [], expiresAt: null,
    confidence: 0.9, pinned: false, archived: false,
    createdAt: NOW, updatedAt: NOW, lastUsedAt: null,
    ...over,
  });

const candidate = (over: Partial<MemoryCandidate> & Pick<MemoryCandidate, 'id'>): MemoryCandidate =>
  makeMemoryCandidate({
    content: '候选内容文本甲乙丙丁戊', scope: 'long_term', kind: 'user',
    confidence: 0.9, createdAt: NOW,
    ...over,
  });

// ===== 门(MemoryWorkerDreamGate.kt 全文) =====

test('dream gate: 三分支逐字', () => {
  const gate = (maint: boolean, model: boolean) => ({
    enabled: true, dreamMaintenanceEnabled: maint, dreamModelEnabled: model,
  });
  assert.equal(isAnyDreamEnabled(gate(false, false)), false);
  assert.equal(isAnyDreamEnabled(gate(true, false)), true);
  assert.equal(isAnyDreamEnabled(gate(false, true)), true);
  assert.equal(isDreamMaintenanceEnabled(gate(true, true)), true);
  assert.equal(isDreamMaintenanceEnabled(gate(false, true)), false);
  assert.equal(isDreamModelEnabled(gate(false, true)), true);
  assert.equal(isDreamModelEnabled(gate(true, false)), false);
});

// ===== hasChanges / summaryText =====

test('hasChanges: notes 不计(MemoryDreamPlanner.kt:296-301)', () => {
  assert.equal(memoryDreamPlanHasChanges(makeMemoryDreamPlan()), false);
  assert.equal(memoryDreamPlanHasChanges(makeMemoryDreamPlan({ notes: ['仅备注'] })), false);
  assert.equal(memoryDreamPlanHasChanges(makeMemoryDreamPlan({ promoteMemoryIds: [1] })), true);
  assert.equal(memoryDreamPlanHasChanges(makeMemoryDreamPlan({ archiveMemoryIds: [1] })), true);
  assert.equal(memoryDreamPlanHasChanges(makeMemoryDreamPlan({ ignoreCandidateIds: ['c1'] })), true);
  assert.equal(memoryDreamPlanHasChanges(makeMemoryDreamPlan({
    mergeSuggestions: [{ targetMemoryId: 1, duplicateMemoryIds: [2], mergedContent: null, reason: '' }],
  })), true);
  assert.equal(memoryDreamPlanHasChanges(makeMemoryDreamPlan({
    supersedeSuggestions: [{
      oldMemoryIds: [1], newContent: '新内容甲乙丙丁戊', scope: 'long_term',
      kind: 'user', confidence: 0.8, reason: '',
    }],
  })), true);
});

// ===== planDreamMaintenance =====

test('maintenance: 过期短期记忆归档(expiresAt <= now 且未归档)', () => {
  // 内容互异,避免意外落入重复组
  const expired: MemoryRecord = record({ id: 1, expiresAt: NOW - 1, confidence: 0.1, content: '过期内容甲乙丙丁' });
  const future: MemoryRecord = record({ id: 2, expiresAt: NOW + 10000, confidence: 0.1, content: '未过期内容戊己庚辛' });
  const alreadyArchived: MemoryRecord = record({ id: 3, expiresAt: NOW - 1, archived: true, content: '归档内容壬癸子丑' });
  const plan: MemoryDreamPlan = planDreamMaintenance([expired, future, alreadyArchived], [], NOW);
  assert.deepEqual(plan.archiveMemoryIds, [1]);
  assert.deepEqual(plan.notes, ['发现 1 条过期或闲置记忆，可归档。']);
});

test('maintenance: same scope/kind duplicate groups rank confidence then updatedAt', () => {
  // Short rows are skipped; exact matching trims outer whitespace only.
  const short: MemoryRecord = record({ id: 9, content: '短' });
  const short2: MemoryRecord = record({ id: 10, content: '短!' });
  // Exact duplicates rank confidence and update time within the same scope/kind.
  const a: MemoryRecord = record({ id: 1, content: 'Alpha beta 内容', confidence: 0.99, updatedAt: NOW + 5 });
  const b: MemoryRecord = record({ id: 2, content: '  Alpha beta 内容  ', confidence: 1 });
  const c: MemoryRecord = record({ id: 3, content: 'Alpha beta 内容', confidence: 0.5 });
  const d: MemoryRecord = record({ id: 4, content: 'Alpha beta 内容', confidence: 0.99, updatedAt: NOW + 9 });
  const plan: MemoryDreamPlan = planDreamMaintenance([a, b, c, d, short, short2], [], NOW);
  assert.equal(plan.mergeSuggestions.length, 1);
  const merge: MemoryMergeSuggestion = plan.mergeSuggestions[0];
  // Highest confidence wins; remaining equal confidence sorts by newest update.
  assert.equal(merge.targetMemoryId, 2);
  assert.deepEqual(merge.duplicateMemoryIds, [4, 1, 3]);
  assert.equal(merge.mergedContent, '  Alpha beta 内容  ');
  assert.equal(merge.reason, '内容高度重复，保留可信度或层级更高的一条。');
  assert.deepEqual(plan.notes, ['发现 1 组可能重复的记忆，可合并后归档副本。']);
});

test('maintenance: promote requires actual reinforcement and 14-day durability', () => {
  const base = { id: 1, expiresAt: null, confidence: 0.82, lastUsedAt: NOW, kind: 'project' as const,
    createdAt: NOW - 20 * 86400000, reinforcementCount: 2, lastReinforcedAt: NOW };
  // 内容互异,避免意外落入重复组
  const ok: MemoryRecord = record({ ...base, content: '可提升的内容甲' });
  const lowConf: MemoryRecord = record({ ...base, id: 2, reinforcementCount: 1, content: '单次引用内容乙' });
  const noUse: MemoryRecord = record({ ...base, id: 3, reinforcementCount: 0, lastReinforcedAt: null, content: '未使用内容丙' });
  const userKind: MemoryRecord = record({ ...base, id: 4, createdAt: NOW - 86400000, content: '新记忆内容丁' });
  const longTerm: MemoryRecord = record({ ...base, id: 5, scope: 'long_term', content: '长期内容戊' });
  const archived: MemoryRecord = record({ ...base, id: 6, archived: true, content: '已归档内容己' });
  const plan: MemoryDreamPlan = planDreamMaintenance(
    [ok, lowConf, noUse, userKind, longTerm, archived], [], NOW);
  assert.deepEqual(plan.promoteMemoryIds, [1]);
  assert.deepEqual(plan.notes, ['发现 1 条反复使用的短期记忆，经至少两次实际引用或确认且跨越 14 天，可提升为长期记忆。']);
});

test('maintenance: 噪声候选 — trim<12 / confidence<0.45 / 与活动记忆 normalize 重复', () => {
  const existing: MemoryRecord = record({ id: 1, content: '已有的事实内容文本' });
  const dupContent: MemoryCandidate = candidate({ id: 'c1', content: '已有的事实内容文本!' });
  const tooShort: MemoryCandidate = candidate({ id: 'c2', content: '太短', confidence: 0.9 });
  const lowConf: MemoryCandidate = candidate({ id: 'c3', content: '正常长度的候选内容文本', confidence: 0.44 });
  const good: MemoryCandidate = candidate({ id: 'c4', content: '另一条正常长度的候选内容', confidence: 0.9 });
  const plan: MemoryDreamPlan = planDreamMaintenance([existing], [dupContent, tooShort, lowConf, good], NOW);
  assert.deepEqual(plan.ignoreCandidateIds, ['c1', 'c2', 'c3']);
  assert.deepEqual(plan.notes, ['发现 3 条低价值或重复候选，可忽略。']);
});

test('maintenance: notes 顺序 重复→提升→过期→噪声 且各 id 列表 distinct', () => {
  const dup1: MemoryRecord = record({ id: 1, content: '重复的内容甲乙丙丁' });
  const dup2: MemoryRecord = record({ id: 2, content: '重复的内容甲乙丙丁' });
  const promotable: MemoryRecord = record({
    id: 3, content: '可提升内容甲乙丙丁', expiresAt: null, confidence: 0.9, lastUsedAt: NOW,
    createdAt: NOW - 20 * 86400000, reinforcementCount: 2, lastReinforcedAt: NOW,
  });
  const expired: MemoryRecord = record({ id: 4, content: '过期内容甲乙丙丁', expiresAt: NOW - 1 });
  const noisy: MemoryCandidate = candidate({ id: 'c1', content: '短' });
  const plan: MemoryDreamPlan = planDreamMaintenance(
    [dup1, dup2, promotable, expired], [noisy, noisy], NOW);
  assert.deepEqual(plan.notes, [
    '发现 1 组可能重复的记忆，可合并后归档副本。',
    '发现 1 条反复使用的短期记忆，经至少两次实际引用或确认且跨越 14 天，可提升为长期记忆。',
    '发现 1 条过期或闲置记忆，可归档。',
    // note 用 distinct 前的 size(Planner.kt:183;同一候选两次 → 2)
    '发现 2 条低价值或重复候选，可忽略。',
  ]);
  assert.deepEqual(plan.ignoreCandidateIds, ['c1']);
  assert.deepEqual(plan.supersedeSuggestions, []);
});

// ===== parseDreamModelPlanJson =====

const PARSE_RECORDS: MemoryRecord[] = [record({ id: 1 }), record({ id: 2 }), record({ id: 3 })];
const PARSE_CANDIDATES: MemoryCandidate[] = [candidate({ id: 'c1' }), candidate({ id: 'c2' })];

test('parseModelPlanJson: fence 剥离 + {} 子串 + 全键解析', () => {
  const raw: string = '前言噪音```json\n' + JSON.stringify({
    merge: [{ target_memory_id: 1, duplicate_memory_ids: [2, 3], merged_content: '合并后', reason: 'r' }],
    promote: [1, 2, 99],
    archive: [3, 98],
    supersede: [{
      old_memory_ids: [1, 2], new_content: '  新的替代内容甲乙丙丁  ',
      scope: 'long_term', kind: 'user', confidence: 0.86, reason: 'why',
    }],
    delete_suggestions: ['c1', 'nope'],
    notes: ['note1'],
  }) + '\n```后记';
  const plan: MemoryDreamPlan = parseDreamModelPlanJson(raw, PARSE_RECORDS, PARSE_CANDIDATES);
  assert.deepEqual(plan.mergeSuggestions, [{
    targetMemoryId: 1, duplicateMemoryIds: [2, 3], mergedContent: '合并后', reason: 'r',
  }]);
  assert.deepEqual(plan.promoteMemoryIds, [1, 2]);
  assert.deepEqual(plan.archiveMemoryIds, [3]);
  assert.deepEqual(plan.ignoreCandidateIds, ['c1']);
  assert.equal(plan.supersedeSuggestions.length, 1);
  assert.equal(plan.supersedeSuggestions[0].newContent, '新的替代内容甲乙丙丁');
  assert.equal(plan.supersedeSuggestions[0].confidence, 0.86);
  assert.deepEqual(plan.notes, ['note1']);
});

test('parseModelPlanJson: id 集校验 — 未知 target/duplicates/oldIds 过滤;空集跳过', () => {
  const raw: string = JSON.stringify({
    merge: [
      { target_memory_id: 99, duplicate_memory_ids: [1] },
      { target_memory_id: 1, duplicate_memory_ids: [99, 1, 2, 2] },
      { target_memory_id: 2, duplicate_memory_ids: [99] },
    ],
    supersede: [
      { old_memory_ids: [99], new_content: '有效的新内容甲乙丙丁' },
      { old_memory_ids: [1, 98], new_content: '短' },
      { old_memory_ids: [1, 98, 1], new_content: '有效的新内容甲乙丙丁' },
    ],
  });
  const plan: MemoryDreamPlan = parseDreamModelPlanJson(raw, PARSE_RECORDS, PARSE_CANDIDATES);
  assert.deepEqual(plan.mergeSuggestions, [{
    targetMemoryId: 1, duplicateMemoryIds: [2], mergedContent: null, reason: '',
  }]);
  assert.equal(plan.supersedeSuggestions.length, 1);
  assert.deepEqual(plan.supersedeSuggestions[0].oldMemoryIds, [1]);
});

test('parseModelPlanJson: supersede 缺省 scope/kind/confidence 回退(0.7 + fromWireName)', () => {
  const raw: string = JSON.stringify({
    supersede: [{ old_memory_ids: [2], new_content: '新替代内容甲乙丙丁戊', scope: 'bogus', kind: 'bogus' }],
  });
  const plan: MemoryDreamPlan = parseDreamModelPlanJson(raw, PARSE_RECORDS, PARSE_CANDIDATES);
  const s: MemorySupersedeSuggestion = plan.supersedeSuggestions[0];
  assert.equal(s.scope, 'long_term');
  assert.equal(s.kind, 'note');
  assert.equal(s.confidence, 0.7);
  assert.equal(s.reason, '');
});

test('parseModelPlanJson: notes take(240) + take(6)', () => {
  // 42 置于第 6 位:map contentOrNull(number→"42")后再 take(6)
  const notes: unknown[] = ['x'.repeat(300), 'n2', 'n3', 'n4', 'n5', 42, 'n7'];
  const plan: MemoryDreamPlan = parseDreamModelPlanJson(
    JSON.stringify({ notes }), PARSE_RECORDS, PARSE_CANDIDATES);
  assert.equal(plan.notes.length, 6);
  assert.equal(plan.notes[0].length, 240);
  // Kotlin contentOrNull 对 number → "42"(JsonPrimitive.content)
  assert.equal(plan.notes[5], '42');
});

test('parseModelPlanJson: Kotlin getter 语义 — 非对象项/根抛错', () => {
  assert.throws(
    () => parseDreamModelPlanJson('{"merge": ["str"]}', PARSE_RECORDS, PARSE_CANDIDATES),
    /not a JsonObject/);
  assert.throws(
    () => parseDreamModelPlanJson('[1,2]', PARSE_RECORDS, PARSE_CANDIDATES),
    /not a JsonObject/);
  assert.throws(
    () => parseDreamModelPlanJson('not json at all', PARSE_RECORDS, PARSE_CANDIDATES));
});

// ===== mergeWith =====

test('mergeWith: other=null 透传;merge 按 target+排序duplicates 去重;各 take 上限', () => {
  const m = (t: number, d: number[]): MemoryMergeSuggestion =>
    ({ targetMemoryId: t, duplicateMemoryIds: d, mergedContent: null, reason: '' });
  const s = (old: number[], content: string): MemorySupersedeSuggestion => ({
    oldMemoryIds: old, newContent: content, scope: 'long_term', kind: 'user', confidence: 0.8, reason: '',
  });
  const local: MemoryDreamPlan = makeMemoryDreamPlan({
    mergeSuggestions: [m(1, [2, 3])],
    promoteMemoryIds: Array.from({ length: 20 }, (_v, i: number): number => i + 1),
    archiveMemoryIds: Array.from({ length: 40 }, (_v, i: number): number => i + 1),
    ignoreCandidateIds: Array.from({ length: 40 }, (_v, i: number): string => `c${i}`),
    supersedeSuggestions: [s([1, 2], '内容甲乙丙丁戊己庚辛')],
    notes: ['a', 'b'],
  });
  assert.equal(mergeDreamPlanWith(local, null), local);

  const other: MemoryDreamPlan = makeMemoryDreamPlan({
    // 同集不同序 → 视为重复被去重
    mergeSuggestions: [m(1, [3, 2]), m(9, [8])],
    promoteMemoryIds: Array.from({ length: 10 }, (_v, i: number): number => i + 15),
    archiveMemoryIds: Array.from({ length: 20 }, (_v, i: number): number => i + 35),
    ignoreCandidateIds: Array.from({ length: 20 }, (_v, i: number): string => `c${i + 30}`),
    supersedeSuggestions: [s([2, 1], '内容甲乙丙丁戊己庚辛'), s([5], '另一条内容甲乙丙丁')],
    notes: ['b', 'c'],
  });
  const merged: MemoryDreamPlan = mergeDreamPlanWith(local, other);
  assert.equal(merged.mergeSuggestions.length, 2); // 1 本地 + 1 新(同集被去重)
  assert.deepEqual(merged.mergeSuggestions[1].targetMemoryId, 9);
  assert.equal(merged.promoteMemoryIds.length, 24); // take(24)
  assert.equal(merged.archiveMemoryIds.length, 48); // take(48)
  assert.equal(merged.ignoreCandidateIds.length, 48); // take(48)
  assert.equal(merged.supersedeSuggestions.length, 2); // 同 oldIds 集+同内容去重
  assert.deepEqual(merged.notes, ['a', 'b', 'c']);
});

// ===== plan 编排(runMemoryDreamPlan) =====

const plannerDeps = (opts: {
  worker?: { enabled: boolean; dreamMaintenanceEnabled: boolean; dreamModelEnabled: boolean };
  records?: MemoryRecord[];
  candidates?: MemoryCandidate[];
  resolution?: { kind: 'ok'; modelId: string } | { kind: 'unavailable' };
  generated?: string;
  generateThrows?: boolean;
  events?: MemoryEvent[];
}): MemoryDreamPlannerDeps => ({
  worker: opts.worker ?? { enabled: true, dreamMaintenanceEnabled: true, dreamModelEnabled: true },
  getAllRecords: async (): Promise<MemoryRecord[]> => opts.records ?? [],
  getPendingCandidates: async (): Promise<MemoryCandidate[]> => opts.candidates ?? [],
  resolveDaydreamModel: async () => opts.resolution ?? { kind: 'ok', modelId: 'm1' },
  generateText: async (): Promise<string> => {
    if (opts.generateThrows === true) throw new Error('boom');
    return opts.generated ?? '{}';
  },
  addEvent: async (event: MemoryEvent): Promise<void> => {
    (opts.events ?? []).push(event);
  },
  now: () => NOW,
});

test('runMemoryDreamPlan: 双源合并 → source=merged + DREAM_PLANNED 消息逐字', async () => {
  const events: MemoryEvent[] = [];
  const expired: MemoryRecord = record({ id: 1, expiresAt: NOW - 1 });
  const reinforced: MemoryRecord = record({ id: 2, content: '独立的可提升记忆',
    createdAt: NOW - 20 * 86400000, reinforcementCount: 2, lastReinforcedAt: NOW });
  const generated: string = JSON.stringify({ promote: [1, 2] });
  const plan: MemoryDreamPlan = await runMemoryDreamPlan(plannerDeps({
    records: [expired, reinforced], generated, events,
  }));
  assert.deepEqual(plan.archiveMemoryIds, [1]);
  assert.deepEqual(plan.promoteMemoryIds, [2]);
  assert.equal(events.length, 1);
  assert.equal(events[0].type, 'dream_planned');
  assert.equal(events[0].message,
    'merge=0, promote=1, archive=1, supersede=0, ignore=0, source=merged');
});

test('runMemoryDreamPlan: 仅模型有变更 → source=model;仅本地 → maintenance;都无 → none', async () => {
  const events1: MemoryEvent[] = [];
  await runMemoryDreamPlan(plannerDeps({
    records: [record({ id: 1 })],
    generated: JSON.stringify({ archive: [1] }), events: events1,
  }));
  assert.ok(events1[0].message.endsWith('source=model'));

  const events2: MemoryEvent[] = [];
  await runMemoryDreamPlan(plannerDeps({
    records: [record({ id: 1, expiresAt: NOW - 1 })],
    worker: { enabled: true, dreamMaintenanceEnabled: true, dreamModelEnabled: false },
    events: events2,
  }));
  assert.ok(events2[0].message.endsWith('source=maintenance'));

  const events3: MemoryEvent[] = [];
  await runMemoryDreamPlan(plannerDeps({ records: [record({ id: 1 })], events: events3 }));
  assert.equal(events3[0].message,
    'merge=0, promote=0, archive=0, supersede=0, ignore=0, source=none');
});

test('runMemoryDreamPlan: 维护关 → 空本地;模型 throw → runCatching null;门/解析不可用 → 无模型', async () => {
  const events1: MemoryEvent[] = [];
  const plan1: MemoryDreamPlan = await runMemoryDreamPlan(plannerDeps({
    records: [record({ id: 1, expiresAt: NOW - 1 })],
    worker: { enabled: true, dreamMaintenanceEnabled: false, dreamModelEnabled: false },
    events: events1,
  }));
  assert.deepEqual(plan1, makeMemoryDreamPlan());
  assert.ok(events1[0].message.endsWith('source=none'));

  const events2: MemoryEvent[] = [];
  await runMemoryDreamPlan(plannerDeps({
    records: [record({ id: 1 })], generateThrows: true, events: events2,
  }));
  assert.ok(events2[0].message.endsWith('source=none'));

  // worker.enabled=false → 模型路径整体跳过
  const events3: MemoryEvent[] = [];
  await runMemoryDreamPlan(plannerDeps({
    worker: { enabled: false, dreamMaintenanceEnabled: false, dreamModelEnabled: true },
    generated: JSON.stringify({ promote: [1] }), events: events3,
  }));
  assert.ok(events3[0].message.endsWith('source=none'));

  const events4: MemoryEvent[] = [];
  await runMemoryDreamPlan(plannerDeps({
    resolution: { kind: 'unavailable' },
    generated: JSON.stringify({ promote: [1] }), events: events4,
  }));
  assert.ok(events4[0].message.endsWith('source=none'));
});

// ===== onlyApplicableToManagedMemories =====

test('onlyApplicable: supersede 五门(core/note/conf<0.70/trim<8/敏感)', () => {
  const recs: MemoryRecord[] = [record({ id: 1 })];
  const mk = (over: Partial<MemorySupersedeSuggestion>): MemorySupersedeSuggestion => ({
    oldMemoryIds: [1], newContent: '有效新内容甲乙丙丁', scope: 'long_term',
    kind: 'user', confidence: 0.8, reason: '', ...over,
  });
  const out: MemoryDreamPlan = dreamPlanOnlyApplicable(makeMemoryDreamPlan({
    supersedeSuggestions: [
      mk({ scope: 'core' }),
      mk({ kind: 'note' }),
      mk({ confidence: 0.69 }),
      mk({ newContent: '  短  ' }),
      mk({ newContent: '我的密码是12345678' }),
      mk({}),
    ],
  }), recs);
  assert.equal(out.supersedeSuggestions.length, 1);
  assert.deepEqual(out.supersedeSuggestions[0].oldMemoryIds, [1]);
});

test('onlyApplicable: supersede oldIds 过滤(canBeSuperseded)+ 去重 + newContent trim + confidence coerce', () => {
  const pinned: MemoryRecord = record({ id: 1, pinned: true });
  const archived: MemoryRecord = record({ id: 2, archived: true });
  const core: MemoryRecord = record({ id: 3, scope: 'core' });
  const sensitive: MemoryRecord = record({ id: 4, content: '包含银行卡号的内容' });
  const ok: MemoryRecord = record({ id: 5 });
  const out: MemoryDreamPlan = dreamPlanOnlyApplicable(makeMemoryDreamPlan({
    supersedeSuggestions: [{
      oldMemoryIds: [1, 2, 3, 4, 5, 5, 99], newContent: '  有效新内容甲乙丙丁  ',
      scope: 'long_term', kind: 'user', confidence: 1.7, reason: '',
    }, {
      oldMemoryIds: [1, 2, 3, 4], newContent: '另一条有效新内容甲乙', scope: 'long_term',
      kind: 'user', confidence: 0.8, reason: '',
    }],
  }), [pinned, archived, core, sensitive, ok]);
  assert.equal(out.supersedeSuggestions.length, 1);
  const s: MemorySupersedeSuggestion = out.supersedeSuggestions[0];
  assert.deepEqual(s.oldMemoryIds, [5]);
  assert.equal(s.newContent, '有效新内容甲乙丙丁');
  assert.equal(s.confidence, 1);
});

test('onlyApplicable: merge redirects by confidence and excludes pinned/core/superseded records', () => {
  const recs: MemoryRecord[] = [
    record({ id: 1, confidence: 0.99, updatedAt: NOW }),
    record({ id: 2, confidence: 1 }),
    record({ id: 3, pinned: true, confidence: 0.5 }),
    record({ id: 4, archived: true }),
    record({ id: 5, scope: 'core' }),
    record({ id: 6 }),
  ];
  const plan: MemoryDreamPlan = makeMemoryDreamPlan({
    supersedeSuggestions: [{
      oldMemoryIds: [6], newContent: '替代内容甲乙丙丁戊', scope: 'long_term',
      kind: 'user', confidence: 0.8, reason: '',
    }],
    mergeSuggestions: [
      // id 2 has the highest confidence; protected records are excluded.
      { targetMemoryId: 1, duplicateMemoryIds: [2, 3, 4, 5], mergedContent: null, reason: '' },
      // 与 supersede 目标(6)重叠 → 6 被排除后 <2 → 丢弃
      { targetMemoryId: 6, duplicateMemoryIds: [4], mergedContent: null, reason: '' },
    ],
  });
  const out: MemoryDreamPlan = dreamPlanOnlyApplicable(plan, recs);
  assert.equal(out.mergeSuggestions.length, 1);
  const merge: MemoryMergeSuggestion = out.mergeSuggestions[0];
  assert.equal(merge.targetMemoryId, 2);
  // duplicates 保 candidates 原序(target+duplicates 拼接序),非排序序(Applier.kt:141-143 filterNot)
  assert.deepEqual(merge.duplicateMemoryIds, [1]);
});

test('onlyApplicable: promote/archive 过滤 + ignore distinct + notes 透传', () => {
  const recs: MemoryRecord[] = [
    record({ id: 1, createdAt: NOW - 20 * 86400000, reinforcementCount: 2, lastReinforcedAt: NOW }),
    record({ id: 2, scope: 'long_term' }),
    record({ id: 3, archived: true }),
    record({ id: 4, pinned: true }),
  ];
  const plan: MemoryDreamPlan = makeMemoryDreamPlan({
    promoteMemoryIds: [1, 2, 3, 99, 1],
    archiveMemoryIds: [1, 2, 3, 4, 1],
    ignoreCandidateIds: ['c1', 'c1', 'c2'],
    notes: ['keep'],
  });
  const out: MemoryDreamPlan = dreamPlanOnlyApplicable(plan, recs, NOW);
  assert.deepEqual(out.promoteMemoryIds, [1]);
  // Already promoted and pinned records cannot also be archived by the same plan.
  assert.deepEqual(out.archiveMemoryIds, []);
  assert.deepEqual(out.ignoreCandidateIds, ['c1', 'c2']);
  assert.deepEqual(out.notes, ['keep']);
});

test('onlyApplicable: archive 排除 merge 集与 supersede 集成员', () => {
  const recs: MemoryRecord[] = [record({ id: 1 }), record({ id: 2 }), record({ id: 3 })];
  const plan: MemoryDreamPlan = makeMemoryDreamPlan({
    mergeSuggestions: [{ targetMemoryId: 1, duplicateMemoryIds: [2], mergedContent: null, reason: '' }],
    supersedeSuggestions: [{
      oldMemoryIds: [3], newContent: '替代内容甲乙丙丁戊', scope: 'long_term',
      kind: 'user', confidence: 0.8, reason: '',
    }],
    archiveMemoryIds: [1, 2, 3],
    promoteMemoryIds: [3],
  });
  const out: MemoryDreamPlan = dreamPlanOnlyApplicable(plan, recs);
  assert.deepEqual(out.archiveMemoryIds, []);
  assert.deepEqual(out.promoteMemoryIds, []);
});

// ===== apply =====

interface ApplyHarness {
  deps: MemoryDreamApplierDeps;
  records: MemoryRecord[];
  candidates: MemoryCandidate[];
  events: MemoryEvent[];
  added: MemoryAddParams[];
}

const applyHarness = (
  records: MemoryRecord[], candidates: MemoryCandidate[] = [],
): ApplyHarness => {
  const state: ApplyHarness = {
    records: [...records], candidates: [...candidates], events: [], added: [],
    deps: {
      getAllRecords: async (): Promise<MemoryRecord[]> => state.records,
      getAllCandidates: async (): Promise<MemoryCandidate[]> => state.candidates,
      upsertRecord: async (r: MemoryRecord): Promise<MemoryRecord> => {
        const index: number = state.records.findIndex((x: MemoryRecord): boolean => x.id === r.id);
        if (index >= 0) state.records[index] = r;
        else state.records.push(r);
        return r;
      },
      addMemory: async (params: MemoryAddParams): Promise<MemoryRecord> => {
        state.added.push(params);
        const id: number = Math.max(0, ...state.records.map((r: MemoryRecord): number => r.id)) + 1;
        const created: MemoryRecord = makeMemoryRecord({
          id, content: params.content, scope: params.scope, kind: params.kind,
          sourceConversationId: params.sourceConversationId,
          sourceMessageIds: params.sourceMessageIds, supersedesIds: params.supersedesIds,
          confidence: params.confidence,
        });
        state.records.push(created);
        return created;
      },
      updateCandidate: async (c: MemoryCandidate): Promise<void> => {
        const index: number = state.candidates.findIndex((x: MemoryCandidate): boolean => x.id === c.id);
        if (index >= 0) state.candidates[index] = c;
      },
      addEvent: async (event: MemoryEvent): Promise<void> => {
        state.events.push(event);
      },
    },
  };
  return state;
};

test('apply: 无变更早退 — 不打 DREAM_APPLIED(MemoryDreamApplier.kt:19)', async () => {
  const h: ApplyHarness = applyHarness([record({ id: 1, scope: 'long_term' })]);
  const out: MemoryDreamPlan = await runMemoryDreamApply(makeMemoryDreamPlan({
    promoteMemoryIds: [1], // long_term → onlyApplicable 过滤 → 无变更
    notes: ['仅备注'],
  }), h.deps);
  assert.equal(memoryDreamPlanHasChanges(out), false);
  assert.equal(h.events.length, 0);
});

test('apply: merge 全流程 — mergedContent trim>=8 否则保留原文 + 逐字事件', async () => {
  const h: ApplyHarness = applyHarness([
    record({ id: 1, content: '原始目标内容甲乙丙丁' }),
    record({ id: 2 }),
  ]);
  await runMemoryDreamApply(makeMemoryDreamPlan({
    mergeSuggestions: [
      { targetMemoryId: 1, duplicateMemoryIds: [2], mergedContent: '  合并后的内容甲乙丙丁  ', reason: '' },
    ],
  }), h.deps);
  const target: MemoryRecord = h.records.find((r: MemoryRecord): boolean => r.id === 1)!;
  const dup: MemoryRecord = h.records.find((r: MemoryRecord): boolean => r.id === 2)!;
  assert.equal(target.content, '合并后的内容甲乙丙丁');
  assert.equal(dup.archived, true);
  assert.deepEqual(h.events.map((e: MemoryEvent): [string, number | null, string] =>
    [e.type, e.memoryId, e.message]), [
    ['memory_updated', 1, 'Updated by dream merge.'],
    ['memory_archived', 2, 'Archived duplicate by dream merge.'],
    ['dream_applied', null, 'Applied dream diff: merge=1, promote=0, archive=0, supersede=0, ignore=0'],
  ]);
});

test('apply: merge mergedContent 过短 → 保留 target.content', async () => {
  const h: ApplyHarness = applyHarness([record({ id: 1 }), record({ id: 2 })]);
  await runMemoryDreamApply(makeMemoryDreamPlan({
    mergeSuggestions: [{ targetMemoryId: 1, duplicateMemoryIds: [2], mergedContent: '短', reason: '' }],
  }), h.deps);
  assert.equal(h.records.find((r: MemoryRecord): boolean => r.id === 1)!.content,
    '既有记忆内容甲乙丙丁');
});

test('apply: reinforced short_term promotes while preserving expiry', async () => {
  const h: ApplyHarness = applyHarness([
    record({ id: 7, assistantId: 'asst-1', expiresAt: NOW + 1000,
      createdAt: NOW - 20 * 86400000, reinforcementCount: 2, lastReinforcedAt: NOW }),
  ]);
  await runMemoryDreamApply(makeMemoryDreamPlan({ promoteMemoryIds: [7] }), { ...h.deps, now: () => NOW });
  const promoted: MemoryRecord = h.records[0];
  assert.equal(promoted.scope, 'long_term');
  assert.equal(promoted.assistantId, '__long_term__');
  assert.equal(promoted.expiresAt, NOW + 1000);
  assert.equal(h.events[0].type, 'memory_updated');
  assert.equal(h.events[0].message, 'Promoted by dream cleanup.');
  assert.equal(h.events[1].message,
    'Applied dream diff: merge=0, promote=1, archive=0, supersede=0, ignore=0');
});

test('apply: archive — archived=true + 逐字事件', async () => {
  const h: ApplyHarness = applyHarness([record({ id: 5 })]);
  await runMemoryDreamApply(makeMemoryDreamPlan({ archiveMemoryIds: [5] }), h.deps);
  assert.equal(h.records[0].archived, true);
  assert.equal(h.events[0].type, 'memory_archived');
  assert.equal(h.events[0].message, 'Archived by dream cleanup.');
});

test('apply: supersede — 新建记忆 + 归档旧 + firstNotNull 会话 + distinct 列表 + 逐字事件', async () => {
  const h: ApplyHarness = applyHarness([
    record({ id: 1, sourceConversationId: null, sourceMessageIds: ['m1', 'm2'] }),
    record({ id: 2, sourceConversationId: 'conv-9', sourceMessageIds: ['m2', 'm3'] }),
  ]);
  await runMemoryDreamApply(makeMemoryDreamPlan({
    supersedeSuggestions: [{
      oldMemoryIds: [1, 2], newContent: '新的替代内容甲乙丙丁', scope: 'long_term',
      kind: 'user', confidence: 0.86, reason: '',
    }],
  }), h.deps);
  assert.equal(h.added.length, 1);
  const added: MemoryAddParams = h.added[0];
  assert.equal(added.content, '新的替代内容甲乙丙丁');
  assert.equal(added.sourceConversationId, 'conv-9');
  assert.deepEqual(added.sourceMessageIds, ['m1', 'm2', 'm3']);
  assert.deepEqual(added.supersedesIds, [1, 2]);
  assert.equal(added.confidence, 0.86);
  const created: MemoryRecord = h.records.find((r: MemoryRecord): boolean => r.id === 3)!;
  assert.equal(created.content, '新的替代内容甲乙丙丁');
  assert.equal(h.records.find((r: MemoryRecord): boolean => r.id === 1)!.archived, true);
  assert.equal(h.records.find((r: MemoryRecord): boolean => r.id === 2)!.archived, true);
  assert.deepEqual(h.events.map((e: MemoryEvent): [string, string] => [e.type, e.message]), [
    ['memory_created', 'Superseded memories: 1,2.'],
    ['memory_archived', 'Archived by dream supersede -> new #3.'],
    ['memory_archived', 'Archived by dream supersede -> new #3.'],
    ['dream_applied', 'Applied dream diff: merge=0, promote=0, archive=0, supersede=1, ignore=0'],
  ]);
});

test('apply: ignore 候选 → status ignored(缺席候选静默跳过)', async () => {
  const h: ApplyHarness = applyHarness([], [
    candidate({ id: 'c1' }), candidate({ id: 'c2' }),
  ]);
  await runMemoryDreamApply(makeMemoryDreamPlan({
    ignoreCandidateIds: ['c1', 'missing'],
  }), h.deps);
  assert.equal(h.candidates.find((c: MemoryCandidate): boolean => c.id === 'c1')!.status, 'ignored');
  assert.equal(h.candidates.find((c: MemoryCandidate): boolean => c.id === 'c2')!.status, 'pending');
  assert.equal(h.events.length, 1);
  assert.equal(h.events[0].message,
    'Applied dream diff: merge=0, promote=0, archive=0, supersede=0, ignore=2');
});

// ===== prompt(MemoryDreamPrompt.kt 全文) =====

test('dream prompt: 模板块逐字 + 记录/候选行格式 + 归档记录不排除(prompt 层由调用方过滤)', () => {
  const recs: MemoryRecord[] = [
    record({ id: 1, scope: 'long_term', kind: 'user', content: '用户喜欢咖啡' }),
  ];
  const cands: MemoryCandidate[] = [
    candidate({ id: 'c1', scope: 'short_term', kind: 'project', content: '项目截止周五' }),
  ];
  const prompt: string = buildMemoryDreamPrompt(recs, cands);
  assert.ok(prompt.startsWith('Review AmberAgent memories and produce a reviewable JSON diff.'));
  assert.ok(prompt.indexOf('"delete_suggestions": ["candidate_id"]') >= 0);
  assert.ok(prompt.indexOf('- delete_suggestions may only contain pending candidate ids, never formal memory ids.') >= 0);
  assert.ok(prompt.indexOf('Memories:\n- #1 [long_term/user] 用户喜欢咖啡 (记录日期=2026-07-28; reinforcement_count=0)\n') >= 0);
  assert.ok(prompt.endsWith('Pending candidates:\n- #c1 [short_term/project] 项目截止周五'));
});
