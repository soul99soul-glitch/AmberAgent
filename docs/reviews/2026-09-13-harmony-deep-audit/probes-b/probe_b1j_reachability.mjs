// B1j: REACHABILITY of the blank-id rewriteLastMessageTools misalignment via the
// real wired path: ask_user (pending, blank id) + a normal auto tool (blank id)
// in the same assistant message; user answers ask_user by typing free text
// (NOT a continuation word) -> resolveIdleToolBlocker leaves the auto tool auto
// (non-resumable) and marks ask answered (resumable). Then runToolLoopContinuation
// executes only the resumable subset -> position-align overwrites the wrong part.
import { resolveIdleToolBlocker }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/tool_approval.ts';
import { runToolLoopContinuation }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/tool_loop.ts';
import { makeConversation, makeMessageNode, currentMessages }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/conversation.ts';
import { makeUIMessage }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/message.ts';
import { makeAgentTool }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/tool.ts';
import { AgentToolDispatcher, defaultToolInvocationHooks }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/tool_dispatcher.ts';
import { makeAssistant }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/assistant.ts';
import { ASK_USER_TOOL_NAME }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/tool_permission.ts';

const execLog = [];
const fileList = makeAgentTool({ name: 'file_list', description: 'read',
  execute: async () => { execLog.push('file_list'); return [{ type: 'text', text: 'OUT_LIST', metadata: null }]; } });
const askUser = makeAgentTool({ name: ASK_USER_TOOL_NAME, description: 'ask',
  execute: async () => { execLog.push('ask_user'); return [{ type: 'text', text: 'OUT_ASK', metadata: null }]; } });

const mkTool = (id, name, approval) => ({
  type: 'tool', toolCallId: id, toolName: name, input: '{}', output: [], approvalState: approval, metadata: null });

// Emitted by one generation step: normal auto tool + ask_user -> both get blank ids
// (provider omitted tool_call.id). ask_user resolves to 'ask' -> pending.
let conv = makeConversation('c', [makeMessageNode([makeUIMessage('assistant', [
  mkTool('', 'file_list', { type: 'auto' }),
  mkTool('', ASK_USER_TOOL_NAME, { type: 'pending' }),
])], 0, 'n')]);

console.log('state before answer =', JSON.stringify(conv.messageNodes[0].messages[0].parts.map((p) => ({ id: p.toolCallId, name: p.toolName, st: p.approvalState.type }))));

// User types a free-text answer that is NOT a continuation word.
const blocker = resolveIdleToolBlocker(conv, '请用中文回答，另外把清单给我', true);
console.log('resolveIdleToolBlocker: changed=', blocker.changed, 'shouldResume=', blocker.shouldResume, 'blocking=', blocker.blockingToolNames);
conv = blocker.conversation;
const states = conv.messageNodes[0].messages[0].parts.map((p) => ({ id: p.toolCallId, name: p.toolName, st: p.approvalState.type }));
console.log('state after answer =', JSON.stringify(states));

const provider = { streamText: async (_m, onChunk) => {
  onChunk({ id: 'c', model: 'm', choices: [{ index: 0, delta: makeUIMessage('assistant', [{ type: 'text', text: 'done', metadata: null }]), message: null, finishReason: 'stop' }], usage: null });
} };
const deps = { assistant: makeAssistant({ name: 'a' }), inputTransformers: [], outputTransformers: [], provider, store: { save: async () => {} } };
const loop = {
  tools: [fileList, askUser], maxSteps: 4, autoApproveTools: false, autoApproveHighRiskTools: false,
  invocationContext: 'normal', dispatcher: new AgentToolDispatcher({ hooks: defaultToolInvocationHooks() }),
  makeProviderForStep: () => provider,
};
const out = await runToolLoopContinuation(conv, deps, loop);
const parts = currentMessages(out).at(-1).parts.filter((p) => p.type === 'tool');
console.log('execLog =', JSON.stringify(execLog));
console.log('final parts =', JSON.stringify(parts.map((t) => ({
  id: t.toolCallId, name: t.toolName, out: t.output.map((o) => o.text ?? '').join(''), st: t.approvalState.type,
}))));
console.log('\nEXPECT: file_list executed once (OUT_LIST), ask_user answered (answer text), no overwrite.');
