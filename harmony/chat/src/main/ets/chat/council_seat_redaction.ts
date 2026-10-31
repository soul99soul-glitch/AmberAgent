// council_seat_redaction — Council 席位快照落盘前的输入脱敏
//
// 为什么需要:
//   一条带凭据的工具调用在 Council 席位里会被序列化两次 —
//     1) seat.store.save(conversation):整棵会话树写进 model-council/seats/*.json
//     2) council_started_v1 标记:把 input 原文复制到工具卡 metadata,同样落盘
//   两处都在 entry 层(ArkTS),拿不到 chat-domain 的会话仓库脱敏钩子,所以这里
//   提供一个纯函数,在写入前把需要脱敏的工具输入替换成占位符。
//
// 边界:
//   - 只改 input 文本与 council_started_v1.input 副本;output/approvalState/其他 metadata 原样保留。
//   - 依赖 chat-domain 的 redactStoredToolInput(按工具名登记),未登记的工具原样返回。
//   - 返回新对象,不原地修改入参(调用方可能还在用同一份内存快照)。

import type { Conversation, MessageNode } from './conversation.ts';
import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessage, UIMessagePart, UIMessagePartTool } from './message.ts';
import { redactStoredToolInput, toolInputNeedsStoredRedaction } from './tool_input_redaction.ts';

const redactStartedMarker = (metadata: JsonObject | null, toolName: string): JsonObject | null => {
  if (metadata === null) return null;
  const started: JsonValue | undefined = metadata['council_started_v1'];
  if (started === null || started === undefined || typeof started !== 'object' || Array.isArray(started)) {
    return metadata;
  }
  const marker: JsonObject = started as JsonObject;
  if (!toolInputNeedsStoredRedaction(toolName)) return metadata;
  const input: JsonValue | undefined = marker['input'];
  const redactedInput: JsonValue = typeof input === 'string'
    ? redactStoredToolInput(toolName, input) : input;
  return { ...metadata, 'council_started_v1': { ...marker, 'input': redactedInput } };
};

const redactPart = (part: UIMessagePart): UIMessagePart => {
  if (part.type !== 'tool') return part;
  const tool: UIMessagePartTool = part as UIMessagePartTool;
  return {
    type: 'tool', toolCallId: tool.toolCallId, toolName: tool.toolName,
    input: redactStoredToolInput(tool.toolName, tool.input),
    output: tool.output, approvalState: tool.approvalState,
    metadata: redactStartedMarker(tool.metadata, tool.toolName),
  };
};

const redactMessage = (message: UIMessage): UIMessage => {
  const parts: UIMessagePart[] = message.parts.map((part: UIMessagePart): UIMessagePart => redactPart(part));
  return {
    id: message.id, role: message.role, parts, annotations: message.annotations,
    createdAt: message.createdAt, finishedAt: message.finishedAt,
    modelId: message.modelId, usage: message.usage, translation: message.translation,
  };
};

const redactNode = (node: MessageNode): MessageNode => ({
  id: node.id,
  messages: node.messages.map((message: UIMessage): UIMessage => redactMessage(message)),
  selectIndex: node.selectIndex,
});

/** 席位快照脱敏:写入 model-council/seats 之前的唯一入口 */
export const redactCouncilConversationInputs = (conversation: Conversation): Conversation => ({
  ...conversation,
  messageNodes: conversation.messageNodes.map((node: MessageNode): MessageNode => redactNode(node)),
});

/** 工具卡上的 council_started_v1 副本脱敏(与上面同源,单独暴露给标记写入点) */
export const redactCouncilStartedInput = (toolName: string, input: string): string =>
  redactStoredToolInput(toolName, input);
