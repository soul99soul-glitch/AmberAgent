// D-120:MCP 工具桥接测试(金样 wire 逐字对照 McpManagementTools.kt + ChatService:2254-2282)

import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';
import {
  createMcpManagementTools, createMcpServerTools, mcpArgumentsObject, toolOutputPreview
} from '../main/ets/chat/mcp_tools.ts';
import type { McpToolActivityPort, McpSkillManagerPort } from '../main/ets/chat/mcp_tools.ts';
import { McpManager } from '../main/ets/chat/mcp_manager.ts';
import type { McpSettingsPort, McpFilesPort } from '../main/ets/chat/mcp_manager.ts';
import type {
  McpHttpPort, McpHttpResponse, McpSseStream, McpPostStreamResponse,
} from '../main/ets/chat/mcp_transports.ts';
import {
  makeMcpCommonOptions, makeMcpTool, makeMcpSseServer, makeMcpStreamableHttpServer
} from '../main/ets/chat/mcp_config.ts';
import type { McpServerConfig, McpTool } from '../main/ets/chat/mcp_config.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';

const tick = (): Promise<void> => new Promise((resolve): void => {
  setTimeout(resolve, 0);
});

const ticks = async (n: number): Promise<void> => {
  for (let i: number = 0; i < n; i++) await tick();
};

// ===== fakes(与 mcp_manager.test.ts 同款脚本化 server) =====

class FakeSettings implements McpSettingsPort {
  servers: McpServerConfig[] = [];
  assistantIds: string[] = [];

  getMcpServers(): McpServerConfig[] {
    return this.servers;
  }

  getCurrentAssistantMcpServerIds(): string[] {
    return this.assistantIds;
  }

  updateMcpServers(updater: (old: McpServerConfig[]) => McpServerConfig[]): void {
    this.servers = updater(this.servers);
  }

  subscribeMcpServers(_l: (c: McpServerConfig[]) => void): () => void {
    return (): void => {};
  }
}

class FakeFiles implements McpFilesPort {
  saveUploadFromBytes(_b: Uint8Array, displayName: string, _m: string): Promise<string> {
    return Promise.resolve(`file://mcp/${displayName}`);
  }

  extensionFromMimeType(mimeType: string): string | null {
    return mimeType === 'image/png' ? 'png' : null;
  }
}

class FakeSseStream implements McpSseStream {
  next(): Promise<null> {
    return new Promise((): void => {});
  }

  cancel(): Promise<void> {
    return Promise.resolve();
  }
}

const postResp = (bodyText: string): McpPostStreamResponse =>
  ({ status: 200, statusDescription: '', headers: [], contentType: 'application/json', bodyText, lines: null });

const INIT_RESULT: JsonObject = {
  protocolVersion: '2025-06-18',
  capabilities: { tools: {} },
  serverInfo: { name: 'srv', version: '1.0' },
};

class ScriptedHttpPort implements McpHttpPort {
  toolsResult: JsonObject[] = [];
  callResult: JsonObject = { content: [{ type: 'text', text: 'hi' }], isError: false };

  request(_m: string, _u: string, _h: Array<[string, string]>,
    _b: string | null): Promise<McpHttpResponse> {
    return Promise.resolve({
      status: 200, statusDescription: '', headers: [], contentType: null, bodyText: '',
    });
  }

  openSse(_u: string, _h: Array<[string, string]>): Promise<McpSseStream> {
    return Promise.resolve(new FakeSseStream());
  }

