import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';
import * as chatDomain from '../main/ets/index.ts';
import type { Conversation } from '../main/ets/chat/conversation.ts';
import { makeConversation, currentMessages, toMessageNode } from '../main/ets/chat/conversation.ts';
import type { ConversationStore, ChatStreamProvider, ChatTurnDeps } from '../main/ets/chat/chat_turn.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import type { MessageChunk, UIMessagePart, UIMessagePartTool } from '../main/ets/chat/message.ts';
import { makeUIMessage, makeUserMessage } from '../main/ets/chat/message.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import { makeAgentTool, makeInputSchemaObj } from '../main/ets/chat/tool.ts';
import type { ChatToolDefinition } from '../main/ets/chat/provider_model.ts';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';
import { AgentToolDispatcher } from '../main/ets/chat/tool_dispatcher.ts';
import { createToolRegistry } from '../main/ets/chat/tool_registry.ts';
import { createToolSearchTool } from '../main/ets/chat/builtin_introspection_tools.ts';
import { runToolLoopContinuation, runChatTurnWithTools } from '../main/ets/chat/tool_loop.ts';
import type { ToolLoopOptions } from '../main/ets/chat/tool_loop.ts';
import { createRecipeLoopAdapter } from '../main/ets/chat/recipes/runner.ts';
import { createRecipeTools } from '../main/ets/chat/recipes/tools.ts';
import { canonicalRecipeJSON } from '../main/ets/chat/recipes/validation.ts';
import type { RecipeStore } from '../main/ets/chat/recipes/ports.ts';
import type { RecipeDescriptor, RecipeManifest } from '../main/ets/chat/recipes/models.ts';
import type { InstalledPlugin, PluginHealth, PluginPackage, PluginRunCheckpoint } from '../main/ets/chat/plugins/models.ts';
import type { PluginJsPort, PluginLoopAdapter, PluginStore } from '../main/ets/chat/plugins/ports.ts';
import { createPluginLoopAdapter, createPluginRun } from '../main/ets/chat/plugins/runner.ts';
import { createPluginTools } from '../main/ets/chat/plugins/tools.ts';
import { preparePluginPackage } from '../main/ets/chat/plugins/validation.ts';
import { McpManager } from '../main/ets/chat/mcp_manager.ts';
import { createMcpServerTools } from '../main/ets/chat/mcp_tools.ts';
import { makeMcpCommonOptions, makeMcpStreamableHttpServer, makeMcpTool } from '../main/ets/chat/mcp_config.ts';
import type { McpServerConfig } from '../main/ets/chat/mcp_config.ts';
import type { McpHttpPort } from '../main/ets/chat/mcp_transports.ts';
import { McpSseOpenError } from '../main/ets/chat/mcp_transports.ts';

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
const output = (value: JsonObject): UIMessagePart[] => [{ type: 'text', text: JSON.stringify(value), metadata: null }];
const result = (part: UIMessagePartTool): JsonObject => JSON.parse((part.output[0] as { text: string }).text) as JsonObject;
const call = (id: string, name: string, input: JsonObject, metadata: JsonObject | null = null): UIMessagePartTool => ({
  type: 'tool', toolCallId: id, toolName: name, input: JSON.stringify(input), output: [], approvalState: { type: 'auto' }, metadata,
});
const partOf = (conv: Conversation, id: string): UIMessagePartTool => {
  const part = currentMessages(conv).flatMap((message) => message.parts).find((part) => part.type === 'tool' && part.toolCallId === id);
  assert.ok(part && part.type === 'tool', 'Missing tool part: ' + id); return part;
};
const checkpoint = (part: UIMessagePartTool): PluginRunCheckpoint => part.metadata!['plugin_v1'] as unknown as PluginRunCheckpoint;
const approve = (conv: Conversation, id: string): Conversation => ({ ...conv, messageNodes: conv.messageNodes.map((node) => ({ ...node,
  messages: node.messages.map((message, index) => index !== node.selectIndex ? message : { ...message,
    parts: message.parts.map((part) => part.type === 'tool' && part.toolCallId === id ? { ...part, approvalState: { type: 'approved' as const } } : part),
  }),
})) });
const conversation = (parts: UIMessagePartTool[]): Conversation => makeConversation('plugin-fixture', [
  toMessageNode(makeUserMessage('Run the requested plugin')), toMessageNode(makeUIMessage('assistant', parts)),
]);
const scripted = (rounds: UIMessagePart[][]) => {
  const state = { streams: 0, schemas: [] as ChatToolDefinition[][] };
  const provider: ChatStreamProvider = { streamText: async (_messages, onChunk) => {
    const index = state.streams++; assert.ok(index < rounds.length, 'Unexpected model round');
    const chunk: MessageChunk = { id: 'fixture', model: 'fixture-model', usage: null,
      choices: [{ index: 0, delta: makeUIMessage('assistant', rounds[index]!), message: null, finishReason: 'unknown' }] };
    onChunk(chunk);
  } };
  return { state, provider, factory: (defs: ChatToolDefinition[]) => { state.schemas.push(clone(defs)); return provider; } };
};
const deps = (provider: ChatStreamProvider, store: ConversationStore): ChatTurnDeps => ({
  assistant: makeAssistant({}), provider, store, inputTransformers: [], outputTransformers: [],
});
const packageOf = (member: JsonObject | JsonObject[], primitives: AgentTool[], scripts: Record<string, string> = {}): Promise<PluginPackage> => {
  const manifest: JsonObject = { schema: 'amber.plugin.v1', id: 'sample', name: 'Sample', version: '1', description: 'Loop fixture',
    tools: Array.isArray(member) ? member : [member], capabilities: { workspaceReadPrefixes: ['/workspace/data'], workspaceWritePrefixes: ['/workspace/data'], networkDomains: [], webMountActions: [] }, backgroundAllowed: false };
  const file = (path: string, text: string) => ({ path, data: Array.from(Buffer.from(text)) });
  return preparePluginPackage([file('plugin.json', JSON.stringify(manifest)), ...Object.entries(scripts).map(([path, text]) => file(path, text))], primitives,
    { sha256: async (bytes) => createHash('sha256').update(bytes).digest('hex') });
};
const persisted = (candidate: PluginPackage, initiallyInstalled = false, events: string[] = []) => {
  const trust = { tier: 'local_unsigned' as const, publisherTrusted: false, keyId: null, fingerprint: null, signature: null };
  const health = (): PluginHealth => ({ pluginId: candidate.manifest.id, packageHash: candidate.hash, consecutiveFailures: 0,
    quarantinedAt: null, quarantineReason: null, diagnostics: [] });
  const entry = (enabled: boolean): InstalledPlugin => ({ id: candidate.manifest.id, currentHash: candidate.hash, package: clone(candidate),
    configuredEnabled: enabled, enabled, trust, health: health(), errorCode: null, errorMessage: null });
  const state = { installed: initiallyInstalled ? [entry(true)] : [], saved: [] as Conversation[], applies: 0, successes: 0, failures: 0 };
  const store: PluginStore = {
    listInstalled: async () => clone(state.installed), readPackage: async () => ({ candidate: clone(candidate), trust }),
    prepareImport: async (source, _primitives, enable) => ({ source, candidate: clone(candidate), trust,
      baseHash: state.installed[0]?.currentHash ?? null, permissionExpanded: false, permissionDiff: [], enable }),
    applyImport: async (preview) => {
      assert.equal(preview.candidate.hash, candidate.hash); assert.equal(preview.baseHash, state.installed[0]?.currentHash ?? null);
      state.applies++; events.push('import'); state.installed = [entry(preview.enable)];
      return { id: candidate.manifest.id, hash: candidate.hash, changed: true, enabled: preview.enable, permissionExpanded: false, trust };
    },
    setEnabled: async (id, hash, enabled) => { assert.equal(id, candidate.manifest.id); assert.equal(hash, candidate.hash);
      state.installed[0]!.enabled = enabled; state.installed[0]!.configuredEnabled = enabled; },
    remove: async (id, hash) => { assert.equal(id, candidate.manifest.id); assert.equal(hash, candidate.hash); state.installed = []; },
    recordSuccess: async () => { state.successes++; return health(); }, recordFailure: async () => { state.failures++; return health(); },
    rollback: async () => { throw new Error('unused rollback'); }, restore: async () => { throw new Error('unused restore'); },
    exportArchive: async () => { throw new Error('unused export'); }, listTrustedKeys: async () => [],
    addTrustedKey: async () => { throw new Error('unused trust mutation'); }, removeTrustedKey: async () => { throw new Error('unused trust mutation'); },
  };
  const conversations: ConversationStore = { save: async (conv) => { state.saved.push(clone(conv)); } };
  return { state, store, conversations, entry };
};
const runtime = (store: PluginStore, installed: InstalledPlugin[], primitives: AgentTool[], js: PluginJsPort) => ({
  store, installed, js, http: { execute: async () => { throw new Error('unused HTTP backend'); } },
  webMount: { withScope: async <T>(_domains: string[], operation: () => Promise<T>): Promise<T> => operation() },
  resolveMcpPrimitive: (serverId: string, toolName: string): AgentTool | null => primitives.find((tool) =>
    tool.mcpTarget?.serverId === serverId && tool.mcpTarget.toolName === toolName) ?? null,
});
const unusedJS: PluginJsPort = { start: async () => { throw new Error('unexpected JS start'); }, reply: async () => { throw new Error('unexpected JS reply'); },
  reject: async () => { throw new Error('unexpected JS reject'); }, cancel: () => {}, hasSession: () => false };

