import type { AbortSignalLike } from '@amber/deepread-domain';
import type {
  SubAgentDefinition, SubAgentMode, SubAgentRuntimeSetting,
} from './agent_prompt_config.ts';
import {
  SUB_AGENT_BUILT_INS, subAgentApplyOverride,
} from './agent_prompt_config.ts';
import type {
  AgentTaskSnapshot, AgentTaskStatus, AgentTaskStore,
} from './agent_task.ts';
import {
  agentTaskStatusToQueueState, makeAgentTaskOutputRef,
  makeAgentTaskRetryPolicy, makeAgentTaskSnapshot,
} from './agent_task.ts';
import type { JsonObject } from './json.ts';
import type { UIMessagePart } from './message.ts';
import type {
  SessionAccessGrant, SessionAccessGrantStore,
} from './session_grant_store.ts';
import type {
  SubAgentResult, SubAgentRun, SubAgentRunStatus, SubAgentTaskSpec,
} from './subagent_models.ts';
import {
  makeSubAgentResult, makeSubAgentRun, subAgentRunStatusRunning,
} from './subagent_models.ts';
import type { SubAgentRunner } from './subagent_runner.ts';
import { scopedSubAgentTools } from './subagent_tool_scope.ts';
import type { SubAgentTranscriptPort } from './subagent_transcript.ts';
import { appendSubAgentTranscriptEvent } from './subagent_transcript.ts';
import {
  DEFAULT_DYNAMIC_READ_ONLY_TOOLS, subAgentDynamicName, subAgentParseTask,
  subAgentResolveDefinition, subAgentValidateToolAllowlist,
} from './subagent_validator.ts';
import type { AgentTool } from './tool.ts';

export interface SubAgentManagerDeps {
  getSubAgentSetting: () => SubAgentRuntimeSetting;
  runner: SubAgentRunner;
  agentTaskStore: AgentTaskStore;
  sessionAccessGrantStore: SessionAccessGrantStore;
  transcript: SubAgentTranscriptPort;
  newId: () => string;
  now: () => number;
  sleep: (ms: number) => Promise<void>;
  transcriptPathForRun: (runId: string) => string;
}

interface AdmissionError {
  code: string;
  message: string;
}

interface TerminalTaskUpdate {
  status: AgentTaskStatus;
  summary: string;
  error: string | null;
  cancelCapability: boolean;
  lastErrorCode: string | null;
}

interface RuntimeRun {
  snapshot: SubAgentRun;
  runner: SubAgentRunner;
  controller: LocalAbortController | null;
  terminalMutex: AsyncMutex;
  taskRegistered: boolean;
  pendingTerminalTaskUpdate: TerminalTaskUpdate | null;
  startedTranscriptWritten: boolean;
  pendingFinishedTranscript: SubAgentRun | null;
}

// holder:超时标记(timer 回调先置位再 abort,防 runner AbortError 竞速混淆来源)
interface TimeoutFlagHolder {
  timedOut: boolean;
}

class AsyncMutex {
  private tail: Promise<void> = Promise.resolve();

  withLock<T>(block: () => Promise<T>): Promise<T> {
    const run: Promise<T> = this.tail.then(block);
    this.tail = run.then((): void => undefined, (): void => undefined);
    return run;
  }
}

class LocalAbortSignal implements AbortSignalLike {
  aborted: boolean = false;
  private listeners: Array<() => void> = [];

  addEventListener(type: string, listener: () => void): void {
    if (type !== 'abort') return;
    if (this.aborted) {
      listener();
      return;
    }
    this.listeners.push(listener);
  }

  abort(): void {
    if (this.aborted) return;
    this.aborted = true;
    const listeners: Array<() => void> = this.listeners.slice();
    this.listeners = [];
    listeners.forEach((listener: () => void): void => {
      try {
        listener();
      } catch {
        // One subscriber must not prevent the remaining abort listeners.
      }
    });
  }
}

class LocalAbortController {
  readonly signal: LocalAbortSignal = new LocalAbortSignal();

  abort(): void {
    this.signal.abort();
  }
}

class SubAgentTimeoutCancellation extends Error {
  constructor() {
    super('Subagent timed out');
    this.name = 'AbortError';
  }
}

