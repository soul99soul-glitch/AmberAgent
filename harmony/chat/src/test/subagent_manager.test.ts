import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type {
  SubAgentDefinition, SubAgentMode, SubAgentOverride, SubAgentRuntimeSetting,
} from '../main/ets/chat/agent_prompt_config.ts';
import {
  makeSubAgentDefinition, makeSubAgentOverride,
} from '../main/ets/chat/agent_prompt_config.ts';
import type {
  AgentTaskFilePort, AgentTaskSnapshot, AgentTaskStoreDeps,
} from '../main/ets/chat/agent_task.ts';
import { AgentTaskStore } from '../main/ets/chat/agent_task.ts';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';
import { SessionAccessGrantStore } from '../main/ets/chat/session_grant_store.ts';
import type {
  SubAgentResult, SubAgentRun, SubAgentRunStatus, SubAgentTaskSpec,
} from '../main/ets/chat/subagent_models.ts';
import { makeSubAgentResult } from '../main/ets/chat/subagent_models.ts';
import type { SubAgentTranscriptPort } from '../main/ets/chat/subagent_transcript.ts';
import { makeChatModel } from '../main/ets/chat/provider_model.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import { makeAgentTool, makeInputSchemaObj } from '../main/ets/chat/tool.ts';
import type { SubAgentTranscriptTail } from '../main/ets/index.ts';
import type { SubAgentManagerDeps, SubAgentRunner } from '../main/ets/index.ts';
import { SubAgentManager } from '../main/ets/index.ts';

interface Deferred<T> {
  promise: Promise<T>;
  resolve(value: T): void;
  reject(error: Error): void;
}

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

interface ManagerHarness {
  manager: SubAgentManager;
  settings: FakeSettings;
  runner: FakeRunner;
  taskStore: AgentTaskStore;
  grants: SessionAccessGrantStore;
  files: MemoryFiles;
  nowState: MutableNow;
  sleeps: number[];
}

interface MutableNow {
  value: number;
}

const deferred = <T>(): Deferred<T> => {
  let resolvePromise: (value: T) => void = (): void => {};
  let rejectPromise: (error: Error) => void = (): void => {};
  const promise: Promise<T> = new Promise<T>((resolve, reject): void => {
    resolvePromise = resolve;
    rejectPromise = reject;
  });
  return {
    promise,
    resolve(value: T): void { resolvePromise(value); },
    reject(error: Error): void { rejectPromise(error); },
  };
};

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

  constructor(setting: SubAgentRuntimeSetting) {
    this.setting = setting;
  }

  getSubAgentSetting(): SubAgentRuntimeSetting {
    this.calls++;
    return this.setting;
  }
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
      definition,
      taskObjective: task.objective,
      sessionGrantId: task.sessionGrantId,
      tools,
      liveText,
      liveParts,
      signal,
    };
    this.calls.push(call);
    const script: RunnerScript | undefined = this.scripts[this.calls.length - 1];
    if (script !== undefined) return script(call);
    return Promise.resolve(makeSubAgentResult({ status: 'completed', summary: 'done' }));
  }
}

class MemoryFiles implements AgentTaskFilePort, SubAgentTranscriptPort {
  readonly files: Map<string, string> = new Map<string, string>();
  readonly appendedPaths: string[] = [];
  taskWriteBlock: Deferred<void> | null = null;
  transcriptExistsBlock: Deferred<void> | null = null;

  mkdirs(_dir: string): Promise<void> { return Promise.resolve(); }

  listJsonFileNames(dir: string): Promise<string[]> {
    const names: string[] = [];
    this.files.forEach((_text: string, path: string): void => {
      if (path.startsWith(`${dir}/`) && path.endsWith('.json') &&
        path.indexOf('/', dir.length + 1) < 0) {
        names.push(path.substring(dir.length + 1));
      }
    });
    names.sort();
    return Promise.resolve(names);
  }

  readText(path: string): Promise<string | null> {
    const value: string | undefined = this.files.get(path);
    return Promise.resolve(value !== undefined ? value : null);
  }

  async writeText(path: string, text: string): Promise<void> {
    const block: Deferred<void> | null = this.taskWriteBlock;
    if (block !== null && path.includes('/amberagent/tasks/')) await block.promise;
    this.files.set(path, text);
  }

  delete(path: string): Promise<void> {
    this.files.delete(path);
    return Promise.resolve();
  }

  async exists(path: string): Promise<boolean> {
    const block: Deferred<void> | null = this.transcriptExistsBlock;
    if (block !== null && path.includes('/amberagent/subagents/runs/')) await block.promise;
    return this.files.has(path);
  }

  isPathInside(root: string, path: string): Promise<boolean> {
    return Promise.resolve(path.startsWith(`${root}/`));
  }

  canonicalPath(path: string): Promise<string | null> {
    return Promise.resolve(path);
  }

  appendText(path: string, text: string): Promise<void> {
    this.appendedPaths.push(path);
    this.files.set(path, (this.files.get(path) ?? '') + text);
    return Promise.resolve();
  }

  isRegularFile(path: string): Promise<boolean> {
    return Promise.resolve(this.files.has(path));
  }

  readTail(path: string, _maxBytes: number): Promise<SubAgentTranscriptTail> {
    return Promise.resolve({ text: this.files.get(path) ?? '', startsAfterFileStart: false });
  }
}

const taskStoreDeps = (files: MemoryFiles): AgentTaskStoreDeps => ({
  taskDir: '/files/amberagent/tasks',
  appFilesDir: '/files',
  files,
});

