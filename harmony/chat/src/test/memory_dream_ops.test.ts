// memory_dream_ops 测试(D-088)
// 锚点:SettingAgentMemoryVM.kt(:95-204 dream/候选操作段)+ MemoryRepository.kt:178-191
//   (acceptCandidate 链)+ SettingAgentMemoryPage.kt:1036-1043(DreamReviewSection 摘要行)
import assert from 'node:assert/strict';
import test from 'node:test';
import { makeMemoryCandidate, makeMemoryRecord } from '../main/ets/chat/memory_models.ts';
import type {
  MemoryCandidate, MemoryEvent, MemoryRecord,
} from '../main/ets/chat/memory_models.ts';
import type { MemoryAddParams } from '../main/ets/chat/memory_write.ts';
import { makeMemoryDreamPlan } from '../main/ets/chat/memory_dream.ts';
import type {
  MemoryDreamApplierDeps, MemoryDreamPlan, MemoryDreamPlannerDeps,
} from '../main/ets/chat/memory_dream.ts';
import type {
  MemoryDreamPlanStore, PersistedMemoryDreamPlan,
} from '../main/ets/chat/memory_dream_plan_store.ts';
import {
  acceptMemoryCandidate, applyMemoryDreamPlanOp, dismissMemoryDreamPlanOp, ignoreLowConfidenceMemoryCandidates,
  ignoreMemoryCandidate, memoryDreamReviewSummaryText, planMemoryDreamOp,
} from '../main/ets/chat/memory_dream_ops.ts';
import type { MemoryCandidateOpsDeps } from '../main/ets/chat/memory_dream_ops.ts';

// ===== 测试夹具 =====

const record = (id: number, content: string, opts?: Partial<MemoryRecord>): MemoryRecord =>
  makeMemoryRecord({
    id, content, scope: 'long_term', kind: 'note', assistantId: '__long_term__',
    createdAt: 1000, updatedAt: 1000, ...(opts ?? {}),
  });

const candidate = (id: string, opts?: Partial<MemoryCandidate>): MemoryCandidate =>
  makeMemoryCandidate({
    id, content: `候选内容 ${id}`, scope: 'long_term', kind: 'note',
    status: 'pending', createdAt: 1000, updatedAt: 1000, ...(opts ?? {}),
  });

interface StoreHarness {
  records: MemoryRecord[];
  candidates: MemoryCandidate[];
  events: MemoryEvent[];
  addedMemories: MemoryAddParams[];
}

const makeCandidateOpsDeps = (h: StoreHarness): MemoryCandidateOpsDeps => ({
  getAllCandidates: async (): Promise<MemoryCandidate[]> => [...h.candidates].reverse(),
  addMemory: async (params: MemoryAddParams): Promise<MemoryRecord> => {
    h.addedMemories.push(params);
    const r: MemoryRecord = record(h.records.length + 1, params.content, {
      scope: params.scope, kind: params.kind,
      sourceConversationId: params.sourceConversationId ?? null,
      sourceMessageIds: params.sourceMessageIds ?? [],
      expiresAt: params.expiresAt ?? null,
      confidence: params.confidence ?? 1,
    });
    h.records.push(r);
    return r;
  },
  updateCandidate: async (c: MemoryCandidate): Promise<void> => {
    const i: number = h.candidates.findIndex((x: MemoryCandidate): boolean => x.id === c.id);
    if (i >= 0) h.candidates[i] = { ...c, updatedAt: Date.now() };
  },
  addEvent: async (e: MemoryEvent): Promise<void> => { h.events.push(e); },
});

const makePlannerDeps = (
  h: StoreHarness, plan: MemoryDreamPlan | null,
): MemoryDreamPlannerDeps => ({
  worker: {
    enabled: true, dreamMaintenanceEnabled: plan !== null,
    dreamModelEnabled: false,
  },
  getAllRecords: async (): Promise<MemoryRecord[]> => [...h.records],
  getPendingCandidates: async (): Promise<MemoryCandidate[]> =>
    h.candidates.filter((c: MemoryCandidate): boolean => c.status === 'pending'),
  resolveDaydreamModel: async () => ({ kind: 'unavailable' }),
  generateText: async (): Promise<string> => '',
  addEvent: async (e: MemoryEvent): Promise<void> => { h.events.push(e); },
  now: () => 2000,
});

interface PlanStoreHarness {
  persisted: PersistedMemoryDreamPlan[];
  saved: Array<{ plan: MemoryDreamPlan; source: string }>;
  appliedIds: string[];
  dismissedIds: string[];
  nextId: number;
}

