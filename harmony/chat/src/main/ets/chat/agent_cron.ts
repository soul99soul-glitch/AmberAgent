// agent_cron — cron 定时任务域面(D-130)
//
// Android 基准:
//   feature/cron/.../CronExpression.kt(全文 106)— 五字段 cron 解析 +
//     nextRunAfter(逐分钟扫描,366 天上限)
//   feature/cron/.../AgentCronModels.kt(全文 35)— Task/Status/Store
//   feature/cron/.../AgentCronManager.kt(全文 389)— DataStore 持久化 +
//     WorkManager 调度 + AgentTaskStore 看板登记
//   feature/tools/.../AgentCronTools.kt(全文 150)— 四工具
//   core/agent-utils/.../ToolJson.kt + feature/tools/access/.../ToolJson.kt
//     (string/requiredString/boolean/textJson 助手)
//   LocalTools.kt:181(注册位 = permissions_status 之后/runPlanUpdate 之前)
// 承载层适配(登记):
//   - ZonedDateTime/ZoneId → Intl.DateTimeFormat(timeZone) 逐分钟取本地字段
//     (hourCycle h23;weekday 短名映射 Java MONDAY=1..SUNDAY=7);
//     deadline = cursor.plusDays(366) 的 zoned 语义 → epoch +366 天
//     (DST 边界 ±1h 内无实际匹配差异)
//   - DataStore preferences → AgentCronPersistencePort(单 raw 字符串读写);
//     decodeFromBackingFile(preferences_pb 二进制恢复)= Android 特有灾难
//     恢复路径,HarmonyOS KV 单一事实源 → 不移植(登记)
//   - WorkManager → AgentCronSchedulerPort(schedule/runNow/cancel);
//     entry = 应用存活期定时器;进程死后唤醒 =
//     WorkSchedulerExtensionAbility 基础设施(P1 登记)
//   - Uuid → newId()(ids.ts,UUID v4 同格式)
//   - AgentCronWorker(161 行,doWork 执行体:prepare→markStarted→
//     ChatService 发送+等待完成→markCompleted/Failed→通知→scheduleNextRun)
//     = 运行执行器端口 AgentCronRunExecutor;entry 接线 = P1(后台运行
//     基础设施);Worker 的通知/前台服务 = 平台层 P1

import type { JsonObject, JsonValue } from './json.ts';
import { newId } from './ids.ts';
import type {
  AgentTaskSnapshot, AgentTaskSnapshotInit, AgentTaskStatus, AgentTaskStore,
} from './agent_task.ts';
import {
  makeAgentTaskSnapshot, agentTaskStatusToQueueState,
} from './agent_task.ts';

import { cronExpressionParse, cronIsValidZoneId, cronSystemZoneId, cronNextRunAfter } from './cron_expression.ts';
import type { CronExpression } from './cron_expression.ts';
export { cronExpressionParse, cronIsValidZoneId, cronSystemZoneId, cronNextRunAfter } from './cron_expression.ts';
export type { CronExpression } from './cron_expression.ts';
export { AgentCronTools } from './agent_cron_tools.ts';

// ===== AgentCronModels(全文 35;kotlinx camelCase 键) =====

// Cancelled/Suspend 为本实现新增终态(R17);解码沿用 AGENT_CRON_STATUS_NAMES。
export type AgentCronTaskStatus =
  'Idle' | 'Queued' | 'Running' | 'Succeeded' | 'Failed' | 'Cancelled' | 'Suspend';

// R17:取消/停止的持久化说明(用户可见,区别于正常 Succeeded)。
const CANCELLED_MESSAGE: string = 'Run cancelled; next cycle remains scheduled.';
const SUSPEND_MESSAGE: string = 'Task disabled while running; run stopped.';

export interface AgentCronTask {
  id: string;
  title: string;
  prompt: string;
  cronExpression: string;
  timezoneId: string;
  conversationId: string;
  enabled: boolean;
  createdAtMs: number;
  updatedAtMs: number;
  nextRunAtMs: number | null;
  lastRunAtMs: number | null;
  lastStatus: AgentCronTaskStatus;   // 默认 Idle
  lastError: string | null;
  runCount: number;                  // 默认 0
}

