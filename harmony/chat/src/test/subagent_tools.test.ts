// subagent_tools.test.ts — D-132c Task 8 五件公共 SubAgentTools
// Android 基准: feature/tools/impl/src/main/kotlin/app/amber/feature/tools/SubAgentTools.kt(全文 300 行)
//   + app/src/test/.../subagent/SubAgentPayloadTest.kt(payload 默认省略 display_text)
// 五件顺序: subagent_list / subagent_start / subagent_read / subagent_wait / subagent_cancel
//   pin schema/required、wait 默认 10000(由 manager 0..60000 clamp)、roster vs smart 提示、
//   mention 强制委派、self-loop guard、manager delegation、默认省略 display_text。
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type {
  SubAgentDefinition, SubAgentMode, SubAgentOverride, SubAgentRuntimeSetting,
} from '../main/ets/chat/agent_prompt_config.ts';
import { makeSubAgentDefinition } from '../main/ets/chat/agent_prompt_config.ts';
import type {
  AgentTaskFilePort, AgentTaskSnapshot, AgentTaskStoreDeps,
} from '../main/ets/chat/agent_task.ts';
import { AgentTaskStore } from '../main/ets/chat/agent_task.ts';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';
import { makeUserMessage } from '../main/ets/chat/message.ts';
import { makeChatModel } from '../main/ets/chat/provider_model.ts';
import { SessionAccessGrantStore } from '../main/ets/chat/session_grant_store.ts';
import type {
  SubAgentResult, SubAgentTaskSpec,
} from '../main/ets/chat/subagent_models.ts';
import { makeSubAgentResult } from '../main/ets/chat/subagent_models.ts';
import type { SubAgentTranscriptPort, SubAgentTranscriptTail } from '../main/ets/chat/subagent_transcript.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import { makeAgentTool, makeInputSchemaObj } from '../main/ets/chat/tool.ts';
import type { SubAgentManagerDeps, SubAgentRunner } from '../main/ets/index.ts';
import { SubAgentManager } from '../main/ets/index.ts';
import { SubAgentTools } from '../main/ets/chat/subagent_tools.ts';

// ===== harness(与 subagent_manager.test.ts 同构的最小真 manager + fake runner) =====

interface RunnerCall {
  definition: SubAgentDefinition;
  taskObjective: string;
  sessionGrantId: string;
  tools: AgentTool[];
  liveText: (text: string) => void;
  liveParts: (parts: UIMessagePart[]) => void;
  signal: AbortSignalLike | undefined;
}
type RunnerScript = (call: RunnerCall) => Promise<SubAgentResult>;

interface SettingOptions {
  enabled?: boolean;
  mode?: SubAgentMode;
  allowDynamicSubAgents?: boolean;
  maxConcurrentRuns?: number;
  timeoutMs?: number;
  maxTurns?: number;
  outputBudgetChars?: number;
  overrides?: Map<string, SubAgentOverride>;
  customDefinitions?: SubAgentDefinition[];
}

const makeSetting = (options: SettingOptions = {}): SubAgentRuntimeSetting => ({
  enabled: options.enabled ?? true,
  mode: options.mode ?? 'roster',
  allowDynamicSubAgents: options.allowDynamicSubAgents ?? true,
  maxConcurrentRuns: options.maxConcurrentRuns ?? 2,
  timeoutMs: options.timeoutMs ?? 60000,
  maxTurns: options.maxTurns ?? 4,
  outputBudgetChars: options.outputBudgetChars ?? 12000,
  overrides: options.overrides ?? new Map<string, SubAgentOverride>(),
  customDefinitions: options.customDefinitions ?? [],
});

class FakeSettings {
  setting: SubAgentRuntimeSetting;
  calls: number = 0;
  constructor(setting: SubAgentRuntimeSetting) { this.setting = setting; }
  getSubAgentSetting(): SubAgentRuntimeSetting { this.calls++; return this.setting; }
}

