// D-118:MCP 两 transport 测试(fake McpHttpPort 驱动,金样消息逐字对照)
//
// 对照:
//   SseClientTransport.kt(201 行全文)/StreamableHttpClientTransport.kt(369 行全文)
//   金样:二启/三 check/错误消息逐字;baseUrl 三分支;endpoint 绝对/相对解析;
//   'Unexpected content type: $<ct>' 字面 '$';inline SSE data trim concat 无换行 quirk;
//   replay id 改写;terminateSession 405 容忍;startSseSession 405/json 静默。

import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';
import {
  McpSseClientTransport, McpStreamableHttpTransport,
  StreamableHttpError, McpSseOpenError,
} from '../main/ets/chat/mcp_transports.ts';
import type {
  McpHttpPort, McpHttpResponse, McpSseEvent, McpSseStream,
  McpPostStreamResponse, McpSseLineStream,
} from '../main/ets/chat/mcp_transports.ts';

const tick = (): Promise<void> => new Promise((resolve): void => {
  setTimeout(resolve, 0);
});

// ===== fakes =====

class FakeSseStream implements McpSseStream {
  private queue: Array<McpSseEvent | null> = [];
  private waiters: Array<(e: McpSseEvent | null) => void> = [];
  cancelled: boolean = false;

  push(e: McpSseEvent): void {
    const w: ((e: McpSseEvent | null) => void) | undefined = this.waiters.shift();
    if (w !== undefined) w(e); else this.queue.push(e);
  }

  end(): void {
    const w: ((e: McpSseEvent | null) => void) | undefined = this.waiters.shift();
    if (w !== undefined) w(null); else this.queue.push(null);
  }

  next(): Promise<McpSseEvent | null> {
    const e: McpSseEvent | null | undefined = this.queue.shift();
    if (e !== undefined) return Promise.resolve(e);
    return new Promise((resolve): void => {
      this.waiters.push(resolve);
    });
  }

  cancel(): Promise<void> {
    this.cancelled = true;
    for (const w of this.waiters) w(null);
    this.waiters = [];
    return Promise.resolve();
  }
}

class FakeLineStream implements McpSseLineStream {
  private readonly lines: string[];
  private idx: number = 0;
  cancelled: boolean = false;

  constructor(lines: string[]) {
    this.lines = lines;
  }

  readLine(): Promise<string | null> {
    if (this.idx >= this.lines.length) return Promise.resolve(null);
    return Promise.resolve(this.lines[this.idx++]);
  }

  cancel(): Promise<void> {
    this.cancelled = true;
    return Promise.resolve();
  }
}

interface RecordedCall {
  method: string;
  url: string;
  headers: Array<[string, string]>;
  body: string | null;
}

class FakePort implements McpHttpPort {
  calls: RecordedCall[] = [];
  openCalls: Array<{ url: string; headers: Array<[string, string]> }> = [];
  postStreamCalls: RecordedCall[] = [];
  requestQueue: McpHttpResponse[] = [];
  postStreamQueue: McpPostStreamResponse[] = [];
  streams: FakeSseStream[] = [];
  openError: Error | null = null;

  request(method: string, url: string, headers: Array<[string, string]>,
    body: string | null): Promise<McpHttpResponse> {
    this.calls.push({ method, url, headers, body });
    const r: McpHttpResponse | undefined = this.requestQueue.shift();
    if (r === undefined) throw new Error('no scripted response');
    return Promise.resolve(r);
  }

  openSse(url: string, headers: Array<[string, string]>): Promise<McpSseStream> {
    this.openCalls.push({ url, headers });
    if (this.openError !== null) return Promise.reject(this.openError);
    const s: FakeSseStream = new FakeSseStream();
    this.streams.push(s);
    return Promise.resolve(s);
  }

  postStream(url: string, headers: Array<[string, string]>,
    body: string): Promise<McpPostStreamResponse> {
    this.postStreamCalls.push({ method: 'POST', url, headers, body });
    const r: McpPostStreamResponse | undefined = this.postStreamQueue.shift();
    if (r === undefined) throw new Error('no scripted postStream response');
    return Promise.resolve(r);
  }
}

const resp = (status: number, bodyText: string,
  headers: Array<[string, string]> = [], contentType: string | null = null,
  statusDescription: string = ''): McpHttpResponse =>
  ({ status, statusDescription, headers, contentType, bodyText });

