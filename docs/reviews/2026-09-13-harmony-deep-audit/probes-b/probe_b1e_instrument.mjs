// B1e: instrument why blank/blank with DISTINCT names yields file_write twice.
import { runAgenticToolLoop }
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

const execLog = [];
const toolA = makeAgentTool({ name: 'file_list', description: 'A',
  execute: async () => { execLog.push('A'); return [{ type: 'text', text: 'OUT_A', metadata: null }]; } });
const toolB = makeAgentTool({ name: 'file_write', description: 'B', mandatoryApproval: true,
  execute: async () => { execLog.push('B'); return [{ type: 'text', text: 'OUT_B', metadata: null }]; } });

const mkTool = (id, name, approval) => ({
  type: 'tool', toolCallId: id, toolName: name, input: '{}', output: [], approvalState: approval, metadata: null });

const dis = new AgentToolDispatcher({ hooks: defaultToolInvocationHooks() });
const origBatch = dis.executeBatch.bind(dis);
dis.executeBatch = async (tools, ...rest) => {
  console.log('  executeBatch input =', JSON.stringify(tools.map((t) => ({ id: t.toolCallId, name: t.toolName, state: t.approvalState.type }))));
  const out = await origBatch(tools, ...rest);
  console.log('  executeBatch output=', JSON.stringify(out.map((t) => ({ id: t.toolCallId, name: t.toolName }))));
  return out;
};
const origResolve = dis.resolveDecision.bind(dis);
dis.resolveDecision = (toolDef, tool, ...rest) => {
  const d = origResolve(toolDef, tool, ...rest);
  console.log('  resolveDecision', tool.toolName, 'def?', toolDef !== null, '->', d.action);
  return d;
};

const asst = makeUIMessage('assistant', [mkTool('', 'file_list', { type: 'auto' }), mkTool('', 'file_write', { type: 'approved' })]);
const conv = makeConversation('conv-1', [makeMessageNode([asst], 0, 'node-1')]);
const provider = { streamText: async (_m, onChunk) => {
  onChunk({ id: 'c', model: 'm', choices: [{ index: 0, delta: makeUIMessage('assistant', [{ type: 'text', text: 'done', metadata: null }]), message: null, finishReason: 'stop' }], usage: null });
} };
const deps = { assistant: makeAssistant({ name: 'a' }), inputTransformers: [], outputTransformers: [], provider, store: { save: async () => {} } };
const loop = {
  tools: [toolA, toolB], maxSteps: 8, autoApproveTools: false, autoApproveHighRiskTools: false,
  invocationContext: 'normal', dispatcher: dis, makeProviderForStep: () => provider,
};
console.log('input parts =', JSON.stringify(currentMessages(conv).at(-1).parts.filter((p) => p.type === 'tool').map((t) => t.toolName)));
const out = await runAgenticToolLoop(conv, deps, loop);
const last = out.messageNodes.at(-1).messages[0];
console.log('execLog =', JSON.stringify(execLog));
console.log('final parts =', JSON.stringify(last.parts.filter((p) => p.type === 'tool').map((t) => ({ id: t.toolCallId, name: t.toolName, out: t.output.map((o) => o.text).join('') }))));
