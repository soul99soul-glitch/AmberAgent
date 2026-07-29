// agent_cron.test.ts — D-130 cron 表达式 + 管理器 + 四工具
// Android 基准: CronExpression.kt + AgentCronModels.kt + AgentCronManager.kt + AgentCronTools.kt
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';
import type {
  AgentCronPersistencePort, AgentCronSchedulerPort, AgentCronTask, CronExpression,
} from '../main/ets/chat/agent_cron.ts';
import {
  AgentCronManager, AgentCronTools,
  cronExpressionParse, cronNextRunAfter, cronIsValidZoneId, cronSystemZoneId,
} from '../main/ets/chat/agent_cron.ts';
import type { AgentTaskFilePort } from '../main/ets/chat/agent_task.ts';
import { AgentTaskStore } from '../main/ets/chat/agent_task.ts';

const ZONE: string = 'Asia/Shanghai';   // UTC+8,无 DST,便于断言

// ===== 假端口 =====

class MemPersistence implements AgentCronPersistencePort {
  raw: string | null = null;

  read(): Promise<string | null> { return Promise.resolve(this.raw); }

  write(raw: string): Promise<void> { this.raw = raw; return Promise.resolve(); }
}

interface ScheduleCall { workName: string; taskId: string; delayMs: number; }

class MemScheduler implements AgentCronSchedulerPort {
  scheduled: ScheduleCall[] = [];
  ranNow: string[] = [];
  cancelled: string[] = [];

  schedule(workName: string, taskId: string, delayMs: number): void {
    this.scheduled = this.scheduled.filter((c: ScheduleCall): boolean => c.workName !== workName);
    this.scheduled.push({ workName, taskId, delayMs });
  }

  runNow(workName: string, taskId: string): void {
    this.ranNow.push(`${workName}|${taskId}`);
  }

  cancel(workName: string): void {
    this.cancelled.push(workName);
    this.scheduled = this.scheduled.filter((c: ScheduleCall): boolean => c.workName !== workName);
  }
}

class MemFiles implements AgentTaskFilePort {
  files: Map<string, string> = new Map();

  mkdirs(_dir: string): Promise<void> { return Promise.resolve(); }

  listJsonFileNames(dir: string): Promise<string[]> {
    const out: string[] = [];
    this.files.forEach((_v: string, path: string) => {
      if (path.startsWith(`${dir}/`) && path.endsWith('.json')) out.push(path.substring(dir.length + 1));
    });
    return Promise.resolve(out);
  }

  readText(path: string): Promise<string | null> {
    const v: string | undefined = this.files.get(path);
    return Promise.resolve(v !== undefined ? v : null);
  }

  writeText(path: string, text: string): Promise<void> { this.files.set(path, text); return Promise.resolve(); }

  delete(path: string): Promise<void> { this.files.delete(path); return Promise.resolve(); }

  exists(path: string): Promise<boolean> { return Promise.resolve(this.files.has(path)); }

  isPathInside(root: string, path: string): Promise<boolean> {
    return Promise.resolve(path.startsWith(`${root}/`));
  }
}

const makeManager = async (): Promise<{
  manager: AgentCronManager; persistence: MemPersistence; scheduler: MemScheduler;
}> => {
  const persistence = new MemPersistence();
  const scheduler = new MemScheduler();
  const taskStore: AgentTaskStore = await AgentTaskStore.create({
    taskDir: '/files/amberagent/tasks', appFilesDir: '/files', files: new MemFiles(),
  });
  return { manager: new AgentCronManager(persistence, scheduler, taskStore), persistence, scheduler };
};

const textOf = (parts: UIMessagePart[]): JsonObject =>
  JSON.parse((parts[0] as { text: string }).text) as JsonObject;

// ===== CronExpression 解析(:51-68) =====

test('parse: 通配/单值/范围/步进/列表 + 周日 0→7', () => {
  const e: CronExpression = cronExpressionParse('*/15 9-17/2 1,15 * 0,3');
  assert.deepEqual([...e.minutes], [0, 15, 30, 45]);
  assert.deepEqual([...e.hours], [9, 11, 13, 15, 17]);
  assert.deepEqual([...e.daysOfMonth], [1, 15]);
  assert.deepEqual([...e.months], [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]);
  assert.deepEqual([...e.daysOfWeek].sort((a: number, b: number): number => a - b), [3, 7]);
  assert.equal(e.dayOfMonthWildcard, false);
  assert.equal(e.dayOfWeekWildcard, false);
  assert.equal(e.raw, '*/15 9-17/2 1,15 * 0,3');
});