test('manual plugin preserves its JS session through two exact approvals, saves started before effects and never streams', async () => {
  const writes: JsonObject[] = []; let captured = 'original';
  let f: ReturnType<typeof persisted>;
  const write = makeAgentTool({ name: 'file_write', description: 'Write Workspace text', needsApproval: true, allowsAutoApproval: false,
    parameters: () => makeInputSchemaObj({ path: { type: 'string' }, content: { type: 'string' } }, ['path', 'content']),
    execute: async (input) => {
      const durable = checkpoint(partOf(f.state.saved.at(-1)!, 'manual'));
      assert.equal(durable.phase, 'started'); assert.equal(durable.pendingStep!.input, JSON.stringify(input));
      assert.equal(durable.pendingStep!.metadata!['captured_target'], writes.length === 0 ? 'original' : 'changed');
      writes.push(input as JsonObject); return output({ path: (input as JsonObject)['path']! });
    } });
  const primitives = [write]; const source = 'tools.file_write({path:"/workspace/data/one",content:input.value});tools.file_write({path:"/workspace/data/two",content:input.value});return null;';
  const pkg = await packageOf({ name: 'run', script: 'scripts/run.js', host_tools: ['file_write'], inputs: { value: 'string' } }, primitives,
    { 'scripts/run.js': source });
  f = persisted(pkg, true); const model = scripted([]); let starts = 0, replies = 0, sessionId = '', alive = false;
  const js: PluginJsPort = {
    start: async (request) => { starts++; sessionId = request.executionId; alive = true; assert.equal(request.source, source); assert.equal(request.inputJson, '{"value":"你好"}');
      return { type: 'host_call', sessionId, callId: 'one', toolName: 'file_write', argsJson: '{"path":"/workspace/data/one","content":"你好"}' }; },
    reply: async (id, callId, resultJson) => { assert.equal(id, sessionId); replies++; assert.equal(callId, replies === 1 ? 'one' : 'two');
      assert.deepEqual(JSON.parse(resultJson), { path: replies === 1 ? '/workspace/data/one' : '/workspace/data/two' });
      if (replies === 1) return { type: 'host_call', sessionId, callId: 'two', toolName: 'file_write', argsJson: '{"path":"/workspace/data/two","content":"你好"}' };
      alive = false; return { type: 'finished', sessionId, resultJson: 'null', logs: [] }; },
    reject: async () => { throw new Error('unexpected JS reject'); }, cancel: () => { alive = false; }, hasSession: (id) => alive && id === sessionId,
  };
  const descriptor = pkg.tools[0]!; const pinned = createPluginRun(descriptor, { value: '你好' });
  const parent = call('manual', descriptor.toolId, { value: '你好' }, { plugin_manual: true, plugin_v1: pinned as unknown as JsonValue });
  const loop: ToolLoopOptions = { tools: [...primitives, ...createPluginTools({ store: f.store, installed: f.state.installed, primitives })],
    pluginAdapter: createPluginLoopAdapter(runtime(f.store, f.state.installed, primitives, js)), manualToolCallId: 'manual',
    autoApprovedToolNames: ['file_write'], dispatcher: new AgentToolDispatcher({ hooks: [] }), makeProviderForStep: model.factory,
    captureInvocationMetadata: () => ({ captured_target: captured }) };
  let conv = await runToolLoopContinuation(conversation([parent]), deps(model.provider, f.conversations), loop);
  const first = checkpoint(partOf(conv, 'manual')); assert.equal(first.phase, 'awaiting_approval'); assert.equal(partOf(conv, 'manual').approvalState.type, 'pending');
  assert.deepEqual(writes, []); assert.equal(starts, 1); assert.equal(model.state.streams, 0);
  await f.store.setEnabled(pkg.manifest.id, pkg.hash, false); captured = 'changed';
  loop.tools = [...primitives, ...createPluginTools({ store: f.store, installed: f.state.installed, primitives })];
  loop.pluginAdapter = createPluginLoopAdapter(runtime(f.store, f.state.installed, primitives, js));
  conv = await runToolLoopContinuation(approve(conv, 'manual'), deps(model.provider, f.conversations), loop);
  const second = checkpoint(partOf(conv, 'manual')); assert.equal(second.phase, 'awaiting_approval'); assert.equal(writes.length, 1);
  assert.notEqual(second.pendingStep!.toolCallId, first.pendingStep!.toolCallId); assert.equal(second.executionId, pinned.executionId);
  assert.equal(second.descriptor.packageHash, pkg.hash); assert.equal(second.pendingStep!.metadata!['captured_target'], 'changed');
  captured = 'latest'; conv = await runToolLoopContinuation(approve(conv, 'manual'), deps(model.provider, f.conversations), loop);
  const final = partOf(conv, 'manual'); assert.equal(result(final)['status'], 'succeeded'); assert.equal(result(final)['result'], null);
  assert.equal(checkpoint(final).descriptor.packageHash, pkg.hash); assert.equal(checkpoint(final).executionId, pinned.executionId);
  assert.deepEqual(writes, [{ path: '/workspace/data/one', content: '你好' }, { path: '/workspace/data/two', content: '你好' }]);
  assert.equal(starts, 1); assert.equal(replies, 2); assert.equal(model.state.streams, 0); assert.deepEqual(partOf(f.state.saved.at(-1)!, 'manual'), final);
});