export interface AgentCronTaskInit {
  id: string;
  title: string;
  prompt: string;
  cronExpression: string;
  timezoneId: string;
  conversationId: string;
  enabled: boolean;
  createdAtMs: number;
  updatedAtMs: number;
  nextRunAtMs?: number | null;
  lastRunAtMs?: number | null;
  lastStatus?: AgentCronTaskStatus;
  lastError?: string | null;
  runCount?: number;
}

export const makeAgentCronTask = (init: AgentCronTaskInit): AgentCronTask => ({
  id: init.id,
  title: init.title,
  prompt: init.prompt,
  cronExpression: init.cronExpression,
  timezoneId: init.timezoneId,
  conversationId: init.conversationId,
  enabled: init.enabled,
  createdAtMs: init.createdAtMs,
  updatedAtMs: init.updatedAtMs,
  nextRunAtMs: init.nextRunAtMs !== undefined ? init.nextRunAtMs : null,
  lastRunAtMs: init.lastRunAtMs !== undefined ? init.lastRunAtMs : null,
  lastStatus: init.lastStatus ?? 'Idle',
  lastError: init.lastError !== undefined ? init.lastError : null,
  runCount: init.runCount ?? 0,
});

const AGENT_CRON_STATUS_NAMES: AgentCronTaskStatus[] = [
  'Idle', 'Queued', 'Running', 'Succeeded', 'Failed', 'Cancelled', 'Suspend',
];

// ===== 端口 =====

// DataStore preferences 等价:单 raw 字符串('tasks_json' 值)
export interface AgentCronPersistencePort {
  read(): Promise<string | null>;
  write(raw: string): Promise<void>;
}

// WorkManager 等价:唯一工作名调度/立即运行/取消
export interface AgentCronSchedulerPort {
  schedule(workName: string, taskId: string, delayMs: number): void;
  runNow(workName: string, taskId: string): void;
  cancel(workName: string): void;
}

// R12:replaceTasks 读-改-写互斥(promise 链,同 agent_task/agent_prompt_config 既有模式)。
// 异步 Promise 队列保证同一 manager 内 RMW 串行,避免并发 prepare/mark 丢 runCount 更新。
class AsyncMutex {
  private tail: Promise<void> = Promise.resolve();

  withLock<T>(block: () => Promise<T>): Promise<T> {
    const run: Promise<T> = this.tail.then(block);
    this.tail = run.then((): void => undefined, (): void => undefined);
    return run;
  }
}

// ===== AgentCronManager(全文 389) =====

const cronTaskToJson = (task: AgentCronTask): JsonObject => {
  const j: JsonObject = {};
  j['id'] = task.id;
  j['title'] = task.title;
  j['prompt'] = task.prompt;
  j['cronExpression'] = task.cronExpression;
  j['timezoneId'] = task.timezoneId;
  j['conversationId'] = task.conversationId;
  j['enabled'] = task.enabled;
  j['createdAtMs'] = task.createdAtMs;
  j['updatedAtMs'] = task.updatedAtMs;
  j['nextRunAtMs'] = task.nextRunAtMs !== null ? task.nextRunAtMs : null;
  j['lastRunAtMs'] = task.lastRunAtMs !== null ? task.lastRunAtMs : null;
  j['lastStatus'] = task.lastStatus;
  j['lastError'] = task.lastError !== null ? task.lastError : null;
  j['runCount'] = task.runCount;
  return j;
};

export class AgentCronManager {
  private readonly mutex: AsyncMutex = new AsyncMutex();
  // R12:同任务原子 claim(同步 Set,先占后 await)。prepare 占,mark* 释放;
  //   防止同一任务定时触发与手动 runNow 重叠执行(entry 侧另有同步 inFlight 守卫)。
  private readonly claimedRuns: Set<string> = new Set();

  constructor(
    private readonly persistence: AgentCronPersistencePort,
    private readonly scheduler: AgentCronSchedulerPort,
    private readonly agentTaskStore: AgentTaskStore,
  ) {}

  // :48-50  HarmonyOS:单一 KV 源(backing-file 恢复 = Android 特有,登记)
  async listTasks(): Promise<AgentCronTask[]> {
    return this.decode(await this.persistence.read());
  }