test('parse: 错误文案逐字', () => {
  assert.throws(() => cronExpressionParse('* * *'), /Cron expression must have 5 fields: minute hour day-of-month month day-of-week/);
  assert.throws(() => cronExpressionParse(''), /Cron expression must have 5 fields/);
  assert.throws(() => cronExpressionParse('* * * * a'), /Invalid cron value in 'a'/);
  assert.throws(() => cronExpressionParse('*/0 * * * *'), /Cron step must be greater than 0 in '\*\/0'/);
  assert.throws(() => cronExpressionParse('*/x * * * *'), /Invalid cron step in '\*\/x'/);
  assert.throws(() => cronExpressionParse('5- * * * *'), /Invalid cron range end in '5-'/);
  assert.throws(() => cronExpressionParse('-5 * * * *'), /Invalid cron range start in '-5'/);
  assert.throws(() => cronExpressionParse('70 * * * *'), /Cron segment '70' is outside 0\.\.59/);
  assert.throws(() => cronExpressionParse('9-5 * * * *'), /Cron segment '9-5' is outside 0\.\.59/);
  assert.throws(() => cronExpressionParse('* 9-5 * * *'), /Cron segment '9-5' is outside 0\.\.23/);
});

// ===== nextRunAfter(:18-46) =====

test('nextRun: 分钟对齐 + 逐分钟扫描', () => {
  // 2026-07-28 12:00:00 UTC = 20:00 Asia/Shanghai
  const base: number = Date.UTC(2026, 6, 28, 12, 0, 0);
  const everyMinute: CronExpression = cronExpressionParse('* * * * *');
  // +30s → 下一分钟
  assert.equal(cronNextRunAfter(everyMinute, base + 30_000, ZONE), base + 60_000);

  // 每天 09:00(上海)= 01:00 UTC;20:00 上海时 → 次日 09:00
  const daily: CronExpression = cronExpressionParse('0 9 * * *');
  const next: number | null = cronNextRunAfter(daily, base, ZONE);
  assert.equal(next, Date.UTC(2026, 6, 29, 1, 0, 0));

  // 周一-周五 09:30;2026-07-28 是周二 → 次日(周三)
  const weekday: CronExpression = cronExpressionParse('30 9 * * 1-5');
  assert.equal(cronNextRunAfter(weekday, base, ZONE), Date.UTC(2026, 6, 29, 1, 30, 0));

  // dom+dow 双非通配 → OR 语义(:40-44);7-28 周二,dow=3(周三) 或 dom=30
  const orExpr: CronExpression = cronExpressionParse('0 0 30 * 3');
  // 下一个命中:周三 07-29 00:00 上海 = 07-28 16:00 UTC
  assert.equal(cronNextRunAfter(orExpr, base, ZONE), Date.UTC(2026, 6, 28, 16, 0, 0));

  // dom 通配 dow 非通配 → AND 退化为仅 dow(:42)
  const dowOnly: CronExpression = cronExpressionParse('0 0 * * 3');
  assert.equal(cronNextRunAfter(dowOnly, base, ZONE), Date.UTC(2026, 6, 28, 16, 0, 0));
});

test('nextRun: 不可能表达式 → null(2 月 31 日)', () => {
  const impossible: CronExpression = cronExpressionParse('0 0 31 2 *');
  assert.equal(cronNextRunAfter(impossible, Date.UTC(2026, 0, 1), ZONE), null);
});

test('zone: 非法 id → false;系统默认非空', () => {
  assert.equal(cronIsValidZoneId('Asia/Shanghai'), true);
  assert.equal(cronIsValidZoneId('Not/AZone'), false);
  assert.ok(cronSystemZoneId().length > 0);
});

// ===== Manager(:59-92 create) =====

