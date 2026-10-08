const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const source = fs.readFileSync(path.join(__dirname, '../main/ets/di/AppContainer.ets'), 'utf8');
const factory = source.slice(source.indexOf('export const getDeepReadScheduler ='), source.indexOf('const mapCacheEntryObservable ='))
  .replace('export const', 'const');
const code = ts.transpileModule('let deepReadScheduler=null; let deepReadSchedulerInit=null;\n' + factory + '\nreturn getDeepReadScheduler;',
  { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;

async function fixture({ cached = null, globalId = 'none', abilities = [], captureError = false, product = 'agent' } = {}) {
  const calls = { capture: [], searches: [], generation: [], steps: [], snapshots: [] };
  let configured;
  const runtime = { model: { modelId: 'exact-model', abilities }, api: { id: 'captured-provider' }, params: { temperature: .25 } };
  class Repository {
    async hydrate() {}
    async load(id) { calls.loaded = id; }
    get() { return cached; }
  }
  const names = ['getAppContainer', 'DeepReadRunRepositoryAdapter', 'getDeepReadArtifactStore', 'resolveDeepReadRuntimeCore',
    'captureDeepReadTemplate', 'createTavilyAndFallbackRegistry', 'loadSearchPrefs', 'getChatKvStore', 'ensureSearchSdk',
    'searchDeepReadGoogle', 'createSourcePrefetcher', 'createEntryAbortController', 'getDeepReadPlaybookRepository',
    'createChatDeepReadAiClient', 'beginDeepReadGenerationStep', 'observeDeepReadGenerationSnapshot', 'hilog', 'DOMAIN', 'TAG',
    'getProductKind', 'toolToModelTool', 'createScheduler', 'mapCacheEntryObservable', 'isInterruptedPhase', 'setDeepReadGenerationActivity'];
  const values = [() => ({ repository: {}, httpClient: {}, storage: { get: async () => globalId }, prefetcher: {}, aiClient: {}, notifier: {} }),
    Repository, () => ({ save: async () => {} }), async () => runtime,
    async (_storage, id) => { calls.capture.push(id); if (captureError) throw Error('模板已删除'); return { id, name: 'captured default', kind: 'native', html: null, capturedAt: 1 }; },
    (_http, _storage, loader, _sdk, options) => ({ loader, options }), async () => ({ searchServiceSelected: 1 }), () => ({}), () => {},
    async (...args) => { calls.searches.push(args); return []; }, (_http, registry) => ({ registry }), () => ({}),
    () => ({ read: async () => ({ markdown: 'Actual playbook' }) }),
    hooks => { calls.hooks = hooks; return { generateText: async params => { calls.generation.push(params); return []; } }; },
    (...args) => calls.steps.push(args), (...args) => calls.snapshots.push(args), { info() {} }, 1, 'test', () => product, tool => tool,
    deps => { configured = deps; return { id: 'scheduler' }; }, () => ({}), () => false, () => {}];
  const getScheduler = new Function(...names, code)(...values);
  await getScheduler();
  return { calls, configured, runtime };
}

test('actual DI selects structured mode for non-tool model and binds Google to the specific admitted topic', async () => {
  const f = await fixture();
  const manager = await f.configured.createRunManager('topic-one', 'title', 7);
  assert.equal(manager.writerMode, 'structured'); assert.equal(manager.model, 'exact-model');
  assert.equal(manager.templateSnapshot.id, 'none');
  const signal = { aborted: false };
  await manager.prefetcher.registry.options.searchGoogle('query', 4, signal);
  assert.deepEqual(f.calls.searches, [['topic-one', 'query', 4, signal]]);
  await manager.collectRun([], 'stage', signal, []);
  assert.deepEqual(f.calls.generation[0].tools, []);
  assert.equal(f.calls.generation[0].signal, signal);
  f.calls.hooks.onRequestStart([]); f.calls.hooks.onRawSnapshot([]);
  assert.deepEqual(f.calls.steps[0], ['topic-one', 7, []]); assert.deepEqual(f.calls.snapshots[0], ['topic-one', 7, []]);
  const next = await f.configured.createRunManager('topic-two', 'second', 8);
  assert.notEqual(next.prefetcher, manager.prefetcher);
  await next.prefetcher.registry.options.searchGoogle('next', 2, signal);
  assert.equal(f.calls.searches[1][0], 'topic-two');
});

test('explicit selected and continued captured templates bypass an invalid global default', async () => {
  const captured = { id: 'article-original', name: 'Original', kind: 'custom', html: '<article>{{content}}</article>', capturedAt: 2 };
  const selected = { ...captured, id: 'current-selection' };
  const f = await fixture({ cached: { templateSnapshot: captured }, globalId: 'deleted', abilities: ['tool'], captureError: true });
  const continued = await f.configured.createRunManager('topic', 'title', 1);
  assert.equal(continued.templateSnapshot, captured); assert.equal(continued.writerMode, 'tools');
  const forced = await f.configured.createRunManager('topic', 'title', 2, { force: true, templateSnapshot: selected });
  assert.equal(forced.templateSnapshot, selected); assert.deepEqual(f.calls.capture, []);
});

test('a fresh forced generation captures the current default instead of silently reusing an old template', async () => {
  const f = await fixture({ cached: { templateSnapshot: { id: 'old' } }, globalId: 'new-default' });
  const manager = await f.configured.createRunManager('topic', 'title', 1, { force: true });
  assert.equal(manager.templateSnapshot.id, 'new-default'); assert.deepEqual(f.calls.capture, ['new-default']);
});

 test('standalone DeepRead uses the new JSON hierarchy even when its selected model supports tools', async () => {
  const f = await fixture({ abilities: ['tool'], product: 'deepread' });
  const manager = await f.configured.createRunManager('topic', 'title', 1);
  assert.equal(manager.writerMode, 'structured');
  assert.equal(manager.model, 'exact-model');
 });
