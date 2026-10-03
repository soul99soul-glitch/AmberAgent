// tool_dispatcher — 工具执行调度器 + 调用 hooks + 失败归一(D-056)
//
// Android 基准:
//   - app/.../feature/runtime/AgentToolDispatcher.kt(全文 372 行)
//   - app/.../feature/runtime/ToolInvocationHooks.kt(全文 118 行)
//   - feature/runtime/api ToolFailure.kt(全文 33 行)
// 偏差登记:
//   - kotlinx Json → 原生 JSON;CancellationException → AbortError(err.name)
//   - ToolFailure.isRecoverableToolFailure:VirtualMachineError/ThreadDeath 无 JS
//     对应物,JS Error 恒 recoverable=true(结构与文案保持)
//   - coroutine async/awaitAll → Promise.all;日志经注入 log 回调(域层零平台依赖)

import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePart, UIMessagePartText, UIMessagePartTool } from './message.ts';
import type { AgentTool } from './tool.ts';
import type { ToolInvocationPolicy } from './tool_policy.ts';
import { toolInvocationPolicyFromText } from './tool_policy.ts';
import type {
  PermissionDecision, ToolInvocationContext,
} from './tool_permission.ts';
import {
  PermissionDecisionResolver, permissionDecisionTraceToJson,
} from './tool_permission.ts';
import type { GenerationRetrySetting, GenerationRetryDecision } from './generation_retry.ts';
import { decideGenerationRetry, makeGenerationRetrySetting } from './generation_retry.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';

const textPart = (text: string): UIMessagePartText => ({ type: 'text', text, metadata: null });

// ===== ToolFailure(ToolFailure.kt 全文) =====

// sanitizedToolFailureMessage(:15-27):首个非空白行 trim take(360) → 去栈帧 →
//   压缩空白 → 空回退
export const sanitizedToolFailureMessage = (error: Error): string => {
  const lines: string[] = (error.message ?? '').split('\n');
  let raw: string | null = null;
  for (const line of lines) {
    if (line.trim().length > 0) {
      raw = line.trim().slice(0, 360);
      break;
    }
  }
  if (raw === null) {
    raw = error.name.length > 0 ? error.name : 'Tool execution failed';
  }
  const cleaned: string = raw
    .replace(/\bat\s+[\w.$]+\([^)]*\)/g, '')
    .replace(/\s+/g, ' ')
    .trim();
  return cleaned.length > 0 ? cleaned : 'Tool execution failed';
};

// isRecoverableToolFailure(:29-33):JS 无 VirtualMachineError/ThreadDeath → true
export const isRecoverableToolFailure = (_error: Error): boolean => true;

// toAgentToolFailurePayload(:9-13)
export const toAgentToolFailurePayload = (error: Error): JsonObject => ({
  status: 'failed',
  message: sanitizedToolFailureMessage(error),
  recoverable: isRecoverableToolFailure(error),
});

export const toAgentToolFailureJson = (error: Error): string =>
  JSON.stringify(toAgentToolFailurePayload(error));

// A caller's explicit persistence barrier must stop execution, unlike observational hooks.
export class ToolInvocationBarrierError extends Error {
  constructor(message: string) { super(message); this.name = 'ToolInvocationBarrierError'; }
}

// ===== ToolInvocationHooks(ToolInvocationHooks.kt 全文) =====

export interface ToolInvocationRequest {
  tool: UIMessagePartTool;
  toolDef: AgentTool | null;
  parsedArgs: JsonValue | null;
  permissionDecision: PermissionDecision;
  invocationContext: ToolInvocationContext;
  startedAtMs: number;
}

export interface ToolInvocationResult {
  output: UIMessagePart[];
  metadata: JsonObject;
}

export interface ToolInvocationHook {
  before?: (request: ToolInvocationRequest) => Promise<ToolInvocationResult | null>;
  after?: (
    request: ToolInvocationRequest, result: ToolInvocationResult,
  ) => Promise<ToolInvocationResult>;
  onError?: (
    request: ToolInvocationRequest, error: Error,
  ) => Promise<ToolInvocationResult | null>;
}

