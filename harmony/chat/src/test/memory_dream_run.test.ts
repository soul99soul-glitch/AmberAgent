// memory_dream_plan_store + memory_dream_run 测试(D-085g)
// 锚点:MemoryDreamPlanStore.kt / MemoryDreamPlanDAO.kt / MemoryDreamRunCoordinator.kt
//   / MemoryDreamScheduler.kt / MemoryDreamWorker.kt / MemoryDreamNotifier.kt
import assert from 'node:assert/strict';
import test from 'node:test';
import { makeMemoryDreamPlan } from '../main/ets/chat/memory_dream.ts';
import type { MemoryDreamPlan } from '../main/ets/chat/memory_dream.ts';
import type {
  MemoryDreamPlanDaoPort, MemoryDreamPlanEntity, MemoryDreamPlanStore,
} from '../main/ets/chat/memory_dream_plan_store.ts';
import {
  createMemoryDreamPlanStore, decodeDreamPlanJson, encodeDreamPlanJson,
  memoryDreamPlanSourceFromWireName, memoryDreamPlanStatusFromWireName,
  persistedDreamPlanSummary,
} from '../main/ets/chat/memory_dream_plan_store.ts';
import type { MemoryDreamRunCoordinatorDeps } from '../main/ets/chat/memory_dream_run.ts';
import {
  computeDelayUntilNextNightWindowMs, dreamSyncShouldSchedule, localDayStart,
  runMemoryDreamRun, runMemoryDreamWorkerOnce,
} from '../main/ets/chat/memory_dream_run.ts';
import type { MemoryEvent } from '../main/ets/chat/memory_models.ts';

// ===== 线格式 =====

test('plan JSON: 全字段声明序 + mergedContent null 显式 + 枚举 wireName 字符串', () => {
  const plan: MemoryDreamPlan = makeMemoryDreamPlan({
    mergeSuggestions: [{ targetMemoryId: 1, duplicateMemoryIds: [2], mergedContent: null, reason: '' }],
    supersedeSuggestions: [{
      oldMemoryIds: [3], newContent: '新内容甲乙丙丁戊', scope: 'short_term',
      kind: 'project', confidence: 0.7, reason: '',
    }],
  });
  const text: string = encodeDreamPlanJson(plan);
  assert.equal(text, '{"mergeSuggestions":[{"targetMemoryId":1,"duplicateMemoryIds":[2],'
    + '"mergedContent":null,"reason":""}],"promoteMemoryIds":[],"archiveMemoryIds":[],'
    + '"ignoreCandidateIds":[],"supersedeSuggestions":[{"oldMemoryIds":[3],'
    + '"newContent":"新内容甲乙丙丁戊","scope":"short_term","kind":"project",'
    + '"confidence":0.7,"reason":""}],"notes":[]}');
  // 往返
  assert.deepEqual(decodeDreamPlanJson(text), plan);
});

test('plan JSON 解码:缺省字段用声明默认;未知枚举值抛错(kotlinx 语义)', () => {
  const minimal: MemoryDreamPlan = decodeDreamPlanJson('{"supersedeSuggestions":'
    + '[{"oldMemoryIds":[1],"newContent":"新内容甲乙丙丁戊","scope":"long_term","kind":"user"}]}');
  assert.equal(minimal.supersedeSuggestions[0].confidence, 0.7);
  assert.equal(minimal.supersedeSuggestions[0].reason, '');
  assert.deepEqual(minimal.promoteMemoryIds, []);
  assert.throws(() => decodeDreamPlanJson('{"supersedeSuggestions":'
    + '[{"oldMemoryIds":[1],"newContent":"x","scope":"bogus","kind":"user"}]}'),
    /Unknown MemoryScope/);
  assert.throws(() => decodeDreamPlanJson('{"supersedeSuggestions":'
    + '[{"oldMemoryIds":[1],"newContent":"x","scope":"core","kind":"bogus"}]}'),
    /Unknown MemoryKind/);
});

test('status/source 回退:未知 → pending/manual(:138-142)', () => {
  assert.equal(memoryDreamPlanStatusFromWireName('applied'), 'applied');
  assert.equal(memoryDreamPlanStatusFromWireName('dismissed'), 'dismissed');
  assert.equal(memoryDreamPlanStatusFromWireName('bogus'), 'pending');
  assert.equal(memoryDreamPlanStatusFromWireName(null), 'pending');
  assert.equal(memoryDreamPlanSourceFromWireName('auto'), 'auto');
  assert.equal(memoryDreamPlanSourceFromWireName('bogus'), 'manual');
  assert.equal(memoryDreamPlanSourceFromWireName(null), 'manual');
});

