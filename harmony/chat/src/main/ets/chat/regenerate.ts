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
//   - Android regenerate 会 invalidateCompacts;D-055 起由 entry 层接线
//     (ChatPage.regenerate → invalidateConvCompacts('message_regenerated'))

import type { Conversation, MessageNode } from './conversation.ts';
import { currentMessages, nodeRole, toMessageNode } from './conversation.ts';
import type { MessageChunk, UIMessage } from './message.ts';
import { finishAssistantMessage, makeUIMessage } from './message.ts';
import { MessageStreamAccumulator } from './stream_accumulator.ts';
import { StreamSnapshotGate } from './stream_snapshot_gate.ts';
import type { Assistant } from './assistant.ts';
import { assembleInternalMessages } from './context_assembly.ts';
import { fitMessagesToTokenBudget } from './context_compact.ts';
import type {
  MessageTransformer, OutputMessageTransformer, TransformerContext,
} from './transformer_pipeline.ts';
import { applyInputTransformers, applyOnGenerationFinish } from './transformer_pipeline.ts';
import type { ChatStreamProvider, ConversationStore, StreamOpts } from './chat_turn.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';
import { nowIso } from './ids.ts';
import { waitForGenerationRetry } from './generation_retry_wait.ts';
import {
  decideGenerationRetry, makeGenerationRetrySetting, RETRY_STATUS_TEMPLATE,
} from './generation_retry.ts';
import type { GenerationRetrySetting } from './generation_retry.ts';
import { runWithVisionFallback } from './vision_fallback.ts';
import type { VisionFallbackHook } from './vision_fallback.ts';

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
  wrapStreamProvider?: (provider: ChatStreamProvider) => ChatStreamProvider;
  store: ConversationStore;
  agentSoul?: string;
  contextMessageSize?: number;
  // D-085b:记忆召回块供给(chat_turn 同语义,动态序最前)
  memorySystemBlocks?: () => Promise<string[]>;
  // D-077b:动态 system 块供给(chat_turn 同语义,每次组装重取)
  extraSystemBlocks?: () => Promise<string[]>;
  // D-055:压缩引擎钩子(chat_turn 同语义);存在时组装以其结果为基底,
  //   contextMessageSize 强制 0(引擎内部截断,避免双重截断)
  prepareContextMessages?: (messages: UIMessage[]) => Promise<UIMessage[]>;
  onUpdate?: (messages: UIMessage[]) => void;
  // raw accumulator 快照观察点(周期 checkpoint;与 ChatTurnDeps 同语义)
  onRawFlushSnapshot?: (messages: UIMessage[]) => void;
  nowMs?: () => number;
  flushIntervalMs?: number;
  // 停止生成(D-035 扩展):abort → 部分快照照常入列(分支追加/新节点),正常 resolve
  abortSignal?: AbortSignalLike;
  // 生成重试(D-050,chat_turn 同语义)
  retrySetting?: GenerationRetrySetting;
  sleep?: (ms: number) => Promise<void>;
  onRetryStatus?: (status: string | null) => void;
  // D-071a:模型是否支持图像输入(GenerationHandler.kt:431);true 时启用视觉兜底
  modelSupportsImageInput?: boolean;
  // 发送边界最终预算(chat_turn 同语义:组装后置 system 块之后的兜底 fit)
  finalTokenBudget?: number;
}

// ===== 再生基底(D-057 提取:runRegenerateAt 与 runRegenerateAtWithTools 共享) =====

export interface RegenerateSeed {
  // 生成基底会话:assistant 节点 = 目标节点之前(messageRange 0..<nodeIndex);
  //   user 节点 = 截断目标之后(含该 user 消息,**已持久化**,ChatService.kt:1091-1099)
  seed: Conversation;
  nodeIndex: number;
  isUserNode: boolean;
}