const postResp = (status: number, bodyText: string,
  headers: Array<[string, string]> = [], contentType: string | null = null,
  lines: McpSseLineStream | null = null,
  statusDescription: string = ''): McpPostStreamResponse =>
  ({ status, statusDescription, headers, contentType, bodyText, lines });

// SSE transport:start 并推 endpoint
const startSse = async (port: FakePort, url: string,
  endpointData: string): Promise<McpSseClientTransport> => {
  const t: McpSseClientTransport = new McpSseClientTransport(port, url, [['X-Key', 'k1']]);
  const p: Promise<void> = t.start();
  await tick();
  port.streams[0].push({ event: 'endpoint', data: endpointData, id: null });
  await p;
  return t;
};

// ===== SseClientTransport =====

test('sse: start 二启 — 逐字消息(:66-68)', async () => {
  const port: FakePort = new FakePort();
  const t: McpSseClientTransport = await startSse(port, 'https://example.com/sse', '/ep');
  await assert.rejects(t.start(),
    (e: Error): boolean => e.message === 'SSEClientTransport already started! ' +
      'If using Client class, note that connect() calls start() automatically.');
});

test('sse: endpoint 相对解析 — baseUrl 三分支(:54-63,:160-176)', async () => {
  // path 无 '/' 前缀目录:lastIndexOf('/')=0 → origin
  let port: FakePort = new FakePort();
  await startSse(port, 'https://example.com/sse', 'messages');
  assert.equal(port.openCalls.length, 1);
  // 深层 path → origin + dir
  port = new FakePort();
  let t: McpSseClientTransport = await startSse(port, 'https://example.com/api/sse', 'messages');
  port.requestQueue.push(resp(200, ''));
  await t.send({ jsonrpc: '2.0', method: 'ping' });
  assert.equal(port.calls[0].url, 'https://example.com/api/messages');
  // path 以 '/' 结尾 → 去尾 '/'
  port = new FakePort();
  t = await startSse(port, 'https://example.com/sse/', 'messages');
  port.requestQueue.push(resp(200, ''));
  await t.send({ jsonrpc: '2.0', method: 'ping' });
  assert.equal(port.calls[0].url, 'https://example.com/sse/messages');
  // 绝对路径 → origin + data(:162-164)
  port = new FakePort();
  t = await startSse(port, 'https://example.com/sse', '/mcp/messages?id=1');
  port.requestQueue.push(resp(200, ''));
  await t.send({ jsonrpc: '2.0', method: 'ping' });
  assert.equal(port.calls[0].url, 'https://example.com/mcp/messages?id=1');
});

test('sse: send 金样 — POST endpoint + Content-Type json + config 头(:101-106)', async () => {
  const port: FakePort = new FakePort();
  const t: McpSseClientTransport = await startSse(port, 'https://example.com/sse', '/ep');
  port.requestQueue.push(resp(200, ''));
  const msg: JsonObject = { id: 'a', method: 'ping', jsonrpc: '2.0' };
  await t.send(msg);
  const call: RecordedCall = port.calls[0];
  assert.equal(call.method, 'POST');
  assert.deepEqual(call.headers, [['X-Key', 'k1'], ['Content-Type', 'application/json']]);
  assert.equal(call.body, JSON.stringify(msg));
});

test('sse: send 非 2xx — 逐字错误(:113-115)+ onError', async () => {
  const port: FakePort = new FakePort();
  const t: McpSseClientTransport = await startSse(port, 'https://example.com/sse', '/ep');
  const errors: string[] = [];
  t.onError((e: Error): void => {
    errors.push(e.message);
  });
  port.requestQueue.push(resp(500, 'boom'));
  await assert.rejects(t.send({ jsonrpc: '2.0', method: 'ping' }),
    (e: Error): boolean => e.message === 'Error POSTing to endpoint (HTTP 500): boom');
  assert.deepEqual(errors, ['Error POSTing to endpoint (HTTP 500): boom']);
});

test('sse: send 未初始化 check(:97)', async () => {
  const t: McpSseClientTransport = new McpSseClientTransport(new FakePort(), 'https://x/sse', []);
  await assert.rejects(t.send({ jsonrpc: '2.0' }),
    (e: Error): boolean => e.message === 'SseClientTransport is not initialized!');
});