const HISTORY_FULL_READ_TOOLS: ReadonlySet<string> =
  new Set<string>(['session_read', 'session_expand']);
const LIVE_STATE_CAP: number = 64;
const TOOL_PROFILES: string = 'none,read_only,workspace_read,web_read,history_read';

const errorMessage = (error: unknown): string => {
  if (error instanceof Error) {
    return error.message.length > 0 ? error.message : error.name;
  }
  return String(error);
};

const copyDefinition = (
  definition: SubAgentDefinition, toolAllowlist: string[], dynamic: boolean,
): SubAgentDefinition => ({
  id: definition.id,
  name: definition.name,
  description: definition.description,
  systemPrompt: definition.systemPrompt,
  toolAllowlist,
  maxTurns: definition.maxTurns,
  timeoutMs: definition.timeoutMs,
  outputBudgetChars: definition.outputBudgetChars,
  dynamic,
  modelId: definition.modelId,
  temperature: definition.temperature,
  reasoningLevel: definition.reasoningLevel,
  routingHint: definition.routingHint,
  supportsModelOverride: definition.supportsModelOverride,
  phaseLabels: definition.phaseLabels,
});

const copyTaskWithGrant = (task: SubAgentTaskSpec, sessionGrantId: string): SubAgentTaskSpec => ({
  objective: task.objective,
  outputFormat: task.outputFormat,
  toolsAndSources: task.toolsAndSources,
  boundaries: task.boundaries,
  context: task.context,
  sessionGrantId,
  sourceSessionIds: task.sourceSessionIds,
  historyQuery: task.historyQuery,
  shardIndex: task.shardIndex,
  shardCount: task.shardCount,
});

const copyToolForHistoryGrant = (tool: AgentTool): AgentTool => ({
  name: tool.name,
  description: tool.description,
  parameters: tool.parameters,
  systemPrompt: tool.systemPrompt,
  needsApproval: false,
  allowsAutoApproval: true,
  mandatoryApproval: tool.mandatoryApproval,
  execute: tool.execute,
});

const resultToWireJson = (result: SubAgentResult): JsonObject => ({
  status: result.status,
  summary: result.summary,
  findings: result.findings,
  evidence: result.evidence,
  risks: result.risks,
  confidence: result.confidence,
  recommended_next_steps: result.recommendedNextSteps,
  error: result.error,
});

const errorPayload = (code: string, message: string): JsonObject => ({
  status: 'failed',
  error: message,
  code,
});

const isHistoryReader = (definition: SubAgentDefinition): boolean => {
  if (definition.id === 'historian') return true;
  return definition.toolAllowlist.some(
    (toolName: string): boolean => HISTORY_FULL_READ_TOOLS.has(toolName));
};

// live = 仍可被 finish/cancel 收口的运行态。approval_required 是子代理自身无法
//   自行审批的挂起态:任务板映射为 INTERRUPTED(带原因),但运行对象保持 live,
//   使 cancel() 仍能收口为 cancelled(R14),不再出现"不可恢复、不可取消"。
const isLiveRunStatus = (status: SubAgentRunStatus): boolean =>
  status === 'running' || status === 'approval_required';

const statusToAgentTaskStatus = (status: SubAgentRunStatus): AgentTaskStatus => {
  if (status === 'running') return 'RUNNING';
  if (status === 'completed') return 'COMPLETED';
  if (status === 'failed') return 'FAILED';
  if (status === 'cancelled') return 'CANCELLED';
  if (status === 'timed_out') return 'TIMED_OUT';
  // approval_required(无法在子代理内授权)与 interrupted 一样按 INTERRUPTED 收口
  return 'INTERRUPTED';
};

const copyRunWithResult = (
  run: SubAgentRun, result: SubAgentResult, displayText: string, updatedAtMs: number,
): SubAgentRun => ({
  runId: run.runId,
  parentConversationId: run.parentConversationId,
  definition: run.definition,
  task: run.task,
  status: result.status,
  result,
  displayText: displayText.trim().length > 0 ? displayText : run.displayText,
  transcriptPath: run.transcriptPath,
  startedAtMs: run.startedAtMs,
  updatedAtMs,
});

