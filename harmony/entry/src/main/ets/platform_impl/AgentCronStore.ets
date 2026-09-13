// AgentCronStore — D-130 entry 承载层
//
// Android 基准:
//   AgentCronManager(context/DataStore/WorkManager/AgentTaskStore)
//   AgentTaskStore(context.filesDir/amberagent/tasks)
//   AgentCronWorker(doWork 控制流:prepare→markStarted→执行→markCompleted/
//     Failed→scheduleNextRun)
//   AmberAgentApp.kt:216-224 rescheduleCronTasks(启动 fire-and-forget)
// 承载映射(登记):
//   - DataStore preferences → ChatKvStore 单键 'agent_cron_tasks_json'
//   - AgentTaskFilePort → fileIo 同步 API
//   - WorkManager → 应用存活期定时器(setTimeout);进程死后唤醒 =
//     WorkSchedulerExtensionAbility(P1 登记);runNow = 立即触发(REPLACE
//     语义:先取消同 workName 的待触发定时器)
//   - Worker 的 ChatService 执行体 = runExecutor 注入;默认显式失败
//     ('Cron 后台运行执行器未接线(P1)')→ markRunFailed 记录,非静默

import { fileIo } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { util } from '@kit.ArkTS';
import type { Context } from '@kit.AbilityKit';
import type {
  KeyValueStore, AgentTaskFilePort, AgentCronPersistencePort, AgentCronSchedulerPort,
} from '@amber/chat-domain';
import type { AbortControllerLike, AbortSignalLike } from '@amber/deepread-domain';

// 段级规范化(沙箱内无 symlink,lexical 等价):'..' 弹栈,'.' 与空段忽略
const normalizeSegments = (p: string): string => {
  const parts: string[] = p.split('/');
  const out: string[] = [];
  for (const part of parts) {
    if (part.length === 0 || part === '.') continue;
    if (part === '..') {
      out.pop();
      continue;
    }
    out.push(part);
  }
  return `/${out.join('/')}`;
};

const DOMAIN: number = 0x0001;
const TAG: string = 'Chat.AgentCron';

export const AGENT_CRON_TASKS_KV_KEY: string = 'agent_cron_tasks_json';

// 应用存活期定时器 + chat run executor 已接线(AppContainer.createEntryCronRunExecutor)。
// 进程死后 WorkSchedulerExtensionAbility 仍 P1(PD-011)。
export const AGENT_CRON_EXECUTION_AVAILABLE: boolean = true;

const filesDirPath = (): string => {
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  return ctx.filesDir;
};

export const agentCronTaskDir = (): string => `${filesDirPath()}/amberagent/tasks`;

export const agentCronFilesDir = (): string => filesDirPath();

// ===== AgentCronPersistencePort(DataStore 'tasks_json' 值等价) =====

export const createEntryCronPersistencePort = (
  kv: KeyValueStore,
): AgentCronPersistencePort => ({
  read: (): Promise<string | null> => kv.get(AGENT_CRON_TASKS_KV_KEY),
  write: (raw: string): Promise<void> => kv.put(AGENT_CRON_TASKS_KV_KEY, raw),
});

// ===== AgentTaskFilePort(fileIo) =====

export const createEntryAgentTaskFilePort = (): AgentTaskFilePort => ({
  mkdirs: (dir: string): Promise<void> => {
    try {
      fileIo.mkdirSync(dir, true);
    } catch {
      // 已存在
    }
    return Promise.resolve();
  },
  listJsonFileNames: (dir: string): Promise<string[]> => {
    try {
      const names: string[] = fileIo.listFileSync(dir);
      return Promise.resolve(names.filter((name: string): boolean => name.endsWith('.json')));
    } catch {
      return Promise.resolve([]);
    }
  },
  readText: (path: string): Promise<string | null> => {
    try {
      const file: fileIo.File = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY);
      try {
        const stat: fileIo.Stat = fileIo.statSync(file.fd);
        const buffer: ArrayBuffer = new ArrayBuffer(stat.size);
        fileIo.readSync(file.fd, buffer);
        return Promise.resolve(
          util.TextDecoder.create('utf-8').decodeToString(new Uint8Array(buffer)));
      } finally {
        fileIo.closeSync(file);
      }
    } catch {
      return Promise.resolve(null);
    }
  },
  writeText: (path: string, text: string): Promise<void> => {
    const file: fileIo.File = fileIo.openSync(
      path, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
    try {
      const bytes: Uint8Array = new util.TextEncoder().encode(text);
      fileIo.writeSync(file.fd, bytes.buffer);
    } finally {
      fileIo.closeSync(file);
    }
    return Promise.resolve();
  },
  delete: (path: string): Promise<void> => {
    try {
      fileIo.unlinkSync(path);
    } catch {
      // 不存在
    }
    return Promise.resolve();
  },
  exists: (path: string): Promise<boolean> => {
    try {
      return Promise.resolve(fileIo.accessSync(path));
    } catch {
      return Promise.resolve(false);
    }
  },
  isPathInside: (root: string, path: string): Promise<boolean> => {
    if (root.length === 0 || path.length === 0 || !path.startsWith('/')) {
      return Promise.resolve(false);
    }
    // 段级规范化后判 containment,防 root/../outside 前缀绕过(SubAgentStore 同款)
    const normRoot: string = normalizeSegments(root);
    const normPath: string = normalizeSegments(path);
    return Promise.resolve(normPath.startsWith(`${normRoot}/`));
  },
});

