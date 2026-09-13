// probe-cron-longdelay.ts — 独立复核「cron 远期任务 delay 截断至 2^31-1ms 导致提前执行」
// 运行：cd harmony/chat && npx tsx <此文件绝对路径>
// 方法：真实 AgentCronManager(.ts) + 真实 createEntryCronSchedulerPort(转译 AgentCronStore.ets)
//        + 注入假 setTimeout/clearTimeout（记录 delay，不真等 25 天）+ 受控 Date.now。
// 约束：无网络/模型/文件/真实定时器副作用；runExecutor 为纯记录桩，绝不调用真实 chat 执行体。
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { createRequire } from 'node:module';
import vm from 'node:vm';
import { AgentCronManager } from '../../../../harmony/chat/src/main/ets/chat/agent_cron.ts';
import type { AgentCronPersistencePort, AgentCronTask } from '../../../../harmony/chat/src/main/ets/chat/agent_cron.ts';
import { AgentTaskStore } from '../../../../harmony/chat/src/main/ets/chat/agent_task.ts';
import type { AgentTaskFilePort } from '../../../../harmony/chat/src/main/ets/chat/agent_task.ts';

const __dir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(__dir, '../../../..');
const require = createRequire(import.meta.url);
const ts = require(resolve(repoRoot, 'harmony/chat/node_modules/typescript'));

let pass = 0, fail = 0;
const check = (name: string, ok: boolean, detail: string = ''): void => {
  if (ok) { pass++; console.log(`PASS  ${name}`); }
  else { fail++; console.log(`FAIL  ${name} :: ${detail}`); }
};

// ===== 加载真实 entry scheduler（转译），注入假时钟 =====
interface FakeTimer { id: number; fn: () => void; delay: number; }
const timers: FakeTimer[] = [];
let nextTimerId = 1;
const fakeSetTimeout = (fn: () => void, delay: number): number => {
  const id = nextTimerId++;
  timers.push({ id, fn, delay });
  return id;
};
const fakeClearTimeout = (id: number): void => {
  const i = timers.findIndex((t) => t.id === id);
  if (i >= 0) timers.splice(i, 1);
};

