// edit_message — 编辑消息(分支追加语义)
//
// Android 基准:ChatService.editMessage(ChatService.kt:2054-2086)
//   - 空/空白 parts → 原样返回(isEmptyInputMessage)
//   - 目标 message 所在 node **追加新消息**(role = node.role,新 id)为新分支,
//     selectIndex 指向新分支;旧分支保留("编辑不覆盖")
//   - messageId 未命中 → 原样返回(edited=false,不抛错)
//   - 编辑本身不截断/不重跑;invalidateCompacts 无对应物(ContextEngine = P1)
//
// 鸿蒙 UI 组合(ChatPage):editMessage + 持久化 → runRegenerateAt(user 节点) =
// 「编辑并重发」一步完成(Android 为编辑后用户再触发 regenerate 的两步)

import type { Conversation, MessageNode } from './conversation.ts';
import { nodeRole } from './conversation.ts';
import type { UIMessage, UIMessagePart } from './message.ts';
import { makeUIMessage } from './message.ts';
import { nowIso } from './ids.ts';

// isEmptyInputMessage 等价:text 全空白且没有其他类型 part
const isEmptyParts = (parts: UIMessagePart[]): boolean =>
  parts.length === 0
  || parts.every((p: UIMessagePart): boolean => p.type === 'text' && p.text.trim().length === 0);

export const editMessage = (
  conv: Conversation, messageId: string, parts: UIMessagePart[],
): Conversation => {
  if (isEmptyParts(parts)) {
    return conv;
  }
  let edited: boolean = false;
  const updatedNodes: MessageNode[] = conv.messageNodes.map((node: MessageNode): MessageNode => {
    if (!node.messages.some((m: UIMessage): boolean => m.id === messageId)) {
      return node;
    }
    edited = true;
    const newMessage: UIMessage = makeUIMessage(nodeRole(node), parts);
    return {
      ...node,
      messages: [...node.messages, newMessage],
      selectIndex: node.messages.length,
    };
  });
  if (!edited) {
    return conv;
  }
  return { ...conv, messageNodes: updatedNodes, updateAt: nowIso() };
};