const makePlanStore = (h: PlanStoreHarness): MemoryDreamPlanStore => ({
  getPendingPlan: async (): Promise<PersistedMemoryDreamPlan | null> => {
    const pending: PersistedMemoryDreamPlan[] =
      h.persisted.filter((p: PersistedMemoryDreamPlan): boolean => p.status === 'pending');
    if (pending.length === 0) return null;
    return pending.reduce((a: PersistedMemoryDreamPlan, b: PersistedMemoryDreamPlan) =>
      b.createdAt > a.createdAt ? b : a);
  },
  countAutoPlansSince: async (): Promise<number> => 0,
  savePending: async (plan: MemoryDreamPlan, source): Promise<PersistedMemoryDreamPlan> => {
    h.saved.push({ plan, source });
    const p: PersistedMemoryDreamPlan = {
      id: `plan-${h.nextId++}`, plan, status: 'pending', source,
      createdAt: Date.now(), appliedAt: null, dismissedAt: null,
    };
    h.persisted.push(p);
    return p;
  },
  recordAutoRun: async (): Promise<void> => {},
  saveApplied: async (plan: MemoryDreamPlan, source): Promise<PersistedMemoryDreamPlan> => {
    const p: PersistedMemoryDreamPlan = {
      id: `plan-${h.nextId++}`, plan, status: 'applied', source,
      createdAt: Date.now(), appliedAt: Date.now(), dismissedAt: null,
    };
    h.persisted.push(p);
    return p;
  },
  markApplied: async (id: string): Promise<void> => {
    h.appliedIds.push(id);
    const p: PersistedMemoryDreamPlan | undefined =
      h.persisted.find((x: PersistedMemoryDreamPlan): boolean => x.id === id);
    if (p !== undefined) p.status = 'applied';
  },
  markDismissed: async (id: string): Promise<void> => {
    h.dismissedIds.push(id);
    const p: PersistedMemoryDreamPlan | undefined =
      h.persisted.find((x: PersistedMemoryDreamPlan): boolean => x.id === id);
    if (p !== undefined) p.status = 'dismissed';
  },
});

const makeApplierDeps = (h: StoreHarness): MemoryDreamApplierDeps => ({
  getAllRecords: async (): Promise<MemoryRecord[]> => [...h.records],
  getAllCandidates: async (): Promise<MemoryCandidate[]> => [...h.candidates],
  upsertRecord: async (r: MemoryRecord): Promise<MemoryRecord> => {
    const i: number = h.records.findIndex((x: MemoryRecord): boolean => x.id === r.id);
    if (i >= 0) h.records[i] = r; else h.records.push(r);
    return r;
  },
  addMemory: async (params: MemoryAddParams): Promise<MemoryRecord> => {
    h.addedMemories.push(params);
    const r: MemoryRecord = record(100 + h.records.length, params.content, {
      scope: params.scope, kind: params.kind,
    });
    h.records.push(r);
    return r;
  },
  updateCandidate: async (c: MemoryCandidate): Promise<void> => {
    const i: number = h.candidates.findIndex((x: MemoryCandidate): boolean => x.id === c.id);
    if (i >= 0) h.candidates[i] = c;
  },
  addEvent: async (e: MemoryEvent): Promise<void> => { h.events.push(e); },
});

// ===== 常量(VM 文案逐字)=====

// ===== 审查卡摘要行(Page :1036-1040)=====

test('审查卡摘要行:五计数 + 组/条后缀(Page :1036-1040 逐字)', () => {
  const plan: MemoryDreamPlan = makeMemoryDreamPlan({
    mergeSuggestions: [
      { targetMemoryId: 1, duplicateMemoryIds: [2], mergedContent: null, reason: '' },
    ],
    promoteMemoryIds: [3, 4],
    archiveMemoryIds: [5],
    supersedeSuggestions: [
      { oldMemoryIds: [6], newContent: '新内容', scope: 'long_term', kind: 'note',
        confidence: 0.8, reason: '' },
    ],
    ignoreCandidateIds: ['c1', 'c2', 'c3'],
  });
  assert.equal(
    memoryDreamReviewSummaryText(plan),
    '合并 1 组 · 提升 2 条 · 归档 1 条 · 替换 1 条 · 忽略候选 3 条');
});

// ===== planDream(VM :143-168)=====

test('planDream:有变更且无存量 pending → saved,replacedPending=false', async () => {
  const h: StoreHarness = { records: [], candidates: [], events: [], addedMemories: [] };
  // 重复记录 → maintenance 产出 merge 建议(内容需触发 duplicate 组)
  h.records.push(record(1, '同一段记忆内容甲乙丙'), record(2, '同一段记忆内容甲乙丙'));
  const ph: PlanStoreHarness =
    { persisted: [], saved: [], appliedIds: [], dismissedIds: [], nextId: 1 };
  const result = await planMemoryDreamOp({
    planner: makePlannerDeps(h, makeMemoryDreamPlan()),
    planStore: makePlanStore(ph),
  });
  assert.deepEqual(result, { kind: 'saved', replacedPending: false });
  assert.equal(ph.saved.length, 1);
  assert.equal(ph.saved[0].source, 'manual');
});

