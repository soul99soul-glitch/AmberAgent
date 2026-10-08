const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const entryRoot = path.resolve(__dirname, '../../../entry/src/main/ets');
const compile = source => ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const loadPureModule = name => {
  const filename = path.join(entryRoot, 'platform_impl', name + '.ets');
  const exports = {};
  vm.runInNewContext(compile(fs.readFileSync(filename, 'utf8')), { exports }, { filename });
  return exports;
};
const state = loadPureModule('HotListState');
const aggregator = loadPureModule('HotListAggregator');
const loadDomain = name => {
  const filename = path.resolve(__dirname, '../main/ets/domain', name + '.ts');
  const exports = {};
  vm.runInNewContext(compile(fs.readFileSync(filename, 'utf8')), {
    exports, require: request => loadDomain(path.basename(request, '.ts')),
  }, { filename });
  return exports;
};
const discovery = loadDomain('discovery_input');
const boardSource = fs.readFileSync(path.join(entryRoot, 'pages/BoardPage.ets'), 'utf8');
// Run the actual non-UI page methods, without interpreting ArkUI's builder DSL.
const extractMethod = name => {
  const pattern = new RegExp('^  (?:private\\s+)?(?:async\\s+)?' + name + '\\(', 'm');
  const start = boardSource.search(pattern);
  assert.ok(start >= 0, 'Board method is present: ' + name);
  const open = boardSource.indexOf('{', start);
  let depth = 1;
  let end = open + 1;
  for (; depth > 0 && end < boardSource.length; end++) {
    if (boardSource[end] === '{') depth++;
    if (boardSource[end] === '}') depth--;
  }
  return boardSource.slice(start, end);
};
const methods = ['applyProjection', 'loadHotlist', 'stopRefresh', 'onPageShow',
  'showTopicActions', 'topicSheetDuration', 'openArticle'].map(extractMethod).join('\n');
const selection = boardSource.match(/const seeds: string\[\] = hotTopicSeedUrls\(tp\);[\s\S]*?this\.showTopicActions\(first, seeds, discoveryHotspotInputs\(hotspots\)\);/)[0];
const plain = value => JSON.parse(JSON.stringify(value));
const item = (title, rank = 1, url = 'https://example.test/' + rank) => ({ rank, title, url, heat: '' });
const section = (id, items, fetchedAt = Date.now(), error = null) => ({
  sourceId: id, sourceName: id, items, fetchedAt, error,
});
const deferred = () => {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
};

function harness(options = {}) {
  const values = Object.assign({ source_one: 'true', source_two: 'false' }, options.values);
  const reads = [];
  const writes = [];
  const fetches = [];
  const translations = [];
  const routes = [];
  const toasts = [];
  const storage = {
    async get(key, fallback) {
      reads.push(key);
      if (options.get) {
        const override = options.get(key);
        if (override !== undefined) return override;
      }
      return Object.hasOwn(values, key) ? values[key] : fallback;
    },
    async set(key, value) { values[key] = value; writes.push({ key, value }); },
  };
  const context = {
    ...state, ...aggregator, ...discovery, console, JSON, Date, Number,
    getProductKind: () => 'agent', markWantRoutingReady: () => {},
    HOTLIST_CACHE_KEY: 'deepread_hotlist_cache',
    HOTLIST_PRESETS: [{ id: 'one' }, { id: 'two' }],
    MOTION_OVERLAY: 200,
    Curve: { EaseOut: 'EaseOut' },
    animateTo: (_options, action) => action(),
    getAppContainer: () => ({ storage, httpClient: {} }),
    createEntryAbortController: () => {
      const signal = { aborted: false };
      return { signal, abort() { signal.aborted = true; } };
    },
    createNewsNowProvider: () => ({
      async fetchAllSources(signal, ids) {
        fetches.push({ signal, ids: plain(ids) });
        return options.fetch ? options.fetch(signal, ids) : [section('one', [item('New research')])];
      },
    }),
    connection: {
      NetBearType: { BEARER_WIFI: 1 },
      async getDefaultNet() { return { netId: 7 }; },
      async getNetCapabilities() { return { bearerTypes: options.wifi === false ? [0] : [1] }; },
    },
    async translateHotListTitles(titles, signal) {
      translations.push({ titles: plain(titles), signal });
      if (options.translate) return options.translate(titles, signal);
      return { 'New research': '新研究' };
    },
    router: { pushUrl: route => { routes.push(plain(route)); return Promise.resolve(); } },
    promptAction: { showToast: toast => toasts.push(plain(toast)) },
    module: { exports: {} },
  };
  vm.runInNewContext(compile(`module.exports = class BoardHarness {
    ${methods}
    selectTopic(tp: HotTopic): void { ${selection} }
  };`), context, { filename: 'BoardPage.actual-methods.ts' });
  const page = new context.module.exports();
  Object.assign(page, {
    rawSections: [], enabledSourceIds: [], focusKeywords: [], focusMode: 'all',
    refreshMinutes: 60, wifiOnly: false, translateTitles: false, cacheLoaded: false,
    pageVisible: true, firstShowDone: true, refreshToken: 0, requestController: null,
    runObservationToken: 0, unsubscribeRuns: null, observeRuns: () => {}, loadIssue: () => {}, arrive: () => {}, saveDiscoveryScroll: () => {},
    loading: false, refreshing: false, sections: [], topics: [], lastUpdatedAt: 0,
    hotlistError: '', hotlistNote: '', sheetItem: null,
  });
  return { page, values, reads, writes, fetches, translations, routes, toasts };
}

