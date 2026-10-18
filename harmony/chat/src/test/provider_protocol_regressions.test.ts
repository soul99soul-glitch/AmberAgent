import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonObject } from '../main/ets/chat/json.ts';
import type { MessageChunk, UIMessage, UIMessagePart, UIMessagePartImage } from '../main/ets/chat/message.ts';
import { makeAssistantMessage, makeUIMessage, makeUserMessage } from '../main/ets/chat/message.ts';
import { parseChatCompletionMessage, parseOpenAiStreamEventData } from '../main/ets/chat/openai_parse.ts';
import { parseGoogleMessagePart } from '../main/ets/chat/google_parse.ts';
import { createOpenAIResponsesApi } from '../main/ets/chat/openai_responses_api.ts';
import {
  parseResponsesOutput, parseResponsesStreamEvent, ResponseStreamReconciler,
} from '../main/ets/chat/openai_responses_parse.ts';
import { makeChatModel, makeProviderSettingOpenAI, makeTextGenerationParams } from '../main/ets/chat/provider_model.ts';
import { appendChunkToMessage, MessageStreamAccumulator } from '../main/ets/chat/stream_accumulator.ts';

const refusal = 'I cannot help with that request.';
const refusalItem: JsonObject = {
  id: 'msg_refusal', type: 'message', role: 'assistant', status: 'completed',
  content: [{ type: 'refusal', refusal }],
};
const imageItem = (id: string, result: string): JsonObject => ({
  id, type: 'image_generation_call', status: 'completed', result,
});
const png = (data: string): string => `data:image/png;base64,${data}`;
const deltaChunk = (parts: UIMessagePart[]): MessageChunk => ({
  id: 'fixture', model: '', usage: null,
  choices: [{ index: 0, delta: makeUIMessage('assistant', parts), message: null, finishReason: null }],
});
const accumulate = (chunks: MessageChunk[], immutable = false): UIMessage => {
  let message = makeAssistantMessage('');
  const accumulator = new MessageStreamAccumulator([message]);
  for (const chunk of chunks) {
    if (immutable) message = appendChunkToMessage(message, chunk);
    else accumulator.append(chunk);
  }
  return immutable ? message : accumulator.snapshot()[0];
};
const imagesOf = (message: UIMessage): UIMessagePartImage[] => message.parts
  .filter((part: UIMessagePart): part is UIMessagePartImage => part.type === 'image');
const visibleText = (message: UIMessage): string => message.parts
  .filter((part) => part.type === 'text').map((part) => part.text).join('');
const streamEvent = (event: JsonObject): MessageChunk => {
  const chunk = parseResponsesStreamEvent(event);
  assert.ok(chunk, `Missing parsed event: ${event.type}`);
  return chunk;
};

test('Chat refusal-only nonstream message and streaming deltas remain visible', () => {
  assert.equal(visibleText(parseChatCompletionMessage({ content: null, refusal })), refusal);
  const chunks = ['I cannot ', 'help with that request.'].flatMap((fragment: string) =>
    parseOpenAiStreamEventData(JSON.stringify({
      id: 'chat_refusal', choices: [{ delta: { refusal: fragment }, finish_reason: null }],
    })).chunks);
  assert.equal(visibleText(accumulate(chunks)), refusal);
});

test('Responses nonstream refusal content remains visible', () => {
  const chunk = parseResponsesOutput({ id: 'resp_refusal', status: 'completed', output: [refusalItem] });
  assert.equal(visibleText(chunk.choices[0].message!), refusal);
});

for (const doneOnly of [false, true]) {
  test(`Responses refusal ${doneOnly ? 'done-only' : 'delta + done'} terminal replay displays once`, () => {
    const reconciler = new ResponseStreamReconciler();
    const events: JsonObject[] = [];
    if (!doneOnly) events.push({ type: 'response.refusal.delta', item_id: 'msg_refusal', delta: 'I cannot ' });
    events.push(
      { type: 'response.refusal.done', item_id: 'msg_refusal', refusal },
      { type: 'response.output_item.done', item: refusalItem },
      { type: 'response.completed', response: { id: 'resp_refusal', status: 'completed', output: [refusalItem] } },
    );
    const chunks = events.map((event: JsonObject) => reconciler.reconcile(streamEvent(event)));
    assert.equal(visibleText(accumulate(chunks)), refusal);
  });
}

test('Responses generateText retains every generated image with its replay call ID', async () => {
  const api = createOpenAIResponsesApi({
    setting: makeProviderSettingOpenAI({ useResponseApi: true }),
    http: {
      fetch: async () => ({ status: 200, headers: {}, body: JSON.stringify({
        id: 'resp_image', status: 'completed', output: [
          imageItem('ig_1', 'aW1hZ2Ux'), imageItem('ig_2', 'aW1hZ2Uy'),
        ],
      }) }),
      fetchStream: async () => ({ status: 200, headers: {}, body: '' }),
    },
  });
  const chunk = await api.generateText([makeUserMessage('Draw two images')], makeTextGenerationParams({
    model: makeChatModel({ tools: ['image_generation'] }),
  }));
  const images = imagesOf(chunk.choices[0].message!);
  assert.deepEqual(images.map((image) => image.url), ['aW1hZ2Ux', 'aW1hZ2Uy']);
  assert.deepEqual(images.map((image) => image.metadata?.openai_image_call_id), ['ig_1', 'ig_2']);
});

