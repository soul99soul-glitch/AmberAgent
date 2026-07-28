// chat turn — Agent loop 骨架(source-only 阶段)
//
// Android 基准: app/core/ai/GenerationHandler.kt stream 路径(:473-545 骨架)
//   user 消息入列 → input transforms → provider.streamText → accumulator.append
//   (节流 onUpdate + streamingTail visual)→ 终态 snapshot → onGenerationFinish
//   → assistant 节点入列 → persist
//
// 重试(D-050):runProviderCallWithRetry(:774-820)忠实 — 失败后 classify
//   (generation_retry.ts),可重试 → onBeforeRetry(重置 baseMessages 全量
//   通知 UI,:625-628)→ 状态文案(strings.xml:386)→ delay → 重新整轮;
//   不可重试/超次数 → 原错误传播。每次尝试用全新 accumulator,
//   失败尝试的部分输出不混入后续尝试。
//
// 裁剪(D-012/PD-006):
//   - vision fallback(D-071a 已落地:modelSupportsImageInput + 分类器 +
//     forceImageToText 重跑,vision_fallback.ts;entry 侧 OCR 接线 = D-071b)
//   - provider/persist 为 Port;chunk 用回调式(对齐 deepread HttpClient.fetchStream 风格,
//     避免 AsyncIterable 在 ArkTS 的不确定性)
//   - 错误路径:不可重试错误直接传播;user 消息快照已持久化,assistant 节点不入列

import type { Conversation } from './conversation.ts';
import { currentMessages, toMessageNode } from './conversation.ts';
import { makeUserMessage } from './message.ts';
import type { MessageChunk, UIMessage, UIMessagePart } from './message.ts';
import { makeUIMessage } from './message.ts';
import { MessageStreamAccumulator } from './stream_accumulator.ts';
import type { Assistant } from './assistant.ts';
import { assembleInternalMessages } from './context_assembly.ts';
import type {
  MessageTransformer, OutputMessageTransformer, TransformerContext,
} from './transformer_pipeline.ts';
import {
  applyInputTransformers, applyOnGenerationFinish, applyVisualTransformersStreamingTail,
} from './transformer_pipeline.ts';
import { nowIso } from './ids.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';
import {
  decideGenerationRetry, makeGenerationRetrySetting, RETRY_STATUS_TEMPLATE,
} from './generation_retry.ts';
import type { GenerationRetrySetting } from './generation_retry.ts';
import { runWithVisionFallback } from './vision_fallback.ts';
import type { VisionFallbackHook } from './vision_fallback.ts';

// ===== Ports =====

export interface StreamOpts {
  signal?: AbortSignalLike;
}

// 流式 provider:回调式 chunk 推送;resolve = 流正常结束,reject = 失败
// opts.signal(D-035):中止信号透传至 API/HTTP 层
export interface ChatStreamProvider {
  streamText(
    messages: UIMessage[], onChunk: (chunk: MessageChunk) => void, opts?: StreamOpts,
  ): Promise<void>;
  // 非流式单次补全(D-040):assistant.streamOutput=false 时优先调用
  //   (Android GenerationHandler.kt:255 stream = assistant.streamOutput →
  //   ChatCompletionsAPI.generateText);未实现时回退流式(Port 可选,
  //   Android provider 接口双实现恒在)
  generateText?(messages: UIMessage[], opts?: StreamOpts): Promise<MessageChunk>;
}

// 会话持久化 Port(真机 adapter 走 RDB/文件;测试用 memory 实现)
export interface ConversationStore {
  save(conv: Conversation): Promise<void>;
}

export interface MemoryConversationStore extends ConversationStore {
  saved: Conversation[];
}

export const createMemoryConversationStore = (): MemoryConversationStore => {
  const saved: Conversation[] = [];
  return {
    saved,
    save(conv: Conversation): Promise<void> {
      saved.push(conv);
      return Promise.resolve();
    },
  };
};

// ===== runChatTurn =====