  async listTasksSnapshot(): Promise<AgentCronTask[]> {
    return this.decode(await this.persistence.read());
  }

  // R16/R17 entry 回调:按 id 读取原始持久化快照(定时器到期前复核 due 用;
  //   不得先 prepare 再比——prepare 会把 nextRunAtMs 重算成未来值)。
  async getTask(id: string): Promise<AgentCronTask | null> {
    const tasks: AgentCronTask[] = await this.listTasks();
    const found: AgentCronTask | undefined = tasks.find((t: AgentCronTask): boolean => t.id === id);
    return found !== undefined ? found : null;
  }

  // :59-92
  async createTask(
    title: string, prompt: string, cronExpression: string,
    timezoneId: string | null, enabled: boolean,
  ): Promise<AgentCronTask> {
    const safePrompt: string = prompt.trim();
    if (safePrompt.length === 0) throw new Error('Cron task prompt cannot be blank');
    const safeTitle: string = title.trim().length > 0 ? title.trim() : safePrompt.substring(0, 32);
    const zone: string = this.resolveZone(timezoneId);
    const expression: CronExpression = cronExpressionParse(cronExpression);
    const now: number = Date.now();
    const nextRunAt: number | null = enabled ? cronNextRunAfter(expression, now, zone) : null;
    if (enabled && nextRunAt === null) {
      throw new Error('Cron expression has no run time in the next 366 days');
    }
    const task: AgentCronTask = makeAgentCronTask({
      id: newId(),
      title: safeTitle.substring(0, 80),
      prompt: safePrompt,
      cronExpression: expression.raw,
      timezoneId: zone,
      conversationId: newId(),
      enabled,
      createdAtMs: now,
      updatedAtMs: now,
      nextRunAtMs: nextRunAt,
    });
    await this.replaceTasks((tasks: AgentCronTask[]): AgentCronTask[] => [...tasks, task]);
    this.schedule(task);
    await this.agentTaskStore.register(
      this.toAgentTaskSnapshot(task), this.cancelCallbackFor(task.id));
    return task;
  }

  // :94-134
  async updateTask(
    id: string,
    patch: {
      title?: string | null;
      prompt?: string | null;
      cronExpression?: string | null;
      timezoneId?: string | null;
      enabled?: boolean | null;
    } = {},
  ): Promise<AgentCronTask> {
    let updated: AgentCronTask | null = null;
    await this.replaceTasks((tasks: AgentCronTask[]): AgentCronTask[] =>
      tasks.map((task: AgentCronTask): AgentCronTask => {
        if (task.id !== id) return task;
        const titleRaw: string | null = patch.title !== undefined ? patch.title : null;
        const promptRaw: string | null = patch.prompt !== undefined ? patch.prompt : null;
        const cronRaw: string | null = patch.cronExpression !== undefined ? patch.cronExpression : null;
        const nextTitle: string =
          titleRaw !== null && titleRaw.trim().length > 0 ? titleRaw.trim().substring(0, 80) : task.title;
        const nextPrompt: string =
          promptRaw !== null && promptRaw.trim().length > 0 ? promptRaw.trim() : task.prompt;
        const nextCron: string =
          cronRaw !== null && cronRaw.trim().length > 0 ? cronRaw.trim() : task.cronExpression;
        const nextZone: string = this.resolveZone(
          patch.timezoneId !== undefined && patch.timezoneId !== null ? patch.timezoneId : task.timezoneId,
        );
        const nextEnabled: boolean =
          patch.enabled !== undefined && patch.enabled !== null ? patch.enabled : task.enabled;
        const expression: CronExpression = cronExpressionParse(nextCron);
        const now: number = Date.now();
        const nextRunAt: number | null = nextEnabled ? cronNextRunAfter(expression, now, nextZone) : null;
        if (nextEnabled && nextRunAt === null) {
          throw new Error('Cron expression has no run time in the next 366 days');
        }
        const next: AgentCronTask = makeAgentCronTask({
          id: task.id,
          title: nextTitle,
          prompt: nextPrompt,
          cronExpression: expression.raw,
          timezoneId: nextZone,
          conversationId: task.conversationId,
          enabled: nextEnabled,
          createdAtMs: task.createdAtMs,
          updatedAtMs: now,
          nextRunAtMs: nextRunAt,
          lastRunAtMs: task.lastRunAtMs,
          lastStatus: 'Idle',
          lastError: null,
          runCount: task.runCount,
        });
        updated = next;
        return next;
      }));
    if (updated === null) throw new Error(`Cron task not found: ${id}`);
    const task: AgentCronTask = updated;
    this.schedule(task);
    // R17:重挂取消回调——upsert(snapshot) 无回调时若 cancelCapability=false 会删除已有回调
    await this.agentTaskStore.upsert(
      this.toAgentTaskSnapshot(task), this.cancelCallbackFor(task.id));
    return task;
  }

