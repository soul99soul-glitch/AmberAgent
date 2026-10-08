const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('typescript');
const { entryRoot, loadPureModule, method, actualPage } = require('./deepread_ui_fixture.cjs');
const hierarchy = loadPureModule(path.join(entryRoot, 'components/deepread/DeepReadArticleHierarchy.ets'));
const { makeEmptyDeepReadOutput } = loadPureModule(path.join(entryRoot, '../../../..', 'deepread/src/main/ets/domain/models.ts'));
const editions = ['components/DeepReadNativeArticleBody.ets', 'components/deepread/DeepReadArticleMagazine.ets']
  .map(file => ({ file, source: fs.readFileSync(path.join(entryRoot, file), 'utf8') }));

test('legacy references remain readable while new numbered sources preserve citation order', () => {
  const output = makeEmptyDeepReadOutput();
  output.references = [{ title: '旧来源', url: 'https://old.test/a', source: null, publishedAt: null }];
  assert.equal(hierarchy.nativeArticleSources(output), output.references);
  output.sources = [{ title: '摘录正文', url: '', source: '用户摘录', publishedAt: null },
    { title: '新来源', url: 'https://new.test/b', source: null, publishedAt: null }];
  assert.equal(hierarchy.nativeArticleSources(output), output.sources);
  output.sources = [];
  assert.equal(hierarchy.nativeArticleSources(output), output.references);
});

test('resumed mixed drafts retain old references as unnumbered supplementary sources', () => {
  const output = makeEmptyDeepReadOutput();
  const link = (title, url) => ({ title, url, source: null, publishedAt: null });
  const old = link('旧参考A', 'https://old.test/a');
  const fresh = link('新来源B', 'https://new.test/b');
  const text = link('输入摘录', '');
  const extra = link('扩展C', 'https://reading.test/c');
  output.references = [old, fresh, text, old];
  output.extendedReading = [extra, extra];
  output.sources = [fresh, text];
  assert.equal(hierarchy.nativeArticleSources(output), output.sources, 'generation list is never renumbered');
  const displayed = hierarchy.nativeArticleSources(output).concat(hierarchy.nativeSupplementarySources(output));
  assert.deepEqual(Array.from(displayed, value => value.title), ['新来源B', '输入摘录', '旧参考A', '扩展C']);
  assert.deepEqual(Array.from(hierarchy.nativeSupplementarySources(output), value => value.title), ['旧参考A', '扩展C']);
  assert.deepEqual(Array.from(hierarchy.nativeCitationNumbers(output, [1, 2, 3])), [1, 2], 'old supplement never gains a generation citation');
  output.sources = [];
  assert.deepEqual(Array.from(hierarchy.nativeSupplementarySources(output)), [], 'old drafts retain their existing references section');
});

test('citations discard out-of-range, fractional and duplicate ids without reassigning a number', () => {
  const output = makeEmptyDeepReadOutput();
  output.sources = [{ url: '' }, { url: 'https://source.test/b' }, { url: 'https://source.test/c' }];
  assert.deepEqual(Array.from(hierarchy.nativeCitationNumbers(output, [3, 0, 2, 4, -1, 2, 1.5, NaN])), [3, 2]);
  assert.deepEqual(Array.from(hierarchy.nativeCitationNumbers(output, undefined)), []);
  delete output.sources;
  output.references = [{ url: 'https://legacy.test' }];
  assert.deepEqual(Array.from(hierarchy.nativeCitationNumbers(output, [1])), [], 'old references have no generation citation contract');
});

test('native citation and source links only admit HTTP or HTTPS destinations', () => {
  for (const url of ['https://news.test/a?b=1#x', 'http://news.test', ' HTTPS://news.test/a ']) {
    assert.equal(hierarchy.nativeArticleLinkUrl(url), url.trim());
  }
  for (const url of ['', 'https://', 'javascript:alert(1)', 'file:///private/a', 'data:text/html,x',
    'https://news.test/a b', 'https://news.test/<script>', 'https://news.test/"x', 'https:///path']) {
    assert.equal(hierarchy.nativeArticleLinkUrl(url), '');
  }
});

test('the numbered source list marks only the sources cited by a judgment or stance', () => {
  const output = makeEmptyDeepReadOutput();
  output.sources = [{ url: '' }, { url: '' }, { url: '' }];
  output.corePoints = [{ point: '判断', sources: [1, 9] }];
  output.analysis.perspectives = [{ viewpoint: '立场', sources: [3] }];
  assert.equal(hierarchy.nativeArticleSourceCited(output, 1), true);
  assert.equal(hierarchy.nativeArticleSourceCited(output, 2), false);
  assert.equal(hierarchy.nativeArticleSourceCited(output, 3), true);
  assert.equal(hierarchy.nativeArticleSourceCited(output, 9), false);
});

function actualCitationHandler(source) {
  const body = method(source, 'Citations');
  const callback = /\.onClick\(\(\): void => \{([\s\S]*?)\n\s*\}\)/.exec(body);
  assert.ok(callback, 'actual citation click handler');
  const code = ts.transpileModule(callback[1], { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  return new Function('id', 'nativeArticleLinkUrl', code);
}

for (const edition of editions) {
  test(`${edition.file}: new drafts move extended reading to supplementary sources exactly once`, () => {
    const owner = actualPage(edition.file, ['standaloneExtendedReading'], {});
    owner.output = makeEmptyDeepReadOutput();
    const extra = { title: '扩展C', url: 'https://reading.test/c', source: null, publishedAt: null };
    owner.output.extendedReading = [extra];
    assert.equal(owner.standaloneExtendedReading(), owner.output.extendedReading, 'old drafts retain the original section');
    owner.output.sources = [{ title: '生成B', url: 'https://source.test/b', source: null, publishedAt: null }];
    owner.output.references = [extra];
    const visibleExtras = owner.standaloneExtendedReading().concat(hierarchy.nativeSupplementarySources(owner.output));
    assert.deepEqual(Array.from(visibleExtras, link => link.title), ['扩展C']);
    assert.deepEqual(Array.from(owner.standaloneExtendedReading()), [], 'the separate section is suppressed with numbered sources');
    owner.output.sources = [];
    assert.equal(owner.standaloneExtendedReading(), owner.output.extendedReading);
  });

  test(`${edition.file}: citations open their numbered source and never unsafe or empty URLs`, () => {
    const opened = [], output = makeEmptyDeepReadOutput();
    output.sources = [{ url: 'https://first.test' }, { url: '' }, { url: 'javascript:alert(1)' }, { url: 'https://fourth.test' }];
    const owner = { output, onOpenLink: url => opened.push(url) };
    const handler = actualCitationHandler(edition.source);
    for (const id of [4, 1, 2, 3]) handler.call(owner, id, hierarchy.nativeArticleLinkUrl);
    assert.deepEqual(opened, ['https://fourth.test', 'https://first.test']);
  });

}