  postStream(_u: string, _h: Array<[string, string]>, body: string): Promise<McpPostStreamResponse> {
    const msg: JsonObject = JSON.parse(body) as JsonObject;
    if (msg['method'] === 'initialize') {
      return Promise.resolve(postResp(
        JSON.stringify({ id: msg['id'], result: INIT_RESULT, jsonrpc: '2.0' })));
    }
    if (msg['method'] === 'notifications/initialized') {
      return Promise.resolve({
        status: 202, statusDescription: '', headers: [], contentType: null, bodyText: '', lines: null,
      });
    }
    if (msg['method'] === 'tools/list') {
      return Promise.resolve(postResp(
        JSON.stringify({ id: msg['id'], result: { tools: this.toolsResult }, jsonrpc: '2.0' })));
    }
    if (msg['method'] === 'tools/call') {
      return Promise.resolve(postResp(
        JSON.stringify({ id: msg['id'], result: this.callResult, jsonrpc: '2.0' })));
    }
    return Promise.resolve(postResp(''));
  }
}

const makeHttpServer = (id: string, name: string, tools: McpTool[] = [],
  enable: boolean = true): McpServerConfig =>
  makeMcpStreamableHttpServer({
    id,
    url: 'https://mcp.test/mcp',
    commonOptions: makeMcpCommonOptions({ name, enable, tools }),
  });

interface Harness {
  manager: McpManager;
  settings: FakeSettings;
  port: ScriptedHttpPort;
}

const makeHarness = (): Harness => {
  const settings: FakeSettings = new FakeSettings();
  const port: ScriptedHttpPort = new ScriptedHttpPort();
  const manager: McpManager = new McpManager({
    http: port, settings, files: new FakeFiles(),
    delay: (ms: number): Promise<void> => {
      void ms;
      return Promise.resolve();
    },
  });
  return { manager, settings, port };
};

const textOf = (parts: UIMessagePart[]): string =>
  (parts[0] as { text: string }).text;

// ===== helpers =====

test('mcpArgumentsObject(:170-185):arguments ?? args ?? {};串解析;数组 error', () => {
  assert.deepEqual(mcpArgumentsObject({}), {});
  assert.deepEqual(mcpArgumentsObject({ arguments: { q: 1 } }), { q: 1 });
  assert.deepEqual(mcpArgumentsObject({ args: { q: 2 } }), { q: 2 });
  assert.deepEqual(mcpArgumentsObject({ arguments: '  {"a":true}  ' }), { a: true });
  assert.deepEqual(mcpArgumentsObject({ arguments: '   ' }), {});
  assert.throws(() => mcpArgumentsObject({ arguments: [1] }),
    (e: Error): boolean => e.message === 'arguments must be a JSON object');
  assert.throws(() => mcpArgumentsObject({ arguments: '"str"' }),
    (e: Error): boolean => e.message === 'Element string is not a JsonObject');
});

test('toolOutputPreview(:2547-2551):join \\n + takeLast(1600)', () => {
  const parts: UIMessagePart[] = [
    { type: 'text', text: 'a', metadata: null },
    { type: 'text', text: 'b', metadata: null },
  ];
  assert.equal(toolOutputPreview(parts), 'a\nb');
  const long: UIMessagePart[] = [{ type: 'text', text: 'x'.repeat(2000), metadata: null }];
  assert.equal(toolOutputPreview(long).length, 1600);
});

// ===== mcp_list =====

