// regenerate — assistant 分支再生 + user 截断重跑 + 分支切换
//
// Android 基准:
//   - ChatService.regenerateAtMessage(ChatService.kt:1078-1118):
//     · assistant 消息:以目标节点之前的消息(messageRange 0..<nodeIndex)为上下文
//       重跑;新结果追加进同一 MessageNode("regenerate 追加不覆盖")
//     · user 消息(:1091-1099):截断目标之后的节点(subList 0..indexAt),**先持久化
//       截断**再重跑生成,新 assistant 作为新节点入列
//   - ChatService.selectMessageNode(ChatVM.kt:420 调用链):设置节点 selectIndex 并持久化
//
// 与 Android 的偏差(已登记 PARITY_DEBT):
//   - Android 的窗口化 merge(streamingTail/mergeGeneratedMessagesIntoWindow/时间线分页)
//     依附于其分页架构;本实现为扁平节点模型下的等价语义
//   - Android regenerate 会 invalidateCompacts;ConversationContextEngine = P1,无对应物

import type { Conversation, MessageNode } from './conversation.ts';
import { currentMessages, nodeRole, toMessageNode } from './conversation.ts';
import type { MessageChunk, UIMessage } from './message.ts';
import { makeUIMessage } from './message.ts';
import { MessageStreamAccumulator } from './stream_accumulator.ts';
import type { Assistant } from './assistant.ts';
import { assembleInternalMessages } from './context_assembly.ts';
import type {
  MessageTransformer, OutputMessageTransformer, TransformerContext,
} from './transformer_pipeline.ts';
import { applyInputTransformers, applyOnGenerationFinish } from './transformer_pipeline.ts';
import type { ChatStreamProvider, ConversationStore, StreamOpts } from './chat_turn.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';
import { nowIso } from './ids.ts';
import {
  decideGenerationRetry, makeGenerationRetrySetting, RETRY_STATUS_TEMPLATE,
} from './generation_retry.ts';
import type { GenerationRetrySetting } from './generation_retry.ts';

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
  // 停止生成(D-035 扩展):abort → 部分快照照常入列(分支追加/新节点),正常 resolve
  abortSignal?: AbortSignalLike;
  // 生成重试(D-050,chat_turn 同语义)
  retrySetting?: GenerationRetrySetting;
  sleep?: (ms: number) => Promise<void>;
  onRetryStatus?: (status: string | null) => void;
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
  const isUserNode: boolean = nodeRole(target) === 'user';

  // 1. 上下文基底
  //    assistant 节点:目标节点之前的 currentMessages(Android messageRange 0..<nodeIndex)
  //    user 节点:截断目标之后节点,**先持久化截断**(ChatService.kt:1091-1099),
  //    基底含该 user 消息
  let working: Conversation = conversation;
  if (isUserNode) {
    working = {
      ...conversation,
      messageNodes: conversation.messageNodes.slice(0, nodeIndex + 1),
      updateAt: nowIso(),
    };
    await deps.store.save(working);
  }
  const priorConversation: Conversation = isUserNode
    ? working
    : { ...conversation, messageNodes: conversation.messageNodes.slice(0, nodeIndex) };
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

  // 3. provider 流式(重试见下;不可重试错误直接传播 — 对齐 runChatTurn;
  //    user 节点路径截断已持久化,与 Android 同序)
  //    accumulator 语义:末条 initial 消息是"活动流式消息"。assistant 分支再生时
  //    base 可能以 assistant 结尾(目标节点之前的消息),若直接 seed 会把新流并入
  //    旧消息;故追加空 assistant 占位作为流式尾部(与 Android 生成时尾部占位同构);
  //    user 节点路径 base 以 user 结尾,新 assistant delta 自然开新消息,无需占位
  const seed: UIMessage[] = isUserNode
    ? baseMessages
    : [...baseMessages, makeUIMessage('assistant', [])];
  const streamOpts: StreamOpts | undefined = deps.abortSignal !== undefined
    ? { signal: deps.abortSignal }
    : undefined;
  // D-040:streamOutput=false 且 provider 支持 → 非流式单次补全(chat_turn 同语义)
  const useNonStream: boolean = deps.assistant.streamOutput === false
    && deps.provider.generateText !== undefined;
  // D-050 重试循环(chat_turn 同构,runProviderCallWithRetry:774-820 忠实):
  //   每次尝试全新 accumulator(失败尝试的部分输出丢弃);
  //   sawChunk 仅累计「最终存活尝试」的 chunk
  const retrySetting: GenerationRetrySetting = deps.retrySetting ?? makeGenerationRetrySetting({});
  const sleep: (ms: number) => Promise<void> = deps.sleep
    ?? ((ms: number): Promise<void> => new Promise((resolve): void => {
      setTimeout(resolve, ms);
    }));
  let attempt: number = 1;
  let lastAccumulator: MessageStreamAccumulator = new MessageStreamAccumulator(seed);
  let sawChunk: boolean = false;
  const isAborted = (): boolean =>
    deps.abortSignal !== undefined && deps.abortSignal.aborted;
  while (true) {
    // 首次尝试必跑(abort 检查在 catch 与 sleep 后,chat_turn 同语义)
    const accumulator: MessageStreamAccumulator = new MessageStreamAccumulator(seed);
    lastAccumulator = accumulator;
    sawChunk = false;
    try {
      if (useNonStream && deps.provider.generateText !== undefined) {
        const chunk: MessageChunk = await deps.provider.generateText(internalMessages, streamOpts);
        sawChunk = true;
        accumulator.append(chunk);
        if (deps.onUpdate !== undefined) {
          deps.onUpdate(accumulator.snapshot());
        }
      } else {
        await deps.provider.streamText(internalMessages, (chunk: MessageChunk): void => {
          sawChunk = true;
          accumulator.append(chunk);
          if (deps.onUpdate !== undefined) {
            deps.onUpdate(accumulator.snapshot());
          }
        }, streamOpts);
      }
      if (deps.onRetryStatus !== undefined) deps.onRetryStatus(null);
      break;
    } catch (e) {
      // abort(停止生成):部分快照照常终态化入列(chat_turn D-035 同语义)
      if (isAborted()) break;
      const err: Error = e instanceof Error ? e : new Error(String(e));
      const decision = decideGenerationRetry(err, attempt, retrySetting);
      if (!decision.retryable) {
        // 不可重试/超次数:原错误传播(user 路径截断保持已持久化,Android 同序)
        if (deps.onRetryStatus !== undefined) deps.onRetryStatus(null);
        throw err;
      }
      // onBeforeRetry:seed 快照全量通知(UI 丢弃失败尝试的部分输出)
      if (deps.onUpdate !== undefined) {
        deps.onUpdate(seed);
      }
      if (deps.onRetryStatus !== undefined) {
        const seconds: number = Math.max(1, Math.trunc(decision.delayMs / 1000));
        deps.onRetryStatus(
          RETRY_STATUS_TEMPLATE
            .replace('%1$d', String(seconds))
            .replace('%2$d', String(attempt))
            .replace('%3$d', String(retrySetting.maxRetries))
            .replace('%4$s', decision.reason),
        );
      }
      await sleep(decision.delayMs);
      attempt++;
      // 重试等待中 abort:不发起新尝试(chat_turn 同语义)
      if (isAborted()) break;
    }
  }
  if (!sawChunk) {
    return isUserNode ? working : conversation;
  }

  // 4. 终态 transforms → 入列
  const finalMessages: UIMessage[] = applyOnGenerationFinish(
    lastAccumulator.snapshot(), deps.outputTransformers, ctx,
  );
  const generated: UIMessage[] = finalMessages.slice(baseMessages.length);
  if (generated.length === 0) {
    return isUserNode ? working : conversation;
  }
  if (isUserNode) {
    // user 截断重跑:新 assistant 作为新节点入列(runChatTurn 同构)
    const out: Conversation = {
      ...working,
      messageNodes: [...working.messageNodes, ...generated.map((m: UIMessage): MessageNode => toMessageNode(m))],
      updateAt: nowIso(),
    };
    await deps.store.save(out);
    return out;
  }
  // assistant 分支再生:新消息追加进目标节点(追加不覆盖),selectIndex 指向新分支
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
