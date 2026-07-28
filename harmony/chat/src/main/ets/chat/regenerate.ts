// regenerate — assistant 分支再生 + 分支切换
//
// Android 基准:
//   - ChatService.regenerateAtMessage(ChatService.kt:1078-1118):assistant 消息
//     regenerate 时以目标节点之前的消息(messageRange 0..<nodeIndex)为上下文重跑
//     生成;新结果追加进同一 MessageNode("regenerate 追加不覆盖",conversation.ts 头注)
//   - ChatService.selectMessageNode(ChatVM.kt:420 调用链):设置节点 selectIndex 并持久化
//
// 与 Android 的偏差(已登记 PARITY_DEBT):
//   - Android 的窗口化 merge(streamingTail/mergeGeneratedMessagesIntoWindow/时间线分页)
//     依附于其分页架构;本实现为扁平节点模型下的等价语义:上下文截断 + 分支追加
//   - Android regenerate 会 invalidateCompacts;ConversationContextEngine = P1,无对应物
//
// 裁剪(P1):user 消息节点 regenerate(截断后续节点重跑)——domain 层显式抛错,
//   不做静默 no-op(宪章)

import type { Conversation, MessageNode } from './conversation.ts';
import { currentMessages, nodeRole } from './conversation.ts';
import type { MessageChunk, UIMessage } from './message.ts';
import { makeUIMessage } from './message.ts';
import { MessageStreamAccumulator } from './stream_accumulator.ts';
import type { Assistant } from './assistant.ts';
import { assembleInternalMessages } from './context_assembly.ts';
import type {
  MessageTransformer, OutputMessageTransformer, TransformerContext,
} from './transformer_pipeline.ts';
import { applyInputTransformers, applyOnGenerationFinish } from './transformer_pipeline.ts';
import type { ChatStreamProvider, ConversationStore } from './chat_turn.ts';
import { nowIso } from './ids.ts';

// ===== 分支切换 =====

// 越界索引夹取(不环绕);未知 nodeId 原样返回(UI 竞态下可安全重试)
export const selectMessageBranch = (
  conv: Conversation, nodeId: string, selectIndex: number,
): Conversation => ({
  ...conv,
  messageNodes: conv.messageNodes.map((node: MessageNode): MessageNode => {
    if (node.id !== nodeId) return node;
    const clamped: number = Math.max(0, Math.min(selectIndex, node.messages.length - 1));
    return { ...node, selectIndex: clamped };
  }),
  updateAt: nowIso(),
});

// ===== assistant 分支再生 =====

export interface RegenerateDeps {
  assistant: Assistant;
  inputTransformers: MessageTransformer[];
  outputTransformers: OutputMessageTransformer[];
  provider: ChatStreamProvider;
  store: ConversationStore;
  agentSoul?: string;
  contextMessageSize?: number;
  onUpdate?: (messages: UIMessage[]) => void;
}

export const runRegenerateAt = async (
  conversation: Conversation, nodeId: string, deps: RegenerateDeps,
): Promise<Conversation> => {
  const nodeIndex: number = conversation.messageNodes.findIndex(
    (n: MessageNode): boolean => n.id === nodeId);
  if (nodeIndex < 0) {
    throw new Error(`runRegenerateAt: node not found: ${nodeId}`);
  }
  const target: MessageNode = conversation.messageNodes[nodeIndex];
  if (nodeRole(target) !== 'assistant') {
    // P1 裁剪:user 节点 regenerate(截断后续 + 重跑)尚未实现,显式抛错
    throw new Error('runRegenerateAt: regenerate at user node is not supported (P1)');
  }

  // 1. 上下文 = 目标节点之前的 currentMessages(Android messageRange 0..<nodeIndex)
  const priorConversation: Conversation = {
    ...conversation,
    messageNodes: conversation.messageNodes.slice(0, nodeIndex),
  };
  const baseMessages: UIMessage[] = currentMessages(priorConversation);
  const ctx: TransformerContext = { assistant: deps.assistant };

  // 2. 组装 + input transforms(作用于发往 provider 的副本,不污染会话本体)
  const assembled: UIMessage[] = assembleInternalMessages({
    messages: baseMessages,
    assistant: deps.assistant,
    agentSoul: deps.agentSoul ?? '',
    contextMessageSize: deps.contextMessageSize ?? 0,
  });
  const internalMessages: UIMessage[] = applyInputTransformers(assembled, deps.inputTransformers, ctx);

  // 3. provider 流式(错误直接传播,会话不变 — 对齐 runChatTurn)
  //    accumulator 语义:末条 initial 消息是"活动流式消息"。regenerate 的 base
  //    可能以 assistant 结尾(目标节点之前的消息),若直接 seed 会把新流并入旧
  //    消息;故追加空 assistant 占位作为流式尾部(与 Android 生成时尾部占位同构)
  const accumulator = new MessageStreamAccumulator(
    [...baseMessages, makeUIMessage('assistant', [])]);
  let sawChunk: boolean = false;
  await deps.provider.streamText(internalMessages, (chunk: MessageChunk): void => {
    sawChunk = true;
    accumulator.append(chunk);
    if (deps.onUpdate !== undefined) {
      deps.onUpdate(accumulator.snapshot());
    }
  });
  if (!sawChunk) {
    return conversation;
  }

  // 4. 终态 transforms → 新 assistant 消息追加进目标节点(追加不覆盖)
  const finalMessages: UIMessage[] = applyOnGenerationFinish(
    accumulator.snapshot(), deps.outputTransformers, ctx,
  );
  const generated: UIMessage[] = finalMessages.slice(baseMessages.length);
  if (generated.length === 0) {
    return conversation;
  }
  const newMessages: UIMessage[] = [...target.messages, ...generated];
  const updatedNode: MessageNode = {
    ...target,
    messages: newMessages,
    selectIndex: newMessages.length - 1,
  };
  const out: Conversation = {
    ...conversation,
    messageNodes: conversation.messageNodes.map(
      (n: MessageNode): MessageNode => (n.id === nodeId ? updatedNode : n)),
    updateAt: nowIso(),
  };
  await deps.store.save(out);
  return out;
};