test('mcp_list:金样 wire(键序/默认 include_tools=true/schema Kotlin toString)', async () => {
  const h: Harness = makeHarness();
  const sse: McpServerConfig = makeMcpSseServer({
    id: 'sse-1',
    url: 'https://x/sse',
    commonOptions: makeMcpCommonOptions({
      name: 'legacy',
      tools: [makeMcpTool({
        name: 'echo', description: '回声', enable: true, needsApproval: false,
        inputSchema: { type: 'object', properties: { a: { type: 'string' } }, required: ['a'] },
      })],
    }),
  });
  h.settings.servers = [sse, makeHttpServer('http-1', 'modern', [], false)];
  const tools: AgentTool[] = createMcpManagementTools({ settings: h.settings, manager: h.manager });
  const list: AgentTool = tools[0];
  assert.equal(list.name, 'mcp_list');
  // 默认 include_tools=true / include_schema=false
  const payload: JsonObject = JSON.parse(textOf(await list.execute({}))) as JsonObject;
  const servers: JsonObject[] = payload['servers'] as JsonObject[];
  assert.equal(servers.length, 2);
  assert.deepEqual(Object.keys(servers[0]),
    ['id', 'name', 'enabled', 'status', 'tool_count', 'enabled_tool_count', 'type', 'url', 'tools']);
  assert.equal(servers[0]['id'], 'sse-1');
  assert.equal(servers[0]['status'], 'idle');
  assert.equal(servers[0]['type'], 'sse');
  assert.equal(servers[0]['tool_count'], 1);
  assert.equal(servers[1]['enabled'], false);
  const tool0: JsonObject = (servers[0]['tools'] as JsonObject[])[0];
  assert.deepEqual(Object.keys(tool0), ['name', 'description', 'enabled', 'needs_approval']);
  assert.equal(tool0['needs_approval'], false);
  assert.equal(payload['call_tool'], 'Use mcp_call_tool with server_id or name, tool_name, ' +
    'and arguments to call one of these tools directly.');
  // include_schema=true → schema = Kotlin data class toString
  const payload2: JsonObject = JSON.parse(textOf(await list.execute({
    include_schema: true,
  }))) as JsonObject;
  const tool0s: JsonObject = ((payload2['servers'] as JsonObject[])[0]['tools'] as JsonObject[])[0];
  assert.equal(tool0s['schema'],
    'Obj(properties={"a":{"type":"string"}}, required=[a])');
  // include_tools=false → 无 tools 键
  const payload3: JsonObject = JSON.parse(textOf(await list.execute({
    include_tools: false,
  }))) as JsonObject;
  assert.equal(((payload3['servers'] as JsonObject[])[0])['tools'], undefined);
  h.manager.dispose();
});

// ===== mcp_call_tool =====

test('mcp_call_tool:tool_name 缺 → 逐字 error;全链调用', async () => {
  const h: Harness = makeHarness();
  h.settings.servers = [makeHttpServer('s1', 'alpha', [makeMcpTool({ name: 'echo' })])];
  const tools: AgentTool[] = createMcpManagementTools({ settings: h.settings, manager: h.manager });
  const call: AgentTool = tools[1];
  assert.equal(call.name, 'mcp_call_tool');
  assert.equal(call.needsApproval, true);
  assert.equal(call.allowsAutoApproval, true);
  await assert.rejects(call.execute({ server_id: 's1' }),
    (e: Error): boolean => e.message === 'tool_name is required');
  h.port.toolsResult = [
    { name: 'echo', description: null, inputSchema: { properties: {}, required: null } },
  ];
  const parts: UIMessagePart[] = await call.execute({
    server_id: 's1', tool_name: 'echo', arguments: { q: 'x' },
  });
  assert.equal(textOf(parts), 'hi');
  assert.equal(h.manager.getStatus(h.settings.servers[0]).kind, 'connected');
  h.manager.dispose();
});

// ===== mcp_test =====

test('mcp_test:findMcpServer(id/name)+ not found + payload 金样', async () => {
  const h: Harness = makeHarness();
  h.settings.servers = [makeHttpServer('s1', 'alpha')];
  h.port.toolsResult = [];
  const tools: AgentTool[] = createMcpManagementTools({ settings: h.settings, manager: h.manager });
  const testTool: AgentTool = tools[2];
  assert.equal(testTool.name, 'mcp_test');
  assert.equal(testTool.needsApproval, true);
  await assert.rejects(testTool.execute({ server_id: 'ghost' }),
    (e: Error): boolean => e.message === 'MCP server not found');
  const parts: UIMessagePart[] = await testTool.execute({ name: 'alpha' });
  const payload: JsonObject = JSON.parse(textOf(parts)) as JsonObject;
  assert.deepEqual(Object.keys(payload), ['server', 'status']);
  assert.equal(payload['status'], 'connected');
  const server: JsonObject = payload['server'] as JsonObject;
  assert.equal(server['id'], 's1');
  assert.equal(server['status'], 'connected');
  h.manager.dispose();
});

