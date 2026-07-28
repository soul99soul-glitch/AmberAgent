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

export interface AgentToolDispatcherDeps {
  resolver?: PermissionDecisionResolver;
  hooks?: ToolInvocationHook[];
  log?: (message: string) => void;
  nowMs?: () => number;
  sleep?: (ms: number) => Promise<void>;
}

export class AgentToolDispatcher {
  private readonly resolver: PermissionDecisionResolver;
  private readonly hooks: ToolInvocationHook[];
  private readonly log: (message: string) => void;
  private readonly nowMs: () => number;
  private readonly sleep: (ms: number) => Promise<void>;

  constructor(deps: AgentToolDispatcherDeps = {}) {
    this.resolver = deps.resolver ?? new PermissionDecisionResolver();
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

  // executeBatch(:63-117):prefetch 复用 → 并行(全部 canRunInParallel)或顺序 →
  //   按原 toolCallId 序重排
  public async executeBatch(
    tools: UIMessagePartTool[],
    toolDefinitions: Map<string, AgentTool>,
    autoApproveTools: boolean,
    autoApproveHighRiskTools: boolean = false,
    autoApprovedToolNames: string[] = [],
    invocationContext: ToolInvocationContext = 'normal',
    prefetchedTools: Map<string, UIMessagePartTool> = new Map<string, UIMessagePartTool>(),
    retrySetting: GenerationRetrySetting = makeGenerationRetrySetting({ enabled: false }),
  ): Promise<UIMessagePartTool[]> {
    const reused: UIMessagePartTool[] = [];
    for (const tool of tools) {
      const prefetched: UIMessagePartTool | undefined = prefetchedTools.get(tool.toolCallId);
      if (prefetched !== undefined
        && prefetched.toolName === tool.toolName
        && prefetched.input === tool.input
        && prefetched.output.length > 0) {
        reused.push(prefetched);
      }
    }
    const reusedIds = new Set<string>(reused.map((t: UIMessagePartTool): string => t.toolCallId));
    const remaining: UIMessagePartTool[] = tools.filter(
      (t: UIMessagePartTool): boolean => !reusedIds.has(t.toolCallId));
    if (remaining.length === 0) return reused;
    let executed: UIMessagePartTool[];
    if (remaining.length > 1 && remaining.every(
      (t: UIMessagePartTool): boolean => this.canRunInParallel(t, toolDefinitions.get(t.toolName) ?? null))) {
      const results: (UIMessagePartTool | null)[] = await Promise.all(remaining.map(
        (tool: UIMessagePartTool): Promise<UIMessagePartTool | null> => this.execute(
          tool, toolDefinitions.get(tool.toolName) ?? null,
          autoApproveTools, autoApproveHighRiskTools, autoApprovedToolNames,
          invocationContext, retrySetting)));
      executed = results.filter((t: UIMessagePartTool | null): boolean => t !== null) as UIMessagePartTool[];
    } else {
      executed = [];
      for (const tool of remaining) {
        const result: UIMessagePartTool | null = await this.execute(
          tool, toolDefinitions.get(tool.toolName) ?? null,
          autoApproveTools, autoApproveHighRiskTools, autoApprovedToolNames,
          invocationContext, retrySetting);
        if (result !== null) executed.push(result);
      }
    }
    const reusedById = new Map<string, UIMessagePartTool>(
      reused.map((t: UIMessagePartTool): [string, UIMessagePartTool] => [t.toolCallId, t]));
    const executedById = new Map<string, UIMessagePartTool>(
      executed.map((t: UIMessagePartTool): [string, UIMessagePartTool] => [t.toolCallId, t]));
    const out: UIMessagePartTool[] = [];
    for (const tool of tools) {
      const hit: UIMessagePartTool | undefined = reusedById.get(tool.toolCallId)
        ?? executedById.get(tool.toolCallId);
      if (hit !== undefined) out.push(hit);
    }
    return out;
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
  ): Promise<UIMessagePartTool | null> {
    const decision: PermissionDecision = this.resolveDecision(
      toolDef, tool, autoApproveTools, autoApproveHighRiskTools,
      autoApprovedToolNames, invocationContext);
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
    return this.executeWithHooks(tracedTool, toolDef, decision, invocationContext, retrySetting);
  }

  // executeWithHooks(:187-239)
  private async executeWithHooks(
    tool: UIMessagePartTool,
    toolDef: AgentTool | null,
    decision: PermissionDecision,
    invocationContext: ToolInvocationContext,
    retrySetting: GenerationRetrySetting,
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
        (): Promise<UIMessagePart[]> => resolved.execute(args));
      const hooked: ToolInvocationResult = await this.runAfterHooks(
        request, { output: executed.output, metadata: {} });
      return withHookMetadata({ ...executed, output: hooked.output }, hooked.metadata);
    } catch (e) {
      if (isAbortError(e)) throw e;
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
      if (isAbortError(e)) throw e;
      const error: Error = e instanceof Error ? e : new Error(String(e));
      this.log(`tool hook ${phase} failed for ${toolName}: ${error.message}`);
      return null;
    }
  }

  // executeResolvedToolWithRetry(:289-318)
  private async executeResolvedToolWithRetry(
    tool: UIMessagePartTool,
    toolDef: AgentTool,
    retrySetting: GenerationRetrySetting,
    execute: () => Promise<UIMessagePart[]>,
  ): Promise<UIMessagePartTool> {
    const retryable: boolean = this.canRetrySafely(tool, toolDef, retrySetting);
    let retryAttempt: number = 1;
    while (true) {
      try {
        return { ...tool, output: await execute() };
      } catch (e) {
        if (isAbortError(e)) throw e;
        const error: Error = e instanceof Error ? e : new Error(String(e));
        const decision: GenerationRetryDecision = decideGenerationRetry(
          error, retryAttempt, retrySetting);
        if (!retryable || !decision.retryable) throw error;
        this.log(
          `execute: retry ${tool.toolName} ${retryAttempt}/${retrySetting.maxRetries} after ${decision.delayMs}ms (${decision.category})`);
        await this.sleep(decision.delayMs);
        retryAttempt++;
      }
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