class FakeRunner implements SubAgentRunner {
  readonly calls: RunnerCall[] = [];
  readonly scripts: RunnerScript[] = [];
  run(
    definition: SubAgentDefinition,
    task: SubAgentTaskSpec,
    tools: AgentTool[],
    liveText: (text: string) => void,
    liveParts: (parts: UIMessagePart[]) => void,
    signal?: AbortSignalLike,
  ): Promise<SubAgentResult> {
    const call: RunnerCall = {
      definition, taskObjective: task.objective, sessionGrantId: task.sessionGrantId,
      tools, liveText, liveParts, signal,
    };
    this.calls.push(call);
    const script: RunnerScript | undefined = this.scripts[this.calls.length - 1];
    if (script !== undefined) return script(call);
    return Promise.resolve(makeSubAgentResult({ status: 'completed', summary: 'done' }));
  }
}

class MemoryFiles implements AgentTaskFilePort, SubAgentTranscriptPort {
  readonly files: Map<string, string> = new Map<string, string>();
  mkdirs(_dir: string): Promise<void> { return Promise.resolve(); }
  listJsonFileNames(dir: string): Promise<string[]> {
    const names: string[] = [];
    this.files.forEach((_text: string, path: string): void => {
      if (path.startsWith(`${dir}/`) && path.endsWith('.json') &&
        path.indexOf('/', dir.length + 1) < 0) {
        names.push(path.substring(dir.length + 1));
      }
    });
    return Promise.resolve(names.sort());
  }
  readText(path: string): Promise<string | null> {
    return Promise.resolve(this.files.get(path) ?? null);
  }
  writeText(path: string, text: string): Promise<void> { this.files.set(path, text); return Promise.resolve(); }
  delete(path: string): Promise<void> { this.files.delete(path); return Promise.resolve(); }
  exists(path: string): Promise<boolean> { return Promise.resolve(this.files.has(path)); }
  isPathInside(root: string, path: string): Promise<boolean> {
    return Promise.resolve(path.startsWith(`${root}/`));
  }
  appendText(path: string, text: string): Promise<void> {
    this.files.set(path, (this.files.get(path) ?? '') + text);
    return Promise.resolve();
  }
  isRegularFile(path: string): Promise<boolean> { return Promise.resolve(this.files.has(path)); }
  readTail(path: string, _maxBytes: number): Promise<SubAgentTranscriptTail> {
    return Promise.resolve({ text: this.files.get(path) ?? '', startsAfterFileStart: false });
  }
  canonicalPath(path: string): Promise<string | null> { return Promise.resolve(path); }
}

interface Harness {
  manager: SubAgentManager;
  settings: FakeSettings;
  runner: FakeRunner;
  files: MemoryFiles;
  nowState: { value: number };
  sleeps: number[];
}

const createManager = async (
  setting: SubAgentRuntimeSetting = makeSetting(),
  configure?: (runner: FakeRunner, files: MemoryFiles) => void,
): Promise<Harness> => {
  const settings = new FakeSettings(setting);
  const runner = new FakeRunner();
  const files = new MemoryFiles();
  if (configure !== undefined) configure(runner, files);
  const taskStore: AgentTaskStore = await AgentTaskStore.create({
    taskDir: '/files/amberagent/tasks', appFilesDir: '/files', files,
  } as AgentTaskStoreDeps);
  let grantId: number = 0;
  const grants = new SessionAccessGrantStore({
    now: (): number => 1000, idGen: (): string => `grant-${++grantId}`,
  });
  let runId: number = 0;
  const nowState: { value: number } = { value: 10000 };
  const sleeps: number[] = [];
  const deps: SubAgentManagerDeps = {
    getSubAgentSetting: (): SubAgentRuntimeSetting => settings.getSubAgentSetting(),
    runner,
    agentTaskStore: taskStore,
    sessionAccessGrantStore: grants,
    transcript: files,
    newId: (): string => `run-${++runId}`,
    now: (): number => nowState.value,
    sleep: (ms: number): Promise<void> => { sleeps.push(ms); nowState.value += ms; return Promise.resolve(); },
    transcriptPathForRun: (id: string): string => `/files/amberagent/subagents/runs/${id}.jsonl`,
  };
  return { manager: new SubAgentManager(deps), settings, runner, files, nowState, sleeps };
};

