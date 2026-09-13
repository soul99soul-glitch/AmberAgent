// B1c: enumerate state shapes that could make rewriteLastMessageTools mis-align.
// Uses REAL runAgenticToolLoop + REAL AgentToolDispatcher + REAL executeBatch +
// REAL resolveIdleToolBlocker + REAL applyToolApprovalToConversation.
import { runAgenticToolLoop }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/tool_loop.ts';
import { resolveIdleToolBlocker, applyToolApprovalToConversation }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/tool_approval.ts';
import { makeConversation, makeMessageNode }
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
const mk = (name, approved, exec) => makeAgentTool({
  name, description: name, mandatoryApproval: true,
  execute: async () => { execLog.push(name); return [{ type: 'text', text: `OUT_${name}`, metadata: null }]; },
});
const readTool = mk('file_read', false);
const writeTool = mk('file_write', false);
const askTool = makeAgentTool({
  name: ASK_USER_TOOL_NAME, description: 'ask',
  execute: async () => { execLog.push('ask'); return [{ type: 'text', text: 'ASKED', metadata: null }]; },
});
const tools = [readTool, writeTool, askTool];

const mkTool = (id, name, approval) => ({
  type: 'tool', toolCallId: id, toolName: name, input: '{}',
  output: [], approvalState: approval, metadata: null,
});

const provider = {
  streamText: async (_m, onChunk) => {
    onChunk({ id: 'c', model: 'm', choices: [{ index: 0, delta: makeUIMessage('assistant', [{ type: 'text', text: 'done', metadata: null }]), message: null, finishReason: 'stop' }], usage: null });
  },
};
const loop = () => ({
  tools, maxSteps: 8, autoApproveTools: false, autoApproveHighRiskTools: false,
  invocationContext: 'normal',
  dispatcher: new AgentToolDispatcher({ hooks: defaultToolInvocationHooks() }),
  makeProviderForStep: () => provider,
});
const deps = {
  assistant: makeAssistant({ name: 'a' }), inputTransformers: [], outputTransformers: [],
  provider, store: { save: async () => {} },
};

const runState = async (label, parts, mutate) => {
  execLog.length = 0;
  const asst = mkTool ? makeUIMessage('assistant', parts) : null;
  let conv = makeConversation('c', [makeMessageNode([asst], 0, 'n')]);
  if (mutate) conv = mutate(conv);
  const out = await runAgenticToolLoop(conv, deps, loop());
  const last = out.messageNodes.at(-1).messages[0];
  const ts = last.parts.filter((p) => p.type === 'tool');
  console.log(`\n[${label}] execLog=${JSON.stringify(execLog)}`);
  console.log('  parts =', JSON.stringify(ts.map((t) => ({
    id: t.toolCallId, name: t.toolName,
    out: t.output.map((o) => o.text ?? '').join(''), state: t.approvalState.type,
  }))));
};

// S1: [blank auto read, blank approved write]  (approval applied to BOTH by blank key)
await runState('S1 blank/blank approve-write', [
  mkTool('', 'file_read', { type: 'auto' }),
  mkTool('', 'file_write', { type: 'approved' }),
]);

// S1b: approve via real API on the blank id -> does it also approve the auto sibling?
{
  const conv = makeConversation('c', [makeMessageNode([makeUIMessage('assistant', [
    mkTool('', 'file_read', { type: 'auto' }),
    mkTool('', 'file_write', { type: 'pending' }),
  ])], 0, 'n')]);
  const ap = applyToolApprovalToConversation(conv, '', { kind: 'approved' });
  console.log('\n[S1b applyToolApproval("", approved)] states =',
    JSON.stringify(ap.messageNodes[0].messages[0].parts.map((p) => p.approvalState.type)));
}

// S2: mixed ids [blank auto read, "call-b" approved write]
await runState('S2 mixed blank/call-b', [
  mkTool('', 'file_read', { type: 'auto' }),
  mkTool('call-b', 'file_write', { type: 'approved' }),
]);

// S3: [blank auto read, blank pending ask] -> user answers -> resolveIdleToolBlocker
{
  const conv = makeConversation('c', [makeMessageNode([makeUIMessage('assistant', [
    mkTool('', 'file_read', { type: 'auto' }),
    mkTool('', ASK_USER_TOOL_NAME, { type: 'pending' }),
  ])], 0, 'n')]);
  const blocker = resolveIdleToolBlocker(conv, 'yes do it', true);
  console.log('\n[S3 ask_user answer] changed=', blocker.changed, 'shouldResume=', blocker.shouldResume,
    'states=', JSON.stringify(blocker.conversation.messageNodes[0].messages[0].parts.map((p) => p.approvalState.type)));
  execLog.length = 0;
  const out = await runAgenticToolLoop(blocker.conversation, deps, loop());
  const ts = out.messageNodes.at(-1).messages[0].parts.filter((p) => p.type === 'tool');
  console.log('  execLog=', JSON.stringify(execLog));
  console.log('  parts =', JSON.stringify(ts.map((t) => ({
    id: t.toolCallId, name: t.toolName,
    out: t.output.map((o) => o.text ?? '').join(''), state: t.approvalState.type,
  }))));
}
