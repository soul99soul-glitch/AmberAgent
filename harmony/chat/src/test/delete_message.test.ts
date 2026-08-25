// delete_message 测试
// Android 基准:ChatService.buildConversationAfterMessageDelete(ChatService.kt:2182-2208)
//   - 从目标 node 移除该 message;node 空 → 移除整个 node;否则 selectIndex
//     夹到 lastIndex;messageId 未命中 → null(failIfMissing=true → 抛
//     NoSuchElementException;=false → 原样返回)
//   - invalidateCompacts(ContextEngine = P1,无对应物,已登记)
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  makeConversation, toMessageNode, makeUserMessage, makeAssistantMessage,
} from '../main/ets/index.ts';
import type { Conversation } from '../main/ets/index.ts';
import { deleteMessage } from '../main/ets/chat/delete_message.ts';

const branchConv = (): Conversation => {
  const u = makeUserMessage('u1');
  const a1 = makeAssistantMessage('a1');
  const a1alt = makeAssistantMessage('a1-alt');
  return {
    ...makeConversation('conv-d', []),
    messageNodes: [
      toMessageNode(u),
      { id: 'node-a', messages: [a1, a1alt], selectIndex: 1 },
      toMessageNode(makeUserMessage('u2')),
    ],
  };
};

describe('deleteMessage', () => {
  it('多分支节点删一条:节点保留,selectIndex 夹到 lastIndex', () => {
    const conv = branchConv();
    const altId = conv.messageNodes[1].messages[1].id;
    const out = deleteMessage(conv, altId);
    assert.equal(out.messageNodes.length, 3);
    assert.equal(out.messageNodes[1].messages.length, 1);
    assert.equal(out.messageNodes[1].selectIndex, 0);
  });

  it('删除未选中分支:selectIndex 不变(≤ lastIndex)', () => {
    const conv = branchConv();
    const firstId = conv.messageNodes[1].messages[0].id;
    const out = deleteMessage(conv, firstId);
    assert.equal(out.messageNodes[1].messages.length, 1);
    // 原 selectIndex=1,删后 lastIndex=0 → 夹到 0
    assert.equal(out.messageNodes[1].selectIndex, 0);
  });

  it('单消息节点:整个节点移除', () => {
    const conv = branchConv();
    const u2Id = conv.messageNodes[2].messages[0].id;
    const out = deleteMessage(conv, u2Id);
    assert.equal(out.messageNodes.length, 2);
    assert.equal(out.messageNodes[1].id, 'node-a');
  });

  it('未知 messageId:failIfMissing 默认 true → 抛 NoSuchElement 语义', () => {
    const conv = branchConv();
    assert.throws(() => deleteMessage(conv, 'no-such-id'), /not found/i);
  });

  it('未知 messageId + failIfMissing=false → 原样返回', () => {
    const conv = branchConv();
    const out = deleteMessage(conv, 'no-such-id', false);
    assert.deepEqual(out.messageNodes, conv.messageNodes);
  });
});

describe('deleteMessage 分支定位修复', () => {
  it('三个分支删除最前面的未选中项:当前选中分支保持不变(索引前移)', () => {
    const a1 = makeAssistantMessage('a1');
    const a2 = makeAssistantMessage('a2');
    const a3 = makeAssistantMessage('a3');
    const conv: Conversation = {
      ...makeConversation('conv-br', []),
      messageNodes: [
        { id: 'node-b', messages: [a1, a2, a3], selectIndex: 2 },
      ],
    };
    const out = deleteMessage(conv, a1.id);
    const node = out.messageNodes[0];
    assert.equal(node.messages.length, 2);
    assert.equal(node.messages[node.selectIndex].id, a3.id,
      '删除前面的分支后仍选中原来的 a3,而不是错切到 a2');
  });

  it('删除的就是当前选中项:回退 clamp 语义(与基准一致)', () => {
    const a1 = makeAssistantMessage('a1');
    const a2 = makeAssistantMessage('a2');
    const a3 = makeAssistantMessage('a3');
    const conv: Conversation = {
      ...makeConversation('conv-br2', []),
      messageNodes: [
        { id: 'node-c', messages: [a1, a2, a3], selectIndex: 2 },
      ],
    };
    const out = deleteMessage(conv, a3.id);
    assert.equal(out.messageNodes[0].selectIndex, 1);
  });
});
