const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { actualPage, loadPureModule } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');
const { parseMarkdown } = loadPureModule(path.resolve(__dirname, '../../../chat/src/main/ets/chat/markdown_blocks.ts'));

function fixture() {
  return actualPage('components/MarkdownBlockView.ets', ['paragraphImageRows'], {});
}
function paragraph(source) {
  const blocks = parseMarkdown(source);
  assert.equal(blocks.length, 1);
  assert.equal(blocks[0].kind, 'paragraph');
  return blocks[0];
}
const urls = rows => rows.map(row => row.map(image => image.url));

test('pure image paragraphs retain original image order and form equal-width pairs without mutating parser tokens', () => {
  const page = fixture();
  const source = paragraph('![横图](https://example.test/wide.jpg)\n![竖图](https://example.test/tall.jpg)\n![第三张](https://example.test/third.jpg)');
  const before = JSON.stringify(source);
  assert.deepEqual(urls(page.paragraphImageRows(source)), [
    ['https://example.test/wide.jpg', 'https://example.test/tall.jpg'],
    ['https://example.test/third.jpg'],
  ]);
  assert.equal(JSON.stringify(source), before);
  assert.deepEqual(urls(page.paragraphImageRows(paragraph('![一张](https://example.test/one.jpg)'))),
    [['https://example.test/one.jpg']]);
});

test('mixed text, links, emphasis, math and empty image destinations retain inline paragraph semantics', () => {
  const page = fixture();
  for (const source of [
    '图片说明 ![图](https://example.test/a.jpg)',
    '![图](https://example.test/a.jpg) [来源](https://example.test/source)',
    '![图](https://example.test/a.jpg) **说明**',
    '![图](https://example.test/a.jpg) $x+1$',
    '![空]()',
    '只有正文',
  ]) assert.deepEqual(page.paragraphImageRows(paragraph(source)), [], source);
  assert.deepEqual(page.paragraphImageRows({ kind: 'paragraph', inlines: [
    { type: 'image', url: ' ', alt: '缺失' },
  ] }), []);
});

test('blank paragraph boundaries and intervening prose never merge independent figures', () => {
  const page = fixture();
  const blocks = parseMarkdown('![第一张](https://example.test/first.jpg)\n\n正文说明。\n\n![第二张](https://example.test/second.jpg)');
  assert.equal(blocks.length, 3);
  assert.deepEqual(Array.from(blocks, block => urls(page.paragraphImageRows(block))), [
    [['https://example.test/first.jpg']], [], [['https://example.test/second.jpg']],
  ]);
  const separate = parseMarkdown('![第一张](https://example.test/first.jpg)\n\n![第二张](https://example.test/second.jpg)');
  assert.deepEqual(Array.from(separate, block => urls(page.paragraphImageRows(block))), [
    [['https://example.test/first.jpg']], [['https://example.test/second.jpg']],
  ]);
});
