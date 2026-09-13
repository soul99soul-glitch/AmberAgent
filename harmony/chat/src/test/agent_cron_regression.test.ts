// agent_cron_regression.test.ts — R12/R17 回归(域层)
//
// 只加载真实域代码(agent_cron.ts / agent_cron_run_executor.ts),平台边缘用内存
// mock;无真实定时器/设备/网络。R16 + entry inFlight 控制流在
// docs/reviews/2026-09-13-harmony-repair/probes/probe-batch3-cron.cjs 用真实
// AgentCronStore.ets + 假时钟覆盖。

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  AgentCronManager, makeAgentCronTask,
  type AgentCronPersistencePort, type AgentCronSchedulerPort, type AgentCronTask,
} from '../main/ets/chat/agent_cron.ts';
import { AgentTaskStore, type AgentTaskFilePort } from '../main/ets/chat/agent_task.ts';
import {
  runCronTaskOnce, type CronRunExecutorDeps, type CronNotifierPort,
} from '../main/ets/chat/agent_cron_run_executor.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';
import { createMemoryConversationRepository } from '../main/ets/chat/persistence.ts';
import { createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { makeAssistantMessage } from '../main/ets/chat/message.ts';
import type { ChatTurnDeps } from '../main/ets/chat/chat_turn.ts';

const ZONE: string = 'UTC';

class MemPersistence implements AgentCronPersistencePort {
  raw: string | null = null;
  read(): Promise<string | null> { return Promise.resolve(this.raw); }
  write(raw: string): Promise<void> { this.raw = raw; return Promise.resolve(); }
}

class MemScheduler implements AgentCronSchedulerPort {
  scheduled: string[] = [];
  ranNow: string[] = [];
  cancelled: string[] = [];
  schedule(workName: string): void { this.scheduled.push(workName); }
  runNow(workName: string): void { this.ranNow.push(workName); }
  cancel(workName: string): void { this.cancelled.push(workName); }
}

class MemFiles implements AgentTaskFilePort {
  files: Map<string, string> = new Map();
  mkdirs(): Promise<void> { return Promise.resolve(); }
  listJsonFileNames(): Promise<string[]> { return Promise.resolve([]); }
  readText(path: string): Promise<string | null> {
    const v = this.files.get(path);
    return Promise.resolve(v === undefined ? null : v);
  }
  writeText(path: string, text: string): Promise<void> { this.files.set(path, text); return Promise.resolve(); }
  delete(path: string): Promise<void> { this.files.delete(path); return Promise.resolve(); }
  exists(path: string): Promise<boolean> { return Promise.resolve(this.files.has(path)); }
  isPathInside(root: string, path: string): Promise<boolean> {
    return Promise.resolve(path.startsWith(`${root}/`));
  }
}

const makeManager = async (): Promise<{
  manager: AgentCronManager; persistence: MemPersistence; scheduler: MemScheduler; store: AgentTaskStore;
}> => {
  const persistence = new MemPersistence();
  const scheduler = new MemScheduler();
  const store: AgentTaskStore = await AgentTaskStore.create({
    taskDir: '/t/tasks', appFilesDir: '/t', files: new MemFiles(),
  });
  return { manager: new AgentCronManager(persistence, scheduler, store), persistence, scheduler, store };
};

// ===== R12:同任务原子 claim(重入不双执行) =====

test('R12: 同一任务并发 prepare 只放行一次(runCount 不丢更新)', async () => {
  const { manager } = await makeManager();
  const task: AgentCronTask = await manager.createTask('t', 'p', '* * * * *', ZONE, true);

  // 同步发起两次(不 await 第一次)→ 第二次必须被 claim 拒绝
  const first: Promise<AgentCronTask | null> = manager.prepareTriggeredRun(task.id);
  const second: Promise<AgentCronTask | null> = manager.prepareTriggeredRun(task.id);
  const [a, b] = await Promise.all([first, second]);
  assert.ok(a !== null, 'first prepare allowed');
  assert.equal(b, null, 'second prepare rejected while in-flight');

  const listed: AgentCronTask[] = await manager.listTasks();
  assert.equal(listed.find((t: AgentCronTask): boolean => t.id === task.id)?.runCount, 1);

  // 收口释放 claim → 下一周期可再次 claim
  await manager.markRunCompleted(task.id);
  const third: AgentCronTask | null = await manager.prepareTriggeredRun(task.id);
  assert.ok(third !== null);
  assert.equal(third.runCount, 2);
});

test('R12: 并发 prepare 不同任务时 replaceTasks 读改写互斥(两队均持久化)', async () => {
  const { manager } = await makeManager();
  const t1: AgentCronTask = await manager.createTask('a', 'p1', '* * * * *', ZONE, true);
  const t2: AgentCronTask = await manager.createTask('b', 'p2', '* * * * *', ZONE, true);
  const [a, b] = await Promise.all([
    manager.prepareTriggeredRun(t1.id), manager.prepareTriggeredRun(t2.id),
  ]);
  assert.ok(a !== null && b !== null);
  const listed: AgentCronTask[] = await manager.listTasks();
  assert.equal(listed.length, 2);
  assert.equal(listed.find((t: AgentCronTask): boolean => t.id === t1.id)?.runCount, 1);
  assert.equal(listed.find((t: AgentCronTask): boolean => t.id === t2.id)?.runCount, 1);
});

// ===== R17:取消通道 + 快照 cancelCapability =====

test('R17: Running 快照可取消,回调走 scheduler.cancel;cancelRun 仅对 Running 生效', async () => {
  const { manager, scheduler, store } = await makeManager();
  const task: AgentCronTask = await manager.createTask('t', 'p', '0 9 * * *', ZONE, true);

  // 未运行:无取消能力,cancelRun=false,不触碰 scheduler.cancel
  const cancelledCalls: Array<string> = scheduler.cancelled;
  cancelledCalls.length = 0;
  assert.equal(await manager.cancelRun(task.id), false);
  assert.deepEqual(cancelledCalls, []);
  assert.equal(store.read(task.id)?.cancelCapability, false);

  await manager.prepareTriggeredRun(task.id);
  await manager.markRunStarted(task.id);
  assert.equal(store.read(task.id)?.cancelCapability, true);

  const targetWorkName: string = `amberagent_cron_${task.id}`;
  assert.equal(await manager.cancelRun(task.id), true);
  assert.ok(cancelledCalls.some((w: string): boolean => w === targetWorkName));

  // 看板 cancel 也经注册回调(scheduler.cancel),并置 CANCELLED
  const snap = await store.cancel(task.id);
  assert.equal(snap.status, 'CANCELLED');
});

test('R17: markRunCancelled 记 Cancelled(非 Succeeded)并明示下一周期保留', async () => {
  const { manager } = await makeManager();
  const task: AgentCronTask = await manager.createTask('t', 'p', '0 9 * * *', ZONE, true);
  await manager.prepareTriggeredRun(task.id);
  await manager.markRunStarted(task.id);
  await manager.markRunCancelled(task.id);
  const listed: AgentCronTask[] = await manager.listTasks();
  const found: AgentCronTask | undefined = listed.find((t: AgentCronTask): boolean => t.id === task.id);
  assert.equal(found?.lastStatus, 'Cancelled');
  assert.ok((found?.lastError ?? '').indexOf('next cycle') >= 0);
  assert.notEqual(found?.lastStatus, 'Succeeded');
});

test('R17: 已删除任务的 markRunCancelled/scheduleNextRun 不复活(无副作用)', async () => {
  const { manager } = await makeManager();
  const task: AgentCronTask = await manager.createTask('t', 'p', '0 9 * * *', ZONE, true);
  await manager.prepareTriggeredRun(task.id);
  await manager.markRunStarted(task.id);
  assert.equal(await manager.deleteTask(task.id), true);
  // 在途 fire 的收口调用:任务已不在 → 不写回、不重排
  await manager.markRunCancelled(task.id);
  await manager.scheduleNextRun(task.id);
  assert.deepEqual(await manager.listTasks(), []);
});

// ===== R17:run_executor signal 透传 =====

test('R17: runCronTaskOnce 将 signal 透传给 provider', async () => {
  const { manager } = await makeManager();
  const repository = createMemoryConversationRepository();
  const events: string[] = [];
  const notifier: CronNotifierPort = {
    notifyRunning: () => { events.push('running'); },
    notifyCompleted: () => { events.push('completed'); },
    notifyFailed: () => { events.push('failed'); },
    cancel: () => {},
  };
  const task = makeAgentCronTask({
    id: 'sig-1', title: 't', prompt: 'p', cronExpression: '* * * * *',
    timezoneId: ZONE, conversationId: 'conv-sig', enabled: true,
    createdAtMs: 0, updatedAtMs: 0,
  });
  let seenSignal: AbortSignalLike | undefined = undefined;
  const deps: CronRunExecutorDeps = {
    manager,
    repository,
    notifier,
    buildTurnDeps: (): ChatTurnDeps => ({
      assistant: makeAssistant({ name: 'cron' }),
      inputTransformers: [],
      outputTransformers: [],
      provider: {
        streamText: async (_m, onChunk, opts): Promise<void> => {
          seenSignal = opts?.signal;
          onChunk({
            id: 'c', model: 'm', usage: null,
            choices: [{ index: 0, delta: makeAssistantMessage('done'), message: null, finishReason: null }],
          });
        },
      },
      store: createMemoryConversationStore(),
    }),
  };
  const signal: AbortSignalLike = { aborted: false };
  const result = await runCronTaskOnce(deps, task, signal);
  assert.equal(result.ok, true);
  assert.equal(seenSignal, signal, 'provider received the run signal');
  assert.deepEqual(events, ['running', 'completed']);
});