for (const immutable of [false, true]) {
  const variant = immutable ? 'immutable append' : 'mutable accumulator';
  test(`Complete Chat image boundaries survive ${variant}`, () => {
    const parsed = parseChatCompletionMessage({ images: [
      { type: 'image_url', image_url: { url: png('aW1hZ2Ux') } },
      { type: 'image_url', image_url: { url: png('aW1hZ2Uy') } },
    ] });
    const message = accumulate([deltaChunk(parsed.parts)], immutable);
    assert.deepEqual(imagesOf(message).map((image) => image.url), [png('aW1hZ2Ux'), png('aW1hZ2Uy')]);
  });

  test(`Complete Google image boundaries and MIME survive ${variant}`, () => {
    const parts = [
      parseGoogleMessagePart({ inlineData: { mimeType: 'image/jpeg', data: 'LzlqL2ZpcnN0' } }),
      parseGoogleMessagePart({ inlineData: { mimeType: 'image/png', data: 'c2Vjb25k' } }),
    ];
    const message = accumulate([deltaChunk(parts)], immutable);
    assert.deepEqual(imagesOf(message).map((image) => image.url), [
      'data:image/jpeg;base64,LzlqL2ZpcnN0', png('c2Vjb25k'),
    ]);
  });

  test(`Responses image ID completes separated placeholders and terminal replay once in ${variant}`, () => {
    const reconciler = new ResponseStreamReconciler();
    const events: JsonObject[] = [
      { type: 'response.output_item.added', item: { id: 'ig_1', type: 'image_generation_call' } },
      { type: 'response.output_text.delta', item_id: 'msg_image', delta: 'Two images' },
      { type: 'response.output_item.added', item: { id: 'ig_2', type: 'image_generation_call' } },
      { type: 'response.output_item.done', item: imageItem('ig_1', 'aW1hZ2Ux') },
      { type: 'response.output_item.done', item: imageItem('ig_2', 'aW1hZ2Uy') },
      { type: 'response.completed', response: { id: 'resp_image', status: 'completed', output: [
        imageItem('ig_1', 'aW1hZ2Ux'),
        { type: 'message', content: [{ type: 'output_text', text: 'Two images' }] },
        imageItem('ig_2', 'aW1hZ2Uy'),
        imageItem('ig_3', 'aW1hZ2Uz'),
      ] } },
    ];
    const chunks = events.map((event: JsonObject) => reconciler.reconcile(streamEvent(event)));
    const message = accumulate(chunks, immutable);
    assert.equal(visibleText(message), 'Two images');
    const images = imagesOf(message);
    assert.deepEqual(images.map((image) => image.url), [png('aW1hZ2Ux'), png('aW1hZ2Uy'), png('aW1hZ2Uz')]);
    assert.deepEqual(images.map((image) => image.metadata?.openai_image_call_id), ['ig_1', 'ig_2', 'ig_3']);
  });

  test(`Legacy bare image fragments still concatenate in ${variant}`, () => {
    const message = accumulate([
      deltaChunk([{ type: 'image', url: 'AAAA', metadata: null }]),
      deltaChunk([{ type: 'image', url: 'BBBB', metadata: null }]),
    ], immutable);
    assert.deepEqual(imagesOf(message).map((image) => image.url), [png('AAAABBBB')]);
  });
}

test('Mistral content arrays retain every reasoning chunk and transition answer before scalar answer deltas', () => {
  const content: JsonObject[] = [
    { type: 'thinking', thinking: [{ type: 'text', text: 'Reasoning one. ' }, { type: 'text', text: 'Reasoning two.' }] },
    { type: 'text', text: 'Answer one. ' },
    { type: 'text', text: 'Answer two.' },
  ];
  const parsed = parseChatCompletionMessage({ content });
  assert.equal(visibleText(parsed), 'Answer one. Answer two.');
  assert.equal(parsed.parts.filter((part) => part.type === 'reasoning').map((part) => part.reasoning).join(''),
    'Reasoning one. Reasoning two.');
  const chunks = [
    ...parseOpenAiStreamEventData(JSON.stringify({ choices: [{ delta: { content } }] })).chunks,
    ...parseOpenAiStreamEventData(JSON.stringify({ choices: [{ delta: { content: ' End.' } }] })).chunks,
  ];
  assert.equal(visibleText(accumulate(chunks)), 'Answer one. Answer two. End.');
  assert.deepEqual(parsed.parts.map((part) => part.type), ['reasoning', 'text', 'text']);
});

test('Separate reasoning_content retains precedence over Mistral inline thinking without losing the answer', () => {
  const parsed = parseChatCompletionMessage({ reasoning_content: 'Separate reasoning', content: [
    { type: 'thinking', thinking: [{ type: 'text', text: 'Inline reasoning' }] },
    { type: 'text', text: 'Answer' },
  ] });
  assert.equal(visibleText(parsed), 'Answer');
  assert.deepEqual(parsed.parts.filter((part) => part.type === 'reasoning').map((part) => part.reasoning), ['Separate reasoning']);
  assert.equal(parsed.parts[0].metadata?.reasoning_content_present, true);
});

test('Mistral inline reasoning remains before the tool call it explains', () => {
  const parsed = parseChatCompletionMessage({ content: [
    { type: 'thinking', thinking: [{ type: 'text', text: 'Look up the answer' }] },
    { type: 'text', text: 'Searching' },
  ], tool_calls: [{ type: 'function', id: 'call_search', function: { name: 'search', arguments: '{}' } }] });
  assert.deepEqual(parsed.parts.map((part) => part.type), ['reasoning', 'tool', 'text']);
});
