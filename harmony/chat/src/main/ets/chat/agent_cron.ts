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
import type { UIMessagePart } from './message.ts';
import type { AgentTool } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';
import { newId } from './ids.ts';
import type {
  AgentTaskSnapshot, AgentTaskSnapshotInit, AgentTaskStatus, AgentTaskStore,
} from './agent_task.ts';
import {
  makeAgentTaskSnapshot, agentTaskStatusToQueueState,
} from './agent_task.ts';

// ===== CronExpression(全文 106) =====

const CRON_MAX_LOOKAHEAD_DAYS: number = 366;
const MINUTE_MS: number = 60_000;

export interface CronExpression {
  raw: string;
  minutes: Set<number>;
  hours: Set<number>;
  daysOfMonth: Set<number>;
  months: Set<number>;
  daysOfWeek: Set<number>;
  dayOfMonthWildcard: boolean;
  dayOfWeekWildcard: boolean;
}

// Kotlin toIntOrNull 等价:可选负号 + 全数字('01' 合法)
const kotlinToIntOrNull = (raw: string): number | null => {
  if (!/^-?\d+$/.test(raw)) return null;
  const n: number = Number.parseInt(raw, 10);
  return Number.isSafeInteger(n) ? n : null;
};

// :78-103
const cronParseSegment = (segment: string, min: number, max: number): number[] => {
  if (segment.trim().length === 0) throw new Error('Cron segment cannot be blank');
  const slashIdx: number = segment.indexOf('/');
  const rangePart: string = slashIdx < 0 ? segment : segment.substring(0, slashIdx);
  const stepStr: string = slashIdx < 0 ? '1' : segment.substring(slashIdx + 1);
  const step: number | null = kotlinToIntOrNull(stepStr);
  if (step === null) throw new Error(`Invalid cron step in '${segment}'`);
  if (step <= 0) throw new Error(`Cron step must be greater than 0 in '${segment}'`);

  let start: number;
  let end: number;
  if (rangePart === '*') {
    start = min;
    end = max;
  } else if (rangePart.indexOf('-') >= 0) {
    const dashIdx: number = rangePart.indexOf('-');
    const startRaw: string = rangePart.substring(0, dashIdx);
    const endRaw: string = rangePart.substring(dashIdx + 1);
    const s: number | null = kotlinToIntOrNull(startRaw);
    if (s === null) throw new Error(`Invalid cron range start in '${segment}'`);
    const e: number | null = kotlinToIntOrNull(endRaw);
    if (e === null) throw new Error(`Invalid cron range end in '${segment}'`);
    start = s;
    end = e;
  } else {
    const value: number | null = kotlinToIntOrNull(rangePart);
    if (value === null) throw new Error(`Invalid cron value in '${segment}'`);
    start = value;
    end = value;
  }
  if (start < min || start > max || end < min || end > max || start > end) {
    throw new Error(`Cron segment '${segment}' is outside ${min}..${max}`);
  }
  const out: number[] = [];
  for (let v: number = start; v <= end; v += step) out.push(v);
  return out;
};

// :70-76
const cronParseField = (field: string, min: number, max: number): Set<number> => {
  if (field.trim().length === 0) throw new Error('Cron field cannot be blank');
  const values: Set<number> = new Set();
  field.split(',').forEach((rawSegment: string) => {
    cronParseSegment(rawSegment.trim(), min, max).forEach((v: number) => { values.add(v); });
  });
  if (values.size === 0) throw new Error(`Cron field '${field}' produced no values`);
  return values;
};

export const cronExpressionParse = (expression: string): CronExpression => {
  const parts: string[] = expression.trim().split(/\s+/);
  if (parts.length !== 5) {
    throw new Error('Cron expression must have 5 fields: minute hour day-of-month month day-of-week');
  }
  const domWildcard: boolean = parts[2] === '*';
  const dowWildcard: boolean = parts[4] === '*';
  const dowRaw: Set<number> = cronParseField(parts[4], 0, 7);
  const daysOfWeek: Set<number> = new Set();
  dowRaw.forEach((v: number) => { daysOfWeek.add(v === 0 ? 7 : v); });
  return {
    raw: expression.trim(),
    minutes: cronParseField(parts[0], 0, 59),
    hours: cronParseField(parts[1], 0, 23),
    daysOfMonth: cronParseField(parts[2], 1, 31),
    months: cronParseField(parts[3], 1, 12),
    daysOfWeek,
    dayOfMonthWildcard: domWildcard,
    dayOfWeekWildcard: dowWildcard,
  };
};