test('sse: message 事件 → onMessage 解码;解析失败仅 onError(:147,:178-185)', async () => {
  const port: FakePort = new FakePort();
  const t: McpSseClientTransport = await startSse(port, 'https://example.com/sse', '/ep');
  const got: JsonObject[] = [];
  const errors: string[] = [];
  t.onMessage((m: JsonObject): void => {
    got.push(m);
  });
  t.onError((e: Error): void => {
    errors.push(e.message);
  });
  port.streams[0].push({ event: null, data: '{"id":"r1","result":{},"jsonrpc":"2.0"}', id: null });
  port.streams[0].push({ event: 'message', data: 'not-json{', id: null });
  await tick();
  await tick();
  assert.equal(got.length, 1);
  assert.equal(got[0]['id'], 'r1');
  assert.equal(errors.length, 1);
});

test('sse: error 事件 → onError 逐字 + 流关闭(:134-139,finally closeResources)', async () => {
  const port: FakePort = new FakePort();
  const t: McpSseClientTransport = await startSse(port, 'https://example.com/sse', '/ep');
  const errors: string[] = [];
  let closed: boolean = false;
  t.onError((e: Error): void => {
    errors.push(e.message);
  });
  t.onClose((): void => {
    closed = true;
  });
  port.streams[0].push({ event: 'error', data: 'upstream dead', id: null });
  await tick();
  await tick();
  assert.ok(errors.includes('SSE error: upstream dead'));
  assert.ok(closed);
  assert.ok(port.streams[0].cancelled);
});

test('sse: open 事件 noop(:141-143)', async () => {
  const port: FakePort = new FakePort();
  const t: McpSseClientTransport = await startSse(port, 'https://example.com/sse', '/ep');
  const got: JsonObject[] = [];
  t.onMessage((m: JsonObject): void => {
    got.push(m);
  });
  port.streams[0].push({ event: 'open', data: '', id: null });
  await tick();
  assert.equal(got.length, 0);
});

test('sse: openSse 失败 → start 抛原错,initialized 复位(:88-92)', async () => {
  const port: FakePort = new FakePort();
  port.openError = new Error('conn refused');
  const t: McpSseClientTransport = new McpSseClientTransport(port, 'https://x/sse', []);
  await assert.rejects(t.start(), (e: Error): boolean => e.message === 'conn refused');
  await assert.rejects(t.send({ jsonrpc: '2.0' }),
    (e: Error): boolean => e.message === 'SseClientTransport is not initialized!');
});

test('sse: close → onClose;未初始化 close → check(:124-127)', async () => {
  const port: FakePort = new FakePort();
  const t: McpSseClientTransport = await startSse(port, 'https://example.com/sse', '/ep');
  let closed: number = 0;
  t.onClose((): void => {
    closed++;
  });
  await t.close();
  assert.equal(closed, 1);
  const t2: McpSseClientTransport = new McpSseClientTransport(new FakePort(), 'https://x/sse', []);
  await assert.rejects(t2.close(),
    (e: Error): boolean => e.message === 'SseClientTransport is not initialized!');
});

// ===== StreamableHttpClientTransport =====

test('sh: start 二启 — 逐字(:84)', async () => {
  const t: McpStreamableHttpTransport = new McpStreamableHttpTransport(
    new FakePort(), 'https://x/mcp', []);
  await t.start();
  await assert.rejects(t.start(),
    (e: Error): boolean => e.message === 'StreamableHttpClientTransport already started!');
});

test('sh: send 未 start check(:105)', async () => {
  const t: McpStreamableHttpTransport = new McpStreamableHttpTransport(
    new FakePort(), 'https://x/mcp', []);
  await assert.rejects(t.send({ jsonrpc: '2.0' }),
    (e: Error): boolean => e.message === 'Transport is not started');
});

test('sh: send 金样 — Accept/Content-Type/config 头 + sessionId 捕获(:119-127)', async () => {
  const port: FakePort = new FakePort();
  port.postStreamQueue.push(postResp(200, '{"id":"a","result":{},"jsonrpc":"2.0"}',
    [['mcp-session-id', 'sess-9']], 'application/json'));
  const t: McpStreamableHttpTransport = new McpStreamableHttpTransport(
    port, 'https://x/mcp', [['Authorization', 'Bearer z']]);
  await t.start();
  const msg: JsonObject = { id: 'a', method: 'ping', jsonrpc: '2.0' };
  await t.send(msg);
  const call: RecordedCall = port.postStreamCalls[0];
  assert.equal(call.url, 'https://x/mcp');
  assert.deepEqual(call.headers, [
    ['Accept', 'application/json, text/event-stream'],
    ['Content-Type', 'application/json'],
    ['Authorization', 'Bearer z'],
  ]);
  assert.equal(call.body, JSON.stringify(msg));
  assert.equal(t.sessionId, 'sess-9');
  // 后续请求带 mcp-session-id(:273-278)
  port.postStreamQueue.push(postResp(202, ''));
  await t.send({ method: 'notifications/exit', jsonrpc: '2.0' });
  assert.deepEqual(port.postStreamCalls[1].headers[0], ['mcp-session-id', 'sess-9']);
});