interface ToolsOpts {
  setting?: SubAgentRuntimeSetting;
  configure?: (runner: FakeRunner, files: MemoryFiles) => void;
  parentConversationId?: string;
  parentToolsProvider?: () => AgentTool[];
  isModelCouncilEnabled?: () => boolean;
}
interface ToolsHarness extends Harness {
  tools: SubAgentTools;
  parentToolsCalls: { count: number };
}
const createTools = async (options: ToolsOpts = {}): Promise<ToolsHarness> => {
  const harness = await createManager(options.setting, options.configure);
  const parentToolsCalls: { count: number } = { count: 0 };
  const tools = new SubAgentTools({
    manager: harness.manager,
    parentConversationId: options.parentConversationId ?? 'parent',
    parentToolsProvider: (): AgentTool[] => {
      parentToolsCalls.count++;
      return options.parentToolsProvider !== undefined ? options.parentToolsProvider() : [];
    },
    isModelCouncilEnabled: options.isModelCouncilEnabled ?? ((): boolean => false),
  });
  return { ...harness, tools, parentToolsCalls };
};

const textTool = (name: string): AgentTool => makeAgentTool({
  name,
  description: `description:${name}`,
  parameters: () => makeInputSchemaObj({ value: { type: 'string' } }, ['value']),
  systemPrompt: (): string => `prompt:${name}`,
  execute: (_input: JsonValue): Promise<UIMessagePart[]> => Promise.resolve([
    { type: 'text', text: `result:${name}`, metadata: null },
  ]),
});

const builtinInput = (id: string = 'fixer', objective: string = 'Review one issue'): JsonObject => ({
  subagent_id: id,
  task: { objective, output_format: 'Findings', tools_and_sources: 'file_read', boundaries: 'Do not edit' },
});

const abortablePendingScript = (): RunnerScript =>
  (call: RunnerCall): Promise<SubAgentResult> => new Promise<SubAgentResult>((_resolve, reject): void => {
    const rejectAbort = (): void => {
      const error: Error = new Error('cancelled');
      error.name = 'AbortError';
      reject(error);
    };
    if (call.signal !== undefined && call.signal.aborted) { rejectAbort(); return; }
    if (call.signal !== undefined && call.signal.addEventListener !== undefined) {
      call.signal.addEventListener('abort', rejectAbort);
    }
  });

const textOf = async (tool: AgentTool, input: JsonValue): Promise<string> => {
  const parts: UIMessagePart[] = await tool.execute(input);
  assert.equal(parts.length, 1);
  assert.equal(parts[0].type, 'text');
  return (parts[0] as { text: string }).text;
};
const jsonOf = async (tool: AgentTool, input: JsonValue): Promise<JsonObject> =>
  JSON.parse(await textOf(tool, input)) as JsonObject;

const MODEL = makeChatModel({ modelId: 'm' });
const promptOf = (tool: AgentTool, messages: Parameters<AgentTool['systemPrompt']>[1]): string =>
  tool.systemPrompt(MODEL, messages);

const schemaProps = (tool: AgentTool): JsonObject => {
  const schema = tool.parameters();
  assert.ok(schema !== null);
  return schema!.properties;
};

// ===== tests =====

