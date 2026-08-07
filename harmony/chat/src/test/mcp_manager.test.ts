// D-119:McpManager 测试(脚本化 MCP server fake 驱动全栈:manager→transport→client)
//
// 对照:McpManager.kt(513 行全文)金样 — 错误消息逐字/退避表/sync 合并语义/
// 重连门(仅 Connected 触发)/最大次数 '连接断开，已达最大重连次数'/clients re-key。

import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonObject } from '../main/ets/chat/json.ts';
import {
  McpManager, mcpCalculateBackoffDelay, base64Decode,
  MCP_MAX_RECONNECT_ATTEMPTS,
} from '../main/ets/chat/mcp_manager.ts';
import type {
  McpSettingsPort, McpFilesPort, McpMessagePart,
} from '../main/ets/chat/mcp_manager.ts';
import type {
  McpHttpPort, McpHttpResponse, McpSseEvent, McpSseStream, McpPostStreamResponse,
} from '../main/ets/chat/mcp_transports.ts';
import {
  makeMcpCommonOptions, makeMcpTool, makeMcpSseServer, makeMcpStreamableHttpServer,
} from '../main/ets/chat/mcp_config.ts';
import type { McpServerConfig, McpTool, McpStatus } from '../main/ets/chat/mcp_config.ts';

const tick = (): Promise<void> => new Promise((resolve): void => {
  setTimeout(resolve, 0);
});

const ticks = async (n: number): Promise<void> => {
  for (let i: number = 0; i < n; i++) await tick();
};

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

class FakeSettings implements McpSettingsPort {
  servers: McpServerConfig[] = [];
  assistantIds: string[] = [];
  private listeners: Array<(c: McpServerConfig[]) => void> = [];

  emit(): void {
    for (const l of this.listeners) l(this.servers);
  }

  getMcpServers(): McpServerConfig[] {
    return this.servers;
  }

  getCurrentAssistantMcpServerIds(): string[] {
    return this.assistantIds;
  }

  updateMcpServers(updater: (old: McpServerConfig[]) => McpServerConfig[]): void {
    this.servers = updater(this.servers);
  }

  subscribeMcpServers(listener: (c: McpServerConfig[]) => void): () => void {
    this.listeners.push(listener);
    return (): void => {
      const i: number = this.listeners.indexOf(listener);
      if (i >= 0) this.listeners.splice(i, 1);
    };
  }
}

interface SavedUpload {
  bytes: Uint8Array;
  displayName: string;
  mimeType: string;
}

class FakeFiles implements McpFilesPort {
  uploads: SavedUpload[] = [];

  saveUploadFromBytes(bytes: Uint8Array, displayName: string,
    mimeType: string): Promise<string> {
    this.uploads.push({ bytes, displayName, mimeType });
    return Promise.resolve(`file://mcp/${displayName}`);
  }

  extensionFromMimeType(mimeType: string): string | null {
    if (mimeType === 'image/png') return 'png';
    if (mimeType === 'image/jpeg') return 'jpg';
    return null;
  }
}

const resp = (status: number, bodyText: string,
  headers: Array<[string, string]> = [], contentType: string | null = null): McpHttpResponse =>
  ({ status, statusDescription: '', headers, contentType, bodyText });

const postResp = (status: number, bodyText: string): McpPostStreamResponse =>
  ({ status, statusDescription: '', headers: [], contentType: 'application/json', bodyText, lines: null });

const INIT_RESULT: JsonObject = {
  protocolVersion: '2025-06-18',
  capabilities: { tools: {} },
  serverInfo: { name: 'srv', version: '1.0' },
};

// 脚本化 streamable_http server(POST 单面)
class ScriptedHttpPort implements McpHttpPort {
  postCalls: Array<{ url: string; headers: Array<[string, string]>; body: string }> = [];
  deleteCalls: number = 0;
  streams: FakeSseStream[] = [];
  toolsResult: JsonObject[] = [];
  callResult: JsonObject = { content: [{ type: 'text', text: 'hi' }], isError: false };
  failRequests: boolean = false;

