// probe-d-independent.ts — 独立复核 D 的 cron / subagent 候选（真实 .ts 逻辑 + 内存假端口）
// 运行：cd harmony/chat && npx tsx <此文件绝对路径>
// 约束：不联网/模型/文件副作用；合成工具 execute 只返回常量。
//
// 覆盖：
//   D03  entry cron 执行体静态调用链（runChatTurn 无工具）
//   D04  approval_required 经“真实 tool_loop + 真实 runner”可达，且 manager 将其永久 RUNNING、cancel 无效
//   D02  cron 无运行锁：真实 AgentCronManager 重复触发双重执行
//   D06  history grant 由模型入参 source_session_ids 自动铸造且 session_read 预批准（有界）
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import type { UIMessage, UIMessagePart, MessageChunk, UIMessagePartTool } from '../../../../harmony/chat/src/main/ets/chat/message.ts';
import { makeAssistantMessage, makeUserMessage } from '../../../../harmony/chat/src/main/ets/chat/message.ts';
import type { Assistant } from '../../../../harmony/chat/src/main/ets/chat/assistant.ts';
import { makeAssistant } from '../../../../harmony/chat/src/main/ets/chat/assistant.ts';
import type { AgentTool } from '../../../../harmony/chat/src/main/ets/chat/tool.ts';
import { makeAgentTool } from '../../../../harmony/chat/src/main/ets/chat/tool.ts';
import type { JsonObject, JsonValue } from '../../../../harmony/chat/src/main/ets/chat/json.ts';
import type { ProviderModel } from '../../../../harmony/chat/src/main/ets/chat/provider_settings.ts';
import { makeProviderModel } from '../../../../harmony/chat/src/main/ets/chat/provider_settings.ts';
import type { GenerationRetrySetting } from '../../../../harmony/chat/src/main/ets/chat/generation_retry.ts';
import { makeGenerationRetrySetting } from '../../../../harmony/chat/src/main/ets/chat/generation_retry.ts';
import {
  makeSubAgentDefinition, makeSubAgentRuntimeSetting,
} from '../../../../harmony/chat/src/main/ets/chat/agent_prompt_config.ts';
import type { SubAgentRuntimeSetting, SubAgentDefinition } from '../../../../harmony/chat/src/main/ets/chat/agent_prompt_config.ts';
import {
  ChatStreamSubAgentGenerationPort, GenerationSubAgentRunner,
} from '../../../../harmony/chat/src/main/ets/chat/subagent_runner.ts';
import type { SubAgentAssistantResolution } from '../../../../harmony/chat/src/main/ets/chat/subagent_runner.ts';
import type { ChatStreamProvider } from '../../../../harmony/chat/src/main/ets/chat/chat_turn.ts';
import { SubAgentManager } from '../../../../harmony/chat/src/main/ets/chat/subagent_manager.ts';
import type { SubAgentManagerDeps } from '../../../../harmony/chat/src/main/ets/chat/subagent_manager.ts';
import { AgentTaskStore } from '../../../../harmony/chat/src/main/ets/chat/agent_task.ts';
import type { AgentTaskFilePort, AgentTaskSnapshot } from '../../../../harmony/chat/src/main/ets/chat/agent_task.ts';
import { SessionAccessGrantStore } from '../../../../harmony/chat/src/main/ets/chat/session_grant_store.ts';
import type { SubAgentTranscriptPort, SubAgentTranscriptTail } from '../../../../harmony/chat/src/main/ets/chat/subagent_transcript.ts';
import type { SubAgentRun } from '../../../../harmony/chat/src/main/ets/chat/subagent_models.ts';
import { AgentCronManager } from '../../../../harmony/chat/src/main/ets/chat/agent_cron.ts';
import type { AgentCronPersistencePort, AgentCronSchedulerPort, AgentCronTask } from '../../../../harmony/chat/src/main/ets/chat/agent_cron.ts';

const __dir = dirname(fileURLToPath(import.meta.url));
let pass = 0, fail = 0;
const check = (name: string, ok: boolean, detail: string = ''): void => {
  if (ok) { pass++; console.log(`PASS  ${name}`); }
  else { fail++; console.log(`FAIL  ${name} :: ${detail}`); }
};