describe('SubAgentTools order and schemas', () => {
  it('returns the five tools in Android order with exact names', async () => {
    const { tools } = await createTools();
    assert.deepEqual(tools.tools().map((t: AgentTool): string => t.name), [
      'subagent_list', 'subagent_start', 'subagent_read', 'subagent_wait', 'subagent_cancel',
    ]);
    assert.deepEqual(tools.getTools().map((t: AgentTool): string => t.name), tools.tools().map((t: AgentTool): string => t.name));
  });

  it('subagent_list has empty parameters and no required', async () => {
    const { tools } = await createTools();
    const schema = tools.tools()[0].parameters();
    assert.ok(schema !== null);
    assert.deepEqual(schema!.properties, {});
    assert.equal(schema!.required, null);
  });

  it('subagent_start requires only task and carries the nested custom_subagent/task schema', async () => {
    const { tools } = await createTools();
    const start = tools.tools()[1];
    const schema = start.parameters();
    assert.ok(schema !== null);
    assert.deepEqual(schema!.required, ['task']);
    const props = schema!.properties;
    assert.equal((props['subagent_id'] as JsonObject)['type'], 'string');

    const custom = props['custom_subagent'] as JsonObject;
    assert.equal(custom['type'], 'object');
    const cp = custom['properties'] as JsonObject;
    for (const key of ['name', 'description', 'system_prompt', 'tool_profile', 'model_id', 'reasoning_level']) {
      assert.equal((cp[key] as JsonObject)['type'], 'string', `${key} should be string`);
    }
    assert.equal((cp['temperature'] as JsonObject)['type'], 'number');
    for (const key of ['max_turns', 'timeout_ms', 'output_budget_chars']) {
      assert.equal((cp[key] as JsonObject)['type'], 'integer', `${key} should be integer`);
    }
    const allowlist = cp['tool_allowlist'] as JsonObject;
    assert.equal(allowlist['type'], 'array');
    assert.equal((allowlist['items'] as JsonObject)['type'], 'string');

    const task = props['task'] as JsonObject;
    assert.equal(task['type'], 'object');
    assert.deepEqual(task['required'], ['objective']);
    const tp = task['properties'] as JsonObject;
    for (const key of ['objective', 'output_format', 'tools_and_sources', 'boundaries', 'context',
      'session_grant_id', 'history_query']) {
      assert.equal((tp[key] as JsonObject)['type'], 'string', `${key} should be string`);
    }
    const sourceIds = tp['source_session_ids'] as JsonObject;
    assert.equal(sourceIds['type'], 'array');
    assert.equal((sourceIds['items'] as JsonObject)['type'], 'string');
    for (const key of ['shard_index', 'shard_count']) {
      assert.equal((tp[key] as JsonObject)['type'], 'integer', `${key} should be integer`);
    }
  });

  it('subagent_read/wait/cancel require run_id; wait carries integer wait_timeout_ms', async () => {
    const { tools } = await createTools();
    const [, , read, wait, cancel] = tools.tools();
    assert.deepEqual(read.parameters()!.required, ['run_id']);
    assert.deepEqual(wait.parameters()!.required, ['run_id']);
    assert.deepEqual(cancel.parameters()!.required, ['run_id']);
    assert.equal((schemaProps(wait)['run_id'] as JsonObject)['type'], 'string');
    assert.equal((schemaProps(read)['run_id'] as JsonObject)['type'], 'string');
    assert.equal((schemaProps(cancel)['run_id'] as JsonObject)['type'], 'string');
    const wtm = schemaProps(wait)['wait_timeout_ms'] as JsonObject;
    assert.equal(wtm['type'], 'integer');
    assert.ok(String(wtm['description']).includes('10000'));
    assert.ok(String(wtm['description']).includes('60000'));
  });
});