// ===== AgentCronSchedulerPort(应用存活期定时器) =====
//
// 触发控制流 = AgentCronWorker.doWork 等价(prepare→markStarted→执行→
// markCompleted/Failed→scheduleNextRun);runExecutor 默认显式失败(P1)。

// R17:executor 持有 run 级 AbortSignal;entry 用它贯穿 Provider/工具循环。
export type AgentCronRunExecutor = (taskId: string, signal: AbortSignalLike) => Promise<void>;

// setTimeout 32-bit 上限;超过只重排不执行(R16)。
const CRON_MAX_TIMER_DELAY_MS: number = 2147483647;

// abortFactory 缺省时的惰性信号(永不 abort;ArkTS 禁无上下文对象字面量)。
class NeverAbortSignal implements AbortSignalLike {
  public readonly aborted: boolean = false;
  public addEventListener(_type: string, _listener: () => void): void {}
  public removeEventListener(_type: string, _listener: () => void): void {}
}

// manager 的结构视图(arkts-no-obj-literals-as-types:命名接口)
export interface EntryCronPreparedTask {
  id: string;
}

// R16/R17:定时路径复核 due、终态分类、禁用收口所需的原始快照字段
export interface EntryCronTaskView {
  id: string;
  enabled: boolean;
  nextRunAtMs: number | null;
  lastStatus: string;
}

export interface EntryCronManagerView {
  prepareTriggeredRun(id: string, manual?: boolean): Promise<EntryCronPreparedTask | null>;
  getTask(id: string): Promise<EntryCronTaskView | null>;
  markRunStarted(id: string): Promise<void>;
  markRunCompleted(id: string): Promise<void>;
  markRunFailed(id: string, message: string): Promise<void>;
  markRunCancelled(id: string): Promise<void>;
  markRunSuspend(id: string): Promise<void>;
  scheduleNextRun(id: string): Promise<void>;
  releaseRunClaim(id: string): void;
}

export interface EntryCronSchedulerDeps {
  // 触发时取 manager(异步单例就绪后调用;打破 manager↔scheduler 循环)
  manager: () => Promise<EntryCronManagerView>;
  runExecutor?: AgentCronRunExecutor;
  // R17:run 级 AbortController 工厂(entry 传 createEntryAbortController;
  //   不依赖全局 AbortController,ArkTS 运行时不暴露)
  createAbortController?: () => AbortControllerLike;
  // 可注入时钟(测试共享假时钟;缺省 Date.now)
  nowMs?: () => number;
}

