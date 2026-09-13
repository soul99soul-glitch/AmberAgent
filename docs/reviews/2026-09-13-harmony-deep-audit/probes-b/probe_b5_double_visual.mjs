// B5 independent recheck: double visual transform on the resume/regenerate-with-tools
// path, using the REAL runRegenerateAtWithTools + REAL regexOutputTransformer.
// Engine (tool_loop.emitSnapshot) applies outputTransformers once, then ChatPage's
// onUpdate re-applies applyVisualTransformersStreamingTail -> two passes.
import { runRegenerateAtWithTools }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/tool_loop.ts';
import { applyVisualTransformersStreamingTail }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/transformer_pipeline.ts';
import { regexOutputTransformer, thinkTagTransformer }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/transformers.ts';
import { makeConversation, makeMessageNode }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/conversation.ts';
import { makeUIMessage }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/message.ts';
import { makeAssistant, makeAssistantRegex }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/assistant.ts';
import { makeAgentTool }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/tool.ts';

const assistant = makeAssistant({
  name: 'a',
  // A realistic visual regex: wrap a word in asterisks (non-idempotent).
  regexes: [makeAssistantRegex({
    findRegex: 'foo', replaceString: '*foo*', enabled: true,
    affectingScope: ['assistant'], visualOnly: false,
  })],
});
const tools = [makeAgentTool({ name: 'noop', description: 'n', execute: async () => [{ type: 'text', text: 'ok', metadata: null }] })];
const provider = { streamText: async (_m, onChunk) => {
  onChunk({ id: 'c', model: 'm', choices: [{ index: 0, delta: makeUIMessage('assistant', [{ type: 'text', text: 'say foo', metadata: null }]), message: null, finishReason: 'stop' }], usage: null });
} };
const ctx = { assistant };
const chatPageOnUpdate = (msgs) => applyVisualTransformersStreamingTail(msgs, [thinkTagTransformer, regexOutputTransformer], ctx);

const previews = [];
const conv = makeConversation('c', [makeMessageNode([makeUIMessage('user', [{ type: 'text', text: 'hi', metadata: null }])], 0, 'n0'), makeMessageNode([makeUIMessage('assistant', [{ type: 'text', text: 'old', metadata: null }])], 0, 'n1')]);
const deps = {
  assistant, inputTransformers: [], outputTransformers: [thinkTagTransformer, regexOutputTransformer],
  provider, store: { save: async () => {} },
  // engine already applies outputTransformers before calling onUpdate; ChatPage re-applies:
  onUpdate: (msgs) => {
    const engineOut = msgs; // already engine-visual-transformed
    const displayed = chatPageOnUpdate(engineOut);
    const ai = [...displayed].reverse().find((m) => m.role === 'assistant');
    const text = ai !== undefined ? ai.parts.filter((p) => p.type === 'text').map((p) => p.text).join('') : '';
    if (text.length > 0) previews.push(text);
  },
};
const loop = { tools, maxSteps: 2, autoApproveTools: true, autoApproveHighRiskTools: true, invocationContext: 'normal', makeProviderForStep: () => provider };
const out = await runRegenerateAtWithTools(conv, 'n1', deps, loop);
const finalMsg = out.messageNodes.flatMap((n) => n.messages).find((m) => m.role === 'assistant' && m.parts.some((p) => p.type === 'text' && p.text.includes('foo')));
console.log('engine-once previews captured =', JSON.stringify(previews.slice(-3)));
console.log('displayed (double) preview last =', JSON.stringify(previews.at(-1)));
console.log('correct single transform        =', JSON.stringify(applyVisualTransformersStreamingTail([makeUIMessage('assistant', [{ type: 'text', text: 'say foo', metadata: null }])], [thinkTagTransformer, regexOutputTransformer], ctx)[0].parts[0].text));
console.log('stored(final) raw text          =', JSON.stringify(finalMsg ? finalMsg.parts.filter((p) => p.type === 'text').map((p) => p.text).join('') : null));