describe('SubAgentTools delegation to manager', () => {
  it('stopping the parent wait does not cancel the independent child', async () => {
    const h = await createTools({ parentToolsProvider: () => [textTool('file_read')],
      configure: runner => { runner.scripts.push(abortablePendingScript()); } });
    const started = await h.manager.start('parent', builtinInput(), [textTool('file_read')]);
    const id = started['run_id'] as string;
    const controller = new AbortController();
    const run = h.tools.getTools().find(tool => tool.name === 'subagent_wait')!.execute(
      { run_id: id, wait_timeout_ms: 60000 }, controller.signal);
    controller.abort();
    try {
      await assert.rejects(run, { name: 'AbortError' });
      assert.equal(h.manager.snapshot(id)?.status, 'running');
      assert.equal(h.runner.calls[0].signal?.aborted, false);
    } finally {
      await h.manager.cancel(id);
    }
  });

  it('a timed-out parent wait removes its abort listener while the child remains running', async () => {
    const h = await createTools({ parentToolsProvider: () => [textTool('file_read')],
      configure: runner => { runner.scripts.push(abortablePendingScript()); } });
    const started = await h.manager.start('parent', builtinInput(), [textTool('file_read')]);
    const id = started['run_id'] as string;
    const listeners = new Set<() => void>();
    const signal: AbortSignalLike = { aborted: false,
      addEventListener: (_type, listener) => { listeners.add(listener); },
      removeEventListener: (_type, listener) => { listeners.delete(listener); } };
    try {
      assert.equal((await h.manager.wait(id, 400, signal))['status'], 'running');
      assert.equal(listeners.size, 0);
      await assert.rejects(h.manager.wait(id, 60000, { aborted: true }), { name: 'AbortError' });
      assert.equal(h.manager.snapshot(id)?.status, 'running');
    } finally { await h.manager.cancel(id); }
  });

  it('subagent_start delegates to manager.start with parent conversation id and profiled parent tools', async () => {
    const { tools, runner, parentToolsCalls } = await createTools({
      parentToolsProvider: (): AgentTool[] => [textTool('file_read')],
    });
    const start = tools.tools()[1];
    const payload = await jsonOf(start, builtinInput('fixer', 'Review one issue'));
    assert.equal(payload['status'], 'running');
    assert.equal(payload['run_id'], 'run-1');
    assert.equal(payload['subagent_id'], 'fixer');
    assert.equal(payload['subagent_name'], 'Fixer');
    assert.equal(payload['dynamic'], false);
    assert.equal(payload['task_objective'], 'Review one issue');
    // default omission of display_text (manager runToPayload)
    assert.equal('display_text' in payload, false);
    assert.equal('definition' in payload, false);
    assert.equal('task' in payload, false);
    assert.equal('transcript_path' in payload, false);
    assert.equal(parentToolsCalls.count, 1);
    assert.equal(runner.calls.length, 1);
    assert.equal(runner.calls[0].tools.some((t: AgentTool): boolean => t.name === 'file_read'), true);
    assert.equal(runner.calls[0].tools.some((t: AgentTool): boolean => t.name.startsWith('subagent_')), false);
  });

  it('subagent_read delegates to manager.read by run_id', async () => {
    const { tools, runner } = await createTools({
      parentToolsProvider: (): AgentTool[] => [textTool('file_read')],
    });
    const [, start, read] = tools.tools();
    await jsonOf(start, builtinInput());
    await waitForRunnerCalls(runner, 1);
    const payload = await jsonOf(read, { run_id: 'run-1' });
    assert.equal(payload['status'], 'completed');
    assert.equal(payload['run_id'], 'run-1');
  });

  it('subagent_wait defaults wait_timeout_ms to 10000 (manager clamps 0..60000, polls 200ms)', async () => {
    const { tools, runner, sleeps, manager } = await createTools({
      configure: (r: FakeRunner): void => { r.scripts.push(abortablePendingScript()); },
      parentToolsProvider: (): AgentTool[] => [textTool('file_read')],
    });
    const [, start, , wait] = tools.tools();
    await jsonOf(start, builtinInput());
    await waitForRunnerCalls(runner, 1);

    const payload = await jsonOf(wait, { run_id: 'run-1' });
    assert.equal(payload['status'], 'running');
    // 10000ms / 200ms poll = 50 sleeps
    assert.equal(sleeps.length, 50);
    assert.equal(sleeps.every((ms: number): boolean => ms === 200), true);
    await manager.cancel('run-1');
  });

  it('subagent_wait passes an explicit wait_timeout_ms through (400ms => 2 polls)', async () => {
    const { tools, runner, sleeps, manager } = await createTools({
      configure: (r: FakeRunner): void => { r.scripts.push(abortablePendingScript()); },
      parentToolsProvider: (): AgentTool[] => [textTool('file_read')],
    });
    const [, start, , wait] = tools.tools();
    await jsonOf(start, builtinInput());
    await waitForRunnerCalls(runner, 1);
    const payload = await jsonOf(wait, { run_id: 'run-1', wait_timeout_ms: 400 });
    assert.equal(payload['status'], 'running');
    assert.equal(sleeps.length, 2);
    await manager.cancel('run-1');
  });

  it('subagent_cancel delegates to manager.cancel by run_id', async () => {
    const { tools, runner } = await createTools({
      configure: (r: FakeRunner): void => { r.scripts.push(abortablePendingScript()); },
      parentToolsProvider: (): AgentTool[] => [textTool('file_read')],
    });
    const [, start, , , cancel] = tools.tools();
    await jsonOf(start, builtinInput());
    await waitForRunnerCalls(runner, 1);
    const payload = await jsonOf(cancel, { run_id: 'run-1' });
    assert.equal(payload['status'], 'cancelled');
    const result = JSON.parse(String(payload['result'])) as JsonObject;
    assert.equal(result['summary'], 'Subagent run was cancelled.');
  });
});