// ===== 时区本地字段(Intl 承载 ZonedDateTime) =====

interface ZonedParts {
  minute: number;
  hour: number;
  dayOfMonth: number;
  month: number;
  dayOfWeek: number;   // Java MONDAY=1..SUNDAY=7
}

const WEEKDAY_TO_JAVA: Record<string, number> = {
  Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7,
};

const zonedFormatters: Map<string, Intl.DateTimeFormat> = new Map();

const zonedFormatter = (zoneId: string): Intl.DateTimeFormat => {
  let fmt: Intl.DateTimeFormat | undefined = zonedFormatters.get(zoneId);
  if (fmt === undefined) {
    fmt = new Intl.DateTimeFormat('en-US', {
      timeZone: zoneId,
      hourCycle: 'h23',
      year: 'numeric', month: 'numeric', day: 'numeric',
      hour: 'numeric', minute: 'numeric', weekday: 'short',
    });
    zonedFormatters.set(zoneId, fmt);
  }
  return fmt;
};

const zonedPartsOf = (epochMs: number, zoneId: string): ZonedParts => {
  const parts: Intl.DateTimeFormatPart[] = zonedFormatter(zoneId).formatToParts(new Date(epochMs));
  let minute: number = 0;
  let hour: number = 0;
  let dayOfMonth: number = 1;
  let month: number = 1;
  let dayOfWeek: number = 1;
  parts.forEach((part: Intl.DateTimeFormatPart) => {
    if (part.type === 'minute') minute = Number.parseInt(part.value, 10);
    else if (part.type === 'hour') hour = Number.parseInt(part.value, 10);
    else if (part.type === 'day') dayOfMonth = Number.parseInt(part.value, 10);
    else if (part.type === 'month') month = Number.parseInt(part.value, 10);
    else if (part.type === 'weekday') dayOfWeek = WEEKDAY_TO_JAVA[part.value] ?? 1;
  });
  return { minute, hour, dayOfMonth, month, dayOfWeek };
};

// ZoneId.of 校验等价:非法 id 抛错(resolveZone catch 回退)
export const cronIsValidZoneId = (zoneId: string): boolean => {
  try {
    zonedFormatter(zoneId).format(new Date(0));
    return true;
  } catch {
    return false;
  }
};

export const cronSystemZoneId = (): string => {
  try {
    const id: string | undefined = Intl.DateTimeFormat().resolvedOptions().timeZone;
    if (id !== undefined && id.length > 0) return id;
  } catch {
    // fallthrough
  }
  return 'UTC';
};

// :32-46
const cronMatches = (expr: CronExpression, parts: ZonedParts): boolean => {
  if (!expr.minutes.has(parts.minute)) return false;
  if (!expr.hours.has(parts.hour)) return false;
  if (!expr.months.has(parts.month)) return false;
  const dayOfMonthMatches: boolean = expr.daysOfMonth.has(parts.dayOfMonth);
  const dayOfWeekMatches: boolean = expr.daysOfWeek.has(parts.dayOfWeek);
  if (!expr.dayOfMonthWildcard && !expr.dayOfWeekWildcard) {
    return dayOfMonthMatches || dayOfWeekMatches;
  }
  return dayOfMonthMatches && dayOfWeekMatches;
};

// :18-30  cursor = epoch+1min 截断至分钟;逐分钟扫至 +366 天
export const cronNextRunAfter = (
  expr: CronExpression, epochMs: number, zoneId: string,
): number | null => {
  let cursor: number = Math.floor(epochMs / MINUTE_MS) * MINUTE_MS + MINUTE_MS;
  const deadline: number = cursor + CRON_MAX_LOOKAHEAD_DAYS * 24 * 60 * MINUTE_MS;
  while (cursor <= deadline) {
    if (cronMatches(expr, zonedPartsOf(cursor, zoneId))) return cursor;
    cursor += MINUTE_MS;
  }
  return null;
};

// ===== AgentCronModels(全文 35;kotlinx camelCase 键) =====

export type AgentCronTaskStatus = 'Idle' | 'Queued' | 'Running' | 'Succeeded' | 'Failed';

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

