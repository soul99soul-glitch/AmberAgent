// scheduler 修复回归:signal 贯穿 / topicLocks 清理 / runningJobs token 防 REPLACE 误删

import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createScheduler, isInterruptedPhase, decideReentryAction } from '../main/ets/agent/scheduler.ts';
import type { RunManagerDeps } from '../main/ets/agent/run_manager.ts';
import type { CollectRunFn } from '../main/ets/agent/supervisor_loop.ts';
import type { AbortSignalLike } from '../main/ets/platform/runtime_api.ts';
import type { UIMessage } from '../main/ets/agent/message.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
import type { DeepReadSource, SourcePrefetcher } from '../main/ets/research/source_prefetcher.ts';
import type { AiClient } from '../main/ets/platform/ai_client.ts';
import type { DeepReadOutput } from '../main/ets/domain/models.ts';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';
import type { Observable } from '../main/ets/platform/observable.ts';

const makeSource = (id: string): DeepReadSource => ({
  sourceId: id, url: `https://src.example.com/${id}`, title: `来源标题 ${id} 足够长`,
  source: 'tavily', evidenceText: '证据正文内容'.repeat(50), credibility: 'medium',
  freshness: 'unknown', publishedAt: null, imageCandidates: [],
});

const mockPrefetcher: SourcePrefetcher = {
  collect: async () => ['s1', 's2', 's3', 's4'].map(makeSource),
  cacheSize: () => 0,
};

const mockAi: AiClient = { generateText: async () => [] };

const makeMemoryObservable = (value: DeepReadOutput): Observable<DeepReadOutput> => ({
  subscribe: (_cb): (() => void) => (): void => {},
  getCurrent: (): DeepReadOutput | undefined => value,
});

const makeMemoryRepo = () => {
  const store = new Map<string, DeepReadOutput>();
  return {
    get: (topicId: string): DeepReadOutput | null => store.get(topicId) ?? null,
    save: (topicId: string, _title: string, output: DeepReadOutput): void => { store.set(topicId, output); },
    clear: (topicId: string): void => { store.delete(topicId); },
  };
};

const baseDeps = (collectRun: CollectRunFn): RunManagerDeps => ({
  prefetcher: mockPrefetcher,
  collectRun,
  aiClient: mockAi,
  model: 'm',
  playbookMarkdown: '',
  nowIso: () => '2026-06-23',
  repository: makeMemoryRepo(),
});

// ===== #3a: signal 从 scheduler 贯穿到 collectRun =====

test('scheduler.abort: signal reaches collectRun; run resolves aborted; cleanup done', async () => {
  let seenSignal: AbortSignalLike | undefined;
  let callCount = 0;
  const collectRun: CollectRunFn = async (messages, _label, signal) => {
    callCount++;
    seenSignal = signal;
    // 等 signal 被 abort(遵守 signal 的实现语义)
    if (signal !== undefined) {
      await new Promise<void>((resolve, reject) => {
        if (signal.aborted) { reject(new Error('aborted')); return; }
        // AbortSignalLike 的 addEventListener 可选;node 原生有
        const sig = signal as AbortSignal;
        const timer = setTimeout(resolve, 60_000); // 不触发即挂起,由 abort 打断
        timer.unref?.();
        if (typeof sig.addEventListener === 'function') {
          sig.addEventListener('abort', () => { clearTimeout(timer); reject(new Error('aborted')); }, { once: true });
        } else {
          clearTimeout(timer);
          reject(new Error('aborted'));
        }
      });
    }
    return [...messages, makeAssistantMessage('x')];
  };
  const deps = baseDeps(collectRun);
  const scheduler = createScheduler({
    runManager: deps,
    observeOutput: () => makeMemoryObservable(makeEmptyDeepReadOutput()),
    isInterruptedPhase,
    createAbortController: () => new AbortController(),
  });
  const runPromise = scheduler.run('t1', '标题', { force: true });
  // 等 collectRun 真正开始(进入 supervisor 需要 createRunContext 的异步步)
  const deadline = Date.now() + 10_000;
  while (callCount === 0 && Date.now() < deadline) {
    await new Promise<void>((r): void => { setTimeout(r, 10); });
  }
  assert.ok(callCount > 0, 'collectRun must be invoked');
  assert.ok(seenSignal !== undefined, 'collectRun must receive the scheduler signal');
  assert.equal(scheduler.isRunning('t1'), true);

  scheduler.abort('t1');
  // R20:取消统一归一为 aborted RunResult(不再以 rejection 泄露原始 abort message)
  const aborted = await runPromise;
  assert.equal(aborted.ok, false);
  assert.equal(aborted.error, 'aborted');
  assert.equal(scheduler.isRunning('t1'), false, 'running job must be cleaned up after abort');
});