const createManager = async (
  setting: SubAgentRuntimeSetting = makeSetting(),
  configure?: (runner: FakeRunner, files: MemoryFiles) => void,
): Promise<ManagerHarness> => {
  const settings = new FakeSettings(setting);
  const runner = new FakeRunner();
  const files = new MemoryFiles();
  if (configure !== undefined) configure(runner, files);
  const taskStore: AgentTaskStore = await AgentTaskStore.create(taskStoreDeps(files));
  let grantId: number = 0;
  const grants = new SessionAccessGrantStore({
    now: (): number => 1000,
    idGen: (): string => `grant-${++grantId}`,
  });
  let runId: number = 0;
  const nowState: MutableNow = { value: 10000 };
  const sleeps: number[] = [];
  const deps: SubAgentManagerDeps = {
    getSubAgentSetting: (): SubAgentRuntimeSetting => settings.getSubAgentSetting(),
    runner,
    agentTaskStore: taskStore,
    sessionAccessGrantStore: grants,
    transcript: files,
    newId: (): string => `run-${++runId}`,
    now: (): number => nowState.value,
    sleep: (ms: number): Promise<void> => {
      sleeps.push(ms);
      nowState.value += ms;
      return Promise.resolve();
    },
    transcriptPathForRun: (id: string): string => `/files/amberagent/subagents/runs/${id}.jsonl`,
  };
  const manager: SubAgentManager = new SubAgentManager(deps);
  return { manager, settings, runner, taskStore, grants, files, nowState, sleeps };
};

const builtinInput = (id: string = 'fixer', objective: string = 'Review one issue'): JsonObject => ({
  subagent_id: id,
  task: {
    objective,
    output_format: 'Findings with evidence',
    tools_and_sources: 'Use only listed tools',
    boundaries: 'Do not edit files',
  },
});

const dynamicInput = (toolAllowlist: string[] | null = null): JsonObject => {
  const custom: JsonObject = {
    name: 'Focused Reviewer',
    description: 'Use when a narrow read-only review is needed for one focused issue.',
    system_prompt: 'Boundaries: do not edit files or ask the user. Report output as findings, evidence, risks, and next steps.',
    tool_profile: toolAllowlist === null ? 'none' : 'read_only',
  };
  if (toolAllowlist !== null) custom['tool_allowlist'] = toolAllowlist;
  return {
    custom_subagent: custom,
    task: { objective: 'Review one issue' },
  };
};

const textTool = (
  name: string, needsApproval: boolean = false, allowsAutoApproval: boolean = true,
  mandatoryApproval: boolean = false,
): AgentTool => makeAgentTool({
  name,
  description: `description:${name}`,
  parameters: () => makeInputSchemaObj({ value: { type: 'string' } }, ['value']),
  systemPrompt: (): string => `prompt:${name}`,
  needsApproval,
  allowsAutoApproval,
  mandatoryApproval,
  execute: (_input: JsonValue): Promise<UIMessagePart[]> => Promise.resolve([
    { type: 'text', text: `result:${name}`, metadata: null },
  ]),
});

const payloadCode = (payload: JsonObject): string => String(payload['code'] ?? '');
const payloadStatus = (payload: JsonObject): string => String(payload['status'] ?? '');
const payloadRunId = (payload: JsonObject): string => String(payload['run_id'] ?? '');

const transcriptEvents = (files: MemoryFiles, runId: string): JsonObject[] => {
  const path: string = `/files/amberagent/subagents/runs/${runId}.jsonl`;
  return (files.files.get(path) ?? '').trim().split('\n').filter(
    (line: string): boolean => line.length > 0,
  ).map((line: string): JsonObject => JSON.parse(line) as JsonObject);
};

const waitForStatus = async (
  manager: SubAgentManager, runId: string, status: string,
): Promise<JsonObject> => {
  for (let attempt: number = 0; attempt < 100; attempt++) {
    const payload: JsonObject = await manager.read(runId);
    if (payloadStatus(payload) === status) return payload;
    await new Promise<void>((resolve): void => { setTimeout(resolve, 0); });
  }
  assert.fail(`Run ${runId} did not reach ${status}`);
};

const waitForRunnerCalls = async (runner: FakeRunner, count: number): Promise<void> => {
  for (let attempt: number = 0; attempt < 100; attempt++) {
    if (runner.calls.length >= count) return;
    await Promise.resolve();
  }
  assert.fail(`Runner did not reach ${count} calls`);
};

const waitForTaskStatus = async (
  store: AgentTaskStore, taskId: string, status: string,
): Promise<AgentTaskSnapshot> => {
  for (let attempt: number = 0; attempt < 100; attempt++) {
    const snapshot: AgentTaskSnapshot | null = store.read(taskId);
    if (snapshot !== null && snapshot.status === status) return snapshot;
    await new Promise<void>((resolve): void => { setTimeout(resolve, 0); });
  }
  assert.fail(`Task ${taskId} did not reach ${status}`);
};

const abortablePendingScript = (): RunnerScript =>
  (call: RunnerCall): Promise<SubAgentResult> => new Promise<SubAgentResult>((_resolve, reject): void => {
    const rejectAbort = (): void => {
      const error: Error = new Error('cancelled');
      error.name = 'AbortError';
      reject(error);
    };
    if (call.signal !== undefined && call.signal.aborted) {
      rejectAbort();
      return;
    }
    if (call.signal !== undefined && call.signal.addEventListener !== undefined) {
      call.signal.addEventListener('abort', rejectAbort);
    }
  });

