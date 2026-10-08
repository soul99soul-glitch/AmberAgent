import test from 'node:test';
import assert from 'node:assert/strict';
import type { JsonObject } from '../main/ets/chat/json.ts';
import { McpManager } from '../main/ets/chat/mcp_manager.ts';
import type { McpSettingsPort } from '../main/ets/chat/mcp_manager.ts';
import type { McpHttpPort, McpPostStreamResponse } from '../main/ets/chat/mcp_transports.ts';
import type { McpServerConfig } from '../main/ets/chat/mcp_config.ts';
import { makeMcpStreamableHttpServer, makeMcpCommonOptions, makeMcpTool } from '../main/ets/chat/mcp_config.ts';
import { serializeMcpServerConfigList, parseMcpServerConfigList } from '../main/ets/chat/mcp_config_serialize.ts';
import { createMcpServerTools, createMcpManagementTools } from '../main/ets/chat/mcp_tools.ts';
import { toChatToolDefinition } from '../main/ets/chat/tool.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import { makeChatModel, makeTextGenerationParams } from '../main/ets/chat/provider_model.ts';
import { buildChatCompletionRequest } from '../main/ets/chat/openai_request.ts';
import { buildClaudeMessageRequest } from '../main/ets/chat/claude_request.ts';
import { buildGoogleCompletionRequestBody } from '../main/ets/chat/google_request.ts';
import { buildResponsesRequestBody } from '../main/ets/chat/openai_responses_request.ts';
import { makeUserMessage } from '../main/ets/chat/message.ts';
import { makeProviderSettingOpenAIVariant, makeProviderSettingClaude } from '../main/ets/chat/provider_settings.ts';
import { createToolsListTool, createToolSearchTool } from '../main/ets/chat/builtin_introspection_tools.ts';
import { createToolRegistry } from '../main/ets/chat/tool_registry.ts';

const schema: JsonObject = { type: 'object', description: 'Exact root schema',
  properties: { q: { type: 'string', enum: ['a', 'b'] } }, required: ['q'], additionalProperties: false,
  $defs: { query: { type: 'string' } }, oneOf: [{ required: ['q'] }], minProperties: 1 };
const annotations: JsonObject = { title: 'Read query', readOnlyHint: true, destructiveHint: false, idempotentHint: true };
const opaqueCursor = '  c/+%雪==  ';
const firstPage: JsonObject = { tools: [{ name: 'first', description: 'first-page', inputSchema: schema, annotations }], nextCursor: opaqueCursor };
const secondPage: JsonObject = { tools: [{ name: 'second', inputSchema: { type: 'object', properties: {} } }] };
const initResult: JsonObject = { protocolVersion: '2025-06-18', capabilities: { tools: {} }, serverInfo: { name: 'fixture', version: '1' } };
const response = (body: JsonObject): McpPostStreamResponse => ({ status: 200, statusDescription: '', headers: [],
  contentType: 'application/json', bodyText: JSON.stringify(body), lines: null });

const harness = (page2: JsonObject = secondPage) => {
  const requests: JsonObject[] = [];
  const port: McpHttpPort = {
    request: async () => ({ status: 200, statusDescription: '', headers: [], contentType: null, bodyText: '' }),
    openSse: async () => { throw new Error('fixture should not open GET SSE'); },
    postStream: async (_url, _headers, body) => {
      const req = JSON.parse(body) as JsonObject;
      requests.push(req);
      if (req.method === 'initialize') return response({ jsonrpc: '2.0', id: req.id, result: initResult });
      if (req.method === 'tools/list') {
        const page = req.params === undefined ? firstPage : page2;
        return response({ jsonrpc: '2.0', id: req.id, ...(page.error === undefined ? { result: page } : page) });
      }
      return { status: 200, statusDescription: '', headers: [], contentType: null, bodyText: '', lines: null };
    },
  };
  const server = makeMcpStreamableHttpServer({ id: 'fixture-server', url: 'https://fixture/mcp',
    commonOptions: makeMcpCommonOptions({ tools: [makeMcpTool({ name: 'old' })] }) });
  let servers: McpServerConfig[] = [server];
  const settings: McpSettingsPort = {
    getMcpServers: () => servers, getCurrentAssistantMcpServerIds: () => [server.id],
    updateMcpServers: updater => { servers = updater(servers); }, subscribeMcpServers: () => () => {},
  };
  const manager = new McpManager({ http: port, settings, files: {
    saveUploadFromBytes: async () => 'fixture', extensionFromMimeType: () => null,
  } });
  return { requests, port, manager, settings, server };
};