// ===== #3b: 同 topic 顺序两轮 — 锁正常释放、无死锁 =====

test('scheduler: sequential runs on same topic complete (topicLock release)', async () => {
  const collectRun: CollectRunFn = async (messages) =>
    [...messages, makeAssistantMessage('done')];
  const scheduler = createScheduler({
    runManager: baseDeps(collectRun),
    observeOutput: () => makeMemoryObservable(makeEmptyDeepReadOutput()),
    isInterruptedPhase,
    createAbortController: () => new AbortController(),
  });
  const r1 = await scheduler.run('t1', '标题', { force: true });
  const r2 = await scheduler.run('t1', '标题', { force: true });
  assert.equal(typeof r1.ok, 'boolean');
  assert.equal(typeof r2.ok, 'boolean');
  assert.equal(scheduler.isRunning('t1'), false);
});

// ===== #3c: waitForAbort 超时后 REPLACE — A 轮迟到 finally 不得误删 B 轮注册 =====

test('scheduler: REPLACE after abort-wait timeout keeps B registration (token guard)', { timeout: 20_000 }, async () => {
  // A(第 1 次 collectRun)忽略 signal,挂住不放(触发 waitForAbort 5s 超时);
  // B(第 2 次)挂在自己的 gate 上。两次调用用调用序号区分。
  let collectCalls = 0;
  let releaseA: (() => void) | null = null;
  let releaseB: (() => void) | null = null;
  const gateA = new Promise<void>(r => { releaseA = r; });
  const gateB = new Promise<void>(r => { releaseB = r; });
  const collectRun: CollectRunFn = async (messages) => {
    collectCalls++;
    const n = collectCalls;
    if (n === 1) await gateA;
    else await gateB;
    return [...messages, makeAssistantMessage(`done-${n}`)];
  };
  const depsA = baseDeps(collectRun);

  const runningCount = { n: 0 };
  const cancelTokens: number[] = [];
  const scheduler = createScheduler({
    runManager: depsA,
    observeOutput: () => makeMemoryObservable(makeEmptyDeepReadOutput()),
    isInterruptedPhase,
    createAbortController: () => new AbortController(),
    notifier: {
      notifyRunning: async (): Promise<void> => { runningCount.n++; },
      notifyCompleted: async (): Promise<void> => {},
      cancelRunning: async (_topicId: string, runToken?: number): Promise<void> => {
        if (runToken !== undefined) cancelTokens.push(runToken);
      },
    },
  });
  const runA = scheduler.run('tA', 'A', { force: true });
  runA.catch(() => {}); // A 最终会被释放并结束,吞掉避免 unhandled

  // 等 A 进入 collectRun
  const started = Date.now() + 10_000;
  while (collectCalls === 0 && Date.now() < started) {
    await new Promise<void>((r): void => { setTimeout(r, 10); });
  }
  assert.equal(collectCalls, 1);

  // B:同 topic REPLACE — waitForAbort 5s 超时后仍注册,B 随后阻塞在 A 持有的 topicLock
  const runB = scheduler.run('tA', 'B', { force: true });
  runB.catch(() => {});
  // 等 B 通过准入并注册(runningCount 2);此时 B 未必进入 collectRun(topicLock 被 A 持有)
  const bRegistered = Date.now() + 10_000;
  while (runningCount.n < 2 && Date.now() < bRegistered) {
    await new Promise<void>((r): void => { setTimeout(r, 20); });
  }
  assert.equal(runningCount.n, 2, 'A and B both registered (B via REPLACE timeout)');
  assert.equal(scheduler.isRunning('tA'), true);

  // 释放 A:A 走完剩余 pass → 迟到 finally。无 token guard 时 A 会无条件 delete,
  // B 的注册被抹掉 → 此后 B 进不了 collectRun / isRunning 变 false。
  releaseA!();
  const bStarted = Date.now() + 10_000;
  while (collectCalls < 2 && Date.now() < bStarted) {
    await new Promise<void>((r): void => { setTimeout(r, 20); });
  }
  assert.equal(collectCalls, 2, 'late A finally must NOT delete B registration (token guard)');
  assert.equal(scheduler.isRunning('tA'), true, 'B remains registered after A finishes');
  assert.equal(cancelTokens.length, 0,
    'late A finally must NOT cancel B run notification (token-scoped)');

  // 释放 B:B 完成并自行清除注册
  releaseB!();
  const settleDeadline = Date.now() + 15_000;
  while (scheduler.isRunning('tA') && Date.now() < settleDeadline) {
    await new Promise<void>((r): void => { setTimeout(r, 50); });
  }
  assert.equal(scheduler.isRunning('tA'), false, 'all runs settle after gate release');
});

