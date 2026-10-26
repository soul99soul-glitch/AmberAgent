const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const { actualPage, entryRoot } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');
const assistants = require('../../../chat/src/main/ets/chat/assistant.ts');
const storage = require('../../../chat/src/main/ets/chat/kv_store.ts');
const { SSHProfileStore } = require('../../../chat/src/main/ets/chat/terminal/profile_store.ts');
const { TerminalRuntime } = require('../../../chat/src/main/ets/chat/terminal/runtime.ts');
const { createTerminalTools } = require('../../../chat/src/main/ets/chat/terminal/tools.ts');
const { createTimeTool } = require('../../../chat/src/main/ets/chat/builtin_time_tool.ts');
const { createAskUserTool } = require('../../../chat/src/main/ets/chat/builtin_ask_user_tool.ts');
const { AgentToolActivityStore } = require('../../../chat/src/main/ets/chat/tool_activity.ts');
const { createToolRegistry } = require('../../../chat/src/main/ets/chat/tool_registry.ts');
const { createToolSearchTool } = require('../../../chat/src/main/ets/chat/builtin_introspection_tools.ts');
const domain = { ...assistants, ...storage, createTerminalTools, createTimeTool, createAskUserTool };

function loadEntry(name, dependencies) {
  const source = fs.readFileSync(path.join(entryRoot, 'platform_impl', name + '.ets'), 'utf8');
  const code = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const exports = {};
  new Function('exports', 'require', code)(exports, key => {
    if (key in dependencies) return dependencies[key];
    throw new Error('Unexpected SSH settings dependency: ' + key);
  });
  return exports;
}

async function fixture() {
  const kv = storage.createMemoryKeyValueStore();
  const assistant = assistants.makeAssistant({ id: 'chat', name: '默认助手', systemPrompt: 'Keep this prompt' });
  await storage.saveAssistants(kv, [assistant]);
  // Run the actual keyed mutex and assistant mutation; native file APIs are unused by this path.
  const atomic = loadEntry('AtomicJsonFile', {
    '@kit.CoreFileKit': {}, '@kit.PerformanceAnalysisKit': {}, '@kit.ArkTS': {},
  });
  const mutation = loadEntry('AssistantMutation', {
    '@amber/chat-domain': domain, './AtomicJsonFile.ets': atomic,
    '../di/AppContainer.ets': { getChatKvStore: () => kv },
  });
  const page = actualPage('pages/SettingSSHProfilesPage.ets',
    ['loadAssistantsForTools', 'assistantAllowsTerminal', 'setAssistantTerminal'], {
      ...domain, ...mutation, getChatAssistants: () => storage.loadAssistants(kv),
      sshPageError: error => error.message,
    });
  Object.assign(page, { alive: true, assistants: [], assistantSavingId: '', assistantError: '' });
  await page.loadAssistantsForTools();
  return { kv, page, mutation, assistant };
}

async function localTools(assistant) {
  const entries = new Map();
  const credentials = { save: async (id, _binding, secret) => { entries.set(id, secret); },
    load: async id => entries.get(id) ?? null, exists: async id => entries.has(id),
    delete: async id => { entries.delete(id); } };
  const profiles = await SSHProfileStore.create({ kv: storage.createMemoryKeyValueStore(), credentials });
  await profiles.commitVerified({ id: 'ssh', name: 'Verified fixture', host: 'host.invalid', port: 22,
    username: 'fixture', authMethod: 'password' }, null, 'SHA256:fixture', { secret: 'fixture-only', passphrase: null });
  await profiles.setDefault('ssh');
  // Discovering tools must not connect to SSH or execute a command.
  const unused = new Proxy({}, { get: (_target, name) => { throw new Error('Unexpected runtime I/O: ' + String(name)); } });
  const runtime = new TerminalRuntime({ profiles, credentials, transport: unused, logs: unused, taskStore: unused });
  const { createAssistantLocalPrimitives } = loadEntry('AssistantLocalPrimitives', {
    '@amber/chat-domain': domain, './JsSandboxTool.ets': {}, './ArtifactPorts.ets': {}, './EntryDocumentParser.ets': {},
  });
  return createAssistantLocalPrimitives({ assistant, conversationId: 'conversation', workspace: unused,
    activityStore: new AgentToolActivityStore(), terminalRuntime: runtime, moshRuntime: null,
    sshProfiles: profiles, pythonRuntime: null });
}

