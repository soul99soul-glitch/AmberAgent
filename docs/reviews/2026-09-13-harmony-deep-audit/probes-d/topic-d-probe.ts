// topic-d-probe.ts — 专题 D 隔离合成 probe
// 约束:只调用仓库真实 chat-domain .ts 逻辑 + 内存假端口;无真实网络/模型/文件副作用。
// 运行:cd harmony/chat && npx tsx <此文件绝对路径>
//
// 覆盖:
//  P1 wm_click 审批分类与自动批准绕过(真实 tool_policy/tool_permission)
//  P2 cron 无运行锁:重复触发双重执行(真实 AgentCronManager)
//  P3 cron 触发路径不校验 due(真实 prepareTriggeredRun)
//  P4 删除后触发被 executor 兜住(反证;真实 manager)
//  P5 禁用 cron 重启恢复(真实 AgentTaskRecoveryManager)
//  P6 subagent approval_required → 任务永久 RUNNING 且 cancel 无效(真实 SubAgentManager)
//  P7 subagent 超时/取消收口(真实 SubAgentManager)
//  P8 ask_user 单开关不可自动批准(真实 resolver)

import assert from 'node:assert/strict';
import type { JsonObject, JsonValue } from '../../../../harmony/chat/src/main/ets/chat/json.ts';
import type { UIMessagePart } from '../../../../harmony/chat/src/main/ets/chat/message.ts';
import { makeAgentTool } from '../../../../harmony/chat/src/main/ets/chat/tool.ts';
import type { AgentTool } from '../../../../harmony/chat/src/main/ets/chat/tool.ts';
import {
  toolInvocationPolicy,
} from '../../../../harmony/chat/src/main/ets/chat/tool_policy.ts';
import { PermissionDecisionResolver } from '../../../../harmony/chat/src/main/ets/chat/tool_permission.ts';
import type { UIMessagePartTool } from '../../../../harmony/chat/src/main/ets/chat/message.ts';
import {
  AgentCronManager,
} from '../../../../harmony/chat/src/main/ets/chat/agent_cron.ts';
import type {
  AgentCronPersistencePort, AgentCronSchedulerPort, AgentCronTask,
} from '../../../../harmony/chat/src/main/ets/chat/agent_cron.ts';
import {
  AgentTaskStore,
} from '../../../../harmony/chat/src/main/ets/chat/agent_task.ts';
import type {
  AgentTaskFilePort, AgentTaskSnapshot,
} from '../../../../harmony/chat/src/main/ets/chat/agent_task.ts';
import {
  AgentTaskRecoveryManager,
} from '../../../../harmony/chat/src/main/ets/chat/agent_task.ts';
import {
  SubAgentManager,
} from '../../../../harmony/chat/src/main/ets/chat/subagent_manager.ts';
import type {
  SubAgentManagerDeps,
} from '../../../../harmony/chat/src/main/ets/chat/subagent_manager.ts';
import {
  makeSubAgentRuntimeSetting, makeSubAgentOverride,
} from '../../../../harmony/chat/src/main/ets/chat/agent_prompt_config.ts';
import type {
  SubAgentRuntimeSetting,
} from '../../../../harmony/chat/src/main/ets/chat/agent_prompt_config.ts';
import {
  SessionAccessGrantStore,
} from '../../../../harmony/chat/src/main/ets/chat/session_grant_store.ts';
import {
  makeSubAgentResult,
} from '../../../../harmony/chat/src/main/ets/chat/subagent_models.ts';
import type {
  SubAgentResult, SubAgentRun, SubAgentTaskSpec,
} from '../../../../harmony/chat/src/main/ets/chat/subagent_models.ts';
import type {
  SubAgentRunner,
} from '../../../../harmony/chat/src/main/ets/chat/subagent_runner.ts';
import type {
  SubAgentTranscriptPort, SubAgentTranscriptTail,
} from '../../../../harmony/chat/src/main/ets/chat/subagent_transcript.ts';
import type {
  SubAgentDefinition,
} from '../../../../harmony/chat/src/main/ets/chat/agent_prompt_config.ts';

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
  type: 'tool',
  toolName: name,
  toolCallId: id,
  input,
  output: [],
  approvalState: { type: 'auto' },
  metadata: null,
});

// ===== 假端口 =====

class MemKv implements AgentCronPersistencePort {
  raw: string | null = null;
  reads = 0;
  writes = 0;
  async read(): Promise<string | null> {
    this.reads++;
    return this.raw;
  }
  async write(raw: string): Promise<void> {
    this.writes++;
    this.raw = raw;
  }
}

