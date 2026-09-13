// Probe: ChatKernelHost mistaking a provider error for a user cancel.
// chat_kernel_host.isAbortError() matches ANY Error whose message contains "abort".
// When it does, run() resolves (does NOT throw) with result=null and the ORIGINAL
// input conversation -> ChatPage's .then persists that original conversation,
// wiping the user message that chat_turn had already persisted.
import { createChatKernelHost } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/chat_kernel_host.ts';
import { makeConversation, makeMessageNode } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/conversation.ts';
import { makeUIMessage } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/message.ts';
import { makeAssistant } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/assistant.ts';

const input = makeConversation('c', [makeMessageNode([makeUIMessage('user', [{ type: 'text', text: 'pre-existing' }])], 0, 'n0')]);
const deps = {
  assistant: makeAssistant({ name: 'a' }), inputTransformers: [], outputTransformers: [],
  provider: { streamText: async () => {} }, store: { save: async () => {} },
};
for (const [label, err] of [
  ['network msg contains "aborted"', Object.assign(new Error('The request connection was aborted by the peer'), { name: 'Error' })],
  ['msg contains "abort" lowercase', Object.assign(new Error('stream abort by transport'), { name: 'Error' })],
  ['normal provider error', new Error('500 server error')],
]) {
  const host = createChatKernelHost({ onPhase: () => {} });
  const op = async () => { throw err; };
  try {
    const out = await host.run(input, deps, op);
    console.log(label, '=> RESOLVED (not thrown), phase=', host.phase(),
      'result=', out.result, 'conversation.nodes=', out.conversation.messageNodes.length);
  } catch (e) {
    console.log(label, '=> THREW, phase=', host.phase(), 'message=', String(e.message));
  }
}
