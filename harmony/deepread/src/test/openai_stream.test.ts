import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { parseOpenAiStreamEvent, createOpenAiTextStream } from '../main/ets/platform/openai_stream.ts';
import type { SseEvent } from '../main/ets/platform/sse_assembler.ts';

const evt = (data: string, done = false): SseEvent => ({ data: data, done: done });
const enc = (s: string): ArrayBuffer => new TextEncoder().encode(s).buffer as ArrayBuffer;

test('parse: content delta extracted', () => {
  const d = parseOpenAiStreamEvent(evt('{"choices":[{"delta":{"content":"Hello"}}]}'));
  assert.equal(d.deltaText, 'Hello');
  assert.equal(d.reasoningText, '');
  assert.equal(d.done, false);
});

test('parse: reasoning_content delta extracted', () => {
  const d = parseOpenAiStreamEvent(evt('{"choices":[{"delta":{"reasoning_content":"think"}}]}'));
  assert.equal(d.deltaText, '');
  assert.equal(d.reasoningText, 'think');
});

test('parse: empty delta object yields empty text', () => {
  const d = parseOpenAiStreamEvent(evt('{"choices":[{"delta":{}}]}'));
  assert.equal(d.deltaText, '');
  assert.equal(d.done, false);
});

test('parse: finish_reason stop marks done', () => {
  const d = parseOpenAiStreamEvent(evt('{"choices":[{"delta":{},"finish_reason":"stop"}]}'));
  assert.equal(d.done, true);
});

test('parse: finish_reason null is not done', () => {
  const d = parseOpenAiStreamEvent(evt('{"choices":[{"delta":{"content":"x"},"finish_reason":null}]}'));
  assert.equal(d.deltaText, 'x');
  assert.equal(d.done, false);
});

test('parse: [DONE] data marks done', () => {
  const d = parseOpenAiStreamEvent(evt('[DONE]'));
  assert.equal(d.done, true);
});

test('parse: assembler done flag propagates', () => {
  const d = parseOpenAiStreamEvent(evt('[DONE]', true));
  assert.equal(d.done, true);
});

test('parse: no choices → empty, no throw', () => {
  const d = parseOpenAiStreamEvent(evt('{"id":"x"}'));
  assert.equal(d.deltaText, '');
  assert.equal(d.done, false);
});

test('parse: malformed JSON → empty, no throw', () => {
  const d = parseOpenAiStreamEvent(evt('{not json'));
  assert.equal(d.deltaText, '');
  assert.equal(d.done, false);
});

test('parse: empty data → empty', () => {
  const d = parseOpenAiStreamEvent(evt(''));
  assert.equal(d.deltaText, '');
  assert.equal(d.done, false);
});

test('sink: accumulates content across chunks and signals done', () => {
  const texts: string[] = [];
  let done = false;
  const sink = createOpenAiTextStream(
    (t) => { texts.push(t); },
    () => { done = true; },
  );
  sink.feed(enc('data: {"choices":[{"delta":{"content":"He"}}]}\n\n'));
  sink.feed(enc('data: {"choices":[{"delta":{"content":"llo"}}]}\n\n'));
  sink.feed(enc('data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\n'));
  sink.feed(enc('data: [DONE]\n\n'));
  assert.deepEqual(texts, ['He', 'llo']);
  assert.equal(done, true);
});

test('sink: split SSE event across ArrayBuffer chunks', () => {
  const texts: string[] = [];
  const sink = createOpenAiTextStream((t) => { texts.push(t); });
  sink.feed(enc('data: {"choices":[{"delta":{"con'));
  sink.feed(enc('tent":"world"}}]}\n\n'));
  assert.deepEqual(texts, ['world']);
});

test('sink: flush handles trailing event without blank line', () => {
  const texts: string[] = [];
  const sink = createOpenAiTextStream((t) => { texts.push(t); });
  sink.feed(enc('data: {"choices":[{"delta":{"content":"tail"}}]}'));
  sink.flush();
  assert.deepEqual(texts, ['tail']);
});

test('sink: no onDelta for empty deltas, but reasoning emitted', () => {
  const texts: string[] = [];
  const reasons: string[] = [];
  const sink = createOpenAiTextStream((t, r) => {
    if (t.length > 0) texts.push(t);
    if (r.length > 0) reasons.push(r);
  });
  sink.feed(enc('data: {"choices":[{"delta":{}}]}\n\n'));
  sink.feed(enc('data: {"choices":[{"delta":{"reasoning_content":"r1"}}]}\n\n'));
  assert.deepEqual(texts, []);
  assert.deepEqual(reasons, ['r1']);
});

test('sink: stops emitting after done even if more chunks fed', () => {
  const texts: string[] = [];
  let doneCount = 0;
  const sink = createOpenAiTextStream(
    (t) => { texts.push(t); },
    () => { doneCount++; },
  );
  sink.feed(enc('data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\n'));
  sink.feed(enc('data: {"choices":[{"delta":{"content":"late"}}]}\n\n'));
  sink.feed(enc('data: [DONE]\n\n'));
  assert.deepEqual(texts, []);
  assert.equal(doneCount, 1, 'onDone fires exactly once');
});