test('真实 Board 投影先关注筛选/排序，global 第 11 话题仍可达且呈现上限为 10', () => {
  const h = harness();
  const titles = ['甲', '乙', '丙', '丁', '戊', '己', '庚', '辛', '壬', '癸', '关注'];
  h.page.rawSections = [section('one', titles.map((title, index) => item(title, index + 1)))];
  h.page.enabledSourceIds = ['one'];
  h.page.focusKeywords = ['关注'];
  h.page.focusMode = 'focus_only';
  h.page.applyProjection();
  assert.deepEqual(plain(h.page.topics.map(topic => topic.title)), ['关注']);
  assert.equal(h.page.sections[0].items[0].rank, 11);
  h.page.focusMode = 'focus_first';
  h.page.applyProjection();
  assert.equal(h.page.topics[0].title, '关注');
  assert.equal(h.page.topics.length, 10);
  h.page.focusMode = 'all';
  h.page.applyProjection();
  assert.equal(h.page.topics[0].title, '甲');
  assert.equal(h.page.topics.length, 10);
  assert.equal(h.page.rawSections[0].items.length, 11);
});

test('真实 Board 从持久原始缓存读取，freshness 跳过网络而手动刷新只发启用源', async () => {
  const raw = [section('one', [item('Cached research')]), section('two', [item('Hidden source')])];
  const h = harness({ values: { deepread_hotlist_cache: JSON.stringify({ sections: raw }) } });
  await h.page.loadHotlist(false);
  assert.equal(h.fetches.length, 0);
  assert.equal(h.page.topics[0].title, 'Cached research');
  await h.page.loadHotlist(true);
  assert.deepEqual(h.fetches[0].ids, ['one']);
  assert.equal(h.page.topics[0].title, 'New research');
  assert.equal(JSON.parse(h.values.deepread_hotlist_cache).sections.length, 2);
  h.values.source_one = 'false';
  h.values.source_two = 'true';
  h.page.onPageShow();
  while (h.page.loading) await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.page.topics[0].title, 'Hidden source');
  assert.equal(h.fetches.length, 1);
});

test('真实 Board Wi-Fi 偏好同时阻止热榜 HTTP 与标题翻译并保留缓存', async () => {
  const raw = [section('one', [item('Cached research')])];
  const h = harness({ wifi: false, values: {
    deepread_hotlist_cache: JSON.stringify({ sections: raw }),
    deepread_hotlist_wifi_only: 'true', deepread_hotlist_translate_zh: 'true',
  } });
  await h.page.loadHotlist(true);
  assert.equal(h.fetches.length, 0);
  assert.equal(h.translations.length, 0);
  assert.equal(h.page.topics[0].title, 'Cached research');
  assert.match(h.page.hotlistNote, /未连接 Wi-Fi/);
  assert.equal(h.page.loading, false);
});

test('真实 Board 翻译失败局部报错，离页会 abort 同一翻译 signal 且迟到结果不写回', async () => {
  const failed = harness({ values: { deepread_hotlist_translate_zh: 'true' },
    translate: async () => { throw new Error('provider unavailable'); } });
  await failed.page.loadHotlist(true);
  assert.equal(failed.page.topics[0].title, 'New research');
  assert.match(failed.page.hotlistNote, /保留原文/);

  const pending = deferred();
  const started = deferred();
  const h = harness({ values: { deepread_hotlist_translate_zh: 'true' },
    translate: async () => { started.resolve(); return pending.promise; } });
  const load = h.page.loadHotlist(true);
  await started.promise;
  const writesBeforeHide = h.writes.length;
  h.page.stopRefresh();
  assert.equal(h.translations[0].signal.aborted, true);
  pending.resolve({ 'New research': '迟到译文' });
  await load;
  assert.equal(h.page.rawSections[0].items[0].displayTitle, undefined);
  assert.equal(h.writes.length, writesBeforeHide);
});

