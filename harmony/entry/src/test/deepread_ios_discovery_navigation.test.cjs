const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const { discoveryHotspotInputs } = require('../../../deepread/src/main/ets/domain/discovery_input.ts');

const root = path.join(__dirname, '../main/ets');
const source = name => fs.readFileSync(path.join(root, name), 'utf8');
function method(text, name) {
  const match = new RegExp('^  (?:private )?(?:async )?' + name + '\\(', 'm').exec(text);
  assert.ok(match, name);
  let end = text.indexOf('{', match.index) + 1, depth = 1;
  for (; depth && end < text.length; end++) {
    if (text[end] === '{') depth++;
    if (text[end] === '}') depth--;
  }
  return text.slice(match.index, end);
}
function controller(text, methods, dependencies) {
  const js = ts.transpileModule('class Controller {\n' + methods.map(name => method(text, name)).join('\n') + '\n} return Controller;',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  return new (new Function(...Object.keys(dependencies), js)(...Object.values(dependencies)))();
}
const tick = () => new Promise(resolve => setImmediate(resolve));

test('direct discovery cards preserve every report and no-URL source metadata before opening an article', () => {
  const pure = {};
  const vm = require('node:vm');
  vm.runInNewContext(ts.transpileModule(source('platform_impl/HotListState.ets'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports: pure });
  const page = controller(source('pages/BoardPage.ets'), ['topicActionItem', 'sourceActionItem'], {
    discoveryHotspotInputs, hotTopicSeedUrls: pure.hotTopicSeedUrls,
  });
  const item = (title, rank, url) => ({ title, rank, url, heat: '', summary: '原始摘要' });
  const topic = { title: '多方报道', displayTitle: '多方报道译文', bestRank: 1, sources: [
    { ...item('讨论', 1, ''), providerName: '知乎' },
    { ...item('原文', 2, 'https://news.test/a'), providerName: 'IT之家' },
    { ...item('补充原文', 3, 'https://news.test/b'), providerName: '少数派' },
  ] };
  const selected = page.topicActionItem(topic);
  assert.deepEqual(Array.from(selected.seedUrls), ['https://news.test/a', 'https://news.test/b']);
  assert.equal(selected.displayTitle, '多方报道译文'); assert.equal(selected.inputSources.length, 3);
  assert.match(selected.inputSources[0].content, /来源：知乎\n排名：1/);
  assert.equal(selected.inputSources[0].url, null);
  const discussion = page.sourceActionItem(item('无链接讨论', 4, ''), { sourceName: 'Hacker News' });
  assert.deepEqual(discussion.seedUrls, []); assert.equal(discussion.inputSources[0].url, null);
  assert.match(discussion.inputSources[0].content, /来源：Hacker News\n排名：4[\s\S]*原始摘要/);
});

test('masthead issue comes from real library size and a late old-page result cannot overwrite a returning page', async () => {
  let resolve;
  const pending = new Promise(done => { resolve = done; });
  const page = controller(source('pages/BoardPage.ets'), ['loadIssue'], {
    getRepository: () => ({ listHistory: () => pending }),
  });
  Object.assign(page, { pageVisible: true, runObservationToken: 1, issue: 0 });
  const work = page.loadIssue(); page.pageVisible = false; page.runObservationToken++;
  page.pageVisible = true; resolve([{}, {}, {}]); await work;
  assert.equal(page.issue, 0);
  const live = controller(source('pages/BoardPage.ets'), ['loadIssue'], {
    getRepository: () => ({ listHistory: async () => [{}, {}, {}] }),
  });
  Object.assign(live, { pageVisible: true, runObservationToken: 1, issue: 0 });
  await live.loadIssue(); assert.equal(live.issue, 4);
});

test('festival matches iOS lunar dates and excludes leap months', () => {
  const masthead = controller(source('components/deepread/DeepReadDiscoveryHeader.ets'), ['lunarFestival'], {});
  assert.equal(masthead.lunarFestival(new Date(2026, 1, 17)), '新春 · 开卷有益');
  assert.equal(masthead.lunarFestival(new Date(2026, 2, 3)), '元宵 · 灯下读');
  assert.equal(masthead.lunarFestival(new Date(2026, 8, 25)), '中秋 · 月下读');
  assert.equal(masthead.lunarFestival(new Date(2025, 6, 26)), '');
});

test('discovery offset waits for actual content and restores once after its root was replaced', () => {
  const stored = new Map(), moves = [];
  const page = controller(source('pages/BoardPage.ets'), ['saveDiscoveryScroll', 'restoreDiscoveryScroll'], {
    AppStorage: { get: key => stored.get(key), setOrCreate: (key, value) => stored.set(key, value) },
  });
  Object.assign(page, { topics: [], sections: [], restoreDiscoveryOffset: true,
    discoveryScroller: { currentOffset: () => ({ yOffset: 720 }), scrollTo: value => moves.push(value) } });
  page.saveDiscoveryScroll(); assert.equal(stored.get('deepreadDiscoveryScroll'), 720);
  page.restoreDiscoveryScroll(); assert.equal(moves.length, 0);
  page.sections = [{ sourceId: 'hn' }]; page.restoreDiscoveryScroll();
  assert.deepEqual(moves, [{ xOffset: 0, yOffset: 720, animation: false }]);
  page.restoreDiscoveryScroll(); assert.equal(moves.length, 1, 'a later refresh must not pull the reader back');
});

test('each source retains its own collapsed state across tab replacement without changing another source', () => {
  const stored = new Map();
  const make = sourceId => {
    const value = controller(source('components/deepread/DeepReadDiscoveryCards.ets'), ['aboutToAppear', 'toggleExpanded'], {
      AppStorage: { get: key => stored.get(key), setOrCreate: (key, value) => stored.set(key, value) },
    });
    Object.assign(value, { section: { sourceId }, expanded: true }); value.aboutToAppear(); return value;
  };
  const hn = make('hn'); hn.toggleExpanded(); assert.equal(hn.expanded, false);
  const returned = make('hn'); assert.equal(returned.expanded, false);
  const zhihu = make('zhihu'); assert.equal(zhihu.expanded, true); zhihu.toggleExpanded();
  returned.toggleExpanded(); assert.equal(make('hn').expanded, true); assert.equal(make('zhihu').expanded, false);
});

test('discovery composer is dismissed only after its saved article opens; rejection keeps the draft sheet available', async () => {
  let props;
  let rejectRoute = true;
  const routes = [];
  const page = controller(source('pages/BoardPage.ets'), ['ComposerSheet'], {
    DeepReadComposerSheet: value => { props = value; },
    router: { pushUrl: route => {
      routes.push(route); return rejectRoute ? Promise.reject(Error('路由不可用')) : Promise.resolve();
    } },
  });
  page.composeOpen = true; page.ComposerSheet(); assert.equal(props.embedded, true);
  await assert.rejects(props.onCreated('saved-draft', '已保存主题'), /路由不可用/);
  assert.equal(page.composeOpen, true);
  rejectRoute = false; await props.onCreated('saved-draft', '已保存主题');
  assert.equal(page.composeOpen, false); assert.equal(routes.length, 2);
  assert.deepEqual(routes[0], routes[1]); assert.equal(routes[0].params.topicId, 'saved-draft');
});
