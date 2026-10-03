const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const implementationDir = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl');
const plain = value => JSON.parse(JSON.stringify(value));
const hilog = { info() {}, warn() {}, error() {} };

function loadImplementation(name, dependencies = {}) {
  const filename = path.join(implementationDir, name);
  const exportsObject = {};
  const output = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  vm.runInNewContext(output, {
    exports: exportsObject, Set, Map, JSON, Date, Math, RegExp, Promise,
    require(id) {
      assert.ok(Object.hasOwn(dependencies, id), `Unexpected runtime import: ${id}`);
      return dependencies[id];
    },
  }, { filename });
  return exportsObject;
}

const rssParser = loadImplementation('HotListRss.ets');
const discovery = {};
const discoveryPath = path.resolve(__dirname, '../main/ets/domain/discovery.ts');
vm.runInNewContext(ts.transpileModule(fs.readFileSync(discoveryPath, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText, { exports: discovery, JSON, Math, Number, Array, RegExp, Error }, { filename: discoveryPath });
const { createNewsNowProvider } = loadImplementation('NewsNowProvider.ets', {
  '@kit.PerformanceAnalysisKit': { hilog }, './HotListRss.ets': rssParser, '@amber/deepread-domain': discovery,
});
const rss = items => `<rss><channel>${items.map(([title, url]) =>
  `<item><title><![CDATA[${title}]]></title><link>${url}</link></item>`).join('')}</channel></rss>`;

function httpFixture(respond) {
  const requests = [];
  return {
    requests,
    http: {
      async fetch(request, options) {
        requests.push({ request, options });
        return respond(request);
      },
    },
  };
}

test('fetchAllSources 只发启用源 HTTP，未知和重复启用项不会扩大调用范围', async () => {
  const fixture = httpFixture(() => ({ status: 200, body: JSON.stringify({
    items: [{ title: '微博新闻', url: 'https://weibo.test/news', extra: { info: '100 热度' } }],
  }) }));
  const signal = { aborted: false };
  const result = await createNewsNowProvider(fixture.http).fetchAllSources(signal, ['weibo', 'weibo', 'unknown']);
  assert.equal(fixture.requests.length, 1);
  assert.equal(fixture.requests[0].request.url, 'https://newsnow.busiyi.world/api/s?id=weibo');
  assert.equal(fixture.requests[0].options.signal, signal);
  assert.deepEqual(plain(result.map(section => section.sourceId)), ['weibo']);
  assert.deepEqual(plain(result[0].items), [{
    rank: 1, title: '微博新闻', url: 'https://weibo.test/news', heat: '100 热度',
  }]);
});

test('没有启用源时不外呼，也不回落到全部预设', async () => {
  const fixture = httpFixture(() => { throw new Error('should not fetch'); });
  const result = await createNewsNowProvider(fixture.http).fetchAllSources(undefined, []);
  assert.deepEqual(plain(result), []);
  assert.equal(fixture.requests.length, 0);
});

test('英语 AI 源走实际 feed HTTP 和 RSS 解析，Arxiv 双 feed 交错取条目且跨 feed 去重', async () => {
  const feedBodies = {
    'https://rss.arxiv.org/rss/cs.AI': rss([
      ['AI first', 'https://arxiv.org/abs/ai-first'],
      ['Shared paper', 'https://arxiv.org/abs/shared'],
    ]),
    'https://rss.arxiv.org/rss/cs.CL': rss([
      ['CL first', 'https://arxiv.org/abs/cl-first'],
      ['Shared paper', 'https://arxiv.org/abs/shared'],
    ]),
    'https://feed.infoq.com/ai-ml-data-eng/': rss([
      ['InfoQ AI & ML', 'https://www.infoq.com/news/ai/?a=1&amp;b=2'],
    ]),
  };
  const fixture = httpFixture(request => {
    assert.ok(Object.hasOwn(feedBodies, request.url), `Unexpected feed URL: ${request.url}`);
    return { status: 200, body: feedBodies[request.url] };
  });
  const signal = { aborted: false };
  const result = await createNewsNowProvider(fixture.http).fetchAllSources(signal, ['arxiv_ai', 'infoq_ai']);
  assert.deepEqual(fixture.requests.map(call => call.request.url).sort(), Object.keys(feedBodies).sort());
  assert.ok(fixture.requests.every(call => call.options.signal === signal));
  const arxiv = result.find(section => section.sourceId === 'arxiv_ai');
  const infoq = result.find(section => section.sourceId === 'infoq_ai');
  assert.deepEqual(plain(arxiv.items.map(item => [item.rank, item.title])), [
    [1, 'AI first'], [2, 'CL first'], [3, 'Shared paper'],
  ]);
  assert.equal(arxiv.error, null);
  assert.equal(infoq.items[0].title, 'InfoQ AI & ML');
  assert.equal(infoq.items[0].url, 'https://www.infoq.com/news/ai/?a=1&b=2');
  assert.equal(infoq.error, null);
  assert.ok(arxiv.fetchedAt > 0 && infoq.fetchedAt > 0);
});

test('Arxiv 单 feed 失败仍返回成功 feed 的条目和可见错误', async () => {
  const fixture = httpFixture(request => request.url.endsWith('cs.AI')
    ? { status: 503, body: 'unavailable' }
    : { status: 200, body: rss([['Language models', 'https://arxiv.org/abs/language']]) });
  const result = await createNewsNowProvider(fixture.http).fetchAllSources(undefined, ['arxiv_ai']);
  assert.equal(fixture.requests.length, 2);
  assert.equal(result[0].error, 'HTTP 503');
  assert.deepEqual(plain(result[0].items), [{
    rank: 1, title: 'Language models', url: 'https://arxiv.org/abs/language', heat: '',
  }]);
});

test('RSS HTTP 成功但正文无有效条目时报告错误，不伪造新闻', async () => {
  const fixture = httpFixture(() => ({ status: 200, body: '<html>not a feed</html>' }));
  const result = await createNewsNowProvider(fixture.http).fetchAllSources(undefined, ['infoq_ai']);
  assert.deepEqual(plain(result[0].items), []);
  assert.equal(result[0].error, '订阅源未返回有效条目');
});

test('预先取消不会开始 NewsNow 或 RSS 外呼', async () => {
  const fixture = httpFixture(() => { throw new Error('should not fetch'); });
  const provider = createNewsNowProvider(fixture.http);
  assert.deepEqual(plain(await provider.fetchAllSources({ aborted: true }, ['weibo', 'arxiv_ai', 'infoq_ai'])), []);
  assert.equal(fixture.requests.length, 0);
});

test('P6 nine additional discovery sources route real HN HF GitHub and NewsNow calls', async () => {
  const requested = ['hacker_news', 'huggingface_papers', 'github_trending_ai', 'ithome', 'sspai', 'juejin', 'coolapk', 'xueqiu-hotstock', 'hupu-zhugandaoretie'];
  const fixture = httpFixture(request => {
    if (request.url.endsWith('/topstories.json')) return { status: 200, body: '[41,42]' };
    if (request.url.endsWith('/item/41.json')) return { status: 200, body: JSON.stringify({ title: 'Ask HN: reliable evaluation', score: 12 }) };
    if (request.url.endsWith('/item/42.json')) return { status: 200, body: JSON.stringify({ title: 'Dead post', dead: true }) };
    if (request.url === 'https://huggingface.co/api/daily_papers') return { status: 200, body: JSON.stringify([{ paper: { id: '2610.00001', title: 'Paper title', summary: 'Actual paper summary' }, upvotes: 7 }]) };
    if (request.url === 'https://github.com/trending') return { status: 200, body: '<article><h2><a href="/owner/repo">Repo</a></h2><p>Actual description</p></article>' };
    return { status: 200, body: JSON.stringify({ items: [{ title: '国内热点', extra: { info: '100' } }] }) };
  });
  const signal = { aborted: false };
  const result = await createNewsNowProvider(fixture.http).fetchAllSources(signal, requested);
  assert.equal(result.length, 9);
  assert.equal(result.find(section => section.sourceId === 'hacker_news').items[0].url, '', 'HN without URL stays without URL');
  assert.equal(result.find(section => section.sourceId === 'hacker_news').items.length, 1);
  assert.equal(result.find(section => section.sourceId === 'huggingface_papers').items[0].summary, 'Actual paper summary');
  assert.equal(result.find(section => section.sourceId === 'github_trending_ai').items[0].url, 'https://github.com/owner/repo');
  assert.ok(fixture.requests.every(call => call.options.signal === signal));
  assert.equal(result.find(section => section.sourceId === 'ithome').items[0].url, '', 'NewsNow title/rank remain usable without guessed URL');
});

test('P6 HN cancellation after top IDs prevents item fetch; failed JSON is a visible source error', async () => {
  const signal = { aborted: false };
  const fixture = httpFixture(request => {
    if (request.url.endsWith('/topstories.json')) { signal.aborted = true; return { status: 200, body: '[7,8]' }; }
    throw new Error('item fetch after cancellation');
  });
  const result = await createNewsNowProvider(fixture.http).fetchAllSources(signal, ['hacker_news']);
  assert.equal(fixture.requests.length, 1);
  assert.match(result[0].error, /取消/);
  const malformed = httpFixture(() => ({ status: 200, body: '{broken' }));
  const failed = await createNewsNowProvider(malformed.http).fetchAllSources(undefined, ['huggingface_papers']);
  assert.equal(failed[0].items.length, 0);
  assert.ok(failed[0].error);
});