test('createTask: 默认标题/排序持久化/调度/看板登记', async () => {
  const { manager, persistence, scheduler } = await makeManager();
  const task: AgentCronTask = await manager.createTask('', '  给公众号写今日草稿并保存到文件  ', '0 9 * * *', ZONE, true);
  assert.equal(task.title, '给公众号写今日草稿并保存到文件'.substring(0, 32));
  assert.equal(task.timezoneId, ZONE);
  assert.ok(task.nextRunAtMs !== null && task.nextRunAtMs > Date.now());
  assert.equal(task.lastStatus, 'Idle');
  // 持久化 raw 含任务
  assert.ok(persistence.raw !== null && persistence.raw.indexOf(task.id) >= 0);
  // 调度延迟 ≈ nextRun - now
  assert.equal(scheduler.scheduled.length, 1);
  assert.equal(scheduler.scheduled[0].workName, `amberagent_cron_${task.id}`);
  assert.ok(Math.abs(scheduler.scheduled[0].delayMs - ((task.nextRunAtMs as number) - Date.now())) < 5000);
});

test('createTask: blank prompt → 文案逐字;不可能表达式 → 366 天文案', async () => {
  const { manager } = await makeManager();
  await assert.rejects(
    () => manager.createTask('', '   ', '* * * * *', null, true),
    /Cron task prompt cannot be blank/,
  );
  await assert.rejects(
    () => manager.createTask('', 'x', '0 0 31 2 *', null, true),
    /Cron expression has no run time in the next 366 days/,
  );
  // disabled → 不计算 nextRun 也不校验
  const disabled: AgentCronTask = await manager.createTask('', 'x', '0 0 31 2 *', null, false);
  assert.equal(disabled.nextRunAtMs, null);
  assert.equal(disabled.enabled, false);
});

// ===== Manager(:94-145 update/delete) =====

test('updateTask: 省略字段保留 + 显式变更 + 看板/调度联动', async () => {
  const { manager, scheduler } = await makeManager();
  const task: AgentCronTask = await manager.createTask('晨报', 'p1', '0 9 * * *', ZONE, true);
  const updated: AgentCronTask = await manager.updateTask(task.id, { prompt: 'p2' });
  assert.equal(updated.prompt, 'p2');
  assert.equal(updated.title, '晨报');                    // 保留
  assert.equal(updated.cronExpression, '0 9 * * *');       // 保留
  assert.equal(updated.conversationId, task.conversationId);
  assert.equal(updated.lastStatus, 'Idle');
  // blank 标题 → 保留
  const updated2: AgentCronTask = await manager.updateTask(task.id, { title: '   ' });
  assert.equal(updated2.title, '晨报');
  // 禁用 → 取消调度
  const disabled: AgentCronTask = await manager.updateTask(task.id, { enabled: false });
  assert.equal(disabled.enabled, false);
  assert.equal(disabled.nextRunAtMs, null);
  assert.ok(scheduler.cancelled.indexOf(`amberagent_cron_${task.id}`) >= 0);
  await assert.rejects(
    () => manager.updateTask('missing', { title: 'x' }),
    /Cron task not found: missing/,
  );
});

test('deleteTask: 移除 + 取消调度;不存在 → false', async () => {
  const { manager, scheduler, persistence } = await makeManager();
  const task: AgentCronTask = await manager.createTask('', 'p', '* * * * *', ZONE, true);
  assert.equal(await manager.deleteTask(task.id), true);
  assert.ok(scheduler.cancelled.indexOf(`amberagent_cron_${task.id}`) >= 0);
  assert.ok(persistence.raw !== null && persistence.raw.indexOf(task.id) < 0);
  assert.equal(await manager.deleteTask(task.id), false);
});

// ===== Manager(:147-191 runTaskNow/prepareTriggeredRun) =====

test('runTaskNow: 手动立即运行入队;未知 id → false', async () => {
  const { manager, scheduler } = await makeManager();
  const task: AgentCronTask = await manager.createTask('', 'p', '0 9 * * *', ZONE, true);
  assert.equal(await manager.runTaskNow(task.id), true);
  assert.deepEqual(scheduler.ranNow, [`amberagent_cron_${task.id}|${task.id}`]);
  assert.equal(await manager.runTaskNow('nope'), false);
});