// ToolTraceHook(:49-74):log 回调注入(Android Log.i 等价;域层零平台依赖)
export const createToolTraceHook = (
  log: (message: string) => void, nowMs: () => number = Date.now,
): ToolInvocationHook => ({
  before: (request: ToolInvocationRequest): Promise<ToolInvocationResult | null> => {
    log(`start ${request.tool.toolName} action=${request.permissionDecision.action}`);
    return Promise.resolve(null);
  },
  after: (
    request: ToolInvocationRequest, result: ToolInvocationResult,
  ): Promise<ToolInvocationResult> => {
    log(`success ${request.tool.toolName} durationMs=${nowMs() - request.startedAtMs}`);
    return Promise.resolve(result);
  },
  onError: (
    request: ToolInvocationRequest, error: Error,
  ): Promise<ToolInvocationResult | null> => {
    log(`failed ${request.tool.toolName} durationMs=${nowMs() - request.startedAtMs} error=${error.message}`);
    return Promise.resolve(null);
  },
});

// ToolArgumentValidationHook(:76-101)
const validationFailure = (
  request: ToolInvocationRequest, message: string,
): ToolInvocationResult => ({
  output: [textPart(JSON.stringify({
    status: 'failed',
    message,
    recoverable: false,
    permission_trace: permissionDecisionTraceToJson(request.permissionDecision.trace),
  }))],
  metadata: {},
});

export const createToolArgumentValidationHook = (): ToolInvocationHook => ({
  before: (request: ToolInvocationRequest): Promise<ToolInvocationResult | null> => {
    if (request.toolDef === null) {
      return Promise.resolve(validationFailure(request, `Tool ${request.tool.toolName} not found`));
    }
    if (request.parsedArgs === null) {
      return Promise.resolve(validationFailure(request, 'Tool arguments were not parsed'));
    }
    if (typeof request.parsedArgs !== 'object' || Array.isArray(request.parsedArgs)) {
      return Promise.resolve(validationFailure(request, 'Tool arguments must be a JSON object'));
    }
    return Promise.resolve(null);
  },
});

// ToolFailureNormalizeHook(:103-118)
export const createToolFailureNormalizeHook = (): ToolInvocationHook => ({
  onError: (request: ToolInvocationRequest, error: Error): Promise<ToolInvocationResult | null> => {
    const payload: JsonObject = toAgentToolFailurePayload(error);
    payload['permission_trace'] = permissionDecisionTraceToJson(request.permissionDecision.trace);
    return Promise.resolve({
      output: [textPart(JSON.stringify(payload))],
      metadata: {},
    });
  },
});

// defaultToolInvocationHooks(:43-47)
export const defaultToolInvocationHooks = (
  log: (message: string) => void = (): void => {},
): ToolInvocationHook[] => [
  createToolTraceHook(log),
  createToolArgumentValidationHook(),
  createToolFailureNormalizeHook(),
];

// ===== AgentToolDispatcher(AgentToolDispatcher.kt 全文) =====

export const TOOL_DISPLAY_METADATA_KEYS: string[] = ['display_title'];

// withoutToolDisplayMetadata(:357-361)
const withoutToolDisplayMetadata = (input: JsonValue): JsonValue => {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return input;
  const obj: JsonObject = input as JsonObject;
  if (!Object.keys(obj).some((k: string): boolean => TOOL_DISPLAY_METADATA_KEYS.includes(k))) {
    return input;
  }
  const out: JsonObject = {};
  for (const k of Object.keys(obj)) {
    if (!TOOL_DISPLAY_METADATA_KEYS.includes(k)) out[k] = obj[k];
  }
  return out;
};

const isAbortError = (e: unknown): boolean =>
  e instanceof Error && e.name === 'AbortError';

export type AutoApprovalReview = (part: UIMessagePartTool, decision: PermissionDecision,
  signal?: AbortSignalLike) => Promise<string[]>;

