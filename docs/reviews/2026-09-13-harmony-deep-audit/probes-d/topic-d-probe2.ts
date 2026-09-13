// topic-d-probe2.ts — 专题 D 隔离合成 probe(第二批:dispatcher/cron executor)
// 只调用仓库真实 chat-domain .ts 逻辑 + 内存假端口;无真实网络/模型/文件副作用。
// 运行:cd harmony/chat && npx tsx <此文件绝对路径>

import assert from 'node:assert/strict';
import type { JsonObject } from '../../../../harmony/chat/src/main/ets/chat/json.ts';
import type { UIMessagePart, UIMessagePartTool } from '../../../../harmony/chat/src/main/ets/chat/message.ts';
import { makeAgentTool } from '../../../../harmony/chat/src/main/ets/chat/tool.ts';
import type { AgentTool } from '../../../../harmony/chat/src/main/ets/chat/tool.ts';
import { toolInvocationPolicy } from '../../../../harmony/chat/src/main/ets/chat/tool_policy.ts';
import { AgentToolDispatcher } from '../../../../harmony/chat/src/main/ets/chat/tool_dispatcher.ts';
import {
  AgentCronManager,
} from '../../../../harmony/chat/src/main/ets/chat/agent_cron.ts';
import type {
  AgentCronPersistencePort, AgentCronSchedulerPort, AgentCronTask,
} from '../../../../harmony/chat/src/main/ets/chat/agent_cron.ts';
import { AgentTaskStore } from '../../../../harmony/chat/src/main/ets/chat/agent_task.ts';
import type { AgentTaskFilePort } from '../../../../harmony/chat/src/main/ets/chat/agent_task.ts';
import {
  fireDueCronTasks, isCronTaskDue,
} from '../../../../harmony/chat/src/main/ets/chat/agent_cron_run_executor.ts';
import type {
  CronRunExecutorDeps, CronNotifierPort, CronRunResult,
} from '../../../../harmony/chat/src/main/ets/chat/agent_cron_run_executor.ts';
import { runChatTurn } from '../../../../harmony/chat/src/main/ets/chat/chat_turn.ts';
import type { ChatTurnDeps, ChatStreamProvider } from '../../../../harmony/chat/src/main/ets/chat/chat_turn.ts';
import { makeAssistant } from '../../../../harmony/chat/src/main/ets/chat/assistant.ts';
import type { Conversation } from '../../../../harmony/chat/src/main/ets/chat/conversation.ts';
import type { ConversationRepository } from '../../../../harmony/chat/src/main/ets/chat/persistence.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';

let passCount = 0;
let failCount = 0;
const probe = async (name: string, body: () => Promise<void>): Promise<void> => {
  try {
    await body();
    passCount++;
    console.log(`PASS  ${name}`);
  } catch (e) {
    failCount++;
    console.log(`FAIL  ${name}`);
    console.log(`      ${e instanceof Error ? e.message : String(e)}`);
  }
};

const textPart = (text: string): UIMessagePart => ({ type: 'text', text, metadata: null });
const toolPart = (name: string, input: string, id: string): UIMessagePartTool => ({
  type: 'tool', toolName: name, toolCallId: id, input, output: [],
  approvalState: { type: 'auto' }, metadata: null,
});

class FakeSignal implements AbortSignalLike {
  aborted = false;
  private listeners: Array<() => void> = [];
  addEventListener(type: string, listener: () => void): void {
    if (type === 'abort') this.listeners.push(listener);
  }
  removeEventListener(type: string, listener: () => void): void {
    if (type !== 'abort') return;
    const i = this.listeners.indexOf(listener);
    if (i >= 0) this.listeners.splice(i, 1);
  }
  abort(): void {
    this.aborted = true;
    this.listeners.slice().forEach((l) => l());
    this.listeners = [];
  }
}

