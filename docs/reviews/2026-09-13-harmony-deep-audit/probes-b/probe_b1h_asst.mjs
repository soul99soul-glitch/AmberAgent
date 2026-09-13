import { makeUIMessage } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/message.ts';
import { MessageStreamAccumulator } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/stream_accumulator.ts';
const mkTool = (id, name, approval) => ({ type:'tool', toolCallId:id, toolName:name, input:'{}', output:[], approvalState:approval, metadata:null });
const asst = makeUIMessage('assistant', [mkTool('', 'file_list', { type:'auto' }), mkTool('', 'file_write', { type:'approved' })]);
console.log('asst.parts names =', JSON.stringify(asst.parts.map(p=>p.toolName)));
const acc = new MessageStreamAccumulator([asst]);
acc.append({ id:'c', model:'m', choices:[{ index:0, delta: makeUIMessage('assistant',[{type:'text',text:'done',metadata:null}]), message:null, finishReason:'stop'}], usage:null });
console.log('after text delta =', JSON.stringify(acc.snapshot().at(-1).parts.map(p=>p.toolName ?? p.type)));
