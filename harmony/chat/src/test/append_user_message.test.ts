// append_user_message.test.ts — answer=false 派发(TDD 先行)
//
// Android 基准: ChatService.kt launchPendingMessageLoop(:847-850)
//   appendUserMessage(conversationId, dispatchMessage)
//   if (dispatchMessage.answer) handleMessageComplete(conversationId)
//   —— answer=false:仅入列 + 持久化,不触发生成;队列循环继续下一条

import { test } from 'node:test';
import assert from 'node:assert/strict';

import { runAppendUserMessage, createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import { makeConversation, currentMessages } from '../main/ets/chat/conversation.ts';
import { toText } from '../main/ets/chat/message.ts';

test('文本入列 + 持久化,不触发生成(无 provider 参与)', async () => {
  const store = createMemoryConversationStore();
  const conv = makeConversation('c1', []);
  const out = await runAppendUserMessage(conv, '只记录', store);
  assert.deepEqual(currentMessages(out).map(toText), ['只记录']);
  assert.equal(store.saved.length, 1);
  assert.equal(currentMessages(store.saved[0]).length, 1);
});

test('多模态 parts 原样入列', async () => {
  const store = createMemoryConversationStore();
  const conv = makeConversation('c1', []);
  const out = await runAppendUserMessage(conv, [
    { type: 'text', text: '看图', metadata: null },
    { type: 'image', url: 'data:image/png;base64,x', metadata: null },
  ], store);
  const msgs = currentMessages(out);
  assert.equal(msgs.length, 1);
  assert.equal(msgs[0].role, 'user');
  assert.equal(msgs[0].parts.length, 2);
  assert.equal(msgs[0].parts[1].type, 'image');
});

test('空输入 no-op(isEmptyInputMessage 同语义,不入列不持久化)', async () => {
  const store = createMemoryConversationStore();
  const conv = makeConversation('c1', []);
  const out = await runAppendUserMessage(conv, '   ', store);
  assert.equal(out, conv);
  assert.equal(store.saved.length, 0);
});