class MemScheduler implements AgentCronSchedulerPort {
  scheduled: Array<{ workName: string; taskId: string; delayMs: number }> = [];
  cancelled: string[] = [];
  schedule(workName: string, taskId: string, delayMs: number): void {
    this.scheduled = this.scheduled.filter((c) => c.workName !== workName);
    this.scheduled.push({ workName, taskId, delayMs });
  }
  runNow(workName: string, taskId: string): void {
    this.scheduled.push({ workName, taskId, delayMs: 0 });
  }
  cancel(workName: string): void {
    this.cancelled.push(workName);
  }
}

class MemFiles implements AgentTaskFilePort {
  files: Map<string, string> = new Map();
  async mkdirs(_dir: string): Promise<void> {}
  async listJsonFileNames(dir: string): Promise<string[]> {
    const out: string[] = [];
    this.files.forEach((_v, k) => {
      if (k.startsWith(`${dir}/`) && k.endsWith('.json')) out.push(k.substring(dir.length + 1));
    });
    return out;
  }
  async readText(path: string): Promise<string | null> {
    return this.files.has(path) ? (this.files.get(path) as string) : null;
  }
  async writeText(path: string, text: string): Promise<void> {
    this.files.set(path, text);
  }
  async delete(path: string): Promise<void> {
    this.files.delete(path);
  }
  async exists(path: string): Promise<boolean> {
    return this.files.has(path);
  }
  async isPathInside(root: string, path: string): Promise<boolean> {
    return path === root || path.startsWith(`${root}/`);
  }
}

class MemTranscript implements SubAgentTranscriptPort {
  data: Map<string, string> = new Map();
  async canonicalPath(path: string): Promise<string | null> {
    return path.length > 0 ? path : null;
  }
  async appendText(path: string, text: string): Promise<void> {
    this.data.set(path, (this.data.get(path) ?? '') + text);
  }
  async exists(path: string): Promise<boolean> {
    return this.data.has(path);
  }
  async isRegularFile(path: string): Promise<boolean> {
    return this.data.has(path);
  }
  async isPathInside(root: string, path: string): Promise<boolean> {
    return path.startsWith(`${root}/`);
  }
  async readTail(path: string, _maxBytes: number): Promise<SubAgentTranscriptTail> {
    return { text: this.data.get(path) ?? '', startsAfterFileStart: false };
  }
}

// ===== P1:wm_click 审批 vs wm_eval =====
const wmClick = makeAgentTool({
  name: 'wm_click',
  description: 'click',
  needsApproval: true,
  execute: async (): Promise<UIMessagePart[]> => [textPart('{}')],
});
const wmEval = makeAgentTool({
  name: 'wm_eval',
  description: 'eval',
  needsApproval: true,
  allowsAutoApproval: false,
  mandatoryApproval: true,
  execute: async (): Promise<UIMessagePart[]> => [textPart('{}')],
});

await probe('P1a wm_click policy: sensitive/needsApproval/autoApprovable=true/mandatory=false', async () => {
  const p = toolInvocationPolicy(wmClick, { target: '#a' });
  assert.equal(p.category, 'webmount', `category=${p.category}`);
  assert.equal(p.mutates, true);
  assert.equal(p.risk, 'sensitive');
  assert.equal(p.needsApproval, true);
  assert.equal(p.autoApprovable, true, 'wm_click must be auto-approvable for the bypass');
  assert.equal(p.mandatoryApproval, false);
  assert.equal(p.alwaysAsk, false);
  assert.equal(p.speculativeEligible, false, 'must not run speculatively');
});

await probe('P1b wm_click single auto-approve toggle => allow (bypasses manual)', async () => {
  const r = new PermissionDecisionResolver({ newId: () => 't' });
  const d = r.resolve(wmClick, toolPart('wm_click', '{"target":"#a"}', 'c1'),
    true, false, [], 'normal');
  assert.equal(d.action, 'allow', `action=${d.action} source=${d.source}`);
  assert.equal(d.source, 'settings');
});

await probe('P1c wm_click run-trust => allow after one approval in same run', async () => {
  const r = new PermissionDecisionResolver({ newId: () => 't' });
  const d = r.resolve(wmClick, toolPart('wm_click', '{"target":"#b"}', 'c2'),
    false, false, ['wm_click'], 'normal');
  assert.equal(d.action, 'allow', `action=${d.action} source=${d.source}`);
  assert.equal(d.source, 'run_trust');
});