  request(method: string, _url: string, _h: Array<[string, string]>,
    _b: string | null): Promise<McpHttpResponse> {
    if (method === 'DELETE') {
      this.deleteCalls++;
      return Promise.resolve(resp(200, ''));
    }
    return Promise.resolve(resp(200, ''));
  }

  openSse(_url: string, _h: Array<[string, string]>): Promise<McpSseStream> {
    const s: FakeSseStream = new FakeSseStream();
    this.streams.push(s);
    return Promise.resolve(s);
  }

  postStream(url: string, headers: Array<[string, string]>,
    body: string): Promise<McpPostStreamResponse> {
    this.postCalls.push({ url, headers, body });
    if (this.failRequests) return Promise.reject(new Error('conn reset'));
    const msg: JsonObject = JSON.parse(body) as JsonObject;
    if (msg['method'] === 'initialize') {
      return Promise.resolve(postResp(200,
        JSON.stringify({ id: msg['id'], result: INIT_RESULT, jsonrpc: '2.0' })));
    }
    if (msg['method'] === 'notifications/initialized') {
      return Promise.resolve({
        status: 202, statusDescription: '', headers: [], contentType: null, bodyText: '', lines: null,
      });
    }
    if (msg['method'] === 'tools/list') {
      return Promise.resolve(postResp(200,
        JSON.stringify({ id: msg['id'], result: { tools: this.toolsResult }, jsonrpc: '2.0' })));
    }
    if (msg['method'] === 'tools/call') {
      return Promise.resolve(postResp(200,
        JSON.stringify({ id: msg['id'], result: this.callResult, jsonrpc: '2.0' })));
    }
    return Promise.resolve(postResp(200, ''));
  }
}

// 脚本化 legacy SSE server(GET 流 + POST 端点,响应经流回推)
class ScriptedSsePort implements McpHttpPort {
  streams: FakeSseStream[] = [];
  postCalls: Array<{ url: string; headers: Array<[string, string]>; body: string }> = [];
  toolsResult: JsonObject[] = [];
  callResult: JsonObject = { content: [{ type: 'text', text: 'hi' }], isError: false };

  request(method: string, url: string, headers: Array<[string, string]>,
    body: string | null): Promise<McpHttpResponse> {
    if (method === 'DELETE') return Promise.resolve(resp(200, ''));
    this.postCalls.push({ url, headers, body: body ?? '' });
    const msg: JsonObject = JSON.parse(body ?? '{}') as JsonObject;
    const method2: string = String(msg['method'] ?? '');
    const respond = (result: JsonObject): void => {
      const stream: FakeSseStream = this.streams[this.streams.length - 1];
      setTimeout((): void => {
        stream.push({
          event: null,
          data: JSON.stringify({ id: msg['id'], result, jsonrpc: '2.0' }),
          id: null,
        });
      }, 0);
    };
    if (method2 === 'initialize') respond(INIT_RESULT);
    else if (method2 === 'tools/list') respond({ tools: this.toolsResult });
    else if (method2 === 'tools/call') respond(this.callResult);
    return Promise.resolve(resp(200, ''));
  }

  openSse(_url: string, _h: Array<[string, string]>): Promise<McpSseStream> {
    const s: FakeSseStream = new FakeSseStream();
    this.streams.push(s);
    setTimeout((): void => {
      s.push({ event: 'endpoint', data: '/ep', id: null });
    }, 0);
    return Promise.resolve(s);
  }

  postStream(_u: string, _h: Array<[string, string]>, _b: string): Promise<McpPostStreamResponse> {
    throw new Error('legacy sse 不走 postStream');
  }
}

// ===== 构造辅助 =====

