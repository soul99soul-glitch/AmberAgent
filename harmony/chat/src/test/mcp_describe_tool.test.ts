import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createMcpDescribeTool } from '../main/ets/chat/mcp_describe_tool.ts';
import type { McpSettingsPort } from '../main/ets/chat/mcp_manager.ts';
import {
  makeMcpCommonOptions, makeMcpTool, makeMcpStreamableHttpServer,
} from '../main/ets/chat/mcp_config.ts';
import type { McpServerConfig, McpTool } from '../main/ets/chat/mcp_config.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';

const server = (id: string, name: string, tools: McpTool[]): McpServerConfig =>
  makeMcpStreamableHttpServer({ id, url: 'https://x.example/mcp', commonOptions: makeMcpCommonOptions({ name, tools }) });

const run = async (servers: McpServerConfig[], input: JsonObject): Promise<JsonObject> => {
  const settings: McpSettingsPort = {
    getMcpServers: () => servers, getCurrentAssistantMcpServerIds: () => [],
    updateMcpServers: () => { throw new Error('must not write'); }, subscribeMcpServers: () => () => {},
  };
  const parts = await createMcpDescribeTool({ settings }).execute(input);
  return JSON.parse((parts[0] as { text: string }).text);
};

const search = makeMcpTool({
  name: 'search_doc', description: 'Search documents', needsApproval: true,
  inputSchema: { type: 'object', properties: { q: { type: 'string' }, limit: { type: 'integer' } }, required: ['q'] },
});

test('returns description and full input schema by server name', async () => {
  const out = await run([server('s1', 'docs', [search, makeMcpTool({ name: 'noschema', enable: false })])],
    { server: 'docs', tool: 'search_doc' });
  assert.equal(out.ok, true);
  assert.equal(out.server_id, 's1');
  assert.equal(out.description, 'Search documents');
  assert.equal(out.enabled, true);
  assert.equal(out.needs_approval, true);
  assert.equal(out.untrusted, true);
  assert.deepEqual(out.input_schema, {
    type: 'object', properties: { q: { type: 'string' }, limit: { type: 'integer' } }, required: ['q'],
  });
});

test('accepts server id and mcp_call_tool style aliases; null schema stays null', async () => {
  const servers = [server('s1', 'docs', [makeMcpTool({ name: 'ping', enable: false })])];
  const out = await run(servers, { server_id: 's1', tool_name: 'ping' });
  assert.equal(out.ok, true);
  assert.equal(out.input_schema, null);
  assert.equal(out.enabled, false);
});

test('prefers root jsonSchema when present', async () => {
  const jsonSchema = { type: 'object', properties: { a: { $ref: '#/$defs/A' } }, $defs: { A: { type: 'string' } } };
  const tool = makeMcpTool({ name: 't', inputSchema: { type: 'object', properties: {}, required: null, jsonSchema } });
  const out = await run([server('s1', 'docs', [tool])], { server: 'docs', tool: 't' });
  assert.deepEqual(out.input_schema, jsonSchema);
});

test('unknown server and tool return sorted valid lists without throwing', async () => {
  const servers = [server('s2', 'zeta', []), server('s1', 'alpha', [makeMcpTool({ name: 'b' }), makeMcpTool({ name: 'a' })])];
  const noServer = await run(servers, { server: 'nope', tool: 'a' });
  assert.equal(noServer.ok, false);
  assert.deepEqual(noServer.valid_servers, ['alpha', 'zeta']);
  const noTool = await run(servers, { server: 'alpha', tool: 'c' });
  assert.equal(noTool.ok, false);
  assert.deepEqual(noTool.valid_tools, ['a', 'b']);
  assert.equal((await run(servers, {})).ok, false);
});

test('duplicate server names are ambiguous unless addressed by id', async () => {
  const servers = [server('s1', 'dup', [makeMcpTool({ name: 'x' })]), server('s2', 'dup', [makeMcpTool({ name: 'x' })])];
  const amb = await run(servers, { server: 'dup', tool: 'x' });
  assert.equal(amb.ok, false);
  assert.deepEqual(amb.server_ids, ['s1', 's2']);
  assert.equal((await run(servers, { server: 's2', tool: 'x' })).ok, true);
});

test('tool is read-only (no approval) and requires server and tool', () => {
  const tool = createMcpDescribeTool({ settings: {} as McpSettingsPort });
  assert.equal(tool.name, 'mcp_describe_tool');
  assert.equal(tool.needsApproval, false);
  assert.deepEqual(tool.parameters()?.required, ['server', 'tool']);
});
