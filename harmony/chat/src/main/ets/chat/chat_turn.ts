// chat turn — Agent loop 骨架(source-only 阶段)
//
// Android 基准: app/core/ai/GenerationHandler.kt stream 路径(:473-545 骨架)
//   user 消息入列 → input transforms → provider.streamText → accumulator.append
//   (节流 onUpdate + streamingTail visual)→ 终态 snapshot → onGenerationFinish
//   → assistant 节点入列 → persist
//
// 裁剪(D-012/PD-006):
//   - retry/vision fallback/工具执行/审批/生成守卫 = P1 范围,不在骨架
//   - provider/persist 为 Port;chunk 用回调式(对齐 deepread HttpClient.fetchStream 风格,
//     避免 AsyncIterable 在 ArkTS 的不确定性)
//   - 错误路径:provider 抛错直接传播;user 消息快照已持久化,assistant 节点不入列

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
  // 停止生成(D-035):abort 信号透传 provider;provider 抛错且 signal.aborted
  //   → 部分快照走终态 transforms 入列并持久化,正常 resolve(对齐 Android
  //   CancellationException → checkpointConversation(force) 语义);
  //   非 abort 错误维持传播(D-012)
  abortSignal?: AbortSignalLike;
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
  const ctx: TransformerContext = { assistant: deps.assistant };

  // 2. 上下文组装(system 前置 + 截断,D-018)→ input transforms(次序对齐
  //    GenerationHandler:434-446:先 [system]+preparedContext 再 transforms);
  //    均作用于发往 provider 的副本,不污染会话本体
  const assembled: UIMessage[] = assembleInternalMessages({
    messages: baseMessages,
    assistant: deps.assistant,
    agentSoul: deps.agentSoul ?? '',
    contextMessageSize: deps.contextMessageSize ?? 0,
  });
  const internalMessages: UIMessage[] = applyInputTransformers(assembled, deps.inputTransformers, ctx);

  // 3. provider 流式 → accumulator;节流 onUpdate 带 streamingTail visual
  const accumulator = new MessageStreamAccumulator(baseMessages);
  const flushIntervalMs: number = deps.flushIntervalMs ?? 50;
  const nowMs: () => number = deps.nowMs ?? ((): number => Date.now());
  let lastFlushAt: number = 0;
  const streamOpts: StreamOpts | undefined = deps.abortSignal !== undefined
    ? { signal: deps.abortSignal }
    : undefined;
  try {
    await deps.provider.streamText(internalMessages, (chunk: MessageChunk): void => {
      accumulator.append(chunk);
      if (deps.onUpdate !== undefined) {
        const now: number = nowMs();
        if (lastFlushAt === 0 || now - lastFlushAt >= flushIntervalMs) {
          lastFlushAt = now;
          const snapshot: UIMessage[] = accumulator.snapshot();
          deps.onUpdate(applyVisualTransformersStreamingTail(snapshot, deps.outputTransformers, ctx));
        }
      }
    }, streamOpts);
  } catch (e) {
    // abort(停止生成):部分快照照常终态化入列(Android checkpoint force 语义);
    // 其余错误传播(D-012)
    const aborted: boolean = deps.abortSignal !== undefined && deps.abortSignal.aborted;
    if (!aborted) {
      throw e;
    }
  }

  // 4. 终态:onGenerationFinish transforms → assistant 节点入列 → 持久化
  const finalMessages: UIMessage[] = applyOnGenerationFinish(
    accumulator.snapshot(), deps.outputTransformers, ctx,
  );
  if (finalMessages.length > baseMessages.length) {
    for (const msg of finalMessages.slice(baseMessages.length)) {
      conv = appendNode(conv, msg);
    }
    await deps.store.save(conv);
  }
  return conv;
};