export interface AgentToolDispatcherDeps {
  autoApprovalReview?: AutoApprovalReview;
  resolver?: PermissionDecisionResolver;
  hooks?: ToolInvocationHook[];
  log?: (message: string) => void;
  nowMs?: () => number;
  sleep?: (ms: number) => Promise<void>;
}

// (input, result) 配对:input 为批次中的原 part 引用,是回写定位的唯一稳定键
// (空 toolCallId / 真子集批次下 id 与位置都无法区分;R08)。
export interface ToolBatchResultPair {
  input: UIMessagePartTool;
  result: UIMessagePartTool;
}

export class AgentToolDispatcher {
  private readonly resolver: PermissionDecisionResolver;
  private readonly autoApprovalReview: AutoApprovalReview | undefined;
  private readonly reviewedCalls: Map<string, Promise<string[]>> = new Map();
  private readonly hooks: ToolInvocationHook[];
  private readonly log: (message: string) => void;
  private readonly nowMs: () => number;
  private readonly sleep: (ms: number) => Promise<void>;

  constructor(deps: AgentToolDispatcherDeps = {}) {
    this.resolver = deps.resolver ?? new PermissionDecisionResolver();
    this.autoApprovalReview = deps.autoApprovalReview;
    this.hooks = deps.hooks ?? [];
    this.log = deps.log ?? ((): void => {});
    this.nowMs = deps.nowMs ?? Date.now;
    this.sleep = deps.sleep
      ?? ((ms: number): Promise<void> => new Promise((resolve): void => {
        setTimeout(resolve, ms);
      }));
  }

  public shouldPauseForApproval(
    toolDef: AgentTool | null,
    tool: UIMessagePartTool,
    autoApproveTools: boolean,
    autoApproveHighRiskTools: boolean = false,
    autoApprovedToolNames: string[] = [],
  ): boolean {
    return this.resolver.shouldPauseForApproval(
      toolDef, tool, autoApproveTools, autoApproveHighRiskTools, autoApprovedToolNames,
    );
  }

  public resolveDecision(
    toolDef: AgentTool | null,
    tool: UIMessagePartTool,
    autoApproveTools: boolean,
    autoApproveHighRiskTools: boolean = false,
    autoApprovedToolNames: string[] = [],
    invocationContext: ToolInvocationContext = 'normal',
  ): PermissionDecision {
    return this.resolver.resolve(
      toolDef, tool, autoApproveTools, autoApproveHighRiskTools,
      autoApprovedToolNames, invocationContext,
    );
  }

  public async resolveReviewedDecision(
    toolDef: AgentTool | null, tool: UIMessagePartTool, autoApproveTools: boolean,
    autoApproveHighRiskTools: boolean = false, autoApprovedToolNames: string[] = [],
    invocationContext: ToolInvocationContext = 'normal', signal?: AbortSignalLike,
  ): Promise<PermissionDecision> {
    const decision = this.resolveDecision(toolDef, tool, autoApproveTools,
      autoApproveHighRiskTools, autoApprovedToolNames, invocationContext);
    const policy = decision.trace.policy;
    if (decision.action !== 'allow' || tool.approvalState.type !== 'auto'
      || this.autoApprovalReview === undefined || signal?.aborted
      || (policy?.mutates === false && policy.risk === 'normal')) return decision;
    const key = JSON.stringify([tool.toolCallId, tool.toolName, tool.input]);
    let review = this.reviewedCalls.get(key);
    if (review === undefined) {
      review = this.autoApprovalReview(tool, decision, signal).catch(() => [] as string[]);
      this.reviewedCalls.set(key, review);
    }
    const reasons = await review;
    if (signal?.aborted || reasons.length === 0) return decision;
    const reason = '自动批准复核：' + reasons.join('、');
    return { ...decision, action: 'ask', source: 'jev_auto_approval', reason,
      trace: { ...decision.trace, action: 'ask', source: 'jev_auto_approval', reason } };
  }

