import { runAgenticToolLoop } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/tool_loop.ts';
import { makeConversation, makeMessageNode } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/conversation.ts';
import { makeUIMessage } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/message.ts';
import { makeAgentTool } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/tool.ts';
import { AgentToolDispatcher, defaultToolInvocationHooks } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/tool_dispatcher.ts';
import { makeAssistant } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/assistant.ts';

const toolA = makeAgentTool({ name: 'file_list', description: 'A', execute: async () => [{ type: 'text', text: 'OUT_A', metadata: null }] });
const toolB = makeAgentTool({ name: 'file_write', description: 'B', mandatoryApproval: true, execute: async () => [{ type: 'text', text: 'OUT_B', metadata: null }] });
const mkTool = (id, name, approval) => ({ type:'tool', toolCallId:id, toolName:name, input:'{}', output:[], approvalState:approval, metadata:null });
const asst = makeUIMessage('assistant', [mkTool('', 'file_list', { type:'auto' }), mkTool('', 'file_write', { type:'approved' })]);
const conv0 = makeConversation('conv-1', [makeMessageNode([asst], 0, 'node-1')]);
const provider = { streamText: async (_m, onChunk) => { onChunk({ id:'c', model:'m', choices:[{ index:0, delta: makeUIMessage('assistant',[{type:'text',text:'done',metadata:null}]), message:null, finishReason:'stop'}], usage:null }); } };
const deps = { assistant: makeAssistant({ name:'a' }), inputTransformers:[], outputTransformers:[], provider,
  store: { save: async () => {} },
  onRawFlushSnapshot: (msgs) => { const last = msgs.at(-1); console.log('raw last tools=', JSON.stringify(last.parts.filter(p=>p.type==='tool').map(t=>({id:t.toolCallId,name:t.toolName})))); },
};
const loop = { tools:[toolA,toolB], maxSteps:8, autoApproveTools:false, autoApproveHighRiskTools:false, invocationContext:'normal', dispatcher: new AgentToolDispatcher({hooks:defaultToolInvocationHooks()}), makeProviderForStep: () => provider };
await runAgenticToolLoop(conv0, deps, loop);