describe('SubAgentManager start validation and admission', () => {
  it('checks disabled, task, definition, dynamic tools, then non-dynamic empty tools in Android order', async () => {
    const disabled = await createManager(makeSetting({ enabled: false }));
    const disabledPayload: JsonObject = await disabled.manager.start('parent', {}, []);
    assert.deepEqual(disabledPayload, {
      status: 'failed',
      error: 'Subagent experimental mode is disabled.',
      code: 'subagent_disabled',
    });

    const enabled = await createManager();
    const invalidTask: JsonObject = await enabled.manager.start('parent', {}, []);
    assert.equal(payloadCode(invalidTask), 'invalid_task');
    assert.equal(invalidTask['error'], 'task object is required');

    const invalidDefinition: JsonObject = await enabled.manager.start(
      'parent', { task: { objective: 'x' }, subagent_id: 'ghost' }, []);
    assert.equal(payloadCode(invalidDefinition), 'invalid_subagent');
    assert.equal(invalidDefinition['error'], 'Unknown subagent_id: ghost');

    const unavailableDynamicTool: JsonObject = await enabled.manager.start(
      'parent', dynamicInput(['missing_tool']), []);
    assert.equal(payloadCode(unavailableDynamicTool), 'invalid_subagent');
    assert.equal(
      unavailableDynamicTool['error'],
      'Tool allowlist contains unavailable tools: missing_tool',
    );

    const recursiveDynamicTool: JsonObject = await enabled.manager.start(
      'parent', dynamicInput(['subagent_wait']), [textTool('subagent_wait')]);
    assert.equal(payloadCode(recursiveDynamicTool), 'invalid_subagent');
    assert.equal(recursiveDynamicTool['error'], 'Subagents cannot call subagent_* tools');

    const noAllowed: JsonObject = await enabled.manager.start(
      'parent', builtinInput('fixer'), [textTool('session_search')]);
    assert.deepEqual(noAllowed, {
      status: 'failed',
      error: 'No allowed tools are currently available for subagent fixer.',
      code: 'no_allowed_tools',
    });
    assert.equal(enabled.runner.calls.length, 0);
  });

  it('uses one overall running count so the dynamic limit branch stays shadowed', async () => {
    const harness = await createManager(makeSetting({ maxConcurrentRuns: 1 }), (runner): void => {
      runner.scripts.push(abortablePendingScript());
    });
    const first: JsonObject = await harness.manager.start('parent', dynamicInput(), []);
    assert.equal(payloadStatus(first), 'running');
    await waitForRunnerCalls(harness.runner, 1);

    const rejected: JsonObject = await harness.manager.start('parent', dynamicInput(), []);
    assert.deepEqual(rejected, {
      status: 'failed',
      error: 'Subagent concurrency limit reached.',
      code: 'too_many_subagents',
    });
    assert.notEqual(payloadCode(rejected), 'too_many_dynamic_subagents');
    await harness.manager.cancel(payloadRunId(first));
  });
});

describe('SubAgentManager grants and child tool scope', () => {
  it('revokes the history grant when admission rejects', async () => {
    const harness = await createManager(makeSetting({ maxConcurrentRuns: 1 }), (runner): void => {
      runner.scripts.push(abortablePendingScript());
    });
    const active: JsonObject = await harness.manager.start('parent', dynamicInput(), []);
    await waitForRunnerCalls(harness.runner, 1);

    const rejected: JsonObject = await harness.manager.start('parent', {
      subagent_id: 'historian',
      task: {
        objective: 'Read history',
        source_session_ids: [' session-a ', 'session-a', 'session-b'],
      },
    }, [textTool('session_read')]);

    assert.equal(payloadCode(rejected), 'too_many_subagents');
    // admission 拒绝 → grant 被撤销,不留无主授权
    assert.equal(harness.grants.get('grant-1'), null);
    await harness.manager.cancel(payloadRunId(active));
  });

  it('copies a new grant into the task and grants full-read tools no-approval auto-approval copies', async () => {
    const harness = await createManager();
    const sessionRead: AgentTool = textTool('session_read', true, false, true);
    const sessionExpand: AgentTool = textTool('session_expand', true, false, true);
    const payload: JsonObject = await harness.manager.start('parent', {
      subagent_id: 'historian',
      task: {
        objective: 'Read history',
        source_session_ids: ['session-a'],
      },
    }, [sessionRead, textTool('subagent_wait'), sessionExpand]);
    await waitForRunnerCalls(harness.runner, 1);

    assert.equal(payload['session_grant_id'], 'grant-1');
    const call: RunnerCall = harness.runner.calls[0];
    assert.equal(call.sessionGrantId, 'grant-1');
    assert.equal(call.tools.some((tool: AgentTool): boolean => tool.name.startsWith('subagent_')), false);
    for (const name of ['session_read', 'session_expand']) {
      const granted: AgentTool | undefined = call.tools.find(
        (tool: AgentTool): boolean => tool.name === name);
      assert.ok(granted !== undefined);
      assert.equal(granted.needsApproval, false);
      assert.equal(granted.allowsAutoApproval, true);
      assert.equal(granted.mandatoryApproval, true);
      assert.equal(granted.description, `description:${name}`);
      assert.equal(granted.systemPrompt(makeChatModel({ modelId: 'm' }), []), `prompt:${name}`);
    }
    assert.equal(sessionRead.needsApproval, true);
    assert.equal(sessionRead.allowsAutoApproval, false);
  });

  it('only gets a preexisting grant without validating or assigning it', async () => {
    const harness = await createManager();
    const existing = harness.grants.create(
      ['different-session'], 5000, 'existing', 'other-parent', 'old-run');
    const payload: JsonObject = await harness.manager.start('parent', {
      subagent_id: 'historian',
      task: {
        objective: 'Use an existing grant without source sessions',
        session_grant_id: existing.grantId,
      },
    }, [textTool('session_read')]);
    await waitForRunnerCalls(harness.runner, 1);

    assert.equal(payload['session_grant_id'], existing.grantId);
    assert.equal(harness.runner.calls[0].sessionGrantId, existing.grantId);
    assert.equal(harness.grants.get(existing.grantId)?.assignedSubagentRunId, 'old-run');
  });
});