  // executeBatch(:63-117):prefetch 复用 → 并行(全部 canRunInParallel)或顺序 →
  //   按输入序重排,并以 (input,result) 配对返回。配对保留**输入 part 对象身份**:
  //   并行无 id 工具 toolCallId 同为 '' 或批次只是真子集时,调用方按身份回写,
  //   不按 '' / 位置游标猜(R08)。signal:abort 后不再启动新工具副作用。
  public async executeBatchPairs(
    tools: UIMessagePartTool[],
    toolDefinitions: Map<string, AgentTool>,
    autoApproveTools: boolean,
    autoApproveHighRiskTools: boolean = false,
    autoApprovedToolNames: string[] = [],
    invocationContext: ToolInvocationContext = 'normal',
    prefetchedTools: Map<string, UIMessagePartTool> = new Map<string, UIMessagePartTool>(),
    retrySetting: GenerationRetrySetting = makeGenerationRetrySetting({ enabled: false }),
    signal: AbortSignalLike | undefined = undefined,
  ): Promise<ToolBatchResultPair[]> {
    const isAborted = (): boolean => signal !== undefined && signal.aborted;
    const reusedPairs: ToolBatchResultPair[] = [];
    const reusedInputs: Set<UIMessagePartTool> = new Set<UIMessagePartTool>();
    for (const tool of tools) {
      // prefetch 复用依赖 toolCallId;空 id 无法稳定命中,不复用
      if (tool.toolCallId.length === 0) continue;
      const prefetched: UIMessagePartTool | undefined = prefetchedTools.get(tool.toolCallId);
      if (prefetched !== undefined
        && prefetched.toolName === tool.toolName
        && prefetched.input === tool.input
        && prefetched.output.length > 0) {
        reusedPairs.push({ input: tool, result: prefetched });
        reusedInputs.add(tool);
      }
    }
    const remaining: UIMessagePartTool[] = tools.filter(
      (t: UIMessagePartTool): boolean => !reusedInputs.has(t));
    // (input, result) 按执行序配对;result=null(被跳过/abort)不产生对
    const executedPairs: ToolBatchResultPair[] = [];
    if (remaining.length > 0) {
      const runOne = async (tool: UIMessagePartTool): Promise<UIMessagePartTool | null> => {
        if (isAborted()) return null;
        try {
          return await this.execute(
            tool, toolDefinitions.get(tool.toolName) ?? null,
            autoApproveTools, autoApproveHighRiskTools, autoApprovedToolNames,
            invocationContext, retrySetting, signal);
        } catch (e) {
          // abort 打断重试等待:丢弃该工具(保持未执行,留给停止收口),不外抛
          if (isAbortError(e) && isAborted()) return null;
          throw e;
        }
      };
      if (remaining.length > 1 && remaining.every(
        (t: UIMessagePartTool): boolean => this.canRunInParallel(t, toolDefinitions.get(t.toolName) ?? null))) {
        const results: (UIMessagePartTool | null)[] = await Promise.all(remaining.map(runOne));
        for (let i = 0; i < remaining.length; i++) {
          if (results[i] !== null) {
            executedPairs.push({ input: remaining[i], result: results[i] as UIMessagePartTool });
          }
        }
      } else {
        for (const tool of remaining) {
          const result: UIMessagePartTool | null = await runOne(tool);
          if (result !== null) executedPairs.push({ input: tool, result });
          if (isAborted()) break;
        }
      }
    }
    // 按 tools 原序重排(reused/executed 均保持输入相对序,双游标即可)
    const out: ToolBatchResultPair[] = [];
    let reusedCursor = 0;
    let pairCursor = 0;
    for (const tool of tools) {
      if (reusedInputs.has(tool)) {
        if (reusedCursor < reusedPairs.length) {
          out.push(reusedPairs[reusedCursor]);
          reusedCursor++;
        }
        continue;
      }
      while (pairCursor < executedPairs.length && executedPairs[pairCursor].input !== tool) pairCursor++;
      if (pairCursor < executedPairs.length) {
        out.push(executedPairs[pairCursor]);
        pairCursor++;
      }
    }
    return out;
  }