export const prepareRegenerateSeed = async (
  conversation: Conversation, nodeId: string, store: ConversationStore,
): Promise<RegenerateSeed> => {
  const nodeIndex: number = conversation.messageNodes.findIndex(
    (n: MessageNode): boolean => n.id === nodeId);
  if (nodeIndex < 0) {
    throw new Error(`runRegenerateAt: node not found: ${nodeId}`);
  }
  const target: MessageNode = conversation.messageNodes[nodeIndex];
  const isUserNode: boolean = nodeRole(target) === 'user';
  let working: Conversation = conversation;
  if (isUserNode) {
    working = {
      ...conversation,
      messageNodes: conversation.messageNodes.slice(0, nodeIndex + 1),
      updateAt: nowIso(),
    };
    await store.save(working);
  }
  const seed: Conversation = isUserNode
    ? working
    : { ...conversation, messageNodes: conversation.messageNodes.slice(0, nodeIndex) };
  return { seed, nodeIndex, isUserNode };
};

export const runRegenerateAt = async (
  conversation: Conversation, nodeId: string, deps: RegenerateDeps,
): Promise<Conversation> => {
  // 1. 上下文基底(D-057 起走 prepareRegenerateSeed,语义不变)
  const seedInfo: RegenerateSeed = await prepareRegenerateSeed(conversation, nodeId, deps.store);
  const nodeIndex: number = seedInfo.nodeIndex;
  const isUserNode: boolean = seedInfo.isUserNode;
  const working: Conversation = seedInfo.seed;
  const target: MessageNode = conversation.messageNodes[nodeIndex];
  const priorConversation: Conversation = working;
  const baseMessages: UIMessage[] = currentMessages(priorConversation);
  const ctx: TransformerContext = { assistant: deps.assistant, processingStatus: deps.onRetryStatus };

  // 2. 组装 + input transforms(作用于发往 provider 的副本,不污染会话本体);
  //    D-055:prepareContextMessages 存在时引擎接管(压缩/编辑/截断),
  //    assembleInternalMessages 的 contextMessageSize 强制 0(chat_turn 同语义)
  const preparedMessages: UIMessage[] = deps.prepareContextMessages !== undefined
    ? await deps.prepareContextMessages(baseMessages)
    : baseMessages;
  const assembledBase: UIMessage[] = assembleInternalMessages({
    messages: preparedMessages,
    assistant: deps.assistant,
    agentSoul: deps.agentSoul ?? '',
    contextMessageSize: deps.prepareContextMessages !== undefined
      ? 0
      : (deps.contextMessageSize ?? 0),
    // D-085b:memory 块在动态序最前(GenerationHandler.kt:727)
    extraSystemBlocks: [
      ...(deps.memorySystemBlocks !== undefined ? await deps.memorySystemBlocks() : []),
      ...(deps.extraSystemBlocks !== undefined ? await deps.extraSystemBlocks() : []),
    ],
  });
  // 文档/OCR/模板物化后再 fit，与普通发送使用同一预算位置。
  const prepareInternalMessages = async (forceImageToText: boolean = false): Promise<UIMessage[]> => {
    const transformed: UIMessage[] = await applyInputTransformers(
      assembledBase, deps.inputTransformers, forceImageToText ? { ...ctx, forceImageToText: true } : ctx);
    return deps.finalTokenBudget !== undefined
      ? fitMessagesToTokenBudget(transformed, deps.finalTokenBudget) : transformed;
  };
  const internalMessages: UIMessage[] = await prepareInternalMessages();
  const visionFallback: VisionFallbackHook | undefined =
    deps.modelSupportsImageInput === undefined ? undefined : {
      modelSupportsImageInput: deps.modelSupportsImageInput,
      rebuildInternalMessages: (): Promise<UIMessage[]> => prepareInternalMessages(true),
    };

  // 3. provider 流式(重试见下;不可重试错误直接传播 — 对齐 runChatTurn;
  //    user 节点路径截断已持久化,与 Android 同序)
  //    accumulator 语义:末条 initial 消息是"活动流式消息"。assistant 分支再生时
  //    base 可能以 assistant 结尾(目标节点之前的消息),若直接 seed 会把新流并入
  //    旧消息;故追加空 assistant 占位作为流式尾部(与 Android 生成时尾部占位同构);
  //    user 节点路径 base 以 user 结尾,新 assistant delta 自然开新消息,无需占位
  const seed: UIMessage[] = isUserNode
    ? baseMessages
    : [...baseMessages, makeUIMessage('assistant', [])];
  const streamOpts: StreamOpts = {
    signal: deps.abortSignal,
    baselineMessages: seed,
  };
  const provider: ChatStreamProvider = deps.wrapStreamProvider !== undefined
    ? deps.wrapStreamProvider(deps.provider) : deps.provider;
  // D-040:streamOutput=false 且 provider 支持 → 非流式单次补全(chat_turn 同语义)
  const useNonStream: boolean = deps.assistant.streamOutput === false
    && provider.generateText !== undefined;
  const nowMs: () => number = deps.nowMs ?? ((): number => Date.now());
  const flushIntervalMs: number = deps.flushIntervalMs ?? 48;
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
    // D-071a:provider 调用闭包 — 视觉兜底重跑与首轮共用同一 accumulator
    //   (chat_turn generateAssistantOnce 同构,GenerationHandler.kt:601-665)
    const callProvider = async (msgs: UIMessage[]): Promise<void> => {
      if (useNonStream && provider.generateText !== undefined) {
        const chunk: MessageChunk = await provider.generateText(msgs, streamOpts);
        // 只有携带实际 delta/message 的 chunk 才算有产出:finish-only/usage-only
        // 尾块(choices 空)不得点亮 sawChunk,否则空占位会追加成新分支
        if (chunk.choices.length > 0
          && (chunk.choices[0].delta !== null || chunk.choices[0].message !== null)) {
          sawChunk = true;
        }
        accumulator.append(chunk);
        if (deps.onRawFlushSnapshot !== undefined || deps.onUpdate !== undefined) {
          const snapshot: UIMessage[] = accumulator.snapshot();
          deps.onRawFlushSnapshot?.(snapshot);
          deps.onUpdate?.(snapshot);
        }
      } else {
        const gate: StreamSnapshotGate = new StreamSnapshotGate(flushIntervalMs, nowMs, (): void => {
          const snapshot: UIMessage[] = accumulator.snapshot();
          deps.onRawFlushSnapshot?.(snapshot);
          deps.onUpdate?.(snapshot);
        });
        try {
          await provider.streamText(msgs, (chunk: MessageChunk): void => {
            if (chunk.choices.length > 0
              && (chunk.choices[0].delta !== null || chunk.choices[0].message !== null)) {
              sawChunk = true;
            }
            accumulator.append(chunk);
            if (deps.onRawFlushSnapshot !== undefined || deps.onUpdate !== undefined) gate.changed();
          }, streamOpts);
        } finally {
          const publishedTail: boolean = gate.finish();
          if (!publishedTail) deps.onRawFlushSnapshot?.(accumulator.snapshot());
        }
      }
    };
    try {
      // D-071a:内层视觉兜底;不可兜底/兜底自身错误 → 外层重试循环(chat_turn 同语义)
      await runWithVisionFallback(
        internalMessages, visionFallback, callProvider, deps.onRetryStatus);
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
      await waitForGenerationRetry(decision.delayMs, sleep, deps.abortSignal);
      attempt++;
      // 重试等待中 abort:不发起新尝试,丢弃失败尝试的部分输出。
      if (isAborted()) {
        lastAccumulator = new MessageStreamAccumulator(seed);
        sawChunk = false;
        break;
      }
    }
  }
  if (!sawChunk) {
    return isUserNode ? working : conversation;
  }

  // 4. 终态 transforms → 入列
  const transformed: UIMessage[] = await applyOnGenerationFinish(
    lastAccumulator.snapshot(), deps.outputTransformers, ctx,
  );
  let finalMessages: UIMessage[] = transformed;
  if (!isAborted() && transformed.length > 0) {
    const lastIndex: number = transformed.length - 1;
    const last: UIMessage = finishAssistantMessage(transformed[lastIndex]);
    if (last !== transformed[lastIndex]) {
      finalMessages = [...transformed.slice(0, lastIndex), last];
    }
  }
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
