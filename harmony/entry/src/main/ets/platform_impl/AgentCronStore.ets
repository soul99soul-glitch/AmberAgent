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

const DOMAIN: number = 0x0001;
const TAG: string = 'Chat.AgentCron';

export const AGENT_CRON_TASKS_KV_KEY: string = 'agent_cron_tasks_json';

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
  isPathInside: (root: string, path: string): Promise<boolean> =>
    Promise.resolve(path.startsWith(`${root}/`)),
});

// ===== AgentCronSchedulerPort(应用存活期定时器) =====
//
// 触发控制流 = AgentCronWorker.doWork 等价(prepare→markStarted→执行→
// markCompleted/Failed→scheduleNextRun);runExecutor 默认显式失败(P1)。

export type AgentCronRunExecutor = (taskId: string) => Promise<void>;

// manager 的结构视图(arkts-no-obj-literals-as-types:命名接口)
export interface EntryCronPreparedTask {
  id: string;
}

export interface EntryCronManagerView {
  prepareTriggeredRun(id: string, manual?: boolean): Promise<EntryCronPreparedTask | null>;
  markRunStarted(id: string): Promise<void>;
  markRunCompleted(id: string): Promise<void>;
  markRunFailed(id: string, message: string): Promise<void>;
  scheduleNextRun(id: string): Promise<void>;
}

export interface EntryCronSchedulerDeps {
  // 触发时取 manager(异步单例就绪后调用;打破 manager↔scheduler 循环)
  manager: () => Promise<EntryCronManagerView>;
  runExecutor?: AgentCronRunExecutor;
}

export const createEntryCronSchedulerPort = (deps: EntryCronSchedulerDeps): AgentCronSchedulerPort => {
  const timers: Map<string, number> = new Map();

  const clearTimer = (workName: string): void => {
    const handle: number | undefined = timers.get(workName);
    if (handle !== undefined) {
      clearTimeout(handle);
      timers.delete(workName);
    }
  };

  const fire = (workName: string, taskId: string, manual: boolean): void => {
    clearTimer(workName);
    (async (): Promise<void> => {
      const manager: EntryCronManagerView = await deps.manager();
      const task: EntryCronPreparedTask | null = await manager.prepareTriggeredRun(taskId, manual);
      if (task === null) return;
      await manager.markRunStarted(taskId);
      try {
        const executor: AgentCronRunExecutor = deps.runExecutor ??
          ((_id: string): Promise<void> =>
            Promise.reject(new Error('Cron 后台运行执行器未接线(P1)')));
        await executor(taskId);
        await manager.markRunCompleted(taskId);
      } catch (e) {
        const message: string = e instanceof Error ? e.message : String(e);
        await manager.markRunFailed(taskId, message);
      }
      await manager.scheduleNextRun(taskId);
    })().catch((e: Error) => {
      hilog.error(DOMAIN, TAG, '[cron fire] %{public}s', e.message);
    });
  };

  return {
    schedule: (workName: string, taskId: string, delayMs: number): void => {
      clearTimer(workName);
      // setTimeout 上限 2^31-1ms;超出 → 截断(远期任务由 rescheduleAll
      //   启动重排兜底,登记)
      const clamped: number = Math.min(Math.max(delayMs, 0), 2147483647);
      const handle: number = setTimeout((): void => { fire(workName, taskId, false); }, clamped);
      timers.set(workName, handle);
    },
    runNow: (workName: string, taskId: string): void => {
      // WorkManager enqueueUniqueWork REPLACE:取消待触发,立即运行
      fire(workName, taskId, true);
    },
    cancel: (workName: string): void => {
      clearTimer(workName);
    },
  };
};