  // 兼容形:只取结果(既有调用方/测试);写回路径请用 executeBatchPairs 保留输入身份。
  public async executeBatch(
    tools: UIMessagePartTool[],
    toolDefinitions: Map<string, AgentTool>,
    autoApproveTools: boolean,
    autoApproveHighRiskTools: boolean = false,
    autoApprovedToolNames: string[] = [],
    invocationContext: ToolInvocationContext = 'normal',
    prefetchedTools: Map<string, UIMessagePartTool> = new Map<string, UIMessagePartTool>(),
    retrySetting: GenerationRetrySetting = makeGenerationRetrySetting({ enabled: false }),
    signal: AbortSignalLike | undefined = undefined,
  ): Promise<UIMessagePartTool[]> {
    const pairs: ToolBatchResultPair[] = await this.executeBatchPairs(
      tools, toolDefinitions, autoApproveTools, autoApproveHighRiskTools,
      autoApprovedToolNames, invocationContext, prefetchedTools, retrySetting, signal);
    return pairs.map((pair: ToolBatchResultPair): UIMessagePartTool => pair.result);
  }

  // execute(:119-185):permission_trace 恒注入 metadata;approvalState 分支
  public async execute(
    tool: UIMessagePartTool,
    toolDef: AgentTool | null,
    autoApproveTools: boolean = false,
    autoApproveHighRiskTools: boolean = false,
    autoApprovedToolNames: string[] = [],
    invocationContext: ToolInvocationContext = 'normal',
    retrySetting: GenerationRetrySetting = makeGenerationRetrySetting({ enabled: false }),
    signal: AbortSignalLike | undefined = undefined,
  ): Promise<UIMessagePartTool | null> {
    if (signal !== undefined && signal.aborted) return null;
    const decision: PermissionDecision = await this.resolveReviewedDecision(
      toolDef, tool, autoApproveTools, autoApproveHighRiskTools,
      autoApprovedToolNames, invocationContext, signal);
    if (signal?.aborted) return null;
    const tracedTool: UIMessagePartTool = withPermissionTrace(
      tool, permissionDecisionTraceToJson(decision.trace));
    if (tool.approvalState.type === 'denied') {
      const reason: string = tool.approvalState.reason;
      return {
        ...tracedTool,
        output: [textPart(JSON.stringify({
          status: 'denied',
          message: `Tool execution denied by user. Reason: ${reason.trim().length > 0 ? reason : 'No reason provided'}`,
          permission_trace: permissionDecisionTraceToJson(decision.trace),
        }))],
      };
    }
    if (tool.approvalState.type === 'answered') {
      return { ...tracedTool, output: [textPart(tool.approvalState.answer)] };
    }
    if (tool.approvalState.type === 'pending') {
      return null;
    }
    if (decision.action === 'deny') {
      return {
        ...tracedTool,
        output: [textPart(JSON.stringify({
          status: 'failed',
          message: decision.reason,
          recoverable: false,
          permission_trace: permissionDecisionTraceToJson(decision.trace),
        }))],
      };
    }
    if (decision.action === 'ask') return { ...tracedTool, approvalState: { type: 'pending' } };
    return this.executeWithHooks(tracedTool, toolDef, decision, invocationContext, retrySetting, signal);
  }

