// B3 independent recheck (corrected): full chain with REAL modules matching
// ChatPage.runSendParts real wiring.
//   chatPage: this.runViaKernel(conv, deps, (d) => runChatTurnWithTools(conv, userInput, d, loop))
// Here `conv` is the conversation BEFORE the user message. runChatTurnWithTools
// persists the user message internally, then the provider throws. The kernel catch
// returns the ORIGINAL `conv` (no user msg). ChatPage.finishGenerationCommon then
// saves that returned conversation over the persisted one -> user message lost.
import { createChatKernelHost }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/chat_kernel_host.ts';
import { runChatTurnWithTools }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/tool_loop.ts';
import { makeConversation, makeMessageNode, currentMessages }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/conversation.ts';
import { makeUIMessage }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/message.ts';
import { makeAssistant }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/assistant.ts';

const run = async (label, err, throwOnError) => {
  const store = { last: null, save: async (c) => { store.last = c; } };
  const conv0 = makeConversation('c', [makeMessageNode([makeUIMessage('user', [{ type: 'text', text: 'pre-existing', metadata: null }])], 0, 'n0')]);
  await store.save(conv0);
  const provider = { streamText: async () => { throw err; } };
  const deps = {
    assistant: makeAssistant({ name: 'a' }), inputTransformers: [], outputTransformers: [],
    provider, store,
  };
  const loop = {
    tools: [], maxSteps: 4, autoApproveTools: false, autoApproveHighRiskTools: false,
    invocationContext: 'normal', makeProviderForStep: () => provider,
  };
  const host = createChatKernelHost({ onPhase: () => {} });
  console.log(`\n[${label}] err.name=${err.name} msg=${JSON.stringify(err.message)}`);
  try {
    const outcome = await host.run(conv0, deps, (d) => runChatTurnWithTools(conv0, 'hello from user', d, loop));
    console.log('  kernel RESOLVED, phase=', host.phase(), 'result=', outcome.result);
    console.log('  persisted-before-return (engine saves user internally) store nodes=', store.last.messageNodes.length);
    console.log('  out.conversation nodes=', outcome.conversation.messageNodes.length,
      '  (no user msg => data loss when saved)');
    // emulate ChatPage.finishGenerationCommon -> queueFinalConversationSave(out.conversation)
    await store.save(outcome.conversation);
    console.log('  AFTER ChatPage final save: store nodes=', store.last.messageNodes.length,
      ' texts=', JSON.stringify(currentMessages(store.last).map((m) => (m.parts[0] ?? {}).text)));
  } catch (e) {
    console.log('  kernel THREW, phase=', host.phase(), 'message=', e.message);
    console.log('  -> ChatPage .catch reloads store (user msg preserved): nodes=', store.last.messageNodes.length);
  }
};

await run('network error msg contains "aborted"', Object.assign(new Error('Request was aborted by upstream gateway'), { name: 'OpenAiStreamError' }));
await run('network error msg contains "abort"', Object.assign(new Error('stream abort by transport'), { name: 'Error' }));
await run('generic 500', new Error('500 server error'));
await run('named AbortError without aborting the signal (not genuine user Stop)', Object.assign(new Error('aborted'), { name: 'AbortError' }));