describe('SubAgentManager registration, transcript, and terminal mapping', () => {
  it('registers the exact nonretryable task snapshot and started event before launching', async () => {
    let observedTask: AgentTaskSnapshot | null = null;
    let observedEvents: JsonObject[] = [];
    const harness = await createManager(makeSetting(), (runner, files): void => {
      runner.scripts.push(async (): Promise<SubAgentResult> => {
        observedEvents = transcriptEvents(files, 'run-1');
        return makeSubAgentResult({ status: 'completed', summary: 'done' });
      });
    });
    harness.runner.scripts[0] = async (): Promise<SubAgentResult> => {
      observedTask = harness.taskStore.read('run-1');
      observedEvents = transcriptEvents(harness.files, 'run-1');
      return makeSubAgentResult({ status: 'completed', summary: 'done' });
    };
    const objective: string = 'x'.repeat(1100);

    const initial: JsonObject = await harness.manager.start(
      'parent-conversation', builtinInput('fixer', objective), [textTool('file_read')]);
    await waitForRunnerCalls(harness.runner, 1);

    assert.equal(payloadStatus(initial), 'running');
    assert.ok(observedTask !== null);
    const task: AgentTaskSnapshot = observedTask;
    assert.equal(task.taskId, 'run-1');
    assert.equal(task.type, 'subagent');
    assert.equal(task.title, 'Fixer');
    assert.equal(task.sourceConversationId, 'parent-conversation');
    assert.equal(task.status, 'RUNNING');
    assert.equal(task.queueState, 'ACTIVE');
    assert.equal(task.outputPath, '/files/amberagent/subagents/runs/run-1.jsonl');
    assert.deepEqual(task.outputRef, {
      type: 'transcript',
      path: '/files/amberagent/subagents/runs/run-1.jsonl',
      tailOffset: 0,
      exists: false,
    });
    assert.equal(task.retryPolicy.retryable, false);
    assert.equal(task.retryPolicy.requiresApproval, false);
    assert.equal(task.retryPolicy.maxRetries, 1);
    assert.equal(task.retryPolicy.reason,
      'Sub Agent retry starts a new isolated run from the original task spec.');
    assert.equal(task.sourceToolName, 'subagent_start');
    assert.equal(task.cancelCapability, true);
    assert.equal(task.summary, objective.slice(0, 1000));
    assert.deepEqual(observedEvents.map(
      (event: JsonObject): JsonValue | undefined => event['event']), ['started']);
  });

  it('records outputRef.exists true when the transcript already exists before registration', async () => {
    const harness = await createManager(makeSetting(), (runner, files): void => {
      files.files.set('/files/amberagent/subagents/runs/run-1.jsonl', '{"event":"old"}\n');
      runner.scripts.push(abortablePendingScript());
    });

    const initial: JsonObject = await harness.manager.start(
      'parent', builtinInput(), [textTool('file_read')]);
    const runId: string = payloadRunId(initial);
    const task: AgentTaskSnapshot | null = harness.taskStore.read(runId);

    assert.equal(task?.outputRef?.exists, true);
    await harness.manager.cancel(runId);
  });

  it('writes started then finished, keeps public display text compact, and JSON-encodes result as a string', async () => {
    const harness = await createManager(makeSetting(), (runner): void => {
      runner.scripts.push(async (call: RunnerCall): Promise<SubAgentResult> => {
        call.liveText('Full human transcript');
        call.liveParts([{ type: 'text', text: 'rich part', metadata: null }]);
        return makeSubAgentResult({
          status: 'completed',
          summary: 'Done',
          findings: ['finding'],
          recommendedNextSteps: ['next'],
          confidence: 'high',
        });
      });
    });
    const initial: JsonObject = await harness.manager.start(
      'parent', builtinInput(), [textTool('file_read')]);
    const runId: string = payloadRunId(initial);
    const terminal: JsonObject = await waitForStatus(harness.manager, runId, 'completed');

    assert.equal(terminal['display_text_chars'], 'Full human transcript'.length);
    assert.equal('display_text' in terminal, false);
    assert.equal(typeof terminal['result'], 'string');
    const result: JsonObject = JSON.parse(String(terminal['result'])) as JsonObject;
    assert.equal(result['status'], 'completed');
    assert.deepEqual(result['recommended_next_steps'], ['next']);
    const events: JsonObject[] = transcriptEvents(harness.files, runId);
    assert.deepEqual(events.map(
      (event: JsonObject): JsonValue | undefined => event['event']), ['started', 'finished']);
    const finishedPayload: JsonObject = events[1]['payload'] as JsonObject;
    assert.equal(finishedPayload['display_text'], 'Full human transcript');
    assert.equal('definition' in terminal, false);
    assert.equal('task' in terminal, false);
    assert.equal('transcript_path' in terminal, false);
  });

  it('maps every runner terminal result to the exact nonretryable task status', async () => {
    const statuses: SubAgentRunStatus[] = [
      'completed', 'failed', 'approval_required', 'cancelled', 'timed_out', 'interrupted',
    ];
    // R14: approval_required 无法在子代理内授权 → 任务板 INTERRUPTED(带原因),不再裸 RUNNING
    const expectedTask: string[] = [
      'COMPLETED', 'FAILED', 'INTERRUPTED', 'CANCELLED', 'TIMED_OUT', 'INTERRUPTED',
    ];
    for (let index: number = 0; index < statuses.length; index++) {
      const status: SubAgentRunStatus = statuses[index];
      const harness = await createManager(makeSetting(), (runner): void => {
        runner.scripts.push((): Promise<SubAgentResult> => Promise.resolve(makeSubAgentResult({
          status,
          summary: status === 'approval_required' ? 'approval' : '',
          findings: status === 'completed' ? ['one', 'two'] : [],
          error: status === 'failed' ? 'boom' : '',
        })));
      });
      const initial: JsonObject = await harness.manager.start(
        'parent', builtinInput(), [textTool('file_read')]);
      const runId: string = payloadRunId(initial);
      await waitForStatus(harness.manager, runId, status);
      const task: AgentTaskSnapshot = await waitForTaskStatus(
        harness.taskStore, runId, expectedTask[index]);
      // approval_required 运行对象仍 live → 任务板保留取消能力(其余终态 false)
      assert.equal(task.cancelCapability, status === 'approval_required');
      assert.equal(task.retryPolicy.retryable, false);
      assert.equal(task.outputRef?.exists, false);
      if (status === 'completed') assert.equal(task.summary, 'one; two');
      if (status === 'failed') assert.equal(task.error, 'boom');
      if (status === 'approval_required') {
        assert.equal(task.lastErrorCode, 'subagent_approval_required');
        assert.ok((task.error ?? '').includes('requested approval'));
      }
    }
  });

  it('R14 approval_required 仍可 cancel 收口为 cancelled', async () => {
    const harness = await createManager(makeSetting(), (runner): void => {
      runner.scripts.push((): Promise<SubAgentResult> => Promise.resolve(makeSubAgentResult({
        status: 'approval_required',
        summary: 'Subagent requested approval for file_write.',
      })));
    });
    const initial: JsonObject = await harness.manager.start(
      'parent', builtinInput(), [textTool('file_read')]);
    const runId: string = payloadRunId(initial);
    await waitForStatus(harness.manager, runId, 'approval_required');
    // 任务板已收口 INTERRUPTED 且带原因
    const suspended: AgentTaskSnapshot = await waitForTaskStatus(
      harness.taskStore, runId, 'INTERRUPTED');
    assert.equal(suspended.lastErrorCode, 'subagent_approval_required');
    // cancel 仍可终结运行对象与任务板(R14:不可恢复/不可取消的挂起态消除)
    const cancelled: JsonObject = await harness.manager.cancel(runId);
    assert.equal(payloadStatus(cancelled), 'cancelled');
    await waitForTaskStatus(harness.taskStore, runId, 'CANCELLED');
  });

  it('R14×R25 approval_required:看板取消经回调收口,活跃期间 cleanup 拒绝', async () => {
    const harness = await createManager(makeSetting(), (runner): void => {
      runner.scripts.push((): Promise<SubAgentResult> => Promise.resolve(makeSubAgentResult({
        status: 'approval_required',
        summary: 'Subagent requested approval.',
      })));
    });
    const initial: JsonObject = await harness.manager.start(
      'parent', builtinInput(), [textTool('file_read')]);
    const runId: string = payloadRunId(initial);
    await waitForStatus(harness.manager, runId, 'approval_required');
    const suspended: AgentTaskSnapshot = await waitForTaskStatus(
      harness.taskStore, runId, 'INTERRUPTED');
    // 任务板保留取消能力 → P1-1:cleanup 不得删活跃记录/回调
    assert.equal(suspended.cancelCapability, true);
    assert.equal(await harness.taskStore.cleanup(runId, true), false);
    assert.notEqual(harness.taskStore.read(runId), null);
    // P2-1:看板取消真正触发 manager 回调 → 运行对象与看板都 CANCELLED
    const cancelled: AgentTaskSnapshot = await harness.taskStore.cancel(runId);
    assert.equal(cancelled.status, 'CANCELLED');
    assert.equal(harness.manager.snapshot(runId)?.status, 'cancelled');
    // 收口后回调注销,记录可清
    assert.equal(await harness.taskStore.cleanup(runId), true);
    assert.equal(harness.taskStore.read(runId), null);
  });

  it('does not block the terminal run/transcript on the fire-and-forget task update', async () => {
    const result = deferred<SubAgentResult>();
    const harness = await createManager(makeSetting(), (runner): void => {
      runner.scripts.push((): Promise<SubAgentResult> => result.promise);
    });
    const initial: JsonObject = await harness.manager.start(
      'parent', builtinInput(), [textTool('file_read')]);
    const runId: string = payloadRunId(initial);
    await waitForRunnerCalls(harness.runner, 1);
    const block: Deferred<void> = deferred<void>();
    harness.files.taskWriteBlock = block;

    result.resolve(makeSubAgentResult({ status: 'completed', summary: 'done' }));
    await waitForStatus(harness.manager, runId, 'completed');

    // AgentTaskStore mutates its in-memory snapshot before its persistence write awaits.
    // The manager contract is that completion and transcript do not await that write.
    assert.equal(harness.taskStore.read(runId)?.status, 'COMPLETED');
    assert.deepEqual(transcriptEvents(harness.files, runId).map(
      (event: JsonObject): JsonValue | undefined => event['event']), ['started', 'finished']);
    block.resolve();
    harness.files.taskWriteBlock = null;
    await waitForTaskStatus(harness.taskStore, runId, 'COMPLETED');
  });
});

