// B1 independent recheck (end-to-end, real modules).
// A non-conformant provider stream omits tool_call.id but keeps index 0/1.
// We use the REAL parseOpenAiStreamEventData (so blank ids come from the real
// parser, not a fabricated part), REAL runChatTurnWithTools, REAL
// applyToolApprovalToConversation, REAL runToolLoopContinuation + executeBatch.
// Only tool execute bodies and the provider transport are mocked.
import { runChatTurnWithTools, runToolLoopContinuation }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/tool_loop.ts';
import { createMemoryConversationStore }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/chat_turn.ts';
import { parseOpenAiStreamEventData }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/openai_parse.ts';
import { applyToolApprovalToConversation, conversationHasPendingTools }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/tool_approval.ts';
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

const execLog = [];
const toolA = makeAgentTool({
  name: 'file_list', description: 'A read-only',
  execute: async () => { execLog.push('A(file_list)'); return [{ type: 'text', text: 'OUT_A', metadata: null }]; },
});
const toolB = makeAgentTool({
  name: 'file_write', description: 'B mutating', mandatoryApproval: true,
  execute: async () => { execLog.push('B(file_write)'); return [{ type: 'text', text: 'OUT_B', metadata: null }]; },
});

// Two tool_calls WITHOUT "id", with index 0/1 (the exact shape that yields blank ids).
const step0 = JSON.stringify({
  id: 'c', model: 'm',
  choices: [{ index: 0, delta: {
    role: 'assistant',
    tool_calls: [
      { index: 0, type: 'function', function: { name: 'file_list', arguments: '{}' } },
      { index: 1, type: 'function', function: { name: 'file_write', arguments: '{}' } },
    ],
  }, finish_reason: null }],
});
const step1 = JSON.stringify({
  id: 'c', model: 'm',
  choices: [{ index: 0, delta: { role: 'assistant', content: 'done' }, finish_reason: 'stop' }],
});

let call = 0;
const provider = {
  streamText: async (_msgs, onChunk) => {
    const data = call++ === 0 ? step0 : step1;
    for (const chunk of parseOpenAiStreamEventData(data).chunks) onChunk(chunk);
  },
};

const store = createMemoryConversationStore();
const deps = {
  assistant: makeAssistant({ name: 'a' }),
  inputTransformers: [], outputTransformers: [],
  provider, store,
};
const loop = {
  tools: [toolA, toolB], maxSteps: 8,
  autoApproveTools: false, autoApproveHighRiskTools: false,
  invocationContext: 'normal',
  dispatcher: new AgentToolDispatcher({ hooks: defaultToolInvocationHooks() }),
  makeProviderForStep: () => provider,
};

const conv0 = makeConversation('c1', [makeMessageNode([makeUIMessage('user', [{ type: 'text', text: 'go', metadata: null }])], 0, 'n0')]);
const afterSend = await runChatTurnWithTools(conv0, 'please write', deps, loop);

const last = currentMessages(afterSend).at(-1);
const parts = last.parts.filter((p) => p.type === 'tool');
console.log('after send: tool parts =', JSON.stringify(parts.map((t) => ({
  id: t.toolCallId, name: t.toolName, state: t.approvalState.type,
}))));
console.log('conversationHasPendingTools =', conversationHasPendingTools(afterSend));

// Simulate the approval UI: onApproveTool(toolCallId) with the (blank) id of the pending tool.
const pending = parts.find((t) => t.approvalState.type === 'pending');
console.log('pending tool id =', JSON.stringify(pending ? pending.toolCallId : null));
const approvedConv = applyToolApprovalToConversation(afterSend, pending.toolCallId, { kind: 'approved' });
const approvedParts = currentMessages(approvedConv).at(-1).parts.filter((p) => p.type === 'tool');
console.log('after approve(""): states =', JSON.stringify(approvedParts.map((t) => ({
  id: t.toolCallId, name: t.toolName, state: t.approvalState.type,
}))));

execLog.length = 0;
const afterResume = await runToolLoopContinuation(approvedConv, deps, loop);
const rparts = currentMessages(afterResume).at(-1).parts.filter((p) => p.type === 'tool');
console.log('resume execLog =', JSON.stringify(execLog));
console.log('final tool parts =', JSON.stringify(rparts.map((t) => ({
  id: t.toolCallId, name: t.toolName,
  out: t.output.map((o) => o.text ?? '').join(''),
}))));
console.log('EXPECT file_list=OUT_A, file_write=OUT_B, each executed once;');