test('planDream:有变更且有存量 pending → replacedPending=true(VM :149)', async () => {
  const h: StoreHarness = { records: [], candidates: [], events: [], addedMemories: [] };
  h.records.push(record(1, '同一段记忆内容甲乙丙'), record(2, '同一段记忆内容甲乙丙'));
  const ph: PlanStoreHarness = {
    persisted: [{
      id: 'old', plan: makeMemoryDreamPlan(), status: 'pending', source: 'manual',
      createdAt: 1, appliedAt: null, dismissedAt: null,
    }],
    saved: [], appliedIds: [], dismissedIds: [], nextId: 2,
  };
  const result = await planMemoryDreamOp({
    planner: makePlannerDeps(h, makeMemoryDreamPlan()),
    planStore: makePlanStore(ph),
  });
  assert.deepEqual(result, { kind: 'saved', replacedPending: true });
});

test('planDream:无变更 → no_changes,不落库(VM :148-152)', async () => {
  const h: StoreHarness = { records: [], candidates: [], events: [], addedMemories: [] };
  const ph: PlanStoreHarness =
    { persisted: [], saved: [], appliedIds: [], dismissedIds: [], nextId: 1 };
  const result = await planMemoryDreamOp({
    planner: makePlannerDeps(h, makeMemoryDreamPlan()),
    planStore: makePlanStore(ph),
  });
  assert.deepEqual(result, { kind: 'no_changes' });
  assert.equal(ph.saved.length, 0);
  // runMemoryDreamPlan 仍记录 DREAM_PLANNED 事件(Planner.kt:54-70)
  assert.ok(h.events.some((e: MemoryEvent): boolean => e.type === 'dream_planned'));
});

// ===== applyDreamPlan(VM :171-198)=====

test('applyDreamPlan:无 pending → no_plan,不动 store(VM :172 dreamPlan.value ?: return)', async () => {
  const h: StoreHarness = { records: [], candidates: [], events: [], addedMemories: [] };
  const ph: PlanStoreHarness =
    { persisted: [], saved: [], appliedIds: [], dismissedIds: [], nextId: 1 };
  const result = await applyMemoryDreamPlanOp({
    applier: makeApplierDeps(h), planStore: makePlanStore(ph),
  });
  assert.deepEqual(result, { kind: 'no_plan' });
  assert.equal(ph.appliedIds.length + ph.dismissedIds.length, 0);
});

test('applyDreamPlan:应用后有变更 → markApplied(VM :180-183)', async () => {
  const h: StoreHarness = { records: [], candidates: [], events: [], addedMemories: [] };
  // archive 适用门(Applier.kt:112-172):scope=short_term 且未归档未 pin
  h.records.push(record(1, '被归档的记忆内容测试', {
    scope: 'short_term', kind: 'project', assistantId: '__short_term__',
  }));
  const plan: MemoryDreamPlan = makeMemoryDreamPlan({ archiveMemoryIds: [1] });
  const ph: PlanStoreHarness = {
    persisted: [{
      id: 'p1', plan, status: 'pending', source: 'auto',
      createdAt: 1, appliedAt: null, dismissedAt: null,
    }],
    saved: [], appliedIds: [], dismissedIds: [], nextId: 2,
  };
  const result = await applyMemoryDreamPlanOp({
    applier: makeApplierDeps(h), planStore: makePlanStore(ph),
  });
  assert.deepEqual(result, { kind: 'applied' });
  assert.deepEqual(ph.appliedIds, ['p1']);
  assert.ok(h.records[0].archived);
});

test('applyDreamPlan:应用后无变更 → markDismissed(VM :184-185)', async () => {
  const h: StoreHarness = { records: [], candidates: [], events: [], addedMemories: [] };
  // archive 目标不存在 → applicablePlan 空 → hasChanges=false
  const plan: MemoryDreamPlan = makeMemoryDreamPlan({ archiveMemoryIds: [99] });
  const ph: PlanStoreHarness = {
    persisted: [{
      id: 'p2', plan, status: 'pending', source: 'manual',
      createdAt: 1, appliedAt: null, dismissedAt: null,
    }],
    saved: [], appliedIds: [], dismissedIds: [], nextId: 2,
  };
  const result = await applyMemoryDreamPlanOp({
    applier: makeApplierDeps(h), planStore: makePlanStore(ph),
  });
  assert.deepEqual(result, { kind: 'dismissed_empty' });
  assert.deepEqual(ph.dismissedIds, ['p2']);
});

// ===== dismissDreamPlan(VM :200-204)=====