const makeHttpServer = (id: string, name: string, tools: McpTool[] = [],
  enable: boolean = true): McpServerConfig =>
  makeMcpStreamableHttpServer({
    id,
    url: 'https://mcp.test/mcp',
    commonOptions: makeMcpCommonOptions({ name, enable, tools, headers: [['X-Key', 'v']] }),
  });

const makeSseServer = (id: string, name: string, tools: McpTool[] = [],
  enable: boolean = true): McpServerConfig =>
  makeMcpSseServer({
    id,
    url: 'https://mcp.test/sse',
    commonOptions: makeMcpCommonOptions({ name, enable, tools, headers: [['X-Key', 'v']] }),
  });

interface Harness {
  manager: McpManager;
  settings: FakeSettings;
  files: FakeFiles;
  port: ScriptedHttpPort;
  statuses: Array<Map<string, McpStatus>>;
}

const makeHarness = (delay?: (ms: number) => Promise<void>): Harness => {
  const settings: FakeSettings = new FakeSettings();
  const files: FakeFiles = new FakeFiles();
  const port: ScriptedHttpPort = new ScriptedHttpPort();
  const statuses: Array<Map<string, McpStatus>> = [];
  const manager: McpManager = new McpManager({
    http: port,
    settings,
    files,
    delay: delay ?? ((ms: number): Promise<void> => {
      void ms;
      return Promise.resolve();
    }),
  });
  manager.subscribeStatuses((m: ReadonlyMap<string, McpStatus>): void => {
    statuses.push(new Map(m));
  });
  return { manager, settings, files, port, statuses };
};

const lastStatus = (h: Harness, id: string): McpStatus | undefined =>
  h.statuses[h.statuses.length - 1].get(id);

// ===== 纯函数 =====

test('退避表(:441-445):1000*2^(attempt-1) cap 30000,shift cap 10', () => {
  assert.equal(mcpCalculateBackoffDelay(1), 1000);
  assert.equal(mcpCalculateBackoffDelay(2), 2000);
  assert.equal(mcpCalculateBackoffDelay(3), 4000);
  assert.equal(mcpCalculateBackoffDelay(4), 8000);
  assert.equal(mcpCalculateBackoffDelay(5), 16000);
  assert.equal(mcpCalculateBackoffDelay(6), 30000); // 32000 → cap
  assert.equal(mcpCalculateBackoffDelay(12), 30000); // shift cap 10 → 1024000 → cap
  assert.equal(MCP_MAX_RECONNECT_ATTEMPTS, 5);
});

test('base64Decode:标准字母表 + padding + 换行容忍', () => {
  assert.deepEqual([...base64Decode('aGVsbG8=')], [104, 101, 108, 108, 111]);
  assert.deepEqual([...base64Decode('aGVs\r\nbG8=')], [104, 101, 108, 108, 111]);
  assert.deepEqual(base64Decode('').length, 0);
  assert.throws(() => base64Decode('aGV$'), /Invalid base64 character/);
});

// ===== init diff(:85-114) =====

test('init:emit 新增 enabled → addClient 全链(握手+sync+Connected)', async () => {
  const h: Harness = makeHarness();
  const server: McpServerConfig = makeHttpServer('s1', 'alpha');
  h.settings.servers = [server];
  h.settings.emit();
  await ticks(6);
  assert.equal(lastStatus(h, 's1')?.kind, 'connected');
  assert.ok(h.manager.getClient(server) !== null);
  // 握手帧:initialize → initialized(202 → 异步 GET SSE)→ tools/list
  const methods: string[] = h.port.postCalls.map(
    (c): string => String((JSON.parse(c.body) as JsonObject)['method']));
  assert.deepEqual(methods, ['initialize', 'notifications/initialized', 'tools/list']);
  // config headers 注入(:241-244)
  assert.ok(h.port.postCalls[0].headers.some(([k, v]) => k === 'X-Key' && v === 'v'));
  h.manager.dispose();
});

