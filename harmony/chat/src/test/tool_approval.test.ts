// tool_approval.test.ts — 工具审批 + 续跑判定(D-057 TDD)
//
// Android 基准: ChatService.kt:1122-1188/:869-970/:2535-2546/:142-157
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  isToolApprovalContinuation,
  conversationHasPendingOrUnexecutedTools,
  resolveApprovalState,
  applyToolApprovalToConversation,
  conversationHasPendingTools,
  cancelToolForNewUserMessage,
  skipStaleToolForContinuation,
  resolveIdleToolBlocker,
} from '../main/ets/chat/tool_approval.ts';
import { makeConversation, currentMessages, toMessageNode } from '../main/ets/chat/conversation.ts';
import type { Conversation, MessageNode } from '../main/ets/chat/conversation.ts';
import { makeUIMessage } from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePart, UIMessagePartTool, ToolApprovalState } from '../main/ets/chat/message.ts';

const toolPart = (
  callId: string, name: string, state: ToolApprovalState, output: UIMessagePart[] = [],
): UIMessagePartTool => ({
  type: 'tool', toolCallId: callId, toolName: name, input: '{}',
  output, approvalState: state, metadata: null,
});

const convWithTool = (part: UIMessagePartTool): Conversation => {
  const msg: UIMessage = makeUIMessage('assistant', [part]);
  return makeConversation('c1', [toMessageNode(msg)]);
};

const findPart = (conv: Conversation, callId: string): UIMessagePartTool => {
  const msgs = currentMessages(conv);
  for (const m of msgs) {
    for (const p of m.parts) {
      if (p.type === 'tool' && (p as UIMessagePartTool).toolCallId === callId) {
        return p as UIMessagePartTool;
      }
    }
  }
  throw new Error('part not found');
};

describe('isToolApprovalContinuation(:945-953)', () => {
  it('词表命中(忽略空白与标点/大小写)', () => {
    assert.equal(isToolApprovalContinuation('继续'), true);
    assert.equal(isToolApprovalContinuation('  OK!!  '), true);
    assert.equal(isToolApprovalContinuation('go ahead'), true); // 去空白 → goahead
    assert.equal(isToolApprovalContinuation('执行吧。'), true);
    assert.equal(isToolApprovalContinuation('Yes,'), true);
  });
  it('startsWith 继续/可以继续', () => {
    assert.equal(isToolApprovalContinuation('继续执行刚才的任务'), true);
    assert.equal(isToolApprovalContinuation('可以继续吗'), true);
  });
  it('非续跑文本 → false;空白 → false', () => {
    assert.equal(isToolApprovalContinuation('今天天气怎么样'), false);
    assert.equal(isToolApprovalContinuation('   '), false);
    assert.equal(isToolApprovalContinuation('不要继续'), false); // compact=不要继续,非词表且不 startsWith 继续
  });
});