  // executeWithHooks(:187-239)
  private async executeWithHooks(
    tool: UIMessagePartTool,
    toolDef: AgentTool | null,
    decision: PermissionDecision,
    invocationContext: ToolInvocationContext,
    retrySetting: GenerationRetrySetting,
    signal: AbortSignalLike | undefined = undefined,
  ): Promise<UIMessagePartTool> {
    let request: ToolInvocationRequest = {
      tool,
      toolDef,
      parsedArgs: null,
      permissionDecision: decision,
      invocationContext,
      startedAtMs: this.nowMs(),
    };
    try {
      const parsed: JsonValue = JSON.parse(
        tool.input.trim().length === 0 ? '{}' : tool.input) as JsonValue;
      const args: JsonValue = withoutToolDisplayMetadata(parsed);
      request = { ...request, parsedArgs: args };
      const beforeResult: ToolInvocationResult | null = await this.runBeforeHooks(request);
      if (beforeResult !== null) {
        return withHookMetadata({ ...tool, output: beforeResult.output }, beforeResult.metadata);
      }
      if (toolDef === null) {
        throw new Error(`Tool ${tool.toolName} not found`);
      }
      const resolved: AgentTool = toolDef;
      const argKeys: string = typeof args === 'object' && args !== null && !Array.isArray(args)
        ? Object.keys(args as JsonObject).join(',')
        : '';
      this.log(`execute: ${resolved.name} argKeys=${argKeys}`);
      const executed: UIMessagePartTool = await this.executeResolvedToolWithRetry(
        tool, resolved, retrySetting,
        (): Promise<UIMessagePart[]> => resolved.execute(args, signal), signal);
      const hooked: ToolInvocationResult = await this.runAfterHooks(
        request, { output: executed.output, metadata: {} });
      return withHookMetadata({ ...executed, output: hooked.output }, hooked.metadata);
    } catch (e) {
      if (isAbortError(e) || e instanceof ToolInvocationBarrierError) throw e;
      const error: Error = e instanceof Error ? e : new Error(String(e));
      this.log(`execute failed for ${tool.toolName}: ${error.message}`);
      const hooked: ToolInvocationResult | null = await this.runErrorHooks(request, error);
      if (hooked !== null) {
        return withHookMetadata({ ...tool, output: hooked.output }, hooked.metadata);
      }
      const payload: JsonObject = toAgentToolFailurePayload(error);
      payload['permission_trace'] = permissionDecisionTraceToJson(decision.trace);
      return { ...tool, output: [textPart(JSON.stringify(payload))] };
    }
  }

  private async runBeforeHooks(request: ToolInvocationRequest): Promise<ToolInvocationResult | null> {
    for (const hook of this.hooks) {
      if (hook.before === undefined) continue;
      const result: ToolInvocationResult | null = await this.runHook(
        'before', request.tool.toolName, () => (hook.before as NonNullable<typeof hook.before>)(request));
      if (result !== null) return result;
    }
    return null;
  }

  private async runAfterHooks(
    request: ToolInvocationRequest, initial: ToolInvocationResult,
  ): Promise<ToolInvocationResult> {
    let result: ToolInvocationResult = initial;
    for (const hook of this.hooks) {
      if (hook.after === undefined) continue;
      const next: ToolInvocationResult | null = await this.runHook(
        'after', request.tool.toolName,
        () => (hook.after as NonNullable<typeof hook.after>)(request, result));
      if (next !== null) result = next;
    }
    return result;
  }

  private async runErrorHooks(
    request: ToolInvocationRequest, error: Error,
  ): Promise<ToolInvocationResult | null> {
    for (const hook of this.hooks) {
      if (hook.onError === undefined) continue;
      const result: ToolInvocationResult | null = await this.runHook(
        'onError', request.tool.toolName,
        () => (hook.onError as NonNullable<typeof hook.onError>)(request, error));
      if (result !== null) return result;
    }
    return null;
  }

  // runHook(:277-287):hook 异常吞掉(AbortError 除外)并记录
  private async runHook<T>(
    phase: string, toolName: string,
    block: () => Promise<T>,
  ): Promise<T | null> {
    try {
      return await block();
    } catch (e) {
      if (isAbortError(e) || e instanceof ToolInvocationBarrierError) throw e;
      const error: Error = e instanceof Error ? e : new Error(String(e));
      this.log(`tool hook ${phase} failed for ${toolName}: ${error.message}`);
      return null;
    }
  }

