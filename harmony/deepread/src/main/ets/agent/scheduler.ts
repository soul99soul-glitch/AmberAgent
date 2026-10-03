// DeepReadScheduler — topic-level admission, cancellation and actual job ownership.
// Entry registers each admitted token in the existing shared generation activity
// set. That platform layer requests dataTransfer while backgrounded and reports
// actual received model content; neither layer promises cross-process resumption.

import type { Observable } from '../platform/observable.ts';
import type { DeepReadOutput, DeepReadTemplateSnapshot } from '../domain/models.ts';
import type { DeepReadGenerationStage, DeepReadGenerationPhase } from '../domain/enums.ts';
import type { RunResult, RunManagerDeps } from '../agent/run_manager.ts';
import { run, runSection, isAborted } from '../agent/run_manager.ts';
import { isComplete, hasDisplayableDeepReadOutput, hasAnyReadySection, withSectionStatus } from '../domain/helpers.ts';
import type { AbortControllerLike } from '../platform/runtime_api.ts';

// ===== Scheduler interface(照搬设计 §6.3) =====

export interface SchedulerRunOptions {
  onProgress?: (stage: DeepReadGenerationPhase, label: string) => void;
  force?: boolean;
  seedUrl?: string | null;
  seedUrls?: string[];
  templateSnapshot?: DeepReadTemplateSnapshot;
}

export interface DeepReadActiveRun { topicId: string; title: string; startedAt: number; stage?: DeepReadGenerationPhase; label?: string; }

export interface DeepReadScheduler {
  // 主入口:UI 调
  run(topicId: string, title: string, opts?: SchedulerRunOptions): Promise<RunResult>;
  // 单 stage 重试:UI"重试该段"按钮调
  runSection(topicId: string, title: string, stage: DeepReadGenerationStage, seedUrl?: string | null, seedUrls?: string[]): Promise<RunResult>;
  // 取消
  abort(topicId: string): void;
  // 观察真实活动作业(缓存 phase 仅用于检测中断)
  observeRunning(topicId: string): Observable<boolean>;
  observeActiveRuns(): Observable<DeepReadActiveRun[]>;
  // 检测中断态(UI 回前台时调)
  detectInterrupted(topicId: string): Promise<boolean>;
  // isBackgrounded 切换(由 UIAbility onBackground/onForeground 调)
  setBackgrounded(topicId: string, backgrounded: boolean): void;
  /** 测试用:runningJobs 是否含 topicId */
  isRunning(topicId: string): boolean;
  /** Actual jobs, including source collection before the first draft is saved. */
  getActiveRuns?(): DeepReadActiveRun[];
}

// ===== 前台主路径实现(照搬设计 §6.4) =====

export interface SchedulerDeps {
  runManager: RunManagerDeps;
  /** 每轮准入后读取一次配置,返回只归属该轮的模型、凭据与规则依赖。 */
  createRunManager?: (topicId: string, title: string, runToken?: number, options?: SchedulerRunOptions) => Promise<RunManagerDeps>;
  /** Entry uses the existing shared background activity set, keyed by this exact job token. */
  onRunActivity?: (topicId: string, runToken: number, active: boolean, title: string) => void;
  // 持久化 observe(真实由 RDB observe;测试注入内存 observable)
  observeOutput: (topicId: string) => Observable<DeepReadOutput>;
  // notifier(真实由 notificationManager;测试 noop)
  // runToken(R22):完成发布需 await;取消只撤当前 token 持有的通知。
  notifier?: {
    notifyRunning: (topicId: string, title: string, runToken?: number) => Promise<void>;
    notifyCompleted: (topicId: string, title: string, complete: boolean, runToken?: number) => Promise<void>;
    notifyFailed?: (topicId: string, title: string, errorMessage: string, runToken?: number) => Promise<void>;
    cancelRunning: (topicId: string, runToken?: number) => Promise<void>;
  };
  // 检查 phase 是否为中断态(COLLECTING/PLANNING/WRITING)
  isInterruptedPhase: (output: DeepReadOutput) => boolean;
  // AbortController 工厂:node 用原生 deps.createAbortController(),ArkTS 注入 shim 实现。
  createAbortController: () => AbortControllerLike;
}

// runningJobs 条目:token 防迟到 finally 误删 REPLACE 后的新注册
interface RunningJob { controller: AbortControllerLike; token: number; title: string; startedAt: number; stage: DeepReadGenerationPhase; label: string; }