describe('handleToolApproval 纯逻辑(:1133-1172)', () => {
  it('resolveApprovalState:answer 优先 > approved > denied', () => {
    assert.deepEqual(resolveApprovalState(true, '', '答'), { type: 'answered', answer: '答' });
    assert.deepEqual(resolveApprovalState(true), { type: 'approved' });
    assert.deepEqual(resolveApprovalState(false, '不行'), { type: 'denied', reason: '不行' });
  });
  it('applyToolApprovalToConversation 只改写目标 toolCallId', () => {
    const conv = convWithTool(toolPart('c1', 'file_write', { type: 'pending' }));
    const other = makeUIMessage('assistant', [toolPart('c2', 'file_read', { type: 'auto' })]);
    const conv2: Conversation = {
      ...conv,
      messageNodes: [...conv.messageNodes, { id: 'n2', messages: [other], selectIndex: 0 } as MessageNode],
    };
    const out = applyToolApprovalToConversation(conv2, 'c1', { kind: 'approved' });
    assert.equal(findPart(out, 'c1').approvalState.type, 'approved');
    assert.equal(findPart(out, 'c2').approvalState.type, 'auto');
  });
  it('R08 applyToolApprovalToConversation:空 id 命中多处 → 拒绝歧义(不全部批准)', () => {
    const blankA = toolPart('', 'file_write', { type: 'pending' });
    const blankB = toolPart('', 'ask_user', { type: 'pending' });
    const conv = convWithTool(blankA);
    const conv2: Conversation = {
      ...conv,
      messageNodes: [{
        ...conv.messageNodes[0],
        messages: [makeUIMessage('assistant', [blankA, blankB])],
      }],
    };
    // 旧 id-only 接口对 '' 会命中全部 → 必须原样返回
    const out = applyToolApprovalToConversation(conv2, '', { kind: 'approved' });
    assert.equal(out, conv2);
    const parts = currentMessages(out)[0].parts as UIMessagePartTool[];
    assert.equal(parts[0].approvalState.type, 'pending');
    assert.equal(parts[1].approvalState.type, 'pending');
    // part locator 精确指向第二个 part → 只改它
    const msgId = currentMessages(conv2)[0].id;
    const located = applyToolApprovalToConversation(conv2, '', { kind: 'approved' },
      { messageId: msgId, partIndex: 1 });
    const locatedParts = currentMessages(located)[0].parts as UIMessagePartTool[];
    assert.equal(locatedParts[0].approvalState.type, 'pending');
    assert.equal(locatedParts[1].approvalState.type, 'approved');
  });
  it('P2-3 locator 未命中/类型不符/重复 messageId → 返回原 conversation 引用', () => {
    const pending = toolPart('c1', 'file_write', { type: 'pending' });
    const text: UIMessagePart = { type: 'text', text: 'hi', metadata: null };
    const conv: Conversation = makeConversation('c1', [
      toMessageNode(makeUIMessage('assistant', [text, pending])),
    ]);
    const msgId = currentMessages(conv)[0].id;
    // text 下标 → 非 tool,未命中 → 原引用(ChatPage 的 out===previous 守卫生效)
    assert.equal(applyToolApprovalToConversation(conv, 'c1', { kind: 'approved' },
      { messageId: msgId, partIndex: 0 }), conv);
    // 错误 messageId → 原引用
    assert.equal(applyToolApprovalToConversation(conv, 'c1', { kind: 'approved' },
      { messageId: 'nope', partIndex: 1 }), conv);
    // toolCallId 与命中项不符 → 原引用
    assert.equal(applyToolApprovalToConversation(conv, 'other', { kind: 'approved' },
      { messageId: msgId, partIndex: 1 }), conv);
    // 正确 locator → 命中且 toolCallId 相符(空 id 合法)
    const ok = applyToolApprovalToConversation(conv, 'c1', { kind: 'approved' },
      { messageId: msgId, partIndex: 1 });
    assert.notEqual(ok, conv);
    assert.equal(findPart(ok, 'c1').approvalState.type, 'approved');
  });
  it('R08 applyToolApprovalToConversation:重复真实 id 也拒绝歧义', () => {
    const a = toolPart('dup', 'file_read', { type: 'pending' });
    const b = toolPart('dup', 'file_write', { type: 'pending' });
    const conv: Conversation = makeConversation('c1', [
      toMessageNode(makeUIMessage('assistant', [a, b])),
    ]);
    assert.equal(applyToolApprovalToConversation(conv, 'dup', { kind: 'denied', reason: 'x' }), conv);
    const msgId = currentMessages(conv)[0].id;
    const located = applyToolApprovalToConversation(conv, 'dup', { kind: 'denied', reason: 'x' },
      { messageId: msgId, partIndex: 0 });
    const parts = currentMessages(located)[0].parts as UIMessagePartTool[];
    assert.equal(parts[0].approvalState.type, 'denied');
    assert.equal(parts[1].approvalState.type, 'pending');
  });
  it('conversationHasPendingTools:任一节点 currentMessage 有 pending → true', () => {
    const conv = convWithTool(toolPart('c1', 'file_write', { type: 'pending' }));
    assert.equal(conversationHasPendingTools(conv), true);
    const approved = applyToolApprovalToConversation(conv, 'c1', { kind: 'approved' });
    assert.equal(conversationHasPendingTools(approved), false);
  });
  it('conversationHasPendingOrUnexecutedTools(:2543-2546)', () => {
    const pending = convWithTool(toolPart('c1', 'file_write', { type: 'pending' }));
    assert.equal(conversationHasPendingOrUnexecutedTools(pending), true);
    const executed = convWithTool(toolPart('c1', 'file_write', { type: 'approved' },
      [{ type: 'text', text: 'done', metadata: null }]));
    assert.equal(conversationHasPendingOrUnexecutedTools(executed), false);
    assert.equal(conversationHasPendingOrUnexecutedTools(makeConversation('c2', [])), false);
  });
});

