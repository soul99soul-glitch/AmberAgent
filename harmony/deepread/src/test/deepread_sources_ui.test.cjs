const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const filename = path.resolve(__dirname, '../../../entry/src/main/ets/pages/DeepReadSourcesPage.ets');
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
function fixture() {
  const source = fs.readFileSync(filename, 'utf8');
  const fields = source.slice(source.indexOf('  @StorageProp('), source.indexOf('\n  build():')).replace(/@(?:StorageProp\([^)]*\)|State)\s*/g, '');
  const calls = [], subscriptions = [], reads = [];
  let topicId = 'topic-a', start = async () => {}, context = { startAbility: want => { calls.push(want); return start(want); } };
  const exports = {};
  vm.runInNewContext(ts.transpileModule('class SourcesFixture {' + fields + '}\nexports.Page = SourcesFixture;', {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText, { exports, Error, Promise, String, url: { URL: { parseURL: value => new URL(value) } },
    router: { getParams: () => ({ topicId }) }, AppStorage: { get: () => context },
    getRepository: () => ({ observe: () => ({ subscribe: fn => { const item = { fn, closed: false }; subscriptions.push(item); return () => { item.closed = true; }; } }),
      get: id => { const d = deferred(); reads.push({ id, ...d }); return d.promise; } }),
  }, { filename });
  return { page: new exports.Page(), source, calls, subscriptions, reads,
    setStart: fn => { start = fn; }, setContext: value => { context = value; }, setTopic: value => { topicId = value; } };
}
const source = (url, id = 'source') => ({ id, url, title: '原始来源', kind: 'url', status: 'ready', content: '正文', error: null, note: null });
const entry = title => ({ output: { inputSources: [source('https://example.com/' + title)], inputText: title } });

test('actual source action launches only absolute HTTP URLs through the system browser Want', async () => {
  const f = fixture(); f.page.aboutToAppear();
  for (const invalid of [null, 'file:///private/file', 'javascript:alert(1)', '/relative', 'https://', 'https://example.com/a b']) {
    assert.equal(f.page.sourceUrl(source(invalid)), '');
    await f.page.openSource(source(invalid));
  }
  assert.equal(f.calls.length, 0);
  await f.page.openSource(source('  HTTPS://example.com/中文?q=一#段落  '));
  assert.equal(f.calls.length, 1);
  assert.equal(f.calls[0].action, 'ohos.want.action.viewData');
  assert.deepEqual(Array.from(f.calls[0].entities), ['entity.system.browsable']);
  assert.equal(f.calls[0].uri, 'HTTPS://example.com/中文?q=一#段落');
  assert.equal(f.page.error, ''); assert.equal(f.page.openingId, '');
});

test('browser errors are visible, duplicate activation is blocked and unavailable context is explicit', async () => {
  const f = fixture(); f.page.aboutToAppear();
  const d = deferred(); f.setStart(() => d.promise);
  const pending = f.page.openSource(source('https://example.com'));
  await f.page.openSource(source('https://example.com/second'));
  assert.equal(f.calls.length, 1);
  d.reject(new Error('browser unavailable')); await pending;
  assert.match(f.page.error, /无法打开来源.*browser unavailable/); assert.equal(f.page.openingId, '');
  f.setContext(undefined); await f.page.openSource(source('https://example.com'));
  assert.match(f.page.error, /上下文/);
});

test('departed and reopened source pages reject old load, subscription and browser error callbacks', async () => {
  const f = fixture(); f.page.aboutToAppear();
  const oldRead = f.reads[0], oldSubscription = f.subscriptions[0];
  const d = deferred(); f.setStart(() => d.promise);
  const pending = f.page.openSource(source('https://example.com/old'));
  f.page.aboutToDisappear(); assert.equal(oldSubscription.closed, true);
  f.setTopic('topic-b'); f.page.aboutToAppear();
  f.subscriptions[1].fn(entry('current')); f.reads[1].resolve(entry('current')); await Promise.resolve();
  oldSubscription.fn(entry('stale-observed')); oldRead.resolve(entry('stale-read')); d.reject(new Error('stale browser'));
  await pending; await Promise.resolve();
  assert.equal(f.page.inputText, 'current'); assert.equal(f.page.error, ''); assert.equal(f.page.openingId, '');
});


test('a newer source observation wins over the older initial repository read in the same page', async () => {
  const f = fixture(); f.page.aboutToAppear();
  const fresh = entry('ready'); fresh.output.inputSources[0].status = 'ready';
  f.subscriptions[0].fn(fresh);
  const stale = entry('pending'); stale.output.inputSources[0].status = 'pending'; stale.output.inputSources[0].content = '';
  f.reads[0].resolve(stale); await Promise.resolve(); await Promise.resolve();
  assert.equal(f.page.sources[0].status, 'ready'); assert.equal(f.page.sources[0].content, '正文');
  assert.equal(f.page.inputText, 'ready');
});