export interface ChatTurnDeps {
  assistant: Assistant;
  inputTransformers: MessageTransformer[];
  outputTransformers: OutputMessageTransformer[];
  provider: ChatStreamProvider;
  store: ConversationStore;
  // 上下文组装(D-018):agentSoul 静态块前缀;contextMessageSize 截断(0=不截断)
  agentSoul?: string;
  contextMessageSize?: number;
  // 流式 UI 刷新节流(Android STREAM_UI_FLUSH_INTERVAL_MS);0 = 每个 chunk 都刷新
  flushIntervalMs?: number;
  nowMs?: () => number;
  onUpdate?: (messages: UIMessage[]) => void;
  // D-063:raw 快照回调(GenerationHandler.kt:519-526/:540-543 — 节流 flush 与
  //   流终两处,**未经 visual transforms**;推测执行观察点。与 onUpdate 独立:
  //   Android 节流检查不依赖 UI 监听者)
  onRawFlushSnapshot?: (messages: UIMessage[]) => void;
  // 停止生成(D-035):abort 信号透传 provider;provider 抛错且 signal.aborted
  //   → 部分快照走终态 transforms 入列并持久化,正常 resolve(对齐 Android
  //   CancellationException → checkpointConversation(force) 语义);
  //   非 abort 错误维持传播(D-012)
  abortSignal?: AbortSignalLike;
  // 生成重试(D-050,GenerationRetry.kt):默认 enabled/5 次/1s 起步指数/jitter 0.15;
  //   sleep 可注入(测试即时);onRetryStatus 状态回调(strings.xml:386 文案,
  //   成功或不可重试抛错前回 null,Android processingStatus 语义)
  retrySetting?: GenerationRetrySetting;
  sleep?: (ms: number) => Promise<void>;
  onRetryStatus?: (status: string | null) => void;
  // 上下文压缩(D-055,ConversationContextEngine.prepareContext:112-286):
  //   提供则替代 limitContext 窗口(edit→plan→compact→注入→fit);
  //   输入为当前会话消息(含新 user),返回发往 provider 的消息序列
  //   (system 前置与 input transforms 仍由本函数在返回后施加,次序对齐
  //   GenerationHandler:434-446)
  prepareContextMessages?: (messages: UIMessage[]) => Promise<UIMessage[]>;
  // D-071a:模型是否支持图像输入(model.inputModalities.contains(IMAGE),
  //   GenerationHandler.kt:431);提供且为 true 时启用视觉兜底 — 首轮生成错误
  //   命中分类器(vision_fallback.ts)则 forceImageToText 重跑;
  //   未提供 = 不兜底(等价 Android 模型不含 IMAGE 模态)
  modelSupportsImageInput?: boolean;
  // D-085b:记忆召回块供给(GenerationHandler.kt:414 → :727,memoryContextPrompt);
  //   动态块序最前(memory → loopBudget → generativeUi → recentChats),
  //   组装时拼在 extraSystemBlocks 之前;每次组装调用时取数
  memorySystemBlocks?: () => Promise<string[]>;
  // D-077b:动态 system 块供给(GenerationHandler.kt:726-736 — memory/generativeUi/
  //   recentChats 之 recentChats 子集;loopBudget 由 tool_loop 前置);
  //   每次组装调用时取数(Android 每次 generateInternal 重算语义)
  extraSystemBlocks?: () => Promise<string[]>;
}

const appendNode = (conv: Conversation, msg: UIMessage): Conversation => ({
  ...conv,
  messageNodes: [...conv.messageNodes, toMessageNode(msg)],
  updateAt: nowIso(),
});

// isEmptyInputMessage 等价(D-038):全空文本 parts → no-op
const isEmptyInput = (parts: UIMessagePart[]): boolean =>
  parts.length === 0
  || parts.every((p: UIMessagePart): boolean => p.type === 'text' && p.text.trim().length === 0);

// ===== answer=false 派发(D-051,ChatService.kt:847-850) =====

// appendUserMessage 等价:user 消息入列 + 持久化,**不触发生成**。
// 队列循环中 answer=false 的派发走此路径;空输入与 runChatTurn 同 no-op 语义。
export const runAppendUserMessage = async (
  conversation: Conversation, userInput: string | UIMessagePart[], store: ConversationStore,
): Promise<Conversation> => {
  const userParts: UIMessagePart[] = typeof userInput === 'string'
    ? makeUserMessage(userInput).parts
    : userInput;
  if (isEmptyInput(userParts)) {
    return conversation;
  }
  const conv: Conversation = appendNode(conversation, makeUIMessage('user', userParts));
  await store.save(conv);
  return conv;
};

