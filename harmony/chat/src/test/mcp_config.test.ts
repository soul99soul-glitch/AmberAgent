// MCP 配置模型/线格式/导入解析器规格测试(D-116)
//
// Android 基准:
//   app/core/ai/mcp/McpConfig.kt(60 行全文;@Serializable 声明序;sealed 鉴别名
//     'sse'/'streamable_http';kotlinx Pair → {"first","second"})
//   app/core/ai/mcp/McpStatus.kt(五态)
//   app/core/ai/mcp/McpImportParser.kt(24 行全文)
//   ai/util/Json.kt(JsonInstant:encodeDefaults/explicitNulls=false/ignoreUnknownKeys)

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  makeMcpCommonOptions, makeMcpTool, makeMcpSseServer, makeMcpStreamableHttpServer
} from '../main/ets/chat/mcp_config.ts';
import {
  serializeMcpServerConfigList, parseMcpServerConfigList,
} from '../main/ets/chat/mcp_config_serialize.ts';
import { parseMcpServersFromJson } from '../main/ets/chat/mcp_import.ts';
import {
  createMemoryKeyValueStore, saveMcpServers, loadMcpServers, MCP_SERVERS_KEY,
} from '../main/ets/chat/kv_store.ts';

// ===== 模型默认值(McpConfig.kt:9-25) =====

test('McpTool 默认值:enable=true/needsApproval=true(fail-closed 逐字)/description·inputSchema=null', () => {
  const t = makeMcpTool({ name: 'read' });
  assert.equal(t.enable, true);
  assert.equal(t.name, 'read');
  assert.equal(t.description, null);
  assert.equal(t.inputSchema, null);
  assert.equal(t.needsApproval, true);
});

// ===== 线格式(kotlinx 逐字) =====

test('golden: sse 服务器线格式(鉴别名首键/声明序/encodeDefaults/Pair first-second)', () => {
  const srv = makeMcpSseServer({
    id: 'srv-1',
    commonOptions: makeMcpCommonOptions({
      enable: false,
      name: 'fs',
      headers: [['Authorization', 'Bearer x'], ['X-A', '1']],
      tools: [
        makeMcpTool({ name: 'read' }),
        makeMcpTool({
          enable: false, name: 'write', description: 'd',
          inputSchema: { type: 'object', properties: { p: { type: 'string' } }, required: ['p'] },
          needsApproval: false,
        }),
      ],
    }),
    url: 'https://mcp.example.com/sse',
  });
  const json = serializeMcpServerConfigList([srv]);
  const expected =
    '[{"type":"sse","id":"srv-1",' +
    '"commonOptions":{"enable":false,"name":"fs",' +
    '"headers":[{"first":"Authorization","second":"Bearer x"},{"first":"X-A","second":"1"}],' +
    '"tools":[' +
    '{"enable":true,"name":"read","needsApproval":true},' +
    '{"enable":false,"name":"write","description":"d",' +
    '"inputSchema":{"type":"object","properties":{"p":{"type":"string"}},"required":["p"]},' +
    '"needsApproval":false}' +
    ']},' +
    '"url":"https://mcp.example.com/sse"}]';
  assert.equal(json, expected);
});

test('golden: explicitNulls=false — description/inputSchema 为 null 时省略', () => {
  const srv = makeMcpStreamableHttpServer({
    id: 's', commonOptions: makeMcpCommonOptions({ tools: [makeMcpTool({ name: 't' })] }),
  });
  const json = serializeMcpServerConfigList([srv]);
  assert.ok(!json.includes('description'));
  assert.ok(!json.includes('inputSchema'));
});

test('round-trip: 两型混合列表序列化→解析深等', () => {
  const list = [
    makeMcpSseServer({
      id: 'a', url: 'https://a',
      commonOptions: makeMcpCommonOptions({ name: 'na', headers: [['k', 'v']] }),
    }),
    makeMcpStreamableHttpServer({
      id: 'b', url: 'https://b',
      commonOptions: makeMcpCommonOptions({
        enable: false, tools: [makeMcpTool({ name: 'x', description: 'dd' })],
      }),
    }),
  ];
  assert.deepEqual(parseMcpServerConfigList(serializeMcpServerConfigList(list)), list);
});