test('真实 Board 离页期间缓存读取晚到，不赋值或启动网络请求', async () => {
  const pending = deferred();
  const started = deferred();
  const h = harness({ get: key => {
    if (key !== 'deepread_hotlist_cache') return undefined;
    started.resolve();
    return pending.promise;
  } });
  const load = h.page.loadHotlist(false);
  await started.promise;
  h.page.stopRefresh();
  pending.resolve(JSON.stringify({ sections: [section('one', [item('Late cache')])] }));
  await load;
  assert.equal(h.page.rawSections.length, 0);
  assert.equal(h.fetches.length, 0);
  assert.equal(h.page.loading, false);
});

test('真实 Board 聚合项携带全部热点来源资料，含无 URL 条目也能进入阅读', () => {
  const h = harness();
  h.page.selectTopic({ title: 'Same topic', bestRank: 1, sources: [
    { title: '无链接讨论', providerName: 'HN', rank: 1, url: '', heat: '' },
    { title: '正文一', providerName: '少数派', rank: 2, url: 'https://one.test/a', heat: '', summary: '报道摘要' },
    { title: '正文二', providerName: '掘金', rank: 3, url: 'https://two.test/b', heat: '' },
    { title: '正文一', providerName: 'IT之家', rank: 4, url: 'https://one.test/a', heat: '' },
  ] });
  const snapshot = h.page.sheetItem;
  assert.equal(snapshot.url, 'https://one.test/a');
  h.page.openArticle(snapshot, true);
  assert.equal(h.routes[0].url, 'pages/DeepReadArticlePage');
  assert.equal(h.routes[0].params.topicId, 'https://one.test/a');
  assert.deepEqual(JSON.parse(h.routes[0].params.seedUrlsJson), ['https://one.test/a', 'https://two.test/b']);
  assert.equal(h.routes[0].params.force, 'true');
  const inputs = JSON.parse(h.routes[0].params.inputSourcesJson);
  assert.equal(inputs.length, 4);
  assert.equal(inputs[0].status, 'ready');
  assert.equal(inputs[0].url, null);
  assert.match(inputs[0].content, /无链接讨论\n来源：HN\n排名：1/);
  assert.match(inputs[1].content, /报道摘要/);
  h.page.selectTopic({ title: 'No link', bestRank: 1, sources: [
    { title: '离线话题', providerName: 'HN', rank: 1, url: '', heat: '' },
  ] });
  h.page.openArticle(h.page.sheetItem, false);
  assert.equal(h.routes.length, 2);
  assert.match(h.routes[1].params.topicId, /^discovery-/);
  assert.equal(h.routes[1].params.sourceUrl, undefined);
  assert.deepEqual(JSON.parse(h.routes[1].params.seedUrlsJson), []);
  assert.equal(JSON.parse(h.routes[1].params.inputSourcesJson)[0].status, 'ready');
  assert.equal(h.toasts.length, 0);
});

test('真实 Board 系统返回先关闭话题 sheet，随后才允许页面返回', () => {
  const animations = [];
  const context = {
    module: { exports: {} }, Curve: { EaseIn: 'EaseIn' }, getProductKind: () => 'agent',
    animateTo: (options, action) => { animations.push(plain(options)); action(); },
  };
  vm.runInNewContext(compile(`module.exports = class BoardBackFixture {
    ${extractMethod('onBackPress')}
    ${extractMethod('topicSheetDuration')}
  };`), context);
  const page = new context.module.exports();
  const controller = { aborted: false };
  Object.assign(page, { sheetItem: null, pageVisible: true, requestController: controller });
  assert.equal(page.onBackPress(), false);
  assert.equal(animations.length, 0);
  page.sheetItem = { title: '话题', url: 'https://example.test/topic', seedUrls: ['https://example.test/topic'] };
  assert.equal(page.onBackPress(), true);
  assert.equal(page.sheetItem, null);
  assert.deepEqual(animations, [{ duration: 200, curve: 'EaseIn' }]);
  assert.equal(page.pageVisible, true);
  assert.equal(page.requestController, controller);
  assert.equal(page.onBackPress(), false);
  assert.equal(animations.length, 1);
});
