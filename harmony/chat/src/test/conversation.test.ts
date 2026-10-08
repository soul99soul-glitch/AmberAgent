// MessageNode / Conversation 分支与上下文规格测试
// 基准: core/model/src/main/kotlin/app/amber/core/model/Conversation.kt
//        + ai/ui/Message.kt 的 limitContext/finishReasoning/finishPendingTools

import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { makeUserMessage, makeAssistantMessage, makeUIMessage } from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePartTool, UIMessagePartReasoning } from '../main/ets/chat/message.ts';
import {
  makeMessageNode, toMessageNode, nodeCurrentMessage, makeConversation, currentMessages, updateCurrentMessages,
  limitContext, finishReasoning, finishPendingTools, collectFileUrls,
} from '../main/ets/chat/conversation.ts';
import type { MessageNode } from '../main/ets/chat/conversation.ts';

const tool = (id: string, executed: boolean): UIMessagePartTool => ({
  type: 'tool', toolCallId: id, toolName: 'n', input: '{}',
  output: executed ? [{ type: 'text', text: 'result', metadata: null }] : [],
  approvalState: { type: 'auto' }, metadata: null,
});

// ===== MessageNode =====

test('nodeCurrentMessage: 空 messages 抛异常', () => {
  const n: MessageNode = { id: 'n1', messages: [], selectIndex: 0 };
  assert.throws(() => nodeCurrentMessage(n), /no valid current message/);
});

test('nodeCurrentMessage: selectIndex 越界抛异常', () => {
  const n = makeMessageNode([makeUserMessage('a')], 5);
  assert.throws(() => nodeCurrentMessage(n));
});

// ===== Conversation.currentMessages =====

test('currentMessages: 每个 node 取 selectIndex 处消息', () => {
  const u = makeUserMessage('u');
  const a1 = makeAssistantMessage('a1');
  const a2 = makeAssistantMessage('a2');
  const c = makeConversation('c1', [
    toMessageNode(u),
    makeMessageNode([a1, a2], 1), // 选中第二个分支
  ]);
  const cur = currentMessages(c);
  assert.equal(cur.length, 2);
  assert.equal(cur[0].id, u.id);
  assert.equal(cur[1].id, a2.id);
});

// ===== updateCurrentMessages =====

test('updateCurrentMessages: 新 index 追加新 node', () => {
  const u = makeUserMessage('u');
  const c = makeConversation('c1', [toMessageNode(u)]);
  const a = makeAssistantMessage('a');
  const c2 = updateCurrentMessages(c, [u, a]);
  assert.equal(c2.messageNodes.length, 2);
  assert.equal(nodeCurrentMessage(c2.messageNodes[1]).id, a.id);
});

test('updateCurrentMessages: 按 id 替换已有消息,selectIndex 不变', () => {
  const u = makeUserMessage('u');
  const c = makeConversation('c1', [toMessageNode(u)]);
  const u2 = { ...u, parts: [{ type: 'text' as const, text: 'edited', metadata: null }] };
  const c2 = updateCurrentMessages(c, [u2]);
  assert.equal(c2.messageNodes.length, 1);
  assert.equal(c2.messageNodes[0].messages.length, 1, '同 id 替换不产生新分支');
  assert.equal(c2.messageNodes[0].selectIndex, 0);
});

test('updateCurrentMessages: 未知 id → 追加为新分支且 selectIndex 指向它', () => {
  const a1 = makeAssistantMessage('a1');
  const c = makeConversation('c1', [toMessageNode(a1)]);
  const regenerated = makeAssistantMessage('a1-regen'); // 不同 id = 新分支
  const c2 = updateCurrentMessages(c, [regenerated]);
  assert.equal(c2.messageNodes[0].messages.length, 2, 'regenerate 不覆盖旧分支');
  assert.equal(c2.messageNodes[0].selectIndex, 1, '选中新分支');
});

test('updateCurrentMessages: 同引用同 selectIndex → 节点引用保持不变(短路)', () => {
  const u = makeUserMessage('u');
  const c = makeConversation('c1', [toMessageNode(u)]);
  const c2 = updateCurrentMessages(c, [u]);
  assert.equal(c2.messageNodes[0], c.messageNodes[0], '未变化的 node 应引用相等');
  assert.equal(c2, c, '全部未变化时 Conversation 引用也保持');
});

