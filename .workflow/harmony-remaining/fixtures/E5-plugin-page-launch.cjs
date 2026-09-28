// Actual domain preparation/persistence; only SDK router and unused execution ports are injected.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const test = require('node:test');
const repo = path.resolve(__dirname, '../../..');
const ts = require(path.join(repo, 'harmony/chat/node_modules/typescript'));
const domain = path.join(repo, 'harmony/chat/src/main/ets/chat');

function loadSource(filename, kits, cache = new Map()) {
  filename = path.resolve(filename);
  if (cache.has(filename)) return cache.get(filename);
  const module = { exports: {} }; cache.set(filename, module.exports);
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  const requireSource = name => {
    if (kits[name] !== undefined) return kits[name];
    if (name.startsWith('.')) return loadSource(path.resolve(path.dirname(filename), name), kits, cache);
    throw new Error('Unexpected module: ' + name);
  };
  vm.runInNewContext(code, { module, exports: module.exports, require: requireSource,
    Uint8Array, ArrayBuffer, Error, SyntaxError, Promise, Map, Set, Math, Number, JSON, String, Date,
    RegExp, Object, console, setTimeout, clearTimeout });
  return module.exports;
}

test('Plugin launch pins the actual checkpoint, awaits durable save, and only routes that parent', async () => {
  const kits = {};
  kits['@amber/deepread-domain'] = loadSource(path.join(repo, 'harmony/deepread/src/main/ets/agent/message.ts'), kits);
  const files = ['plugins/models.ts', 'plugins/validation.ts', 'plugins/runner.ts', 'conversation.ts',
    'message.ts', 'ids.ts', 'context_assembly.ts', 'assistant.ts', 'persistence.ts', 'tool.ts'];
  const actual = Object.assign({}, ...files.map(file => loadSource(path.join(domain, file), kits)));
  kits['@amber/chat-domain'] = actual;
  const packageOf = async version => {
    const manifest = { schema: 'amber.plugin.v1', id: 'launch_fixture', name: 'Launch fixture', version,
      description: 'Actual pinned JS descriptor', tools: [{ name: 'echo', script: 'scripts/echo.js',
        input_schema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'], additionalProperties: false } }],
      capabilities: { workspaceReadPrefixes: [], workspaceWritePrefixes: [], networkDomains: [], webMountActions: [] },
      backgroundAllowed: false };
    const sourceFiles = [{ path: 'plugin.json', data: Array.from(Buffer.from(JSON.stringify(manifest))) },
      { path: 'scripts/echo.js', data: Array.from(Buffer.from('return input.text;')) }];
    return actual.preparePluginPackage(sourceFiles, [], { sha256: async bytes => crypto.createHash('sha256').update(bytes).digest('hex') });
  };
  const original = await packageOf('1.0'); const replacement = await packageOf('2.0');
  const unsigned = { tier: 'local_unsigned', publisherTrusted: false, keyId: null, fingerprint: null, signature: null };
  const installed = candidate => [{ id: candidate.manifest.id, currentHash: candidate.hash, package: candidate,
    configuredEnabled: true, enabled: true, trust: unsigned, health: null, errorCode: null, errorMessage: null }];
  let source = original; let calls = 0;
  const unexpected = () => { calls++; throw new Error('Launch must not execute a backend or primitive'); };
  const store = { readPackage: async requested => {
    assert.equal(requested.kind, 'archive'); assert.equal(requested.workspacePath, 'plugins/test.json');
    return { candidate: source, trust: unsigned };
  }, applyImport: unexpected, recordSuccess: unexpected, recordFailure: unexpected };
  const adapterDeps = { store, installed: installed(original), js: { start: unexpected, reply: unexpected,
    reject: unexpected, cancel: unexpected, hasSession: unexpected }, http: { execute: unexpected },
    webMount: { withScope: unexpected }, resolveMcpPrimitive: unexpected };
  const adapter = actual.createPluginLoopAdapter(adapterDeps);
  const assistant = actual.makeAssistant({ id: 'real-default-assistant', chatModelId: null,
    presetMessages: [actual.makeSystemMessage('preset retained')] });
  const memory = actual.createMemoryConversationRepository(); const navigation = []; const events = [];
  kits['@kit.ArkUI'] = { router: { pushUrl: async route => {
    assert.ok(await memory.getById(route.params.conversationId));
    events.push('navigate'); navigation.push(route);
  } } };
  const launch = loadSource(path.join(repo, 'harmony/entry/src/main/ets/platform_impl/PluginPageLaunch.ets'), kits).startEntryPluginFromPage;
  let release; let entered;
  const saved = new Promise(resolve => { entered = resolve; });
  const barrier = new Promise(resolve => { release = resolve; });
  let saves = 0;
  const repository = Object.assign({}, memory, { save: async conversation => {
    saves++; entered(); await barrier; await memory.save(conversation); events.push('saved');
  } });
  const parent = { type: 'tool', toolCallId: 'exact-plugin-parent', toolName: original.tools[0].toolId,
    input: JSON.stringify({ text: '中文🙂', display_title: 'not a member input' }), output: [],
    approvalState: { type: 'auto' }, metadata: { plugin_manual: false, retained: 'owner metadata' } };
  const pending = launch({ assistant, primitives: [], adapter, repository }, parent, '手动真实插件');
  await saved; assert.equal(navigation.length, 0); assert.equal((await memory.list()).length, 0);
  adapterDeps.installed = installed(replacement); parent.input = '{"text":"later mutation"}'; source = replacement;
  release(); await pending;
  assert.equal(events.join(','), 'saved,navigate'); assert.equal(saves, 1);
  const route = navigation[0]; const conversation = await memory.getById(route.params.conversationId);
  assert.equal(route.url, 'pages/ChatPage'); assert.equal(route.params.pluginManualToolCallId, 'exact-plugin-parent');
  assert.equal(conversation.assistantId, assistant.id); assert.equal(conversation.autoApproveToolCalls, false);
  assert.equal(conversation.messageNodes.length, 3); assert.equal(actual.currentMessages(conversation)[0].role, 'system');
  const message = actual.currentMessages(conversation)[2]; const persisted = message.parts[0];
  assert.equal(message.role, 'assistant'); assert.equal(message.modelId, null); assert.equal(message.usage, null);
  assert.equal(persisted.toolCallId, route.params.pluginManualToolCallId); assert.equal(persisted.metadata.plugin_manual, true);
  assert.equal(persisted.metadata.retained, 'owner metadata'); assert.equal(persisted.metadata.plugin_v1.phase, 'ready');
  assert.equal(persisted.metadata.plugin_v1.descriptor.packageHash, original.hash);
  assert.equal(persisted.metadata.plugin_v1.inputs.text, '中文🙂');
  assert.equal(Object.hasOwn(persisted.metadata.plugin_v1.inputs, 'display_title'), false);
  assert.equal(persisted.approvalState.type, 'auto'); assert.equal(persisted.output.length, 0);
  const restored = await actual.createPluginLoopAdapter({ ...adapterDeps, installed: installed(replacement) }).prepare(persisted, []);
  assert.equal(restored.metadata.plugin_v1.descriptor.packageHash, original.hash);
  const saveError = new Error('real repository failed');
  const failingRepository = Object.assign({}, memory, { save: async () => { throw saveError; } });
  await assert.rejects(launch({ assistant, primitives: [], adapter, repository: failingRepository }, persisted, '失败保存'), error => error === saveError);
  assert.equal(navigation.length, 1);
  await assert.rejects(launch({ assistant, primitives: [], adapter, repository }, { ...parent, toolName: 'file_write' }, '普通工具'), /plugin_manual_tool_unavailable/);
  assert.equal(saves, 1); assert.equal(navigation.length, 1);
  const testParent = expected => ({ ...parent, toolName: 'plugin_test', metadata: null,
    input: JSON.stringify({ archive_path: 'plugins/test.json', expected_candidate_hash: original.hash,
      tool: 'echo', inputs: { text: 'test' }, ...expected }) });
  // A changed candidate cannot become the version shown in this UI.
  await assert.rejects(launch({ assistant, primitives: [], adapter, repository }, testParent({ expected_result: null }), '候选测试'), error => error.code === 'stale_candidate');
  assert.equal(saves, 1); assert.equal(navigation.length, 1);
  source = original;
  const explicitNull = await adapter.prepare(testParent({ expected_result: null }), []);
  const absent = await adapter.prepare(testParent({}), []);
  assert.equal(explicitNull.metadata.plugin_v1.test.expectedProvided, true);
  assert.equal(explicitNull.metadata.plugin_v1.test.expectedResult, null);
  assert.equal(absent.metadata.plugin_v1.test.expectedProvided, false);
  assert.equal(calls, 0);
});

test('Plugin center distinguishes actual invalid/quarantined/disabled state and full trust semantics', () => {
  const projection = loadSource(path.join(repo, 'harmony/entry/src/main/ets/components/PluginPageProjection.ets'), {});
  const unsigned = { tier: 'local_unsigned', publisherTrusted: false, fingerprint: null };
  const base = { id: 'fixture', currentHash: 'a'.repeat(64), package: {}, configuredEnabled: true,
    enabled: true, trust: unsigned, health: null, errorCode: null };
  assert.equal(projection.pluginStatusLabel(base), '已启用');
  assert.equal(projection.pluginStatusLabel({ ...base, enabled: false }), '已停用');
  assert.equal(projection.pluginStatusLabel({ ...base, enabled: false, health: { quarantinedAt: 1 } }), '已隔离');
  assert.equal(projection.pluginStatusLabel({ ...base, package: null, errorCode: 'signature_invalid' }), '无效');
  assert.equal(projection.pluginTrustLabel(unsigned), '本地未签名');
  assert.equal(projection.pluginTrustLabel({ tier: 'signed', publisherTrusted: false }), '已签名 · 发布者未信任');
  assert.equal(projection.pluginTrustLabel({ tier: 'signed', publisherTrusted: true }), '已签名 · 发布者已信任');
});
