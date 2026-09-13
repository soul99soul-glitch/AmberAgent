// Probe: double visual-transform on resume/regenerate-with-tools paths.
// The engine's generateAssistantOnce already applies deps.outputTransformers'
// visualTransformTail before invoking deps.onUpdate. ChatPage.runSendParts.onUpdate
// treats that as final (no re-transform), but resumeToolLoop/runRegenerate re-apply
// applyVisualTransformersStreamingTail to the SAME message -> two passes.
// Uses the REAL regexOutputTransformer + REAL applyVisualTransformersStreamingTail.
import { applyVisualTransformersStreamingTail } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/transformer_pipeline.ts';
import { regexOutputTransformer } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/transformers.ts';
import { makeAssistant, makeAssistantRegex } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/assistant.ts';
import { makeUIMessage } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/message.ts';

const assistant = makeAssistant({
  name: 'a',
  regexes: [makeAssistantRegex({
    findRegex: 'foo', replaceString: 'foo!', enabled: true,
    affectingScope: ['assistant'], visualOnly: false,
  })],
});
const ctx = { assistant };
const msg = makeUIMessage('assistant', [{ type: 'text', text: 'foo', metadata: null }]);
const once = applyVisualTransformersStreamingTail([msg], [regexOutputTransformer], ctx)[0];
const twice = applyVisualTransformersStreamingTail([once], [regexOutputTransformer], ctx)[0];
console.log('once  text =', JSON.stringify(once.parts[0].text));
console.log('twice text =', JSON.stringify(twice.parts[0].text));
console.log('idempotent =', once.parts[0].text === twice.parts[0].text);