export const createScheduler = (deps: SchedulerDeps): DeepReadScheduler => {
  // runningJobs:topicId → RunningJob(REPLACE 语义;token 防 A 轮
  // finally 误删 B 轮已注册的条目)
  const runningJobs = new Map<string, RunningJob>();
  const runningObservers = new Map<string, Set<(running: boolean) => void>>();
  const activeRunObservers = new Set<(runs: DeepReadActiveRun[]) => void>();
  const publishActiveRuns = (): void => {
    const activeRuns: DeepReadActiveRun[] = getActiveRuns();
    for (const observer of activeRunObservers) {
      try { observer(activeRuns); } catch { /* one UI observer cannot interrupt the job */ }
    }
  };
  const publishRunning = (topicId: string): void => {
    publishActiveRuns();
    const observers = runningObservers.get(topicId);
    if (observers === undefined) return;
    const running = runningJobs.has(topicId);
    for (const observer of observers) {
      try { observer(running); } catch { /* 单个 UI 观察者不影响作业收口。 */ }
    }
  };
  let jobTokenSeq = 0;
  // Legacy background marker retained for API compatibility; Entry owns keep-alive.
  const backgroundedTopics = new Set<string>();

  // topicMutex:同 topicId 串行(REPLACE:abort 现有 + 等结束 + 启新)
  const withLock = async <T>(
    locks: Map<string, Promise<unknown>>, topicId: string, fn: () => Promise<T>,
  ): Promise<T> => {
    const prev = locks.get(topicId) ?? Promise.resolve();
    let resolve!: () => void;
    const next = new Promise<void>(r => { resolve = r; });
    // 必须保存链式 promise 本体再入 map;finally 与同一对象比较才能清理,
    // 否则 map 条目永不删除(每次同 topic 运行泄漏一条 resolved promise)
    const queued = prev.then(() => next);
    locks.set(topicId, queued);
    await prev;
    try {
      return await fn();
    } finally {
      resolve();
      if (locks.get(topicId) === queued) locks.delete(topicId);
    }
  };
  const topicLocks = new Map<string, Promise<unknown>>();
  const withTopicLock = <T>(topicId: string, fn: () => Promise<T>): Promise<T> =>
    withLock(topicLocks, topicId, fn);
  // 准入锁:串行化「abort 旧 + waitForAbort + 注册新」段 — B/C 并发 REPLACE
  // 时只放行一个进入注册,后到者会 abort 先到者(否则双双通过,B 白跑)
  const admissionLocks = new Map<string, Promise<unknown>>();
  const withAdmissionLock = <T>(topicId: string, fn: () => Promise<T>): Promise<T> =>
    withLock(admissionLocks, topicId, fn);

  // 取消只经 per-job signal:topic 级共享 marker 会被跨轮迟到 finally 误清/
  // 残留,signal 归属单轮作业,无此竞态
  const abort = (topicId: string): void => {
    const job = runningJobs.get(topicId);
    if (job) job.controller.abort();
  };

  const waitForAbort = async (topicId: string): Promise<void> => {
    // 等当前 run 结束(signal 已贯穿 run → supervisor → collectRun,正常路径
    // 会很快退出;5s 截止是 collectRun 不遵守 signal 时的兜底)
    const deadline = Date.now() + 5_000;
    while (runningJobs.has(topicId) && Date.now() < deadline) {
      await new Promise(r => setTimeout(r, 10));
    }
  };

  // Running, terminal notifications and activity lifetime are shared by full
  // generation and section retries; only their domain operation differs.
  const executeJob = async (
    topicId: string,
    title: string,
    operation: (manager: RunManagerDeps, controller: AbortControllerLike) => Promise<RunResult>,
    options?: SchedulerRunOptions,
  ): Promise<RunResult> => {
    const controller = deps.createAbortController();
    const job: RunningJob = { controller, token: ++jobTokenSeq, title, startedAt: Date.now(), stage: 'IDLE', label: '准备运行' };
    let activityStarted: boolean = false;
    let terminalPublished: boolean = false;
    const ownsJob = (): boolean => runningJobs.get(topicId)?.token === job.token;
    const onProgress = (stage: DeepReadGenerationPhase, label: string): void => {
      if (!ownsJob() || controller.signal.aborted) return;
      job.stage = stage;
      job.label = label;
      publishActiveRuns();
      options?.onProgress?.(stage, label);
    };
    const abortedResult = (): RunResult => ({
      ok: false, output: deps.runManager.repository?.get(topicId, title) ?? makeEmpty(), error: 'aborted',
    });
    const publishFailed = async (message: string): Promise<void> => {
      if (!ownsJob() || deps.notifier?.notifyFailed === undefined) return;
      try {
        await deps.notifier.notifyFailed(topicId, title, message, job.token);
        terminalPublished = true;
      } catch (_notifyError) {
        // Failed terminal delivery leaves cleanup responsible for the ongoing
        // notification, while preserving the original article result/error.
        terminalPublished = false;
      }
    };
    try {
      await withAdmissionLock(topicId, async (): Promise<void> => {
        if (runningJobs.has(topicId)) {
          abort(topicId);
          await waitForAbort(topicId);
        }
        runningJobs.set(topicId, job);
        activityStarted = true;
        // Zero-byte preparing/source-collection activity is registered before
        // any notification, configuration resolution or network await.
        deps.onRunActivity?.(topicId, job.token, true, job.title);
        publishRunning(topicId);
      });
      if (!ownsJob()) return abortedResult();
      await deps.notifier?.notifyRunning(topicId, title, job.token);
      const finalResult: RunResult = await withTopicLock(topicId, async (): Promise<RunResult> => {
        if (controller.signal.aborted) return abortedResult();
        let manager: RunManagerDeps;
        try {
          manager = deps.createRunManager
            ? await deps.createRunManager(topicId, title, job.token, { ...options, onProgress }) : deps.runManager;
        } catch (error) {
          // Configuration fails before run() can record its first failed stage.
          // Keep an existing article intact; an empty composer entry needs a
          // persisted failure so cold reentry offers an explicit retry.
          if (controller.signal.aborted || !ownsJob()) return abortedResult();
          if (error instanceof Error && error.name === 'AbortError') throw error;
          const repository = deps.runManager.repository;
          const cached = repository?.get(topicId, title) ?? null;
          if (repository !== undefined && (cached === null
            || (!hasDisplayableDeepReadOutput(cached) && !hasAnyReadySection(cached)))) {
            const message: string = error instanceof Error ? error.message : String(error);
            await repository.save(topicId, title,
              withSectionStatus(cached ?? makeEmpty(), 'OVERVIEW', 'FAILED', message));
          }
          throw error;
        }
        if (controller.signal.aborted) return abortedResult();
        const managerProgress = manager.onProgress;
        manager = { ...manager, onProgress: (stage: DeepReadGenerationPhase, label: string): void => {
          if (!ownsJob() || controller.signal.aborted) return;
          onProgress(stage, label);
          if (managerProgress !== onProgress) managerProgress?.(stage, label);
        } };
        return operation(manager, controller);
      });
      if (ownsJob() && finalResult.ok && deps.notifier !== undefined) {
        try {
          await deps.notifier.notifyCompleted(topicId, title, finalResult.output.generationComplete, job.token);
          terminalPublished = true;
        } catch (_notifyError) {
          terminalPublished = false;
        }
      } else if (!finalResult.ok && finalResult.error !== 'aborted') {
        await publishFailed(finalResult.error ?? '深度阅读生成失败');
      }
      return finalResult;
    } catch (error) {
      // A rejected real save is not a cancellation merely because the signal
      // was aborted at the same moment; only the explicit AbortError is normalized.
      if (isAborted(controller.signal) && error instanceof Error && error.name === 'AbortError') {
        return abortedResult();
      }
      if (!(error instanceof Error && error.name === 'AbortError')) {
        await publishFailed(error instanceof Error ? error.message : String(error));
      }
      throw error;
    } finally {
      // Every admitted job releases its own activity, including a late job
      // whose topic registration was already replaced by a newer token.
      if (activityStarted) deps.onRunActivity?.(topicId, job.token, false, job.title);
      const owner: boolean = ownsJob();
      if (owner) {
        runningJobs.delete(topicId);
        publishRunning(topicId);
      }
      if (owner && !terminalPublished) {
        // Notification cleanup failure must not replace a saved article result
        // or the original provider/storage error.
        await deps.notifier?.cancelRunning(topicId, job.token).catch((): void => {});
      }
    }
  };

  const runImpl = (
    topicId: string, title: string, opts: SchedulerRunOptions = {},
  ): Promise<RunResult> => executeJob(topicId, title, (manager, controller): Promise<RunResult> =>
    run(manager, topicId, title, {
      force: opts.force, seedUrl: opts.seedUrl, seedUrls: opts.seedUrls, templateSnapshot: opts.templateSnapshot, signal: controller.signal,
    }), opts);

  const runSectionImpl = (
    topicId: string, title: string, stage: DeepReadGenerationStage,
    seedUrl: string | null = null, seedUrls?: string[],
  ): Promise<RunResult> => executeJob(topicId, title, (manager, controller): Promise<RunResult> =>
    runSection(manager, topicId, title, stage, seedUrl, controller.signal, seedUrls));

  const observeRunning = (topicId: string): Observable<boolean> => {
    return {
      subscribe: (callback: (running: boolean) => void): (() => void) => {
        let observers = runningObservers.get(topicId);
        if (observers === undefined) {
          observers = new Set<(running: boolean) => void>();
          runningObservers.set(topicId, observers);
        }
        observers.add(callback);
        callback(runningJobs.has(topicId));
        return (): void => {
          const current = runningObservers.get(topicId);
          if (current === undefined) return;
          current.delete(callback);
          if (current.size === 0) runningObservers.delete(topicId);
        };
      },
      getCurrent: (): boolean => runningJobs.has(topicId),
    };
  };

  const detectInterrupted = async (topicId: string): Promise<boolean> => {
    // phase ∈ {COLLECTING,PLANNING,WRITING} 且 runningJobs 无此 topicId
    if (runningJobs.has(topicId)) return false;
    const output = getCurrent(deps.observeOutput(topicId));
    return output !== undefined && deps.isInterruptedPhase(output);
  };

  const setBackgrounded = (topicId: string, backgrounded: boolean): void => {
    if (backgrounded) backgroundedTopics.add(topicId);
    else backgroundedTopics.delete(topicId);
  };

  const isRunning = (topicId: string): boolean => runningJobs.has(topicId);
  const getActiveRuns = (): DeepReadActiveRun[] => Array.from(runningJobs.entries()).map(entry => ({
    topicId: entry[0], title: entry[1].title, startedAt: entry[1].startedAt, stage: entry[1].stage, label: entry[1].label,
  }));
  const observeActiveRuns = (): Observable<DeepReadActiveRun[]> => ({
    subscribe: (callback: (runs: DeepReadActiveRun[]) => void): (() => void) => {
      activeRunObservers.add(callback);
      callback(getActiveRuns());
      return (): void => { activeRunObservers.delete(callback); };
    },
    getCurrent: (): DeepReadActiveRun[] => getActiveRuns(),
  });

  return {
    run: runImpl,
    runSection: runSectionImpl,
    abort,
    observeRunning,
    observeActiveRuns,
    detectInterrupted,
    setBackgrounded,
    isRunning,
    getActiveRuns,
  };
};