test('init:emit 移除 → removeClient(close+状态摘除)', async () => {
  const h: Harness = makeHarness();
  const server: McpServerConfig = makeHttpServer('s1', 'alpha');
  h.settings.servers = [server];
  h.settings.emit();
  await ticks(6);
  h.settings.servers = [];
  h.settings.emit();
  await ticks(4);
  assert.equal(h.manager.getClient(server), null);
  assert.equal(lastStatus(h, 's1'), undefined);
  h.manager.dispose();
});

test('init:disabled 配置不入列(:92 enable 过滤)', async () => {
  const h: Harness = makeHarness();
  h.settings.servers = [makeHttpServer('s1', 'alpha', [], false)];
  h.settings.emit();
  await ticks(4);
  assert.equal(h.manager.getClient(h.settings.servers[0]), null);
  assert.equal(h.port.postCalls.length, 0);
  h.manager.dispose();
});

// ===== getAllAvailableTools(:120-131) =====

test('getAllAvailableTools:enable + assistant 白名单 + tool.enable 三过滤', () => {
  const h: Harness = makeHarness();
  const t1: McpTool = makeMcpTool({ name: 'echo' });
  const t2: McpTool = makeMcpTool({ name: 'off', enable: false });
  h.settings.servers = [
    makeHttpServer('s1', 'alpha', [t1, t2]),
    makeHttpServer('s2', 'beta', [makeMcpTool({ name: 'other' })]),
    makeHttpServer('s3', 'gamma', [makeMcpTool({ name: 'disabledSrv' })], false),
  ];
  h.settings.assistantIds = ['s1'];
  const names: string[] = h.manager.getAllAvailableTools().map((t: McpTool): string => t.name);
  assert.deepEqual(names, ['echo']);
  h.manager.dispose();
});

// ===== callTool(:133-160) =====

test('callTool:无此工具/无 client — 两金样消息(:136/:139)', async () => {
  const h: Harness = makeHarness();
  h.settings.servers = [makeHttpServer('s1', 'alpha', [makeMcpTool({ name: 'echo' })])];
  h.settings.assistantIds = ['s1'];
  let parts: McpMessagePart[] = await h.manager.callTool('nope', {});
  assert.deepEqual(parts, [
    { type: 'text', text: 'Failed to execute tool, because no such tool', metadata: null },
  ]);
  // 工具存在但 clients 空(未 emit → 未 addClient)
  parts = await h.manager.callTool('echo', {});
  assert.deepEqual(parts, [
    { type: 'text', text: 'Failed to execute tool, because no such mcp client for the tool', metadata: null },
  ]);
  h.manager.dispose();
});

test('callTool:全链 — text/image→file part/other→JSON 三型(:153-159)', async () => {
  const h: Harness = makeHarness();
  const server: McpServerConfig = makeHttpServer('s1', 'alpha', [makeMcpTool({ name: 'echo' })]);
  h.settings.servers = [server];
  h.settings.assistantIds = ['s1'];
  // sync 以服务端 tools 重建本地表(:316-337)— 脚本服务端须回 'echo' 否则被 removeIf
  h.port.toolsResult = [
    { name: 'echo', description: null, inputSchema: { properties: {}, required: null } },
  ];
  h.settings.emit();
  await ticks(6);
  h.port.callResult = {
    content: [
      { type: 'text', text: 'done' },
      { type: 'image', data: 'aGVsbG8=', mimeType: 'image/png' },
      { type: 'resource', resource: { uri: 'mcp://x' } },
    ],
    isError: false,
  };
  const parts: McpMessagePart[] = await h.manager.callTool('echo', { q: 1 });
  assert.equal(parts.length, 3);
  assert.deepEqual(parts[0], { type: 'text', text: 'done', metadata: null });
  assert.deepEqual(parts[1], { type: 'image', url: 'file://mcp/mcp_image.png', metadata: null });
  // else → JsonInstant.encodeToString(:157)
  assert.equal(parts[2].type, 'text');
  assert.equal((parts[2] as { text: string }).text,
    '{"type":"resource","resource":{"uri":"mcp://x"}}');
  // convertImageContentToFilePart(:207-219):base64 解码 + ext + displayName
  assert.equal(h.files.uploads.length, 1);
  assert.equal(h.files.uploads[0].displayName, 'mcp_image.png');
  assert.deepEqual([...h.files.uploads[0].bytes], [104, 101, 108, 108, 111]);
  // tools/call 帧:name + arguments + 120s(timeout 不出现于帧)
  const callFrame: JsonObject = JSON.parse(h.port.postCalls[3].body) as JsonObject;
  assert.equal(callFrame['method'], 'tools/call');
  assert.deepEqual(callFrame['params'], { name: 'echo', arguments: { q: 1 } });
  h.manager.dispose();
});

