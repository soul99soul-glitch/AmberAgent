// 非流式输出规格测试(D-040)
//
// Android 基准: GenerationHandler.kt:255 stream = assistant.streamOutput
//   false → ChatCompletionsAPI.generateText 单次补全(非 SSE),chunk 以
//   message(非 delta)形式进入同一 accumulator(message→replaceActive,
//   stream_accumulator.ts:264-293)
//
// 语义锁定:
//   - assistant.streamOutput=false 且 provider.generateText 存在 → 走非流式,
//     streamText 不被调用;单次 chunk 照常终态 transforms + 入列 + persist
//   - provider.generateText 缺失 → 回退流式(Port 可选;Android 双实现恒在)
//   - abort:generateText reject 且 signal.aborted → 部分语义(chat_turn:
//     无 chunk → 仅 user 快照正常 resolve;regenerate:sawChunk=false → 原样返回)
//   - signal 透传 generateText opts

import { test } from 'node:test';
import assert from 'node:assert/strict';

import { runChatTurn, createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import type { ChatStreamProvider, StreamOpts } from '../main/ets/chat/chat_turn.ts';
import { runRegenerateAt } from '../main/ets/chat/regenerate.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import type { Assistant } from '../main/ets/chat/assistant.ts';
import { makeConversation, currentMessages, toMessageNode } from '../main/ets/chat/conversation.ts';
import type { Conversation } from '../main/ets/chat/conversation.ts';
import type { MessageChunk, UIMessage } from '../main/ets/chat/message.ts';
import { toText, makeAssistantMessage, makeUserMessage } from '../main/ets/chat/message.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';

const deltaChunkOf = (text: string): MessageChunk => ({
  id: 'c', model: 'm',
  choices: [{
    index: 0,
    delta: {
      id: 'd', role: 'assistant',
      parts: [{ type: 'text', text, metadata: null }],
      annotations: [], createdAt: '2026-07-28T00:00:00Z', finishedAt: null,
      modelId: null, usage: null, translation: null,
    },
    message: null, finishReason: 'unknown',
  }],
  usage: null,
});

// 非流式响应 chunk:message(非 delta),finishReason=stop
const messageChunkOf = (text: string): MessageChunk => ({
  id: 'c', model: 'm',
  choices: [{
    index: 0,
    delta: null,
    message: {
      id: 'm1', role: 'assistant',
      parts: [{ type: 'text', text, metadata: null }],
      annotations: [], createdAt: '2026-07-28T00:00:00Z', finishedAt: '2026-07-28T00:00:01Z',
      modelId: null, usage: null, translation: null,
    },
    finishReason: 'stop',
  }],
  usage: null,
});

class FakeAbortSignal implements AbortSignalLike {
  aborted: boolean = false;
  private listeners: (() => void)[] = [];
  addEventListener(_type: string, cb: () => void): void {
    this.listeners.push(cb);
  }
  fire(): void {
    this.aborted = true;
    for (const cb of this.listeners) cb();
  }
}

interface BothProvider extends ChatStreamProvider {
  streamCalls: number;
  generateCalls: number;
  seenSignal: AbortSignalLike | null;
}

const bothProvider = (chunk: MessageChunk): BothProvider => ({
  streamCalls: 0,
  generateCalls: 0,
  seenSignal: null,
  streamText(_m: UIMessage[], onChunk: (c: MessageChunk) => void): Promise<void> {
    this.streamCalls++;
    onChunk(chunk);
    return Promise.resolve();
  },
  generateText(_m: UIMessage[], opts?: StreamOpts): Promise<MessageChunk> {
    this.generateCalls++;
    this.seenSignal = opts?.signal ?? null;
    return Promise.resolve(chunk);
  },
});

const baseDeps = (assistant: Assistant, provider: ChatStreamProvider, store = createMemoryConversationStore()) => ({
  assistant,
  inputTransformers: [],
  outputTransformers: [],
  provider,
  store,
});

test('runChatTurn 非流式:streamOutput=false → generateText 单次补全,streamText 不调用', async () => {
  const conv: Conversation = makeConversation('conv1', [], { title: 't' });
  const provider = bothProvider(messageChunkOf('完整回答'));
  const store = createMemoryConversationStore();
  const out = await runChatTurn(
    conv, '你好', baseDeps(makeAssistant({ streamOutput: false }), provider, store),
  );

  assert.equal(provider.generateCalls, 1, '走 generateText');
  assert.equal(provider.streamCalls, 0, 'streamText 不被调用');
  const msgs = currentMessages(out);
  assert.equal(msgs.length, 2);
  assert.equal(toText(msgs[1]), '完整回答', 'message chunk 经 accumulator(replaceActive)合并');
  assert.equal(store.saved.length, 2, 'user 入列 + assistant 完成两次 persist');
});

test('runChatTurn:streamOutput=false 但 provider 无 generateText → 回退流式(Port 可选)', async () => {
  const conv: Conversation = makeConversation('conv1', [], { title: 't' });
  const streamOnly: ChatStreamProvider & { calls: number } = {
    calls: 0,
    streamText(_m: UIMessage[], onChunk: (c: MessageChunk) => void): Promise<void> {
      this.calls++;
      onChunk(deltaChunkOf('流式回答'));
      return Promise.resolve();
    },
  };
  const out = await runChatTurn(
    conv, '你好', baseDeps(makeAssistant({ streamOutput: false }), streamOnly),
  );
  assert.equal(streamOnly.calls, 1, '回退 streamText');
  assert.equal(toText(currentMessages(out)[1]), '流式回答');
});

test('runChatTurn 非流式 abort:generateText reject + signal.aborted → 仅 user 快照正常 resolve', async () => {
  const conv: Conversation = makeConversation('conv1', [], { title: 't' });
  const signal = new FakeAbortSignal();
  const provider: ChatStreamProvider = {
    streamText(): Promise<void> {
      return Promise.reject(new Error('should not be called'));
    },
    generateText(): Promise<MessageChunk> {
      signal.fire();
      return Promise.reject(new Error('aborted'));
    },
  };
  const store = createMemoryConversationStore();
  const deps = {
    ...baseDeps(makeAssistant({ streamOutput: false }), provider, store),
    abortSignal: signal as AbortSignalLike,
  };
  const out = await runChatTurn(conv, '你好', deps);
  assert.equal(out.messageNodes.length, 1, '仅 user 节点');
  assert.equal(store.saved.length, 1, '仅 user 快照持久化');
});

test('runChatTurn 非流式:signal 透传 generateText opts', async () => {
  const conv: Conversation = makeConversation('conv1', [], { title: 't' });
  const provider = bothProvider(messageChunkOf('ok'));
  const signal = new FakeAbortSignal();
  const deps = {
    ...baseDeps(makeAssistant({ streamOutput: false }), provider),
    abortSignal: signal as AbortSignalLike,
  };
  await runChatTurn(conv, '你好', deps);
  assert.equal(provider.seenSignal, signal as AbortSignalLike, 'signal 透传到 generateText');
});

test('runRegenerateAt 非流式:assistant 分支 → generateText chunk 追加为新分支', async () => {
  const assistantMsg: UIMessage = makeAssistantMessage('旧回答');
  const conv: Conversation = makeConversation('conv1', [
    toMessageNode(makeUserMessage('问题')),
    toMessageNode(assistantMsg),
  ]);
  const provider = bothProvider(messageChunkOf('新回答'));
  const store = createMemoryConversationStore();
  const out = await runRegenerateAt(
    conv, conv.messageNodes[1].id,
    baseDeps(makeAssistant({ streamOutput: false }), provider, store),
  );

  assert.equal(provider.generateCalls, 1);
  assert.equal(provider.streamCalls, 0);
  const node = out.messageNodes[1];
  assert.equal(node.messages.length, 2, '旧分支保留 + 新分支追加');
  assert.equal(node.selectIndex, 1, '指向新分支');
  assert.equal(toText(node.messages[1]), '新回答');
});

test('runRegenerateAt 非流式 abort:generateText reject + aborted → 原样返回(sawChunk=false)', async () => {
  const assistantMsg: UIMessage = makeAssistantMessage('旧回答');
  const conv: Conversation = makeConversation('conv1', [
    toMessageNode(makeUserMessage('问题')),
    toMessageNode(assistantMsg),
  ]);
  const signal = new FakeAbortSignal();
  const provider: ChatStreamProvider = {
    streamText(): Promise<void> {
      return Promise.reject(new Error('should not be called'));
    },
    generateText(): Promise<MessageChunk> {
      signal.fire();
      return Promise.reject(new Error('aborted'));
    },
  };
  const store = createMemoryConversationStore();
  const deps = {
    ...baseDeps(makeAssistant({ streamOutput: false }), provider, store),
    abortSignal: signal as AbortSignalLike,
  };
  const out = await runRegenerateAt(conv, conv.messageNodes[1].id, deps);
  assert.equal(out.messageNodes[1].messages.length, 1, '无新分支');
  assert.equal(toText(out.messageNodes[1].messages[0]), '旧回答');
});