export class SubAgentManager {
  private readonly deps: SubAgentManagerDeps;
  private readonly runs: Map<string, RuntimeRun> = new Map<string, RuntimeRun>();
  private readonly admissionMutex: AsyncMutex = new AsyncMutex();
  private readonly liveTexts: Map<string, string> = new Map<string, string>();
  private readonly liveParts: Map<string, UIMessagePart[]> = new Map<string, UIMessagePart[]>();
  private readonly textListeners: Map<string, Set<(text: string) => void>> =
    new Map<string, Set<(text: string) => void>>();
  private readonly partsListeners: Map<string, Set<(parts: UIMessagePart[]) => void>> =
    new Map<string, Set<(parts: UIMessagePart[]) => void>>();

  constructor(deps: SubAgentManagerDeps) {
    this.deps = deps;
  }

  async start(
    parentConversationId: string, input: JsonObject, parentTools: AgentTool[],
    runner: SubAgentRunner = this.deps.runner,
  ): Promise<JsonObject> {
    const setting: SubAgentRuntimeSetting = this.deps.getSubAgentSetting();
    if (!setting.enabled) {
      return errorPayload('subagent_disabled', 'Subagent experimental mode is disabled.');
    }

    const parentToolNames: Set<string> = new Set<string>();
    parentTools.forEach((tool: AgentTool): void => { parentToolNames.add(tool.name); });
    let task: SubAgentTaskSpec;
    try {
      task = subAgentParseTask(input);
    } catch (error) {
      return errorPayload('invalid_task', errorMessage(error));
    }

    let definition: SubAgentDefinition;
    try {
      definition = subAgentResolveDefinition(input, setting, parentToolNames).definition;
    } catch (error) {
      return errorPayload('invalid_subagent', errorMessage(error));
    }

    let effectiveDefinition: SubAgentDefinition;
    if (definition.dynamic) {
      try {
        subAgentValidateToolAllowlist(
          new Set<string>(definition.toolAllowlist), parentToolNames);
      } catch (error) {
        return errorPayload('invalid_tools', errorMessage(error));
      }
      effectiveDefinition = definition;
    } else {
      const allowed: string[] = definition.toolAllowlist.filter(
        (toolName: string): boolean => parentToolNames.has(toolName));
      effectiveDefinition = copyDefinition(definition, allowed, definition.dynamic);
    }
    if (!effectiveDefinition.dynamic && effectiveDefinition.toolAllowlist.length === 0) {
      return errorPayload(
        'no_allowed_tools',
        `No allowed tools are currently available for subagent ${definition.id}.`,
      );
    }

    let historyGrant: SessionAccessGrant | null = null;
    // 只撤销本次 start() 新铸造的 grant;session_grant_id 带入的外部 grant
    // 所有权不在本运行,失败时不得误删
    let ownsHistoryGrant: boolean = false;
    if (isHistoryReader(effectiveDefinition) && task.sourceSessionIds.length > 0) {
      historyGrant = this.deps.sessionAccessGrantStore.create(
        task.sourceSessionIds,
        effectiveDefinition.outputBudgetChars * 4,
        task.objective,
        parentConversationId,
      );
      ownsHistoryGrant = true;
    } else if (task.sessionGrantId.trim().length > 0) {
      historyGrant = this.deps.sessionAccessGrantStore.get(task.sessionGrantId);
    }
    const effectiveTask: SubAgentTaskSpec = historyGrant !== null &&
      task.sessionGrantId.trim().length === 0
      ? copyTaskWithGrant(task, historyGrant.grantId)
      : task;

    const allowedTools: AgentTool[] = [];
    parentTools.forEach((tool: AgentTool): void => {
      if (tool.name.startsWith('subagent_')) return;
      if (!effectiveDefinition.toolAllowlist.includes(tool.name)) return;
      if (HISTORY_FULL_READ_TOOLS.has(tool.name) && historyGrant !== null) {
        allowedTools.push(copyToolForHistoryGrant(tool));
      } else {
        allowedTools.push(tool);
      }
    });

    const nowMs: number = this.deps.now();
    const runId: string = this.deps.newId();
    const transcriptPath: string = this.deps.transcriptPathForRun(runId);
    const run: SubAgentRun = makeSubAgentRun({
      runId,
      parentConversationId,
      definition: effectiveDefinition,
      task: effectiveTask,
      status: 'running',
      transcriptPath,
      startedAtMs: nowMs,
    });
    const runtimeRun: RuntimeRun = {
      snapshot: run,
      runner,
      // controller 在创建 runtimeRun 时即存在:admission 后任意 await 窗口内
      // cancel() 都能立刻 abort,launchRun 启动前也会复查 signal
      controller: new LocalAbortController(),
      terminalMutex: new AsyncMutex(),
      taskRegistered: false,
      pendingTerminalTaskUpdate: null,
      startedTranscriptWritten: false,
      pendingFinishedTranscript: null,
    };
    const admissionError: AdmissionError | null = await this.admissionMutex.withLock(
      (): Promise<AdmissionError | null> => {
        const runLimit: number = Math.max(setting.maxConcurrentRuns, 1);
        let running: number = 0;
        this.runs.forEach((candidate: RuntimeRun): void => {
          if (subAgentRunStatusRunning(candidate.snapshot.status)) running++;
        });
        if (running >= runLimit) {
          return Promise.resolve({
            code: 'too_many_subagents',
            message: 'Subagent concurrency limit reached.',
          });
        }
        if (effectiveDefinition.dynamic && running >= runLimit) {
          return Promise.resolve({
            code: 'too_many_dynamic_subagents',
            message: 'Dynamic subagent per-turn limit reached.',
          });
        }
        if (effectiveDefinition.dynamic) {
          const usedNames: Set<string> = new Set<string>();
          this.runs.forEach((candidate: RuntimeRun): void => {
            if (subAgentRunStatusRunning(candidate.snapshot.status)) {
              usedNames.add(candidate.snapshot.definition.name);
            }
          });
          // 分配与入库在同一 admission 锁内，初始化的 await 不会让并发重名。
          const namedDefinition: SubAgentDefinition = copyDefinition(
            effectiveDefinition, effectiveDefinition.toolAllowlist, true);
          namedDefinition.name = subAgentDynamicName(
            effectiveDefinition.name, `${effectiveTask.objective}|${runId}`, usedNames);
          run.definition = namedDefinition;
        }
        this.runs.set(runId, runtimeRun);
        return Promise.resolve(null);
      },
    );
    if (admissionError !== null) {
      // admission 拒绝:撤销刚铸造的 history grant,不留无主授权
      if (historyGrant !== null && ownsHistoryGrant) {
        this.deps.sessionAccessGrantStore.revoke(historyGrant.grantId);
      }
      return errorPayload(admissionError.code, admissionError.message);
    }

    try {
      const outputExists: boolean = await this.deps.transcript.exists(transcriptPath);
      await this.deps.agentTaskStore.register(
        this.toAgentTaskSnapshot(run, outputExists),
        async (): Promise<boolean> => {
          await this.cancel(runId);
          return true;
        },
      );
      runtimeRun.taskRegistered = true;
      const pendingTaskUpdate: TerminalTaskUpdate | null = runtimeRun.pendingTerminalTaskUpdate;
      runtimeRun.pendingTerminalTaskUpdate = null;
      if (pendingTaskUpdate !== null) {
        this.scheduleTaskUpdate(runId, pendingTaskUpdate);
      }
      await appendSubAgentTranscriptEvent(
        this.deps.transcript, transcriptPath, 'started', this.deps.now(), this.runToPayload(run));
      runtimeRun.startedTranscriptWritten = true;
      const pendingFinished: SubAgentRun | null = runtimeRun.pendingFinishedTranscript;
      runtimeRun.pendingFinishedTranscript = null;
      if (pendingFinished !== null) {
        await this.appendFinishedTranscript(pendingFinished);
      }
    } catch (error) {
      // 初始化失败:撤销自有 grant + 尽力收口 failed,不留幽灵 running
      if (historyGrant !== null && ownsHistoryGrant) {
        this.deps.sessionAccessGrantStore.revoke(historyGrant.grantId);
      }
      try {
        await this.finish(runId, makeSubAgentResult({ status: 'failed', error: errorMessage(error) }));
      } catch { /* 收口尽力而为 */ }
      throw error;
    }

    this.liveTexts.set(runId, '');
    this.liveParts.set(runId, []);
    this.textListeners.set(runId, new Set<(text: string) => void>());
    this.partsListeners.set(runId, new Set<(parts: UIMessagePart[]) => void>());
    this.capLiveState();

    // 顶层 catch:finish/transcript 等后续步骤的 rejection 也必须收口,防 unhandled
    this.launchRun(runtimeRun, allowedTools).catch(async (error: unknown): Promise<void> => {
      try {
        await this.finish(runId, makeSubAgentResult({ status: 'failed', error: errorMessage(error) }));
      } catch { /* 收口尽力而为 */ }
    });
    return this.runToPayload(run);
  }