// ===== limitContext =====

test('limitContext: size<=0 或列表不足 → 原样返回', () => {
  const msgs = [makeUserMessage('a'), makeAssistantMessage('b')];
  assert.deepEqual(limitContext(msgs, 0), msgs);
  assert.deepEqual(limitContext(msgs, 5), msgs);
});

test('limitContext: 基本截断取尾部', () => {
  const msgs = [makeUserMessage('1'), makeUserMessage('2'), makeUserMessage('3')];
  const r = limitContext(msgs, 2);
  assert.equal(r.length, 2);
  assert.equal(r[0], msgs[1]);
});

test('limitContext: 起点含已执行 tool → 级联前扩到 tool call 再到 user 消息', () => {
  const user = makeUserMessage('q');
  const call: UIMessage = { ...makeAssistantMessage(''), parts: [tool('t1', false)] };
  const resultMsg: UIMessage = { ...makeAssistantMessage(''), parts: [tool('t1', true)] };
  const tail = makeAssistantMessage('final');
  // 窗口从 resultMsg 开始:已执行结果 → 前扩到 call(规则1);
  // call 是未执行 tool call → 再级联前扩到 user(规则2),最终全量保留
  const r = limitContext([user, call, resultMsg, tail], 2);
  assert.equal(r.length, 4);
  assert.equal(r[0], user);
});

test('limitContext: 起点含未执行 tool call → 前扩到 user 消息', () => {
  const user = makeUserMessage('q');
  const call: UIMessage = { ...makeAssistantMessage(''), parts: [tool('t1', false)] };
  const tail = makeAssistantMessage('final');
  const r = limitContext([user, call, tail], 2);
  assert.equal(r.length, 3);
  assert.equal(r[0], user);
});

// ===== finishReasoning / finishPendingTools =====

test('finishReasoning: 只给未完成 reasoning 打 finishedAt', () => {
  const r1: UIMessagePartReasoning = { type: 'reasoning', reasoning: 'a', createdAt: 't0', finishedAt: null, metadata: null };
  const r2: UIMessagePartReasoning = { type: 'reasoning', reasoning: 'b', createdAt: 't0', finishedAt: 't1', metadata: null };
  const m = makeUIMessage('assistant', [r1, r2]);
  const done = finishReasoning(m);
  const p1 = done.parts[0] as UIMessagePartReasoning;
  const p2 = done.parts[1] as UIMessagePartReasoning;
  assert.ok(p1.finishedAt !== null);
  assert.equal(p2.finishedAt, 't1', '已完成的不动');
});

test('finishPendingTools: 转换未执行 tool + 设置 finishedAt + 关闭 reasoning', () => {
  const t = tool('t1', false);
  const m: UIMessage = {
    ...makeUIMessage('assistant', [
      { type: 'reasoning', reasoning: 'r', createdAt: 't0', finishedAt: null, metadata: null },
      t,
    ]),
  };
  const done = finishPendingTools(m, (pt) => ({ ...pt, output: [{ type: 'text', text: 'timeout', metadata: null }] }));
  const pt = done.parts[1] as UIMessagePartTool;
  assert.equal(pt.output.length, 1);
  assert.ok(done.finishedAt !== null);
  assert.ok((done.parts[0] as UIMessagePartReasoning).finishedAt !== null);
});

// ===== files 收集 =====

test('collectFileUrls: 递归 tool output,只收 file:// 前缀', () => {
  const t: UIMessagePartTool = {
    type: 'tool', toolCallId: 'c', toolName: 'n', input: '',
    output: [{ type: 'image', url: 'file:///tmp/a.png', metadata: null }],
    approvalState: { type: 'auto' }, metadata: null,
  };
  const c = makeConversation('c1', [
    toMessageNode(makeUIMessage('user', [
      { type: 'image', url: 'file:///tmp/b.png', metadata: null },
      { type: 'image', url: 'https://x/y.png', metadata: null },
      { type: 'document', url: 'file:///tmp/c.pdf', fileName: 'c.pdf', mime: 'application/pdf', metadata: null },
      t,
    ])),
  ]);
  const urls = collectFileUrls(c).sort();
  assert.deepEqual(urls, ['file:///tmp/a.png', 'file:///tmp/b.png', 'file:///tmp/c.pdf']);
});
