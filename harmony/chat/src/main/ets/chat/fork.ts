// fork — 会话分叉
//
// Android 基准:ChatService.forkConversationAtMessage(ChatService.kt:2088-2127)
//   - 复制 0..targetIndex(含)节点;node id 全部换新,message id 保留
//   - 新 conversation:id 新、assistantId 相同、title 默认空(自动命名后续补)
//   - messageId 未命中 → NoSuchElementException
//
// 裁剪(登记):
//   - copyWithForkedFileUrl:本地文件复制(file: 协议 image/document/video/audio)
//     — 鸿蒙多模态输入未落地,当前无文件类 part 来源,P1 补;现为恒等
//   - copyValidCompactsToConversation:ContextEngine = P1,无对应物

import type { Conversation, MessageNode } from './conversation.ts';
import { makeConversation } from './conversation.ts';
import { newId } from './ids.ts';

export interface ForkDeps {
  newId?: () => string;
}

export const forkConversation = (
  conv: Conversation, messageId: string, deps: ForkDeps = {},
): Conversation => {
  const idGen: () => string = deps.newId ?? newId;
  const targetIndex: number = conv.messageNodes.findIndex((node: MessageNode): boolean =>
    node.messages.some((m): boolean => m.id === messageId));
  if (targetIndex < 0) {
    throw new Error(`forkConversation: message not found: ${messageId}`);
  }
  const copiedNodes: MessageNode[] = conv.messageNodes
    .slice(0, targetIndex + 1)
    .map((node: MessageNode): MessageNode => ({
      ...node,
      id: idGen(),
      // message id 保留(Android 不换);文件类 part 恒等(裁剪,见头注)
      messages: [...node.messages],
    }));
  // title 默认空(Android Conversation() 默认参数;自动命名在下一轮生成后补)
  return makeConversation(idGen(), copiedNodes, { assistantId: conv.assistantId });
};