// ===== createRunTools mcp__ 组(ChatService:2254-2282) =====

test('mcp__ 组:name/needsApproval/description/parameters 透传 + 全链 execute', async () => {
  const h: Harness = makeHarness();
  h.settings.servers = [makeHttpServer('s1', 'alpha', [
    makeMcpTool({
      name: 'echo', description: '回声', needsApproval: false,
      inputSchema: { type: 'object', properties: { q: { type: 'string' } }, required: null },
    }),
    makeMcpTool({ name: 'off', enable: false }),
  ])];
  h.settings.assistantIds = ['s1'];
  h.port.toolsResult = [
    { name: 'echo', description: '回声', inputSchema: { properties: { q: { type: 'string' } }, required: null } },
  ];
  h.settings.servers = [h.settings.servers[0]]; // 触发不到 emit — 直接 addClient
  await h.manager.addClient(h.settings.servers[0]);
  await ticks(4);
  const group: AgentTool[] = createMcpServerTools({ manager: h.manager });
  // getAllAvailableTools:enable 过滤 → 仅 echo
  assert.equal(group.length, 1);
  const t: AgentTool = group[0];
  assert.equal(t.name, 'mcp__echo');
  assert.equal(t.description, '回声');
  assert.equal(t.needsApproval, false);
  assert.deepEqual(t.parameters(),
    { type: 'object', properties: { q: { type: 'string' } }, required: null });
  const parts: UIMessagePart[] = await t.execute({ q: 'hello' });
  assert.equal(textOf(parts), 'hi');
  h.manager.dispose();
});

test('mcp__ 组:activity 端口事件(start/complete/fail 序 + preview)', async () => {
  const events: string[] = [];
  const activity: McpToolActivityPort = {
    startTool: (toolName: string, title: string, inputPreview: string, runtime: string): string => {
      events.push(`start:${toolName}|${title}|${inputPreview}|${runtime}`);
      return 'tc-1';
    },
    complete: (toolCallId: string, outputPreview: string): void => {
      events.push(`complete:${toolCallId}|${outputPreview}`);
    },
    fail: (toolCallId: string, error: Error): void => {
      events.push(`fail:${toolCallId}|${error.message}`);
    },
  };
  const h: Harness = makeHarness();
  h.settings.servers = [makeHttpServer('s1', 'alpha', [makeMcpTool({ name: 'echo' })])];
  h.settings.assistantIds = ['s1'];
  h.port.toolsResult = [
    { name: 'echo', description: null, inputSchema: { properties: {}, required: null } },
  ];
  await h.manager.addClient(h.settings.servers[0]);
  await ticks(4);
  const group: AgentTool[] = createMcpServerTools({ manager: h.manager, activity });
  await group[0].execute({ q: 1 });
  assert.deepEqual(events, [
    'start:mcp__echo|调用 MCP 工具|{"q":1}|MCP',
    'complete:tc-1|hi',
  ]);
  // fail 路径:工具不存在 → callTool 返回错误文本(非异常);用 manager 抛错路径
  const bad: AgentTool[] = createMcpServerTools({ manager: h.manager, activity });
  h.port.callResult = { content: [{ type: 'text', text: 'x' }] };
  events.length = 0;
  // input 非 object → jsonObject 抛(经 fail 包装)
  await assert.rejects((bad[0].execute as (i: JsonValue) => Promise<UIMessagePart[]>)('raw'));
  assert.ok(events[0].startsWith('start:mcp__echo|调用 MCP 工具|"raw"|MCP'));
  assert.ok(events[1].startsWith('fail:tc-1|'));
  h.manager.dispose();
});

// ===== D-123:mcp_import_from_skill(McpManagementTools.kt:131-167) =====

