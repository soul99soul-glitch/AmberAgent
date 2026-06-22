import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { SseAssembler, createSseStreamHandler } from '../main/ets/platform/sse_assembler.ts';
import type { SseEvent } from '../main/ets/platform/sse_assembler.ts';

const enc = (s: string): ArrayBuffer => {
  return new TextEncoder().encode(s).buffer as ArrayBuffer;
};

test('single complete event in one chunk', () => {
  const a = new SseAssembler();
  const events = a.feed(enc('data: {"v":1}\n\n'));
  assert.equal(events.length, 1);
  assert.equal(events[0].data, '{"v":1}');
  assert.equal(events[0].done, false);
});

test('[DONE] marker sets done flag', () => {
  const a = new SseAssembler();
  const events = a.feed(enc('data: [DONE]\n\n'));
  assert.equal(events.length, 1);
  assert.equal(events[0].done, true);
  assert.equal(events[0].data, '[DONE]');
});

test('event split across chunks (boundary at blank line)', () => {
  const a = new SseAssembler();
  const r1 = a.feed(enc('data: {"v":1}\n'));
  assert.equal(r1.length, 0, 'no complete event yet');
  const r2 = a.feed(enc('\n'));
  assert.equal(r2.length, 1);
  assert.equal(r2[0].data, '{"v":1}');
});

test('data line split mid-content across chunks', () => {
  const a = new SseAssembler();
  const r1 = a.feed(enc('data: {"v":1,"na'));
  assert.equal(r1.length, 0);
  const r2 = a.feed(enc('me":"foo"}\n\n'));
  assert.equal(r2.length, 1);
  assert.equal(r2[0].data, '{"v":1,"name":"foo"}');
});

test('multiple events in one chunk', () => {
  const a = new SseAssembler();
  const events = a.feed(enc('data: a\n\ndata: b\n\ndata: c\n\n'));
  assert.equal(events.length, 3);
  assert.deepEqual(events.map(e => e.data), ['a', 'b', 'c']);
});

test('multi-line data payload (joined with newline)', () => {
  const a = new SseAssembler();
  const events = a.feed(enc('data: line1\ndata: line2\n\n'));
  assert.equal(events.length, 1);
  assert.equal(events[0].data, 'line1\nline2');
});

test('nested data: prefix stripped', () => {
  const a = new SseAssembler();
  const events = a.feed(enc('data: data: data: {"v":1}\n\n'));
  assert.equal(events.length, 1);
  assert.equal(events[0].data, '{"v":1}');
});

test('comment lines ignored', () => {
  const a = new SseAssembler();
  const events = a.feed(enc(': heartbeat\n\ndata: {"v":1}\n\n'));
  assert.equal(events.length, 1);
  assert.equal(events[0].data, '{"v":1}');
});

test('event/id/retry fields ignored, only data kept', () => {
  const a = new SseAssembler();
  const events = a.feed(enc('event: ping\nid: 42\nretry: 5000\ndata: hello\n\n'));
  assert.equal(events.length, 1);
  assert.equal(events[0].data, 'hello');
});

test('flush handles trailing partial event', () => {
  const a = new SseAssembler();
  a.feed(enc('data: trailing-no-blank-line'));
  const flushed = a.flush();
  assert.equal(flushed.length, 1);
  assert.equal(flushed[0].data, 'trailing-no-blank-line');
});

test('flush returns empty for whitespace-only buffer', () => {
  const a = new SseAssembler();
  a.feed(enc('   \n  '));
  assert.equal(a.flush().length, 0);
});

test('CRLF line endings supported', () => {
  const a = new SseAssembler();
  const events = a.feed(enc('data: hello\r\n\r\n'));
  assert.equal(events.length, 1);
  assert.equal(events[0].data, 'hello');
});

test('createSseStreamHandler emits events then done', () => {
  const received: SseEvent[] = [];
  let doneCalled = false;
  const handler = createSseStreamHandler(
    evt => received.push(evt),
    () => { doneCalled = true; },
  );
  handler.onDataBlock(enc('data: {"a":1}\n\n'), false);
  handler.onDataBlock(enc('data: {"a":2}\n\n'), false);
  handler.onDataBlock(enc('data: [DONE]\n\n'), true);
  assert.equal(received.length, 2);
  assert.equal(received[0].data, '{"a":1}');
  assert.equal(received[1].data, '{"a":2}');
  assert.equal(doneCalled, true);
});

test('createSseStreamHandler handles partial-final-chunk via end flag', () => {
  const received: SseEvent[] = [];
  const handler = createSseStreamHandler(evt => received.push(evt));
  handler.onDataBlock(enc('data: no-trailing-newline'), true);
  assert.equal(received.length, 1);
  assert.equal(received[0].data, 'no-trailing-newline');
});

test('chunk containing only [DONE] without data prefix does not parse as done', () => {
  // SSE 规范只 data: 前缀的内容算 payload;裸 [DONE] 不是有效 done 信号
  const a = new SseAssembler();
  const events = a.feed(enc('[DONE]\n\n'));
  assert.equal(events.length, 0, 'bare [DONE] without data: prefix is ignored');
});