  async read(runId: string): Promise<JsonObject> {
    const runtimeRun: RuntimeRun | undefined = this.runs.get(runId);
    if (runtimeRun === undefined) return this.readMissingRun(runId);
    return this.runToPayload(runtimeRun.snapshot);
  }

  async wait(runId: string, waitTimeoutMs: number, signal?: AbortSignalLike): Promise<JsonObject> {
    const cancelled = (): Error => {
      const error = new Error('subagent wait aborted');
      error.name = 'AbortError';
      return error;
    };
    if (signal?.aborted) throw cancelled();
    const timeout: number = Math.min(Math.max(waitTimeoutMs, 0), 60000);
    const deadline: number = this.deps.now() + timeout;
    let onAbort: (() => void) | null = null;
    const aborted: Promise<void> = new Promise<void>((_resolve, reject): void => {
      onAbort = (): void => { reject(cancelled()); };
      signal?.addEventListener?.('abort', onAbort);
    });
    try {
      while (this.deps.now() < deadline) {
        if (signal?.aborted) throw cancelled();
        const runtimeRun: RuntimeRun | undefined = this.runs.get(runId);
        if (runtimeRun === undefined) return this.readMissingRun(runId);
        if (!subAgentRunStatusRunning(runtimeRun.snapshot.status)) {
          return this.runToPayload(runtimeRun.snapshot);
        }
        await Promise.race([this.deps.sleep(200), aborted]);
      }
      if (signal?.aborted) throw cancelled();
      return this.read(runId);
    } finally {
      if (onAbort !== null) signal?.removeEventListener?.('abort', onAbort);
    }
  }