test('prepareTriggeredRun: 门/计数/下一次重算;disabled+manual 放行', async () => {
  const { manager } = await makeManager();
  const task: AgentCronTask = await manager.createTask('', 'p', '0 9 * * *', ZONE, true);
  const prepared: AgentCronTask | null = await manager.prepareTriggeredRun(task.id);
  assert.ok(prepared !== null);
  assert.equal(prepared.lastStatus, 'Queued');
  assert.equal(prepared.runCount, 1);
  assert.ok(prepared.lastRunAtMs !== null);
  assert.ok(prepared.nextRunAtMs !== null && prepared.nextRunAtMs > Date.now() - 60_000);

  // 禁用任务:非 manual → null;manual → 放行
  const disabled: AgentCronTask = await manager.createTask('', 'p2', '0 9 * * *', ZONE, false);
  assert.equal(await manager.prepareTriggeredRun(disabled.id), null);
  const manual: AgentCronTask | null = await manager.prepareTriggeredRun(disabled.id, true);
  assert.ok(manual !== null);
  assert.equal(manual.nextRunAtMs, null);   // disabled → 不排下一次
});

// ===== Manager(:197-246 mark*/rescheduleAll) =====

test('mark 三态 + rescheduleAll 取消禁用项', async () => {
  const { manager, scheduler } = await makeManager();
  const on: AgentCronTask = await manager.createTask('', 'p1', '0 9 * * *', ZONE, true);
  const off: AgentCronTask = await manager.createTask('', 'p2', '0 10 * * *', ZONE, false);

  await manager.markRunStarted(on.id);
  let listed: AgentCronTask[] = await manager.listTasks();
  assert.equal(listed.find((t: AgentCronTask): boolean => t.id === on.id)?.lastStatus, 'Running');

  await manager.markRunCompleted(on.id);
  listed = await manager.listTasks();
  assert.equal(listed.find((t: AgentCronTask): boolean => t.id === on.id)?.lastStatus, 'Succeeded');

  const longError: string = 'e'.repeat(600);
  await manager.markRunFailed(on.id, longError);
  listed = await manager.listTasks();
  const failed: AgentCronTask | undefined = listed.find((t: AgentCronTask): boolean => t.id === on.id);
  assert.equal(failed?.lastStatus, 'Failed');
  assert.equal(failed?.lastError?.length, 500);   // take(500)

  scheduler.cancelled = [];
  await manager.rescheduleAll();
  assert.ok(scheduler.cancelled.indexOf(`amberagent_cron_${off.id}`) >= 0);
  assert.ok(scheduler.scheduled.some((c: { workName: string }): boolean => c.workName === `amberagent_cron_${on.id}`));
});

// ===== decode(:284-317 严格→宽松→空) =====

test('decode: 严格失败回退宽松(id/cronExpression 必填,缺省回退)', async () => {
  const { manager, persistence } = await makeManager();
  // 缺 enabled 等必填 → 严格失败 → 宽松:enabled=false,时区=系统默认
  persistence.raw = JSON.stringify({
    tasks: [
      { id: 'a', cronExpression: '0 9 * * *' },
      { cronExpression: '0 9 * * *' },          // 无 id → 跳过
      { id: 'b' },                               // 无 cronExpression → 跳过
      { id: 'c', cronExpression: '*/5 * * * *', enabled: true, lastStatus: 'queued', runCount: 3 },
    ],
  });
  const tasks: AgentCronTask[] = await manager.listTasks();
  assert.equal(tasks.length, 2);
  const a: AgentCronTask | undefined = tasks.find((t: AgentCronTask): boolean => t.id === 'a');
  assert.ok(a !== undefined);
  assert.equal(a.enabled, false);
  assert.equal(a.lastStatus, 'Idle');
  const c: AgentCronTask | undefined = tasks.find((t: AgentCronTask): boolean => t.id === 'c');
  assert.ok(c !== undefined);
  assert.equal(c.enabled, true);
  assert.equal(c.lastStatus, 'Queued');          // ignoreCase 匹配
  assert.equal(c.runCount, 3);

  // 全坏 → 空
  persistence.raw = 'not json at all';
  assert.deepEqual(await manager.listTasks(), []);
  persistence.raw = null;
  assert.deepEqual(await manager.listTasks(), []);
});

// ===== 排序持久化(:265 nextRunAtMs 升序,null 最后) =====

