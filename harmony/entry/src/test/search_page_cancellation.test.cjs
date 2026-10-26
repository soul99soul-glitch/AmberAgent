const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../../chat/node_modules/typescript');
process.env.TSX_TSCONFIG_PATH ??= path.resolve(__dirname, '../../../chat/tsconfig.json');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const { defaultSearchPrefs } = require('../../../chat/src/main/ets/search/search_prefs.ts');
const { initSearchSdk, makeSearchServiceOptions } = require('../../../chat/src/main/ets/search/search_service.ts');
const { searchOrchestratorSearch } = require('../../../chat/src/main/ets/search/search_orchestrator.ts');

const compile = source => ts.transpileModule(source, { compilerOptions: {
  module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
} }).outputText;
const flush = () => new Promise(resolve => setImmediate(resolve));

// Execute the production page owner, controller, orchestrator and provider adapter.
// Only ArkUI rendering, local storage/repository and the HTTP transport are replaced.
const loadPage = ({ loadPrefs = async settings => settings, localSummary = null } = {}) => {
  const settings = defaultSearchPrefs();
  const service = makeSearchServiceOptions('tavily');
  service.apiKey = 'fixture-key';
  settings.searchServices = [service];
  settings.searchEnabledServiceIds = [service.id];
  settings.searchBuiltinDuckDuckGoEnabled = false;
  settings.searchBuiltinBingEnabled = false;
  settings.searchBuiltinWikipediaEnabled = false;
  settings.searchBuiltinHackerNewsEnabled = false;
  const requests = [];
  initSearchSdk({ fetch: (request, context) => new Promise((resolve, reject) => {
    const signal = context?.signal;
    const pending = { request, signal, aborted: false, finish: () => {
      signal?.removeEventListener('abort', abort);
      resolve({ status: 200, headers: {}, body: JSON.stringify({ results: [{
        title: 'Current result', url: 'https://example.com/current', content: 'Current content',
      }] }) });
    } };
    const abort = () => {
      pending.aborted = true;
      signal?.removeEventListener('abort', abort);
      reject(new Error('transport cancelled'));
    };
    requests.push(pending);
    if (signal?.aborted) abort();
    else signal?.addEventListener('abort', abort);
  }), fetchStream: async () => { throw Error('unused'); } });
  const controllerOutput = {};
  const controllerFile = path.resolve(__dirname, '../main/ets/platform_impl/EntryAbortController.ets');
  vm.runInNewContext(compile(fs.readFileSync(controllerFile, 'utf8')), { exports: controllerOutput });
  const source = fs.readFileSync(path.resolve(__dirname, '../main/ets/pages/SearchPage.ets'), 'utf8');
  const helpers = source.slice(source.indexOf('type SearchResultKind'), source.indexOf('@Entry'));
  const start = source.indexOf('struct SearchPage {');
  const body = source.slice(start, source.indexOf('  @Builder', start))
    .replace('struct SearchPage', 'class SearchPage')
    .replace(/@(?:State|StorageProp)(?:\([^\n]*?\))?\s*/g, '');
  const timers = new Map();
  let timerId = 0;
  const sandbox = { exports: {}, Promise, Error, Set, Date,
    EntryAbortController: controllerOutput.EntryAbortController,
    router: { getParams: () => ({}), pushUrl: () => sandbox.exports.page.onPageHide() },
    loadSearchPrefs: () => loadPrefs(settings), getChatKvStore: () => ({}), ensureSearchSdk: () => {},
    searchOrchestratorSearch, getChatRepository: () => ({
      search: async () => localSummary === null ? [] : [localSummary],
      list: async () => [], getById: async () => null,
    }),
    setTimeout: callback => { timers.set(++timerId, callback); return timerId; },
    clearTimeout: id => timers.delete(id),
  };
  vm.runInNewContext(compile(`${helpers}\n${body}\n}\nexports.page = new SearchPage();`), sandbox);
  const page = sandbox.exports.page;
  page.aboutToAppear();
  return { page, requests, runDebounce: async () => {
    const callbacks = [...timers.values()];
    timers.clear();
    callbacks.forEach(callback => callback());
    await flush();
  } };
};

test('changing query aborts its real HTTP requests while the new query can finish normally', async () => {
  const { page, requests, runDebounce } = loadPage();
  page.onSearch('first');
  await runDebounce();
  const firstRequests = requests.slice();
  assert.ok(firstRequests.length > 0);
  page.onSearch('second');
  assert.ok(firstRequests.every(request => request.aborted), 'old HTTP must stop immediately');
  await runDebounce();
  const currentRequests = requests.slice(firstRequests.length);
  assert.ok(currentRequests.length > 0);
  assert.ok(currentRequests.every(request => !request.signal.aborted));
  assert.notEqual(currentRequests[0].signal, firstRequests[0].signal);
  currentRequests.forEach(request => request.finish());
  await flush();
  assert.equal(page.loading, false);
  assert.equal(page.errorMsg, '');
  assert.equal(page.results[0].title, 'Current result');
});

test('clearing and leaving the page cancel HTTP without publishing cancellation errors', async () => {
  for (const leave of [false, true]) {
    const { page, requests, runDebounce } = loadPage();
    page.onSearch('pending');
    await runDebounce();
    assert.ok(requests.length > 0);
    if (leave) { page.onPageHide(); page.aboutToDisappear(); }
    else page.onSearch('');
    assert.ok(requests.every(request => request.aborted));
    await flush();
    assert.equal(page.errorMsg, '');
    assert.equal(page.results.length, 0);
    assert.equal(page.loading, false);
    if (!leave) {
      assert.equal(page.searched, false);
    }
  }
});

test('a query cancelled during preference loading never starts obsolete HTTP', async () => {
  let finishPrefs;
  const { page, requests, runDebounce } = loadPage({ loadPrefs: settings => new Promise(resolve => {
    finishPrefs = () => resolve(settings);
  }) });
  page.onSearch('pending prefs');
  await runDebounce();
  page.onSearch('');
  finishPrefs();
  await flush();
  assert.equal(requests.length, 0);
  assert.equal(page.errorMsg, '');
  assert.equal(page.loading, false);
});

test('opening a local result hides the retained page and cancels HTTP; returning preserves results without restarting', async () => {
  const { page, requests, runDebounce } = loadPage({ localSummary: {
    id: 'local-chat', title: 'Local conversation', updateAt: '2026-10-25T12:00:00Z',
  } });
  page.onSearch('local');
  await runDebounce();
  const pendingCount = requests.length;
  assert.ok(pendingCount > 0);
  const localResult = page.results.find(result => result.kind === 'conversation');
  assert.ok(localResult);
  page.openResult(localResult);
  assert.ok(requests.every(request => request.aborted));
  assert.equal(page.loading, false);
  page.onPageShow();
  await runDebounce();
  assert.equal(requests.length, pendingCount, 'returning must not silently start another search');
  assert.equal(page.searchText, 'local');
  assert.equal(page.results[0].title, 'Local conversation');
  assert.equal(page.errorMsg, '');
  page.onSearch('edited after return');
  await runDebounce();
  assert.ok(requests.length > pendingCount);
  requests.slice(pendingCount).forEach(request => request.finish());
  await flush();
  assert.ok(page.results.some(result => result.title === 'Current result'));
  assert.equal(page.loading, false);
});