const AGENT_CRON_STATUS_NAMES: AgentCronTaskStatus[] = ['Idle', 'Queued', 'Running', 'Succeeded', 'Failed'];

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
    await this.agentTaskStore.register(this.toAgentTaskSnapshot(task));
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
    await this.agentTaskStore.upsert(this.toAgentTaskSnapshot(task));
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
    await this.agentTaskStore.upsert(this.toAgentTaskSnapshot(task, 'QUEUED'));
    return true;
  }

  // :163-191
  async prepareTriggeredRun(id: string, manual: boolean = false): Promise<AgentCronTask | null> {
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
    if (prepared !== null) {
      const task: AgentCronTask = prepared;
      await this.agentTaskStore.upsert(this.toAgentTaskSnapshot(task, 'QUEUED'));
    }
    return prepared;
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
    await this.agentTaskStore.update(id, { status: 'RUNNING', error: '' });
  }

  // :214-229
  async markRunCompleted(id: string): Promise<void> {
    await this.replaceTasks((tasks: AgentCronTask[]): AgentCronTask[] =>
      tasks.map((task: AgentCronTask): AgentCronTask => {
        if (task.id !== id) return task;
        const init: AgentCronTaskInit = this.taskInitOf(task);
        init.lastStatus = 'Succeeded';
        init.lastError = null;
        init.updatedAtMs = Date.now();
        return makeAgentCronTask(init);
      }));
    await this.agentTaskStore.update(id, { status: 'COMPLETED', summary: 'Cron run completed.' });
  }

  // :231-246
  async markRunFailed(id: string, message: string): Promise<void> {
    await this.replaceTasks((tasks: AgentCronTask[]): AgentCronTask[] =>
      tasks.map((task: AgentCronTask): AgentCronTask => {
        if (task.id !== id) return task;
        const init: AgentCronTaskInit = this.taskInitOf(task);
        init.lastStatus = 'Failed';
        init.lastError = message.substring(0, 500);
        init.updatedAtMs = Date.now();
        return makeAgentCronTask(init);
      }));
    await this.agentTaskStore.update(id, { status: 'FAILED', error: message.substring(0, 500) });
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
  private async replaceTasks(
    transform: (tasks: AgentCronTask[]) => AgentCronTask[],
  ): Promise<void> {
    const current: AgentCronTask[] = this.decode(await this.persistence.read());
    const next: AgentCronTask[] = transform(current);
    next.sort((a: AgentCronTask, b: AgentCronTask): number =>
      (a.nextRunAtMs !== null ? a.nextRunAtMs : Number.MAX_SAFE_INTEGER) -
      (b.nextRunAtMs !== null ? b.nextRunAtMs : Number.MAX_SAFE_INTEGER));
    const store: JsonObject = { tasks: next.map((task: AgentCronTask): JsonObject => cronTaskToJson(task)) };
    await this.persistence.write(JSON.stringify(store));
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
      cancelCapability: false,
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

// ===== AgentCronTools(全文 150) =====

// ToolJson.kt:string/requiredString/boolean(contentOrNull 系)
const cronInputString = (input: JsonValue, key: string): string | undefined => {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return undefined;
  const raw: JsonValue | undefined = (input as JsonObject)[key];
  if (raw === undefined || raw === null) return undefined;
  if (typeof raw === 'string') return raw;
  if (typeof raw === 'number' || typeof raw === 'boolean') return String(raw);
  return undefined;
};

const cronInputRequiredString = (input: JsonValue, key: string): string => {
  const value: string | undefined = cronInputString(input, key);
  if (value === undefined) throw new Error(`${key} is required`);
  return value;
};

const cronInputBoolean = (input: JsonValue, key: string): boolean | undefined => {
  const content: string | undefined = cronInputString(input, key);
  if (content === undefined) return undefined;
  if (content === 'true') return true;
  if (content === 'false') return false;
  return undefined;
};

// :124-139  toJson(键序逐字;prompt.take(2000);可空字段键省略)
const cronTaskToolJson = (task: AgentCronTask): JsonObject => {
  const j: JsonObject = {};
  j['id'] = task.id;
  j['title'] = task.title;
  j['prompt'] = task.prompt.substring(0, 2000);
  j['cron_expression'] = task.cronExpression;
  j['timezone'] = task.timezoneId;
  j['conversation_id'] = task.conversationId;
  j['enabled'] = task.enabled;
  if (task.nextRunAtMs !== null) j['next_run_at_ms'] = task.nextRunAtMs;
  if (task.lastRunAtMs !== null) j['last_run_at_ms'] = task.lastRunAtMs;
  j['last_status'] = task.lastStatus.toLowerCase();
  if (task.lastError !== null) j['last_error'] = task.lastError;
  j['run_count'] = task.runCount;
  j['created_at_ms'] = task.createdAtMs;
  j['updated_at_ms'] = task.updatedAtMs;
  return j;
};

const textJson = (payload: JsonObject): UIMessagePart[] => [
  { type: 'text', text: JSON.stringify(payload), metadata: null },
];

const stringProp = (description: string): JsonObject => ({ type: 'string', description });
const booleanProp = (description: string): JsonObject => ({ type: 'boolean', description });

export class AgentCronTools {
  constructor(private readonly manager: AgentCronManager) {}

  getTools(): AgentTool[] {
    return [this.listTool(), this.createTool(), this.updateTool(), this.deleteTool()];
  }

  private listTool(): AgentTool {
    return makeAgentTool({
      name: 'cron_task_list',
      description: 'List AmberAgent mobile cron tasks scheduled on this Android device.',
      parameters: (): ReturnType<typeof makeInputSchemaObj> => makeInputSchemaObj({}),
      execute: async (): Promise<UIMessagePart[]> => {
        const tasks: AgentCronTask[] = await this.manager.listTasksSnapshot();
        const payload: JsonObject = {
          status: 'ok',
          tasks: tasks.map((task: AgentCronTask): JsonObject => cronTaskToolJson(task)),
        };
        return textJson(payload);
      },
    });
  }

  private createTool(): AgentTool {
    return makeAgentTool({
      name: 'cron_task_create',
      description: "Create a mobile-side cron task. The task will trigger AmberAgent on this Android device and send the prompt to its own conversation when due. Supports 5-field cron expressions like '0 9 * * *', '*/30 * * * *', and '30 9 * * 1-5'.",
      needsApproval: true,
      allowsAutoApproval: false,
      parameters: (): ReturnType<typeof makeInputSchemaObj> => makeInputSchemaObj({
        title: stringProp('Short task name shown in Settings.'),
        prompt: stringProp('User instruction AmberAgent should run when the cron fires.'),
        cron_expression: stringProp('Five-field cron expression: minute hour day-of-month month day-of-week.'),
        timezone: stringProp('IANA timezone id. Defaults to this device timezone.'),
        enabled: booleanProp('Whether to schedule immediately. Defaults to true.'),
      }, ['prompt', 'cron_expression']),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        const title: string = cronInputString(input, 'title') ?? '';
        const prompt: string = cronInputRequiredString(input, 'prompt');
        const cronExpression: string = cronInputRequiredString(input, 'cron_expression');
        const timezone: string | undefined = cronInputString(input, 'timezone');
        const enabled: boolean = cronInputBoolean(input, 'enabled') ?? true;
        const task: AgentCronTask = await this.manager.createTask(
          title, prompt, cronExpression, timezone !== undefined ? timezone : null, enabled);
        const payload: JsonObject = { status: 'created', task: cronTaskToolJson(task) };
        return textJson(payload);
      },
    });
  }

  private updateTool(): AgentTool {
    return makeAgentTool({
      name: 'cron_task_update',
      description: 'Update an existing mobile cron task. Omitted fields are preserved.',
      needsApproval: true,
      allowsAutoApproval: false,
      parameters: (): ReturnType<typeof makeInputSchemaObj> => makeInputSchemaObj({
        task_id: stringProp('Cron task id.'),
        title: stringProp('New task title.'),
        prompt: stringProp('New prompt to send when the task runs.'),
        cron_expression: stringProp('New five-field cron expression.'),
        timezone: stringProp('New IANA timezone id.'),
        enabled: booleanProp('Enable or pause the task.'),
      }, ['task_id']),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        const task: AgentCronTask = await this.manager.updateTask(
          cronInputRequiredString(input, 'task_id'),
          {
            title: cronInputString(input, 'title') ?? null,
            prompt: cronInputString(input, 'prompt') ?? null,
            cronExpression: cronInputString(input, 'cron_expression') ?? null,
            timezoneId: cronInputString(input, 'timezone') ?? null,
            enabled: cronInputBoolean(input, 'enabled') ?? null,
          },
        );
        const payload: JsonObject = { status: 'updated', task: cronTaskToolJson(task) };
        return textJson(payload);
      },
    });
  }

  private deleteTool(): AgentTool {
    return makeAgentTool({
      name: 'cron_task_delete',
      description: 'Delete a mobile cron task and cancel its scheduled work.',
      needsApproval: true,
      allowsAutoApproval: false,
      parameters: (): ReturnType<typeof makeInputSchemaObj> => makeInputSchemaObj({
        task_id: stringProp('Cron task id.'),
      }, ['task_id']),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        const taskId: string = cronInputRequiredString(input, 'task_id');
        const removed: boolean = await this.manager.deleteTask(taskId);
        const payload: JsonObject = {
          status: removed ? 'deleted' : 'not_found',
          task_id: taskId,
        };
        return textJson(payload);
      },
    });
  }
}
