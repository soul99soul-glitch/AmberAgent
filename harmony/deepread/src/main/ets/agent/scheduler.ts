// DeepReadScheduler — 照搬设计文档 §6.3-6.5
//
// interface + 前台主路径实现。后台任务/续跑/中断检测:
// - 逻辑可测部分(topicMutex / runningJobs REPLACE 语义 / abort / observeRunning /
//   detectInterrupted 推导)在本文件实现
// - 真正调 @kit 后台 API(ContinuousTask / notificationManager / UIAbility 生命周期)
//   的部分留接口 + 注释,标注需运行时(Phase 6-7 接 ArkTS)
//
// 0.2:不申请 ContinuousTask(LLM 不符合任何合法 BackgroundMode)。
// 切后台 → 下个 stage 边界暂停(由 UIAbility.onBackground + runStageSupervisorLoop
// 之间的 isBackgrounded 检查实现,Phase 6 接)。

import type { Observable } from '../platform/observable.ts';
import type { DeepReadOutput } from '../domain/models.ts';
import type { DeepReadGenerationStage } from '../domain/enums.ts';
import type { RunResult, RunManagerDeps } from '../agent/run_manager.ts';
import { run, runSection } from '../agent/run_manager.ts';
import type { AbortControllerLike } from '../platform/runtime_api.ts';

// ===== Scheduler interface(照搬设计 §6.3) =====

export interface SchedulerRunOptions {
  force?: boolean;
  seedUrl?: string | null;
}

export interface DeepReadScheduler {
  // 主入口:UI 调
  run(topicId: string, title: string, opts?: SchedulerRunOptions): Promise<RunResult>;
  // 单 stage 重试:UI"重试该段"按钮调
  runSection(topicId: string, title: string, stage: DeepReadGenerationStage, seedUrl?: string | null): Promise<RunResult>;
  // 取消
  abort(topicId: string): void;
  // 观察运行状态(derive 自 RDB)
  observeRunning(topicId: string): Observable<boolean>;
  // 检测中断态(UI 回前台时调)
  detectInterrupted(topicId: string): Promise<boolean>;
  // isBackgrounded 切换(由 UIAbility onBackground/onForeground 调)
  setBackgrounded(topicId: string, backgrounded: boolean): void;
  /** 测试用:runningJobs 是否含 topicId */
  isRunning(topicId: string): boolean;
}

// ===== 前台主路径实现(照搬设计 §6.4) =====

export interface SchedulerDeps {
  runManager: RunManagerDeps;
  // 持久化 observe(真实由 RDB observe;测试注入内存 observable)
  observeOutput: (topicId: string) => Observable<DeepReadOutput>;
  // notifier(真实由 notificationManager;测试 noop)
  notifier?: {
    notifyRunning: (topicId: string, title: string) => Promise<void>;
    notifyCompleted: (topicId: string, title: string, complete: boolean) => Promise<void>;
    cancelRunning: (topicId: string) => Promise<void>;
  };
  // 检查 phase 是否为中断态(COLLECTING/PLANNING/WRITING)
  isInterruptedPhase: (output: DeepReadOutput) => boolean;
  // AbortController 工厂:node 用原生 deps.createAbortController(),ArkTS 注入 shim 实现。
  createAbortController: () => AbortControllerLike;
}

// runningJobs 条目:token 防迟到 finally 误删 REPLACE 后的新注册
interface RunningJob { controller: AbortControllerLike; token: number }