  async cancel(runId: string): Promise<JsonObject> {
    const runtimeRun: RuntimeRun | undefined = this.runs.get(runId);
    if (runtimeRun === undefined) return this.readMissingRun(runId);
    if (runtimeRun.controller !== null) runtimeRun.controller.abort();
    await this.finish(runId, makeSubAgentResult({
      status: 'cancelled',
      summary: 'Subagent run was cancelled.',
    }));
    return this.runToPayload(runtimeRun.snapshot);
  }

  listBuiltIns(): SubAgentDefinition[] {
    const setting: SubAgentRuntimeSetting = this.deps.getSubAgentSetting();
    const builtIns: SubAgentDefinition[] = setting.mode === 'smart_dynamic'
      ? []
      : SUB_AGENT_BUILT_INS.map((definition: SubAgentDefinition): SubAgentDefinition =>
        subAgentApplyOverride(definition, setting.overrides.get(definition.id) ?? null));
    const customDefinitions: SubAgentDefinition[] = setting.mode === 'smart_dynamic'
      ? setting.customDefinitions.map((definition: SubAgentDefinition): SubAgentDefinition => {
        const tools: string[] = definition.toolAllowlist.filter(
          (toolName: string): boolean => DEFAULT_DYNAMIC_READ_ONLY_TOOLS.has(toolName));
        return copyDefinition(definition, tools, true);
      })
      : setting.customDefinitions;
    return builtIns.concat(customDefinitions);
  }

  runtimeSummary(): JsonObject {
    const setting: SubAgentRuntimeSetting = this.deps.getSubAgentSetting();
    let running: number = 0;
    this.runs.forEach((runtimeRun: RuntimeRun): void => {
      if (subAgentRunStatusRunning(runtimeRun.snapshot.status)) running++;
    });
    return {
      enabled: setting.enabled,
      mode: setting.mode,
      allow_dynamic_subagents: setting.allowDynamicSubAgents,
      max_concurrent_runs: setting.maxConcurrentRuns,
      dynamic_run_limit: setting.maxConcurrentRuns,
      tool_profiles: TOOL_PROFILES,
      max_depth: 1,
      timeout_ms: setting.timeoutMs,
      max_turns: setting.maxTurns,
      output_budget_chars: setting.outputBudgetChars,
      running,
    };
  }