test('MCP pages reach persisted catalog and full schemas reach actual provider requests', async () => {
  const h = harness();
  await h.manager.addClient(h.server);
  try {
    const list = h.requests.filter(req => req.method === 'tools/list');
    assert.equal(list.length, 2);
    assert.equal(list[0].params, undefined);
    assert.deepEqual(list[1].params, { cursor: opaqueCursor });
    const restored = parseMcpServerConfigList(serializeMcpServerConfigList(h.settings.getMcpServers()));
    assert.deepEqual(restored[0].commonOptions.tools.map(tool => tool.name), ['first', 'second']);
    assert.deepEqual(JSON.parse(serializeMcpServerConfigList(restored))[0].commonOptions.tools[0].inputSchema, schema);
    assert.deepEqual(JSON.parse(serializeMcpServerConfigList(restored))[0].commonOptions.tools[0].annotations, annotations);
    h.settings.updateMcpServers(() => restored);
    const tool = createMcpServerTools({ manager: h.manager })[0];
    const definition = toChatToolDefinition(tool);
    assert.deepEqual(definition.parameters, schema);
    const model = makeChatModel({ modelId: 'fixture-model', abilities: ['tool'] });
    const params = makeTextGenerationParams({ model, tools: [definition] });
    const messages = [makeUserMessage('query')];
    const openai = buildChatCompletionRequest({ messages, params, setting: makeProviderSettingOpenAIVariant({}), stream: false });
    assert.deepEqual(((openai.tools as JsonObject[])[0].function as JsonObject).parameters, schema);
    const responses = buildResponsesRequestBody({ messages, params, setting: makeProviderSettingOpenAIVariant({}), stream: false });
    assert.deepEqual((responses.tools as JsonObject[])[0].parameters, schema);
    const claude = buildClaudeMessageRequest({ messages, params, setting: makeProviderSettingClaude({}), stream: false });
    assert.deepEqual((claude.tools as JsonObject[])[0].input_schema, schema);
    const google = buildGoogleCompletionRequestBody({ messages, params });
    const googleDeclaration = ((google.tools as JsonObject[])[0].functionDeclarations as JsonObject[])[0];
    assert.deepEqual(googleDeclaration.parametersJsonSchema, schema);
    assert.equal(googleDeclaration.parameters, undefined, 'Gemini JSON Schema and OpenAPI schema fields are mutually exclusive');
    const management = createMcpManagementTools({ settings: h.settings, manager: h.manager }).find(t => t.name === 'mcp_list')!;
    const output = await management.execute({ include_tools: true, include_schema: true });
    assert.ok(output[0].type === 'text' && output[0].text.includes('readOnlyHint'));
    assert.ok(output[0].type === 'text');
    const managed = JSON.parse(output[0].text) as { servers: Array<{ tools: Array<{ schema: string }> }> };
    assert.deepEqual(JSON.parse(managed.servers[0].tools[0].schema), schema);
    assert.equal(restored[0].commonOptions.tools[0].needsApproval, true, 'server hints do not change saved approval policy');
  } finally { h.manager.dispose(); }
});

