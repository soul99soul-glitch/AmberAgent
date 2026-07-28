// MCP JSON-RPC client 核规格测试(D-117)
//
// Android 基准: io.modelcontextprotocol:kotlin-sdk 0.8.4
//   shared/Protocol.kt + client/Client.kt + types/jsonRpc.kt/McpException.kt/
//   methods.kt/tools.kt(行为忠实;锚点见 mcp_protocol.ts 头注)

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  McpClient, McpError, MCP_LATEST_PROTOCOL_VERSION,
} from '../main/ets/chat/mcp_protocol.ts';
import type { McpTransport } from '../main/ets/chat/mcp_protocol.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';

// ===== FakeTransport:脚本化入站/录制出站 =====

class FakeTransport implements McpTransport {
  sent: JsonObject[] = [];
  started: boolean = false;
  closed: boolean = false;
  sendError: Error | null = null;
  sendHang: boolean = false; // send 永不返回(超时测试)
  private closeBlock: () => void = (): void => {};
  private messageBlock: (msg: JsonObject) => void = (): void => {};

  start(): Promise<void> {
    this.started = true;
    return Promise.resolve();
  }

  send(message: JsonObject): Promise<void> {
    this.sent.push(message); // 帧已受理再模拟挂起/失败(与真实传输一致)
    if (this.sendHang) return new Promise((): void => {});
    if (this.sendError !== null) return Promise.reject(this.sendError);
    return Promise.resolve();
  }

  close(): Promise<void> {
    if (this.closed) return Promise.resolve();
    this.closed = true;
    this.closeBlock();
    return Promise.resolve();
  }

  onClose(block: () => void): void {
    this.closeBlock = block;
  }

  onError(_block: (e: Error) => void): void {}

  onMessage(block: (msg: JsonObject) => void): void {
    this.messageBlock = block;
  }

  // 测试注入:模拟入站消息
  emit(msg: JsonObject): void {
    this.messageBlock(msg);
  }

  // 对最近一条请求回包(id 取自 sent 末条)
  respondLast(result: JsonObject): void {
    const last = this.sent[this.sent.length - 1];
    this.emit({ jsonrpc: '2.0', id: last['id'] as string, result } as JsonObject);
  }

  lastSent(): JsonObject {
    return this.sent[this.sent.length - 1];
  }
}

const INIT_RESULT: JsonObject = {
  protocolVersion: MCP_LATEST_PROTOCOL_VERSION,
  capabilities: { tools: {} },
  serverInfo: { name: 'srv', version: '2.0' },
} as JsonObject;

const connectWithTools = async (t: FakeTransport): Promise<McpClient> => {
  const client = new McpClient({ name: 'amber', version: '1.0' });
  const p = client.connect(t);
  await new Promise((r): void => { setTimeout(r, 0); }); // start 后 initialize 发出
  t.respondLast(INIT_RESULT);
  await p;
  return client;
};

// ===== initialize 握手(Client.kt:172-211) =====

test('initialize 握手:请求帧逐字(protocolVersion 2025-06-18/capabilities {}/clientInfo/id 32hex 无连字符)+ initialized 通知', async () => {
  const t = new FakeTransport();
  const client = new McpClient({ name: 'amber', version: '1.0' });
  const p = client.connect(t);
  // start 后 initialize 请求已发出(同步微任务推进)
  await new Promise((r): void => { setTimeout(r, 0); });
  assert.equal(t.sent.length, 1);
  const req = t.sent[0];
  assert.equal(req['jsonrpc'], '2.0');
  assert.equal(req['method'], 'initialize');
  assert.match(req['id'] as string, /^[0-9a-f]{32}$/);
  const params = req['params'] as JsonObject;
  assert.equal(params['protocolVersion'], '2025-06-18');
  assert.deepEqual(params['capabilities'], {});
  assert.deepEqual(params['clientInfo'], { name: 'amber', version: '1.0' });

  t.respondLast(INIT_RESULT);
  await p;
  // initialized 通知:无 params 键(explicitNulls=false)
  assert.equal(t.sent.length, 2);
  const note = t.sent[1];
  assert.equal(note['method'], 'notifications/initialized');
  assert.equal(note['jsonrpc'], '2.0');
  assert.ok(!('params' in note));
  assert.ok(!('id' in note));
  // server 信息沉淀
  assert.deepEqual(client.serverCapabilities, { tools: {} });
  assert.deepEqual(client.serverVersion, { name: 'srv', version: '2.0' });
  assert.equal(client.hasTransport, true);
});

