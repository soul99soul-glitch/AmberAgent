import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseClaudeMessage, parseClaudeStreamEvent } from '../main/ets/chat/claude_parse.ts';
import { buildClaudeMessages } from '../main/ets/chat/claude_request.ts';
import { buildResponsesInput } from '../main/ets/chat/openai_responses_request.ts';
import { MessageStreamAccumulator, appendChunkToMessage, coalesceStreamParts } from '../main/ets/chat/stream_accumulator.ts';
import { makeUIMessage, isValidToUpload } from '../main/ets/chat/message.ts';
import type { UIMessagePartReasoning } from '../main/ets/chat/message.ts';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';
import { groupMessageParts } from '../main/ets/chat/message_grouping.ts';

const thought = (body: string, metadata: JsonObject | null): UIMessagePartReasoning => ({
  type: 'reasoning', reasoning: body, createdAt: '2026-10-02T00:00:00Z', finishedAt: null, metadata,
});

test('Claude opaque blocks survive snapshot, serialization and protocol replay without empty UI cards', () => {
  const blocks: JsonValue[] = [
    { type: 'thinking', thinking: '', signature: 'sig-empty' },
    { type: 'redacted_thinking', data: 'cipher-one' },
    { type: 'redacted_thinking', data: 'cipher-two' },
  ];
  const source = parseClaudeMessage(blocks);
  const snapshot = new MessageStreamAccumulator([source]).snapshot()[0];
  assert.equal(snapshot.parts.length, 3);
  const stored = JSON.parse(JSON.stringify(snapshot));
  assert.equal(isValidToUpload(stored), true);
  assert.deepEqual(buildClaudeMessages([stored], false), [{ role: 'assistant', content: blocks }]);
  assert.deepEqual(groupMessageParts(stored.parts), []);
});

test('Claude stream thinking and fragmented signatures merge only within original indexed block', () => {
  const events = [
    ['content_block_start', { index: 0, content_block: { type: 'thinking', thinking: '', signature: '' } }],
    ['content_block_delta', { index: 0, delta: { type: 'thinking_delta', thinking: 'first' } }],
    ['content_block_delta', { index: 0, delta: { type: 'signature_delta', signature: 'sig-' } }],
    ['content_block_delta', { index: 0, delta: { type: 'signature_delta', signature: 'first' } }],
    ['content_block_start', { index: 1, content_block: { type: 'thinking', thinking: '', signature: '' } }],
    ['content_block_delta', { index: 1, delta: { type: 'thinking_delta', thinking: 'second' } }],
    ['content_block_delta', { index: 1, delta: { type: 'signature_delta', signature: 'sig-second' } }],
  ] as const;
  const initial = makeUIMessage('assistant', []);
  const accumulator = new MessageStreamAccumulator([initial]);
  let immutable = initial;
  for (const [type, event] of events) {
    const chunk = parseClaudeStreamEvent(type, undefined, JSON.stringify(event)).chunk;
    if (chunk !== null) {
      accumulator.append(chunk);
      immutable = appendChunkToMessage(immutable, chunk);
    }
  }
  const expected = [{ role: 'assistant', content: [
    { type: 'thinking', thinking: 'first', signature: 'sig-first' },
    { type: 'thinking', thinking: 'second', signature: 'sig-second' },
  ] }];
  assert.deepEqual(buildClaudeMessages(accumulator.snapshot(), false), expected);
  assert.deepEqual(buildClaudeMessages([immutable], false), expected);
});

test('Claude request projects foreign/plain reasoning away while canonical history remains unchanged', () => {
  const message = makeUIMessage('assistant', [
    thought('plain thought', null),
    thought('Responses thought', { reasoning_id: 'rs-foreign', encrypted_content: 'foreign-cipher' }),
    thought('empty signature', { signature: ' ' }),
    thought('native', { signature: 'sig-native', claude_thinking_block_index: 3, reasoning_level: 'high' }),
    { type: 'text', text: 'answer', metadata: null },
  ]);
  const history = JSON.stringify(message);
  assert.deepEqual(buildClaudeMessages([message], false), [{ role: 'assistant', content: [
    { type: 'thinking', thinking: 'native', signature: 'sig-native' },
    { type: 'text', text: 'answer' },
  ] }]);
  assert.equal(JSON.stringify(message), history);
});

test('Responses request accepts original nonblank ID without requiring encrypted payload', () => {
  const message = makeUIMessage('assistant', [
    thought('plain', null),
    thought('Claude', { signature: 'sig-native' }),
    thought('blank ID', { reasoning_id: ' ' }),
    thought('native', { reasoning_id: 'rs-native' }),
  ]);
  const history = JSON.stringify(message);
  assert.deepEqual(buildResponsesInput([message]), [
    { type: 'reasoning', id: 'rs-native', summary: [{ type: 'summary_text', text: 'native' }] },
  ]);
  assert.equal(JSON.stringify(message), history);
});

test('coalesce preserves opaque empty blocks but discards useless empty signatures', () => {
  const parts = [thought('', { signature: 'sig' }), thought('', { signature: '' }),
    thought('', { reasoning_id: 'rs-empty', encrypted_content: 'cipher' })];
  assert.deepEqual(coalesceStreamParts(parts), [parts[0], parts[2]]);
});

test('Responses distinct reasoning item IDs stay separate during stream merge', () => {
  const accumulator = new MessageStreamAccumulator([makeUIMessage('assistant', [])]);
  for (const id of ['rs-one', 'rs-two']) accumulator.append({ id: '', model: '', usage: null,
    choices: [{ index: 0, finishReason: null, message: null,
      delta: makeUIMessage('assistant', [thought(id, { reasoning_id: id })]) }],
  });
  assert.deepEqual(buildResponsesInput(accumulator.snapshot()), [
    { type: 'reasoning', id: 'rs-one', summary: [{ type: 'summary_text', text: 'rs-one' }] },
    { type: 'reasoning', id: 'rs-two', summary: [{ type: 'summary_text', text: 'rs-two' }] },
  ]);
});

test('Claude tool continuation reusing block index zero cannot mutate a finished earlier thought', () => {
  const earlier = { ...thought('earlier', { signature: 'sig-earlier', claude_thinking_block_index: 0 }),
    finishedAt: '2026-10-02T00:00:01Z' };
  const seed = makeUIMessage('assistant', [earlier]);
  const accumulator = new MessageStreamAccumulator([seed]);
  const events = [
    ['content_block_start', { index: 0, content_block: { type: 'thinking', thinking: '', signature: '' } }],
    ['content_block_delta', { index: 0, delta: { type: 'thinking_delta', thinking: 'next' } }],
    ['content_block_delta', { index: 0, delta: { type: 'signature_delta', signature: 'sig-next' } }],
  ] as const;
  for (const [type, event] of events) {
    const chunk = parseClaudeStreamEvent(type, undefined, JSON.stringify(event)).chunk;
    if (chunk !== null) accumulator.append(chunk);
  }
  assert.deepEqual(accumulator.snapshot()[0].parts[0], earlier);
  assert.deepEqual(buildClaudeMessages(accumulator.snapshot(), false), [{ role: 'assistant', content: [
    { type: 'thinking', thinking: 'earlier', signature: 'sig-earlier' },
    { type: 'thinking', thinking: 'next', signature: 'sig-next' },
  ] }]);
});