// ===== 内存端口 =====
class MemKv implements AgentCronPersistencePort {
  raw: string | null = null;
  async read() { return this.raw; }
  async write(raw: string) { this.raw = raw; }
}
class MemScheduler implements AgentCronSchedulerPort { scheduled: string[] = []; runNows: string[] = []; cancels: string[] = []; schedule(w: string) { this.scheduled.push(w); } runNow(w: string) { this.runNows.push(w); } cancel(w: string) { this.cancels.push(w); } }
class MemFiles implements AgentTaskFilePort {
  files = new Map<string, string>();
  async mkdirs() {}
  async listJsonFileNames() { return []; }
  async readText(p: string) { return this.files.get(p) ?? null; }
  async writeText(p: string, t: string) { this.files.set(p, t); }
  async delete(p: string) { this.files.delete(p); }
  async exists(p: string) { return this.files.has(p); }
  async isPathInside(root: string, p: string) { return p.startsWith(`${root}/`); }
}
class MemTranscript implements SubAgentTranscriptPort {
  data = new Map<string, string>();
  async canonicalPath(p: string) { return p; }
  async appendText(p: string, t: string) { this.data.set(p, (this.data.get(p) ?? '') + t); }
  async exists(p: string) { return this.data.has(p); }
  async isRegularFile(p: string) { return this.data.has(p); }
  async isPathInside(root: string, p: string) { return p.startsWith(`${root}/`); }
  async readTail(p: string): Promise<SubAgentTranscriptTail> { return { text: this.data.get(p) ?? '', startsAfterFileStart: false }; }
}

// ===== 假 provider（脚本化 chunk）=====
const textChunk = (text: string): MessageChunk => ({
  id: 'c', model: 'm',
  choices: [{ index: 0, delta: { id: 'd', role: 'assistant', parts: [{ type: 'text', text, metadata: null }], annotations: [], createdAt: '2026-09-13T00:00:00Z', finishedAt: null, modelId: null, usage: null, translation: null }, message: null, finishReason: 'unknown' }],
  usage: null,
});
const toolChunk = (callId: string, name: string, input: string): MessageChunk => ({
  id: 'c', model: 'm',
  choices: [{ index: 0, delta: { id: 'd', role: 'assistant', parts: [{ type: 'tool', toolCallId: callId, toolName: name, input, output: [], approvalState: { type: 'auto' }, metadata: null }], annotations: [], createdAt: '2026-09-13T00:00:00Z', finishedAt: null, modelId: null, usage: null, translation: null }, message: null, finishReason: 'unknown' }],
  usage: null,
});
interface Scripted { streamText(m: UIMessage[], cb: (c: MessageChunk) => void): Promise<void>; calls: number; }
const scriptedProvider = (groups: MessageChunk[][]): Scripted => {
  const s = { calls: 0 } as Scripted;
  s.streamText = (_m, cb) => { const g = groups[s.calls] ?? [textChunk('done')]; s.calls++; for (const c of g) cb(c); return Promise.resolve(); };
  return s;
};

const syntheticModel = (): ProviderModel => makeProviderModel({ id: 'probe-model', modelId: 'probe-api', displayName: 'probe' });
const assistant = (): Assistant => makeAssistant({});
const retry = (): GenerationRetrySetting => makeGenerationRetrySetting({ enabled: false, maxRetries: 0, initialDelayMs: 1, maxDelayMs: 1, jitterRatio: 0 });

// 合成“敏感类目”工具：screen 类目 → subagent 上下文需审批；execute 只返回常量，零副作用
const screenTool = (): AgentTool => makeAgentTool({
  name: 'screen_click',
  description: 'synthetic sensitive tool (no side effect)',
  execute: async (): Promise<UIMessagePart[]> => [{ type: 'text', text: '{"clicked":true}', metadata: null }],
});

const makePort = (autoApproveTools: boolean, groups: MessageChunk[][]) => {
  const resolution: SubAgentAssistantResolution = { assistant: assistant(), generationRetry: retry(), autoApproveTools, autoApproveHighRiskTools: false };
  return new ChatStreamSubAgentGenerationPort({
    resolveModel: async () => syntheticModel(),
    resolveAssistant: async () => resolution,
    makeProvider: () => scriptedProvider(groups) as unknown as ChatStreamProvider,
  });
};

