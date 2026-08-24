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
  toMessageNode, makeUIMessage,
  applyStopGeneration, cancelToolByUser, stopGenerationUpdatedMessage,
  CANCEL_TOOL_BY_USER_OUTPUT, CANCEL_TOOL_BY_USER_REASON,
} from '../main/ets/index.ts';
import type {
  Conversation, MessageChunk, UIMessage, UIMessagePartTool,
} from '../main/ets/index.ts';
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
  // D-050:重试回退即时化(计时不在本文件断言范围)
  sleep: (): Promise<void> => Promise.resolve(),
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

// ===== stopGenerationUpdatedMessage / cancelToolByUser / applyStopGeneration =====
// Android 基准: ChatService.stopGeneration(:2408-2455)+ cancelToolByUser(:1611-1620)

const CANCELLED_AT: string = '2026-08-14T12:00:00.000Z';

const pendingTool = (id: string = 't1'): UIMessagePartTool => ({
  type: 'tool', toolCallId: id, toolName: 'local_shell', input: '{"cmd":"ls"}',
  output: [], approvalState: { type: 'pending' }, metadata: null,
});

const executedTool = (id: string = 't2'): UIMessagePartTool => ({
  type: 'tool', toolCallId: id, toolName: 'local_shell', input: '{"cmd":"ls"}',
  output: [{ type: 'text', text: 'result-ok', metadata: null }],
  approvalState: { type: 'auto' }, metadata: null,
});

describe('cancelToolByUser(停止生成工具收口)', () => {
  it('取消工具:输出置取消 JSON + denied 状态(Android :1611-1620 逐字)', () => {
    const out = cancelToolByUser(pendingTool());
    assert.equal(out.output.length, 1);
    assert.equal(out.output[0].type, 'text');
    if (out.output[0].type === 'text') {
      assert.equal(out.output[0].text, CANCEL_TOOL_BY_USER_OUTPUT);
    }
    assert.deepEqual(out.approvalState, { type: 'denied', reason: CANCEL_TOOL_BY_USER_REASON });
  });

  it('取消工具:保留调用输入/工具名', () => {
    const out = cancelToolByUser(pendingTool('x1'));
    assert.equal(out.toolCallId, 'x1');
    assert.equal(out.toolName, 'local_shell');
    assert.equal(out.input, '{"cmd":"ls"}');
  });
});

describe('stopGenerationUpdatedMessage(末条消息收口三情形)', () => {
  it('abort 时 reasoning 打开 + 工具 pending → 工具取消、finishedAt 补齐、reasoning 关闭、已执行结果保留', () => {
    const msg = makeUIMessage('assistant', [
      { type: 'reasoning', reasoning: 'thinking…', createdAt: '2026-08-14T11:00:00Z',
        finishedAt: null, metadata: null },
      pendingTool(),
      executedTool(),
    ]);
    const updated = stopGenerationUpdatedMessage(msg, CANCELLED_AT);
    assert.notEqual(updated, null);
    const u = updated as UIMessage;
    // finishPendingTools 有变化时自身戳 finishedAt(Android 同语义,非 null)
    assert.notEqual(u.finishedAt, null);
    assert.equal(u.finishedAt !== null && u.finishedAt.length > 0, true);
    const reasoning = u.parts.find((p): boolean => p.type === 'reasoning');
    assert.equal(reasoning !== undefined && reasoning.type === 'reasoning' && reasoning.finishedAt !== null,
      true);
    const tool = u.parts.find((p): boolean =>
      p.type === 'tool' && (p as UIMessagePartTool).toolCallId === 't1') as UIMessagePartTool;
    assert.equal(tool.output.length, 1);
    assert.equal(tool.output[0].type, 'text');
    if (tool.output[0].type === 'text') assert.equal(tool.output[0].text, CANCEL_TOOL_BY_USER_OUTPUT);
    assert.deepEqual(tool.approvalState, { type: 'denied', reason: CANCEL_TOOL_BY_USER_REASON });
    // 已执行工具结果保留
    const executed = u.parts.find((p): boolean =>
      p.type === 'tool' && (p as UIMessagePartTool).toolCallId === 't2') as UIMessagePartTool;
    assert.equal(executed.output.length, 1);
    if (executed.output[0].type === 'text') assert.equal(executed.output[0].text, 'result-ok');
  });

  it('abort 时部分文本(无工具)→ 补 finishedAt(cancelledAt)+ 关闭 reasoning,文本保留', () => {
    const msg = makeUIMessage('assistant', [
      { type: 'reasoning', reasoning: 'r', createdAt: '2026-08-14T11:00:00Z', finishedAt: null, metadata: null },
      { type: 'text', text: 'partial answer', metadata: null },
    ]);
    const updated = stopGenerationUpdatedMessage(msg, CANCELLED_AT);
    assert.notEqual(updated, null);
    const u = updated as UIMessage;
    // 无工具变化 → assistant finishedAt 走 cancelledAt 补齐(Android :2434-2439)
    assert.equal(u.finishedAt, CANCELLED_AT);
    const reasoning = u.parts.find((p): boolean => p.type === 'reasoning');
    assert.equal(reasoning !== undefined && reasoning.type === 'reasoning' && reasoning.finishedAt !== null,
      true);
    const text = u.parts.find((p): boolean => p.type === 'text');
    assert.equal(text !== undefined && text.type === 'text' && text.text, 'partial answer');
  });

  it('无变化(已 finished + 无 reasoning + 无 pending 工具)→ null(Android 早退)', () => {
    const msg = makeUIMessage('assistant', [
      { type: 'text', text: 'done', metadata: null },
    ], { finishedAt: CANCELLED_AT });
    assert.equal(stopGenerationUpdatedMessage(msg, CANCELLED_AT), null);
  });

  it('user 消息 → 不补 finishedAt,仅关闭 reasoning(Android :2434-2439)', () => {
    const msg = makeUIMessage('user', [
      { type: 'reasoning', reasoning: 'r', createdAt: '2026-08-14T11:00:00Z', finishedAt: null, metadata: null },
    ]);
    const updated = stopGenerationUpdatedMessage(msg, CANCELLED_AT);
    assert.notEqual(updated, null);
    const u = updated as UIMessage;
    assert.equal(u.finishedAt, null);
    const reasoning = u.parts.find((p): boolean => p.type === 'reasoning');
    assert.equal(reasoning !== undefined && reasoning.type === 'reasoning' && reasoning.finishedAt !== null,
      true);
  });
});

