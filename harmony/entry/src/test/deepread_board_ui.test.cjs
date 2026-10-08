const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../../chat/node_modules/typescript');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const { discoveryHotspotInputs, discoveryArticleParams } = require('../../../deepread/src/main/ets/domain/discovery_input.ts');
const source = fs.readFileSync(path.join(__dirname, '../main/ets/pages/BoardPage.ets'), 'utf8');
const pure = {};
vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname, '../main/ets/platform_impl/HotListState.ets'), 'utf8'),
  { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText, { exports: pure });
function method(name) {
  const pageSource = source.slice(source.indexOf('struct BoardPageContent {'));
  const match = new RegExp('^  (?:private )?(?:async )?' + name + '\\(', 'm').exec(pageSource);
  assert.ok(match, name);
  let end = pageSource.indexOf('{', match.index) + 1, depth = 1;
  for (; depth && end < pageSource.length; end++) {
    if (pageSource[end] === '{') depth++;
    if (pageSource[end] === '}') depth--;
  }
  return pageSource.slice(match.index, end);
}
const methods = ['onPageShow', 'onPageHide', 'aboutToDisappear', 'stopRefresh', 'settleArrival', 'observeRuns', 'runKey',
  'openRun', 'randomItems', 'randomRead', 'openArticle'];
