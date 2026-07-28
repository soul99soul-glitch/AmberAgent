// stop generation 停止生成测试
// Android 基准:ChatService.stopGeneration + 流收集 onFailure 路径
//   (ChatService.kt:1437-1447:CancellationException → checkpointConversation(force)
//   → **部分生成内容被保存**,错误不上浮)
// 鸿蒙口径:abort(signal 触发,provider 抛错且 signal.aborted)→ 终态 transforms
//   照常应用于已累积快照 → 部分 assistant 节点入列 + 持久化 → **正常 resolve**
//   (非 abort 错误维持 D-012 语义:传播,assistant 节点不入列)
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  makeConversation, makeAssistant, makeUserMessage,
} from '../main/ets/index.ts';
import type { Conversation, MessageChunk, UIMessage } from '../main/ets/index.ts';
import { runChatTurn, createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import type { ChatTurnDeps } from '../main/ets/chat/chat_turn.ts';

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

const makeSignal = (): { aborted: boolean; controller: { abort: () => void } } => {
  const state = { aborted: false };
  return {
    get aborted() { return state.aborted; },
    controller: { abort: (): void => { state.aborted = true; } },
  };
};

const baseDeps = (streamText: ChatTurnDeps['provider']['streamText']): ChatTurnDeps => ({
  assistant: makeAssistant({ name: 'T' }),
  inputTransformers: [],
  outputTransformers: [],
  provider: { streamText },
  store: createMemoryConversationStore(),
});

describe('runChatTurn abort(停止生成)', () => {
  it('流中段 abort:部分 assistant 节点入列并持久化,正常 resolve', async () => {
    const signal = makeSignal();
    const deps: ChatTurnDeps = baseDeps(
      (_m: UIMessage[], onChunk: (c: MessageChunk) => void, opts?): Promise<void> => {
        onChunk(chunkOf('前半'));
        onChunk(chunkOf('后半'));
        // provider 收到 abort 后抛错(对齐 RCP destroy 语义)
        return Promise.reject(new Error('stream aborted'));
      });
    deps.abortSignal = signal;
    signal.controller.abort(); // 已 aborted(provider 抛错时 signal.aborted=true)
    const conv = makeConversation('c-abort', []);
    const out = await runChatTurn(conv, '你好', deps);
    const texts = out.messageNodes.map((n) => {
      const m = n.messages[0];
      return m.parts.filter((p) => p.type === 'text')
        .map((p) => (p as { text: string }).text).join('');
    });
    assert.deepEqual(texts, ['你好', '前半后半']);
    // user 快照 + 部分 assistant 各持久化一次
    assert.equal((deps.store as ReturnType<typeof createMemoryConversationStore>).saved.length, 2);
  });

  it('无任何 chunk 时 abort:无 assistant 节点,正常 resolve(user 快照仍在)', async () => {
    const signal = makeSignal();
    signal.controller.abort();
    const deps: ChatTurnDeps = baseDeps(
      (): Promise<void> => Promise.reject(new Error('stream aborted')));
    deps.abortSignal = signal;
    const out = await runChatTurn(makeConversation('c0', []), '你好', deps);
    assert.equal(out.messageNodes.length, 1); // 仅 user
  });

  it('非 abort 错误维持传播语义(D-012 不回归)', async () => {
    const signal = makeSignal(); // 未 abort
    const deps: ChatTurnDeps = baseDeps(
      (): Promise<void> => Promise.reject(new Error('HTTP 500')));
    deps.abortSignal = signal;
    await assert.rejects(
      () => runChatTurn(makeConversation('c1', []), '你好', deps), /HTTP 500/);
  });

  it('signal 经 ChatStreamProvider opts 透传给 API 层', async () => {
    let seenSignal: unknown = null;
    const signal = makeSignal();
    const deps: ChatTurnDeps = baseDeps(
      (_m: UIMessage[], onChunk: (c: MessageChunk) => void, opts?): Promise<void> => {
        seenSignal = opts?.signal;
        onChunk(chunkOf('x'));
        return Promise.resolve();
      });
    deps.abortSignal = signal;
    await runChatTurn(makeConversation('c2', []), '你好', deps);
    assert.equal(seenSignal, signal);
  });
});