  // executeResolvedToolWithRetry(:289-318);signal abort 时以 AbortError 结束重试等待
  private async executeResolvedToolWithRetry(
    tool: UIMessagePartTool,
    toolDef: AgentTool,
    retrySetting: GenerationRetrySetting,
    execute: () => Promise<UIMessagePart[]>,
    signal: AbortSignalLike | undefined = undefined,
  ): Promise<UIMessagePartTool> {
    const retryable: boolean = this.canRetrySafely(tool, toolDef, retrySetting);
    let retryAttempt: number = 1;
    while (true) {
      try {
        return { ...tool, output: await execute() };
      } catch (e) {
        if (isAbortError(e) || e instanceof ToolInvocationBarrierError) throw e;
        const error: Error = e instanceof Error ? e : new Error(String(e));
        const decision: GenerationRetryDecision = decideGenerationRetry(
          error, retryAttempt, retrySetting);
        if (!retryable || !decision.retryable) throw error;
        this.log(
          `execute: retry ${tool.toolName} ${retryAttempt}/${retrySetting.maxRetries} after ${decision.delayMs}ms (${decision.category})`);
        await this.abortableSleep(decision.delayMs, signal);
        retryAttempt++;
      }
    }
  }

  // abort 可打断的重试等待:signal 带 listener 时即刻打断;否则睡完检查
  private async abortableSleep(ms: number, signal: AbortSignalLike | undefined): Promise<void> {
    if (signal !== undefined && !signal.aborted
      && typeof signal.addEventListener === 'function') {
      await new Promise<void>((resolve, reject): void => {
        const onAbort = (): void => {
          cleanup();
          const err: Error = new Error('aborted');
          err.name = 'AbortError';
          reject(err);
        };
        const onTimeout = (): void => {
          cleanup();
          resolve();
        };
        const cleanup = (): void => {
          signal.removeEventListener?.('abort', onAbort);
          clearTimeout(timer);
        };
        const timer: ReturnType<typeof setTimeout> = setTimeout(onTimeout, ms);
        // AbortSignalLike 的 addEventListener 仅两参(abort 事件本身至多触发一次,无需 once)
        signal.addEventListener?.('abort', onAbort);
      });
    } else if (signal === undefined || !signal.aborted) {
      await this.sleep(ms);
    }
    if (signal !== undefined && signal.aborted) {
      const err: Error = new Error('aborted');
      err.name = 'AbortError';
      throw err;
    }
  }

  // canRunInParallel(:320-328)
  private canRunInParallel(tool: UIMessagePartTool, toolDef: AgentTool | null): boolean {
    if (tool.approvalState.type !== 'auto') return false;
    if (toolDef === null) return false;
    const policy: ToolInvocationPolicy = toolInvocationPolicyFromText(toolDef, tool.input);
    return policy.concurrencySafe
      && !policy.mutates
      && !policy.needsApproval
      && policy.risk === 'normal'
      && policy.parallelGroup !== null;
  }

  // canRetrySafely(:330-342)
  private canRetrySafely(
    tool: UIMessagePartTool, toolDef: AgentTool, retrySetting: GenerationRetrySetting,
  ): boolean {
    if (!retrySetting.enabled) return false;
    if (tool.approvalState.type !== 'auto') return false;
    const policy: ToolInvocationPolicy = toolInvocationPolicyFromText(toolDef, tool.input);
    return policy.concurrencySafe
      && !policy.mutates
      && !policy.needsApproval
      && policy.risk === 'normal';
  }
}

// withPermissionTrace(:344-348)
export const withPermissionTrace = (
  tool: UIMessagePartTool, trace: JsonObject,
): UIMessagePartTool => {
  const existing: JsonObject = tool.metadata !== null ? { ...tool.metadata } : {};
  existing['permission_trace'] = trace;
  return { ...tool, metadata: existing };
};

// withHookMetadata(:350-355)
export const withHookMetadata = (
  tool: UIMessagePartTool, hookMetadata: JsonObject,
): UIMessagePartTool => {
  if (Object.keys(hookMetadata).length === 0) return tool;
  const existing: JsonObject = tool.metadata !== null ? { ...tool.metadata } : {};
  for (const k of Object.keys(hookMetadata)) existing[k] = hookMetadata[k];
  return { ...tool, metadata: existing };
};