describe('SubAgentManager cancellation and first-wins races', () => {
  it('replays cancellation to the task store when cancel wins before registration', async () => {
    const harness = await createManager(makeSetting(), (runner): void => {
      runner.scripts.push((): Promise<SubAgentResult> => Promise.resolve(
        makeSubAgentResult({ status: 'completed', summary: 'late completion' })));
    });
    const existsBlock: Deferred<void> = deferred<void>();
    harness.files.transcriptExistsBlock = existsBlock;
    const starting: Promise<JsonObject> = harness.manager.start(
      'parent', builtinInput(), [textTool('file_read')]);
    for (let attempt: number = 0; attempt < 100 && harness.manager.snapshot('run-1') === null; attempt++) {
      await Promise.resolve();
    }
    assert.equal(harness.manager.snapshot('run-1')?.status, 'running');

    const cancelled: JsonObject = await harness.manager.cancel('run-1');
    assert.equal(payloadStatus(cancelled), 'cancelled');
    assert.equal(harness.taskStore.read('run-1'), null);
    existsBlock.resolve();
    harness.files.transcriptExistsBlock = null;
    await starting;

    assert.equal((await harness.manager.read('run-1'))['status'], 'cancelled');
    const task: AgentTaskSnapshot | null = harness.taskStore.read('run-1');
    assert.equal(task?.status, 'CANCELLED');
    assert.equal(task?.cancelCapability, false);
    assert.deepEqual(transcriptEvents(harness.files, 'run-1').map(
      (event: JsonObject): JsonValue | undefined => event['event']), ['started', 'finished']);
  });

  it('aborts, returns the exact cancellation result, and drops a late provider result', async () => {
    const late = deferred<SubAgentResult>();
    const harness = await createManager(makeSetting(), (runner): void => {
      runner.scripts.push((_call: RunnerCall): Promise<SubAgentResult> => late.promise);
    });
    const initial: JsonObject = await harness.manager.start(
      'parent', builtinInput(), [textTool('file_read')]);
    const runId: string = payloadRunId(initial);
    await waitForRunnerCalls(harness.runner, 1);

    const cancelled: JsonObject = await harness.manager.cancel(runId);
    assert.equal(payloadStatus(cancelled), 'cancelled');
    const cancelledResult: JsonObject = JSON.parse(String(cancelled['result'])) as JsonObject;
    assert.equal(cancelledResult['summary'], 'Subagent run was cancelled.');
    assert.equal(harness.runner.calls[0].signal?.aborted, true);

    late.resolve(makeSubAgentResult({ status: 'completed', summary: 'too late' }));
    await new Promise<void>((resolve): void => { setTimeout(resolve, 0); });
    const stillCancelled: JsonObject = await harness.manager.read(runId);
    assert.equal(payloadStatus(stillCancelled), 'cancelled');
    const result: JsonObject = JSON.parse(String(stillCancelled['result'])) as JsonObject;
    assert.equal(result['summary'], 'Subagent run was cancelled.');
  });

  it('finishes a timed-out run as timed_out (不再永久 RUNNING 占并发额度)', async () => {
    const override: SubAgentOverride = makeSubAgentOverride();
    override.timeoutMsOverride = 5;
    const overrides: Map<string, SubAgentOverride> = new Map<string, SubAgentOverride>();
    overrides.set('fixer', override);
    const harness = await createManager(makeSetting({ overrides }), (runner): void => {
      runner.scripts.push(abortablePendingScript());
    });
    const initial: JsonObject = await harness.manager.start(
      'parent', builtinInput(), [textTool('file_read')]);
    const runId: string = payloadRunId(initial);
    await waitForRunnerCalls(harness.runner, 1);
    // cappedBy 会把 timeoutMs 钳到下限 1000ms → 轮询窗口须覆盖
    for (let attempt: number = 0;
      attempt < 400 && harness.runner.calls[0].signal?.aborted !== true; attempt++) {
      await new Promise<void>((resolve): void => { setTimeout(resolve, 5); });
    }

    assert.equal(harness.runner.calls[0].signal?.aborted, true);
    for (let attempt: number = 0;
      attempt < 400 && (await harness.manager.read(runId))['status'] !== 'timed_out'; attempt++) {
      await new Promise<void>((resolve): void => { setTimeout(resolve, 5); });
    }
    assert.equal((await harness.manager.read(runId))['status'], 'timed_out');
    assert.equal(harness.taskStore.read(runId)?.status, 'TIMED_OUT');
    assert.deepEqual(transcriptEvents(harness.files, runId).map(
      (event: JsonObject): JsonValue | undefined => event['event']), ['started', 'finished']);
  });

  it('early cancel during registration blocks the runner launch; cancellation stays first-wins', async () => {
    const harness = await createManager(makeSetting(), (runner): void => {
      runner.scripts.push((): Promise<SubAgentResult> => Promise.resolve(
        makeSubAgentResult({ status: 'completed', summary: 'late completion' })));
    });
    const registrationBlock: Deferred<void> = deferred<void>();
    harness.files.taskWriteBlock = registrationBlock;
    const starting: Promise<JsonObject> = harness.manager.start(
      'parent', builtinInput(), [textTool('file_read')]);
    for (let attempt: number = 0; attempt < 100 && harness.manager.snapshot('run-1') === null; attempt++) {
      await Promise.resolve();
    }
    assert.equal(harness.manager.snapshot('run-1')?.status, 'running');

    const cancelled: JsonObject = await harness.manager.cancel('run-1');
    assert.equal(payloadStatus(cancelled), 'cancelled');
    registrationBlock.resolve();
    harness.files.taskWriteBlock = null;
    const initial: JsonObject = await starting;
    assert.equal(payloadStatus(initial), 'running');
    assert.deepEqual(transcriptEvents(harness.files, 'run-1').map(
      (event: JsonObject): JsonValue | undefined => event['event']), ['started', 'finished']);
    // controller 在 runtimeRun 创建时即存在,启动前已 aborted → runner 不再启动
    await new Promise<void>((resolve): void => { setTimeout(resolve, 0); });
    assert.equal(harness.runner.calls.length, 0);
    assert.equal((await harness.manager.read('run-1'))['status'], 'cancelled');
  });
});

