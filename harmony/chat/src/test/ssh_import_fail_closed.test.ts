import { test } from 'node:test';
import assert from 'node:assert/strict';
import { captureSecretToolInput, clearAllSecretToolInputs, restoreSecretToolInput } from '../main/ets/chat/tool_input_vault.ts';
import { redactStoredToolInput, restoreStoredToolInput } from '../main/ets/chat/tool_input_redaction.ts';
import { externalizeConversation } from '../main/ets/chat/conversation_blobs.ts';
import { makeConversation, toMessageNode } from '../main/ets/chat/conversation.ts';
import { makeUIMessage } from '../main/ets/chat/message.ts';
import type { UIMessagePartTool } from '../main/ets/chat/message.ts';
import { AgentToolDispatcher } from '../main/ets/chat/tool_dispatcher.ts';
import { makeAgentTool } from '../main/ets/chat/tool.ts';
import { runChatTurnWithTools } from '../main/ets/chat/tool_loop.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import type { ChatStreamProvider } from '../main/ets/chat/chat_turn.ts';

test('HM-1: interrupted and non-object key imports fail closed through capture, storage and error output', async () => {
  clearAllSecretToolInputs();
  const secrets = ['synthetic-private-HM1', 'synthetic-passphrase-HM1'];
  const object = { private_key: secrets[0], passphrase: secrets[1] };
  const inputs = [JSON.stringify(object).slice(0, -1), JSON.stringify([object]), JSON.stringify(secrets[0])];
  const logs: string[] = [];
  const dispatcher = new AgentToolDispatcher({ log: line => logs.push(line) });
  const toolDef = makeAgentTool({ name: 'ssh_key_import', execute: async () => { throw Error('must not execute'); } });
  const noBlobs = { hash: () => '', write: async () => {}, read: async () => null,
    list: async () => [], remove: async () => {} };
  for (const input of inputs) {
    const part: UIMessagePartTool = { type: 'tool', toolCallId: 'hm1', toolName: 'ssh_key_import', input,
      output: [], approvalState: { type: 'approved' }, metadata: null };
    const captured = captureSecretToolInput(part.toolName, part.toolCallId, input);
    const stored = redactStoredToolInput(part.toolName, input);
    const durable = await externalizeConversation(makeConversation('hm1', [
      toMessageNode(makeUIMessage('assistant', [part])),
    ]), noBlobs);
    const provider: ChatStreamProvider = { streamText: async (_messages, onChunk) => {
      onChunk({ id: 'hm1-chunk', model: 'synthetic', usage: null, choices: [{ index: 0, message: null,
        finishReason: 'unknown', delta: makeUIMessage('assistant', [{ ...part, approvalState: { type: 'auto' } }]),
      }] });
    } };
    const pending = await runChatTurnWithTools(makeConversation('hm1-generated', []), 'synthetic import', {
      assistant: makeAssistant({ streamOutput: false }), inputTransformers: [], outputTransformers: [],
      provider, store: createMemoryConversationStore(),
    }, { tools: [{ ...toolDef, needsApproval: true }], makeProviderForStep: () => provider, maxSteps: 1 });
    const failed = await dispatcher.execute(part, toolDef);
    const observable = JSON.stringify({ captured, stored, durable, pending, failed, logs });
    // Boolean assertions keep synthetic credentials out of failure diagnostics too.
    assert.equal(secrets.some(secret => observable.includes(secret)), false);
    assert.equal(captured.captured, false);
    assert.equal(restoreSecretToolInput(part.toolName, part.toolCallId, JSON.parse(captured.input)).ok, false);
    assert.equal(restoreStoredToolInput(part.toolName, stored).status, 'unavailable');
    assert.equal(failed?.output.some(output => output.type === 'text' && output.text.includes('secret_input_unavailable')), true);
  }
});