// ===== R22:完成通知发布成功不被同 id finally cancel 撤回 =====

test('scheduler: completed run does not cancel its just-published notification', async () => {
  const collectRun: CollectRunFn = async (messages) =>
    [...messages, makeAssistantMessage('done')];
  let cancelCalls = 0;
  let completedCalls = 0;
  const scheduler = createScheduler({
    runManager: baseDeps(collectRun),
    observeOutput: () => makeMemoryObservable(makeEmptyDeepReadOutput()),
    isInterruptedPhase,
    createAbortController: () => new AbortController(),
    notifier: {
      notifyRunning: async (): Promise<void> => {},
      notifyCompleted: async (): Promise<void> => { completedCalls++; },
      cancelRunning: async (): Promise<void> => { cancelCalls++; },
    },
  });
  await scheduler.run('tDone', '标题', { force: true });
  assert.equal(completedCalls, 1, 'completed notification published once');
  assert.equal(cancelCalls, 0, 'published completion must not be cancelled on same id');
});

// ===== P2-3:完成通知发布失败 → 仍撤 ongoing,不留永久「正在深度阅读」 =====

test('scheduler: failed completion publish still cancels the ongoing notification', async () => {
  const collectRun: CollectRunFn = async (messages) =>
    [...messages, makeAssistantMessage('done')];
  let cancelCalls = 0;
  const scheduler = createScheduler({
    runManager: baseDeps(collectRun),
    observeOutput: () => makeMemoryObservable(makeEmptyDeepReadOutput()),
    isInterruptedPhase,
    createAbortController: () => new AbortController(),
    notifier: {
      notifyRunning: async (): Promise<void> => {},
      notifyCompleted: async (): Promise<void> => { throw new Error('notification service down'); },
      cancelRunning: async (): Promise<void> => { cancelCalls++; },
    },
  });
  const result = await scheduler.run('tFail', '标题', { force: true });
  // 通知失败不污染深读结果本身
  assert.equal(result.ok, true, `run result must be unaffected: ${result.error}`);
  assert.equal(cancelCalls, 1, 'failed completion publish must still cancel ongoing notification');
});

// ===== UI-F30:重进决策 — 不隐式新建并 REPLACE =====

test('decideReentryAction: running wins; partial/cancel cache needs explicit continue', () => {
  const empty = makeEmptyDeepReadOutput();
  // 活动 run → 仅订阅(即使缓存为完整)
  assert.equal(decideReentryAction(true, empty), 'subscribe');
  // 无缓存无 run → 首次
  assert.equal(decideReentryAction(false, null), 'idle');
  // 完整缓存 → 仅展示
  const complete = { ...empty, generationPhase: 'COMPLETE' as const, generationComplete: true };
  assert.equal(decideReentryAction(false, complete), 'complete');
  assert.equal(decideReentryAction(true, complete), 'subscribe', 'active force run wins over retained completed article');
  // 部分进度(有 sectionStates/phase)→ 显式继续,不自动 REPLACE
  const partial = { ...empty, generationPhase: 'WRITING' as const, sectionStates: { OVERVIEW: { status: 'READY' as const, errorMessage: null } } };
  assert.equal(decideReentryAction(false, partial), 'subscribe');
});

test('scheduler: notifyRunning rejection does not leak runningJobs registration', async () => {
  const collectRun: CollectRunFn = async (messages) =>
    [...messages, makeAssistantMessage('x')];
  const scheduler = createScheduler({
    runManager: baseDeps(collectRun),
    observeOutput: () => makeMemoryObservable(makeEmptyDeepReadOutput()),
    isInterruptedPhase,
    createAbortController: () => new AbortController(),
    notifier: {
      notifyRunning: async (): Promise<void> => { throw new Error('notification service down'); },
      notifyCompleted: async (): Promise<void> => {},
      cancelRunning: async (): Promise<void> => {},
    },
  });
  await assert.rejects(scheduler.run('tN', '标题', { force: true }), /notification service down/);
  assert.equal(scheduler.isRunning('tN'), false, 'registration must be cleaned up when notifyRunning rejects');
});

