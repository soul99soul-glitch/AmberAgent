// B1d: reproduce/explain the probe_tool_pairing "both file_write" anomaly with
// distinct tool names and blank ids, instrumenting the real pipeline.
import { runAgenticToolLoop }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/tool_loop.ts';
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

const execLog = [];
const toolA = makeAgentTool({ name: 'file_list', description: 'A read-only',
  execute: async () => { execLog.push('A'); return [{ type: 'text', text: 'OUT_A', metadata: null }]; } });
const toolB = makeAgentTool({ name: 'file_write', description: 'B mutating', mandatoryApproval: true,
  execute: async () => { execLog.push('B'); return [{ type: 'text', text: 'OUT_B', metadata: null }]; } });

const mkTool = (id, name, approval) => ({
  type: 'tool', toolCallId: id, toolName: name, input: '{}', output: [], approvalState: approval, metadata: null });

const run = async (idA, idB) => {
  execLog.length = 0;
  const asst = makeUIMessage('assistant', [mkTool(idA, 'file_list', { type: 'auto' }), mkTool(idB, 'file_write', { type: 'approved' })]);
  const conv = makeConversation('conv-1', [makeMessageNode([asst], 0, 'node-1')]);
  const provider = { streamText: async (_m, onChunk) => {
    onChunk({ id: 'c', model: 'm', choices: [{ index: 0, delta: makeUIMessage('assistant', [{ type: 'text', text: 'done', metadata: null }]), message: null, finishReason: 'stop' }], usage: null });
  } };
  const deps = { assistant: makeAssistant({ name: 'a' }), inputTransformers: [], outputTransformers: [], provider, store: { save: async () => {} } };
  const loop = {
    tools: [toolA, toolB], maxSteps: 8, autoApproveTools: false, autoApproveHighRiskTools: false,
    invocationContext: 'normal', dispatcher: new AgentToolDispatcher({ hooks: defaultToolInvocationHooks() }),
    makeProviderForStep: () => provider,
  };
  const out = await runAgenticToolLoop(conv, deps, loop);
  const last = out.messageNodes.at(-1).messages[0];
  console.log(JSON.stringify({ idA, idB, execLog, parts: last.parts.filter((p) => p.type === 'tool').map((t) => ({ id: t.toolCallId, name: t.toolName, out: t.output.map((o) => o.text ?? '').join('') })) }));
};

await run('', '');
await run('call-a', 'call-b');
await run('', 'call-b');