// ===== Store(DAO 内存实现 + 查询语义)=====

interface DaoHarness {
  dao: MemoryDreamPlanDaoPort;
  rows: MemoryDreamPlanEntity[];
  calls: string[];
}

const daoHarness = (): DaoHarness => {
  const h: DaoHarness = { rows: [], calls: [], dao: {
    getPendingPlan: async (): Promise<MemoryDreamPlanEntity | null> => {
      // status='pending' ORDER created_at DESC LIMIT 1
      const pendings: MemoryDreamPlanEntity[] = h.rows
        .filter((r: MemoryDreamPlanEntity): boolean => r.status === 'pending')
        .sort((a: MemoryDreamPlanEntity, b: MemoryDreamPlanEntity): number => b.createdAt - a.createdAt);
      return pendings.length > 0 ? pendings[0] : null;
    },
    countPlansSince: async (source: string, createdAfter: number): Promise<number> =>
      h.rows.filter((r: MemoryDreamPlanEntity): boolean =>
        r.source === source && r.createdAt >= createdAfter).length,
    updatePendingStatus: async (status: string, dismissedAt: number): Promise<void> => {
      h.calls.push(`updatePendingStatus:${status}:${dismissedAt}`);
      for (const r of h.rows) {
        if (r.status === 'pending') {
          r.status = status;
          r.dismissedAt = dismissedAt;
        }
      }
    },
    markApplied: async (id: string, appliedAt: number): Promise<void> => {
      const r: MemoryDreamPlanEntity | undefined =
        h.rows.find((x: MemoryDreamPlanEntity): boolean => x.id === id);
      if (r !== undefined) {
        r.status = 'applied';
        r.appliedAt = appliedAt;
      }
    },
    markDismissed: async (id: string, dismissedAt: number): Promise<void> => {
      const r: MemoryDreamPlanEntity | undefined =
        h.rows.find((x: MemoryDreamPlanEntity): boolean => x.id === id);
      if (r !== undefined) {
        r.status = 'dismissed';
        r.dismissedAt = dismissedAt;
      }
    },
    insert: async (entity: MemoryDreamPlanEntity): Promise<void> => {
      const index: number = h.rows.findIndex(
        (x: MemoryDreamPlanEntity): boolean => x.id === entity.id);
      if (index >= 0) h.rows[index] = entity;
      else h.rows.push(entity);
    },
  } };
  return h;
};

const planWith = (promote: number[]): MemoryDreamPlan =>
  makeMemoryDreamPlan({ promoteMemoryIds: promote });

test('savePending: 先 dismissed 存量 pending 再插新行 + 返回 persisted(计数列逐字)', async () => {
  const h: DaoHarness = daoHarness();
  const store: MemoryDreamPlanStore = createMemoryDreamPlanStore(h.dao);
  const first = await store.savePending(planWith([1]), 'manual', 1000);
  assert.equal(first.status, 'pending');
  assert.equal(first.source, 'manual');
  const second = await store.savePending(planWith([2, 3]), 'auto', 2000);
  // 每次 savePending 都先无条件 dismissed 存量 pending(PlanStore.kt:54)
  assert.deepEqual(h.calls, ['updatePendingStatus:dismissed:1000', 'updatePendingStatus:dismissed:2000']);
  assert.equal(h.rows.length, 2);
  // 存量 pending 被 dismissed(:54)
  assert.equal(h.rows[0].status, 'dismissed');
  assert.equal(h.rows[0].dismissedAt, 2000);
  assert.equal(h.rows[1].status, 'pending');
  assert.equal(h.rows[1].promoteCount, 2);
  assert.equal(h.rows[1].appliedAt, null);
  assert.equal(h.rows[1].dismissedAt, null);
  // getPendingPlan 返回最新
  const pending = await store.getPendingPlan();
  assert.equal(pending!.id, second.id);
  assert.deepEqual(pending!.plan.promoteMemoryIds, [2, 3]);
  assert.equal(persistedDreamPlanSummary(pending!), '合并 0 · 提升 2 · 归档 0 · 替换 0 · 忽略候选 0');
});