test('initialize:不支持的协议版本 → 抛 "Server\'s protocol version is not supported: X" + close', async () => {
  const t = new FakeTransport();
  const client = new McpClient({ name: 'a', version: '1.0' });
  const p = client.connect(t);
  await new Promise((r): void => { setTimeout(r, 0); });
  t.respondLast({ protocolVersion: '1900-01-01', capabilities: {}, serverInfo: { name: 's', version: '1' } });
  await assert.rejects(p, /Server's protocol version is not supported: 1900-01-01/);
  assert.equal(t.closed, true);
});

test('initialize:服务器低版本但在 SUPPORTED 内 → 接受(2024-11-05)', async () => {
  const t = new FakeTransport();
  const client = new McpClient({ name: 'a', version: '1.0' });
  const p = client.connect(t);
  await new Promise((r): void => { setTimeout(r, 0); });
  t.respondLast({ protocolVersion: '2024-11-05', capabilities: { tools: {} }, serverInfo: { name: 's', version: '1' } });
  await p;
  assert.equal(client.hasTransport, true);
});

test('connect:transport.start 抛错 → 原样传播(super.connect 在握手 try 之外,SDK 逐字)', async () => {
  const t = new FakeTransport();
  t.start = (): Promise<void> => Promise.reject(new Error('boom'));
  const client = new McpClient({ name: 'a', version: '1.0' });
  await assert.rejects(client.connect(t), (e: Error): boolean => {
    assert.equal(e.message, 'boom'); // 原样,未经 'Error connecting to transport:' 映射
    return true;
  });
});

test('connect:initialize 请求传输失败 → 映射 "Error connecting to transport: <msg>"', async () => {
  const t = new FakeTransport();
  t.sendError = new Error('conn reset'); // start 不 send;initialize 帧必走此径
  const client = new McpClient({ name: 'a', version: '1.0' });
  const p = client.connect(t);
  await assert.rejects(p, /Error connecting to transport: conn reset/);
  assert.equal(t.closed, true);
});

// ===== tools/list(Client.kt:484-490) =====

test('listTools:params 省略;结果 ToolSchema.toSchema(properties ?: {}, required 原样)', async () => {
  const t = new FakeTransport();
  const client = await connectWithTools(t);
  const p = client.listTools();
  await new Promise((r): void => { setTimeout(r, 0); });
  const req = t.lastSent();
  assert.equal(req['method'], 'tools/list');
  assert.ok(!('params' in req));
  t.respondLast({
    tools: [
      { name: 'read', description: 'd', inputSchema: { type: 'object', properties: { p: { type: 'string' } }, required: ['p'] } },
      { name: 'write' }, // description/inputSchema 缺
    ],
  });
  const tools = await p;
  assert.equal(tools.length, 2);
  assert.deepEqual(tools[0], {
    name: 'read', description: 'd',
    inputSchema: { properties: { p: { type: 'string' } }, required: ['p'] },
  });
  assert.deepEqual(tools[1], {
    name: 'write', description: null,
    inputSchema: { properties: {}, required: null },
  });
});

// ===== tools/call(tools.kt:180-227) =====

test('callTool:请求 params={name,arguments};结果 content text/image/other 三型解析 + isError', async () => {
  const t = new FakeTransport();
  const client = await connectWithTools(t);
  const p = client.callTool('mix', { a: 1 }, 120000);
  await new Promise((r): void => { setTimeout(r, 0); });
  const req = t.lastSent();
  assert.equal(req['method'], 'tools/call');
  assert.deepEqual(req['params'], { name: 'mix', arguments: { a: 1 } });
  t.respondLast({
    content: [
      { type: 'text', text: 'hi' },
      { type: 'image', data: 'aGk=', mimeType: 'image/png' },
      { type: 'audio', data: 'xx' },
    ],
    isError: true,
  });
  const result = await p;
  assert.deepEqual(result.content, [
    { type: 'text', text: 'hi' },
    { type: 'image', data: 'aGk=', mimeType: 'image/png' },
    { type: 'other', raw: { type: 'audio', data: 'xx' } },
  ]);
  assert.equal(result.isError, true);
});

// ===== strict capabilities(Client.kt:214-256,默认 enforce=true) =====

test('strict capabilities:服务器未声明 tools → callTool/listTools 抛 "Server does not support tools (required for ToolsCall/ToolsList)"', async () => {
  const t = new FakeTransport();
  const client = new McpClient({ name: 'a', version: '1.0' });
  const p = client.connect(t);
  await new Promise((r): void => { setTimeout(r, 0); });
  t.respondLast({ protocolVersion: MCP_LATEST_PROTOCOL_VERSION, capabilities: {}, serverInfo: { name: 's', version: '1' } });
  await p;
  await assert.rejects(client.callTool('x', {}),
    /Server does not support tools \(required for ToolsCall\)/);
  await assert.rejects(client.listTools(), /Server does not support tools \(required for ToolsList\)/);
});

// ===== 响应相关/错误映射(Protocol.kt:368-399) =====

test('JSONRPCError 响应 → McpError(code, message, data);message = "MCP error <code>: <msg>"', async () => {
  const t = new FakeTransport();
  const client = await connectWithTools(t);
  const p = client.listTools();
  await new Promise((r): void => { setTimeout(r, 0); });
  const id = t.lastSent()['id'] as string;
  t.emit({ jsonrpc: '2.0', id, error: { code: -32601, message: 'Method not found', data: { hint: 1 } } });
  await assert.rejects(p, (e: Error): boolean => {
    assert.ok(e instanceof McpError);
    assert.equal((e as McpError).code, -32601);
    assert.equal(e.message, 'MCP error -32601: Method not found');
    assert.deepEqual((e as McpError).data, { hint: 1 });
    return true;
  });
});

test('未知 id 响应 → onProtocolError "Received a response for an unknown message ID: <json>"', async () => {
  const t = new FakeTransport();
  const client = await connectWithTools(t);
  const errors: string[] = [];
  client.onProtocolError((e: Error): void => { errors.push(e.message); });
  t.emit({ jsonrpc: '2.0', id: 'zzz', result: {} });
  assert.equal(errors.length, 1);
  assert.ok(errors[0].startsWith('Received a response for an unknown message ID: '));
  assert.ok(errors[0].includes('zzz'));
});

// ===== 超时(Protocol.kt:503-520 — withTimeout 仅裹 send,quirk 逐字) =====

test('超时:send 挂起 → CancelledNotification{requestId,reason="MCP error -32001: Request timed out"} + 抛 "Timed out waiting for N ms"', async () => {
  const t = new FakeTransport();
  const client = await connectWithTools(t);
  t.sendHang = true;
  const p = client.callTool('slow', {}, 30);
  await assert.rejects(p, /Timed out waiting for 30 ms/);
  t.sendHang = false;
  // cancel 路径的 send 已录制
  await new Promise((r): void => { setTimeout(r, 10); });
  const cancelNote = t.sent[t.sent.length - 1];
  assert.equal(cancelNote['method'], 'notifications/cancelled');
  const params = cancelNote['params'] as JsonObject;
  assert.equal(params['reason'], 'MCP error -32001: Request timed out');
  assert.equal(typeof params['requestId'], 'string');
});

// ===== doClose(Protocol.kt:261-273) =====

test('传输关闭:挂起请求 → "MCP error -32000: Connection closed";hasTransport=false;onClose 触发', async () => {
  const t = new FakeTransport();
  const client = await connectWithTools(t);
  let closed: boolean = false;
  client.onClose((): void => { closed = true; });
  const p = client.listTools();
  await new Promise((r): void => { setTimeout(r, 0); });
  await t.close();
  await assert.rejects(p, /MCP error -32000: Connection closed/);
  assert.equal(closed, true);
  assert.equal(client.hasTransport, false);
});

// ===== 入站请求(Protocol.kt:401-418) =====

test('入站请求无 handler → METHOD_NOT_FOUND 回复 "Server does not support <method>"', async () => {
  const t = new FakeTransport();
  await connectWithTools(t);
  const before: number = t.sent.length;
  t.emit({ jsonrpc: '2.0', id: 'srv-1', method: 'sampling/createMessage' } as JsonObject);
  await new Promise((r): void => { setTimeout(r, 0); });
  assert.equal(t.sent.length, before + 1);
  const reply = t.lastSent();
  assert.equal(reply['id'], 'srv-1');
  const err = reply['error'] as JsonObject;
  assert.equal(err['code'], -32601);
  assert.equal(err['message'], 'Server does not support sampling/createMessage');
});

// ===== 边界 =====

test('未连接 → "Not connected"(SDK transport ?: error 逐字)', async () => {
  const client = new McpClient({ name: 'a', version: '1.0' });
  await assert.rejects(client.listTools(), /Not connected/);
});

test('notifications/progress 无注册 → onProtocolError "unknown token" 文案', async () => {
  const t = new FakeTransport();
  const client = await connectWithTools(t);
  const errors: string[] = [];
  client.onProtocolError((e: Error): void => { errors.push(e.message); });
  t.emit({ jsonrpc: '2.0', method: 'notifications/progress', params: { progressToken: 'tk', progress: 1 } } as JsonObject);
  assert.equal(errors.length, 1);
  assert.ok(errors[0].startsWith('Received a progress notification for an unknown token: '));
});

test('空消息({}) → 忽略(JSONRPCEmptyMessage → Unit)', async () => {
  const t = new FakeTransport();
  const client = await connectWithTools(t);
  const errors: string[] = [];
  client.onProtocolError((e: Error): void => { errors.push(e.message); });
  t.emit({});
  assert.equal(errors.length, 0);
});