  // :136-145
  async deleteTask(id: string): Promise<boolean> {
    let removed: boolean = false;
    await this.replaceTasks((tasks: AgentCronTask[]): AgentCronTask[] => {
      removed = tasks.some((task: AgentCronTask): boolean => task.id === id);
      return tasks.filter((task: AgentCronTask): boolean => task.id !== id);
    });
    this.scheduler.cancel(this.workName(id));
    if (removed) await this.agentTaskStore.remove(id);
    return removed;
  }

  // :147-161
  async runTaskNow(id: string): Promise<boolean> {
    const tasks: AgentCronTask[] = await this.listTasks();
    const task: AgentCronTask | undefined = tasks.find((t: AgentCronTask): boolean => t.id === id);
    if (task === undefined) return false;
    this.scheduler.runNow(this.workName(task.id), task.id);
    await this.agentTaskStore.upsert(
      this.toAgentTaskSnapshot(task, 'QUEUED'), this.cancelCallbackFor(task.id));
    return true;
  }

  // R17:显式停止通道(区分「停当前生成」与「停后续调度」)。仅当任务持久化
  //   状态为 Running 时转发 scheduler.cancel——entry 会 abort 在途 run;
  //   在途 fire 收口后按取消语义 markRunCancelled 并重排下一周期。
  //   非 Running 返回 false,不清除待触发定时器(避免静默停掉后续调度)。
  async cancelRun(id: string): Promise<boolean> {
    const tasks: AgentCronTask[] = await this.listTasks();
    const task: AgentCronTask | undefined = tasks.find((t: AgentCronTask): boolean => t.id === id);
    if (task === undefined || task.lastStatus !== 'Running') return false;
    this.scheduler.cancel(this.workName(id));
    return true;
  }

  // :163-191
  // R12:函数入口同步占 claim(先于任何 await)——并发/重入的第二次 prepare 直接
  //   返回 null;markRunCompleted/Failed/Cancelled/releaseRunClaim 释放。
  async prepareTriggeredRun(id: string, manual: boolean = false): Promise<AgentCronTask | null> {
    if (this.claimedRuns.has(id)) return null;
    this.claimedRuns.add(id);
    let prepared: AgentCronTask | null = null;
    await this.replaceTasks((tasks: AgentCronTask[]): AgentCronTask[] =>
      tasks.map((task: AgentCronTask): AgentCronTask => {
        if (task.id !== id || (!manual && !task.enabled)) return task;
        const now: number = Date.now();
        let nextRunAt: number | null = null;
        if (task.enabled) {
          try {
            nextRunAt = cronNextRunAfter(
              cronExpressionParse(task.cronExpression), now, this.resolveZone(task.timezoneId));
          } catch {
            nextRunAt = null;
          }
        }
        const next: AgentCronTask = makeAgentCronTask({
          id: task.id,
          title: task.title,
          prompt: task.prompt,
          cronExpression: task.cronExpression,
          timezoneId: task.timezoneId,
          conversationId: task.conversationId,
          enabled: task.enabled,
          createdAtMs: task.createdAtMs,
          updatedAtMs: now,
          nextRunAtMs: nextRunAt,
          lastRunAtMs: now,
          lastStatus: 'Queued',
          lastError: null,
          runCount: task.runCount + 1,
        });
        prepared = next;
        return next;
      }));
    if (prepared === null) {
      this.claimedRuns.delete(id);   // 门未通过 → 释放,不泄漏 claim
      return null;
    }
    const task: AgentCronTask = prepared;
    await this.agentTaskStore.upsert(
      this.toAgentTaskSnapshot(task, 'QUEUED'), this.cancelCallbackFor(task.id));
    return prepared;
  }