// 中断态 phase 判定(照搬设计 §6.5;VERIFYING=补漏段,同属活动期)
export const isInterruptedPhase = (output: DeepReadOutput): boolean =>
  output.generationPhase === 'COLLECTING' ||
  output.generationPhase === 'PLANNING' ||
  output.generationPhase === 'WRITING' ||
  output.generationPhase === 'VERIFYING';

// ===== 重进文章页决策(UI-F30 契约,供 entry 层调用;纯函数可测) =====
// 重进已完成/进行中深读时:不得隐式新建并 REPLACE 当前轮次。
// - 'idle'     :无本次活动痕迹(首次或无进度空壳),可直接启动新 run
// - 'complete' :完整缓存 → 仅展示,绝不启动新生成
// - 'subscribe':已有 run 在跑 / 有部分或取消缓存 → 仅订阅或显示显式「继续」,不自动 REPLACE
// 活动作业优先:重生成期间旧稿可以保持 COMPLETE,但仍须显示本轮状态/取消入口。
export type DeepReadReentryAction = 'idle' | 'complete' | 'subscribe';

export const decideReentryAction = (
  isRunning: boolean,
  cached: DeepReadOutput | null,
): DeepReadReentryAction => {
  if (isRunning) return 'subscribe';
  if (cached !== null && (cached.generationPhase === 'COMPLETE' || isComplete(cached))) return 'complete';
  if (cached === null) return 'idle';
  // 有部分进度/取消/失败痕迹 → 显式继续,不自动 REPLACE
  if (cached.generationPhase !== 'IDLE' || Object.keys(cached.sectionStates).length > 0) return 'subscribe';
  return 'idle';
};

