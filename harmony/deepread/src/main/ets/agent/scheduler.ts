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
}

export const createScheduler = (deps: SchedulerDeps): DeepReadScheduler => {
  // runningJobs:topicId → AbortController(REPLACE 语义)
  const runningJobs = new Map<string, AbortController>();
  // backgrounded 标志(切后台暂停用)
  const backgroundedTopics = new Set<string>();
  // aborted 标志(模拟 cancel signal;真实用 AbortController.signal 传给 runManager)
  const abortedTopics = new Set<string>();

  // topicMutex:同 topicId 串行(REPLACE:abort 现有 + 等结束 + 启新)
  const topicLocks = new Map<string, Promise<unknown>>();
  const withTopicLock = async <T>(topicId: string, fn: () => Promise<T>): Promise<T> => {
    const prev = topicLocks.get(topicId) ?? Promise.resolve();
    let resolve!: () => void;
    const next = new Promise<void>(r => { resolve = r; });
    topicLocks.set(topicId, prev.then(() => next));
    await prev;
    try {
      return await fn();
    } finally {
      resolve();
      if (topicLocks.get(topicId) === next) topicLocks.delete(topicId);
    }
  };

  const abort = (topicId: string): void => {
    abortedTopics.add(topicId);
    const controller = runningJobs.get(topicId);
    if (controller) controller.abort();
  };

  const waitForAbort = async (topicId: string): Promise<void> => {
    // 简化:等当前 run 结束(runningJobs 清除);真实有 signal 传播
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
    // dedup:REPLACE 语义
    if (runningJobs.has(topicId)) {
      abort(topicId);
      await waitForAbort(topicId);
    }
    abortedTopics.delete(topicId);
    const controller = new AbortController();
    runningJobs.set(topicId, controller);

    // 不申请 ContinuousTask(0.2);只 ongoing notification
    await deps.notifier?.notifyRunning(topicId, title);

    try {
      const result = await withTopicLock(topicId, async () => {
        // 检查 abort(在锁内再确认)
        if (abortedTopics.has(topicId)) {
          return { ok: false, output: deps.runManager.repository?.get(topicId, title) ?? makeEmpty(), error: 'aborted' };
        }
        return run(deps.runManager, topicId, title, opts);
      });
      const finalResult: RunResult = await result;
      await deps.notifier?.notifyCompleted(topicId, title, finalResult.output.generationComplete);
      return finalResult;
    } finally {
      runningJobs.delete(topicId);
      abortedTopics.delete(topicId);
      await deps.notifier?.cancelRunning(topicId);
    }
  };

  const runSectionImpl = async (
    topicId: string,
    title: string,
    stage: DeepReadGenerationStage,
    seedUrl: string | null,
  ): Promise<RunResult> => {
    if (runningJobs.has(topicId)) {
      abort(topicId);
      await waitForAbort(topicId);
    }
    const controller = new AbortController();
    runningJobs.set(topicId, controller);
    try {
      return await withTopicLock(topicId, () => runSection(deps.runManager, topicId, title, stage, seedUrl));
    } finally {
      runningJobs.delete(topicId);
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

// 中断态 phase 判定(照搬设计 §6.5)
export const isInterruptedPhase = (output: DeepReadOutput): boolean =>
  output.generationPhase === 'COLLECTING' ||
  output.generationPhase === 'PLANNING' ||
  output.generationPhase === 'WRITING';

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
