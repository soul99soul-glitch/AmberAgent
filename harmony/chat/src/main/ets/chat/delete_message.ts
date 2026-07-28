// delete_message — 删除单条消息(节点级语义)
//
// Android 基准:ChatService.buildConversationAfterMessageDelete(ChatService.kt:2182-2208)
//   - 目标 message 从其 node 移除;node 空 → 移除整个 node;否则 selectIndex
//     夹到 nextMessages.lastIndex
//   - messageId 未命中:failIfMissing=true → NoSuchElementException;=false → 原样
//   - Android 另有 invalidateCompacts(ContextEngine = P1,无对应物,PARITY_DEBT 已记)
//
// 生成中禁止删除由 UI 层保证(ChatVM.showDeleteBlockedWhileGeneratingError 语义:
// sending 时不提供删除入口)

import type { Conversation, MessageNode } from './conversation.ts';
import { nowIso } from './ids.ts';

export const deleteMessage = (
  conv: Conversation, messageId: string, failIfMissing: boolean = true,
): Conversation => {
  const targetIndex: number = conv.messageNodes.findIndex((node: MessageNode): boolean =>
    node.messages.some((m): boolean => m.id === messageId));
  if (targetIndex < 0) {
    if (failIfMissing) {
      throw new Error(`deleteMessage: message not found: ${messageId}`);
    }
    return conv;
  }
  const updatedNodes: MessageNode[] = [];
  for (let i = 0; i < conv.messageNodes.length; i++) {
    const node: MessageNode = conv.messageNodes[i];
    if (i !== targetIndex) {
      updatedNodes.push(node);
      continue;
    }
    const nextMessages = node.messages.filter((m): boolean => m.id !== messageId);
    if (nextMessages.length === 0) {
      continue; // 节点空 → 移除
    }
    updatedNodes.push({
      ...node,
      messages: nextMessages,
      selectIndex: Math.min(node.selectIndex, nextMessages.length - 1),
    });
  }
  return { ...conv, messageNodes: updatedNodes, updateAt: nowIso() };
};
