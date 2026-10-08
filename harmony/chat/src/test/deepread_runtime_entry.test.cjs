// Execute the actual narrow Entry DI factories with controlled platform ports.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const entryFile = path.resolve(__dirname, '../../../entry/src/main/ets/di/AppContainer.ets');
const names = new Set([
  'TASK_DEEPREAD_MODEL_KEY', 'deepReadScheduler', 'deepReadSchedulerInit',
  'getDeepReadScheduler', 'getDeepReadTextRuntime', 'toolToModelTool', 'mapCacheEntryObservable',
  'resolveDeepReadRuntimeCore', 'resolveProviderRuntimeCore', 'resolveConfiguredTaskModel',
  'makeProviderChoice', 'effectiveProvider', 'buildProviderApi', 'loadDeepReadModelOptions',
]);
const actualFactories = () => {
  const source = fs.readFileSync(entryFile, 'utf8');
  const file = ts.createSourceFile(entryFile, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  return file.statements.filter(statement => ts.isVariableStatement(statement)
    && statement.declarationList.declarations.some(declaration => names.has(declaration.name.getText(file))))
    .map(statement => statement.getText(file)).join('\n');
};
const harness = async () => {
  const domain = await import('../main/ets/index.ts');
  const kv = domain.createMemoryKeyValueStore();
  const requests = [], lifecycle = [], histories = [];
  let schedulerDeps;
  let choice = null;
  let providers = [];
  let cached = null;
  let defaultTemplateId = 'none';
  const captures = [];
  const storage = { get: async (_key, fallback) => defaultTemplateId ?? fallback };
  const snapshot = id => ({ id, name: id, kind: 'native', html: null, capturedAt: 5 });
  const http = { fetchStream: async (request, opts) => {
    requests.push(request);
    const bytes = new TextEncoder().encode('data: {"id":"x","model":"m","choices":[{"index":0,"delta":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n');
    opts.onChunk(bytes.buffer, true); return { status: 200, headers: {}, body: '' };
  } };
  const repository = { listHistory: async () => { histories.push('hydrate'); return []; },
    get: async id => { histories.push(id); return cached; }, observe: () => ({ subscribe: () => () => {}, getCurrent: () => null }) };
  const exports = {};
  const adapterFile = path.resolve(path.dirname(entryFile), '../platform_impl/DeepReadRunRepository.ets');
  const adapterExports = {};
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(adapterFile, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports: adapterExports, require: () => domain, Map, Promise, Date, Error });
  const host = { ...domain, exports, Error, Promise, Map, Set, JSON, String, Number, Date, Math,
    getProductKind: () => 'agent',
    DOMAIN: 1, TAG: 'test', hilog: { info() {} },
    getChatKvStore: () => kv,
    readTaskModelReference: async key => domain.decodeTaskModelReference(await kv.get(key)),
    loadProviders: async () => providers,
    resolveChatProviderChoice: async () => choice,
    getChatAssistants: async () => [domain.makeAssistant({ customHeaders: [{ name: 'X-Assistant', value: 'captured' }] })],
    getValidCodexToken: async () => ({ accessToken: 'fresh', accountId: 'account-1' }),
    getValidGrokToken: async () => ({ accessToken: 'grok-fresh' }),
    getAppContainer: () => ({ httpClient: http, storage, repository, prefetcher: {}, aiClient: {}, notifier: {} }),
    getDeepReadArtifactStore: () => ({ save: async () => {} }),
    captureDeepReadTemplate: async (_storage, id) => { captures.push(id); return snapshot(id); },
    createTavilyAndFallbackRegistry: () => ({}),
    createSourcePrefetcher: () => ({}),
    ensureSearchSdk: () => {},
    getChatKeyRoulette: () => ({ next: key => key }),
    getDeepReadPlaybookRepository: () => ({ read: async () => ({ markdown: 'playbook' }) }),
    DeepReadRunRepositoryAdapter: adapterExports.DeepReadRunRepositoryAdapter,
    createScheduler: deps => { schedulerDeps = deps; return { marker: 'ready' }; },
    isInterruptedPhase: () => false,
    createEntryAbortController: () => new AbortController(),
    setDeepReadGenerationActivity: (...args) => lifecycle.push(['activity', ...args]),
    beginDeepReadGenerationStep: (...args) => lifecycle.push(['begin', ...args]),
    observeDeepReadGenerationSnapshot: (...args) => lifecycle.push(['snapshot', ...args]),
    encodeImageForChat: () => '', encodeImageDetailed: () => ({}),
    resolveEntryGoogleAuth: async () => null,
  };
  vm.runInNewContext(ts.transpileModule(actualFactories(), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, host, { filename: entryFile });
  return { domain, kv, requests, lifecycle, histories, exports, captures, snapshot,
    setCached: output => { cached = output === null ? null : { topicId: 'topic', title: 'title', output }; },
    setDefaultTemplateId: id => { defaultTemplateId = id; },
    setChoice: value => { choice = value; providers = value === null ? [] : [value.provider]; },
    deps: () => schedulerDeps };
};

test('DeepRead Entry: history scheduler initializes without a model; actual run reports unavailable model', async () => {
  const h = await harness();
  assert.equal((await h.exports.getDeepReadScheduler()).marker, 'ready');
  assert.deepEqual(h.histories, ['hydrate']);
  assert.equal(h.requests.length, 0);
  await assert.rejects(h.deps().createRunManager('topic', 'title', 5), /配置可用的聊天模型/);
  assert.deepEqual(h.histories, ['hydrate', 'topic']);
});

test('DeepRead Entry: a fixed missing UUID fails instead of invoking the available current model', async () => {
  const h = await harness();
  const provider = h.domain.makeProviderSettingOpenAIVariant({ id: 'provider-a', apiKey: 'key', models: [
    h.domain.makeProviderModel({ id: 'uuid-a', modelId: 'api-a', type: 'chat', abilities: ['tool'] }),
  ] });
  h.setChoice({ provider, model: provider.models[0], providerContainerId: provider.id });
  await h.kv.put('deepread_task_model', JSON.stringify({ providerId: 'provider-a', modelId: 'removed-uuid' }));
  await assert.rejects(h.exports.getDeepReadTextRuntime(), /指定的深读模型已不可用/);
  assert.equal(h.requests.length, 0);
});

test('DeepRead Entry: plaintext and article admit the configured non-tool model; actual stage collection sends no tools', async () => {
  const h = await harness();
  const provider = h.domain.makeProviderSettingOpenAIVariant({ id: 'provider-a', apiKey: 'key', models: [
    h.domain.makeProviderModel({ id: 'uuid-a', modelId: 'api-a', type: 'chat', abilities: [] }),
  ] });
  h.setChoice({ provider, model: provider.models[0], providerContainerId: provider.id });
  const text = await h.exports.getDeepReadTextRuntime();
  const output = await text.aiClient.generateText({ model: text.model, messages: [h.domain.makeUserMessage('翻译')] });
  assert.equal(h.domain.toText(output.at(-1)), 'done');
  await h.exports.getDeepReadScheduler();
  const manager = await h.deps().createRunManager('topic', 'title', 5);
  assert.equal(manager.writerMode, 'structured');
  assert.equal(manager.model, 'api-a');
  await manager.collectRun([h.domain.makeUserMessage('只输出概览JSON')], 'overview', undefined, []);
  assert.equal(h.requests.length, 2);
  assert.deepEqual(JSON.parse(h.requests[1].body).tools ?? [], []);
  assert.equal(JSON.parse(h.requests[1].body).model, 'api-a');
});

test('DeepRead Entry: OAuth refresh is present in the first actual request; activity hooks carry exact token', async () => {
  const h = await harness();
  const provider = h.domain.makeProviderSettingOpenAIVariant({ id: 'codex-a', apiKey: 'old', authMode: 'codex_oauth',
    models: [h.domain.makeProviderModel({ id: 'uuid-a', modelId: 'api-a', type: 'chat', abilities: ['tool'] })] });
  h.setChoice({ provider, model: provider.models[0], providerContainerId: provider.id });
  await h.exports.getDeepReadScheduler();
  const run = await h.deps().createRunManager('topic', 'title', 7);
  await run.aiClient.generateText({ model: run.model, messages: [h.domain.makeUserMessage('规划')] });
  assert.equal(h.requests[0].headers.Authorization, 'Bearer fresh');
  assert.equal(h.requests[0].headers['ChatGPT-Account-Id'], 'account-1');
  assert.equal(h.requests[0].headers['X-Assistant'], 'captured');
  assert.equal(provider.apiKey, 'old');
  assert.equal(run.playbookMarkdown, 'playbook');
  assert.equal(run.writerMode, 'tools');
  assert.deepEqual(h.lifecycle.map(event => event.slice(0, 3)), [['begin', 'topic', 7], ['snapshot', 'topic', 7]]);
  h.deps().onRunActivity('topic', 7, false);
  assert.deepEqual(h.lifecycle.at(-1), ['activity', 'topic', 7, false]);
});


test('DeepRead Entry: actual factory prefers explicit then continued cache, and fresh/forced jobs capture current default', async () => {
  const h = await harness();
  const provider = h.domain.makeProviderSettingOpenAIVariant({ id: 'provider-a', apiKey: 'key', models: [
    h.domain.makeProviderModel({ id: 'uuid-a', modelId: 'api-a', type: 'chat', abilities: [] }),
  ] });
  h.setChoice({ provider, model: provider.models[0], providerContainerId: provider.id });
  await h.exports.getDeepReadScheduler();
  h.setDefaultTemplateId('current-default');
  const cached = h.snapshot('article-original');
  const explicit = h.snapshot('explicit-choice');
  h.setCached({ templateSnapshot: cached });
  const selected = await h.deps().createRunManager('topic', 'title', 1, { templateSnapshot: explicit });
  assert.equal(selected.templateSnapshot, explicit);
  const continued = await h.deps().createRunManager('topic', 'title', 2);
  assert.equal(continued.templateSnapshot, cached);
  assert.deepEqual(h.captures, []);
  const forced = await h.deps().createRunManager('topic', 'title', 3, { force: true });
  assert.equal(forced.templateSnapshot.id, 'current-default');
  const forcedSelected = await h.deps().createRunManager('topic', 'title', 4, { force: true, templateSnapshot: explicit });
  assert.equal(forcedSelected.templateSnapshot, explicit);
  h.setCached(null);
  const fresh = await h.deps().createRunManager('topic', 'title', 5);
  assert.equal(fresh.templateSnapshot.id, 'current-default');
  assert.deepEqual(h.captures, ['current-default', 'current-default']);
});