// ===== P9:dispatcher 取消/审批语义 =====
const makeReadTool = (counter: { n: number }): AgentTool => makeAgentTool({
  name: 'file_read',
  description: 'read',
  execute: async (): Promise<UIMessagePart[]> => {
    counter.n++;
    return [textPart(JSON.stringify({ output: 'x' }))];
  },
});

await probe('P9a file_read policy is parallel-safe read (premise)', async () => {
  const p = toolInvocationPolicy(makeReadTool({ n: 0 }), { path: '/a' });
  assert.equal(p.category, 'workspace');
  assert.equal(p.mutates, false);
  assert.equal(p.risk, 'normal');
  assert.equal(p.needsApproval, false);
  assert.equal(p.concurrencySafe, true);
  assert.equal(p.parallelGroup, 'workspace');
});

await probe('P9b pre-aborted signal => zero tool side effects, empty results', async () => {
  const counter = { n: 0 };
  const dispatcher = new AgentToolDispatcher();
  const sig = new FakeSignal();
  sig.abort();
  const defs = new Map<string, AgentTool>([['file_read', makeReadTool(counter)]]);
  const out = await dispatcher.executeBatch(
    [toolPart('file_read', '{"path":"/a"}', 't1')], defs, false, false, [], 'normal',
    new Map(), undefined, sig);
  assert.equal(out.length, 0, `out.length=${out.length}`);
  assert.equal(counter.n, 0, `executed=${counter.n}`);
});

await probe('P9c two parallel reads both execute, order preserved', async () => {
  const counter = { n: 0 };
  const dispatcher = new AgentToolDispatcher();
  const defs = new Map<string, AgentTool>([['file_read', makeReadTool(counter)]]);
  const out = await dispatcher.executeBatch(
    [toolPart('file_read', '{"path":"/a"}', 't1'), toolPart('file_read', '{"path":"/b"}', 't2')],
    defs, false, false, [], 'normal');
  assert.equal(counter.n, 2);
  assert.equal(out.length, 2);
  assert.equal(out[0].toolCallId, 't1');
  assert.equal(out[1].toolCallId, 't2');
});

await probe('P9d pending approval tool is not executed (returns null)', async () => {
  const counter = { n: 0 };
  const dispatcher = new AgentToolDispatcher();
  const defs = new Map<string, AgentTool>([['file_read', makeReadTool(counter)]]);
  const pending = toolPart('file_read', '{"path":"/a"}', 't3');
  pending.approvalState = { type: 'pending' };
  const r = await dispatcher.execute(pending, defs.get('file_read') ?? null, false, false, [], 'normal');
  assert.equal(r, null, 'pending must yield null (wait for user)');
  assert.equal(counter.n, 0);
});

await probe('P9e denied tool yields denied output, never executes', async () => {
  const counter = { n: 0 };
  const dispatcher = new AgentToolDispatcher();
  const denied = toolPart('file_read', '{"path":"/a"}', 't4');
  denied.approvalState = { type: 'denied', reason: 'nope' };
  const r = await dispatcher.execute(denied, makeReadTool(counter), false, false, [], 'normal');
  assert.ok(r !== null);
  assert.equal(counter.n, 0);
  const txt = (r?.output[0] as { text: string }).text;
  assert.match(txt, /"status":"denied"/);
});

// ===== P10:cron run executor(fireDueCronTasks)无运行锁 =====
class MemKv implements AgentCronPersistencePort {
  raw: string | null = null;
  async read(): Promise<string | null> { return this.raw; }
  async write(raw: string): Promise<void> { this.raw = raw; }
}
class MemScheduler implements AgentCronSchedulerPort {
  schedule(): void {}
  runNow(): void {}
  cancel(): void {}
}
class MemFiles implements AgentTaskFilePort {
  files = new Map<string, string>();
  async mkdirs(): Promise<void> {}
  async listJsonFileNames(dir: string): Promise<string[]> {
    const out: string[] = [];
    this.files.forEach((_v, k) => { if (k.startsWith(`${dir}/`) && k.endsWith('.json')) out.push(k.slice(dir.length + 1)); });
    return out;
  }
  async readText(p: string): Promise<string | null> { return this.files.has(p) ? this.files.get(p) as string : null; }
  async writeText(p: string, t: string): Promise<void> { this.files.set(p, t); }
  async delete(p: string): Promise<void> { this.files.delete(p); }
  async exists(p: string): Promise<boolean> { return this.files.has(p); }
  async isPathInside(root: string, p: string): Promise<boolean> { return p.startsWith(`${root}/`); }
}

