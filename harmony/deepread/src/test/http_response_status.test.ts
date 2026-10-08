import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { httpResponseStatusFromHeaders, isEventStreamResponse } from '../main/ets/platform/http_response_status.ts';

test('headersReceive exposes status as an HTTP status-line key', () => {
  assert.equal(httpResponseStatusFromHeaders({ 'HTTP/1.1 200 OK': '', 'content-type': 'text/event-stream' }), 200);
  assert.equal(httpResponseStatusFromHeaders({ 'HTTP/2 503': '', 'content-type': 'application/json' }), 503);
});

test('redirect and interim header blocks do not depend on key order', () => {
  assert.equal(httpResponseStatusFromHeaders({ 'HTTP/1.1 100 Continue': '' }), null);
  assert.equal(httpResponseStatusFromHeaders({ 'HTTP/1.1 200 OK': '', 'HTTP/1.1 302 Found': '' }), 200);
  assert.equal(httpResponseStatusFromHeaders({ 'HTTP/1.1 302 Found': '', 'HTTP/1.1 200 OK': '' }), 200);
  assert.equal(httpResponseStatusFromHeaders({ 'HTTP/1.1 100 Continue': '', 'HTTP/1.1 401 Unauthorized': '' }), 401);
});

test('absent or malformed status lines leave completion status authoritative', () => {
  assert.equal(httpResponseStatusFromHeaders({ 'content-type': 'text/event-stream' }), null);
  assert.equal(httpResponseStatusFromHeaders({ 'HTTP/1.1 2000 nope': '', status: '200' }), null);
  assert.equal(httpResponseStatusFromHeaders({ 'HTTP/1.1 200 OK': '', 'HTTP/1.1 401 Unauthorized': '' }), null);
});

test('only an explicit SSE MIME permits streaming before the status callback', () => {
  assert.equal(isEventStreamResponse({ 'content-type': 'text/event-stream; charset=utf-8' }), true);
  assert.equal(isEventStreamResponse({ 'Content-Type': ' Text/Event-Stream ' }), true);
  assert.equal(isEventStreamResponse({ 'content-type': 'application/json' }), false);
  assert.equal(isEventStreamResponse({ 'content-type': 'text/plain' }), false);
  assert.equal(isEventStreamResponse({}), false);
});