test('parse 容忍:未知键忽略(ignoreUnknownKeys)/缺字段落默认(id 缺 → 随机)', () => {
  const raw = '[{"type":"streamable_http","commonOptions":{"name":"n","extra":1},' +
    '"url":"u","unknown":true}]';
  const list = parseMcpServerConfigList(raw);
  assert.equal(list.length, 1);
  assert.equal(list[0].kind, 'streamable_http');
  assert.match(list[0].id, /^[0-9a-f-]{36}$/);
  assert.equal(list[0].commonOptions.name, 'n');
  assert.equal(list[0].commonOptions.enable, true);
  assert.equal(list[0].commonOptions.tools.length, 0);
});

test('parse:未知鉴别名 → 抛(kotlinx 缺 subclass 同抛)', () => {
  assert.throws(
    () => parseMcpServerConfigList('[{"type":"ws","id":"x","url":"u"}]'),
    /McpServerConfig/);
});

test('kv_store:save/load 往返;无键 → null', async () => {
  const kv = createMemoryKeyValueStore();
  assert.equal(await loadMcpServers(kv), null);
  const list = [makeMcpSseServer({ id: 'k1', url: 'https://k' })];
  await saveMcpServers(kv, list);
  assert.deepEqual(await loadMcpServers(kv), list);
  assert.ok(kv.entries.get(MCP_SERVERS_KEY) !== undefined);
});

// ===== McpStatus(McpStatus.kt) =====

// ===== McpImportParser(McpImportParser.kt 全文) =====

test('import:mcpServers 缺失 → [];两型解析/headers/未知 type 落 streamable_http', () => {
  assert.deepEqual(parseMcpServersFromJson('{}'), []);
  const json = JSON.stringify({
    mcpServers: {
      fs: { type: 'sse', url: 'https://fs/sse', headers: { A: '1', B: '2' } },
      web: { type: 'streamable_http', url: 'https://web/mcp' },
      odd: { type: 'future_kind', url: 'https://odd' },
    },
  });
  const list = parseMcpServersFromJson(json);
  assert.equal(list.length, 3);
  assert.equal(list[0].kind, 'sse');
  assert.equal(list[0].commonOptions.name, 'fs');
  assert.deepEqual(list[0].commonOptions.headers, [['A', '1'], ['B', '2']]);
  assert.equal(list[1].kind, 'streamable_http');
  assert.equal(list[1].commonOptions.name, 'web');
  assert.equal(list[2].kind, 'streamable_http'); // else 分支逐字
  assert.equal(list[2].url, 'https://odd');
  // enable 默认 true/tools 空/id 随机
  assert.equal(list[0].commonOptions.enable, true);
  assert.equal(list[0].commonOptions.tools.length, 0);
  assert.match(list[0].id, /^[0-9a-f-]{36}$/);
});

test('import:type 缺省 → streamable_http;url 缺失/非串 → 跳过(mapNotNull)', () => {
  const json = JSON.stringify({
    mcpServers: {
      ok: { url: 'https://ok' },
      noUrl: { type: 'sse' },
      nullUrl: { url: null },
    },
  });
  const list = parseMcpServersFromJson(json);
  assert.equal(list.length, 1);
  assert.equal(list[0].kind, 'streamable_http');
  assert.equal(list[0].commonOptions.name, 'ok');
});

test('import:headers 值非串原始值 → 字面串(contentOrNull);对象 → ""', () => {
  const json = JSON.stringify({
    mcpServers: {
      h: { url: 'u', headers: { s: 'v', n: 123, b: true, o: { x: 1 } } },
    },
  });
  const list = parseMcpServersFromJson(json);
  assert.deepEqual(list[0].commonOptions.headers, [
    ['s', 'v'], ['n', '123'], ['b', 'true'], ['o', ''],
  ]);
});

test('import:root 非对象/mcpServers 非对象 → 抛(jsonObject 语义)', () => {
  assert.throws(() => parseMcpServersFromJson('[1]'));
  assert.throws(() => parseMcpServersFromJson('{"mcpServers":[1]}'));
});