test('test/import mixed batch is human-gated; refreshed wrappers keep MCP identity, removal and child scope', async () => {
  const effects: string[] = []; const remoteCalls: string[] = []; let f: ReturnType<typeof persisted>; let mcpName = '';
  const memberSchema = makeInputSchemaObj({ amount: { type: 'number' } }, ['amount']);
  let servers: McpServerConfig[] = ['demo-server', 'demo_server'].map((id, index) => makeMcpStreamableHttpServer({ id, url: 'https://mcp.test/' + index,
    commonOptions: makeMcpCommonOptions({ name: id, tools: [makeMcpTool({ name: 'double', needsApproval: false, inputSchema: memberSchema })] }) }));
  const http: McpHttpPort = {
    request: async () => ({ status: 200, statusDescription: '', headers: [], contentType: null, bodyText: '' }),
    openSse: async () => { throw new McpSseOpenError('JSON-only fixture', 405, 'application/json'); },
    postStream: async (url, _headers, body) => {
      const request = JSON.parse(body) as JsonObject; let response: JsonObject;
      if (request['method'] === 'notifications/initialized') return { status: 202, statusDescription: '', headers: [], contentType: null, bodyText: '', lines: null };
      if (request['method'] === 'initialize') response = { protocolVersion: '2025-06-18', capabilities: { tools: {} }, serverInfo: { name: 'fixture', version: '1' } };
      else if (request['method'] === 'tools/list') response = { tools: [{ name: 'double', inputSchema: clone(memberSchema) as unknown as JsonObject }] };
      else {
        assert.equal(request['method'], 'tools/call'); const durable = currentMessages(f.state.saved.at(-1)!).flatMap((message) => message.parts)
          .filter((part) => part.type === 'tool').map((part) => part.metadata?.['plugin_v1'] as unknown as PluginRunCheckpoint | undefined).find((state) => state?.phase === 'started');
        assert.equal(durable?.pendingStep!.toolName, mcpName); assert.equal(url, 'https://mcp.test/1'); remoteCalls.push(url); effects.push('mcp');
        const input = (request['params'] as JsonObject)['arguments'] as JsonObject;
        response = { content: [{ type: 'text', text: JSON.stringify({ doubled: (input['amount'] as number) * 2 }) }], isError: false };
      }
      return { status: 200, statusDescription: '', headers: [], contentType: 'application/json', lines: null,
        bodyText: JSON.stringify({ jsonrpc: '2.0', id: request['id'], result: response }) };
    },
  };
  const manager = new McpManager({ http, settings: { getMcpServers: () => servers, getCurrentAssistantMcpServerIds: () => servers.map((server) => server.id),
    updateMcpServers: (update) => { servers = update(servers); }, subscribeMcpServers: () => () => {} },
    files: { saveUploadFromBytes: async () => { throw new Error('unused MCP image'); }, extensionFromMimeType: () => null } });
  try {
    for (const server of [...servers]) await manager.addClient(server);
    const mcpTools = createMcpServerTools({ manager, assistantIds: ['demo-server', 'demo_server'] });
    mcpName = mcpTools.find((tool) => tool.mcpTarget?.serverId === 'demo_server')!.name;
    assert.equal(mcpName, 'mcp__double__demo_server__2');
    const primitives = [...mcpTools, makeAgentTool({ name: 'file_read', description: 'Read text', execute: async () => { effects.push('recipe'); return output({ content: 'snapshot' }); } }),
      makeAgentTool({ name: 'file_write', description: 'Write text', needsApproval: true, allowsAutoApproval: false,
        execute: async () => { effects.push('sibling'); return output({ path: '/workspace/data/sibling' }); } })];
    const pkg = await packageOf({ name: 'double', remote: { kind: 'mcp', server: 'demo_server', tool: 'double' }, inputs: { amount: 'number' },
      output: 'object', output_schema: { type: 'object', properties: { doubled: { type: 'number' } }, required: ['doubled'], additionalProperties: false } }, primitives);
    f = persisted(pkg, false, effects); const wrapperName = pkg.tools[0]!.toolId;
    const manifest: RecipeManifest = { schema: 'amber.recipe.v1', name: 'read_snapshot', version: '1', description: 'Read snapshot', inputs: { path: 'string' },
      steps: [{ id: 'read', tool: 'file_read', arguments: { path: '${input.path}' } }], outputs: { content: '${step.read.output.content}' } };
    const canonicalJSON = canonicalRecipeJSON(manifest); const bytes = Buffer.from(canonicalJSON); const size = Buffer.alloc(8); size.writeBigUInt64BE(BigInt(bytes.length));
    const recipe: RecipeDescriptor = { manifest, canonicalJSON, hash: createHash('sha256').update('amber.recipe.package.v1\0').update(size).update(bytes).digest('hex') };
    const recipes = [{ descriptor: recipe, enabled: true }]; const recipeStore: RecipeStore = { listInstalled: async () => clone(recipes),
      prepareImport: async () => { throw new Error('unused Recipe import'); }, applyImport: async () => { throw new Error('unused Recipe import'); },
      setEnabled: async () => { throw new Error('unused Recipe mutation'); }, remove: async () => { throw new Error('unused Recipe mutation'); } };
    const model = scripted([
      [call('test', 'plugin_test', { workspace_directory: 'pkg', expected_candidate_hash: pkg.hash, tool: 'double', inputs: { amount: 21 }, expected_result: { doubled: 42 } })],
      [call('recipe', 'recipe__read_snapshot', { path: '/workspace/data/source' }), call('import', 'plugin_import', { workspace_directory: 'pkg', expected_candidate_hash: pkg.hash, enable: true }),
        call('sibling', 'file_write', { path: '/workspace/data/sibling', content: 'after import' })],
      [call('search-installed', 'tool_search', { query: wrapperName })], [call('installed', wrapperName, { amount: 21 })],
      [call('disabled', wrapperName, { amount: 22 })], [call('search-deleted', 'tool_search', { query: wrapperName })],
      [call('deleted', wrapperName, { amount: 23 })], [{ type: 'text', text: 'Finished', metadata: null }],
    ]);
    const loop: ToolLoopOptions = { tools: [], autoApproveTools: true, autoApproveHighRiskTools: true, autoApprovedToolNames: ['file_write'],
      dispatcher: new AgentToolDispatcher({ hooks: [] }), makeProviderForStep: model.factory };
    let disabled = false, deleted = false;
    const refresh = async (): Promise<AgentTool[]> => {
      if (model.state.streams === 4 && !disabled) { await f.store.setEnabled(pkg.manifest.id, pkg.hash, false); disabled = true; }
      if (model.state.streams === 5 && !deleted) { await f.store.remove(pkg.manifest.id, pkg.hash); deleted = true; }
      const installed = await f.store.listInstalled(primitives);
      loop.pluginAdapter = createPluginLoopAdapter(runtime(f.store, installed, primitives, unusedJS));
      loop.recipeAdapter = createRecipeLoopAdapter({ store: recipeStore, installed: recipes });
      const catalog = [...primitives, ...createPluginTools({ store: f.store, installed, primitives }), ...createRecipeTools({ store: recipeStore, installed: recipes, primitives })];
      return [...catalog, createToolSearchTool(createToolRegistry(catalog))];
    };
    loop.tools = await refresh(); loop.refreshTools = refresh;
    let conv = await runChatTurnWithTools(makeConversation('test-import'), 'Test, import and run', deps(model.provider, f.conversations), loop);
    assert.equal(result(partOf(conv, 'test'))['candidate_hash'], pkg.hash); assert.equal(result(partOf(conv, 'test'))['expected_match'], true);
    assert.equal(result(partOf(conv, 'test'))['registered'], false); assert.equal(f.state.successes, 0); assert.equal(f.state.applies, 0);
    assert.equal(partOf(conv, 'import').approvalState.type, 'pending'); assert.deepEqual(partOf(conv, 'sibling').output, []);
    assert.equal(result(partOf(conv, 'recipe'))['status'], 'succeeded'); assert.deepEqual(effects, ['mcp', 'recipe']); assert.equal(model.state.streams, 2);
    const recipeCheckpoint = clone(partOf(conv, 'recipe').metadata!['recipe_v1']);
    conv = await runToolLoopContinuation(approve(conv, 'import'), deps(model.provider, f.conversations), loop);
    assert.equal(f.state.applies, 1); assert.equal(result(partOf(conv, 'import'))['package_hash'], pkg.hash);
    assert.deepEqual(partOf(conv, 'recipe').metadata!['recipe_v1'], recipeCheckpoint); assert.deepEqual(partOf(f.state.saved.at(-1)!, 'recipe').metadata!['recipe_v1'], recipeCheckpoint);
    assert.deepEqual(effects, ['mcp', 'recipe', 'import', 'sibling', 'mcp']); assert.deepEqual(remoteCalls, ['https://mcp.test/1', 'https://mcp.test/1']);
    assert.deepEqual(result(partOf(conv, 'search-installed'))['expanded_tools'], [wrapperName]);
    assert.equal(model.state.schemas[0]!.some((schema) => schema.name === wrapperName), false);
    const wrapper = model.state.schemas[3]!.find((schema) => schema.name === wrapperName)!; assert.deepEqual(wrapper.parameters?.properties, { amount: { type: 'number' } });
    assert.deepEqual(wrapper.parameters?.required, ['amount']); assert.deepEqual(result(partOf(conv, 'installed'))['result'], { doubled: 42 });
    for (const schemas of model.state.schemas.slice(4)) assert.equal(schemas.some((schema) => schema.name === wrapperName), false);
    assert.equal(result(partOf(conv, 'disabled'))['status'], 'failed'); assert.equal(result(partOf(conv, 'deleted'))['status'], 'failed');
    assert.deepEqual(result(partOf(conv, 'search-deleted'))['expanded_tools'], []); assert.equal(f.state.successes, 1); assert.equal(f.state.failures, 0);
    const scoped = scripted([[call('scope-denied', wrapperName, { amount: 24 })], [{ type: 'text', text: 'Scope complete', metadata: null }]]);
    const scopedConv = await runChatTurnWithTools(makeConversation('scoped'), 'Call unavailable plugin', deps(scoped.provider, f.conversations), {
      tools: primitives, pluginAdapter: createPluginLoopAdapter(runtime(f.store, [f.entry(true)], primitives, unusedJS)),
      dispatcher: new AgentToolDispatcher({ hooks: [] }), makeProviderForStep: scoped.factory,
    });
    assert.equal(result(partOf(scopedConv, 'scope-denied'))['status'], 'failed'); assert.equal(remoteCalls.length, 2);
    const childPrimitives = primitives.filter((tool) => tool.mcpTarget?.serverId === 'demo_server' || tool.name === 'file_read');
    const member = clone(pkg.manifest.tools[0]!) as unknown as JsonObject;
    const expanded = await packageOf([member, { ...member, name: 'outside' }], childPrimitives);
    const child = persisted(expanded, true); const observed: AgentTool[][] = [];
    const childStore: PluginStore = { ...child.store, listInstalled: async (oracle) => {
      assert.deepEqual(oracle, childPrimitives); observed.push(oracle!); return child.store.listInstalled(oracle);
    } };
    const report = makeAgentTool({ name: 'subagent_report', description: 'Report child result',
      parameters: () => makeInputSchemaObj({ summary: { type: 'string' } }, ['summary']), execute: async () => output({ reported: true }) });
    const childPlugin = createPluginTools({ store: childStore, installed: child.state.installed, primitives: childPrimitives }).find((tool) => tool.name === wrapperName)!;
    const childRecipe = createRecipeTools({ store: recipeStore, installed: recipes, primitives: childPrimitives }).find((tool) => tool.name === 'recipe__read_snapshot')!;
    const childLoop: ToolLoopOptions = { tools: [...childPrimitives, childPlugin, childRecipe, report], invocationContext: 'subagent', makeProviderForStep: model.factory };
    const source = await readFile(new URL('../../../entry/src/main/ets/platform_impl/ScopedRecipeLoop.ets', import.meta.url), 'utf8');
    const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
    const platform = { exports: {} as { configureEntryScopedRecipeLoop: (loop: ToolLoopOptions, store: RecipeStore, guard: null,
      plugin: { store: PluginStore; createAdapter: (installed: InstalledPlugin[], primitives: AgentTool[]) => PluginLoopAdapter }) => Promise<void> } };
    new Function('require', 'exports', 'module', compiled)((name: string) => {
      assert.equal(name, '@amber/chat-domain'); return chatDomain;
    }, platform.exports, platform);
    await platform.exports.configureEntryScopedRecipeLoop(childLoop, recipeStore, null, { store: childStore,
      createAdapter: (installed, oracle) => { assert.deepEqual(oracle, childPrimitives); return createPluginLoopAdapter(runtime(childStore, installed, oracle, unusedJS)); } });
    assert.deepEqual(childLoop.tools.map((tool) => tool.name).sort(), [...childPrimitives.map((tool) => tool.name), wrapperName, childRecipe.name, report.name, 'tool_search'].sort());
    assert.equal(childLoop.tools.find((tool) => tool.name === report.name), report);
    const prepared = await childLoop.pluginAdapter!.prepare(call('child-new', wrapperName, { amount: 25 }), childPrimitives);
    assert.equal(checkpoint(prepared).descriptor.packageHash, expanded.hash);
    await childStore.setEnabled(expanded.manifest.id, expanded.hash, false);
    const refreshed = await childLoop.refreshTools!();
    assert.deepEqual(refreshed.map((tool) => tool.name).sort(), [...childPrimitives.map((tool) => tool.name), childRecipe.name, report.name, 'tool_search'].sort());
    assert.equal(refreshed.find((tool) => tool.name === report.name), report); assert.equal(observed.length, 2); assert.equal(remoteCalls.length, 2);
  } finally { manager.dispose(); }
});