  // R12:释放 claim(entry fire 在 finally 兜底调用,防 prepare 后 mark 前异常卡死)。
  releaseRunClaim(id: string): void {
    this.claimedRuns.delete(id);
  }

  // :193-195
  async scheduleNextRun(id: string): Promise<void> {
    const tasks: AgentCronTask[] = await this.listTasks();
    const task: AgentCronTask | undefined = tasks.find((t: AgentCronTask): boolean => t.id === id);
    if (task !== undefined) this.schedule(task);
  }

  // :197-212
  async markRunStarted(id: string): Promise<void> {
    await this.replaceTasks((tasks: AgentCronTask[]): AgentCronTask[] =>
      tasks.map((task: AgentCronTask): AgentCronTask => {
        if (task.id !== id) return task;
        const init: AgentCronTaskInit = this.taskInitOf(task);
        init.lastStatus = 'Running';
        init.lastError = null;
        init.updatedAtMs = Date.now();
        return makeAgentCronTask(init);
      }));
    await this.agentTaskStore.update(id, {
      status: 'RUNNING',
      error: null,
      lastErrorCode: null,
      cancelCapability: true,
    });
  }

  // :214-229
  async markRunCompleted(id: string): Promise<void> {
    this.claimedRuns.delete(id);
    await this.setLastStatus(id, 'Succeeded', null);
    await this.agentTaskStore.update(id, {
      status: 'COMPLETED',
      summary: 'Cron run completed.',
      error: null,
      lastErrorCode: null,
      cancelCapability: false,   // 无在途 run;下一轮 markRunStarted 再置 true
    });
  }

  // :231-246
  async markRunFailed(id: string, message: string): Promise<void> {
    this.claimedRuns.delete(id);
    await this.setLastStatus(id, 'Failed', message);
    await this.agentTaskStore.update(id, {
      status: 'FAILED',
      error: message.substring(0, 500),
      lastErrorCode: 'cron_run_failed',
      cancelCapability: false,
    });
  }

  // R17:取消当前生成后的终态。不复用 markRunCompleted(不能把中断记成 Succeeded),
  //   也不走 FAILED。看板置 CANCELLED + cancelCapability=false,写 lastError 明示。
  async markRunCancelled(id: string): Promise<void> {
    this.claimedRuns.delete(id);
    await this.setLastStatus(id, 'Cancelled', CANCELLED_MESSAGE);
    await this.agentTaskStore.update(id, {
      status: 'CANCELLED',
      summary: 'Cron run cancelled.',
      error: null,
      lastErrorCode: 'cron_run_cancelled',
      cancelCapability: false,
    });
  }

  // 任务被禁用但本轮在途:停在终态,不改 nextRunAtMs(保留既有值,不复活调度)。
  //   与 markRunCancelled 复用 status 文案,看板置 COMPLETED 以表示「执行已收口」。
  async markRunSuspend(id: string): Promise<void> {
    this.claimedRuns.delete(id);
    await this.setLastStatus(id, 'Suspend', SUSPEND_MESSAGE);
    await this.agentTaskStore.update(id, {
      status: 'COMPLETED',
      summary: 'Cron task disabled while running; run stopped.',
      error: null,
      lastErrorCode: null,
    });
  }

  // :231-246 抽取:仅改持久化任务态(看板由调用方 update)
  private async setLastStatus(
    id: string, status: AgentCronTaskStatus, message: string | null,
  ): Promise<void> {
    await this.replaceTasks((tasks: AgentCronTask[]): AgentCronTask[] =>
      tasks.map((task: AgentCronTask): AgentCronTask => {
        if (task.id !== id) return task;
        const init: AgentCronTaskInit = this.taskInitOf(task);
        init.lastStatus = status;
        init.lastError = message !== null ? message.substring(0, 500) : null;
        init.updatedAtMs = Date.now();
        return makeAgentCronTask(init);
      }));
  }