test('MCP missing tool array in a later page cannot wipe the saved catalog', async () => {
  const h = harness({ result: {} });
  await h.manager.addClient(h.server);
  try {
    assert.equal(h.manager.getStatus(h.server).kind, 'error');
    assert.deepEqual(h.settings.getMcpServers()[0].commonOptions.tools.map(tool => tool.name), ['old']);
  } finally { h.manager.dispose(); }
});

test('MCP failed later page retains entire old catalog and reports error', async () => {
  const h = harness({ error: { code: -32603, message: 'second page failed' } });
  await h.manager.addClient(h.server);
  try {
    assert.equal(h.manager.getStatus(h.server).kind, 'error');
    assert.deepEqual(h.settings.getMcpServers()[0].commonOptions.tools.map(tool => tool.name), ['old']);
  } finally { h.manager.dispose(); }
});

test('MCP repeated cursor rejects pagination instead of replacing catalog with duplicate first page', async () => {
  const h = harness(firstPage);
  await h.manager.addClient(h.server);
  try {
    assert.equal(h.manager.getStatus(h.server).kind, 'error');
    assert.deepEqual(h.settings.getMcpServers()[0].commonOptions.tools.map(tool => tool.name), ['old']);
    assert.equal(h.requests.filter(req => req.method === 'tools/list').length, 2);
  } finally { h.manager.dispose(); }
});

test('MCP removal while later page is pending retains old catalog and closes request', async () => {
  const h = harness();
  let reached: () => void = () => {};
  const secondStarted = new Promise<void>(resolve => { reached = resolve; });
  const original = h.port.postStream;
  h.port.postStream = async (...args) => {
    const req = JSON.parse(args[2]) as JsonObject;
    if (req.method === 'tools/list' && req.params !== undefined) {
      reached();
      return new Promise(() => {});
    }
    return original(...args);
  };
  const adding = h.manager.addClient(h.server);
  const didStart = await Promise.race([secondStarted.then(() => true), adding.then(() => false)]);
  assert.equal(didStart, true);
  await h.manager.removeClient(h.server);
  await adding;
  assert.deepEqual(h.settings.getMcpServers()[0].commonOptions.tools.map(tool => tool.name), ['old']);
  h.manager.dispose();
});

test('MCP inline SSE notification/unrelated response/CRLF/EOF resolves only correct catalog pages', async () => {
  const h = harness();
  const original = h.port.postStream;
  h.port.postStream = async (...args) => {
    const req = JSON.parse(args[2]) as JsonObject;
    if (req.method !== 'tools/list') return original(...args);
    const page = req.params === undefined ? firstPage : secondPage;
    h.requests.push(req);
    const lines = [
      'data: {"jsonrpc":"2.0","method":"notifications/tools/list_changed"}\r', '\r',
      'data: {"jsonrpc":"2.0","id":"unrelated","result":{"tools":[{"name":"wrong"}]}}\r', '\r',
      `data: ${JSON.stringify({ jsonrpc: '2.0', id: req.id, result: page })}\r`,
    ];
    return { status: 200, statusDescription: '', headers: [], contentType: 'text/event-stream', bodyText: '',
      lines: { readLine: async () => lines.shift() ?? null, cancel: async () => {} } };
  };
  await h.manager.addClient(h.server);
  try {
    assert.equal(h.manager.getStatus(h.server).kind, 'connected');
    assert.deepEqual(h.settings.getMcpServers()[0].commonOptions.tools.map(tool => tool.name), ['first', 'second']);
    assert.equal(h.requests.filter(req => req.method === 'tools/list').length, 2);
  } finally { h.manager.dispose(); }
});

