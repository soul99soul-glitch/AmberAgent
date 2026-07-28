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

  it('user 节点 regenerate = P1 裁剪:显式抛错而非静默 no-op', async () => {
    const conv = threeNodeConv();
    const d: RegenerateDeps = deps(textProvider('x'));
    await assert.rejects(
      () => runRegenerateAt(conv, conv.messageNodes[0].id, d),
      /user/,
    );
  });

  it('未知 nodeId → 抛错(调用方传错是 bug,不静默)', async () => {
    const conv = threeNodeConv();
    const d: RegenerateDeps = deps(textProvider('x'));
    await assert.rejects(() => runRegenerateAt(conv, 'no-such-node', d), /node/);
  });
});