  // :248-260
  async rescheduleAll(): Promise<void> {
    const tasks: AgentCronTask[] = await this.listTasks();
    for (const task of tasks) {
      if (task.enabled) {
        let next: number | null = task.nextRunAtMs;
        if (next === null) {
          try {
            next = cronNextRunAfter(
              cronExpressionParse(task.cronExpression), Date.now(), this.resolveZone(task.timezoneId));
          } catch {
            next = null;
          }
        }
        const init: AgentCronTaskInit = this.taskInitOf(task);
        init.nextRunAtMs = next;
        this.schedule(makeAgentCronTask(init));
      } else {
        this.scheduler.cancel(this.workName(task.id));
      }
    }
  }

  // :262-268  读 → transform → nextRunAtMs(null=MAX) 稳定排序 → 写
  // R12:整个 read-modify-write 在 mutex 内,消除并发丢更新。
  private replaceTasks(
    transform: (tasks: AgentCronTask[]) => AgentCronTask[],
  ): Promise<void> {
    return this.mutex.withLock(async (): Promise<void> => {
      const current: AgentCronTask[] = this.decode(await this.persistence.read());
      const next: AgentCronTask[] = transform(current);
      next.sort((a: AgentCronTask, b: AgentCronTask): number =>
        (a.nextRunAtMs !== null ? a.nextRunAtMs : Number.MAX_SAFE_INTEGER) -
        (b.nextRunAtMs !== null ? b.nextRunAtMs : Number.MAX_SAFE_INTEGER));
      const store: JsonObject = {
        tasks: next.map((task: AgentCronTask): JsonObject => cronTaskToJson(task)),
      };
      await this.persistence.write(JSON.stringify(store));
    });
  }

  // :270-282
  private schedule(task: AgentCronTask): void {
    if (!task.enabled || task.nextRunAtMs === null) {
      this.scheduler.cancel(this.workName(task.id));
      return;
    }
    const delayMs: number = Math.max(task.nextRunAtMs - Date.now(), 0);
    this.scheduler.schedule(this.workName(task.id), task.id, delayMs);
  }

  // :284-291  严格 → 宽松 → 空
  private decode(raw: string | null): AgentCronTask[] {
    if (raw === null || raw.trim().length === 0) return [];
    try {
      return this.decodeStrict(raw);
    } catch {
      try {
        return this.decodeLenient(raw);
      } catch {
        return [];
      }
    }
  }

  // kotlinx 严格:必填非默认字段缺失即失败(id/title/prompt/cronExpression/
  //   timezoneId/conversationId/enabled/createdAtMs/updatedAtMs)
  private decodeStrict(raw: string): AgentCronTask[] {
    const root: JsonObject = JSON.parse(raw) as JsonObject;
    const tasksRaw: JsonValue | undefined = root['tasks'];
    if (!Array.isArray(tasksRaw)) throw new Error('strict decode failed');
    return tasksRaw.map((element: JsonValue): AgentCronTask => {
      const obj: JsonObject = element as JsonObject;
      const id: string | undefined = strictStr(obj, 'id');
      const title: string | undefined = strictStr(obj, 'title');
      const prompt: string | undefined = strictStr(obj, 'prompt');
      const cronExpression: string | undefined = strictStr(obj, 'cronExpression');
      const timezoneId: string | undefined = strictStr(obj, 'timezoneId');
      const conversationId: string | undefined = strictStr(obj, 'conversationId');
      const enabled: boolean | undefined = strictBool(obj, 'enabled');
      const createdAtMs: number | undefined = strictNum(obj, 'createdAtMs');
      const updatedAtMs: number | undefined = strictNum(obj, 'updatedAtMs');
      if (id === undefined || title === undefined || prompt === undefined ||
        cronExpression === undefined || timezoneId === undefined ||
        conversationId === undefined || enabled === undefined ||
        createdAtMs === undefined || updatedAtMs === undefined) {
        throw new Error('strict decode failed');
      }
      return makeAgentCronTask({
        id, title, prompt, cronExpression, timezoneId, conversationId,
        enabled, createdAtMs, updatedAtMs,
        nextRunAtMs: strictNumOrNull(obj, 'nextRunAtMs'),
        lastRunAtMs: strictNumOrNull(obj, 'lastRunAtMs'),
        lastStatus: strictStatus(obj, 'lastStatus'),
        lastError: strictStrOrNull(obj, 'lastError'),
        runCount: strictNum(obj, 'runCount'),
      });
    });
  }