describe('SubAgentManager read, wait, and missing runs', () => {
  it('clamps waits to 0..60000 and polls every 200ms before returning current state', async () => {
    const harness = await createManager(makeSetting(), (runner): void => {
      runner.scripts.push(abortablePendingScript());
    });
    const initial: JsonObject = await harness.manager.start(
      'parent', dynamicInput(), []);
    const runId: string = payloadRunId(initial);
    await waitForRunnerCalls(harness.runner, 1);

    const direct: JsonObject = await harness.manager.wait(runId, -5);
    assert.equal(payloadStatus(direct), 'running');
    assert.deepEqual(harness.sleeps, []);

    const waited: JsonObject = await harness.manager.wait(runId, 999999);
    assert.equal(payloadStatus(waited), 'running');
    assert.equal(harness.sleeps.length, 300);
    assert.equal(harness.sleeps.every((ms: number): boolean => ms === 200), true);
    await harness.manager.cancel(runId);
  });

  it('returns interrupted when a missing run transcript exists and not_found otherwise', async () => {
    const harness = await createManager();
    harness.files.files.set(
      '/files/amberagent/subagents/runs/gone.jsonl',
      '{"event":"finished"}\n',
    );

    assert.deepEqual(await harness.manager.read('gone'), {
      status: 'interrupted',
      run_id: 'gone',
      transcript_available: true,
      error: 'Subagent run is no longer active in memory.',
    });
    assert.deepEqual(await harness.manager.cancel('unknown'), {
      status: 'failed',
      error: 'Unknown subagent run_id: unknown',
      code: 'not_found',
    });
  });
});

