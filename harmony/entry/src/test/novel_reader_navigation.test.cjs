const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');

function fixture() {
  const source = fs.readFileSync(path.join(__dirname, '../main/ets/pages/NovelChapterReaderPage.ets'), 'utf8');
  const method = source.slice(source.indexOf('  private jumpTo('), source.indexOf('  private versionKindLabel('));
  const js = ts.transpileModule('class Reader {' + method + '} return Reader;',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const scrolls = [];
  const Page = new Function('animateTo', 'MOTION_ENTER', 'Curve', 'Edge', js)(
    (_options, update) => update(), 180, { EaseOut: 'ease-out' }, { Top: 'top' });
  const page = new Page();
  Object.assign(page, {
    pageAlive: true, loaded: true, busy: false,
    project: { chapters: [{ id: 'first' }, { id: 'second' }] }, chapterId: 'first',
    notice: '旧稿提示', evidenceQuote: '第一章的证据', evidenceFocused: true,
    readerScroller: { scrollEdge: edge => scrolls.push({ edge, chapterId: page.chapterId }) },
  });
  return { page, scrolls };
}

test('changing chapter returns the retained reader scroll to the new chapter start', () => {
  const { page, scrolls } = fixture();
  page.jumpTo(1);
  assert.equal(page.chapterId, 'second');
  assert.equal(page.notice, '');
  assert.equal(page.evidenceQuote, '');
  assert.equal(page.evidenceFocused, false);
  assert.deepEqual(scrolls, [{ edge: 'top', chapterId: 'second' }]);
  page.jumpTo(0);
  assert.deepEqual(scrolls, [{ edge: 'top', chapterId: 'second' }, { edge: 'top', chapterId: 'first' }]);
});

test('same chapter, invalid target, busy, unloaded, and departed readers preserve reading position and evidence', () => {
  for (const [prepare, idx] of [
    [() => {}, 0], [() => {}, -1], [() => {}, 2],
    [p => { p.busy = true; }, 1], [p => { p.loaded = false; }, 1],
    [p => { p.pageAlive = false; }, 1], [p => { p.project = null; }, 1],
  ]) {
    const { page, scrolls } = fixture();
    prepare(page);
    page.jumpTo(idx);
    assert.equal(page.chapterId, 'first');
    assert.equal(page.notice, '旧稿提示');
    assert.equal(page.evidenceQuote, '第一章的证据');
    assert.equal(page.evidenceFocused, true);
    assert.deepEqual(scrolls, []);
  }
});