test('sh: JSON 响应 → onMessage;解码失败 → onError + 抛(:152-159)', async () => {
  const port: FakePort = new FakePort();
  port.postStreamQueue.push(postResp(200, '{"id":"a","result":{"x":1},"jsonrpc":"2.0"}',
    [], 'application/json; charset=utf-8'));
  port.postStreamQueue.push(postResp(200, '{broken', [], 'application/json'));
  const t: McpStreamableHttpTransport = new McpStreamableHttpTransport(
    port, 'https://x/mcp', []);
  await t.start();
  const got: JsonObject[] = [];
  const errors: string[] = [];
  t.onMessage((m: JsonObject): void => {
    got.push(m);
  });
  t.onError((e: Error): void => {
    errors.push(e.message);
  });
  await t.send({ id: 'a', method: 'ping', jsonrpc: '2.0' });
  assert.equal(got.length, 1);
  assert.deepEqual(got[0]['result'], { x: 1 });
  await assert.rejects(t.send({ id: 'b', method: 'ping', jsonrpc: '2.0' }));
  assert.equal(errors.length, 1);
});

test('sh: 非 2xx → StreamableHttpError(status, body)(:145-149)', async () => {
  const port: FakePort = new FakePort();
  port.postStreamQueue.push(postResp(500, 'server blew up'));
  const t: McpStreamableHttpTransport = new McpStreamableHttpTransport(
    port, 'https://x/mcp', []);
  await t.start();
  const errors: Error[] = [];
  t.onError((e: Error): void => {
    errors.push(e);
  });
  await assert.rejects(t.send({ id: 'a', method: 'ping', jsonrpc: '2.0' }),
    (e: Error): boolean => e instanceof StreamableHttpError &&
      (e as StreamableHttpError).code === 500 &&
      e.message === 'Streamable HTTP error: server blew up');
  assert.equal(errors.length, 1);
});

test('sh: 202 + notifications/initialized → 异步起 SSE(:129-143)', async () => {
  const port: FakePort = new FakePort();
  port.postStreamQueue.push(postResp(202, ''));
  const t: McpStreamableHttpTransport = new McpStreamableHttpTransport(
    port, 'https://x/mcp', [['X-K', 'v']]);
  await t.start();
  await t.send({ method: 'notifications/initialized', jsonrpc: '2.0' });
  await tick();
  await tick();
  assert.equal(port.openCalls.length, 1);
  // GET SSE 头:Accept application/json + config(:241-244)
  assert.deepEqual(port.openCalls[0].headers, [['Accept', 'application/json'], ['X-K', 'v']]);
});

test('sh: 202 其他通知 → 不起 SSE', async () => {
  const port: FakePort = new FakePort();
  port.postStreamQueue.push(postResp(202, ''));
  const t: McpStreamableHttpTransport = new McpStreamableHttpTransport(
    port, 'https://x/mcp', []);
  await t.start();
  await t.send({ method: 'notifications/cancelled', jsonrpc: '2.0' });
  await tick();
  assert.equal(port.openCalls.length, 0);
});

test("sh: else content-type — 'Unexpected content type: $<ct>' 字面 $(:171-172)", async () => {
  const port: FakePort = new FakePort();
  port.postStreamQueue.push(postResp(200, 'hello', [], 'text/plain'));
  const t: McpStreamableHttpTransport = new McpStreamableHttpTransport(
    port, 'https://x/mcp', []);
  await t.start();
  await assert.rejects(t.send({ id: 'a', method: 'ping', jsonrpc: '2.0' }),
    (e: Error): boolean => e instanceof StreamableHttpError &&
      (e as StreamableHttpError).code === -1 &&
      e.message === 'Streamable HTTP error: Unexpected content type: $text/plain');
});