test('SSH settings enable and disable discoverable terminal tools for a saved default assistant', async () => {
  const { kv, page } = await fixture();
  assert.deepEqual(page.assistants[0].localTools, ['time_info']);
  assert.equal((await localTools(page.assistants[0])).some(tool => tool.name === 'terminal_execute'), false);
  await page.setAssistantTerminal('chat', true);
  const [enabled] = await storage.loadAssistants(kv);
  assert.deepEqual(enabled.localTools, ['time_info', 'terminal']);
  assert.equal(enabled.systemPrompt, 'Keep this prompt');
  assert.deepEqual(page.assistants[0], enabled);
  const tools = await localTools(enabled);
  assert.ok(tools.some(tool => tool.name === 'terminal_execute'));
  const registry = createToolRegistry(tools);
  assert.equal(registry.metadataFor('terminal_execute').needsApproval, true);
  assert.equal(registry.metadataFor('terminal_execute').autoApprovable, false);
  const result = await createToolSearchTool(registry).execute({ query: 'terminal_execute' });
  const payload = JSON.parse(result[0].text);
  assert.equal(payload.tools[0].name, 'terminal_execute');
  await page.setAssistantTerminal('chat', false);
  const [disabled] = await storage.loadAssistants(kv);
  assert.deepEqual(disabled.localTools, ['time_info']);
  assert.equal((await localTools(disabled)).some(tool => tool.name === 'terminal_execute'), false);
});

test('SSH tool toggle reads the latest assistant and preserves a concurrent settings change', async () => {
  const { kv, page, mutation } = await fixture();
  const concurrent = mutation.mutateAssistant('chat', current => assistants.patchAssistant(current, {
    name: 'Renamed elsewhere', localTools: [...current.localTools, 'ask_user'], temperature: 0.4,
  }));
  await Promise.all([concurrent, page.setAssistantTerminal('chat', true)]);
  const [saved] = await storage.loadAssistants(kv);
  assert.equal(saved.name, 'Renamed elsewhere');
  assert.equal(saved.temperature, 0.4);
  assert.deepEqual(saved.localTools, ['time_info', 'ask_user', 'terminal']);
  assert.deepEqual(page.assistants[0], saved);
});

test('failed SSH tool preference write preserves the persisted and displayed state with an error', async () => {
  const { kv, page } = await fixture();
  kv.put = async () => { throw new Error('fixture disk full'); };
  await page.setAssistantTerminal('chat', true);
  assert.deepEqual((await storage.loadAssistants(kv))[0].localTools, ['time_info']);
  assert.deepEqual(page.assistants[0].localTools, ['time_info']);
  assert.match(page.assistantError, /fixture disk full/);
  assert.equal(page.assistantSavingId, '');
});

test('SSH tool toggle keeps an assistant tool profile restriction visible without changing it', async () => {
  const { kv, page, mutation } = await fixture();
  await mutation.mutateAssistant('chat', current => assistants.patchAssistant(current, { toolProfile: 'web_read' }));
  await page.loadAssistantsForTools();
  await page.setAssistantTerminal('chat', true);
  const [saved] = await storage.loadAssistants(kv);
  assert.equal(saved.toolProfile, 'web_read');
  assert.deepEqual(saved.localTools, ['time_info']);
  assert.deepEqual(page.assistants[0], saved);
  assert.match(page.assistantError, /工具范围不包含终端/);
});