// userInput:string(纯文本)或 UIMessagePart[](多模态,D-038)
export const runChatTurn = async (
  conversation: Conversation, userInput: string | UIMessagePart[], deps: ChatTurnDeps,
): Promise<Conversation> => {
  const userParts: UIMessagePart[] = typeof userInput === 'string'
    ? makeUserMessage(userInput).parts
    : userInput;
  if (isEmptyInput(userParts)) {
    return conversation;
  }
  // 1. user 消息入列 + 持久化(对齐 Android 先落 user 再生成)
  let conv: Conversation = appendNode(conversation, makeUIMessage('user', userParts));
  await deps.store.save(conv);

  const baseMessages: UIMessage[] = currentMessages(conv);
  // D-071a:processingStatus 透传(Android TransformerContext.processingStatus
  //   与 GenerationHandler 同一 StateFlow — OcrTransformer 状态文案经此上屏)
  const ctx: TransformerContext = { assistant: deps.assistant, processingStatus: deps.onRetryStatus };

  // 2. 上下文组装:prepareContext(D-055,提供时:edit→compact→注入替换)
  //    或 limitContext 窗口(D-018)→ system 前置 → input transforms(次序对齐
  //    GenerationHandler:434-446:先 [system]+preparedContext 再 transforms);
  //    均作用于发往 provider 的副本,不污染会话本体
  const preparedMessages: UIMessage[] = deps.prepareContextMessages !== undefined
    ? await deps.prepareContextMessages(baseMessages)
    : baseMessages;
  const assembled: UIMessage[] = assembleInternalMessages({
    messages: preparedMessages,
    assistant: deps.assistant,
    agentSoul: deps.agentSoul ?? '',
    // prepareContext 内部已处理截断/替换;未提供时走原窗口
    contextMessageSize: deps.prepareContextMessages !== undefined
      ? 0
      : (deps.contextMessageSize ?? 0),
    // D-085b:memory 块在动态序最前(GenerationHandler.kt:727)
    extraSystemBlocks: [
      ...(deps.memorySystemBlocks !== undefined ? await deps.memorySystemBlocks() : []),
      ...(deps.extraSystemBlocks !== undefined ? await deps.extraSystemBlocks() : []),
    ],
  });
  const internalMessages: UIMessage[] = await applyInputTransformers(
    assembled, deps.inputTransformers, ctx);
  // D-071a:视觉兜底重建闭包(prepareInternalMessages(forceImageToText = true),
  //   GenerationHandler.kt:613/:648);模型不支持图像时整条兜底不启用
  const visionFallback: VisionFallbackHook | undefined =
    deps.modelSupportsImageInput === undefined ? undefined : {
      modelSupportsImageInput: deps.modelSupportsImageInput,
      rebuildInternalMessages: (): Promise<UIMessage[]> => applyInputTransformers(
        assembled, deps.inputTransformers, { ...ctx, forceImageToText: true }),
    };

  // 3. 生成一步(D-057 抽取出共享核心;单跑行为与 D-050 逐字节一致)
  const finalMessages: UIMessage[] = await generateAssistantOnce(
    baseMessages, internalMessages, deps, undefined, visionFallback);

  // 4. 终态:assistant 节点入列 → 持久化
  if (finalMessages.length > baseMessages.length) {
    for (const msg of finalMessages.slice(baseMessages.length)) {
      conv = appendNode(conv, msg);
    }
    await deps.store.save(conv);
  }
  return conv;
};