test('sh: null content-type + 空白体 → 静默 return(:168-169)', async () => {
  const port: FakePort = new FakePort();
  port.postStreamQueue.push(postResp(200, '   ', [], null));
  const t: McpStreamableHttpTransport = new McpStreamableHttpTransport(
    port, 'https://x/mcp', []);
  await t.start();
  await t.send({ id: 'a', method: 'ping', jsonrpc: '2.0' });
});

test('sh: inline SSE — multiline data forms one JSON payload', async () => {
  const port: FakePort = new FakePort();
  port.postStreamQueue.push(postResp(200, '', [], 'text/event-stream',
    new FakeLineStream([
      'data: {"id":"a",',
      'data: "result":{},"jsonrpc":"2.0"}',
      '',
    ])));
  const t: McpStreamableHttpTransport = new McpStreamableHttpTransport(
    port, 'https://x/mcp', []);
  await t.start();
  const got: JsonObject[] = [];
  t.onMessage((m: JsonObject): void => {
    got.push(m);
  });
  await t.send({ id: 'a', method: 'ping', jsonrpc: '2.0' });
  assert.equal(got.length, 1);
  assert.deepEqual(got[0]['result'], {});
});

test('sh: inline SSE — unrelated response retains its original ID', async () => {
  const port: FakePort = new FakePort();
  port.postStreamQueue.push(postResp(200, '', [], 'text/event-stream',
    new FakeLineStream([
      'data: {"id":"server-side","result":{"ok":true},"jsonrpc":"2.0"}',
      '',
    ])));
  const t: McpStreamableHttpTransport = new McpStreamableHttpTransport(
    port, 'https://x/mcp', []);
  await t.start();
  const got: JsonObject[] = [];
  t.onMessage((m: JsonObject): void => {
    got.push(m);
  });
  await t.send({ id: 'req-1', method: 'tools/list', jsonrpc: '2.0' });
  assert.equal(got[0]['id'], 'server-side');
});

test('sh: inline SSE — notifications and unrelated responses cannot impersonate matching response', async () => {
  const port = new FakePort();
  port.postStreamQueue.push(postResp(200, '', [], 'text/event-stream', new FakeLineStream([
    'data: {"jsonrpc":"2.0","method":"notifications/tools/list_changed"}', '',
    'data: {"jsonrpc":"2.0","id":"other","result":{"tools":[{"name":"wrong"}]}}', '',
    'data: {"jsonrpc":"2.0","id":"req-1","result":{"tools":[{"name":"right"}]}}', '',
  ])));
  const transport = new McpStreamableHttpTransport(port, 'https://x/mcp', []);
  const got: JsonObject[] = [];
  transport.onMessage(message => got.push(message));
  await transport.start();
  await transport.send({ id: 'req-1', method: 'tools/list', jsonrpc: '2.0' });
  assert.deepEqual(got.map(message => message.id ?? message.method),
    ['notifications/tools/list_changed', 'other', 'req-1']);
});

test('sh: inline SSE — CRLF separator and complete frame at EOF both dispatch', async () => {
  const port = new FakePort();
  port.postStreamQueue.push(postResp(200, '', [], 'text/event-stream', new FakeLineStream([
    'data: {"jsonrpc":"2.0","method":"notifications/tools/list_changed"}\r', '\r',
    'event: message\r', 'data: {"jsonrpc":"2.0","id":"req-1","result":{"tools":[]}}\r',
  ])));
  const transport = new McpStreamableHttpTransport(port, 'https://x/mcp', []);
  const got: JsonObject[] = [];
  transport.onMessage(message => got.push(message));
  await transport.start();
  await transport.send({ id: 'req-1', method: 'tools/list', jsonrpc: '2.0' });
  assert.deepEqual(got.map(message => message.id ?? message.method),
    ['notifications/tools/list_changed', 'req-1']);
});

test('sh: inline SSE 解码失败 → onError + 抛(:344-348)', async () => {
  const port: FakePort = new FakePort();
  port.postStreamQueue.push(postResp(200, '', [], 'text/event-stream',
    new FakeLineStream(['data: garbage{', ''])));
  const t: McpStreamableHttpTransport = new McpStreamableHttpTransport(
    port, 'https://x/mcp', []);
  await t.start();
  const errors: string[] = [];
  t.onError((e: Error): void => {
    errors.push(e.message);
  });
  await assert.rejects(t.send({ id: 'a', method: 'ping', jsonrpc: '2.0' }));
  assert.equal(errors.length, 1);
});