const code = ts.transpileModule('class Page {\n' + methods.map(method).join('\n') + '\n}\nreturn Page;',
  { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
const tick = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; }
function fixture(options = {}) {
  const callbacks = [], listeners = new Set(), routes = [];
  let current = [], releases = 0;
  const scheduler = { observeActiveRuns: () => ({ subscribe(callback) {
    callbacks.push(callback); listeners.add(callback); callback(current);
    return () => { if (listeners.delete(callback)) releases++; };
  } }), run: () => { throw Error('Board must never start a job directly'); } };
  const Page = new Function('getProductKind', 'markWantRoutingReady', 'getDeepReadScheduler', 'router',
    'discoveryHotspotInputs', 'discoveryArticleParams', 'hotTopicSeedUrls', 'Math', code)(() => options.productKind ?? 'deepread', () => {},
      options.init ?? (async () => scheduler), { pushUrl: route => { routes.push(route); return options.routeError ? Promise.reject(Error(options.routeError)) : Promise.resolve(); } },
      discoveryHotspotInputs, discoveryArticleParams, pure.hotTopicSeedUrls, { floor: Math.floor, random: () => options.random ?? 0 });
  const page = new Page(); Object.assign(page, { dockScroll: { reset() {} }, pageVisible: false, firstShowDone: false, runObservationToken: 0,
    unsubscribeRuns: null, refreshToken: 0, requestController: null, loading: false, refreshing: false,
    activeRuns: [], activityError: '', topics: [], sections: [], loadHotlist: () => {}, loadIssue: () => {}, arrive: () => {}, saveDiscoveryScroll: () => {}, getUIContext: () => ({ getRouter: () => ({}) }) });
  return { page, scheduler, callbacks, listeners, routes, get releases() { return releases; },
    emit(runs) { current = runs; for (const callback of listeners) callback(runs); } };
}
const item = (title, rank = 1, url = '') => ({ title, rank, url, heat: '', summary: '真实摘要' });
const section = items => ({ sourceId: 'hn', sourceName: 'Hacker News', items, fetchedAt: 1, error: null });
const topic = () => ({ id: 'topic', title: '跨来源话题', sourceCount: 2, bestRank: 2, latestFetchedAt: 1,
  sources: [{ providerId: 'hn', providerName: 'Hacker News', ...item('无链接话题', 2) },
    { providerId: 'zhihu', providerName: '知乎', ...item('另一个报道', 5, 'https://news.test/story') }] });

test('visible Board observes admitted tasks and same-job real phase/label changes, then completion removes the card', async () => {
  const f = fixture(); f.page.onPageShow(); await tick(); assert.equal(f.listeners.size, 1);
  const run = { topicId: 'new', title: '很长的正在创作文章标题', startedAt: 100, stage: 'COLLECTING', label: '搜索公开资料' };
  f.emit([run]); assert.equal(f.page.activeRuns.length, 1); const firstKey = f.page.runKey(run);
  const updated = { ...run, stage: 'WRITING', label: '撰写正文' }; f.emit([updated]);
  assert.equal(f.page.activeRuns[0].stage, 'WRITING'); assert.notEqual(f.page.runKey(updated), firstKey);
  f.emit([]); assert.deepEqual(f.page.activeRuns, []);
  assert.ok(source.indexOf('DeepReadNewsroomCard({ run: run') > source.indexOf('Scroll()'));
});

test('hide/disappear release actual all-run subscription; queued callbacks are ignored and returning resubscribes', async () => {
  const f = fixture(); f.page.onPageShow(); await tick(); const old = f.callbacks[0];
  f.page.onPageHide(); assert.equal(f.listeners.size, 0); assert.equal(f.releases, 1);
  old([{ topicId: 'late', title: '迟到', startedAt: 2 }]); assert.deepEqual(f.page.activeRuns, []);
  f.emit([{ topicId: 'returned', title: '新任务', startedAt: 3 }]); f.page.onPageShow(); await tick();
  assert.equal(f.listeners.size, 1); assert.equal(f.page.activeRuns[0].topicId, 'returned');
  f.page.aboutToDisappear(); assert.equal(f.listeners.size, 0); assert.equal(f.releases, 2);
});

test('late scheduler initialization after hide cannot register, and stale failure cannot write visible error', async () => {
  const pending = deferred(), f = fixture({ init: () => pending.promise });
  f.page.onPageShow(); f.page.onPageHide(); pending.resolve(f.scheduler); await tick(); assert.equal(f.listeners.size, 0);
  const failure = deferred(), rejected = fixture({ init: () => failure.promise });
  rejected.page.onPageShow(); rejected.page.aboutToDisappear(); failure.reject(Error('late failure')); await tick();
  assert.equal(rejected.page.activityError, '');
});

test('runtime initialization errors remain visible while real hotspot reading remains available', async () => {
  const f = fixture({ init: async () => { throw Error('任务库读取失败'); } });
  f.page.onPageShow(); await tick(); assert.match(f.page.activityError, /任务库读取失败/);
  f.page.sections = [section([item('真实热点')])]; f.page.randomRead(); await tick(); assert.equal(f.routes.length, 1);
});

test('no-URL random hotspot preserves actual source/rank/summary and routes via production Article params without starting a job', async () => {
  const f = fixture(); f.page.sections = [section([item('Ask HN: evaluation', 4)])];
  assert.equal(f.routes.length, 0); f.page.randomRead(); await tick();
  const route = f.routes[0]; assert.equal(route.url, 'pages/DeepReadArticlePage');
  assert.match(route.params.topicId, /^discovery-/); assert.equal(route.params.sourceUrl, undefined);
  assert.deepEqual(JSON.parse(route.params.seedUrlsJson), []);
  const inputs = JSON.parse(route.params.inputSourcesJson); assert.equal(inputs.length, 1);
  assert.equal(inputs[0].url, null); assert.match(inputs[0].content, /来源：Hacker News/);
  assert.match(inputs[0].content, /排名：4/); assert.match(inputs[0].content, /真实摘要/);
  assert.equal(inputs[0].researchSource.source, 'Hacker News'); assert.equal(route.params.force, undefined);
  // Decorative arrival timers do not own generation; guard the actual entry paths.
  assert.doesNotMatch(['randomItems', 'randomRead', 'openArticle', 'openRun'].map(method).join('\n'),
    /scheduler\.run\(|setInterval|setTimeout|observeRunning\(/);
});

test('aggregated random choice preserves all real provider metadata and all valid source links', async () => {
  const f = fixture(); f.page.topics = [topic()]; f.page.sections = [section([item('其他来源条目')])];
  assert.equal(f.page.randomItems().length, 2); f.page.randomRead(); await tick();
  const params = f.routes[0].params, inputs = JSON.parse(params.inputSourcesJson);
  assert.equal(inputs.length, 2); assert.deepEqual(inputs.map(x => x.researchSource.source), ['Hacker News', '知乎']);
  assert.match(inputs[0].content, /排名：2/); assert.match(inputs[1].content, /排名：5/);
  assert.deepEqual(JSON.parse(params.seedUrlsJson), ['https://news.test/story']);
  assert.equal(params.sourceUrl, 'https://news.test/story'); assert.equal(params.title, '跨来源话题');
});

test('empty or invalid titles disable random reading and selection includes only current displayed source rows', async () => {
  const f = fixture({ random: 0.999 }); f.page.randomRead(); assert.equal(f.routes.length, 0);
  f.page.topics = [{ ...topic(), title: ' ' }]; f.page.sections = [section([item(' ')])];
  assert.equal(f.page.randomItems().length, 0); f.page.randomRead(); assert.equal(f.routes.length, 0);
  f.page.topics = []; f.page.sections = [section(Array.from({ length: 13 }, (_, i) => item('第' + i + '条', i)))];
  assert.equal(f.page.randomItems().length, 12); f.page.randomRead(); assert.equal(f.routes[0].params.title, '第11条');
});

test('progress entry reopens the existing topic without discovery input rewrite and route failures are visible only while shown', async () => {
  const f = fixture({ routeError: '路由失败' }); f.page.onPageShow(); await tick();
  f.page.openRun({ topicId: 'active', title: '原任务', startedAt: 1 }); await tick();
  assert.deepEqual(f.routes[0].params, { topicId: 'active', title: '原任务' }); assert.match(f.page.activityError, /路由失败/);
  f.page.activityError = ''; f.page.sections = [section([item('新热点')])]; f.page.randomRead(); f.page.onPageHide(); await tick();
  assert.equal(f.page.activityError, '');
});


test('chat and novel product Boards keep the original hotspot entry without initializing standalone newsroom runtime', async () => {
  for (const productKind of ['agent', 'novel']) {
    let initializations = 0;
    const f = fixture({ productKind, init: async () => { initializations++; throw Error('must not initialize'); } });
    f.page.onPageShow(); await tick(); assert.equal(initializations, 0); assert.equal(f.listeners.size, 0);
    const existing = { ...item('原宿主热点', 1, 'https://news.test/host'), seedUrls: ['https://news.test/host'],
      inputSources: discoveryHotspotInputs([{ title: '原宿主热点', source: '真实来源', rank: 1, url: 'https://news.test/host' }]) };
    f.page.openArticle(existing); await tick(); assert.equal(f.routes[0].params.sourceUrl, 'https://news.test/host');
  }
});
