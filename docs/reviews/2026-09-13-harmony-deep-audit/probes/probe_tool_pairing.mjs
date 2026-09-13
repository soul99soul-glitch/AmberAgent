// Probe: blank-toolCallId parallel tools + approval resume → result written onto
// the WRONG tool part and the approved tool executed twice.
// Uses the REAL runAgenticToolLoop + REAL AgentToolDispatcher + REAL dispatcher.executeBatch.
// Mock boundary: only the initial conversation state (post-approval) and the tool execute bodies.
import { runAgenticToolLoop } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/tool_loop.ts';
import { makeConversation, makeMessageNode } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/conversation.ts';
import { makeUIMessage } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/message.ts';
import { makeAgentTool } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/tool.ts';
import { AgentToolDispatcher, defaultToolInvocationHooks } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/tool_dispatcher.ts';
import { makeAssistant } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/assistant.ts';

const execLog = [];
const toolA = makeAgentTool({
  name: 'file_list', description: 'A read-only',
  execute: async () => { execLog.push('A'); return [{ type: 'text', text: 'OUT_A', metadata: null }]; },
});
const toolB = makeAgentTool({
  name: 'file_write', description: 'B mutating',
  mandatoryApproval: true,
  execute: async () => { execLog.push('B'); return [{ type: 'text', text: 'OUT_B', metadata: null }]; },
});

const mkTool = (id, name, approval) => ({
  type: 'tool', toolCallId: id, toolName: name, input: '{}',
  output: [], approvalState: approval, metadata: null,
});

const run = async (idA, idB) => {
  execLog.length = 0;
  const asst = makeUIMessage('assistant', [
    mkTool(idA, 'file_list', { type: 'auto' }),
    mkTool(idB, 'file_write', { type: 'approved' }),
  ]);
  const conv = makeConversation('conv-1', [makeMessageNode([asst], 0, 'node-1')]);
  // Second step needs a provider; emit a plain text delta so the loop breaks
  // (no new tool calls) right after the resume batch is written back.
  const provider = {
    streamText: async (msgs, onChunk) => {
      onChunk({ id: 'c', model: 'm', choices: [{ index: 0, delta: makeUIMessage('assistant', [{ type: 'text', text: 'done', metadata: null }]), message: null, finishReason: 'stop' }], usage: null });
    },
  };
  const deps = {
    assistant: makeAssistant({ name: 'a' }),
    inputTransformers: [], outputTransformers: [],
    provider,
    store: { save: async () => {} },
  };
  const loop = {
    tools: [toolA, toolB],
    maxSteps: 8,
    autoApproveTools: false,
    autoApproveHighRiskTools: false,
    invocationContext: 'normal',
    dispatcher: new AgentToolDispatcher({ hooks: defaultToolInvocationHooks() }),
    makeProviderForStep: () => provider,
  };
  const out = await runAgenticToolLoop(conv, deps, loop);
  const last = out.messageNodes[out.messageNodes.length - 1].messages[0];
  const tools = last.parts.filter((p) => p.type === 'tool');
  return { execLog: [...execLog], parts: tools.map((t) => ({ id: t.toolCallId, name: t.toolName, out: t.output.map(o => o.text ?? '').join('') })) };
};

console.log('--- blank toolCallId (both "") ---');
console.log(JSON.stringify(await run('', ''), null, 2));
console.log('--- distinct toolCallId ---');
console.log(JSON.stringify(await run('call-a', 'call-b'), null, 2));
