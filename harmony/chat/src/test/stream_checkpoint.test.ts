// stream_checkpoint 纯逻辑测试(node:test,零 SDK 依赖)
//
// Android 基准: StreamCheckpoints.kt + ChatStreamCheckpointRecorder(512 字符合并信号)
// Phase 2 收口:轻量判定纯函数 + checkpointConversationTail 投影(可恢复部分快照)
import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  STREAM_CHECKPOINT_MIN_GROWTH_CHARS,
  shouldCheckpoint,
  checkpointConversationTail,
  checkpointRegenerateConversation,
} from '../main/ets/chat/stream_checkpoint.ts';
import { makeConversation, toMessageNode, currentMessages } from '../main/ets/chat/conversation.ts';
import { makeUIMessage, makeUserMessage } from '../main/ets/chat/message.ts';

// ===== shouldCheckpoint:10s + 增长阈值(Android 合并信号) =====

test('shouldCheckpoint:无增长 → false', () => {
  assert.equal(shouldCheckpoint(0, 0, 100, 100), false);
});

test('shouldCheckpoint:无基线(生成首段)→ true(早期落一次防崩溃丢内容)', () => {
  assert.equal(shouldCheckpoint(5000, 0, 0, 10), true);
});

test('shouldCheckpoint:距上次 >= 10s 且有增长 → true', () => {
  assert.equal(shouldCheckpoint(10_000, 0, 10, 11), true);
  assert.equal(shouldCheckpoint(10_000, 0, 10, 20), true);
});

test('shouldCheckpoint:10s 内增长不足阈值 → false', () => {
  assert.equal(shouldCheckpoint(5000, 1000, 10, 15), false);
});

test('shouldCheckpoint:10s 内增长达 512 字符合并信号 → true(Android)', () => {
  assert.equal(shouldCheckpoint(
    1000, 500, 10, 10 + STREAM_CHECKPOINT_MIN_GROWTH_CHARS), true);
});

test('shouldCheckpoint:10s 内增长恰在阈值之下 → false', () => {
  assert.equal(shouldCheckpoint(
    1000, 500, 10, 10 + STREAM_CHECKPOINT_MIN_GROWTH_CHARS - 1), false);
});

// ===== checkpointConversationTail:部分快照投影(不重复 append 节点) =====

test('checkpointConversationTail:同 id 消息原位替换,新消息追加', () => {
  const user = makeUserMessage('hi');
  const conv = makeConversation('c1', [toMessageNode(user)]);
  const snapshot = [
    user,
    makeUIMessage('assistant', [{ type: 'text', text: '流式前半', metadata: null }]),
  ];
  const projected = checkpointConversationTail(conv, snapshot);
  assert.equal(projected.messageNodes.length, 2);
  assert.equal(currentMessages(projected)[1].id, snapshot[1].id);
  const text = currentMessages(projected)[1].parts[0];
  assert.equal(text.type === 'text' && text.text, '流式前半');
});

test('checkpointConversationTail:重复投影不重复 append 节点(同 id 原位替换)', () => {
  const user = makeUserMessage('hi');
  const conv = makeConversation('c1', [toMessageNode(user)]);
  const snap1 = [
    user,
    makeUIMessage('assistant', [{ type: 'text', text: '前半', metadata: null }], { id: 'a1' }),
  ];
  let projected = checkpointConversationTail(conv, snap1);
  assert.equal(projected.messageNodes.length, 2);
  // 同 id 新内容 → 原位替换,节点数不变
  const snap2 = [
    user,
    makeUIMessage('assistant', [{ type: 'text', text: '前半后半', metadata: null }], { id: 'a1' }),
  ];
  projected = checkpointConversationTail(projected, snap2);
  assert.equal(projected.messageNodes.length, 2);
  const text = currentMessages(projected)[1].parts[0];
  assert.equal(text.type === 'text' && text.text, '前半后半');
});

test('checkpointRegenerateConversation:assistant 再生写入目标分支,后续节点保留', () => {
  const user = makeUserMessage('u1');
  const oldAssistant = makeUIMessage('assistant', [
    { type: 'text', text: 'old', metadata: null },
  ], { id: 'old-a' });
  const trailing = makeUserMessage('u2');
  const conv = makeConversation('c1', [
    toMessageNode(user), toMessageNode(oldAssistant), toMessageNode(trailing),
  ]);
  const generated = makeUIMessage('assistant', [
    { type: 'text', text: 'partial-new', metadata: null },
  ], { id: 'new-a' });
  const projected = checkpointRegenerateConversation(
    conv, conv.messageNodes[1].id, [user, generated],
  );
  assert.equal(projected.messageNodes.length, 3);
  assert.equal(projected.messageNodes[1].messages.length, 2);
  assert.equal(projected.messageNodes[1].selectIndex, 1);
  assert.equal(projected.messageNodes[1].messages[1].id, 'new-a');
  assert.equal(currentMessages(projected)[2].id, trailing.id);
});

test('checkpointRegenerateConversation:user 再生截断旧尾并追加部分 assistant', () => {
  const user1 = makeUserMessage('u1');
  const assistant1 = makeUIMessage('assistant', [
    { type: 'text', text: 'a1', metadata: null },
  ]);
  const user2 = makeUserMessage('u2');
  const staleAssistant = makeUIMessage('assistant', [
    { type: 'text', text: 'stale', metadata: null },
  ]);
  const conv = makeConversation('c1', [
    toMessageNode(user1), toMessageNode(assistant1),
    toMessageNode(user2), toMessageNode(staleAssistant),
  ]);
  const generated = makeUIMessage('assistant', [
    { type: 'text', text: 'partial-rerun', metadata: null },
  ], { id: 'rerun-a' });
  const projected = checkpointRegenerateConversation(
    conv, conv.messageNodes[2].id, [user1, assistant1, user2, generated],
  );
  assert.equal(projected.messageNodes.length, 4);
  assert.equal(currentMessages(projected)[3].id, 'rerun-a');
  assert.notEqual(currentMessages(projected)[3].id, staleAssistant.id);
});
