// scheduler 修复回归:signal 贯穿 / topicLocks 清理 / runningJobs token 防 REPLACE 误删

import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createScheduler, isInterruptedPhase } from '../main/ets/agent/scheduler.ts';
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

test('scheduler.abort: signal reaches collectRun; run rejects with abort; cleanup done', async () => {
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
    return [...messages, makeAssistantMessage([{ kind: 'text', text: 'x' }])];
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
  await assert.rejects(runPromise, (e: unknown) => e instanceof Error && /abort/i.test(e.message));
  assert.equal(scheduler.isRunning('t1'), false, 'running job must be cleaned up after abort');
});

// ===== #3b: 同 topic 顺序两轮 — 锁正常释放、无死锁 =====

test('scheduler: sequential runs on same topic complete (topicLock release)', async () => {
  const collectRun: CollectRunFn = async (messages) =>
    [...messages, makeAssistantMessage([{ kind: 'text', text: 'done' }])];
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
  // A:collectRun 忽略 signal,挂住不放(触发 waitForAbort 5s 超时)
  let aCollectCalls = 0;
  let releaseA: (() => void) | null = null;
  const aGate = new Promise<void>(r => { releaseA = r; });
  const collectRunA: CollectRunFn = async (messages) => {
    aCollectCalls++;
    await aGate; // 显式释放前不结束(无视 signal = 故意不遵守,模拟坏实现)
    return [...messages, makeAssistantMessage([{ kind: 'text', text: 'A done' }])];
  };
  const depsA = baseDeps(collectRunA);

  // 通知钩子:每次 cancelRunning 时记录 isRunning — A 的迟到 finally 触发的
  // 首次 cancel 时,B 必须仍处于注册状态(token guard 的直接观测点)
  const runningCount = { n: 0 };
  const isRunningAtCancel: boolean[] = [];
  const scheduler = createScheduler({
    runManager: depsA,
    observeOutput: () => makeMemoryObservable(makeEmptyDeepReadOutput()),
    isInterruptedPhase,
    createAbortController: () => new AbortController(),
    notifier: {
      notifyRunning: async (): Promise<void> => { runningCount.n++; },
      notifyCompleted: async (): Promise<void> => {},
      cancelRunning: async (topicId: string): Promise<void> => {
        isRunningAtCancel.push(scheduler.isRunning(topicId));
      },
    },
  });
  const runA = scheduler.run('tA', 'A', { force: true });
  runA.catch(() => {}); // A 最终会被释放并结束,吞掉避免 unhandled

  // 等 A 进入 collectRun
  const started = Date.now() + 10_000;
  while (aCollectCalls === 0 && Date.now() < started) {
    await new Promise<void>((r): void => { setTimeout(r, 10); });
  }
  assert.ok(aCollectCalls > 0);

  // B:同 topic REPLACE — waitForAbort 5s 超时后仍注册并等待 topicLock
  const runB = scheduler.run('tA', 'B', { force: true });
  runB.catch(() => {});
  await new Promise(r => setTimeout(r, 6_000));
  assert.equal(runningCount.n, 2, 'A and B both registered (B via REPLACE timeout)');
  assert.equal(scheduler.isRunning('tA'), true);

  // 释放 A:A 走完剩余 pass → 迟到 finally。
  // 无 token guard 时 A 会无条件 delete,B 的注册被抹掉 → 首次 cancel 时 isRunning=false
  releaseA!();

  const settleDeadline = Date.now() + 15_000;
  while (scheduler.isRunning('tA') && Date.now() < settleDeadline) {
    await new Promise<void>((r): void => { setTimeout(r, 50); });
  }
  assert.equal(scheduler.isRunning('tA'), false, 'all runs settle after gate release');
  assert.ok(isRunningAtCancel.length >= 1, 'A/B finally both fired cancelRunning');
  assert.equal(isRunningAtCancel[0], true,
    'late A finally must NOT delete B registration (token guard)');
});

test('scheduler: notifyRunning rejection does not leak runningJobs registration', async () => {
  const collectRun: CollectRunFn = async (messages) =>
    [...messages, makeAssistantMessage([{ kind: 'text', text: 'x' }])];
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
    return [...messages, makeAssistantMessage([{ kind: 'text', text: 'done' }])];
  };
  const scheduler = createScheduler({
    runManager: baseDeps(collectRun),
    observeOutput: () => makeMemoryObservable(makeEmptyDeepReadOutput()),
    isInterruptedPhase,
    createAbortController: () => new AbortController(),
  });
  // A 挂在 gate 上
  const runA = scheduler.run('tR', 'A', { force: true });
  runA.then((): void => { runOutcomes.push('A-ok'); }, (e: Error): void => { runOutcomes.push(`A-err:${e.message}`); });
  const started = Date.now() + 10_000;
  while (runCounter === 0 && Date.now() < started) await new Promise<void>((r): void => { setTimeout(r, 10); });
  assert.ok(runCounter > 0, 'A entered collectRun');

  // B、C 相隔 50ms 并发 REPLACE(旧实现:两者都会通过准入 → 都跑完;
  // 新实现:C 的准入会 abort B 的注册)
  const runB = scheduler.run('tR', 'B', { force: true });
  runB.then((): void => { runOutcomes.push('B-ok'); }, (e: Error): void => { runOutcomes.push(`B-err:${e.message}`); });
  await new Promise<void>((r): void => { setTimeout(r, 50); });
  const runC = scheduler.run('tR', 'C', { force: true });
  runC.then((): void => { runOutcomes.push('C-ok'); }, (e: Error): void => { runOutcomes.push(`C-err:${e.message}`); });

  // C 进入准入后 5s 超时等 A → 先开 gate 让链路流动
  await new Promise(r => setTimeout(r, 1_000));
  releaseGate!();
  await new Promise(r => setTimeout(r, 1_000));

  // A 被 B abort(signal)→ A-err;B 被 C abort → B-err;C 正常完成
  assert.ok(runOutcomes.some(o => o.startsWith('A-err')), `A should be aborted: ${runOutcomes}`);
  assert.ok(runOutcomes.some(o => o.startsWith('B-err')), `B should be aborted by C: ${runOutcomes}`);
  assert.ok(runOutcomes.includes('C-ok'), `C should complete: ${runOutcomes}`);
  assert.equal(scheduler.isRunning('tR'), false);
});