await probe('P1d contrast wm_eval single toggle stays ask (mandatory+high)', async () => {
  const r = new PermissionDecisionResolver({ newId: () => 't' });
  const d = r.resolve(wmEval, toolPart('wm_eval', '{"code":"1"}', 'c3'),
    true, false, [], 'normal');
  assert.equal(d.action, 'ask', `action=${d.action} source=${d.source}`);
  const p = toolInvocationPolicy(wmEval, { code: '1' });
  assert.equal(p.risk, 'high');
  assert.equal(p.autoApprovable, false);
});

await probe('P1e wm_click double toggle => unattended allow (same as wm_eval)', async () => {
  const r = new PermissionDecisionResolver({ newId: () => 't' });
  const d = r.resolve(wmClick, toolPart('wm_click', '{"target":"#a"}', 'c4'),
    true, true, [], 'normal');
  assert.equal(d.action, 'allow');
  assert.equal(d.source, 'settings_unattended');
});

// ===== P8:ask_user 单开关不可自动批准 =====
await probe('P8 ask_user always asks even with single/both toggles', async () => {
  const ask = makeAgentTool({
    name: 'ask_user',
    description: 'ask',
    needsApproval: true,
    execute: async (): Promise<UIMessagePart[]> => [textPart('{}')],
  });
  const r = new PermissionDecisionResolver({ newId: () => 't' });
  const single = r.resolve(ask, toolPart('ask_user', '{"questions":[]}', 'c5'), true, false, [], 'normal');
  const both = r.resolve(ask, toolPart('ask_user', '{"questions":[]}', 'c6'), true, true, [], 'normal');
  assert.equal(single.action, 'ask', `single=${single.action}/${single.source}`);
  assert.equal(both.action, 'ask', `both=${both.action}/${both.source}`);
});

// ===== cron 装配辅助 =====
const makeCronManager = async (): Promise<{
  manager: AgentCronManager; kv: MemKv; scheduler: MemScheduler; files: MemFiles;
}> => {
  const kv = new MemKv();
  const scheduler = new MemScheduler();
  const files = new MemFiles();
  const store = await AgentTaskStore.create({
    taskDir: '/mem/tasks', appFilesDir: '/mem', files,
  });
  const manager = new AgentCronManager(kv, scheduler, store);
  return { manager, kv, scheduler, files };
};

// ===== P2:无运行锁 → 重复触发双重执行 =====
await probe('P2 cron has no run lock: second trigger prepares again while first active', async () => {
  const { manager } = await makeCronManager();
  const task = await manager.createTask('t', 'do side effect', '0 9 * * *', 'UTC', true);
  const first = await manager.prepareTriggeredRun(task.id, false);
  assert.ok(first !== null, 'first trigger prepared');
  await manager.markRunStarted(task.id);
  // 模拟第二次触发(entry runNow 在 runExecutor 进行中不检查在跑)
  const second = await manager.prepareTriggeredRun(task.id, false);
  assert.ok(second !== null, 'second trigger ALSO prepared => overlapping execution');
  assert.equal(second?.runCount, 2, `runCount=${second?.runCount}`);
  const snap = await manager.listTasks();
  assert.equal(snap[0].lastStatus, 'Queued', 'status reset to Queued while first still Running');
});

await probe('P2b concurrent triggers lose runCount update (non-atomic replaceTasks)', async () => {
  const { manager } = await makeCronManager();
  const task = await manager.createTask('t', 'p', '0 9 * * *', 'UTC', true);
  const [a, b] = await Promise.all([
    manager.prepareTriggeredRun(task.id, false),
    manager.prepareTriggeredRun(task.id, false),
  ]);
  assert.ok(a !== null && b !== null, 'both concurrent triggers prepared (2 executions)');
  const snap = await manager.listTasks();
  // 两次运行但计数只 +1 = lost update
  assert.equal(snap[0].runCount, 1, `runCount=${snap[0].runCount} (expected lost update=1)`);
});

// ===== P3:触发路径不校验 due =====
await probe('P3 prepareTriggeredRun ignores nextRunAtMs (runs far-future task)', async () => {
  const { manager } = await makeCronManager();
  // 1 月 1 日:从当年 9 月看下一次在 ~110 天后
  const task = await manager.createTask('yearly', 'p', '0 0 1 1 *', 'UTC', true);
  assert.ok(task.nextRunAtMs !== null && task.nextRunAtMs > Date.now() + 40 * 24 * 3600 * 1000,
    `nextRunAtMs=${task.nextRunAtMs}`);
  const prepared = await manager.prepareTriggeredRun(task.id, false);
  assert.ok(prepared !== null, 'far-future task still "prepared" without due check');
  assert.equal(prepared?.runCount, 1);
});