const customDef = (allow: string[]): SubAgentDefinition => makeSubAgentDefinition({
  id: 'probe_agent', name: 'ProbeAgent', description: 'probe',
  systemPrompt: 'probe', toolAllowlist: allow, dynamic: false, maxTurns: 3,
});

const makeManager = async (autoApproveTools: boolean, groups: MessageChunk[][]) => {
  const setting: SubAgentRuntimeSetting = makeSubAgentRuntimeSetting();
  setting.enabled = true;
  setting.mode = 'roster';
  setting.maxConcurrentRuns = 2;
  setting.timeoutMs = 60000;
  setting.maxTurns = 3;
  setting.outputBudgetChars = 20000;
  setting.customDefinitions = [customDef(['screen_click'])];
  const files = new MemFiles();
  const taskStore = await AgentTaskStore.create({ taskDir: '/mem/tasks', appFilesDir: '/mem', files });
  const runner = new GenerationSubAgentRunner(makePort(autoApproveTools, groups));
  let n = 0;
  const deps: SubAgentManagerDeps = {
    getSubAgentSetting: () => setting,
    runner,
    agentTaskStore: taskStore,
    sessionAccessGrantStore: new SessionAccessGrantStore(),
    transcript: new MemTranscript(),
    newId: () => `run-${++n}`,
    now: () => Date.now(),
    sleep: (ms: number) => new Promise<void>((r) => { setTimeout(r, ms); }),
    transcriptPathForRun: (runId: string) => `/mem/subagents/${runId}.jsonl`,
  };
  return { manager: new SubAgentManager(deps), taskStore };
};

const sleep = (ms: number) => new Promise<void>((r) => { setTimeout(r, ms); });