test('recordAutoRun: 插 dismissed/auto 行且不动存量 pending;saveApplied: dismissed 存量 + applied 行', async () => {
  const h: DaoHarness = daoHarness();
  const store: MemoryDreamPlanStore = createMemoryDreamPlanStore(h.dao);
  await store.savePending(planWith([1]), 'manual', 1000);
  await store.recordAutoRun(planWith([]), 1500);
  assert.equal(h.rows.length, 2);
  assert.equal(h.rows[0].status, 'pending'); // 不动存量
  assert.equal(h.rows[1].status, 'dismissed');
  assert.equal(h.rows[1].source, 'auto');
  assert.equal(h.rows[1].appliedAt, null);
  assert.equal(h.rows[1].dismissedAt, 1500);
  // countAutoPlansSince:source='auto' 且 created_at>=after(:46-47)
  assert.equal(await store.countAutoPlansSince(0), 1);
  assert.equal(await store.countAutoPlansSince(1600), 0);

  const applied = await store.saveApplied(planWith([9]), 'manual', 3000);
  assert.equal(applied.status, 'applied');
  assert.equal(h.rows[0].status, 'dismissed'); // 存量 pending 被 dismissed(:100)
  assert.equal(h.rows[2].status, 'applied');
  assert.equal(h.rows[2].appliedAt, 3000);
  assert.equal(h.rows[2].dismissedAt, null);
  // getPendingPlan → null
  assert.equal(await store.getPendingPlan(), null);
});

test('markApplied/markDismissed:按 id 更新 + now 默认 Date.now', async () => {
  const h: DaoHarness = daoHarness();
  const store: MemoryDreamPlanStore = createMemoryDreamPlanStore(h.dao);
  const p = await store.savePending(planWith([1]), 'auto', 1000);
  await store.markApplied(p.id, 5000);
  assert.equal(h.rows[0].status, 'applied');
  assert.equal(h.rows[0].appliedAt, 5000);
  await store.markDismissed(p.id, 6000);
  assert.equal(h.rows[0].status, 'dismissed');
  assert.equal(h.rows[0].dismissedAt, 6000);
});

// ===== Coordinator =====

interface CoordHarness {
  deps: MemoryDreamRunCoordinatorDeps;
  notices: string[];
  store: MemoryDreamPlanStore;
  h: DaoHarness;
}

const coordHarness = (opts: {
  gate?: { enabled: boolean; dreamMaintenanceEnabled: boolean; dreamModelEnabled: boolean; dreamMaxDailyRuns: number };
  plan?: MemoryDreamPlan;
  now?: number;
}): CoordHarness => {
  const h: DaoHarness = daoHarness();
  const store: MemoryDreamPlanStore = createMemoryDreamPlanStore(h.dao);
  const notices: string[] = [];
  const deps: MemoryDreamRunCoordinatorDeps = {
    worker: opts.gate ?? {
      enabled: true, dreamMaintenanceEnabled: true, dreamModelEnabled: false, dreamMaxDailyRuns: 1,
    },
    planStore: store,
    notifier: {
      notifyRunning: (): void => { notices.push('running'); },
      notifyPendingReview: (): void => { notices.push('pending'); },
      notifyFailed: (): void => { notices.push('failed'); },
      cancel: (): void => { notices.push('cancel'); },
    },
    planner: { plan: async (): Promise<MemoryDreamPlan> => opts.plan ?? makeMemoryDreamPlan() },
    now: () => opts.now ?? Date.now(),
  };
  return { deps, notices, store, h };
};

test('coordinator: 门关 → cancel + disabled(:19-22)', async () => {
  const c: CoordHarness = coordHarness({
    gate: { enabled: false, dreamMaintenanceEnabled: true, dreamModelEnabled: false, dreamMaxDailyRuns: 1 },
  });
  assert.equal(await runMemoryDreamRun(c.deps, false), 'disabled');
  assert.deepEqual(c.notices, ['cancel']);
  const c2: CoordHarness = coordHarness({
    gate: { enabled: true, dreamMaintenanceEnabled: false, dreamModelEnabled: false, dreamMaxDailyRuns: 1 },
  });
  assert.equal(await runMemoryDreamRun(c2.deps, true), 'disabled');
  assert.deepEqual(c2.notices, ['cancel']);
});