describe('SubAgentTools list payload and roster text', () => {
  it('builds the roster-mode payload with limits, mode, dynamic help, profiles, roster and built_ins', async () => {
    const { tools } = await createTools({ setting: makeSetting({ maxConcurrentRuns: 3, timeoutMs: 12345 }) });
    const list = tools.tools()[0];
    const payload = await jsonOf(list, {});
    assert.equal(payload['status'], 'ok');
    assert.equal(payload['mode'], 'roster');
    assert.deepEqual(payload['limits'], {
      enabled: true, mode: 'roster', allow_dynamic_subagents: true,
      max_concurrent_runs: 3, dynamic_run_limit: 3,
      tool_profiles: 'none,read_only,workspace_read,web_read,history_read',
      max_depth: 1, timeout_ms: 12345, max_turns: 4, output_budget_chars: 12000, running: 0,
    });
    assert.equal(
      payload['dynamic_subagents'],
      'supported_with_validator: pass custom_subagent and omit subagent_id; name is optional, while invocation description, boundary prompt, report format, and tool profile/allowlist are validated',
    );
    assert.equal(payload['tool_profiles'], 'none, read_only, workspace_read, web_read, history_read');
    const roster = String(payload['roster']);
    assert.equal(payload['built_ins'], roster);
    assert.ok(roster.startsWith('@explorer (Explorer)\n'));
    assert.ok(roster.includes('description: '));
    assert.ok(roster.includes('tools: '));
    assert.ok(roster.includes('\nrouting:\n'));
    // six built-ins joined by blank line
    assert.equal(roster.split('\n\n').length, 6);
  });

  it('smart_dynamic mode hides built-ins and emits the smart help string', async () => {
    const { tools } = await createTools({ setting: makeSetting({ mode: 'smart_dynamic' }) });
    const payload = await jsonOf(tools.tools()[0], {});
    assert.equal(payload['mode'], 'smart_dynamic');
    assert.equal(payload['roster'], '');
    assert.equal(payload['built_ins'], '');
    assert.equal(
      payload['dynamic_subagents'],
      'smart_dynamic: create temporary custom_subagent definitions; built-in ids are hidden/disabled; English name may be auto-assigned',
    );
  });
});

