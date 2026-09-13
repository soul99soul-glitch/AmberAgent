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
// R15:该判定**不再用于批准工具** — 自然语言(mandatory 与否)一律不得仅凭
//   「继续」等模糊续写放行;强制审批只能走显式工具/参数绑定的用户授权
//   (applyToolApprovalToConversation 的审批按钮路径)。此处仅保留分类语义。
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

// 精确定位参数(R08):旧 id-only 接口在空 id / 重复 id 时无法区分同 blank 的多个
// part;审批 UI 可传 messageId + partIndex 明确指向一个 part。非 UI 调用可不传。
export interface ToolApprovalPartLocator {
  messageId: string;
  partIndex: number;
}

// 解析 id 在各节点各消息中的全部工具 part(用于歧义判定)。
export const collectToolApprovalCandidates = (
  conv: Conversation, toolCallId: string,
): UIMessagePartTool[] => {
  const found: UIMessagePartTool[] = [];
  for (const node of conv.messageNodes) {
    for (const msg of node.messages) {
      for (const part of msg.parts) {
        if (part.type === 'tool' && (part as UIMessagePartTool).toolCallId === toolCallId) {
          found.push(part as UIMessagePartTool);
        }
      }
    }
  }
  return found;
};

// :1145-1164 改写目标 toolCallId 的 approvalState(全节点全消息)。
// R08:空 toolCallId 或 id 命中多处时**拒绝歧义**(原样返回,绝不"全部批准");
//   需要精确改写时由调用方传 locator(messageId + partIndex)。
// P2-3:locator 必须**真正命中**一个 tool part,且当 toolCallId 非空时命中项的
//   toolCallId 必须相符(空 id 合法)。未命中/消息 id 重复/类型不符 → 返回**原
//   conversation 引用**(不是新对象),使调用方 out===previous 的"未生效"守卫可达,
//   避免静默改错工具(fail-open)。
export const applyToolApprovalToConversation = (
  conv: Conversation, toolCallId: string, verdict: ToolApprovalVerdict,
  locator: ToolApprovalPartLocator | null = null,
): Conversation => {
  if (locator !== null) {
    // messageId 必须唯一;重复 → 拒绝歧义
    let messageCount: number = 0;
    for (const node of conv.messageNodes) {
      for (const msg of node.messages) {
        if (msg.id === locator.messageId) messageCount++;
      }
    }
    if (messageCount !== 1) return conv;
    // 命中前校验:part 存在、为 tool、且 toolCallId 相符(空 id 合法)
    const node: MessageNode | undefined = conv.messageNodes.find(
      (n: MessageNode): boolean => n.messages.some(
        (m: UIMessage): boolean => m.id === locator.messageId));
    const message: UIMessage | undefined = node?.messages.find(
      (m: UIMessage): boolean => m.id === locator.messageId);
    const part: UIMessagePart | undefined = message?.parts[locator.partIndex];
    if (message === undefined || part === undefined || part.type !== 'tool') return conv;
    const located: UIMessagePartTool = part as UIMessagePartTool;
    if (toolCallId.length > 0 && located.toolCallId !== toolCallId) return conv;
  } else if (toolCallId.length === 0
    || collectToolApprovalCandidates(conv, toolCallId).length !== 1) {
    return conv;
  }
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
        parts: msg.parts.map((part: UIMessagePart, index: number): UIMessagePart => {
          if (part.type !== 'tool') return part;
          const tp: UIMessagePartTool = part as UIMessagePartTool;
          const targeted: boolean = locator !== null
            ? msg.id === locator.messageId && index === locator.partIndex
            : tp.toolCallId === toolCallId;
          return targeted ? { ...tp, approvalState: newState } : part;
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
  conv: Conversation, userAnswer: string, _userMessageIsPureText: boolean,
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
  // R15:自然语言「继续」等**不再批准任何 pending 工具**(mandatory 也拦住)。
  //   强制审批只能走显式审批按钮(applyToolApprovalToConversation / handleToolApproval)。
  //   ask_user 自由文本应答保留(:用户文本即答案)。旧 userMessageIsPureText 形参保留
  //   以兼容调用方,但不再参与批准判定。
  const hasAskUserAnswer: boolean = answer.trim().length > 0
    && blockingTools.some((t: UIMessagePartTool): boolean =>
      isToolPending(t) && t.toolName === ASK_USER_TOOL_NAME);
  const shouldResume: boolean = hasAskUserAnswer;

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
      if (hasAskUserAnswer) {
        return part;
      }
      // 非应答场景:陈旧未执行工具按新消息取消(不再靠模糊续写批准)
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