test('coordinator: 自动日限额 — 今日 auto 计数 >= max(1, dreamMaxDailyRuns) → cancel + auto_daily_limit', async () => {
  const now: number = new Date(2026, 6, 28, 3, 30).getTime(); // 凌晨 03:30
  const c: CoordHarness = coordHarness({ now });
  // 预置一条今日 auto 记录
  await c.store.recordAutoRun(makeMemoryDreamPlan(), new Date(2026, 6, 28, 1, 0).getTime());
  assert.equal(await runMemoryDreamRun(c.deps, false), 'auto_daily_limit');
  assert.deepEqual(c.notices, ['cancel']);
  // 手动运行不受限额(:23 if (!isManualRun))
  const c2: CoordHarness = coordHarness({ now, plan: planWith([1]) });
  await c2.store.recordAutoRun(makeMemoryDreamPlan(), new Date(2026, 6, 28, 1, 0).getTime());
  assert.equal(await runMemoryDreamRun(c2.deps, true), 'pending_review');
});

test('coordinator: 空计划 — 自动 → recordAutoRun + cancel + empty;手动 → 不记录 + empty', async () => {
  const now: number = new Date(2026, 6, 28, 2, 0).getTime();
  const c: CoordHarness = coordHarness({ now });
  assert.equal(await runMemoryDreamRun(c.deps, false), 'empty');
  assert.deepEqual(c.notices, ['running', 'cancel']);
  assert.equal(c.h.rows.length, 1); // recordAutoRun(:40-42)
  assert.equal(c.h.rows[0].source, 'auto');
  assert.equal(c.h.rows[0].status, 'dismissed');

  const c2: CoordHarness = coordHarness({ now });
  assert.equal(await runMemoryDreamRun(c2.deps, true), 'empty');
  assert.deepEqual(c2.notices, ['running', 'cancel']);
  assert.equal(c2.h.rows.length, 0); // 手动不记录
});

test('coordinator: 有变更 → savePending(manual/auto)+ notifyPendingReview + pending_review(:47-50)', async () => {
  const now: number = new Date(2026, 6, 28, 2, 0).getTime();
  const c: CoordHarness = coordHarness({ now, plan: planWith([7]) });
  assert.equal(await runMemoryDreamRun(c.deps, false), 'pending_review');
  assert.deepEqual(c.notices, ['running', 'pending']);
  assert.equal(c.h.rows[0].source, 'auto');
  assert.equal(c.h.rows[0].status, 'pending');

  const c2: CoordHarness = coordHarness({ now, plan: planWith([7]) });
  assert.equal(await runMemoryDreamRun(c2.deps, true), 'pending_review');
  assert.equal(c2.h.rows[0].source, 'manual');
});

// ===== Scheduler 纯逻辑 =====

test('sync 门:enabled && anyDream → 调度;否则 cancel(:24-31)', () => {
  assert.equal(dreamSyncShouldSchedule(
    { enabled: true, dreamMaintenanceEnabled: true, dreamModelEnabled: false }), true);
  assert.equal(dreamSyncShouldSchedule(
    { enabled: true, dreamMaintenanceEnabled: false, dreamModelEnabled: true }), true);
  assert.equal(dreamSyncShouldSchedule(
    { enabled: false, dreamMaintenanceEnabled: true, dreamModelEnabled: true }), false);
  assert.equal(dreamSyncShouldSchedule(
    { enabled: true, dreamMaintenanceEnabled: false, dreamModelEnabled: false }), false);
});

test('夜窗延迟:窗口内 0 / skip → 明日 00:00 / 窗口外 → 明日 00:00(:69-90)', () => {
  // 03:00 窗口内
  const inWindow: Date = new Date(2026, 6, 28, 3, 0);
  assert.equal(computeDelayUntilNextNightWindowMs(inWindow), 0);
  assert.equal(computeDelayUntilNextNightWindowMs(inWindow, true),
    new Date(2026, 6, 29, 0, 0).getTime() - inWindow.getTime());
  // 05:59 仍在窗口内
  assert.equal(computeDelayUntilNextNightWindowMs(new Date(2026, 6, 28, 5, 59)), 0);
  // 06:00 出窗 → 明日 00:00
  const after: Date = new Date(2026, 6, 28, 6, 0);
  assert.equal(computeDelayUntilNextNightWindowMs(after),
    new Date(2026, 6, 29, 0, 0).getTime() - after.getTime());
  // 12:00 → 明日 00:00
  const noon: Date = new Date(2026, 6, 28, 12, 0);
  assert.equal(computeDelayUntilNextNightWindowMs(noon),
    new Date(2026, 6, 29, 0, 0).getTime() - noon.getTime());
  // 00:00 恰入窗
  assert.equal(computeDelayUntilNextNightWindowMs(new Date(2026, 6, 28, 0, 0)), 0);
  // localDayStart
  assert.equal(localDayStart(new Date(2026, 6, 28, 15, 40).getTime()),
    new Date(2026, 6, 28, 0, 0).getTime());
});