describe('SubAgentTools system prompt: roster vs smart and council', () => {
  it('roster mode prompt lists every built-in with indented routing and the delegation guidance', async () => {
    const { tools } = await createTools();
    const prompt = promptOf(tools.tools()[0], [makeUserMessage('hi')]);
    assert.ok(prompt.startsWith('=== Available Subagents ===\n'));
    assert.ok(prompt.includes('You can delegate bounded subtasks to specialist subagents.'));
    assert.ok(prompt.includes('To delegate: call subagent_start(subagent_id, task={'));
    assert.ok(prompt.includes('@explorer (Explorer)\n'));
    assert.ok(prompt.includes('- 跨多源'));
    // routing lines indented two spaces
    assert.ok(prompt.includes('\n  何时调用：'));
    assert.ok(prompt.includes('@fixer (Fixer)\n'));
  });

  it('smart_dynamic mode prompt uses the smart dynamic guidance and hides built-in roles', async () => {
    const { tools } = await createTools({ setting: makeSetting({ mode: 'smart_dynamic' }) });
    const prompt = promptOf(tools.tools()[0], [makeUserMessage('hi')]);
    assert.ok(prompt.includes('Smart dynamic mode is enabled.'));
    assert.ok(prompt.includes('To delegate: call subagent_start(custom_subagent={'));
    assert.ok(prompt.includes('Never write textual <tool_call>/<function>/<parameter> blocks'));
    assert.equal(prompt.includes('@explorer (Explorer)\n'), false);
  });

  it('emits the @council roster section with the first external tool id only when council is enabled', async () => {
    const off = await createTools({ isModelCouncilEnabled: (): boolean => false });
    assert.equal(promptOf(off.tools.tools()[0], [makeUserMessage('hi')]).includes('@council (Model Council)'), false);
    const on = await createTools({ isModelCouncilEnabled: (): boolean => true });
    const prompt = promptOf(on.tools.tools()[0], [makeUserMessage('hi')]);
    assert.ok(prompt.includes('@council (Model Council)'));
    // roster @council section uses only the first external tool id (SubAgentTools.kt:101 .first())
    assert.ok(prompt.includes('external_tool=gemini_cli'));
    assert.equal(prompt.includes('gemini_cli, antigravity_cli'), false);
  });
});

describe('SubAgentTools mention override directive and self-loop guard', () => {
  it('omits the directive when no role is mentioned', async () => {
    const { tools } = await createTools();
    const prompt = promptOf(tools.tools()[0], [makeUserMessage('just a plain message')]);
    assert.equal(prompt.includes('=== USER MENTION OVERRIDE ==='), false);
  });

  it('forces a single-role subagent_start delegation and the self-loop guard', async () => {
    const { tools } = await createTools();
    const prompt = promptOf(tools.tools()[0], [makeUserMessage('@oracle should we refactor this?')]);
    assert.ok(prompt.includes('=== USER MENTION OVERRIDE ==='));
    assert.ok(prompt.includes('The user explicitly invoked @oracle. You MUST call subagent_start with subagent_id="oracle" for this turn, filling the task fields from the user\'s message. After it reports, you may package or follow up on its result.'));
    assert.ok(prompt.includes('(If you have already started the requested subagent(s) or council run in this turn, do NOT start them again — proceed to wait/read their results.)'));
  });

  it('forces parallel delegation for multiple mentions', async () => {
    const { tools } = await createTools();
    const prompt = promptOf(tools.tools()[0], [makeUserMessage('@oracle @explorer please look')]);
    assert.ok(prompt.includes('The user explicitly invoked: @oracle, @explorer. You MUST start each of these via subagent_start (in parallel where the subtasks are independent). After they report, synthesize their results.'));
  });

  it('does not treat @council as a subagent mention (roster ids exclude council)', async () => {
    const { tools } = await createTools({ isModelCouncilEnabled: (): boolean => true });
    const prompt = promptOf(tools.tools()[0], [makeUserMessage('@council discuss this')]);
    assert.equal(prompt.includes('=== USER MENTION OVERRIDE ==='), false);
  });
});

const waitForRunnerCalls = async (runner: FakeRunner, count: number): Promise<void> => {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (runner.calls.length >= count) return;
    await Promise.resolve();
  }
  assert.fail(`Runner did not reach ${count} calls`);
};