  // :293-317
  private decodeLenient(raw: string): AgentCronTask[] {
    const root: JsonObject = JSON.parse(raw) as JsonObject;
    const tasksRaw: JsonValue | undefined = root['tasks'];
    const list: JsonValue[] = Array.isArray(tasksRaw) ? tasksRaw : [];
    const out: AgentCronTask[] = [];
    list.forEach((element: JsonValue) => {
      const obj: JsonObject = element as JsonObject;
      const id: string | undefined = lenientStr(obj, 'id');
      if (id === undefined) return;
      const cronExpression: string | undefined = lenientStr(obj, 'cronExpression');
      if (cronExpression === undefined) return;
      const statusRaw: string | undefined = lenientStr(obj, 'lastStatus');
      const status: AgentCronTaskStatus | undefined = statusRaw !== undefined
        ? AGENT_CRON_STATUS_NAMES.find(
          (s: AgentCronTaskStatus): boolean => s.toLowerCase() === statusRaw.toLowerCase())
        : undefined;
      out.push(makeAgentCronTask({
        id,
        title: lenientStr(obj, 'title') ?? '',
        prompt: lenientStr(obj, 'prompt') ?? '',
        cronExpression,
        timezoneId: lenientStr(obj, 'timezoneId') ?? cronSystemZoneId(),
        conversationId: lenientStr(obj, 'conversationId') ?? newId(),
        enabled: lenientBool(obj, 'enabled') ?? false,
        createdAtMs: lenientNum(obj, 'createdAtMs') ?? 0,
        updatedAtMs: lenientNum(obj, 'updatedAtMs') ?? 0,
        nextRunAtMs: lenientNum(obj, 'nextRunAtMs') ?? null,
        lastRunAtMs: lenientNum(obj, 'lastRunAtMs') ?? null,
        lastStatus: status ?? 'Idle',
        lastError: lenientStr(obj, 'lastError') ?? null,
        runCount: lenientNum(obj, 'runCount') ?? 0,
      }));
    });
    return out;
  }

  // :356-358  非法/blank → 系统默认
  private resolveZone(timezoneId: string | null): string {
    const candidate: string =
      timezoneId !== null && timezoneId.trim().length > 0 ? timezoneId : cronSystemZoneId();
    return cronIsValidZoneId(candidate) ? candidate : cronSystemZoneId();
  }

  // :360
  private workName(id: string): string {
    return `amberagent_cron_${id}`;
  }

  // R17:注册/刷新看板取消回调。直接走 scheduler.cancel(entry 收到 Signal
  //   'cancel' 时若任务在跑会 abort 在途 run,否则仅清待触发定时器)。
  //   返回 true 恒成立——是否真有在途 run 由 entry 判定。
  private cancelCallbackFor(id: string): () => Promise<boolean> {
    return async (): Promise<boolean> => {
      this.scheduler.cancel(this.workName(id));
      return true;
    };
  }

  private taskInitOf(task: AgentCronTask): AgentCronTaskInit {
    return {
      id: task.id,
      title: task.title,
      prompt: task.prompt,
      cronExpression: task.cronExpression,
      timezoneId: task.timezoneId,
      conversationId: task.conversationId,
      enabled: task.enabled,
      createdAtMs: task.createdAtMs,
      updatedAtMs: task.updatedAtMs,
      nextRunAtMs: task.nextRunAtMs,
      lastRunAtMs: task.lastRunAtMs,
      lastStatus: task.lastStatus,
      lastError: task.lastError,
      runCount: task.runCount,
    };
  }