(async () => {
  // ---------- D03：entry cron 执行体无工具 ----------
  {
    const entry = readFileSync(resolve(__dir, '../../../../harmony/entry/src/main/ets/di/AppContainer.ets'), 'utf8');
    const fnStart = entry.indexOf('const createEntryCronRunExecutor');
    const fn = entry.slice(fnStart, entry.indexOf('export const getAgentCronManager', fnStart));
    check('D03a entry cron executor calls runChatTurn (single-shot, no tools)', /runChatTurn\(/.test(fn) && !/runChatTurnWithTools/.test(fn), 'not runChatTurn');
    check('D03b entry cron executor never builds a ToolLoopOptions', !/ToolLoopOptions|tools:/.test(fn), 'toolLoop present');
    const chatTurn = readFileSync(resolve(__dir, '../../../../harmony/chat/src/main/ets/chat/chat_turn.ts'), 'utf8');
    const body = chatTurn.slice(chatTurn.indexOf('export const runChatTurn'), chatTurn.indexOf('export const runChatTurnWithTools'));
    check('D03c runChatTurn has no tools/loop parameter', !/ToolLoopOptions/.test(body), 'loop leaked');
    // cron 执行器工具端口存在但 entry 未用
    const execFile = readFileSync(resolve(__dir, '../../../../harmony/chat/src/main/ets/chat/agent_cron_run_executor.ts'), 'utf8');
    check('D03d runCronTaskOnce (tool-capable Port impl) exists but entry never imports it', /runCronTaskOnce/.test(execFile) && !/runCronTaskOnce/.test(entry), 'entry uses it');
  }

  // ---------- D04：approval_required 真实可达 ----------
  {
    const groups: MessageChunk[][] = [[toolChunk('c1', 'screen_click', '{}')], [textChunk('final answer')]];
    const h = await makeManager(false, groups);
    const payload = await h.manager.start('conv-1', {
      subagent_id: 'probe_agent',
      task: { objective: 'probe approval reachability' },
    } as JsonObject, [screenTool()]);
    const runId = payload['run_id'] as string;
    check('D04a manager.start accepted synthetic sensitive-tool subagent', typeof runId === 'string' && runId.length > 0, JSON.stringify(payload));
    await sleep(150);
    const run: SubAgentRun | null = h.manager.snapshot(runId);
    check('D04b REAL runner+loop yields status=approval_required (reachable)', run?.status === 'approval_required', `status=${run?.status}`);
    const task = h.taskStore.read(runId);
    check('D04c task mapped to RUNNING and cancelCapability=false', task?.status === 'RUNNING' && task?.cancelCapability === false, `task=${task?.status} cancel=${task?.cancelCapability}`);
    await h.manager.cancel(runId);
    const after = h.manager.snapshot(runId);
    const taskAfter = h.taskStore.read(runId);
    check('D04d cancel() is a NO-OP on approval_required (still stuck)', after?.status === 'approval_required' && taskAfter?.status === 'RUNNING', `run=${after?.status} task=${taskAfter?.status}`);
    const waited = await h.manager.wait(runId, 0);
    check('D04e wait() provides no recovery channel', waited['status'] === 'approval_required', JSON.stringify(waited['status']));
  }

  // 对照 A：只读普通工具（无审批门）在同一 runner 下正常完成 → 证明 approval_required 来自审批门。
  {
    const readTool: AgentTool = makeAgentTool({
      name: 'probe_read',
      description: 'synthetic read-only tool',
      execute: async (): Promise<UIMessagePart[]> => [{ type: 'text', text: '{"ok":true}', metadata: null }],
    });
    const groups: MessageChunk[][] = [[toolChunk('c1', 'probe_read', '{}')], [textChunk('final answer')]];
    const setting: SubAgentRuntimeSetting = makeSubAgentRuntimeSetting();
    setting.enabled = true; setting.mode = 'roster'; setting.maxConcurrentRuns = 2;
    setting.timeoutMs = 60000; setting.maxTurns = 3; setting.outputBudgetChars = 20000;
    setting.customDefinitions = [customDef(['probe_read'])];
    const files = new MemFiles();
    const taskStore = await AgentTaskStore.create({ taskDir: '/mem/tasks', appFilesDir: '/mem', files });
    const resolution: SubAgentAssistantResolution = { assistant: assistant(), generationRetry: retry(), autoApproveTools: false, autoApproveHighRiskTools: false };
    const port = new ChatStreamSubAgentGenerationPort({
      resolveModel: async () => syntheticModel(),
      resolveAssistant: async () => resolution,
      makeProvider: () => scriptedProvider(groups) as unknown as ChatStreamProvider,
    });
    let n = 0;
    const manager = new SubAgentManager({
      getSubAgentSetting: () => setting,
      runner: new GenerationSubAgentRunner(port),
      agentTaskStore: taskStore,
      sessionAccessGrantStore: new SessionAccessGrantStore(),
      transcript: new MemTranscript(),
      newId: () => `run-${++n}`,
      now: () => Date.now(),
      sleep: (ms: number) => new Promise<void>((r) => { setTimeout(r, ms); }),
      transcriptPathForRun: (runId: string) => `/mem/subagents/${runId}.jsonl`,
    });
    const payload = await manager.start('conv-2', {
      subagent_id: 'probe_agent', task: { objective: 'probe read-only' },
    } as JsonObject, [readTool]);
    const runId = payload['run_id'] as string;
    await sleep(150);
    const run = manager.snapshot(runId);
    check('D04f contrast: read-only tool completes (not approval_required) => trigger is the approval gate',
      run?.status !== 'approval_required', `status=${run?.status}`);
  }

  // 对照 B：subagent 上下文中，即便单开 autoApproveAllToolCalls，敏感类目仍恒 ask（Android 同构检查点）
  {
    const groups: MessageChunk[][] = [[toolChunk('c1', 'screen_click', '{}')], [textChunk('final answer')]];
    const h = await makeManager(true, groups);
    const payload = await h.manager.start('conv-3', {
      subagent_id: 'probe_agent', task: { objective: 'probe single toggle subagent' },
    } as JsonObject, [screenTool()]);
    const runId = payload['run_id'] as string;
    await sleep(150);
    const run = h.manager.snapshot(runId);
    check('D04g subagent sensitive tool asks even with autoApproveAllToolCalls=true (default reachable)',
      run?.status === 'approval_required', `status=${run?.status}`);
  }

  // ---------- D02：cron 无运行锁 ----------
  {
    const kv = new MemKv();
    const scheduler = new MemScheduler();
    const files = new MemFiles();
    const store = await AgentTaskStore.create({ taskDir: '/mem/tasks', appFilesDir: '/mem', files });
    const manager = new AgentCronManager(kv, scheduler, store);
    const task: AgentCronTask = await manager.createTask('t', 'side effect', '0 9 * * *', 'UTC', true);
    const first = await manager.prepareTriggeredRun(task.id, false);
    await manager.markRunStarted(task.id);
    const second = await manager.prepareTriggeredRun(task.id, false);
    check('D02a second trigger while first Running is NOT blocked', first !== null && second !== null, `first=${first !== null} second=${second !== null}`);
    check('D02b runCount double-increments (2 execs staged)', second?.runCount === 2, `runCount=${second?.runCount}`);
    const snap = await manager.listTasks();
    check('D02c status regressed to Queued while prior run still Running', snap[0].lastStatus === 'Queued', `lastStatus=${snap[0].lastStatus}`);
    const [a, b] = await Promise.all([manager.prepareTriggeredRun(task.id, false), manager.prepareTriggeredRun(task.id, false)]);
    const snap2 = await manager.listTasks();
    check('D02d concurrent prepare => lost update (2 runs, runCount only +1)', a !== null && b !== null && snap2[0].runCount === 3, `runCount=${snap2[0].runCount}`);
  }

  // ---------- D06：history grant 由模型入参自动铸造 ----------
  {
    const setting: SubAgentRuntimeSetting = makeSubAgentRuntimeSetting();
    setting.enabled = true;
    setting.mode = 'roster';
    setting.customDefinitions = [customDef(['session_read'])];
    const files = new MemFiles();
    const taskStore = await AgentTaskStore.create({ taskDir: '/mem/tasks', appFilesDir: '/mem', files });
    const grantStore = new SessionAccessGrantStore();
    const runner = { run: async (): Promise<any> => ({ status: 'completed', summary: 's', findings: [], risks: [], recommendedNextSteps: [], artifacts: [], error: '' }) };
    const deps: SubAgentManagerDeps = {
      getSubAgentSetting: () => setting,
      runner: runner as never,
      agentTaskStore: taskStore,
      sessionAccessGrantStore: grantStore,
      transcript: new MemTranscript(),
      newId: (() => { let n = 0; return () => `run-${++n}`; })(),
      now: () => Date.now(),
      sleep: (ms: number) => new Promise<void>((r) => { setTimeout(r, ms); }),
      transcriptPathForRun: (runId: string) => `/mem/subagents/${runId}.jsonl`,
    };
    const manager = new SubAgentManager(deps);
    const grantsBefore = (grantStore as unknown as { grants: Map<string, unknown> }).grants.size;
    const payload = await manager.start('conv-9', {
      subagent_id: 'probe_agent',
      task: { objective: 'read arbitrary history', source_session_ids: ['sess-user-1', 'sess-user-2'] },
    } as JsonObject, [makeAgentTool({ name: 'session_read', description: 'r', needsApproval: true, execute: async () => [{ type: 'text', text: '{}', metadata: null }] })]);
    await sleep(50);
    const grantsAfter = (grantStore as unknown as { grants: Map<string, unknown> }).grants.size;
    check('D06a minter auto-creates a grant from model-supplied source_session_ids (no user prompt)',
      grantsAfter === grantsBefore + 1, `before=${grantsBefore} after=${grantsAfter} payload=${JSON.stringify(payload['status'])}`);
    const grant = [...(grantStore as unknown as { grants: Map<string, any> }).grants.values()][0];
    check('D06b grant scope == model-supplied ids', grant !== undefined && grant.sessionIds.join(',') === 'sess-user-1,sess-user-2', `scope=${grant?.sessionIds}`);
    check('D06c grant is bounded (TTL<=30min, chars<=120k, sessions<=24)',
      grant !== undefined && grant.maxChars <= 120000 && grant.maxSessions <= 24
        && grant.expiresAt - Date.now() <= 30 * 60_000 + 1000,
      JSON.stringify({ maxChars: grant?.maxChars, maxSessions: grant?.maxSessions, ttlMs: grant !== undefined ? grant.expiresAt - Date.now() : null }));
  }

  console.log(`\n=== probe-d-independent summary: pass=${pass} fail=${fail} ===`);
  if (fail > 0) process.exitCode = 1;
})().catch((e) => { console.error(e); process.exitCode = 1; });