const makeDeps = async (): Promise<{
  deps: CronRunExecutorDeps; manager: AgentCronManager; runs: string[];
}> => {
  const manager = new AgentCronManager(new MemKv(), new MemScheduler(),
    await AgentTaskStore.create({ taskDir: '/mem/tasks', appFilesDir: '/mem', files: new MemFiles() }));
  const runs: string[] = [];
  const repo = {
    async getById(_id: string): Promise<Conversation | null> { return null; },
    async save(_c: Conversation): Promise<void> {},
  } as unknown as ConversationRepository;
  const deps: CronRunExecutorDeps = {
    manager,
    repository: repo,
    buildTurnDeps: (_c: Conversation, _t: AgentCronTask): ChatTurnDeps => ({
      assistant: makeAssistant({ id: 'a', name: 'a', systemPrompt: '' }),
      inputTransformers: [], outputTransformers: [],
      provider: { streamText: async (): Promise<void> => {} } as ChatStreamProvider,
      store: { save: async (): Promise<void> => {}, load: async (): Promise<Conversation | null> => null } as unknown as ChatTurnDeps['store'],
    }),
  };
  return { deps, manager, runs };
};

import { makeAssistant } from '../../../../harmony/chat/src/main/ets/chat/assistant.ts';

await probe('P10a due cron task runs once per fireDueCronTasks call', async () => {
  const { deps, manager } = await makeDeps();
  const task = await manager.createTask('t', 'p', '* * * * *', 'UTC', true);
  // 强制到期
  await manager.prepareTriggeredRun(task.id, true);
  const now = Date.now() + 10 * 60_000;
  const first = await fireDueCronTasks(deps, now);
  assert.equal(first.length, 1, `first=${first.length}`);
  assert.equal(first[0].ok, true);
});

await probe('P10b concurrent fireDueCronTasks on same due task => double execution', async () => {
  const { deps, manager } = await makeDeps();
  const task = await manager.createTask('t', 'p', '* * * * *', 'UTC', true);
  const now = Date.now() + 10 * 60_000;
  // 两个并发扫描同时看到同一 due 快照(无 claim/锁)
  const [a, b] = await Promise.all([
    fireDueCronTasks(deps, now),
    fireDueCronTasks(deps, now),
  ]);
  assert.equal(a.length + b.length, 2,
    `total runs=${a.length + b.length} (same task executed twice)`);
});

await probe('P10c fireDueCronTasks does not clear due => re-fire persists', async () => {
  const { deps, manager } = await makeDeps();
  const task = await manager.createTask('t', 'p', '* * * * *', 'UTC', true);
  const now = Date.now() + 10 * 60_000;
  await fireDueCronTasks(deps, now);
  // runCronTaskOnce 完全不动 nextRunAtMs(注释:不调用 mark*)
  const tasks = await manager.listTasks();
  assert.ok(isCronTaskDue(tasks[0], now),
    `still due after run: nextRunAtMs=${tasks[0].nextRunAtMs} <= ${now}`);
  const again = await fireDueCronTasks(deps, now);
  assert.equal(again.length, 1, 'fires again — no claim');
});

console.log(`\n=== probe2 summary: pass=${passCount} fail=${failCount} ===`);
if (failCount > 0) process.exitCode = 1;