export const createScheduler = (deps: SchedulerDeps): DeepReadScheduler => {
  // runningJobs:topicId → RunningJob(REPLACE 语义;token 防 A 轮
  // finally 误删 B 轮已注册的条目)
  const runningJobs = new Map<string, RunningJob>();
  let jobTokenSeq = 0;
  // backgrounded 标志(切后台暂停用)
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

  const runImpl = async (
    topicId: string,
    title: string,
    opts: SchedulerRunOptions,
  ): Promise<RunResult> => {
    const controller = deps.createAbortController();
    const job: RunningJob = { controller, token: ++jobTokenSeq };
    // dedup:REPLACE 语义(准入段整体串行,防并发双注册)
    await withAdmissionLock(topicId, async (): Promise<void> => {
      if (runningJobs.has(topicId)) {
        abort(topicId);
        await waitForAbort(topicId);
      }
      runningJobs.set(topicId, job);
    });
    const registered: boolean = runningJobs.get(topicId) === job;
    if (!registered) {
      // 准入被后到者取代:本作业直接作废
      return { ok: false, output: deps.runManager.repository?.get(topicId, title) ?? makeEmpty(), error: 'aborted' };
    }

    try {
      // 不申请 ContinuousTask(0.2);只 ongoing notification。
      // 在 try 内:notifyRunning 拒绝时 finally 兜底清理注册,不泄漏
      await deps.notifier?.notifyRunning(topicId, title);
      const result = await withTopicLock(topicId, async () => {
        // 检查 abort(在锁内再确认;per-job signal,不受他轮影响)
        if (controller.signal.aborted) {
          return { ok: false, output: deps.runManager.repository?.get(topicId, title) ?? makeEmpty(), error: 'aborted' };
        }
        return run(deps.runManager, topicId, title, { force: opts.force, seedUrl: opts.seedUrl, signal: controller.signal });
      });
      const finalResult: RunResult = await result;
      await deps.notifier?.notifyCompleted(topicId, title, finalResult.output.generationComplete);
      return finalResult;
    } finally {
      // 仅当注册条目仍是本轮(token 匹配)才清除:避免 A 轮迟到 finally
      // 误删 REPLACE 后 B 轮的注册,导致 C 轮并发进入
      const current = runningJobs.get(topicId);
      if (current !== undefined && current.token === job.token) {
        runningJobs.delete(topicId);
      }
      await deps.notifier?.cancelRunning(topicId);
    }
  };

  const runSectionImpl = async (
    topicId: string,
    title: string,
    stage: DeepReadGenerationStage,
    seedUrl: string | null,
  ): Promise<RunResult> => {
    const controller = deps.createAbortController();
    const job: RunningJob = { controller, token: ++jobTokenSeq };
    await withAdmissionLock(topicId, async (): Promise<void> => {
      if (runningJobs.has(topicId)) {
        abort(topicId);
        await waitForAbort(topicId);
      }
      runningJobs.set(topicId, job);
    });
    if (runningJobs.get(topicId) !== job) {
      return { ok: false, output: deps.runManager.repository?.get(topicId, title) ?? makeEmpty(), error: 'aborted' };
    }
    try {
      return await withTopicLock(topicId, () => {
        // 排队期间被 abort(等锁时)→ 不再进 prefetch/plan,不写仓库状态
        if (controller.signal.aborted) {
          return Promise.resolve({
            ok: false,
            output: deps.runManager.repository?.get(topicId, title) ?? makeEmpty(),
            error: 'aborted',
          } as RunResult);
        }
        return runSection(deps.runManager, topicId, title, stage, seedUrl, controller.signal);
      });
    } finally {
      const current = runningJobs.get(topicId);
      if (current !== undefined && current.token === job.token) {
        runningJobs.delete(topicId);
      }
    }
  };

  const observeRunning = (topicId: string): Observable<boolean> => {
    // derive 自 output observable:phase ∈ 中断态 → running
    const outputObs = deps.observeOutput(topicId);
    // 包一层:map phase → boolean
    return mapObservable(outputObs, o => deps.isInterruptedPhase(o));
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

  return {
    run: runImpl,
    runSection: runSectionImpl,
    abort,
    observeRunning,
    detectInterrupted,
    setBackgrounded,
    isRunning,
  };
};

// 中断态 phase 判定(照搬设计 §6.5;VERIFYING=补漏段,同属活动期)
export const isInterruptedPhase = (output: DeepReadOutput): boolean =>
  output.generationPhase === 'COLLECTING' ||
  output.generationPhase === 'PLANNING' ||
  output.generationPhase === 'WRITING' ||
  output.generationPhase === 'VERIFYING';

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

// Observable<T>.map → Observable<U>(简化包装;真实 Observable interface 无 map,
// 这里用 subscribe 转发 + transform — Phase 6 接 ArkTS 时换成真实 derive)
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
