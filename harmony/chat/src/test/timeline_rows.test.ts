import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { buildChatTimelineRows } from '../main/ets/chat/timeline_rows.ts';
import { makeConversation, toMessageNode, updateCurrentMessages } from '../main/ets/chat/conversation.ts';
import type { MessageNode } from '../main/ets/chat/conversation.ts';
import { makeUIMessage } from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePartTool } from '../main/ets/chat/message.ts';
import { applyToolApprovalToConversation } from '../main/ets/chat/tool_approval.ts';

const textMessage = (text: string): UIMessage => makeUIMessage('user', [{ type: 'text', text, metadata: null }]);
const pendingTool: UIMessagePartTool = {
  type: 'tool', toolCallId: 'ssh-call', toolName: 'terminal_execute', input: '{"command":"printf ok"}',
  output: [], approvalState: { type: 'pending' }, metadata: { terminal_target_label: 'user@host:22' },
};

describe('chat timeline immutable row replacement', () => {
  it('approval and completed output/final text rebuild only their existing node', () => {
    const user = textMessage('run command');
    const assistant = makeUIMessage('assistant', [pendingTool]);
    const conv = makeConversation('conversation', [toMessageNode(user), toMessageNode(assistant)]);
    const pending = buildChatTimelineRows(conv.messageNodes);
    const approved = applyToolApprovalToConversation(conv, 'ssh-call', { kind: 'approved' });
    const approvedRows = buildChatTimelineRows(approved.messageNodes, pending);
    assert.equal(approvedRows[0].displayRevision, pending[0].displayRevision);
    assert.equal(approvedRows[1].nodeId, pending[1].nodeId);
    assert.equal(approvedRows[1].displayRevision, pending[1].displayRevision + 1);
    const completedTool: UIMessagePartTool = {
      ...pendingTool, approvalState: { type: 'approved' },
      output: [{ type: 'text', text: '{"status":"completed","exit_code":0}', metadata: null }],
    };
    const completed = updateCurrentMessages(approved, [user, {
      ...assistant, parts: [completedTool, { type: 'text', text: 'Command completed.', metadata: null }],
    }]);
    const completedRows = buildChatTimelineRows(completed.messageNodes, approvedRows);
    assert.equal(completedRows[0].displayRevision, pending[0].displayRevision);
    assert.equal(completedRows[1].nodeId, pending[1].nodeId);
    assert.equal(completedRows[1].displayRevision, approvedRows[1].displayRevision + 1);
  });

  it('equal reloaded messages and reordered rows preserve each node revision', () => {
    const nodes = [toMessageNode(textMessage('one')), toMessageNode(textMessage('two'))];
    const rows = buildChatTimelineRows(nodes);
    rows[0].displayRevision = 3;
    rows[1].displayRevision = 5;
    const reloaded: MessageNode[] = JSON.parse(JSON.stringify(nodes)) as MessageNode[];
    const next = buildChatTimelineRows([reloaded[1], reloaded[0]], rows);
    assert.deepEqual(next.map((row) => row.displayRevision), [5, 3]);
  });

  it('same-length output changes are observed even with unchanged output count', () => {
    const tool: UIMessagePartTool = { ...pendingTool, approvalState: { type: 'approved' },
      output: [{ type: 'text', text: 'one', metadata: null }] };
    const message = makeUIMessage('assistant', [tool]);
    const node = toMessageNode(message);
    const rows = buildChatTimelineRows([node]);
    const nextTool: UIMessagePartTool = { ...tool, output: [{ type: 'text', text: 'two', metadata: null }] };
    const next = buildChatTimelineRows([{ ...node, messages: [{ ...message, parts: [nextTool] }] }], rows);
    assert.equal(next[0].displayRevision, rows[0].displayRevision + 1);
  });

  it('branch and last-assistant controls advance the affected node, new rows begin at zero', () => {
    const message = makeUIMessage('assistant', [{ type: 'text', text: 'first', metadata: null }]);
    const node = toMessageNode(message);
    const rows = buildChatTimelineRows([node]);
    const variant = makeUIMessage('assistant', [{ type: 'text', text: 'second', metadata: null }]);
    const selected = { ...node, messages: [message, variant], selectIndex: 1 };
    const branchRows = buildChatTimelineRows([selected], rows);
    assert.equal(branchRows[0].displayRevision, rows[0].displayRevision + 1);
    const newNode = toMessageNode(makeUIMessage('assistant', []));
    const appended = buildChatTimelineRows([selected, newNode], branchRows);
    assert.equal(appended[0].isLastAssistant, false);
    assert.equal(appended[0].displayRevision, branchRows[0].displayRevision + 1);
    assert.equal(appended[1].displayRevision, 0);
  });
});