test('sh: terminateSession — 无 session 无 DELETE;405 容忍;他错逐字(:201-223)', async () => {
  const port: FakePort = new FakePort();
  const t: McpStreamableHttpTransport = new McpStreamableHttpTransport(
    port, 'https://x/mcp', []);
  await t.start();
  await t.terminateSession(); // sessionId null → 直返(:202)
  assert.equal(port.calls.length, 0);

  t.sessionId = 'sess-1';
  port.requestQueue.push(resp(405, ''));
  await t.terminateSession();
  assert.equal(port.calls[0].method, 'DELETE');
  assert.equal(t.sessionId, null); // 405 也清(:220-221)

  t.sessionId = 'sess-2';
  port.requestQueue.push(resp(500, '', [], null, 'Internal Server Error'));
  await assert.rejects(t.terminateSession(),
    (e: Error): boolean => e instanceof StreamableHttpError &&
      e.message === 'Streamable HTTP error: Failed to terminate session: Internal Server Error');
});

test('sh: close — 未 start 静默;start 后 terminate+onClose(:179-196)', async () => {
  const port: FakePort = new FakePort();
  const t: McpStreamableHttpTransport = new McpStreamableHttpTransport(
    port, 'https://x/mcp', []);
  await t.close(); // 未 start → 静默(:180)
  await t.start();
  t.sessionId = 'sess-x';
  port.requestQueue.push(resp(200, ''));
  let closed: boolean = false;
  t.onClose((): void => {
    closed = true;
  });
  await t.close();
  assert.ok(closed);
  assert.equal(port.calls[0].method, 'DELETE');
  await t.close(); // 幂等
});

test('sh: startSseSession 405 / json content-type → 静默(:247-262)', async () => {
  // 405(openError 先于 send 设置:startSseSession 在 send 内异步触发)
  let port: FakePort = new FakePort();
  port.postStreamQueue.push(postResp(202, ''));
  port.openError = new McpSseOpenError('405', 405, null);
  let t: McpStreamableHttpTransport = new McpStreamableHttpTransport(
    port, 'https://x/mcp', []);
  await t.start();
  const errors1: string[] = [];
  t.onError((e: Error): void => {
    errors1.push(e.message);
  });
  await t.send({ method: 'notifications/initialized', jsonrpc: '2.0' });
  await tick();
  await tick();
  assert.equal(errors1.length, 0);

  // json content-type
  port = new FakePort();
  port.postStreamQueue.push(postResp(202, ''));
  port.openError = new McpSseOpenError('json mode', 200, 'application/json');
  t = new McpStreamableHttpTransport(port, 'https://x/mcp', []);
  await t.start();
  const errors2: string[] = [];
  t.onError((e: Error): void => {
    errors2.push(e.message);
  });
  await t.send({ method: 'notifications/initialized', jsonrpc: '2.0' });
  await tick();
  await tick();
  assert.equal(errors2.length, 0);
});

test('sh: collectSse — message → onMessage;error → StreamableHttpError(null,data)(:280-314)', async () => {
  const port: FakePort = new FakePort();
  port.postStreamQueue.push(postResp(202, ''));
  const t: McpStreamableHttpTransport = new McpStreamableHttpTransport(
    port, 'https://x/mcp', []);
  await t.start();
  const got: JsonObject[] = [];
  const errors: Error[] = [];
  t.onMessage((m: JsonObject): void => {
    got.push(m);
  });
  t.onError((e: Error): void => {
    errors.push(e);
  });
  await t.send({ method: 'notifications/initialized', jsonrpc: '2.0' });
  await tick();
  await tick();
  const stream: FakeSseStream = port.streams[0];
  stream.push({ event: 'message', data: '{"method":"notifications/progress","jsonrpc":"2.0"}', id: 'evt-1' });
  stream.push({ event: 'error', data: 'stream broke', id: null });
  stream.end();
  await tick();
  await tick();
  assert.equal(got.length, 1);
  assert.equal(got[0]['method'], 'notifications/progress');
  assert.equal(errors.length, 1);
  assert.ok(errors[0] instanceof StreamableHttpError);
  assert.equal((errors[0] as StreamableHttpError).code, null);
  assert.equal(errors[0].message, 'Streamable HTTP error: stream broke');
});
