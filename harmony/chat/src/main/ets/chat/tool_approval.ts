// tool_approval — 工具审批 + 续跑判定(D-057)
//
// Android 基准: ChatService.kt
//   - handleToolApproval(:1122-1188):answer/approved/denied 三态改写 → 保存 →
//     无 pending 才续跑(纯逻辑部分;job 取消/screen 信任集/generationDone 属服务层)
//   - resolveIdleToolBlockerBeforeDispatch(:869-943):末条消息有未执行工具时,
//     新用户消息按「继续」语义决定 resume(approve/answer)或 cancel 陈旧工具
//   - isToolApprovalContinuation(:945-953)/cancelToolForNewUserMessage(:955-963)/
//     skipStaleToolForContinuation(:965-970)/TOOL_APPROVAL_CONTINUATION_WORDS(:142-157)
//   - findToolName(:2535-2541)/hasPendingOrUnexecutedTools(:2543-2546)
// 偏差:服务层副作用(job cancel/trustedRunToolNames/审计 recordEvent)由调用方
//   接线;本模块返回事件信息(event/count/detail)供审计。

import type { Conversation, MessageNode } from './conversation.ts';
import { currentMessages, nodeCurrentMessage } from './conversation.ts';
import type { UIMessage, UIMessagePart, UIMessagePartTool, ToolApprovalState } from './message.ts';
import { isToolExecuted, isToolPending } from './message.ts';
import { nowIso } from './ids.ts';
import { ASK_USER_TOOL_NAME } from './tool_permission.ts';

// ===== TOOL_APPROVAL_CONTINUATION_WORDS(:142-157) =====

export const TOOL_APPROVAL_CONTINUATION_WORDS: string[] = [
  '继续', '继续吧', '可以继续', '执行', '执行吧', '确认', '同意', '批准',
  'ok', 'yes', 'y', 'continue', 'goahead', 'approve', 'approved',
];