// ===== 生成一步(D-057 抽取,供单跑/工具循环共用) =====
// 语义与抽取前 runChatTurn 第 3-4 段逐字节一致:流式/非流式 → 节流 onUpdate
//   (streamingTail visual)→ D-050 重试循环(失败尝试丢弃,base 全量通知)→
//   abort 部分快照终态化 → onGenerationFinish。返回全量 finalMessages
//   (seed + 生成),入列由调用方决定。
export const generateAssistantOnce = async (
  seedMessages: UIMessage[], internalMessages: UIMessage[], deps: ChatTurnDeps,
  providerOverride?: ChatStreamProvider, visionFallback?: VisionFallbackHook,
): Promise<UIMessage[]> => {
  const provider: ChatStreamProvider = providerOverride ?? deps.provider;
  const ctx: TransformerContext = { assistant: deps.assistant };
  // 流式 UI 刷新节流(Android STREAM_UI_FLUSH_INTERVAL_MS);0 = 每个 chunk 都刷新
  const flushIntervalMs: number = deps.flushIntervalMs ?? 50;
  const nowMs: () => number = deps.nowMs ?? ((): number => Date.now());
  const streamOpts: StreamOpts | undefined = deps.abortSignal !== undefined
    ? { signal: deps.abortSignal }
    : undefined;
  // D-040:streamOutput=false 且 provider 支持 → 非流式单次补全(Android
  //   GenerationHandler.kt:255);chunk 以 message 形式进同一 accumulator
  const useNonStream: boolean = deps.assistant.streamOutput === false
    && provider.generateText !== undefined;
  // D-050 重试循环(runProviderCallWithRetry:774-820 忠实):
  //   每次尝试全新 accumulator(失败尝试的部分输出丢弃);
  //   sawChunk 仅累计「最终存活尝试」的 chunk
  const retrySetting: GenerationRetrySetting = deps.retrySetting ?? makeGenerationRetrySetting({});
  const sleep: (ms: number) => Promise<void> = deps.sleep
    ?? ((ms: number): Promise<void> => new Promise((resolve): void => {
      setTimeout(resolve, ms);
    }));
  let attempt: number = 1;
  let lastAccumulator: MessageStreamAccumulator = new MessageStreamAccumulator(seedMessages);
  let lastFlushAt: number = 0;
  const isAborted = (): boolean =>
    deps.abortSignal !== undefined && deps.abortSignal.aborted;
  while (true) {
    // 首次尝试必跑(abort 检查在 catch 与 sleep 后 — provider 抛错时
    //   signal.aborted=true 的既有语义不回归,D-035)
    const accumulator: MessageStreamAccumulator = new MessageStreamAccumulator(seedMessages);
    lastAccumulator = accumulator;
    lastFlushAt = 0;
    // D-071a:provider 调用闭包 — 视觉兜底重跑与首轮共用**同一 accumulator**
    //   (GenerationHandler.kt:601-665:fallback 生成接续写入同一消息流)
    const callProvider = async (msgs: UIMessage[]): Promise<void> => {
      if (useNonStream && provider.generateText !== undefined) {
        const chunk: MessageChunk = await provider.generateText(msgs, streamOpts);
        accumulator.append(chunk);
        if (deps.onUpdate !== undefined) {
          const snapshot: UIMessage[] = accumulator.snapshot();
          deps.onUpdate(applyVisualTransformersStreamingTail(snapshot, deps.outputTransformers, ctx));
        }
      } else {
        await provider.streamText(msgs, (chunk: MessageChunk): void => {
          accumulator.append(chunk);
          // D-063:节流检查移出 onUpdate 门(Android :519-526 不依赖 UI 监听者)
          const now: number = nowMs();
          if (lastFlushAt === 0 || now - lastFlushAt >= flushIntervalMs) {
            lastFlushAt = now;
            if (deps.onRawFlushSnapshot !== undefined || deps.onUpdate !== undefined) {
              const snapshot: UIMessage[] = accumulator.snapshot();
              if (deps.onRawFlushSnapshot !== undefined) {
                deps.onRawFlushSnapshot(snapshot);
              }
              if (deps.onUpdate !== undefined) {
                deps.onUpdate(applyVisualTransformersStreamingTail(
                  snapshot, deps.outputTransformers, ctx));
              }
            }
          }
        }, streamOpts);
        // D-063(:540-543):流终 raw 快照观察点(成功收流后、终态前)
        if (deps.onRawFlushSnapshot !== undefined) {
          deps.onRawFlushSnapshot(accumulator.snapshot());
        }
      }
    };
    try {
      // D-071a:内层视觉兜底(:601-665);不可兜底/兜底自身错误 → 外层重试循环
      await runWithVisionFallback(
        internalMessages, visionFallback, callProvider, deps.onRetryStatus);
      // 成功:状态清零(Android :787 processingStatus.value = null)
      if (deps.onRetryStatus !== undefined) deps.onRetryStatus(null);
      break;
    } catch (e) {
      // abort(停止生成):部分快照照常终态化入列(Android checkpoint force 语义)
      if (isAborted()) break;
      const err: Error = e instanceof Error ? e : new Error(String(e));
      const decision = decideGenerationRetry(err, attempt, retrySetting);
      if (!decision.retryable) {
        // 不可重试/超次数:状态清零后原错误传播(Android :797)
        if (deps.onRetryStatus !== undefined) deps.onRetryStatus(null);
        throw err;
      }
      // onBeforeRetry(:625-628):messages = baseMessages + 全量 onUpdate
      //   —— 失败尝试的部分输出被丢弃,UI 回到 user 尾部
      if (deps.onUpdate !== undefined) {
        deps.onUpdate(applyVisualTransformersStreamingTail(seedMessages, deps.outputTransformers, ctx));
      }
      // 状态文案(strings.xml:386;秒 = delayMs/1000 整除 coerceAtLeast 1)
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
      // 重试等待中点停止(含即时 sleep):不发起新尝试,走部分快照终态化
      if (isAborted()) break;
    }
  }

  // 终态:onGenerationFinish transforms(入列在调用方)
  return await applyOnGenerationFinish(lastAccumulator.snapshot(), deps.outputTransformers, ctx);
};