test('MCP explicit resync failure retains previous complete catalog and marks error instead of connecting forever', async () => {
  const h = harness();
  await h.manager.addClient(h.server);
  const previous = serializeMcpServerConfigList(h.settings.getMcpServers());
  const original = h.port.postStream;
  h.port.postStream = async (...args) => {
    const req = JSON.parse(args[2]) as JsonObject;
    if (req.method === 'tools/list' && req.params !== undefined) {
      return response({ jsonrpc: '2.0', id: req.id, error: { code: -32603, message: 'refresh page failed' } });
    }
    return original(...args);
  };
  try {
    await assert.rejects(h.manager.sync(h.server), /refresh page failed/);
    assert.equal(h.manager.getStatus(h.server).kind, 'error');
    assert.equal(serializeMcpServerConfigList(h.settings.getMcpServers()), previous);
  } finally { h.manager.dispose(); }
});

test('MCP explicit sync reconnect initialize failure marks error and leaves saved catalog intact', async () => {
  const h = harness();
  const original = h.port.postStream;
  h.port.postStream = async (...args) => {
    const req = JSON.parse(args[2]) as JsonObject;
    if (req.method === 'initialize') throw new Error('reconnect initialize failed');
    return original(...args);
  };
  await h.manager.addClient(h.server);
  try {
    assert.equal(h.manager.getStatus(h.server).kind, 'error');
    await assert.rejects(h.manager.sync(h.server), /reconnect initialize failed/);
    assert.equal(h.manager.getStatus(h.server).kind, 'error');
    assert.deepEqual(h.settings.getMcpServers()[0].commonOptions.tools.map(tool => tool.name), ['old']);
  } finally { h.manager.dispose(); }
});

test('MCP full root schema survives lazy tool discovery and catalog inspection', async () => {
  const h = harness();
  await h.manager.addClient(h.server);
  try {
    const tools = createMcpServerTools({ manager: h.manager });
    const registry = createToolRegistry(tools);
    const definition = toChatToolDefinition(registry.tools()[0]);
    const projected = definition.parameters;
    assert.deepEqual(projected.$defs, schema.$defs);
    assert.deepEqual(projected.oneOf, schema.oneOf);
    assert.equal(projected.additionalProperties, false);
    assert.equal(((projected.properties as JsonObject).display_title as JsonObject).type, 'string');
    const model = makeChatModel({ modelId: 'fixture-model', abilities: ['tool'] });
    const params = makeTextGenerationParams({ model, tools: [definition] });
    const messages = [makeUserMessage('query')];
    const openai = buildChatCompletionRequest({ messages, params, setting: makeProviderSettingOpenAIVariant({}), stream: false });
    assert.deepEqual(((openai.tools as JsonObject[])[0].function as JsonObject).parameters, projected);
    const responses = buildResponsesRequestBody({ messages, params, setting: makeProviderSettingOpenAIVariant({}), stream: false });
    assert.deepEqual((responses.tools as JsonObject[])[0].parameters, projected);
    const claude = buildClaudeMessageRequest({ messages, params, setting: makeProviderSettingClaude({}), stream: false });
    assert.deepEqual((claude.tools as JsonObject[])[0].input_schema, projected);
    const google = buildGoogleCompletionRequestBody({ messages, params });
    const declaration = ((google.tools as JsonObject[])[0].functionDeclarations as JsonObject[])[0];
    assert.deepEqual(declaration.parametersJsonSchema, projected);
    assert.equal(declaration.parameters, undefined);
    const saved = JSON.parse(serializeMcpServerConfigList(h.settings.getMcpServers()));
    assert.deepEqual(saved[0].commonOptions.tools[0].inputSchema, schema, 'registry projection does not mutate saved MCP schema');
    const actions: Array<{ tool: AgentTool; input: JsonObject }> = [
      { tool: createToolsListTool(registry), input: { include_schema: true } },
      { tool: createToolSearchTool(registry), input: { query: 'first', limit: 1 } },
    ];
    for (const action of actions) {
      const output = await action.tool.execute(action.input);
      assert.ok(output[0].type === 'text');
      assert.ok(output[0].text.includes('$defs'));
      assert.ok(output[0].text.includes('oneOf'));
      assert.ok(output[0].text.includes('additionalProperties'));
    }
  } finally { h.manager.dispose(); }
});