const makeSkillPort = (dirs: Record<string, string | null>): McpSkillManagerPort => ({
  getSkillDir: (skillName: string): string | null =>
    skillName.indexOf('/') < 0 && skillName.indexOf('\\') < 0 &&
      skillName !== '.' && skillName !== '..' && skillName.trim().length > 0
      ? `/files/skills/${skillName}` : null,
  fileExists: (path: string): boolean =>
    Object.prototype.hasOwnProperty.call(dirs, path) && dirs[path] !== null,
  readFileText: (path: string): string => {
    const hit: string | null | undefined = dirs[path];
    if (hit === undefined || hit === null) throw new Error(`NoSuchFile: ${path}`);
    return hit;
  },
});

const MCP_JSON: string = JSON.stringify({
  mcpServers: {
    alpha: { type: 'streamable_http', url: 'https://mcp.test/mcp' },
    beta: { type: 'sse', url: 'https://mcp.test/sse' },
  },
});

test('mcp_import_from_skill:skills 缺省 → 工具不出;传入 → 第四件', () => {
  const h: Harness = makeHarness();
  const without: AgentTool[] =
    createMcpManagementTools({ settings: h.settings, manager: h.manager });
  assert.deepEqual(without.map((t: AgentTool): string => t.name),
    ['mcp_list', 'mcp_call_tool', 'mcp_test']);
  const withSkill: AgentTool[] = createMcpManagementTools({
    settings: h.settings, manager: h.manager, skills: makeSkillPort({}),
  });
  assert.deepEqual(withSkill.map((t: AgentTool): string => t.name),
    ['mcp_list', 'mcp_call_tool', 'mcp_test', 'mcp_import_from_skill']);
  h.manager.dispose();
});

test('mcp_import_from_skill:needsApproval + 三错误文案逐字', async () => {
  const h: Harness = makeHarness();
  const tools: AgentTool[] = createMcpManagementTools({
    settings: h.settings, manager: h.manager,
    skills: makeSkillPort({
      '/files/skills/empty/mcp.json': '{"mcpServers":{}}',
      '/files/skills/nomcp/mcp.json': null,
    }),
  });
  const t: AgentTool = tools[3];
  assert.equal(t.needsApproval, true);
  await assert.rejects(t.execute({}), /skill_name is required/);
  await assert.rejects(t.execute({ skill_name: '../x' }), /Skill not found: \.\.\/x/);
  await assert.rejects(t.execute({ skill_name: 'nomcp' }),
    /Skill 'nomcp' does not contain mcp\.json/);
  await assert.rejects(t.execute({ skill_name: 'empty' }),
    /mcp\.json does not contain valid MCP servers/);
  h.manager.dispose();
});

test('mcp_import_from_skill:导入 + importKey 去重(payload 计数逐字)', async () => {
  const h: Harness = makeHarness();
  // 预置 alpha(importKey 相同 → 去重)
  h.settings.servers = [makeHttpServer('existing', 'alpha')];
  const tools: AgentTool[] = createMcpManagementTools({
    settings: h.settings, manager: h.manager,
    skills: makeSkillPort({ '/files/skills/pack/mcp.json': MCP_JSON }),
  });
  const parts: UIMessagePart[] = await tools[3].execute({ skill_name: 'pack' });
  if (parts[0].type !== 'text') throw new Error('text part');
  assert.deepEqual(JSON.parse(parts[0].text), {
    success: true, skill_name: 'pack', imported_count: 1, already_exists_count: 1,
  });
  assert.equal(h.settings.servers.length, 2);
  assert.equal(h.settings.servers[1].commonOptions.name, 'beta');
  assert.equal(h.settings.servers[1].kind, 'sse');
  // 二次导入:全部重复 → 0/2
  const again: UIMessagePart[] = await tools[3].execute({ skill_name: 'pack' });
  if (again[0].type !== 'text') throw new Error('text part');
  assert.deepEqual(JSON.parse(again[0].text), {
    success: true, skill_name: 'pack', imported_count: 0, already_exists_count: 2,
  });
  assert.equal(h.settings.servers.length, 2);
  h.manager.dispose();
});
