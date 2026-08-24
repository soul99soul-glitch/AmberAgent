// Agent loop 骨架规格测试(chat turn)
//
// Android 基准: app/core/ai/GenerationHandler.kt stream 路径(:473-545 核心骨架)
//   user 消息入 conversation → input transforms → provider.streamText
//   → MessageStreamAccumulator.append(节流 onUpdate + streamingTail visual)
//   → 终态 snapshot → onGenerationFinish transforms → assistant 节点入 conversation → persist
//
// 裁剪(source-only 骨架,记 D-012):
//   - retry/vision fallback/工具执行/审批/生成守卫 = P1 范围,不在骨架
//   - provider/persist 为 Port(回调式 chunk,对齐 deepread HttpClient.fetchStream 风格)
//   - 错误路径:provider 抛错直接传播;已入列的 user 消息保留(错误恢复语义 P1 再对齐,PD-006)

import { test } from 'node:test';
import assert from 'node:assert/strict';

import { runChatTurn, createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import type {
  ChatStreamProvider, ConversationStore,
} from '../main/ets/chat/chat_turn.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { makeGenerationRetrySetting } from '../main/ets/chat/generation_retry.ts';
import type { Assistant } from '../main/ets/chat/assistant.ts';
import {
  createTemplateTransformer, thinkTagTransformer,
} from '../main/ets/chat/transformers.ts';
import { makeConversation, currentMessages, toMessageNode } from '../main/ets/chat/conversation.ts';
import type { Conversation } from '../main/ets/chat/conversation.ts';
import type { MessageChunk, UIMessage, UIMessagePart } from '../main/ets/chat/message.ts';
import { toText, reasoningPartText, makeUserMessage, makeAssistantMessage } from '../main/ets/chat/message.ts';

const chunkOf = (text: string): MessageChunk => ({
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

interface FakeProvider extends ChatStreamProvider {
  seenMessages: UIMessage[][];
}

const fakeProvider = (chunks: MessageChunk[][]): FakeProvider => ({
  seenMessages: [],
  streamText(messages: UIMessage[], onChunk: (c: MessageChunk) => void): Promise<void> {
    this.seenMessages.push(messages);
    for (const group of chunks) group.forEach(onChunk);
    return Promise.resolve();
  },
});

const baseDeps = (assistant: Assistant, provider: ChatStreamProvider, store: ConversationStore) => ({
  assistant,
  inputTransformers: [],
  outputTransformers: [],
  provider,
  store,
});

test('chatTurn happy path: user 入列 → provider → accumulator → assistant 节点 → 两次 persist', async () => {
  const conv: Conversation = makeConversation('conv1', [], { title: 't' });
  const provider = fakeProvider([[chunkOf('你好'), chunkOf('世界')]]);
  const store = createMemoryConversationStore();
  const out = await runChatTurn(conv, '打个招呼', baseDeps(makeAssistant({}), provider, store));

  assert.equal(out.messageNodes.length, 2, 'user + assistant 两个节点');
  const msgs = currentMessages(out);
  assert.equal(msgs[0].role, 'user');
  assert.equal(msgs[1].role, 'assistant');
  assert.equal(toText(msgs[1]), '你好世界', 'chunk 经 accumulator 合并');
  assert.notEqual(msgs[1].finishedAt, null, '正常完成的 assistant 必须闭合 finishedAt');

  // provider 看到的消息包含 user 输入
  const seen = (provider as FakeProvider).seenMessages[0];
  assert.equal(seen[seen.length - 1].role, 'user');
  assert.equal(toText(seen[seen.length - 1]), '打个招呼');

  // persist: user 入列一次 + assistant 完成一次
  assert.equal(store.saved.length, 2);
  assert.equal(store.saved[0].messageNodes.length, 1);
  assert.equal(store.saved[1].messageNodes.length, 2);
});

test('chatTurn: input transformer 作用于发往 provider 的消息,不污染会话本体', async () => {
  const conv: Conversation = makeConversation('conv1', [], { title: 't' });
  const provider = fakeProvider([[chunkOf('ok')]]);
  const store = createMemoryConversationStore();
  const assistant = makeAssistant({ messageTemplate: '包装:{{message}}' });
  const deps = {
    ...baseDeps(assistant, provider, store),
    inputTransformers: [createTemplateTransformer()],
  };
  const out = await runChatTurn(conv, '原文', deps);

  const seen = (provider as FakeProvider).seenMessages[0];
  assert.equal(toText(seen[seen.length - 1]), '包装:原文', 'provider 收到模板套用后的文本');
  assert.equal(toText(currentMessages(out)[0]), '原文', '会话中保留原文');
});

test('chatTurn: onUpdate 流式回调带 streamingTail visual(think tag 转为 reasoning)', async () => {
  const conv: Conversation = makeConversation('conv1', [], { title: 't' });
  const provider = fakeProvider([[chunkOf('<think>想'), chunkOf('想完了</think>答')]]);
  const store = createMemoryConversationStore();
  const updates: UIMessage[][] = [];
  const deps = {
    ...baseDeps(makeAssistant({}), provider, store),
    outputTransformers: [thinkTagTransformer],
    flushIntervalMs: 0, // 每个 chunk 都刷新(测试)
    onUpdate: (msgs: UIMessage[]): void => { updates.push(msgs); },
  };
  const out = await runChatTurn(conv, 'q', deps);

  assert.ok(updates.length >= 2, '流式回调至少两次');
  // 终态:onGenerationFinish 把 think 块转成闭合 reasoning
  const tail = currentMessages(out)[1];
  assert.equal(reasoningPartText(tail), '想想完了');
  // toText 对齐 Android(Message.kt:164):非 text part 映射为 '' 后 \n join → 前导 \n
  assert.equal(toText(tail), '\n答');
  const textOnly = tail.parts.filter((p: { type: string }): boolean => p.type === 'text')
    .map((p): string => (p as unknown as { text: string }).text).join('');
  assert.equal(textOnly, '答');
});

test('chatTurn: provider 抛错 → 传播;user 消息已持久化,assistant 节点不入列', async () => {
  const conv: Conversation = makeConversation('conv1', [], { title: 't' });
  const failing: ChatStreamProvider = {
    streamText: (_m: UIMessage[], _c: (c: MessageChunk) => void): Promise<void> =>
      Promise.reject(new Error('boom')),
  };
  const store = createMemoryConversationStore();
  await assert.rejects(
    () => runChatTurn(conv, 'q', baseDeps(makeAssistant({}), failing, store)),
    /boom/,
  );
  assert.equal(store.saved.length, 1, '仅 user 入列时的快照');
  assert.equal(store.saved[0].messageNodes.length, 1);
  assert.equal(conv.messageNodes.length, 0, '入参 conversation 不被就地修改');
});

test('chatTurn: retry sleep 中 abort 丢弃失败尝试部分输出', async () => {
  const signal = { aborted: false };
  let releaseSleep: () => void = (): void => {};
  const provider: ChatStreamProvider = {
    streamText: (_messages: UIMessage[], onChunk: (c: MessageChunk) => void): Promise<void> => {
      onChunk(chunkOf('failed-partial'));
      return Promise.reject(new Error('HTTP 503'));
    },
  };
  const store = createMemoryConversationStore();
  const outPromise = runChatTurn(makeConversation('retry-abort', []), 'q', {
    ...baseDeps(makeAssistant({}), provider, store),
    abortSignal: signal,
    retrySetting: makeGenerationRetrySetting({
      enabled: true, maxRetries: 2, initialDelayMs: 1, maxDelayMs: 1, jitterRatio: 0,
    }),
    sleep: (): Promise<void> => new Promise((resolve): void => { releaseSleep = resolve; }),
  });
  await new Promise((resolve): void => { setTimeout(resolve, 0); });
  signal.aborted = true;
  releaseSleep();
  const out = await outPromise;
  assert.equal(out.messageNodes.length, 1);
  assert.equal(store.saved.length, 1);
});

test('chatTurn: provider 零 chunk → 不追加空 assistant 节点', async () => {
  const conv: Conversation = makeConversation('conv1', [], { title: 't' });
  const provider = fakeProvider([]);
  const store = createMemoryConversationStore();
  const out = await runChatTurn(conv, 'q', baseDeps(makeAssistant({}), provider, store));
  assert.equal(out.messageNodes.length, 1, '只有 user 节点');
});

test('chatTurn: 上下文组装 — system 前置 + 截断 + 模板在组装后套用(D-018 次序)', async () => {
  const history = [makeUserMessage('m1'), makeAssistantMessage('a1'), makeUserMessage('m2')];
  const conv: Conversation = makeConversation('conv1', history.map(toMessageNode), { title: 't' });
  const provider = fakeProvider([[chunkOf('ok')]]);
  const store = createMemoryConversationStore();
  const assistant = makeAssistant({ systemPrompt: 'SP', messageTemplate: '包:{{message}}' });
  const deps = {
    ...baseDeps(assistant, provider, store),
    inputTransformers: [createTemplateTransformer()],
    contextMessageSize: 2,
  };
  const out = await runChatTurn(conv, 'm3', deps);

  const seen = (provider as FakeProvider).seenMessages[0];
  assert.equal(seen.length, 3, 'system + 截断后 2 条');
  assert.equal(seen[0].role, 'system');
  // TemplateTransformer 对每条消息的 text part 都套模板(TemplateTransformer.kt:26-39,含 system)
  assert.equal(toText(seen[0]), '包:SP');
  assert.equal(toText(seen[1]), '包:m2', '截断保留末尾 2 条([m2,m3]) + 模板套用(assemble→transform 次序)');
  assert.equal(toText(seen[2]), '包:m3');
  // 会话本体:无 system、无截断(3 历史 + user + assistant = 5 节点)
  assert.equal(out.messageNodes.length, 5);
  assert.equal(currentMessages(out)[0].role, 'user');
});

// Phase 4 回归:空 url 图片输入不再被当作有效输入(isEmptyInputMessage 口径)
test('chatTurn: image part with empty url is a no-op', async () => {
  const conv: Conversation = makeConversation('conv-empty-img', [], { title: 't' });
  const provider = fakeProvider([[chunkOf('不应被调用')]]);
  const store = createMemoryConversationStore();
  const out = await runChatTurn(
    conv, [{ type: 'image', url: '', metadata: null }],
    baseDeps(makeAssistant({}), provider, store));
  assert.equal(out, conv, 'empty-url image input must be a no-op (returns original conversation)');
  assert.equal(store.saved.length, 0, 'nothing persisted');
  assert.equal(provider.seenMessages.length, 0, 'provider must not be called');
});
