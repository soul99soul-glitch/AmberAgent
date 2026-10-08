// regenerate 分支再生测试
// Android 基准(ChatService.regenerateAtMessage:1078-1118):
//   - assistant 消息 regenerate:以目标节点之前的 currentMessages 为上下文重跑生成,
//     新 assistant 消息追加进同一节点(分支"追加不覆盖",conversation.ts 头注),
//     selectIndex 指向新分支
//   - 分支切换 selectMessageNode(ChatVM.kt:420):设置节点 selectIndex 并持久化
// 裁剪(本测试锁定):user 消息节点 regenerate(截断重跑)= P1;domain 层抛错而非静默
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  makeConversation, toMessageNode, makeUserMessage, makeAssistantMessage,
  makeAssistant, currentMessages,
} from '../main/ets/index.ts';
import type { Conversation, MessageChunk, UIMessage } from '../main/ets/index.ts';
import { selectMessageBranch, runRegenerateAt } from '../main/ets/chat/regenerate.ts';
import type { RegenerateDeps } from '../main/ets/chat/regenerate.ts';
import { createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import { makeGenerationRetrySetting } from '../main/ets/chat/generation_retry.ts';

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

const textProvider = (text: string, capture?: { messages?: UIMessage[] }) => ({
  streamText(messages: UIMessage[], onChunk: (chunk: MessageChunk) => void): Promise<void> {
    if (capture !== undefined) capture.messages = messages;
    onChunk(chunkOf(text));
    return Promise.resolve();
  },
});

const failingProvider = () => ({
  streamText(_messages: UIMessage[], _onChunk: (chunk: MessageChunk) => void): Promise<void> {
    return Promise.reject(new Error('network down'));
  },
});

const threeNodeConv = (): Conversation => ({
  ...makeConversation('conv-1', []),
  messageNodes: [
    toMessageNode(makeUserMessage('u1')),
    toMessageNode(makeAssistantMessage('a1')),
    toMessageNode(makeAssistantMessage('a1-备选')),
  ].map((n, i) => i === 2
    // 第三节点做成双分支节点(模拟已有备选)
    ? { ...n, messages: [makeAssistantMessage('a2'), makeAssistantMessage('a2-alt')], selectIndex: 0 }
    : n),
});

const deps = (provider: RegenerateDeps['provider']): RegenerateDeps => ({
  assistant: makeAssistant({ name: 'T' }),
  inputTransformers: [],
  outputTransformers: [],
  provider,
  store: createMemoryConversationStore(),
  // D-050:重试回退即时化(计时不在本文件断言范围)
  sleep: (): Promise<void> => Promise.resolve(),
});

describe('selectMessageBranch', () => {
  it('设置节点 selectIndex', () => {
    const conv = threeNodeConv();
    const out = selectMessageBranch(conv, conv.messageNodes[2].id, 1);
    assert.equal(out.messageNodes[2].selectIndex, 1);
    assert.equal(currentMessages(out)[2].parts[0].type === 'text'
      ? (currentMessages(out)[2].parts[0] as { text: string }).text : '', 'a2-alt');
  });

  it('越界索引收敛到合法范围(非静默:夹取而非环绕)', () => {
    const conv = threeNodeConv();
    assert.equal(selectMessageBranch(conv, conv.messageNodes[2].id, 99).messageNodes[2].selectIndex, 1);
    assert.equal(selectMessageBranch(conv, conv.messageNodes[2].id, -3).messageNodes[2].selectIndex, 0);
  });

  it('未知 nodeId → 原样返回(不抛错,UI 可安全重试)', () => {
    const conv = threeNodeConv();
    const out = selectMessageBranch(conv, 'no-such-node', 1);
    assert.deepEqual(out.messageNodes, conv.messageNodes);
  });
});

describe('runRegenerateAt', () => {
  it('throttles rapid regeneration flushes, shares their snapshot and keeps the final raw and stored tail', async () => {
    const conv = threeNodeConv();
    let now = 100;
    const raw: UIMessage[][] = [];
    const visible: UIMessage[][] = [];
    const provider = { streamText: async (_messages: UIMessage[], onChunk: (chunk: MessageChunk) => void) => {
      for (let i = 0; i < 20; i++) { onChunk(chunkOf('字')); now++; }
    } };
    const out = await runRegenerateAt(conv, conv.messageNodes[2].id, {
      ...deps(provider), nowMs: () => now,
      onRawFlushSnapshot: (messages) => { raw.push(messages); },
      onUpdate: (messages) => { visible.push(messages); },
    });
    assert.equal(visible.length, 2, 'sub-48ms chunks coalesce, then completion publishes the full tail');
    assert.equal(raw.length, 2, 'one gated flush plus an unconditional final raw snapshot');
    assert.equal(raw[0], visible[0], 'raw and UI share a single immutable flush snapshot');
    const last = (messages: UIMessage[]) => messages.at(-1)!.parts[0];
    assert.equal(last(raw[1]).type === 'text' ? (last(raw[1]) as { text: string }).text : '', '字'.repeat(20));
    assert.equal(last(currentMessages(out)).type === 'text'
      ? (last(currentMessages(out)) as { text: string }).text : '', '字'.repeat(20));
    assert.notEqual(currentMessages(out).at(-1)!.finishedAt, null);
  });
  it('assistant 节点:新分支追加进同节点,selectIndex 指向新分支,节点总数不变', async () => {
    const conv = threeNodeConv();
    const store = createMemoryConversationStore();
    const d: RegenerateDeps = { ...deps(textProvider('a2-new')), store };
    const out = await runRegenerateAt(conv, conv.messageNodes[2].id, d);
    assert.equal(out.messageNodes.length, 3);
    const node = out.messageNodes[2];
    assert.equal(node.messages.length, 3);
    assert.equal(node.selectIndex, 2);
    const last = node.messages[2];
    assert.notEqual(last.finishedAt, null, '正常完成的再生 assistant 必须闭合 finishedAt');
    assert.equal(last.parts[0].type === 'text' ? (last.parts[0] as { text: string }).text : '', 'a2-new');
    // 已有分支保留(追加不覆盖)
    assert.equal(node.messages[0].parts[0].type === 'text'
      ? (node.messages[0].parts[0] as { text: string }).text : '', 'a2');
    assert.ok(store.saved.length >= 1);
  });

  it('provider 收到的上下文只含目标节点之前的消息(截断语义)', async () => {
    const conv = threeNodeConv();
    const capture: { messages?: UIMessage[] } = {};
    const d: RegenerateDeps = deps(textProvider('x', capture));
    await runRegenerateAt(conv, conv.messageNodes[2].id, d);
    const texts = (capture.messages ?? []).map((m: UIMessage): string =>
      m.parts.filter((p) => p.type === 'text').map((p) => (p as { text: string }).text).join(''));
    // u1 + a1(目标之前的两节点);不含目标节点任何分支
    assert.deepEqual(texts, ['u1', 'a1']);
  });

  it('中间节点 regenerate:后续节点保留在会话中(仅上下文截断)', async () => {
    const conv = threeNodeConv();
    const d: RegenerateDeps = deps(textProvider('a1-new'));
    const out = await runRegenerateAt(conv, conv.messageNodes[1].id, d);
    assert.equal(out.messageNodes.length, 3);
    assert.equal(out.messageNodes[1].messages.length, 2);
    assert.equal(out.messageNodes[1].selectIndex, 1);
    // 后续节点原样保留
    assert.equal(out.messageNodes[2].messages.length, 2);
  });

  it('provider 抛错 → 传播且会话不变(对齐 runChatTurn 错误语义)', async () => {
    const conv = threeNodeConv();
    const store = createMemoryConversationStore();
    const d: RegenerateDeps = { ...deps(failingProvider()), store };
    await assert.rejects(() => runRegenerateAt(conv, conv.messageNodes[2].id, d), /network down/);
    assert.equal(store.saved.length, 0);
  });

  it('user 节点 regenerate:截断后续 + 先持久化截断 + 新 assistant 节点入列', async () => {
    const conv = threeNodeConv();
    const store = createMemoryConversationStore();
    const capture: { messages?: UIMessage[] } = {};
    const d: RegenerateDeps = { ...deps(textProvider('a1-new', capture)), store };
    const out = await runRegenerateAt(conv, conv.messageNodes[0].id, d);
    // 截断:只留 user 节点;新生成 assistant 追加为新节点
    assert.equal(out.messageNodes.length, 2);
    assert.equal(out.messageNodes[1].messages.length, 1);
    const gen = out.messageNodes[1].messages[0];
    assert.equal(gen.parts[0].type === 'text' ? (gen.parts[0] as { text: string }).text : '', 'a1-new');
    // 持久化次序:saved[0]=截断(1 节点),saved[1]=终态(2 节点)
    assert.equal(store.saved.length, 2);
    assert.equal(store.saved[0].messageNodes.length, 1);
    assert.equal(store.saved[1].messageNodes.length, 2);
    // provider 上下文含该 user 消息(截断后基底)
    const texts = (capture.messages ?? []).map((m: UIMessage): string =>
      m.parts.filter((p) => p.type === 'text').map((p) => (p as { text: string }).text).join(''));
    assert.deepEqual(texts, ['u1']);
  });

  it('user 节点 regenerate + provider 抛错:截断已持久化(Android 同序),错误传播', async () => {
    const conv = threeNodeConv();
    const store = createMemoryConversationStore();
    const d: RegenerateDeps = { ...deps(failingProvider()), store };
    await assert.rejects(() => runRegenerateAt(conv, conv.messageNodes[0].id, d), /network down/);
    // 截断在生成前持久化(ChatService.kt:1091-1099 同序)
    assert.equal(store.saved.length, 1);
    assert.equal(store.saved[0].messageNodes.length, 1);
  });

  it('user 节点为末节点:无截断,直接续跑', async () => {
    const base = threeNodeConv();
    const conv: Conversation = {
      ...base,
      messageNodes: [base.messageNodes[0]],
    };
    const store = createMemoryConversationStore();
    const d: RegenerateDeps = { ...deps(textProvider('a1')), store };
    const out = await runRegenerateAt(conv, conv.messageNodes[0].id, d);
    assert.equal(out.messageNodes.length, 2);
    assert.equal(store.saved.length, 2);
  });

  it('assistant 路径 abort:部分分支照常追加入节点,正常 resolve(D-035 同语义)', async () => {
    const conv = threeNodeConv();
    const store = createMemoryConversationStore();
    const signal = { aborted: true };
    let now = 100;
    const raw: UIMessage[][] = [];
    const d: RegenerateDeps = {
      ...deps({
        streamText: (_m: UIMessage[], onChunk: (c: MessageChunk) => void): Promise<void> => {
          onChunk(chunkOf('部'));
          now++;
          onChunk(chunkOf('分'));
          return Promise.reject(new Error('stream aborted'));
        },
      }),
      store,
      abortSignal: signal,
      nowMs: () => now,
      onRawFlushSnapshot: (messages) => { raw.push(messages); },
    };
    const out = await runRegenerateAt(conv, conv.messageNodes[2].id, d);
    const node = out.messageNodes[2];
    assert.equal(node.messages.length, 3);
    assert.equal(node.selectIndex, 2);
    const last = node.messages[2];
    assert.equal(last.parts[0].type === 'text' ? (last.parts[0] as { text: string }).text : '', '部分');
    assert.equal(store.saved.length, 1);
    const captured = raw.at(-1)!.at(-1)!.parts[0];
    assert.equal(captured.type === 'text' ? captured.text : '', '部分', 'abort raw tail includes the last gated-out chunk');
  });

  it('非 abort 错误维持传播(会话不变)', async () => {
    const conv = threeNodeConv();
    const store = createMemoryConversationStore();
    const d: RegenerateDeps = {
      ...deps({
        streamText: (): Promise<void> => Promise.reject(new Error('HTTP 500')),
      }),
      store,
      abortSignal: { aborted: false },
    };
    await assert.rejects(() => runRegenerateAt(conv, conv.messageNodes[2].id, d), /HTTP 500/);
    assert.equal(store.saved.length, 0);
  });

  it('retry sleep 中 abort 丢弃失败尝试部分输出', async () => {
    const signal = { aborted: false };
    let releaseSleep: () => void = (): void => {};
    const store = createMemoryConversationStore();
    const conv = threeNodeConv();
    const d: RegenerateDeps = {
      ...deps({
        streamText: (_messages: UIMessage[], onChunk: (c: MessageChunk) => void): Promise<void> => {
          onChunk(chunkOf('failed-partial'));
          return Promise.reject(new Error('HTTP 503'));
        },
      }),
      store,
      abortSignal: signal,
      retrySetting: makeGenerationRetrySetting({
        enabled: true, maxRetries: 2, initialDelayMs: 1, maxDelayMs: 1, jitterRatio: 0,
      }),
      sleep: (): Promise<void> => new Promise((resolve): void => { releaseSleep = resolve; }),
    };
    const promise = runRegenerateAt(conv, conv.messageNodes[2].id, d);
    await new Promise((resolve): void => { setTimeout(resolve, 0); });
    signal.aborted = true;
    releaseSleep();
    const out = await promise;
    assert.equal(out.messageNodes.length, 3);
    assert.equal(out.messageNodes[2].messages.length, 2);
    assert.equal(store.saved.length, 0);
  });

  it('未知 nodeId → 抛错(调用方传错是 bug,不静默)', async () => {
    const conv = threeNodeConv();
    const d: RegenerateDeps = deps(textProvider('x'));
    await assert.rejects(() => runRegenerateAt(conv, 'no-such-node', d), /node/);
  });
});

// Phase 4 回归:finish-only chunk 不点亮 sawChunk(空占位不追加成新分支)
it('regenerate: finish-only chunk (empty choices) does not append empty branch', async () => {
  // 单 assistant 节点;provider 流只回调一个空 choices chunk(某些网关的尾帧)
  const conv: Conversation = {
    ...makeConversation('conv-f', []),
    messageNodes: [
      toMessageNode(makeUserMessage('hi')),
      toMessageNode(makeAssistantMessage('old')),
    ],
  };
  const finishOnlyProvider = {
    streamText(_messages: UIMessage[], onChunk: (chunk: MessageChunk) => void): Promise<void> {
      onChunk({ id: 'x', model: 'm', choices: [], usage: null });
      return Promise.resolve();
    },
  };
  const out = await runRegenerateAt(conv, conv.messageNodes[1].id, deps(finishOnlyProvider));
  // 无产出 → 目标 assistant 节点分支不变(仍是 1 条备选)
  assert.equal(out.messageNodes[1].messages.length, 1, 'no empty alternative branch appended');
});

// Phase 4 回归:delta/message 双 null 的 choice 同样不算产出
it('regenerate: choice with null delta and null message does not append empty branch', async () => {
  const conv: Conversation = {
    ...makeConversation('conv-g', []),
    messageNodes: [
      toMessageNode(makeUserMessage('hi')),
      toMessageNode(makeAssistantMessage('old')),
    ],
  };
  const nullDeltaProvider = {
    streamText(_messages: UIMessage[], onChunk: (chunk: MessageChunk) => void): Promise<void> {
      onChunk({
        id: 'x', model: 'm',
        choices: [{ index: 0, delta: null, message: null, finishReason: 'stop' }],
        usage: null,
      });
      return Promise.resolve();
    },
  };
  const out = await runRegenerateAt(conv, conv.messageNodes[1].id, deps(nullDeltaProvider));
  assert.equal(out.messageNodes[1].messages.length, 1, 'no empty alternative branch appended');
});
