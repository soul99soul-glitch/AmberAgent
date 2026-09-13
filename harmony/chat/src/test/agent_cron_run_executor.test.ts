// Cron run executor tests

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  runCronTaskOnce, fireDueCronTasks, isCronTaskDue,
  type CronRunExecutorDeps, type CronNotifierPort,
} from '../main/ets/chat/agent_cron_run_executor.ts';
import { AgentCronManager, makeAgentCronTask } from '../main/ets/chat/agent_cron.ts';
import type { AgentCronPersistencePort, AgentCronSchedulerPort } from '../main/ets/chat/agent_cron.ts';
import { AgentTaskStore, type AgentTaskFilePort } from '../main/ets/chat/agent_task.ts';
import { createMemoryConversationRepository } from '../main/ets/chat/persistence.ts';
import { createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { makeAssistantMessage } from '../main/ets/chat/message.ts';
import type { ChatTurnDeps } from '../main/ets/chat/chat_turn.ts';

class MemPersistence implements AgentCronPersistencePort {
  raw: string | null = null;
  read(): Promise<string | null> { return Promise.resolve(this.raw); }
  write(raw: string): Promise<void> { this.raw = raw; return Promise.resolve(); }
}

class MemScheduler implements AgentCronSchedulerPort {
  schedule(): void {}
  runNow(): void {}
  cancel(): void {}
}

class MemFiles implements AgentTaskFilePort {
  files: Map<string, string> = new Map();
  mkdirs(): Promise<void> { return Promise.resolve(); }
  listJsonFileNames(): Promise<string[]> { return Promise.resolve([]); }
  readText(path: string): Promise<string | null> {
    const v = this.files.get(path);
    return Promise.resolve(v === undefined ? null : v);
  }
  writeText(path: string, text: string): Promise<void> {
    this.files.set(path, text);
    return Promise.resolve();
  }
  delete(path: string): Promise<void> { this.files.delete(path); return Promise.resolve(); }
  exists(path: string): Promise<boolean> { return Promise.resolve(this.files.has(path)); }
  isPathInside(root: string, path: string): Promise<boolean> {
    return Promise.resolve(path.startsWith(`${root}/`));
  }
}

const makeManager = async (): Promise<AgentCronManager> => {
  const taskStore: AgentTaskStore = await AgentTaskStore.create({
    taskDir: '/tmp/tasks',
    appFilesDir: '/tmp',
    files: new MemFiles(),
  });
  return new AgentCronManager(new MemPersistence(), new MemScheduler(), taskStore);
};

test('isCronTaskDue', () => {
  const t = makeAgentCronTask({
    id: '1', title: 't', prompt: 'p', cronExpression: '* * * * *',
    timezoneId: 'UTC', conversationId: '', enabled: true,
    createdAtMs: 0, updatedAtMs: 0, nextRunAtMs: 100,
  });
  assert.equal(isCronTaskDue(t, 50), false);
  assert.equal(isCronTaskDue(t, 100), true);
  assert.equal(isCronTaskDue({ ...t, enabled: false }, 100), false);
});

test('runCronTaskOnce: success notifies without marking (scheduler owns state)', async () => {
  const manager = await makeManager();
  const repository = createMemoryConversationRepository();
  const events: string[] = [];
  const notifier: CronNotifierPort = {
    notifyRunning: () => { events.push('running'); },
    notifyCompleted: () => { events.push('completed'); },
    notifyFailed: () => { events.push('failed'); },
    cancel: () => {},
  };
  const task = makeAgentCronTask({
    id: 'task-1', title: '日报', prompt: '写日报', cronExpression: '* * * * *',
    timezoneId: 'UTC', conversationId: 'conv-1', enabled: true,
    createdAtMs: 0, updatedAtMs: 0,
  });
  const store = createMemoryConversationStore();
  const deps: CronRunExecutorDeps = {
    manager,
    repository,
    notifier,
    buildTurnDeps: (): ChatTurnDeps => ({
      assistant: makeAssistant({ name: 'cron' }),
      inputTransformers: [],
      outputTransformers: [],
      provider: {
        streamText: async (_m, onChunk): Promise<void> => {
          onChunk({
            id: 'c', model: 'm', usage: null,
            choices: [{ index: 0, delta: makeAssistantMessage('done'), message: null, finishReason: null }],
          });
        },
      },
      store,
    }),
  };
  const result = await runCronTaskOnce(deps, task);
  assert.equal(result.ok, true);
  assert.deepEqual(events, ['running', 'completed']);
  const saved = await repository.getById('conv-1');
  assert.ok(saved !== null);
  assert.ok(saved!.messageNodes.length >= 2);
});

test('runCronTaskOnce: failure notifies without marking (scheduler owns state)', async () => {
  const manager = await makeManager();
  const repository = createMemoryConversationRepository();
  const events: string[] = [];
  const task = makeAgentCronTask({
    id: 'task-2', title: 'x', prompt: 'p', cronExpression: '* * * * *',
    timezoneId: 'UTC', conversationId: '', enabled: true,
    createdAtMs: 0, updatedAtMs: 0,
  });
  const deps: CronRunExecutorDeps = {
    manager,
    repository,
    notifier: {
      notifyRunning: () => { events.push('running'); },
      notifyCompleted: () => { events.push('completed'); },
      notifyFailed: () => { events.push('failed'); },
      cancel: () => {},
    },
    buildTurnDeps: (): ChatTurnDeps => ({
      assistant: makeAssistant({}),
      inputTransformers: [],
      outputTransformers: [],
      provider: {
        streamText: (): Promise<void> => Promise.reject(new Error('provider down')),
      },
      store: createMemoryConversationStore(),
    }),
  };
  const result = await runCronTaskOnce(deps, task);
  assert.equal(result.ok, false);
  assert.equal(result.error?.message, 'provider down');
  assert.deepEqual(events, ['running', 'failed']);
});

test('fireDueCronTasks: empty list no runs', async () => {
  const manager = await makeManager();
  const repository = createMemoryConversationRepository();
  const deps: CronRunExecutorDeps = {
    manager,
    repository,
    buildTurnDeps: (): ChatTurnDeps => ({
      assistant: makeAssistant({}),
      inputTransformers: [],
      outputTransformers: [],
      provider: { streamText: async (): Promise<void> => {} },
      store: createMemoryConversationStore(),
    }),
  };
  const out = await fireDueCronTasks(deps, 1000);
  assert.equal(out.length, 0);
});