describe('SubAgentManager snapshots, live subscriptions, and cap', () => {
  it('replays current text/parts immediately, supports unsubscribe, and returns null for unknown runs', async () => {
    const result = deferred<SubAgentResult>();
    const harness = await createManager(makeSetting(), (runner): void => {
      runner.scripts.push((call: RunnerCall): Promise<SubAgentResult> => {
        call.liveText('already live');
        call.liveParts([{ type: 'text', text: 'part one', metadata: null }]);
        return result.promise;
      });
    });
    const initial: JsonObject = await harness.manager.start(
      'parent', builtinInput(), [textTool('file_read')]);
    const runId: string = payloadRunId(initial);
    await waitForRunnerCalls(harness.runner, 1);
    const texts: string[] = [];
    const partCounts: number[] = [];
    const unsubscribeText: (() => void) | null = harness.manager.subscribeLiveText(
      runId, (text: string): void => { texts.push(text); });
    const unsubscribeParts: (() => void) | null = harness.manager.subscribeLiveParts(
      runId, (parts: UIMessagePart[]): void => { partCounts.push(parts.length); });

    assert.deepEqual(texts, ['already live']);
    assert.deepEqual(partCounts, [1]);
    assert.ok(unsubscribeText !== null);
    assert.ok(unsubscribeParts !== null);
    harness.runner.calls[0].liveText('next');
    harness.runner.calls[0].liveParts([]);
    assert.deepEqual(texts, ['already live', 'next']);
    assert.deepEqual(partCounts, [1, 0]);
    unsubscribeText();
    unsubscribeParts();
    harness.runner.calls[0].liveText('ignored');
    harness.runner.calls[0].liveParts([{ type: 'text', text: 'ignored', metadata: null }]);
    assert.deepEqual(texts, ['already live', 'next']);
    assert.deepEqual(partCounts, [1, 0]);
    assert.equal(harness.manager.subscribeLiveText('missing', (): void => {}), null);
    assert.equal(harness.manager.subscribeLiveParts('missing', (): void => {}), null);
    const snapshot: SubAgentRun | null = harness.manager.snapshot(runId);
    assert.equal(snapshot?.displayText, '');
    result.resolve(makeSubAgentResult({ status: 'completed', summary: 'done' }));
  });

  it('keeps active live state and evicts the oldest terminal state after the soft cap of 64', async () => {
    const harness = await createManager(makeSetting({ maxConcurrentRuns: 100 }), (runner): void => {
      runner.scripts.push(abortablePendingScript());
      for (let index: number = 1; index < 65; index++) {
        runner.scripts.push((): Promise<SubAgentResult> => Promise.resolve(
          makeSubAgentResult({ status: 'completed', summary: 'done' })));
      }
    });
    const first: JsonObject = await harness.manager.start('parent', dynamicInput(), []);
    const firstId: string = payloadRunId(first);
    await waitForRunnerCalls(harness.runner, 1);
    for (let index: number = 1; index < 65; index++) {
      const payload: JsonObject = await harness.manager.start('parent', dynamicInput(), []);
      await waitForStatus(harness.manager, payloadRunId(payload), 'completed');
      harness.nowState.value++;
    }

    assert.ok(harness.manager.subscribeLiveText(firstId, (): void => {}) !== null);
    assert.equal(harness.manager.subscribeLiveText('run-2', (): void => {}), null);
    assert.ok(harness.manager.subscribeLiveText('run-65', (): void => {}) !== null);
    await harness.manager.cancel(firstId);
  });
});