// ===== P4:删除后触发被 executor 兜住(反证) =====
await probe('P4 deleted task cannot be triggered (prepare returns null)', async () => {
  const { manager } = await makeCronManager();
  const task = await manager.createTask('t', 'p', '0 9 * * *', 'UTC', true);
  const removed = await manager.deleteTask(task.id);
  assert.equal(removed, true);
  const prepared = await manager.prepareTriggeredRun(task.id, false);
  assert.equal(prepared, null, 'deleted task must not prepare');
});

// ===== P5:cron 重启恢复 =====
const cronSnapshot = (enabled: boolean): AgentTaskSnapshot => ({
  schemaVersion: 2, taskId: 'cron-1', type: 'cron', title: 't', spec: { enabled },
  runtime: null, queueState: 'QUEUED', recoveryState: 'ACTIVE',
  retryPolicy: {
    retryable: false, requiresApproval: true, maxRetries: 0, retryCount: 0, reason: null,
  },
  outputRef: null, sourceToolName: 'cron_task_create', sourceConversationId: 'c',
  status: 'RUNNING', outputPath: null, outputOffset: 0, createdAtMs: 1, updatedAtMs: 1,
  lastHeartbeatMs: null, notified: false, cancelCapability: true, permissionTraceId: null,
  summary: null, lastErrorCode: null, error: null,
});

await probe('P5a disabled cron restart => CANCELLED (not revived)', async () => {
  const rm = new AgentTaskRecoveryManager(async () => false);
  const out = await rm.recoverOnStartup(cronSnapshot(false), 100);
  assert.equal(out.status, 'CANCELLED', `status=${out.status}`);
  assert.equal(out.queueState, 'TERMINAL');
  assert.equal(out.recoveryState, 'CLEANUP_ONLY');
});

await probe('P5b enabled cron restart => SCHEDULED (intended)', async () => {
  const rm = new AgentTaskRecoveryManager(async () => false);
  const out = await rm.recoverOnStartup(cronSnapshot(true), 100);
  assert.equal(out.status, 'QUEUED', `status=${out.status}`);
  assert.equal(out.queueState, 'SCHEDULED');
  assert.equal(out.recoveryState, 'SCHEDULED');
});

// ===== subagent 装配辅助 =====
interface RunnerProbe {
  runner: SubAgentRunner;
  startedCount: () => number;
}

const makeSubAgentHarness = async (
  runnerImpl: (signal?: { aborted: boolean }) => Promise<SubAgentResult>,
  timeoutMsOverride: number | null = null,
): Promise<{
  manager: SubAgentManager; taskStore: AgentTaskStore; runner: RunnerProbe;
  setting: SubAgentRuntimeSetting;
}> => {
  const setting = makeSubAgentRuntimeSetting();
  setting.enabled = true;
  setting.maxConcurrentRuns = 2;
  setting.timeoutMs = 600000;
  setting.maxTurns = 8;
  setting.outputBudgetChars = 20000;
  if (timeoutMsOverride !== null) {
    const o = makeSubAgentOverride();
    o.timeoutMsOverride = timeoutMsOverride;
    setting.overrides.set('explorer', o);
  }
  const files = new MemFiles();
  const taskStore = await AgentTaskStore.create({
    taskDir: '/mem/tasks', appFilesDir: '/mem', files,
  });
  let started = 0;
  const runner: SubAgentRunner = {
    async run(
      _def: SubAgentDefinition, _task: SubAgentTaskSpec, _tools: AgentTool[],
      _liveText: (t: string) => void, _liveParts: (p: UIMessagePart[]) => void,
      signal?: { aborted: boolean; addEventListener?: unknown },
    ): Promise<SubAgentResult> {
      started++;
      return runnerImpl(signal);
    },
  };
  const deps: SubAgentManagerDeps = {
    getSubAgentSetting: () => setting,
    runner,
    agentTaskStore: taskStore,
    sessionAccessGrantStore: new SessionAccessGrantStore(),
    transcript: new MemTranscript(),
    newId: (() => { let n = 0; return (): string => `run-${++n}`; })(),
    now: () => Date.now(),
    sleep: (ms: number) => new Promise<void>((r) => { setTimeout(r, ms); }),
    transcriptPathForRun: (runId: string) => `/mem/subagents/${runId}.jsonl`,
  };
  return {
    manager: new SubAgentManager(deps),
    taskStore,
    runner: { runner, startedCount: () => started },
    setting,
  };
};

