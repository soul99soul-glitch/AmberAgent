import { test } from 'node:test';
import assert from 'node:assert/strict';
import { generateAssistantOnce, createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import { ProviderResponseError } from '../main/ets/chat/provider_response_error.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { makeUIMessage, makeUserMessage, toText } from '../main/ets/chat/message.ts';
import type { UIMessage, MessageChunk } from '../main/ets/chat/message.ts';

test('nonstream protocol failure publishes its partial to raw checkpoint and UI, then rejects', async () => {
  const seed = [makeUserMessage('question')];
  const partial: MessageChunk = { id: 'partial', model: 'm', usage: null, choices: [{
    index: 0, delta: null, finishReason: 'SAFETY', message: makeUIMessage('assistant', [
      { type: 'text', text: '已收到的正文', metadata: null },
    ]),
  }] };
  const raw: UIMessage[][] = []; const visual: UIMessage[][] = [];
  await assert.rejects(generateAssistantOnce(seed, seed, {
    assistant: makeAssistant({ streamOutput: false }), inputTransformers: [], outputTransformers: [],
    store: createMemoryConversationStore(),
    provider: { streamText: async () => { throw new Error('unexpected stream'); },
      generateText: async () => { throw new ProviderResponseError('Google SAFETY', partial); } },
    onRawFlushSnapshot: messages => { raw.push(messages); },
    onUpdate: messages => { visual.push(messages); },
  }), /Google SAFETY/);
  assert.equal(raw.length, 1); assert.equal(visual.length, 1);
  assert.equal(toText(raw[0][1]), '已收到的正文');
  assert.equal(toText(visual[0][1]), '已收到的正文');
});

test('nonstream success publishes raw checkpoint through the same generation path', async () => {
  const seed = [makeUserMessage('question')]; const raw: UIMessage[][] = [];
  const completed = await generateAssistantOnce(seed, seed, {
    assistant: makeAssistant({ streamOutput: false }), inputTransformers: [], outputTransformers: [],
    store: createMemoryConversationStore(),
    provider: { streamText: async () => {}, generateText: async () => ({
      id: 'c', model: 'm', usage: null, choices: [{ index: 0, delta: null, finishReason: null,
        message: makeUIMessage('assistant', [{ type: 'text', text: 'answer', metadata: null }]) }],
    }) }, onRawFlushSnapshot: messages => { raw.push(messages); },
  });
  assert.equal(raw.length, 1); assert.equal(toText(raw[0][1]), 'answer');
  assert.equal(toText(completed[1]), 'answer');
});