const entryPath = resolve(repoRoot, 'harmony/entry/src/main/ets/platform_impl/AgentCronStore.ets');
const js = ts.transpileModule(readFileSync(entryPath, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const moduleObj: { exports: Record<string, unknown> } = { exports: {} };
const mockRequire = (name: string): unknown => {
  if (name === '@kit.CoreFileKit') return { fileIo: {} };
  if (name === '@kit.PerformanceAnalysisKit') return { hilog: { error: () => {}, warn: () => {}, info: () => {} } };
  if (name === '@kit.ArkTS') return { util: {} };
  if (name === '@amber/chat-domain') return {};
  throw new Error(`Unexpected runtime dependency: ${name}`);
};
const sandbox = { console, setTimeout: fakeSetTimeout, clearTimeout: fakeClearTimeout };
vm.runInNewContext(
  `(function(require,module,exports){${js}\n})`, sandbox, { filename: entryPath },
)(mockRequire, moduleObj, moduleObj.exports);
const createEntryCronSchedulerPort = moduleObj.exports['createEntryCronSchedulerPort'] as
  (deps: unknown) => { schedule: (w: string, t: string, d: number) => void; runNow: (w: string, t: string) => void; cancel: (w: string) => void };

// ===== 受控时钟 =====
const realNow = Date.now;
let currentNow = Date.parse('2026-01-02T00:00:00Z');   // 距下一年 1/1 约 364 天
const setNow = (v: number): void => { currentNow = v; };
Date.now = (): number => currentNow;

// ===== 内存端口 =====
class MemKv implements AgentCronPersistencePort {
  raw: string | null = null;
  async read(): Promise<string | null> { return this.raw; }
  async write(raw: string): Promise<void> { this.raw = raw; }
}
class MemFiles implements AgentTaskFilePort {
  files = new Map<string, string>();
  async mkdirs(): Promise<void> {}
  async listJsonFileNames(): Promise<string[]> { return []; }
  async readText(p: string): Promise<string | null> { return this.files.get(p) ?? null; }
  async writeText(p: string, t: string): Promise<void> { this.files.set(p, t); }
  async delete(p: string): Promise<void> { this.files.delete(p); }
  async exists(p: string): Promise<boolean> { return this.files.has(p); }
  async isPathInside(root: string, p: string): Promise<boolean> { return p.startsWith(`${root}/`); }
}
const drain = (): Promise<void> => new Promise<void>((r) => { setTimeout(r, 0); });
const flush = async (): Promise<void> => { for (let i = 0; i < 20; i++) await drain(); };

(async () => {
  const MAX_DELAY = 2147483647; // 2^31-1 ≈ 24.855 天

  // ---------- 1. 纯 scheduler 层：clamp 边界 ----------
  {
    timers.length = 0; nextTimerId = 1;
    const port = createEntryCronSchedulerPort({
      manager: async () => ({ prepareTriggeredRun: async () => null, markRunStarted: async () => {}, markRunCompleted: async () => {}, markRunFailed: async () => {}, scheduleNextRun: async () => {} }),
      runExecutor: async () => {},
    });
    port.schedule('w-near', 't1', 3_600_000);                 // 1 小时
    port.schedule('w-far', 't2', 3_000_000_000);              // > 2^31-1
    port.schedule('w-neg', 't3', -5);
    const near = timers.find((t) => t.fn !== undefined && timers.indexOf(t) === 0);
    // 按插入顺序取：near=1h, far=clamped, neg=0
    check('CL1 near delay (1h) is NOT clamped', timers[0].delay === 3_600_000, `delay=${timers[0].delay}`);
    check('CL2 far delay (3e9) is clamped to 2^31-1', timers[1].delay === MAX_DELAY, `delay=${timers[1].delay}`);
    check('CL3 negative delay clamped to 0', timers[2].delay === 0, `delay=${timers[2].delay}`);
    const raw = 3_000_000_000;
    check('CL4 lost time = raw - clamp = far early by ~9.6 天', raw - MAX_DELAY === 852_516_353, `lost=${raw - MAX_DELAY}`);
  }

  // ---------- 2. 真实 manager + 真实 entry scheduler：远期任务被提前触发 ----------
  {
    timers.length = 0; nextTimerId = 1;
    setNow(Date.parse('2026-01-02T00:00:00Z'));

    const kv = new MemKv();
    const files = new MemFiles();
    const store = await AgentTaskStore.create({ taskDir: '/mem/tasks', appFilesDir: '/mem', files });
    let executorCalls: string[] = [];
    const port = createEntryCronSchedulerPort({
      manager: async () => manager,
      runExecutor: async (taskId: string) => { executorCalls.push(taskId); },
    });
    let manager!: AgentCronManager;
    manager = new AgentCronManager(kv, port as never, store);

    // 年度任务：下一次执行在约 364 天后
    const task: AgentCronTask = await manager.createTask('yearly', 'p', '0 0 1 1 *', 'UTC', true);
    const dueAt = task.nextRunAtMs as number;
    const gap = dueAt - currentNow;
    check('CL5 yearly task due > 2^31-1ms out (premise)', gap > MAX_DELAY,
      `gap=${gap}ms (${(gap / 86400000).toFixed(1)}d)`);
    check('CL6 createTask scheduled the far timer clamped to 2^31-1 (already early)',
      timers.length === 1 && timers[0].delay === MAX_DELAY, `timers=${JSON.stringify(timers.map((t) => t.delay))}`);

    // 真实提前触发：活进程第 24.855 天（远早于 due）
    const timer = timers.shift() as FakeTimer;
    setNow(Date.parse('2026-01-02T00:00:00Z') + timer.delay);
    check('CL7 at fire time still NOT due (now < dueAt)', currentNow < dueAt,
      `now=${currentNow} due=${dueAt} remain=${((dueAt - currentNow) / 86400000).toFixed(1)}d`);

    timer.fn();          // = setTimeout 到期回调 → fire → prepareTriggeredRun(markRunStarted)→executor
    await flush();

    check('CL8 executor REALLY ran (early execution, not just rescheduled)', executorCalls.includes(task.id),
      `calls=${JSON.stringify(executorCalls)}`);
    const snap = (await manager.listTasks()).find((t) => t.id === task.id) as AgentCronTask;
    check('CL9 task marked Succeeded with runCount=1 (early run treated as normal)',
      snap.lastStatus === 'Succeeded' && snap.runCount === 1, `status=${snap.lastStatus} runCount=${snap.runCount}`);

    // 提前执行后 scheduleNextRun 再次 clamp → 周期性提前执行（放大）
    check('CL10 after early run, next timer is clamped AGAIN (repeats every ~24.855d)',
      timers.length >= 1 && timers[timers.length - 1].delay === MAX_DELAY,
      `timers=${JSON.stringify(timers.map((t) => t.delay))}`);

    // 启用条件反证/联动：prepareTriggeredRun 不校验 due（根因之一，D-08；此处仅作联动证据）
    const farPrepared = await manager.prepareTriggeredRun(task.id, false);
    check('CL11 prepareTriggeredRun executes a task whose now << dueAt (no due guard, enabler)',
      farPrepared !== null, `prepared=${farPrepared !== null}`);
  }

  // ---------- 3. 对照：clamp 窗口内的任务按时触发（非普遍缺陷） ----------
  {
    timers.length = 0; nextTimerId = 1;
    setNow(Date.parse('2026-01-02T00:00:00Z'));
    const kv = new MemKv();
    const files = new MemFiles();
    const store = await AgentTaskStore.create({ taskDir: '/mem/tasks', appFilesDir: '/mem', files });
    let executorCalls: string[] = [];
    const port = createEntryCronSchedulerPort({
      manager: async () => manager,
      runExecutor: async (taskId: string) => { executorCalls.push(taskId); },
    });
    let manager!: AgentCronManager;
    manager = new AgentCronManager(kv, port as never, store);
    // 每分钟继续跑，下一次必然 < 2^31-1
    const task: AgentCronTask = await manager.createTask('everymin', 'p', '* * * * *', 'UTC', true);
    const gap = (task.nextRunAtMs as number) - currentNow;
    check('CT1 near task delay NOT clamped (control)', gap <= MAX_DELAY && timers[0].delay === gap,
      `gap=${gap} delay=${timers[0].delay}`);
    const timer = timers.shift() as FakeTimer;
    setNow(currentNow + timer.delay);
    timer.fn();
    await flush();
    check('CT2 near task still executes normally at due', executorCalls.includes(task.id), `calls=${executorCalls.length}`);
  }

  Date.now = realNow;
  console.log(`\n=== probe-cron-longdelay summary: pass=${pass} fail=${fail} ===`);
  if (fail > 0) process.exitCode = 1;
})().catch((e) => { Date.now = realNow; console.error(e); process.exitCode = 1; });