// isToolApprovalContinuation(:945-953):纯文本(调用方保证)→ trim 小写 →
//   去除空白与标点(Java \p{Punct}=ASCII 标点 + 显式 CJK 标点列表)→
//   词表命中 或 startsWith 继续/可以继续
export const isToolApprovalContinuation = (text: string): boolean => {
  const raw: string = text.slice(0, 80).trim().toLowerCase();
  if (raw.length === 0) return false;
  const compact: string = raw.replace(
    /[\s!-/:-@[-`{-~，。！？、；：「」『』（）【】《》]+/g, '');
  return TOOL_APPROVAL_CONTINUATION_WORDS.includes(compact)
    || compact.startsWith('继续')
    || compact.startsWith('可以继续');
};

// ===== findToolName(:2535-2541) =====

export const findToolNameInConversation = (
  conv: Conversation, toolCallId: string,
): string | null => {
  for (const node of conv.messageNodes) {
    for (const msg of node.messages) {
      for (const part of msg.parts) {
        if (part.type === 'tool' && (part as UIMessagePartTool).toolCallId === toolCallId) {
          return (part as UIMessagePartTool).toolName;
        }
      }
    }
  }
  return null;
};

// ===== hasPendingOrUnexecutedTools(:2543-2546) =====

export const conversationHasPendingOrUnexecutedTools = (conv: Conversation): boolean => {
  const msgs: UIMessage[] = currentMessages(conv);
  if (msgs.length === 0) return false;
  const last: UIMessage = msgs[msgs.length - 1];
  return last.parts.some((p: UIMessagePart): boolean =>
    p.type === 'tool'
    && (!isToolExecuted(p as UIMessagePartTool) || isToolPending(p as UIMessagePartTool)));
};

// ===== handleToolApproval 纯逻辑(:1133-1172) =====

export type ToolApprovalVerdict =
  | { kind: 'approved' }
  | { kind: 'denied'; reason: string }
  | { kind: 'answered'; answer: string };

// :1136-1140 三态映射(answer 优先)
export const resolveApprovalState = (
  approved: boolean, reason: string = '', answer: string | null = null,
): ToolApprovalState => {
  if (answer !== null) return { type: 'answered', answer };
  if (approved) return { type: 'approved' };
  return { type: 'denied', reason };
};

// :1145-1164 改写目标 toolCallId 的 approvalState(全节点全消息)
export const applyToolApprovalToConversation = (
  conv: Conversation, toolCallId: string, verdict: ToolApprovalVerdict,
): Conversation => {
  const newState: ToolApprovalState = verdict.kind === 'answered'
    ? { type: 'answered', answer: verdict.answer }
    : verdict.kind === 'approved'
      ? { type: 'approved' }
      : { type: 'denied', reason: (verdict as { kind: 'denied'; reason: string }).reason };
  return {
    ...conv,
    messageNodes: conv.messageNodes.map((node: MessageNode): MessageNode => ({
      ...node,
      messages: node.messages.map((msg: UIMessage): UIMessage => ({
        ...msg,
        parts: msg.parts.map((part: UIMessagePart): UIMessagePart => {
          if (part.type === 'tool'
            && (part as UIMessagePartTool).toolCallId === toolCallId) {
            return { ...(part as UIMessagePartTool), approvalState: newState };
          }
          return part;
        }),
      })),
    })),
    updateAt: nowIso(),
  };
};

// :1166-1172 续跑门:任何节点 currentMessage 仍有 pending → 不续跑
export const conversationHasPendingTools = (conv: Conversation): boolean =>
  conv.messageNodes.some((node: MessageNode): boolean =>
    nodeCurrentMessage(node).parts.some((p: UIMessagePart): boolean =>
      p.type === 'tool' && isToolPending(p as UIMessagePartTool)));

// ===== 陈旧工具处理(:955-970) =====

export const cancelToolForNewUserMessage = (tool: UIMessagePartTool): UIMessagePartTool => ({
  ...tool,
  output: [{
    type: 'text',
    text: '{"status":"cancelled","error":"A new user message arrived before this pending tool was approved, so AmberAgent cancelled the stale tool state and continued the conversation."}',
    metadata: null,
  }],
  approvalState: { type: 'denied', reason: 'Cancelled because a new user message arrived before approval' },
});

export const skipStaleToolForContinuation = (tool: UIMessagePartTool): UIMessagePartTool => ({
  ...tool,
  approvalState: { type: 'denied', reason: 'Skipped stale tool after user asked to continue' },
});

// ===== resolveIdleToolBlockerBeforeDispatch(:869-943) =====

export interface IdleToolBlockerResult {
  // changed=false → 无阻塞工具或未改动,调用方走正常派发
  changed: boolean;
  conversation: Conversation;
  // true → 调用方应续跑生成(handleMessageComplete)而非派发新消息
  shouldResume: boolean;
  // 审计事件信息(Android recordPendingMessageEvent :925-932)
  event: 'pending_tool_resume' | 'pending_tool_cancel' | null;
  blockingCount: number;
  blockingToolNames: string[];
}

export const resolveIdleToolBlocker = (
  conv: Conversation, userAnswer: string, userMessageIsPureText: boolean,
): IdleToolBlockerResult => {
  const none: IdleToolBlockerResult = {
    changed: false, conversation: conv, shouldResume: false,
    event: null, blockingCount: 0, blockingToolNames: [],
  };
  const nodes: MessageNode[] = conv.messageNodes;
  if (nodes.length === 0) return none;
  const lastNode: MessageNode = nodes[nodes.length - 1];
  const lastMessage: UIMessage = nodeCurrentMessage(lastNode);
  const blockingTools: UIMessagePartTool[] = lastMessage.parts.filter(
    (p: UIMessagePart): boolean => p.type === 'tool' && !isToolExecuted(p as UIMessagePartTool),
  ) as UIMessagePartTool[];
  if (blockingTools.length === 0) return none;

  const answer: string = userAnswer.slice(0, 4000);
  // Android message.isToolApprovalContinuation() 前置:parts 全 text(:946)
  const explicitApproval: boolean = userMessageIsPureText && isToolApprovalContinuation(answer);
  const hasAskUserAnswer: boolean = answer.trim().length > 0
    && blockingTools.some((t: UIMessagePartTool): boolean =>
      isToolPending(t) && t.toolName === ASK_USER_TOOL_NAME);
  const shouldResume: boolean = explicitApproval || hasAskUserAnswer;

  let changed: boolean = false;
  const updatedMessage: UIMessage = {
    ...lastMessage,
    parts: lastMessage.parts.map((part: UIMessagePart): UIMessagePart => {
      if (part.type !== 'tool') return part;
      const tool: UIMessagePartTool = part as UIMessagePartTool;
      if (isToolExecuted(tool)) return part;
      if (tool.toolName === ASK_USER_TOOL_NAME && hasAskUserAnswer) {
        changed = true;
        return { ...tool, approvalState: { type: 'answered', answer } };
      }
      if (explicitApproval && isToolPending(tool)) {
        changed = true;
        return { ...tool, approvalState: { type: 'approved' } };
      }
      if (explicitApproval) {
        changed = true;
        return skipStaleToolForContinuation(tool);
      }
      if (hasAskUserAnswer) {
        return part;
      }
      changed = true;
      return cancelToolForNewUserMessage(tool);
    }),
  };
  if (!changed || updatedMessage === lastMessage) return none;

  const updatedConversation: Conversation = {
    ...conv,
    messageNodes: [
      ...nodes.slice(0, nodes.length - 1),
      {
        ...lastNode,
        messages: lastNode.messages.map((m: UIMessage): UIMessage =>
          m.id === lastMessage.id ? updatedMessage : m),
      },
    ],
    updateAt: nowIso(),
  };
  return {
    changed: true,
    conversation: updatedConversation,
    shouldResume,
    event: shouldResume ? 'pending_tool_resume' : 'pending_tool_cancel',
    blockingCount: blockingTools.length,
    blockingToolNames: blockingTools.map((t: UIMessagePartTool): string => t.toolName),
  };
};
