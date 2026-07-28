// edit_message 测试
// Android 基准:ChatService.editMessage(ChatService.kt:2054-2086)
//   - 空 parts → 直接返回(isEmptyInputMessage)
//   - 目标 message 所在 node **追加新消息**(role = node.role,parts = 新内容,
//     新 id)作为新分支;selectIndex 指向新分支;旧分支保留
//   - messageId 未命中 → edited=false → 原样返回(不抛错)
//   - 编辑本身不截断不重跑;重跑由调用方组合 regenerate(鸿蒙 UI:编辑+重发一步)
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  makeConversation, toMessageNode, makeUserMessage, makeAssistantMessage,
} from '../main/ets/index.ts';
import type { Conversation, UIMessagePart } from '../main/ets/index.ts';
import { editMessage } from '../main/ets/chat/edit_message.ts';

const textParts = (t: string): UIMessagePart[] => [{ type: 'text', text: t, metadata: null }];

const convWith = (): Conversation => ({
  ...makeConversation('conv-e', []),
  messageNodes: [
    toMessageNode(makeUserMessage('u1')),
    toMessageNode(makeAssistantMessage('a1')),
  ],
});

describe('editMessage', () => {
  it('user 节点编辑:新分支追加(role=user),selectIndex 指向新分支,旧分支保留', () => {
    const conv = convWith();
    const u1Id = conv.messageNodes[0].messages[0].id;
    const out = editMessage(conv, u1Id, textParts('u1-改'));
    const node = out.messageNodes[0];
    assert.equal(node.messages.length, 2);
    assert.equal(node.selectIndex, 1);
    assert.equal(node.messages[1].role, 'user');
    assert.equal(node.messages[1].parts[0].type === 'text'
      ? (node.messages[1].parts[0] as { text: string }).text : '', 'u1-改');
    // 旧分支原样
    assert.equal(node.messages[0].id, u1Id);
    // 新消息有新 id
    assert.notEqual(node.messages[1].id, u1Id);
  });

  it('assistant 节点编辑:role 跟随节点(=assistant)', () => {
    const conv = convWith();
    const a1Id = conv.messageNodes[1].messages[0].id;
    const out = editMessage(conv, a1Id, textParts('a1-改'));
    const node = out.messageNodes[1];
    assert.equal(node.messages.length, 2);
    assert.equal(node.messages[1].role, 'assistant');
    assert.equal(node.selectIndex, 1);
  });

  it('空 parts → 原样返回(isEmptyInputMessage 语义)', () => {
    const conv = convWith();
    const out = editMessage(conv, conv.messageNodes[0].messages[0].id, []);
    assert.deepEqual(out.messageNodes, conv.messageNodes);
  });

  it('空文本 parts → 原样返回(空白输入不建分支)', () => {
    const conv = convWith();
    const out = editMessage(conv, conv.messageNodes[0].messages[0].id, textParts('   '));
    assert.deepEqual(out.messageNodes, conv.messageNodes);
  });

  it('未知 messageId → 原样返回(edited=false 语义)', () => {
    const conv = convWith();
    const out = editMessage(conv, 'no-such-id', textParts('x'));
    assert.deepEqual(out.messageNodes, conv.messageNodes);
  });
});
