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
import type { UIMessage } from './message.ts';
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
    // 删除的是其它 alternative 时,当前选中的分支必须保持不变:
    // 旧索引在新数组中会指错对象(删除选中项之前的分支时整体前移一位),
    // 按选中 message id 重定位;删除的就是选中项时才回退到 clamp 策略。
    // (Android 基准 coerceAtMost 同样存在该错位 — 双端共享 bug,Harmony 先修)
    const selected: UIMessage | undefined = node.messages[node.selectIndex];
    let selectIndex: number = Math.max(0, Math.min(node.selectIndex, nextMessages.length - 1));
    if (selected !== undefined && selected.id !== messageId) {
      const relocated: number = nextMessages.findIndex((m): boolean => m.id === selected.id);
      if (relocated >= 0) selectIndex = relocated;
    }
    updatedNodes.push({
      ...node,
      messages: nextMessages,
      selectIndex,
    });
  }
  return { ...conv, messageNodes: updatedNodes, updateAt: nowIso() };
};