// ===== 准入锁回归:B/C 并发 REPLACE 时后到者必须 abort 先到者 =====

test('scheduler: concurrent REPLACE admission — later caller aborts the earlier one', { timeout: 20_000 }, async () => {
  // collectRun 遵守 signal:gate 或 abort 任一先到即结束
  let runCounter = 0;
  const runOutcomes: string[] = [];
  let releaseGate: (() => void) | null = null;
  const gate = new Promise<void>(r => { releaseGate = r; });
  const seenSignals: AbortSignalLike[] = [];
  const collectRun: CollectRunFn = async (messages, _label, signal) => {
    runCounter++;
    seenSignals.push(signal as AbortSignal);
    await new Promise<void>((resolve, reject) => {
      const finish = (err?: Error): void => {
        if (signal !== undefined && typeof signal.removeEventListener === 'function' && onAbortRef) {
          signal.removeEventListener('abort', onAbortRef);
        }
        clearTimeout(timer);
        timer.unref?.();
        if (err !== undefined) reject(err); else resolve();
      };
      let onAbortRef: (() => void) | null = null;
      let timer: ReturnType<typeof setTimeout>;
      if (signal !== undefined && !signal.aborted && typeof signal.addEventListener === 'function') {
        onAbortRef = (): void => { finish(new Error('aborted')); };
        // AbortSignalLike 仅两参(abort 至多一次,无需 once)
        signal.addEventListener('abort', onAbortRef);
      }
      timer = setTimeout((): void => finish(), 60_000);
      gate.then((): void => finish(), (): void => finish());
    });
    return [...messages, makeAssistantMessage('done')];
  };
  const scheduler = createScheduler({
    runManager: baseDeps(collectRun),
    observeOutput: () => makeMemoryObservable(makeEmptyDeepReadOutput()),
    isInterruptedPhase,
    createAbortController: () => new AbortController(),
  });
  // A 挂在 gate 上
  const runA = scheduler.run('tR', 'A', { force: true });
  // R20:取消归一为 {ok:false,error:'aborted'} 的 resolved 结果
  runA.then((r): void => { runOutcomes.push(r.error === 'aborted' ? 'A-aborted' : 'A-ok'); },
    (e: Error): void => { runOutcomes.push(`A-err:${e.message}`); });
  const started = Date.now() + 10_000;
  while (runCounter === 0 && Date.now() < started) await new Promise<void>((r): void => { setTimeout(r, 10); });
  assert.ok(runCounter > 0, 'A entered collectRun');

  // B、C 相隔 50ms 并发 REPLACE(旧实现:两者都会通过准入 → 都跑完;
  // 新实现:C 的准入会 abort B 的注册)
  const runB = scheduler.run('tR', 'B', { force: true });
  runB.then((r): void => { runOutcomes.push(r.error === 'aborted' ? 'B-aborted' : 'B-ok'); },
    (e: Error): void => { runOutcomes.push(`B-err:${e.message}`); });
  await new Promise<void>((r): void => { setTimeout(r, 50); });
  const runC = scheduler.run('tR', 'C', { force: true });
  runC.then((r): void => { runOutcomes.push(r.ok ? 'C-ok' : `C-err:${r.error}`); },
    (e: Error): void => { runOutcomes.push(`C-err:${e.message}`); });

  // C 进入准入后 5s 超时等 A → 先开 gate 让链路流动
  await new Promise(r => setTimeout(r, 1_000));
  releaseGate!();
  await new Promise(r => setTimeout(r, 1_000));

  // A 被 B abort(signal)→ A-aborted;B 被 C abort → B-aborted;C 正常完成
  assert.ok(runOutcomes.some(o => o.startsWith('A-aborted') || o.startsWith('A-err')), `A should be aborted: ${runOutcomes}`);
  assert.ok(runOutcomes.some(o => o.startsWith('B-aborted') || o.startsWith('B-err')), `B should be aborted by C: ${runOutcomes}`);
  assert.ok(runOutcomes.includes('C-ok'), `C should complete: ${runOutcomes}`);
  assert.equal(scheduler.isRunning('tR'), false);
});
