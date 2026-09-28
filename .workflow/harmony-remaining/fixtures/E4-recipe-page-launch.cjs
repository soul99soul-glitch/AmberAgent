// One actual-domain fixture. Only the router and repository failure boundary are injected.
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
  if (!fs.existsSync(filename)) filename += '.ets';
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
    Uint8Array, ArrayBuffer, Error, Promise, Map, Set, Math, Number, JSON, String, Date,
    RegExp, Object, console, setTimeout, clearTimeout });
  return module.exports;
}

test('manual launch pins an actual checkpoint, saves before navigation, executes no model/primitive', async () => {
  const kits = {};
  kits['@amber/deepread-domain'] = loadSource(path.join(repo, 'harmony/deepread/src/main/ets/agent/message.ts'), kits);
  const modules = ['recipes/models.ts', 'recipes/validation.ts', 'recipes/runner.ts', 'conversation.ts',
    'message.ts', 'ids.ts', 'context_assembly.ts', 'assistant.ts', 'persistence.ts', 'tool.ts'];
  const actual = Object.assign({}, ...modules.map(file => loadSource(path.join(domain, file), kits)));
  kits['@amber/chat-domain'] = actual;
  const canonicalDescriptor = version => {
    const manifest = actual.decodeRecipe(JSON.stringify({ schema: 'amber.recipe.v1', name: 'manual_fixture', version,
      description: 'Manual checkpoint fixture', inputs: { text: 'string' },
      steps: [{ id: 'write', tool: 'file_write', arguments: { path: 'manual.json', content: '${input.text}' } }],
      outputs: { saved_path: '${step.write.output.path}' } }));
    const canonicalJSON = actual.canonicalRecipeJSON(manifest);
    const bytes = Buffer.from(canonicalJSON, 'utf8'); const length = Buffer.alloc(8); length.writeBigUInt64BE(BigInt(bytes.length));
    const hash = crypto.createHash('sha256').update(Buffer.from('amber.recipe.package.v1\0')).update(length).update(bytes).digest('hex');
    return { manifest, canonicalJSON, hash };
  };
  const original = canonicalDescriptor('1.0');
  let installed = [{ enabled: true, descriptor: original }]; let reads = 0; let primitiveExecutions = 0;
  const store = { listInstalled: async () => { reads++; return installed; },
    prepareImport: async () => { throw new Error('Manual checkpoint must not import'); },
    applyImport: async () => { throw new Error('Manual checkpoint must not install'); } };
  const primitives = [actual.makeAgentTool({ name: 'file_write', description: 'fixture write', needsApproval: true,
    execute: async () => { primitiveExecutions++; throw new Error('Launch must not execute'); } })];
  const assistant = actual.makeAssistant({ id: 'actual-default-assistant', chatModelId: null, localTools: ['workspace_files'],
    presetMessages: [actual.makeSystemMessage('preset retained')] });
  const memory = actual.createMemoryConversationRepository();
  const navigation = []; const events = [];
  kits['@kit.ArkUI'] = { router: { pushUrl: async route => {
    assert.ok(await memory.getById(route.params.conversationId), 'Navigation must have a persisted conversation');
    events.push('navigate'); navigation.push(route);
  } } };
  const launch = loadSource(path.join(repo, 'harmony/entry/src/main/ets/platform_impl/RecipePageLaunch.ets'), kits).startEntryRecipeFromPage;
  let releaseSave; let markSaveEntered;
  const entered = new Promise(resolve => { markSaveEntered = resolve; });
  const saveGate = new Promise(resolve => { releaseSave = resolve; });
  let saveAttempts = 0;
  const repository = Object.assign({}, memory, { save: async conversation => {
    saveAttempts++; markSaveEntered(); await saveGate; await memory.save(conversation); events.push('saved');
  } });
  const inputs = { text: '中文🙂', display_title: 'host metadata' };
  const pending = launch({ store, primitives, assistant, repository }, original.manifest.name, original.hash, inputs);
  await entered;
  assert.equal(navigation.length, 0); assert.equal((await memory.list()).length, 0);
  // Replace the live package and mutate caller inputs while the durable save is pending.
  installed = [{ enabled: true, descriptor: canonicalDescriptor('2.0') }]; inputs.text = 'changed later';
  releaseSave(); await pending;
  assert.equal(reads, 1); assert.equal(saveAttempts, 1); assert.equal(events.join(','), 'saved,navigate');
  const route = navigation[0]; assert.equal(route.url, 'pages/ChatPage');
  const conversation = await memory.getById(route.params.conversationId);
  assert.equal(conversation.assistantId, assistant.id); assert.equal(conversation.title, 'Recipe · manual_fixture');
  assert.equal(conversation.autoApproveToolCalls, false); assert.equal(conversation.messageNodes.length, 3);
  assert.equal(actual.currentMessages(conversation)[0].role, 'system');
  assert.equal(actual.currentMessages(conversation)[1].role, 'user');
  const message = actual.currentMessages(conversation)[2]; const parent = message.parts[0];
  assert.equal(message.role, 'assistant'); assert.equal(message.modelId, null); assert.equal(message.usage, null);
  assert.equal(parent.toolCallId, route.params.recipeManualToolCallId); assert.equal(parent.toolName, 'recipe__manual_fixture');
  assert.equal(parent.approvalState.type, 'auto'); assert.equal(parent.output.length, 0);
  assert.equal(parent.metadata.recipe_manual, true);
  const checkpoint = parent.metadata.recipe_v1;
  assert.equal(checkpoint.descriptor.hash, original.hash); assert.equal(checkpoint.descriptor.manifest.version, '1.0');
  assert.equal(checkpoint.descriptor.canonicalJSON, original.canonicalJSON); assert.equal(checkpoint.inputs.text, '中文🙂');
  assert.equal(Object.hasOwn(checkpoint.inputs, 'display_title'), false);
  assert.equal(checkpoint.phase, 'ready'); assert.equal(checkpoint.nextIndex, 0); assert.equal(checkpoint.pendingStep, null);
  assert.ok(checkpoint.executionId); assert.notEqual(checkpoint.executionId, parent.toolCallId);
  // The same actual adapter validates the persisted checkpoint without replacing it from the live v2 package.
  const restored = await actual.createRecipeLoopAdapter({ store, installed }).prepare(parent, primitives);
  assert.equal(restored.metadata.recipe_v1.descriptor.hash, original.hash); assert.equal(reads, 1);
  const saveError = new Error('fixed repository save failure');
  const failingRepository = Object.assign({}, memory, { save: async () => { saveAttempts++; throw saveError; } });
  await assert.rejects(launch({ store, primitives, assistant, repository: failingRepository }, original.manifest.name,
    installed[0].descriptor.hash, { text: 'next' }), error => error === saveError);
  assert.equal(navigation.length, 1); assert.equal((await memory.list()).length, 1);
  const before = saveAttempts;
  await assert.rejects(launch({ store, primitives, assistant, repository }, original.manifest.name, original.hash,
    { text: 'old approval' }), error => error.code === 'stale_base');
  await assert.rejects(launch({ store, primitives: [], assistant, repository }, original.manifest.name,
    installed[0].descriptor.hash, { text: 'unavailable primitive' }), error => error.code === 'invalid_recipe');
  assert.equal(saveAttempts, before); assert.equal(navigation.length, 1); assert.equal(primitiveExecutions, 0);
  console.log(JSON.stringify({ persistedCheckpoint: 'v1/ready/auto', livePackage: 'v2', saveFailureNavigation: 0,
    primitiveExecutions, modelConfigured: false, realDomainMemoryRepositoryRoundtrip: true }));
});