test('callTool:未知 mime → ext bin(:210)', async () => {
  const h: Harness = makeHarness();
  const server: McpServerConfig = makeHttpServer('s1', 'alpha', [makeMcpTool({ name: 'echo' })]);
  h.settings.servers = [server];
  h.settings.assistantIds = ['s1'];
  h.port.toolsResult = [
    { name: 'echo', description: null, inputSchema: { properties: {}, required: null } },
  ];
  h.settings.emit();
  await ticks(6);
  h.port.callResult = {
    content: [{ type: 'image', data: 'AA==', mimeType: 'image/x-unknown' }],
  };
  const parts: McpMessagePart[] = await h.manager.callTool('echo', {});
  assert.equal((parts[0] as { url: string }).url, 'file://mcp/mcp_image.bin');
  h.manager.dispose();
});

// ===== callConfiguredTool(:162-205) =====

test('callConfiguredTool:四金样 error/require 逐字', async () => {
  const h: Harness = makeHarness();
  h.settings.servers = [
    makeHttpServer('s1', 'alpha', [
      makeMcpTool({ name: 'echo' }),
      makeMcpTool({ name: 'off', enable: false }),
    ]),
  ];
  await assert.rejects(h.manager.callConfiguredTool(null, null, '  ', {}),
    (e: Error): boolean => e.message === 'tool_name is required');
  await assert.rejects(h.manager.callConfiguredTool(null, null, 'ghost', {}),
    (e: Error): boolean => e.message === 'MCP tool not found in enabled servers: ghost');
  // serverId 过滤不匹配 → 同第一条
  await assert.rejects(h.manager.callConfiguredTool('other-id', null, 'echo', {}),
    (e: Error): boolean => e.message === 'MCP tool not found in enabled servers: echo');
  await assert.rejects(h.manager.callConfiguredTool(null, null, 'off', {}),
    (e: Error): boolean => e.message === 'MCP tool is disabled: alpha/off');
  h.manager.dispose();
});

test('callConfiguredTool:client 缺 → addClient 懒建 + 调用(:180-197)', async () => {
  const h: Harness = makeHarness();
  h.settings.servers = [makeHttpServer('s1', 'alpha', [makeMcpTool({ name: 'echo' })])];
  const parts: McpMessagePart[] = await h.manager.callConfiguredTool('s1', null, 'echo', {});
  assert.deepEqual(parts[0], { type: 'text', text: 'hi', metadata: null });
  assert.equal(lastStatus(h, 's1')?.kind, 'connected');
  h.manager.dispose();
});

// ===== sync 合并(:297-360) =====