  runtimeMode(): SubAgentMode {
    return this.deps.getSubAgentSetting().mode;
  }

  snapshot(runId: string): SubAgentRun | null {
    const runtimeRun: RuntimeRun | undefined = this.runs.get(runId);
    return runtimeRun !== undefined ? runtimeRun.snapshot : null;
  }

  // 跨会话活动条:仍在运行的 run(内存态,按开始时间倒序;iOS activityBar 数据源)
  listActive(): SubAgentRun[] {
    const out: SubAgentRun[] = [];
    this.runs.forEach((runtimeRun: RuntimeRun): void => {
      if (subAgentRunStatusRunning(runtimeRun.snapshot.status)) out.push(runtimeRun.snapshot);
    });
    return out.sort((a: SubAgentRun, b: SubAgentRun): number => b.startedAtMs - a.startedAtMs);
  }

  subscribeLiveText(
    runId: string, listener: (text: string) => void,
  ): (() => void) | null {
    const current: string | undefined = this.liveTexts.get(runId);
    const listeners: Set<(text: string) => void> | undefined = this.textListeners.get(runId);
    if (current === undefined || listeners === undefined) return null;
    listener(current);
    listeners.add(listener);
    return (): void => { listeners.delete(listener); };
  }

  subscribeLiveParts(
    runId: string, listener: (parts: UIMessagePart[]) => void,
  ): (() => void) | null {
    const current: UIMessagePart[] | undefined = this.liveParts.get(runId);
    const listeners: Set<(parts: UIMessagePart[]) => void> | undefined =
      this.partsListeners.get(runId);
    if (current === undefined || listeners === undefined) return null;
    listener(current);
    listeners.add(listener);
    return (): void => { listeners.delete(listener); };
  }

  private async launchRun(runtimeRun: RuntimeRun, allowedTools: AgentTool[]): Promise<void> {
    const run: SubAgentRun = runtimeRun.snapshot;
    // controller 在 start() 创建 runtimeRun 时即已存在;启动前被取消(admission 后
    // 的 await 窗口内 cancel)则不再启动 runner,避免 finish(cancelled) 后又开跑
    const controller: LocalAbortController | null = runtimeRun.controller;
    if (controller === null || controller.signal.aborted) return;
    // 超时标记:abort() 会同步触发 runner 以 AbortError 竞速先 settle,
    // 不能靠异常类型区分超时/用户取消,必须用显式 flag
    const timeoutFlag: TimeoutFlagHolder = { timedOut: false };
    let result: SubAgentResult;
    try {
      result = await this.runWithTimeout(
        runtimeRun.runner.run(
          run.definition,
          run.task,
          scopedSubAgentTools(allowedTools),
          (text: string): void => { this.updateLiveText(run.runId, text); },
          (parts: UIMessagePart[]): void => { this.updateLiveParts(run.runId, parts); },
          controller.signal,
        ),
        run.definition.timeoutMs,
        controller,
        timeoutFlag,
      );
    } catch (error) {
      if (timeoutFlag.timedOut) {
        // 超时必须收口:否则 run/task 永久 RUNNING 并占用并发额度
        result = makeSubAgentResult({
          status: 'timed_out',
          summary: 'Subagent run timed out.',
        });
      } else if (controller.signal.aborted) {
        // 用户取消:cancel() 已 finish(cancelled)(abort 同步置位,由本 signal
        // 触发的 AbortError 必然伴随 aborted=true;独立的 AbortError 走 failed)
        return;
      } else {
        result = makeSubAgentResult({ status: 'failed', error: errorMessage(error) });
      }
    }
    await this.finish(run.runId, result, this.liveTexts.get(run.runId) ?? '');
  }

