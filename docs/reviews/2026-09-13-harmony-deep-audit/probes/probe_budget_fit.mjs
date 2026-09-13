// Probe: does the "final send-boundary fit" actually bound the request?
// Uses REAL assembleInternalMessages + REAL fitMessagesToTokenBudget.
import { assembleInternalMessages } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/context_assembly.ts';
import { fitMessagesToTokenBudget, estimateTokens } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/context_compact.ts';
import { makeUserMessage } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/message.ts';
import { makeAssistant } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/assistant.ts';

const budget = 4000;
// Simulate a long assistant.systemPrompt (like a long agent soul) + a history.
const assistant = makeAssistant({ name: 'a', systemPrompt: 'S'.repeat(60000) });
const history = [makeUserMessage('hi'), makeUserMessage('there')];
const blocks = [ 'M'.repeat(20000)]; // memory block, same as deps.memorySystemBlocks

const base = assembleInternalMessages({
  messages: history, assistant, agentSoul: '', contextMessageSize: 0,
  extraSystemBlocks: blocks,
});
console.log('assembled system count =', base.filter(m => m.role === 'system').length);
console.log('assembled estimate =', estimateTokens(base), 'budget =', budget);
const fitted = fitMessagesToTokenBudget(base, budget);
console.log('after finalTokenBudget fit estimate =', estimateTokens(fitted),
  ' OVER BUDGET?', estimateTokens(fitted) > budget);
console.log('system messages retained =', fitted.filter(m => m.role === 'system').length);
console.log('non-system messages retained =', fitted.filter(m => m.role !== 'system').length);
