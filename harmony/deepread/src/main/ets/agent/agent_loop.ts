import type { AbortSignalLike } from '../platform/runtime_api.ts';
// runAgentLoop — 组装 GenerationHandler 核心行为(§4.8)
// 多步 agent 循环:callModel → 检测 assistant 的 tool_calls → executeToolsAndMerge → 再调模型,
// 直到无 tool / maxSteps / budget FINAL / pending approval / abort。
//
// 纯逻辑:callModel / tools / approval / signal 全注入。复用已有:
// - tool_execution.executeToolsAndMerge(P0-12 tool-result 写回)
// - budget_prompt.shouldHideToolsForBudget / buildBudgetPrompt
// - retry_classifier(由 callModel 内部用,本循环不做重试 — 重试是单次模型调用的职责)
//
// 这是 OpenAiCompatibleAiClient 应组装的 agent loop;此处先做成纯逻辑可测,
// entry 的 .ets AiClient 包装 SSE 流式解析后调 runAgentLoop。

import type { UIMessage } from './message.ts';
import { makeUserMessage, getTools, isToolExecuted } from './message.ts';
import {
  executeToolsAndMerge, resolveDecision,
} from './tool_execution.ts';
import type { ToolDefinition, ApprovalMatrix } from './tool_execution.ts';
import { shouldHideToolsForBudget, buildBudgetPrompt } from './budget_prompt.ts';

export type StopReason =
  | 'no_more_tools'      // 模型不再调 tool → 完成
  | 'max_steps'          // 达到 maxSteps
  | 'pending_approval'   // 有 tool 需用户审批 → 暂停
  | 'aborted';           // signal abort

export interface AgentLoopResult {
  messages: UIMessage[];
  steps: number;
  stopReason: StopReason;
  pendingApproval: boolean;
}

export interface AgentLoopDeps {
  /** 单次模型调用:接收当前 messages + 暴露的 tool 列表 + 可选 budget 注入;返回更新后 messages(含 assistant 回复) */
  callModel: (
    messages: UIMessage[],
    exposedTools: ToolDefinition[],
    budgetPrompt: string,
  ) => Promise<UIMessage[]>;
  tools: Map<string, ToolDefinition>;
  approval: ApprovalMatrix;
  maxSteps: number;          // 默认调用方传 MAX_GENERATION_STEPS(32)
  onMessagesUpdate?: (messages: UIMessage[]) => void;
  signal?: AbortSignalLike;
}

const isAborted = (signal?: AbortSignalLike): boolean => signal !== undefined && signal.aborted;

// resumable tool = 未执行且可恢复(approval approved/denied/answered)
const hasResumableTools = (messages: UIMessage[]): boolean => {
  if (messages.length === 0) return false;
  const last = messages[messages.length - 1];
  if (last.role !== 'assistant') return false;
  return getTools(last).some(t => !isToolExecuted(t));
};

export const runAgentLoop = async (
  initialMessages: UIMessage[],
  deps: AgentLoopDeps,
): Promise<AgentLoopResult> => {
  let messages = [...initialMessages];
  let steps = 0;
  let pendingApproval = false;

  while (steps < deps.maxSteps) {
    if (isAborted(deps.signal)) {
      return { messages, steps, stopReason: 'aborted', pendingApproval: false };
    }

    // budget 判定(§4.8.1):FINAL 且无 resumable tool → 隐藏 tool,注入 FINAL 提醒
    const resumable = hasResumableTools(messages);
    const hideTools = shouldHideToolsForBudget(steps, deps.maxSteps, resumable);
    const exposedTools = hideTools ? [] : Array.from(deps.tools.values());
    const budgetPrompt = buildBudgetPrompt(steps, deps.maxSteps);

    // 单次模型调用
    let newMessages: UIMessage[];
    try {
      newMessages = await deps.callModel(messages, exposedTools, budgetPrompt);
    } catch (e) {
      // 重试是 callModel 的职责;此处直接抛(取消错误透传)
      throw e;
    }
    messages = newMessages;
    steps += 1;
    if (deps.onMessagesUpdate) deps.onMessagesUpdate(messages);

    if (isAborted(deps.signal)) {
      return { messages, steps, stopReason: 'aborted', pendingApproval: false };
    }

    // 检查最后一条 assistant 是否有未执行 tool
    if (messages.length === 0) {
      return { messages, steps, stopReason: 'no_more_tools', pendingApproval };
    }
    const lastMsg = messages[messages.length - 1];
    if (lastMsg.role !== 'assistant') {
      // 非 assistant 回复 → 无 tool → 完成
      return { messages, steps, stopReason: 'no_more_tools', pendingApproval };
    }

    // 执行 tool(executeToolsAndMerge 处理审批决策 + 写回 output)
    const execResult = await executeToolsAndMerge(messages, deps.tools, deps.approval);
    messages = execResult.messages;
    if (deps.onMessagesUpdate) deps.onMessagesUpdate(messages);

    if (execResult.hasPendingApproval) {
      pendingApproval = true;
      return { messages, steps, stopReason: 'pending_approval', pendingApproval: true };
    }

    // 无执行也无 pending → 检查是否还有未执行 tool(理论上执行后都 done)
    const stillHasUnexecuted = messages.length > 0 &&
      getTools(messages[messages.length - 1]).some(t => !isToolExecuted(t));
    if (!stillHasUnexecuted && execResult.executedCount === 0) {
      // 模型没调任何 tool → 完成
      return { messages, steps, stopReason: 'no_more_tools', pendingApproval };
    }
  }

  return { messages, steps, stopReason: 'max_steps', pendingApproval };
};

// 重新导出供 entry AiClient 用
export { resolveDecision };
export type { ToolDefinition, ApprovalMatrix };