test('sync:新增 enable=true/更新保 enable/缺席删除 + clients re-key', async () => {
  const h: Harness = makeHarness();
  const server: McpServerConfig = makeHttpServer('s1', 'alpha', [
    makeMcpTool({ name: 'keep', enable: false, description: 'old' }),
    makeMcpTool({ name: 'gone' }),
  ]);
  h.settings.servers = [server];
  h.settings.emit();
  h.port.toolsResult = [
    { name: 'keep', description: 'new-desc', inputSchema: { properties: { a: { type: 'string' } }, required: ['a'] } },
    { name: 'fresh', description: null, inputSchema: { properties: {}, required: null } },
  ];
  await ticks(8);
  const updated: McpServerConfig = h.settings.servers[0];
  const tools: McpTool[] = updated.commonOptions.tools;
  assert.equal(tools.length, 2);
  const keep: McpTool = tools.find((t: McpTool): boolean => t.name === 'keep') as McpTool;
  assert.equal(keep.enable, false); // copy 仅改 description+inputSchema(:329-332)
  assert.equal(keep.description, 'new-desc');
  assert.deepEqual(keep.inputSchema,
    { type: 'object', properties: { a: { type: 'string' } }, required: ['a'] });
  const fresh: McpTool = tools.find((t: McpTool): boolean => t.name === 'fresh') as McpTool;
  assert.equal(fresh.enable, true); // 新增默认 enable=true(:320-325)
  // re-key 后新 config(同 id)仍可取 client(:116-118 id 匹配)
  assert.ok(h.manager.getClient(updated) !== null);
  h.manager.dispose();
});

// ===== addClient 失败(:291-294) =====

test('addClient:连接失败 → Error 状态(message)', async () => {
  const h: Harness = makeHarness();
  h.port.failRequests = true;
  h.settings.servers = [makeHttpServer('s1', 'alpha')];
  h.settings.emit();
  await ticks(6);
  const st: McpStatus | undefined = lastStatus(h, 's1');
  assert.equal(st?.kind, 'error');
  if (st?.kind === 'error') {
    assert.equal(st.message, 'Error connecting to transport: conn reset');
  }
  h.manager.dispose();
});

// ===== legacy SSE 全链(getTransport :222-234) =====

test('sse server:addClient 全链(GET 流 endpoint + POST + 流回推)+ callTool', async () => {
  const settings: FakeSettings = new FakeSettings();
  const files: FakeFiles = new FakeFiles();
  const port: ScriptedSsePort = new ScriptedSsePort();
  const manager: McpManager = new McpManager({ http: port, settings, files });
  const server: McpServerConfig = makeSseServer('s1', 'legacy', [makeMcpTool({ name: 'echo' })]);
  settings.servers = [server];
  settings.assistantIds = ['s1'];
  port.toolsResult = [
    { name: 'echo', description: null, inputSchema: { properties: {}, required: null } },
  ];
  settings.emit();
  await ticks(10);
  assert.equal(manager.getStatus(server).kind, 'connected');
  // POST 打到解析端点,config headers 注入(:227-231)
  assert.equal(port.postCalls[0].url, 'https://mcp.test/ep');
  assert.ok(port.postCalls[0].headers.some(([k]) => k === 'X-Key'));
  const parts: McpMessagePart[] = await manager.callTool('echo', {});
  assert.deepEqual(parts[0], { type: 'text', text: 'hi', metadata: null });
  manager.dispose();
});

// ===== 重连(:388-487) =====

test('重连:Connected 后 SSE error → Reconnecting(1,5) → 重连成功 Connected + 计数复位', async () => {
  const h: Harness = makeHarness();
  const server: McpServerConfig = makeHttpServer('s1', 'alpha');
  h.settings.servers = [server];
  h.settings.emit();
  await ticks(6);
  assert.equal(lastStatus(h, 's1')?.kind, 'connected');
  // 模拟 GET SSE 流错误 → transport onError → 门:Connected → scheduleReconnect
  assert.equal(h.port.streams.length, 1);
  h.port.streams[0].push({ event: 'error', data: 'boom', id: null });
  await ticks(20);
  const st: McpStatus | undefined = lastStatus(h, 's1');
  assert.equal(st?.kind, 'connected'); // reconnectClient 成功(:484)
  // 过程中出现过 Reconnecting(1,5)
  const sawReconnecting: boolean = h.statuses.some((m: Map<string, McpStatus>): boolean => {
    const s: McpStatus | undefined = m.get('s1');
    return s?.kind === 'reconnecting' && s.attempt === 1 && s.maxAttempts === 5;
  });
  assert.ok(sawReconnecting);
  h.manager.dispose();
});