// ===== Worker =====

test('worker: 成功路径 + 自动运行后重排(skipCurrentWindow)', async () => {
  const events: MemoryEvent[] = [];
  const failed: string[] = [];
  let rescheduled: number = 0;
  const result: string = await runMemoryDreamWorkerOnce({
    workerGate: { enabled: true, dreamMaintenanceEnabled: true, dreamModelEnabled: false },
    isManualRun: false,
    isStopped: () => false,
    runCoordinator: async () => 'empty',
    addEvent: async (e: MemoryEvent): Promise<void> => { events.push(e); },
    notifyFailed: (m: string): void => { failed.push(m); },
    reschedule: (): void => { rescheduled += 1; },
  });
  assert.equal(result, 'success');
  assert.equal(events.length, 0);
  assert.equal(failed.length, 0);
  assert.equal(rescheduled, 1);
});

test('worker: 失败 → DREAM_FAILED take(500) + notifyFailed 原文 + failure + 仍重排(:35-43)', async () => {
  const events: MemoryEvent[] = [];
  const failed: string[] = [];
  let rescheduled: number = 0;
  const longMsg: string = 'e'.repeat(600);
  const result: string = await runMemoryDreamWorkerOnce({
    workerGate: { enabled: true, dreamMaintenanceEnabled: false, dreamModelEnabled: true },
    isManualRun: false,
    isStopped: () => false,
    runCoordinator: async (): Promise<never> => { throw new Error(longMsg); },
    addEvent: async (e: MemoryEvent): Promise<void> => { events.push(e); },
    notifyFailed: (m: string): void => { failed.push(m); },
    reschedule: (): void => { rescheduled += 1; },
  });
  assert.equal(result, 'failure');
  assert.equal(events.length, 1);
  assert.equal(events[0].type, 'dream_failed');
  assert.equal(events[0].message.length, 500);
  assert.equal(failed[0], longMsg); // notifyFailed 传完整原文(120/500 截断在 Notifier 内)
  assert.equal(rescheduled, 1);
});

test('worker: 手动运行/isStopped/门关 → 不重排(:49-51);无 message 错误 → constructor 名', async () => {
  let rescheduled: number = 0;
  const mk = (opts: { manual?: boolean; stopped?: boolean; enabled?: boolean }) => ({
    workerGate: {
      enabled: opts.enabled ?? true, dreamMaintenanceEnabled: true, dreamModelEnabled: false,
    },
    isManualRun: opts.manual ?? false,
    isStopped: () => opts.stopped ?? false,
    runCoordinator: async (): Promise<'empty'> => 'empty',
    addEvent: async (): Promise<void> => {},
    notifyFailed: (): void => {},
    reschedule: (): void => { rescheduled += 1; },
  });
  await runMemoryDreamWorkerOnce(mk({ manual: true }));
  assert.equal(rescheduled, 0);
  await runMemoryDreamWorkerOnce(mk({ stopped: true }));
  assert.equal(rescheduled, 0);
  await runMemoryDreamWorkerOnce(mk({ enabled: false }));
  assert.equal(rescheduled, 0);

  const events: MemoryEvent[] = [];
  const failed: string[] = [];
  await runMemoryDreamWorkerOnce({
    workerGate: { enabled: true, dreamMaintenanceEnabled: true, dreamModelEnabled: false },
    isManualRun: true,
    isStopped: () => false,
    runCoordinator: async (): Promise<never> => {
      const e: Error = new Error();
      e.message = '';
      throw e;
    },
    addEvent: async (e: MemoryEvent): Promise<void> => { events.push(e); },
    notifyFailed: (m: string): void => { failed.push(m); },
    reschedule: (): void => {},
  });
  assert.equal(failed[0], 'Error'); // error::class.java.simpleName 等价(:36)
  assert.equal(events[0].message, 'Error');
});