export const createEntryCronSchedulerPort = (deps: EntryCronSchedulerDeps): AgentCronSchedulerPort => {
  if (!AGENT_CRON_EXECUTION_AVAILABLE || deps.runExecutor === undefined) {
    return {
      schedule: (_workName: string, _taskId: string, _delayMs: number): void => {},
      runNow: (_workName: string, _taskId: string): void => {},
      cancel: (_workName: string): void => {},
    };
  }

  const runExecutor: AgentCronRunExecutor = deps.runExecutor;
  const abortFactory: (() => AbortControllerLike) | undefined = deps.createAbortController;
  const timers: Map<string, number> = new Map();
  // workName → taskId(取消时回找在途 controller,不硬编码前缀)
  const workNameToTaskId: Map<string, string> = new Map();
  // R12:entry 侧同步 inFlight——同一 task 定时触发与 manual runNow 不并发。
  //   同步 Set 在首个 await 前占位,是防双 fire 的即时守卫(manager claim
  //   在异步 RMW 之间,无法覆盖「同一 tick 两次 fire」)。
  const inFlight: Set<string> = new Set();
  // R17:taskId → 在途 run 的 AbortController;cancel 时 abort
  const activeAborts: Map<string, AbortControllerLike> = new Map();

  const nowMs = (): number => deps.nowMs !== undefined ? deps.nowMs() : Date.now();

  const clearTimer = (workName: string): void => {
    const handle: number | undefined = timers.get(workName);
    if (handle !== undefined) {
      clearTimeout(handle);
      timers.delete(workName);
    }
  };

  // 收口:按「已删除 / 仍启用+abort / 仍启用+正常 / 已禁用」区分终态,
  //   不把中断记成 Succeeded。删除与禁用优先于 abort 判定——两者都会经
  //   scheduler.cancel 触发 abort,但语义分别是「不复活」与「停当前不重排」。
  const finalizeRun = async (
    manager: EntryCronManagerView, taskId: string, signal: AbortSignalLike,
  ): Promise<void> => {
    const current: EntryCronTaskView | null = await manager.getTask(taskId);
    if (current === null) return;   // 已删除:不 markCompleted / 不复活调度
    if (!current.enabled) {
      // 运行中被禁用:停当前生成,不重排(不复活),不改 nextRunAtMs
      await manager.markRunSuspend(taskId);
      return;
    }
    if (signal.aborted) {
      // 正常取消:终态 Cancelled 并重排下一周期(与 Succeeded 明示区分)
      await manager.markRunCancelled(taskId);
      await manager.scheduleNextRun(taskId);
      return;
    }
    await manager.markRunCompleted(taskId);
    await manager.scheduleNextRun(taskId);
  };

  const startRun = async (
    manager: EntryCronManagerView, taskId: string, manual: boolean,
  ): Promise<void> => {
    const controller: AbortControllerLike | undefined =
      abortFactory !== undefined ? abortFactory() : undefined;
    if (controller !== undefined) activeAborts.set(taskId, controller);
    const signal: AbortSignalLike = controller !== undefined
      ? controller.signal
      : new NeverAbortSignal();
    let claimed: boolean = false;
    try {
      const prepared: EntryCronPreparedTask | null =
        await manager.prepareTriggeredRun(taskId, manual);
      if (prepared === null) return;   // 已被他人 claim / 门未通过 → 不释放他人 claim
      claimed = true;
      await manager.markRunStarted(taskId);
      try {
        await runExecutor(taskId, signal);
      } catch (e) {
        const message: string = e instanceof Error ? e.message : String(e);
        if (signal.aborted) {
          await finalizeRun(manager, taskId, signal);   // abort 引发的异常按取消收口
        } else {
          await manager.markRunFailed(taskId, message);
          await manager.scheduleNextRun(taskId);
        }
        return;   // 已收口,不再走正常完成路径(避免重复 mark)
      }
      await finalizeRun(manager, taskId, signal);
    } finally {
      if (claimed) manager.releaseRunClaim(taskId);   // mark* 已释放;此为异常兜底
    }
  };

  const fire = (workName: string, taskId: string, manual: boolean): void => {
    clearTimer(workName);
    if (inFlight.has(taskId)) return;   // R12:同步防双 fire(定时器重入/manual 冲突)
    inFlight.add(taskId);
    (async (): Promise<void> => {
      try {
        const manager: EntryCronManagerView = await deps.manager();
        if (!manual) {
          // R16:到期复核必须基于**原始持久化 due**,先 getTask 后比;
          //   (不得先 prepare 再比——prepare 已把 nextRunAtMs 重算成未来值)
          const original: EntryCronTaskView | null = await manager.getTask(taskId);
          if (original === null) return;
          if (!original.enabled) return;
          if (original.nextRunAtMs === null || nowMs() < original.nextRunAtMs) {
            await manager.scheduleNextRun(taskId);   // 只重排,不执行
            return;
          }
        }
        await startRun(manager, taskId, manual);
      } finally {
        // claim 生命周期在 startRun 内(mark* 或异常兜底释放);此处只清本地守卫
        inFlight.delete(taskId);
        activeAborts.delete(taskId);
      }
    })().catch((e: Error) => {
      hilog.error(DOMAIN, TAG, '[cron fire] %{public}s', e.message);
    });
  };

  return {
    schedule: (workName: string, taskId: string, delayMs: number): void => {
      clearTimer(workName);
      workNameToTaskId.set(workName, taskId);
      // R16:不静默丢失远期时间——clamp 到 setTimeout 上限,到期回调经 fire
      //   的原始 due 复核判定:未到点只重排(链式),到点才执行。
      const clamped: number = Math.min(Math.max(delayMs, 0), CRON_MAX_TIMER_DELAY_MS);
      const handle: number = setTimeout((): void => { fire(workName, taskId, false); }, clamped);
      timers.set(workName, handle);
    },
    runNow: (workName: string, taskId: string): void => {
      // WorkManager enqueueUniqueWork REPLACE:取消待触发,立即运行(manual 免 due 门)
      workNameToTaskId.set(workName, taskId);
      fire(workName, taskId, true);
    },
    cancel: (workName: string): void => {
      const taskId: string | undefined = workNameToTaskId.get(workName);
      clearTimer(workName);
      // R17:若该任务有在途 run,abort 其 signal;executor 收口后按取消语义终态。
      if (taskId !== undefined) {
        const controller: AbortControllerLike | undefined = activeAborts.get(taskId);
        if (controller !== undefined) controller.abort();
      }
    },
  };
};