// ===== 辅助:Observable map + getCurrent(简化;真实 Observable 已有这些) =====

const makeEmpty = (): DeepReadOutput => {
  // 避免循环 import,内联空 output
  return {
    topicType: 'event', generationComplete: false, generationPhase: 'IDLE',
    summary: '', keyEntities: [], timeline: null, corePoints: null,
    analysis: { coreDispute: null, perspectives: [], implications: null, quotes: [] },
    extendedReading: [], heroImageQuery: null, heroImageUrl: null, heroCaption: null,
    heroImageConfidence: null, imageAssets: [], diagram: null, visualDiagnostics: null,
    references: [], sectionStates: {}, sectionQualities: {},
  };
};

// 旧 phase 派生 helper 已不用于 observeRunning;保留至独立清理,活动状态只认 runningJobs。
const mapObservable = <T, U>(obs: Observable<T>, fn: (t: T) => U): Observable<U> => {
  return {
    subscribe: (cb: (value: U) => void): (() => void) => obs.subscribe(t => cb(fn(t))),
    getCurrent: (): U | undefined => {
      const cur = obs.getCurrent();
      return cur !== undefined ? fn(cur) : undefined;
    },
  };
};

const getCurrent = <T>(obs: Observable<T>): T | undefined => obs.getCurrent();