const explorerParentTools = (): AgentTool[] => [
  makeAgentTool({
    name: 'tools_list',
    description: 'list',
    execute: async (): Promise<UIMessagePart[]> => [textPart('{}')],
  }),
  makeAgentTool({
    name: 'search_web',
    description: 'search',
    execute: async (): Promise<UIMessagePart[]> => [textPart('{}')],
  }),
];

const explorerInput = (): JsonObject => ({
  subagent_id: 'explorer',
  task: {
    objective: 'probe approval stall',
    output_format: 'summary',
    tools_and_sources: 'tools_list',
    boundaries: 'read only',
  },
});

// ===== P6:approval_required → 永久 RUNNING =====
await probe('P6 approval_required maps to task RUNNING and cannot be cancelled', async () => {
  const h = await makeSubAgentHarness(async (_signal) =>
    makeSubAgentResult({ status: 'approval_required', summary: 'need approval for screen_x' }));
  const payload = await h.manager.start('conv-1', explorerInput(), explorerParentTools());
  const runId = payload['run_id'] as string;
  // 等 launchRun 完成
  await new Promise<void>((r) => { setTimeout(r, 50); });
  const run: SubAgentRun | null = h.manager.snapshot(runId);
  assert.ok(run !== null);
  assert.equal(run?.status, 'approval_required', `run status=${run?.status}`);
  const task = h.taskStore.read(runId);
  assert.ok(task !== null);
  assert.equal(task?.status, 'RUNNING', `task status=${task?.status} (stuck)`);
  assert.equal(task?.cancelCapability, false, 'cancel capability disabled');
  // cancel 尝试:manager.finish 只对 running 生效 → 无操作
  await h.manager.cancel(runId);
  const after: SubAgentRun | null = h.manager.snapshot(runId);
  assert.equal(after?.status, 'approval_required', `after cancel status=${after?.status}`);
  const taskAfter = h.taskStore.read(runId);
  assert.equal(taskAfter?.status, 'RUNNING', `task after cancel=${taskAfter?.status}`);
  // 无任何恢复通道:等待也不会变
  const waited = await h.manager.wait(runId, 0);
  assert.equal(waited['status'], 'approval_required');
});

// ===== P7:超时/取消收口 =====
await probe('P7a timeout => timed_out terminal + task TIMED_OUT', async () => {
  const h = await makeSubAgentHarness(
    (_signal) => new Promise<SubAgentResult>(() => { /* never resolves */ }),
    1000);
  const payload = await h.manager.start('conv-1', explorerInput(), explorerParentTools());
  const runId = payload['run_id'] as string;
  await new Promise<void>((r) => { setTimeout(r, 1400); });
  const run = h.manager.snapshot(runId);
  assert.equal(run?.status, 'timed_out', `run status=${run?.status}`);
  const task = h.taskStore.read(runId);
  assert.equal(task?.status, 'TIMED_OUT', `task status=${task?.status}`);
});

await probe('P7b user cancel => cancelled terminal (runner aborted, not restarted)', async () => {
  const h = await makeSubAgentHarness((signal) => new Promise<SubAgentResult>((_resolve, reject) => {
    const t = setInterval(() => {
      if (signal !== undefined && signal.aborted) {
        clearInterval(t);
        const e = new Error('aborted');
        e.name = 'AbortError';
        reject(e);
      }
    }, 10);
  }));
  const payload = await h.manager.start('conv-1', explorerInput(), explorerParentTools());
  const runId = payload['run_id'] as string;
  await new Promise<void>((r) => { setTimeout(r, 30); });
  await h.manager.cancel(runId);
  await new Promise<void>((r) => { setTimeout(r, 60); });
  const run = h.manager.snapshot(runId);
  assert.equal(run?.status, 'cancelled', `run status=${run?.status}`);
  const task = h.taskStore.read(runId);
  assert.equal(task?.status, 'CANCELLED', `task status=${task?.status}`);
  assert.equal(h.runner.startedCount(), 1, 'runner must run exactly once');
});

console.log(`\n=== probe summary: pass=${passCount} fail=${failCount} ===`);
if (failCount > 0) process.exitCode = 1;