describe('陈旧工具处理(:955-970 逐字)', () => {
  it('cancelToolForNewUserMessage', () => {
    const out = cancelToolForNewUserMessage(toolPart('c1', 'file_write', { type: 'pending' }));
    assert.equal((out.output[0] as { text: string }).text,
      '{"status":"cancelled","error":"A new user message arrived before this pending tool was approved, so AmberAgent cancelled the stale tool state and continued the conversation."}');
    assert.deepEqual(out.approvalState, {
      type: 'denied',
      reason: 'Cancelled because a new user message arrived before approval',
    });
  });
  it('skipStaleToolForContinuation', () => {
    const out = skipStaleToolForContinuation(toolPart('c1', 'file_write', { type: 'auto' }));
    assert.deepEqual(out.approvalState, {
      type: 'denied',
      reason: 'Skipped stale tool after user asked to continue',
    });
  });
});

describe('resolveIdleToolBlocker(:869-943)', () => {
  it('R15 「继续」不再批准 pending → 取消陈旧工具,shouldResume=false', () => {
    const conv = convWithTool(toolPart('c1', 'file_write', { type: 'pending' }));
    const r = resolveIdleToolBlocker(conv, '继续', true);
    assert.equal(r.changed, true);
    assert.equal(r.shouldResume, false);
    assert.equal(r.event, 'pending_tool_cancel');
    assert.equal(r.blockingCount, 1);
    assert.deepEqual(r.blockingToolNames, ['file_write']);
    assert.equal(findPart(r.conversation, 'c1').approvalState.type, 'denied');
  });
  it('R15 mandatory 工具 + 裸「继续」/续写句一律不得批准', () => {
    // mandatory 只在显式审批按钮路径生效;自然语言路径绝不能放行(R15)
    const conv = convWithTool(toolPart('c1', 'wm_eval', { type: 'pending' }));
    for (const word of ['继续', '继续写首诗', '可以继续吗', 'yes', '批准', 'go ahead']) {
      const r = resolveIdleToolBlocker(conv, word, true);
      assert.equal(findPart(r.conversation, 'c1').approvalState.type, 'denied',
        `"${word}" must not approve a pending tool`);
      assert.equal(r.shouldResume, false, `"${word}" must not resume as approval`);
    }
  });
  it('「继续」→ 非 pending 未执行(auto)工具 → 取消(不再 skipStale)', () => {
    const conv = convWithTool(toolPart('c1', 'file_read', { type: 'auto' }));
    const r = resolveIdleToolBlocker(conv, '继续', true);
    assert.equal(r.changed, true);
    assert.equal(r.shouldResume, false);
    assert.equal(r.event, 'pending_tool_cancel');
    assert.equal(findPart(r.conversation, 'c1').approvalState.type, 'denied');
  });
  it('普通新消息 → 陈旧工具 cancelled(逐字 JSON),shouldResume=false,event pending_tool_cancel', () => {
    const conv = convWithTool(toolPart('c1', 'file_write', { type: 'pending' }));
    const r = resolveIdleToolBlocker(conv, '换个话题', true);
    assert.equal(r.changed, true);
    assert.equal(r.shouldResume, false);
    assert.equal(r.event, 'pending_tool_cancel');
    const p = findPart(r.conversation, 'c1');
    assert.equal((p.output[0] as { text: string }).text.includes('"status":"cancelled"'), true);
    assert.equal(p.approvalState.type, 'denied');
  });
  it('ask_user pending + 任意非空文本 → Answered(用户文本),其他未执行工具保持', () => {
    const askPart = toolPart('c1', 'ask_user', { type: 'pending' });
    const otherPart = toolPart('c2', 'file_read', { type: 'auto' });
    const conv = convWithTool(askPart);
    const conv2: Conversation = {
      ...conv,
      messageNodes: [{
        ...conv.messageNodes[0],
        messages: [makeUIMessage('assistant', [askPart, otherPart])],
      }],
    };
    const r = resolveIdleToolBlocker(conv2, '我的回答是蓝色', true);
    assert.equal(r.changed, true);
    assert.equal(r.shouldResume, true);
    assert.deepEqual(findPart(r.conversation, 'c1').approvalState, {
      type: 'answered', answer: '我的回答是蓝色',
    });
    // hasAskUserAnswer 分支:其他工具保持原状
    assert.equal(findPart(r.conversation, 'c2').approvalState.type, 'auto');
  });
  it('非纯文本消息(带图片)→ 不做「继续」判定,走 cancel', () => {
    const conv = convWithTool(toolPart('c1', 'file_write', { type: 'pending' }));
    const r = resolveIdleToolBlocker(conv, '继续', false);
    assert.equal(r.changed, true);
    assert.equal(r.shouldResume, false);
    assert.equal(findPart(r.conversation, 'c1').approvalState.type, 'denied');
  });
});