  private runWithTimeout(
    run: Promise<SubAgentResult>, timeoutMs: number, controller: LocalAbortController,
    timeoutFlag: TimeoutFlagHolder,
  ): Promise<SubAgentResult> {
    let timer: ReturnType<typeof setTimeout> | null = null;
    const timeout: Promise<SubAgentResult> = new Promise<SubAgentResult>(
      (_resolve, reject): void => {
        timer = setTimeout((): void => {
          timeoutFlag.timedOut = true;
          controller.abort();
          reject(new SubAgentTimeoutCancellation());
        }, timeoutMs);
      },
    );
    return Promise.race([run, timeout]).then(
      (result: SubAgentResult): SubAgentResult => {
        if (timer !== null) clearTimeout(timer);
        return result;
      },
      (error: unknown): Promise<SubAgentResult> => {
        if (timer !== null) clearTimeout(timer);
        return Promise.reject(error);
      },
    );
  }

  private async finish(
    runId: string, result: SubAgentResult, displayText: string = '',
  ): Promise<void> {
    const runtimeRun: RuntimeRun | undefined = this.runs.get(runId);
    if (runtimeRun === undefined) return;
    const next: SubAgentRun | null = await runtimeRun.terminalMutex.withLock(
      (): Promise<SubAgentRun | null> => {
        const current: SubAgentRun = runtimeRun.snapshot;
        if (!isLiveRunStatus(current.status)) return Promise.resolve(null);
        const updated: SubAgentRun = copyRunWithResult(
          current, result, displayText, this.deps.now());
        runtimeRun.snapshot = updated;
        return Promise.resolve(updated);
      },
    );
    if (next === null) return;

    const status: AgentTaskStatus = statusToAgentTaskStatus(next.status);
    const summary: string = result.summary.trim().length > 0
      ? result.summary
      : result.findings.join('; ').slice(0, 1000);
    // approval_required 也必须带可见原因(任务板显示 INTERRUPTED 而非裸 RUNNING):
    //   运行对象仍 live,用户可再 cancel();任务板给出挂起原因与错误码(R14)。
    const explicitError: string = result.error.trim();
    const approvalReason: string = next.status === 'approval_required'
      ? 'Subagent stopped because it requested approval, which cannot be granted inside the subagent run.'
      : '';
    const error: string | null = explicitError.length > 0
      ? explicitError
      : (approvalReason.length > 0 ? approvalReason : null);
    const lastErrorCode: string | null = explicitError.length === 0 && approvalReason.length > 0
      ? 'subagent_approval_required'
      : null;
    const terminalTaskUpdate: TerminalTaskUpdate = {
      status,
      summary,
      error,
      // approval_required 运行对象仍 live 且 start() 已注册取消回调 → 任务板保留
      //   取消能力(看板取消可经回调收口);其余终态一律 false(R14)。
      cancelCapability: next.status === 'approval_required',
      lastErrorCode,
    };
    if (runtimeRun.taskRegistered) {
      this.scheduleTaskUpdate(runId, terminalTaskUpdate);
    } else {
      runtimeRun.pendingTerminalTaskUpdate = terminalTaskUpdate;
    }
    if (runtimeRun.startedTranscriptWritten) {
      await this.appendFinishedTranscript(next);
    } else {
      runtimeRun.pendingFinishedTranscript = next;
    }
  }

  private scheduleTaskUpdate(runId: string, update: TerminalTaskUpdate): void {
    void this.deps.agentTaskStore.update(runId, {
      status: update.status,
      summary: update.summary,
      error: update.error,
      lastErrorCode: update.lastErrorCode,
      cancelCapability: update.cancelCapability,
    }).catch((): void => {});
  }

  private appendFinishedTranscript(run: SubAgentRun): Promise<void> {
    return appendSubAgentTranscriptEvent(
      this.deps.transcript,
      run.transcriptPath,
      'finished',
      this.deps.now(),
      this.runToPayload(run, true),
    );
  }

  private updateLiveText(runId: string, text: string): void {
    if (!this.liveTexts.has(runId)) return;
    this.liveTexts.set(runId, text);
    const listeners: Set<(value: string) => void> | undefined = this.textListeners.get(runId);
    if (listeners === undefined) return;
    listeners.forEach((listener: (value: string) => void): void => { listener(text); });
  }

