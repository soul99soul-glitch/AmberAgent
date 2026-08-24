// stop_generation — 停止生成收口纯逻辑(Phase 2)
//
// Android 基准: ChatService.kt stopGeneration(:2408-2455)
//   - finishPendingTools(::cancelToolByUser)(cancelToolByUser :1611-1620)
//   - assistant 消息补 finishedAt(:2434-2439,仅 null 时)
//   - finishReasoning(:2440)
//   - updatedMessage == lastMessage → 无变化(不落库,launchPendingMessageLoop)
// 复用 conversation.ts 的 finishReasoning / finishPendingTools(Message.kt:551-590),
// 本文件只补 stopGeneration 特有的「取消工具结果文案/状态」与编排。
//
// 语义要点:
//   - 已执行工具(有 output)保留结果,不动
//   - 未执行/未批准(pending)工具 → output 置取消 JSON + approvalState denied
//   - open reasoning 全部关闭(finishedAt 补齐)
//   - assistant finishedAt 仅在 null 时补齐(cancelledAt)

import type { Conversation, MessageNode } from './conversation.ts';
import { finishPendingTools, finishReasoning } from './conversation.ts';
import type { UIMessage, UIMessagePart, UIMessagePartTool } from './message.ts';
import { nowIso } from './ids.ts';

// ChatService.cancelToolByUser(:1611-1620) 逐字:取消输出 JSON + denied reason
export const CANCEL_TOOL_BY_USER_OUTPUT: string =
  '{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}';

export const CANCEL_TOOL_BY_USER_REASON: string = 'Generation cancelled by user';

export const cancelToolByUser = (tool: UIMessagePartTool): UIMessagePartTool => ({
  ...tool,
  output: [{ type: 'text', text: CANCEL_TOOL_BY_USER_OUTPUT, metadata: null }],
  approvalState: { type: 'denied', reason: CANCEL_TOOL_BY_USER_REASON },
});

// 结构相等判定(Android `updatedMessage == lastMessage` 是 data class 结构相等,
//  Harmony 为引用相等;finishReasoning/finishPendingTools 无变化时也会构建新对象,
//  故按 parts 逐元素引用 + finishedAt 比较判定「是否有可观察变化」)
const partsUnchanged = (a: UIMessagePart[], b: UIMessagePart[]): boolean => {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) {
    if (a[i] !== b[i]) return false;
  }
  return true;
};

// stopGeneration 对末条消息的更新(:2428-2440):
//   finishPendingTools(::cancelToolByUser) → assistant finishedAt 补齐 →
//   finishReasoning;无可观察变化返回 null(Android updatedMessage == lastMessage
//   :2441 早退语义)
export const stopGenerationUpdatedMessage = (
  msg: UIMessage, cancelledAt: string,
): UIMessage | null => {
  const finalized: UIMessage = finishPendingTools(msg, cancelToolByUser);
  const withFinishedAt: UIMessage = finalized.role === 'assistant' && finalized.finishedAt === null
    ? { ...finalized, finishedAt: cancelledAt }
    : finalized;
  const closed: UIMessage = finishReasoning(withFinishedAt);
  if (closed.finishedAt === msg.finishedAt && partsUnchanged(msg.parts, closed.parts)) {
    return null;
  }
  return closed;
};

// applyStopGeneration:默认对会话末条 currentMessage 做收口。assistant
//   中间节点 regenerate 时显式传 targetNodeId,避免新分支中的 pending tool
//   因后续历史节点仍存在而悬空。无变化返回 null,且不追加节点。
export const applyStopGeneration = (
  conv: Conversation, cancelledAt: string, targetNodeId: string = '',
): Conversation | null => {
  if (conv.messageNodes.length === 0) return null;
  const targetIndex: number = targetNodeId.length > 0
    ? conv.messageNodes.findIndex((node: MessageNode): boolean => node.id === targetNodeId)
    : conv.messageNodes.length - 1;
  if (targetIndex < 0) return null;
  const targetNode: MessageNode = conv.messageNodes[targetIndex];
  const current: UIMessage = targetNode.messages[targetNode.selectIndex];
  const updated: UIMessage | null = stopGenerationUpdatedMessage(current, cancelledAt);
  if (updated === null) return null;
  const updatedNode: MessageNode = {
    ...targetNode,
    messages: targetNode.messages.map(
      (m: UIMessage): UIMessage => (m.id === current.id ? updated : m)),
  };
  return {
    ...conv,
    messageNodes: conv.messageNodes.map(
      (node: MessageNode, index: number): MessageNode => index === targetIndex ? updatedNode : node),
    updateAt: nowIso(),
  };
};