test('replaceTasks: 按 nextRunAtMs 升序持久化(disabled 居尾)', async () => {
  const { manager, persistence } = await makeManager();
  const early: AgentCronTask = await manager.createTask('', 'p1', '* * * * *', ZONE, true);
  const late: AgentCronTask = await manager.createTask('', 'p2', '0 0 1 1 *', ZONE, true);
  const off: AgentCronTask = await manager.createTask('', 'p3', '0 9 * * *', ZONE, false);
  const raw: JsonObject = JSON.parse(persistence.raw as string) as JsonObject;
  const ids: string[] = (raw['tasks'] as JsonValue[]).map(
    (t: JsonValue): string => (t as JsonObject)['id'] as string);
  assert.deepEqual(ids, [early.id, late.id, off.id]);
});

// ===== AgentCronTools(全文 150) =====

test('tools: 四件定义(name/审批旗标/required)', async () => {
  const { manager } = await makeManager();
  const tools = new AgentCronTools(manager).getTools();
  assert.deepEqual(tools.map((t): string => t.name), [
    'cron_task_list', 'cron_task_create', 'cron_task_update', 'cron_task_delete',
  ]);
  assert.equal(tools[0].needsApproval, false);
  assert.equal(tools[1].needsApproval, true);
  assert.equal(tools[1].allowsAutoApproval, false);
  assert.equal(tools[2].needsApproval, true);
  assert.equal(tools[3].needsApproval, true);
  const createParams = tools[1].parameters();
  assert.deepEqual(createParams !== null ? createParams.required : null, ['prompt', 'cron_expression']);
  const updateParams = tools[2].parameters();
  assert.deepEqual(updateParams !== null ? updateParams.required : null, ['task_id']);
});

test('tools: create → list 全字段键序 + toJson 可空键省略', async () => {
  const { manager } = await makeManager();
  const tools = new AgentCronTools(manager).getTools();
  const created: JsonObject = textOf(await tools[1].execute({
    title: '晨报', prompt: '写早报', cron_expression: '0 9 * * *', timezone: ZONE,
  }));
  assert.equal(created['status'], 'created');
  const task: JsonObject = created['task'] as JsonObject;
  assert.deepEqual(Object.keys(task), [
    'id', 'title', 'prompt', 'cron_expression', 'timezone', 'conversation_id',
    'enabled', 'next_run_at_ms', 'last_status', 'run_count', 'created_at_ms', 'updated_at_ms',
  ]);
  assert.equal(task['enabled'], true);             // 默认 true
  assert.equal(task['last_status'], 'idle');
  assert.equal(task['last_run_at_ms'], undefined); // null → 键省略
  assert.equal(task['last_error'], undefined);

  const listed: JsonObject = textOf(await tools[0].execute({}));
  assert.equal(listed['status'], 'ok');
  assert.equal((listed['tasks'] as JsonValue[]).length, 1);
});

test('tools: update/delete + 输入助手(required/严格布尔)', async () => {
  const { manager } = await makeManager();
  const tools = new AgentCronTools(manager).getTools();
  const created: JsonObject = textOf(await tools[1].execute({
    prompt: 'p', cron_expression: '0 9 * * *',
  }));
  const taskId: string = ((created['task'] as JsonObject)['id']) as string;

  // requiredString 缺失 → 'task_id is required'
  await assert.rejects(() => tools[2].execute({}), /task_id is required/);

  // enabled='false' 字符串(contentOrNull → toBooleanStrictOrNull)
  const updated: JsonObject = textOf(await tools[2].execute({ task_id: taskId, enabled: 'false' }));
  assert.equal(updated['status'], 'updated');
  assert.equal((updated['task'] as JsonObject)['enabled'], false);

  const deleted: JsonObject = textOf(await tools[3].execute({ task_id: taskId }));
  assert.equal(deleted['status'], 'deleted');
  assert.equal(deleted['task_id'], taskId);
  const again: JsonObject = textOf(await tools[3].execute({ task_id: taskId }));
  assert.equal(again['status'], 'not_found');
});

test('tools: prompt.take(2000) 截断', async () => {
  const { manager } = await makeManager();
  const tools = new AgentCronTools(manager).getTools();
  const created: JsonObject = textOf(await tools[1].execute({
    prompt: 'x'.repeat(3000), cron_expression: '0 9 * * *',
  }));
  assert.equal(((created['task'] as JsonObject)['prompt'] as string).length, 2000);
});