describe('SubAgentManager roster and runtime settings surface', () => {
  it('returns roster built-ins plus custom definitions and exact runtime summary values', async () => {
    const override: SubAgentOverride = makeSubAgentOverride();
    override.systemPrompt = 'overridden explorer';
    const overrides: Map<string, SubAgentOverride> = new Map<string, SubAgentOverride>();
    overrides.set('explorer', override);
    const custom: SubAgentDefinition = makeSubAgentDefinition({
      id: 'saved', name: 'Saved', description: 'Use when saved work is needed.',
      systemPrompt: 'Boundaries: do not edit. Report output as findings.',
      toolAllowlist: ['file_read'],
    });
    const harness = await createManager(makeSetting({
      overrides,
      customDefinitions: [custom],
      maxConcurrentRuns: 3,
      timeoutMs: 12345,
      maxTurns: 5,
      outputBudgetChars: 23456,
    }));

    const roster: SubAgentDefinition[] = harness.manager.listBuiltIns();
    assert.equal(roster.length, 7);
    assert.equal(roster[0].id, 'explorer');
    assert.equal(roster[0].systemPrompt, 'overridden explorer');
    assert.equal(roster[6].id, 'saved');
    assert.deepEqual(roster[6].toolAllowlist, ['file_read']);
    assert.equal(roster[6].dynamic, false);
    assert.equal(harness.manager.runtimeMode(), 'roster');
    assert.deepEqual(harness.manager.runtimeSummary(), {
      enabled: true,
      mode: 'roster',
      allow_dynamic_subagents: true,
      max_concurrent_runs: 3,
      dynamic_run_limit: 3,
      tool_profiles: 'none,read_only,workspace_read,web_read,history_read',
      max_depth: 1,
      timeout_ms: 12345,
      max_turns: 5,
      output_budget_chars: 23456,
      running: 0,
    });
  });

  it('reads a fresh settings snapshot for roster, mode, and summary without starting a run', async () => {
    const custom: SubAgentDefinition = makeSubAgentDefinition({
      id: 'fresh', name: 'Fresh', description: 'Use when fresh settings are required.',
      systemPrompt: 'Boundaries: do not edit. Report output as findings and evidence.',
      toolAllowlist: ['file_read', 'terminal_execute'],
    });
    const harness = await createManager(makeSetting());
    harness.settings.setting = makeSetting({
      mode: 'smart_dynamic',
      maxConcurrentRuns: 9,
      timeoutMs: 2345,
      maxTurns: 7,
      outputBudgetChars: 3456,
      customDefinitions: [custom],
    });

    assert.equal(harness.manager.runtimeMode(), 'smart_dynamic');
    const roster: SubAgentDefinition[] = harness.manager.listBuiltIns();
    assert.equal(roster.length, 1);
    assert.equal(roster[0].id, 'fresh');
    assert.deepEqual(roster[0].toolAllowlist, ['file_read']);
    assert.equal(roster[0].dynamic, true);
    const summary: JsonObject = harness.manager.runtimeSummary();
    assert.equal(summary['mode'], 'smart_dynamic');
    assert.equal(summary['max_concurrent_runs'], 9);
    assert.equal(summary['dynamic_run_limit'], 9);
    assert.equal(summary['timeout_ms'], 2345);
    assert.equal(summary['max_turns'], 7);
    assert.equal(summary['output_budget_chars'], 3456);
  });

  it('hides built-ins in smart mode and returns read-only dynamic copies of saved roles', async () => {
    const custom: SubAgentDefinition = makeSubAgentDefinition({
      id: 'saved', name: 'Saved', description: 'Use when saved work is needed.',
      systemPrompt: 'Boundaries: do not edit. Report output as findings.',
      toolAllowlist: ['file_read', 'terminal_execute'],
      dynamic: false,
    });
    const harness = await createManager(makeSetting({
      mode: 'smart_dynamic', customDefinitions: [custom],
    }));

    const roster: SubAgentDefinition[] = harness.manager.listBuiltIns();
    assert.equal(roster.length, 1);
    assert.deepEqual(roster[0].toolAllowlist, ['file_read']);
    assert.equal(roster[0].dynamic, true);
    assert.equal(harness.manager.runtimeMode(), 'smart_dynamic');
  });
});

describe('SubAgentManager dynamic English name allocation', () => {
  it('assigns distinct names to parallel starts while transcript initialization is awaiting', async () => {
    const harness = await createManager(makeSetting({ maxConcurrentRuns: 10 }), (runner): void => {
      for (let i: number = 0; i < 10; i++) runner.scripts.push(abortablePendingScript());
    });
    const payloads: JsonObject[] = await Promise.all(
      Array.from({ length: 10 }, (): Promise<JsonObject> =>
        harness.manager.start('parent', dynamicInput(), [])));
    try {
      const names: string[] = payloads.map((payload: JsonObject): string => String(payload['subagent_name']));
      names.forEach((name: string): void => { assert.match(name, /^[A-Z][a-z]+$/); });
      assert.equal(new Set(names.map((name: string): string => name.toLowerCase())).size, 10);
      payloads.forEach((payload: JsonObject): void => {
        const runId: string = payloadRunId(payload);
        assert.equal(harness.manager.snapshot(runId)?.definition.name, payload['subagent_name']);
        assert.equal(harness.taskStore.read(runId)?.title, payload['subagent_name']);
      });
    } finally {
      await Promise.all(payloads.map((payload: JsonObject): Promise<JsonObject> =>
        harness.manager.cancel(payloadRunId(payload))));
    }
  });

  it('preserves an explicit English name, distinguishes a simultaneous duplicate, and reuses it after completion', async () => {
    const harness = await createManager(makeSetting({ maxConcurrentRuns: 2 }), (runner): void => {
      runner.scripts.push(abortablePendingScript(), abortablePendingScript(), abortablePendingScript());
    });
    const input: JsonObject = dynamicInput();
    (input['custom_subagent'] as JsonObject)['name'] = 'Grace_5';
    const first: JsonObject = await harness.manager.start('parent', input, []);
    const second: JsonObject = await harness.manager.start('other-parent', input, []);
    try {
      assert.equal(first['subagent_name'], 'Grace');
      assert.notEqual(second['subagent_name'], 'Grace');
      await harness.manager.cancel(payloadRunId(first));
      const third: JsonObject = await harness.manager.start('parent', input, []);
      try { assert.equal(third['subagent_name'], 'Grace'); }
      finally { await harness.manager.cancel(payloadRunId(third)); }
    } finally {
      await harness.manager.cancel(payloadRunId(first));
      await harness.manager.cancel(payloadRunId(second));
    }
  });
});
