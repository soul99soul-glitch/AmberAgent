// fork 会话分叉测试
// Android 基准:ChatService.forkConversationAtMessage(ChatService.kt:2088-2127)
//   - 复制 0..targetIndex(含)的节点;**node id 全部换新**,message id 保留
//   - 新 conversation:id 新、assistantId 相同、title 默认空(Android Conversation()
//     默认参数,自动命名后续补)
//   - messageId 未命中 → NoSuchElementException
// 裁剪:copyWithForkedFileUrl(本地文件复制)= 多模态输入落地后 P1(当前鸿蒙无
//   文件类 part 来源);copyValidCompactsToConversation(ContextEngine = P1)
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  makeConversation, toMessageNode, makeUserMessage, makeAssistantMessage,
} from '../main/ets/index.ts';
import type { Conversation } from '../main/ets/index.ts';
import { forkConversation } from '../main/ets/chat/fork.ts';

const baseConv = (): Conversation => ({
  ...makeConversation('conv-f', [], { assistantId: 'asst-1', title: '原标题' }),
  messageNodes: [
    { id: 'n1', messages: [makeUserMessage('u1')], selectIndex: 0 },
    { id: 'n2', messages: [makeAssistantMessage('a1'), makeAssistantMessage('a1-alt')], selectIndex: 1 },
    { id: 'n3', messages: [makeUserMessage('u2')], selectIndex: 0 },
  ],
});

describe('forkConversation', () => {
  it('中间消息 fork:复制 0..targetIndex(含),node id 换新,message id 保留', () => {
    const conv = baseConv();
    const a1Id = conv.messageNodes[1].messages[0].id;
    const fork = forkConversation(conv, a1Id, { newId: () => 'NEW' });
    assert.equal(fork.id, 'NEW');
    assert.equal(fork.assistantId, 'asst-1');
    assert.equal(fork.title, '');
    assert.equal(fork.messageNodes.length, 2);
    // node id 全部换新
    assert.equal(fork.messageNodes[0].id, 'NEW');
    assert.equal(fork.messageNodes[1].id, 'NEW');
    // message id 保留(Android 不换 message id)
    assert.equal(fork.messageNodes[0].messages[0].id, conv.messageNodes[0].messages[0].id);
    assert.equal(fork.messageNodes[1].messages.length, 2);
    assert.equal(fork.messageNodes[1].selectIndex, 1);
    // 原会话不受影响
    assert.equal(conv.messageNodes[0].id, 'n1');
    assert.equal(conv.messageNodes.length, 3);
  });

  it('末条消息 fork:全量复制', () => {
    const conv = baseConv();
    const u2Id = conv.messageNodes[2].messages[0].id;
    let seq = 0;
    const fork = forkConversation(conv, u2Id, { newId: () => `F${seq++}` });
    assert.equal(fork.messageNodes.length, 3);
  });

  it('未知 messageId → 抛 NoSuchElement 语义', () => {
    const conv = baseConv();
    assert.throws(() => forkConversation(conv, 'no-such', { newId: () => 'X' }), /not found/i);
  });
});
