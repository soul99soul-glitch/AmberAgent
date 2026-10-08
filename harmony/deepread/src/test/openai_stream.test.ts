import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { parseOpenAiStreamEvent, createOpenAiTextStream } from '../main/ets/platform/openai_stream.ts';
import type { SseEvent } from '../main/ets/platform/sse_assembler.ts';

const evt = (data: string, done = false): SseEvent => ({ data: data, done: done });
const enc = (s: string): ArrayBuffer => new TextEncoder().encode(s).buffer as ArrayBuffer;

test('parse: finish_reason stop marks done', () => {
  const d = parseOpenAiStreamEvent(evt('{"choices":[{"delta":{},"finish_reason":"stop"}]}'));
  assert.equal(d.done, true);
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

// ===== tool_calls 增量解析回归(DepthRead agent loop 组装依赖) =====

test('parseOpenAiStreamEvent: tool_calls delta surfaces index/id/name/args', () => {
  const evt: SseEvent = {
    data: JSON.stringify({
      choices: [{ delta: { tool_calls: [
        { index: 0, id: 'call_a', function: { name: 'search_web', arguments: '{"q":"' } },
        { index: 1, function: { arguments: '{"path":"x' } },
      ] } }],
    }),
    done: false,
  };
  const d = parseOpenAiStreamEvent(evt);
  assert.equal(d.deltaText, '');
  assert.equal(d.toolCalls.length, 2);
  assert.equal(d.toolCalls[0].index, 0);
  assert.equal(d.toolCalls[0].id, 'call_a');
  assert.equal(d.toolCalls[0].name, 'search_web');
  assert.equal(d.toolCalls[0].args, '{"q":"');
  assert.equal(d.toolCalls[1].index, 1);
  assert.equal(d.toolCalls[1].id, undefined);
  assert.equal(d.done, false);
});

test('parseOpenAiStreamEvent: tool_calls without index falls back to array position', () => {
  const evt: SseEvent = {
    data: JSON.stringify({
      choices: [{ delta: { tool_calls: [
        { function: { name: 'write_file' } },
      ] } }],
    }),
    done: false,
  };
  const d = parseOpenAiStreamEvent(evt);
  assert.equal(d.toolCalls.length, 1);
  assert.equal(d.toolCalls[0].index, 0);
  assert.equal(d.toolCalls[0].name, 'write_file');
});