test('dismissDreamPlan:markDismissed(当前 pending);无 pending → false 不动', async () => {
  const ph: PlanStoreHarness = {
    persisted: [{
      id: 'p3', plan: makeMemoryDreamPlan(), status: 'pending', source: 'manual',
      createdAt: 1, appliedAt: null, dismissedAt: null,
    }],
    saved: [], appliedIds: [], dismissedIds: [], nextId: 2,
  };
  assert.equal(await dismissMemoryDreamPlanOp(makePlanStore(ph)), true);
  assert.deepEqual(ph.dismissedIds, ['p3']);
  const empty: PlanStoreHarness =
    { persisted: [], saved: [], appliedIds: [], dismissedIds: [], nextId: 1 };
  assert.equal(await dismissMemoryDreamPlanOp(makePlanStore(empty)), false);
});

// ===== acceptCandidate(MemoryRepository.kt:178-191)=====

test('acceptCandidate:addMemory 全字段透传 → 状态 ACCEPTED(:178-191)', async () => {
  const h: StoreHarness = { records: [], candidates: [], events: [], addedMemories: [] };
  h.candidates.push(candidate('c1', {
    scope: 'short_term', kind: 'project', sourceConversationId: 'conv-1',
    sourceMessageIds: ['m1', 'm2'], expiresAt: 9999, confidence: 0.75,
  }));
  const deps: MemoryCandidateOpsDeps = makeCandidateOpsDeps(h);
  const r: MemoryRecord = await acceptMemoryCandidate(deps, 'c1');
  assert.equal(h.addedMemories.length, 1);
  const p: MemoryAddParams = h.addedMemories[0];
  assert.equal(p.scope, 'short_term');
  assert.equal(p.kind, 'project');
  assert.equal(p.content, '候选内容 c1');
  assert.equal(p.sourceConversationId, 'conv-1');
  assert.deepEqual(p.sourceMessageIds, ['m1', 'm2']);
  assert.equal(p.expiresAt, 9999);
  assert.equal(p.confidence, 0.75);
  assert.equal(h.candidates[0].status, 'accepted');
  assert.equal(r.content, '候选内容 c1');
});

test('acceptCandidate:未找到 → 抛错(error 逐字 :180)', async () => {
  const h: StoreHarness = { records: [], candidates: [], events: [], addedMemories: [] };
  await assert.rejects(
    () => acceptMemoryCandidate(makeCandidateOpsDeps(h), 'ghost'),
    /Memory candidate #ghost not found/);
});

// ===== ignoreCandidate(VM :101-106)=====

test('ignoreCandidate:状态 IGNORED;未找到 → false 不写(VM :104 ?: return)', async () => {
  const h: StoreHarness = { records: [], candidates: [], events: [], addedMemories: [] };
  h.candidates.push(candidate('c1'));
  const deps: MemoryCandidateOpsDeps = makeCandidateOpsDeps(h);
  assert.equal(await ignoreMemoryCandidate(deps, 'c1'), true);
  assert.equal(h.candidates[0].status, 'ignored');
  assert.equal(await ignoreMemoryCandidate(deps, 'ghost'), false);
});

// ===== ignoreLowConfidenceCandidates(VM :108-137)=====

test('ignoreLowConfidence:仅 pending 且 confidence<0.60;事件+计数(VM :112-129)', async () => {
  const h: StoreHarness = { records: [], candidates: [], events: [], addedMemories: [] };
  h.candidates.push(
    candidate('low1', { confidence: 0.3 }),
    candidate('low2', { confidence: 0.59 }),
    candidate('edge', { confidence: 0.60 }),           // 边界:不小于 → 保留
    candidate('accepted-low', { confidence: 0.1, status: 'accepted' }), // 非 pending → 保留
  );
  const result = await ignoreLowConfidenceMemoryCandidates(makeCandidateOpsDeps(h));
  assert.deepEqual(result, { kind: 'ignored', count: 2 });
  assert.equal(h.candidates[0].status, 'ignored');
  assert.equal(h.candidates[1].status, 'ignored');
  assert.equal(h.candidates[2].status, 'pending');
  assert.equal(h.candidates[3].status, 'accepted');
  // 事件仅在有忽略时打(:119-127),文案 Kotlin Float 插值 0.60f → "0.6"
  assert.equal(h.events.length, 1);
  assert.equal(h.events[0].type, 'candidate_ignored');
  assert.equal(
    h.events[0].message,
    'Batch ignored 2 pending candidates with confidence < 0.6');
});

test('ignoreLowConfidence:空结果 → none,不打事件(VM :119 if 门)', async () => {
  const h: StoreHarness = { records: [], candidates: [], events: [], addedMemories: [] };
  h.candidates.push(candidate('high', { confidence: 0.9 }));
  const result = await ignoreLowConfidenceMemoryCandidates(makeCandidateOpsDeps(h));
  assert.deepEqual(result, { kind: 'none' });
  assert.equal(h.events.length, 0);
  assert.equal(h.candidates[0].status, 'pending');
});