  private updateLiveParts(runId: string, parts: UIMessagePart[]): void {
    if (!this.liveParts.has(runId)) return;
    this.liveParts.set(runId, parts);
    const listeners: Set<(value: UIMessagePart[]) => void> | undefined =
      this.partsListeners.get(runId);
    if (listeners === undefined) return;
    listeners.forEach((listener: (value: UIMessagePart[]) => void): void => { listener(parts); });
  }

  private capLiveState(): void {
    if (this.liveTexts.size <= LIVE_STATE_CAP && this.liveParts.size <= LIVE_STATE_CAP) return;
    const ids: string[] = [];
    this.liveTexts.forEach((_value: string, id: string): void => {
      if (!ids.includes(id)) ids.push(id);
    });
    this.liveParts.forEach((_value: UIMessagePart[], id: string): void => {
      if (!ids.includes(id)) ids.push(id);
    });
    const candidates: Array<[string, number]> = [];
    ids.forEach((id: string): void => {
      const runtimeRun: RuntimeRun | undefined = this.runs.get(id);
      if (runtimeRun === undefined) {
        candidates.push([id, 0]);
      } else if (!subAgentRunStatusRunning(runtimeRun.snapshot.status)) {
        candidates.push([id, runtimeRun.snapshot.updatedAtMs]);
      }
    });
    candidates.sort((left: [string, number], right: [string, number]): number => left[1] - right[1]);
    const toDrop: number = Math.max(this.liveTexts.size, this.liveParts.size) - LIVE_STATE_CAP;
    candidates.slice(0, toDrop).forEach((candidate: [string, number]): void => {
      const id: string = candidate[0];
      this.liveTexts.delete(id);
      this.liveParts.delete(id);
      this.textListeners.delete(id);
      this.partsListeners.delete(id);
    });
  }

  private async readMissingRun(runId: string): Promise<JsonObject> {
    const transcriptPath: string = this.deps.transcriptPathForRun(runId);
    if (await this.deps.transcript.exists(transcriptPath)) {
      return {
        status: 'interrupted',
        run_id: runId,
        transcript_available: true,
        error: 'Subagent run is no longer active in memory.',
      };
    }
    return errorPayload('not_found', `Unknown subagent run_id: ${runId}`);
  }

  private runToPayload(run: SubAgentRun, includeDisplayText: boolean = false): JsonObject {
    const payload: JsonObject = {
      status: run.status,
      run_id: run.runId,
      subagent_id: run.definition.id,
      subagent_name: run.definition.name,
      dynamic: run.definition.dynamic,
      task_objective: run.task.objective.slice(0, 1000),
      started_at_ms: run.startedAtMs,
      updated_at_ms: run.updatedAtMs,
    };
    if (run.task.sessionGrantId.trim().length > 0) {
      payload['session_grant_id'] = run.task.sessionGrantId;
    }
    if (run.result !== null) {
      payload['result'] = JSON.stringify(resultToWireJson(run.result));
    }
    if (run.displayText.trim().length > 0) {
      payload['display_text_chars'] = run.displayText.length;
      if (includeDisplayText) payload['display_text'] = run.displayText;
    }
    return payload;
  }

  private toAgentTaskSnapshot(run: SubAgentRun, outputExists: boolean): AgentTaskSnapshot {
    const status: AgentTaskStatus = statusToAgentTaskStatus(run.status);
    return makeAgentTaskSnapshot({
      taskId: run.runId,
      type: 'subagent',
      title: run.definition.name,
      sourceConversationId: run.parentConversationId,
      status,
      queueState: agentTaskStatusToQueueState(status, 'subagent'),
      outputPath: run.transcriptPath,
      outputRef: makeAgentTaskOutputRef({
        type: 'transcript',
        path: run.transcriptPath,
        exists: outputExists,
      }),
      retryPolicy: makeAgentTaskRetryPolicy({
        retryable: false,
        requiresApproval: false,
        maxRetries: 1,
        reason: 'Sub Agent retry starts a new isolated run from the original task spec.',
      }),
      sourceToolName: 'subagent_start',
      createdAtMs: run.startedAtMs,
      updatedAtMs: run.updatedAtMs,
      cancelCapability: subAgentRunStatusRunning(run.status),
      summary: run.task.objective.slice(0, 1000),
    });
  }
}
