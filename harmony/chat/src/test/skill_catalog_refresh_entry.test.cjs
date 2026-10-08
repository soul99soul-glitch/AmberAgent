const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

test('skill management refreshes the real run catalog and discovery while preserving provider snapshots', async () => {
  const domain = await import('../main/ets/index.ts');
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'amber-skill-refresh-'));
  const content = name => `---\nname: ${name}\ndescription: Useful skill\n---\n\nInstructions for ${name}`;
  const port = {
    exists: fs.existsSync, isDirectory: file => fs.statSync(file).isDirectory(),
    listDir: dir => fs.readdirSync(dir, { withFileTypes: true }).map(item => ({ name: item.name, isDirectory: item.isDirectory() })),
    mkdirs: dir => { if (fs.existsSync(dir)) return false; fs.mkdirSync(dir, { recursive: true }); return true; },
    readBytes: file => new Uint8Array(fs.readFileSync(file)),
    writeText: (file, value) => fs.writeFileSync(file, value), writeBytes: (file, value) => fs.writeFileSync(file, value),
    deleteRecursively: file => { fs.rmSync(file, { recursive: true, force: true }); return true; },
    renameTo: (from, to) => { try { fs.renameSync(from, to); return true; } catch { return false; } },
    walkFiles: () => ['SKILL.md'],
  };
  const skills = new domain.SkillManager({ port, skillsRoot: root });
  skills.saveSkill('demo', content('demo'));
  const originalAssistant = domain.makeAssistant({ id: 'owner', enabledSkills: [], localTools: [], toolProfile: 'full' });
  let savedAssistant = originalAssistant;
  const originalApi = { identity: 'original provider' };
  const originalModel = domain.makeChatModel({ modelId: 'original-model' });
  const dummy = name => domain.makeAgentTool({ name, description: name, execute: async () => [] });
  const fixtureDomain = { ...domain, asChatStreamProvider: (api, params) => ({ api, params: params() }) };
  for (const name of ['createConversationContextTools', 'createConversationHistoryTools', 'createConversationQueueTools',
    'createDeepReadPlaybookTools', 'createMcpManagementTools', 'createMcpServerTools', 'createMemoryTools']) fixtureDomain[name] = () => [];
  for (const name of ['createRunPlanUpdateTool', 'createHealthSummaryTool', 'createDeepReadOpenTool']) fixtureDomain[name] = () => dummy(name);
  const deps = {
    '@amber/chat-domain': fixtureDomain,
    '@kit.CoreFileKit': {}, '@kit.ArkTS': {}, '@kit.PerformanceAnalysisKit': { hilog: { warn: () => {} } },
    '../di/AppContainer.ets': {
      getAppContainer: () => ({ httpClient: { fetch: async () => { throw new Error('Provider probe is outside the skill catalog test'); } } }),
      getChatKvStore: () => ({}), getChatRepository: () => ({}), getCompactStore: () => ({}),
      getDeepReadPlaybookRepository: () => ({}), getMcpManager: () => ({ getAllAvailableTools: () => [] }),
      getSkillManager: () => skills, getSkillWorkspacePort: () => ({ readBytes: async () => new TextEncoder().encode(content('new-skill')) }),
      getChatAssistants: async () => [domain.makeAssistant({ id: 'other' }), savedAssistant],
      getPermissionsStatusTool: () => dummy('permissions_status'), getAgentPromptConfigTool: () => dummy('agent_prompt_config'),
      getWorkspaceManager: () => ({}), getSessionGrantStore: () => ({}),
      getRecipeStore: async () => ({ listInstalled: async () => [] }), getPluginStore: async () => ({ listInstalled: async () => [] }),
    },
    './AgentRuntimePrefs.ets': { systemLanguage: () => 'zh-CN' },
    './AtomicJsonFile.ets': { sharedKvMutex: { withLock: async (_key, action) => action() } },
    './AssistantMutation.ets': { mutateAssistant: async (id, update) => { assert.equal(id, 'owner'); savedAssistant = update(savedAssistant); } },
    './AssistantLocalPrimitives.ets': { createAssistantLocalPrimitives: () => Array.from({ length: 45 }, (_, i) => dummy(`file_fixture_${i}`)) },
    './ConversationBrowserGuard.ets': { guardConversationBrowserTools: tools => tools },
    './ConversationRunDependencies.ets': { buildConversationCompressProvider: () => ({}) },
    './EntryDocumentParser.ets': { inflateRawEntry: async value => value },
    './CouncilChatSupport.ets': { getChatCouncilTools: async () => [] },
    './EntryLocalToolPorts.ets': {}, './FeishuDocsTools.ets': {},
    './HealthReadPort.ets': { createEntryHealthReadPort: () => ({}) }, './ImageGenTool.ets': {},
    './JevSupport.ets': { createJevStatusTool: () => dummy('jev_status'), jevToolSearchReranker: () => undefined },
    './JevAutoApprovalSupport.ets': { createEntryJevAutoApprovalReview: () => domain.createJevAutoApprovalReview({
      loadSettings: async () => domain.makeJevSettings(), recentUserTexts: [],
      evaluate: async () => { throw new Error('Off Jev configuration must not evaluate'); },
    }) },
    './McpSettingsStore.ets': { getMcpSettingsStore: () => ({}) }, './MemoryStore.ets': {},
    './SoulTools.ets': { createSoulTools: () => [] }, './WebMountSession.ets': {},
    './WebMountTools.ets': {}, './WebMountAdvancedTools.ets': {}, './JevWebDecision.ets': {},
    './EntryPluginRuntime.ets': { createEntryPluginAdapter: () => ({ supports: () => false }) },
  };
  const entry = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl');
  const cache = new Map();
  const load = name => {
    const filename = path.join(entry, name);
    if (cache.has(filename)) return cache.get(filename);
    const exports = {};
    cache.set(filename, exports);
    const js = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
      compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
    }).outputText;
    vm.runInNewContext(js, { exports, require: module => {
      if (module === './RunToolCatalog.ets') return load('RunToolCatalog.ets');
      if (Object.hasOwn(deps, module)) return deps[module];
      throw new Error(`Unexpected import ${module}`);
    }, AppStorage: { get: () => undefined }, Error, Date, Intl, Promise, Map, Set, JSON, String, setTimeout, clearTimeout });
    return exports;
  };
  try {
    const rt = { assistant: originalAssistant, allSkills: skills.listSkills(), api: originalApi,
      params: { model: originalModel, temperature: 0.3 }, modelAbilities: ['tool'], modelContextWindowTokens: 16000 };
    const ctx = { conversationId: 'conversation', conversation: domain.makeConversation('conversation'),
      seed: { runtime: rt, searchSettings: { enableWebSearch: false }, agentRuntime: {
        contextCompaction: {}, speculativeToolExecution: {}, maxToolLoopSteps: 6 }, taskPrompts: {}, agentTaskTools: [], agentCronTools: [] },
      activityStore: new domain.AgentToolActivityStore(), publish: () => {}, pending: () => [], consumeSteer: async () => [] };
    const loop = await load('ConversationRunTools.ets').buildConversationToolLoop(ctx);
    const byName = (tools, name) => tools.find(tool => tool.name === name);
    assert.equal(byName(loop.tools, 'use_skill'), undefined);
    await byName(loop.tools, 'skill_enable').execute({ name: 'demo' });
    let refreshed = await loop.refreshTools();
    assert.ok(byName(refreshed, 'use_skill'), 'enabled skill is loadable on the next step');
    let loaded = await byName(refreshed, 'use_skill').execute({ name: 'demo' });
    assert.match(loaded[0].text, /Instructions for demo/);
    const discovery = await byName(refreshed, 'tool_search').execute({ query: 'use_skill', limit: 1 });
    assert.ok(JSON.parse(discovery[0].text).expanded_tools.includes('use_skill'));
    await byName(refreshed, 'skill_import').execute({ workspace_path: 'SKILL.md' });
    refreshed = await loop.refreshTools();
    const list = await byName(refreshed, 'skills_list').execute({});
    assert.equal(JSON.parse(list[0].text).enabled_count, 2);
    loaded = await byName(refreshed, 'use_skill').execute({ name: 'new-skill' });
    assert.match(loaded[0].text, /Instructions for new-skill/);
    await byName(refreshed, 'skill_disable').execute({ name: 'demo' });
    refreshed = await loop.refreshTools();
    await assert.rejects(byName(refreshed, 'use_skill').execute({ name: 'demo' }), /not enabled/);
    assert.equal(rt.assistant, originalAssistant);
    const provider = loop.makeProviderForStep([]);
    assert.equal(provider.api, originalApi);
    assert.equal(provider.params.model, originalModel);
    assert.equal(provider.params.temperature, 0.3);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