  // :362-383
  private toAgentTaskSnapshot(
    task: AgentCronTask, status: AgentTaskStatus | null = null,
  ): AgentTaskSnapshot {
    const resolvedStatus: AgentTaskStatus = status !== null ? status : ((): AgentTaskStatus => {
      if (!task.enabled) return 'CANCELLED';
      if (task.lastStatus === 'Failed') return 'FAILED';
      if (task.lastStatus === 'Running') return 'RUNNING';
      if (task.lastStatus === 'Succeeded') return 'COMPLETED';
      if (task.lastStatus === 'Queued') return 'QUEUED';
      return 'QUEUED';
    })();
    const snapshot: AgentTaskSnapshot = makeAgentTaskSnapshot({
      taskId: task.id,
      type: 'cron',
      title: task.title,
      sourceConversationId: task.conversationId,
      status: resolvedStatus,
      createdAtMs: task.createdAtMs,
      updatedAtMs: task.updatedAtMs,
      sourceToolName: 'cron_task_create',
      // R17:仅运行中任务开放取消(agent_task_cancel 才转发 scheduler.cancel);
      //   其余状态无在途生成,恒 false。
      cancelCapability: task.lastStatus === 'Running',
      spec: { enabled: task.enabled },
      summary: `cron=${task.cronExpression}; timezone=${task.timezoneId}; ` +
        `next_run_at_ms=${task.nextRunAtMs !== null ? task.nextRunAtMs : 'none'}`,
      error: task.lastError,
    });
    const init: AgentTaskSnapshotInit = this.snapshotInitOf(snapshot);
    init.queueState = agentTaskStatusToQueueState(snapshot.status, snapshot.type);
    return makeAgentTaskSnapshot(init);
  }

  private snapshotInitOf(s: AgentTaskSnapshot): AgentTaskSnapshotInit {
    return {
      schemaVersion: s.schemaVersion,
      taskId: s.taskId,
      type: s.type,
      title: s.title,
      spec: s.spec,
      runtime: s.runtime,
      queueState: s.queueState,
      recoveryState: s.recoveryState,
      retryPolicy: s.retryPolicy,
      outputRef: s.outputRef,
      sourceToolName: s.sourceToolName,
      sourceConversationId: s.sourceConversationId,
      status: s.status,
      outputPath: s.outputPath,
      outputOffset: s.outputOffset,
      createdAtMs: s.createdAtMs,
      updatedAtMs: s.updatedAtMs,
      lastHeartbeatMs: s.lastHeartbeatMs,
      notified: s.notified,
      cancelCapability: s.cancelCapability,
      permissionTraceId: s.permissionTraceId,
      summary: s.summary,
      lastErrorCode: s.lastErrorCode,
      error: s.error,
    };
  }
}

// ===== 严格/宽松读取助手 =====

const strictStr = (j: JsonObject, key: string): string | undefined => {
  const v: JsonValue | undefined = j[key];
  return typeof v === 'string' ? v : undefined;
};
const strictNum = (j: JsonObject, key: string): number | undefined => {
  const v: JsonValue | undefined = j[key];
  return typeof v === 'number' ? v : undefined;
};
const strictBool = (j: JsonObject, key: string): boolean | undefined => {
  const v: JsonValue | undefined = j[key];
  return typeof v === 'boolean' ? v : undefined;
};
// kotlinx nullable 字段:键缺/显式 null → null(有默认,不失败)
const strictNumOrNull = (j: JsonObject, key: string): number | null => {
  const v: JsonValue | undefined = j[key];
  return typeof v === 'number' ? v : null;
};
const strictStrOrNull = (j: JsonObject, key: string): string | null => {
  const v: JsonValue | undefined = j[key];
  return typeof v === 'string' ? v : null;
};
const strictStatus = (j: JsonObject, key: string): AgentCronTaskStatus | undefined => {
  const v: JsonValue | undefined = j[key];
  if (typeof v !== 'string') return undefined;
  return AGENT_CRON_STATUS_NAMES.find((s: AgentCronTaskStatus): boolean => s === v);
};

const lenientStr = (j: JsonObject, key: string): string | undefined => {
  const v: JsonValue | undefined = j[key];
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  return undefined;
};
const lenientNum = (j: JsonObject, key: string): number | undefined => {
  const v: JsonValue | undefined = j[key];
  if (typeof v === 'number') return v;
  if (typeof v === 'string') {
    const n: number = Number(v);
    return Number.isFinite(n) ? n : undefined;
  }
  return undefined;
};
const lenientBool = (j: JsonObject, key: string): boolean | undefined => {
  const v: JsonValue | undefined = j[key];
  if (typeof v === 'boolean') return v;
  if (typeof v === 'string') {
    if (v === 'true') return true;
    if (v === 'false') return false;
  }
  return undefined;
};