test('重连:持续失败 → 达最大次数 Error(连接断开，已达最大重连次数 :395)', async () => {
  const h: Harness = makeHarness();
  const server: McpServerConfig = makeHttpServer('s1', 'alpha');
  h.settings.servers = [server];
  h.settings.emit();
  await ticks(6);
  assert.equal(lastStatus(h, 's1')?.kind, 'connected');
  h.port.failRequests = true; // 后续 initialize 全失败 → reconnectClient 抛 → 递归
  h.port.streams[0].push({ event: 'error', data: 'boom', id: null });
  await ticks(40);
  const st: McpStatus | undefined = lastStatus(h, 's1');
  assert.equal(st?.kind, 'error');
  if (st?.kind === 'error') {
    assert.equal(st.message, '连接断开，已达最大重连次数');
  }
  h.manager.dispose();
});

test('removeClient: intentional close 不调度重连', async () => {
  let delayed: number = 0;
  const h: Harness = makeHarness((ms: number): Promise<void> => {
    void ms;
    delayed++;
    return new Promise((): void => {}); // 永不 resolve:job 挂起待察
  });
  h.settings.servers = [makeHttpServer('s1', 'alpha')];
  h.settings.emit();
  await ticks(6);
  assert.equal(lastStatus(h, 's1')?.kind, 'connected');
  await h.manager.removeClient(h.settings.servers[0]);
  assert.equal(delayed, 0);
  const sawReconnecting: boolean = h.statuses.some((m: Map<string, McpStatus>): boolean =>
    m.get('s1')?.kind === 'reconnecting');
  assert.equal(sawReconnecting, false);
  // 状态 entry 最终被 removeClient 摘除(close 完成后)
  assert.equal(lastStatus(h, 's1'), undefined);
  assert.equal(h.manager.getClient(h.settings.servers[0]), null);
  h.manager.dispose();
});

test('removeClient:取消重连任务(:373)', async () => {
  let delayed: number = 0;
  const h: Harness = makeHarness((ms: number): Promise<void> => {
    void ms;
    delayed++;
    return new Promise((): void => {}); // 永不 resolve 的 delay
  });
  const server: McpServerConfig = makeHttpServer('s1', 'alpha');
  h.settings.servers = [server];
  h.settings.emit();
  await ticks(6);
  h.port.failRequests = true;
  h.port.streams[0].push({ event: 'error', data: 'boom', id: null });
  await ticks(4);
  assert.equal(delayed, 1); // 重连任务在 delay 中
  await h.manager.removeClient(server);
  await ticks(10);
  // cancel 后 delay 返回即静默;无进一步重连调度
  assert.equal(delayed, 1);
  assert.equal(lastStatus(h, 's1'), undefined);
  h.manager.dispose();
});

// ===== syncAll(:362-370) =====

test('syncAll:逐 client sync,单失败不阻断', async () => {
  const h: Harness = makeHarness();
  h.settings.servers = [makeHttpServer('s1', 'alpha'), makeHttpServer('s2', 'beta')];
  h.settings.emit();
  await ticks(8);
  h.port.toolsResult = [
    { name: 't', description: null, inputSchema: { properties: {}, required: null } },
  ];
  await h.manager.syncAll();
  for (const s of h.settings.servers) {
    assert.equal(s.commonOptions.tools.length, 1);
    assert.equal(s.commonOptions.tools[0].name, 't');
  }
  h.manager.dispose();
});