describe('applyStopGeneration(Conversation 级收口,不追加节点)', () => {
  it('更新末条消息(按 id),节点数不变,其他节点不动', () => {
    const user = makeUserMessage('hi');
    const assistant = makeUIMessage('assistant', [pendingTool('p1')]);
    const conv = makeConversation('c1', [toMessageNode(user), toMessageNode(assistant)]);
    const updated = applyStopGeneration(conv, CANCELLED_AT);
    assert.notEqual(updated, null);
    const u = updated as Conversation;
    assert.equal(u.messageNodes.length, 2); // 不追加节点
    const last = u.messageNodes[1].messages[u.messageNodes[1].selectIndex];
    // 有 pending 工具 → finishPendingTools 自身戳 finishedAt(非 null)
    assert.notEqual(last.finishedAt, null);
    const tool = last.parts.find((p): boolean => p.type === 'tool') as UIMessagePartTool;
    assert.deepEqual(tool.approvalState, { type: 'denied', reason: CANCEL_TOOL_BY_USER_REASON });
    assert.equal(tool.output.length, 1);
    // 首条 user 消息未被改动(同引用)
    assert.equal(u.messageNodes[0].messages[u.messageNodes[0].selectIndex], user);
  });

  it('无变化 → null(不落库)', () => {
    const user = makeUserMessage('hi');
    const assistant = makeUIMessage('assistant', [
      { type: 'text', text: 'done', metadata: null },
    ], { finishedAt: CANCELLED_AT });
    const conv = makeConversation('c1', [toMessageNode(user), toMessageNode(assistant)]);
    assert.equal(applyStopGeneration(conv, CANCELLED_AT), null);
  });

  it('指定中间 assistant 节点时收口目标分支,不误改写后续节点', () => {
    const user = makeUserMessage('hi');
    const target = makeUIMessage('assistant', [pendingTool('middle-pending')]);
    const trailing = makeUserMessage('later');
    const conv = makeConversation('c1', [
      toMessageNode(user), toMessageNode(target), toMessageNode(trailing),
    ]);
    const updated = applyStopGeneration(
      conv, CANCELLED_AT, conv.messageNodes[1].id);
    assert.notEqual(updated, null);
    const out = updated as Conversation;
    const targetMessage = out.messageNodes[1].messages[out.messageNodes[1].selectIndex];
    const tool = targetMessage.parts.find((p): boolean => p.type === 'tool') as UIMessagePartTool;
    assert.deepEqual(tool.approvalState, { type: 'denied', reason: CANCEL_TOOL_BY_USER_REASON });
    assert.equal(out.messageNodes[2], conv.messageNodes[2]);
  });

  it('空会话 → null', () => {
    assert.equal(applyStopGeneration(makeConversation('c1', []), CANCELLED_AT), null);
  });
});
