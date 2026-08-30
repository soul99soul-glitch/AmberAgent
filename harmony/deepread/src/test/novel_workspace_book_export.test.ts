import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  buildNovelBookExport, encodeNovelBookEpub,
} from '../main/ets/novel/workspace_book_export.ts';
import type { NovelBookZipEncoder } from '../main/ets/novel/workspace_book_export.ts';

test('book export renders current-branch chapters in stable ordinal order', () => {
  const exported = buildNovelBookExport({
    bookTitle: ' 星海 & 远方 ',
    chapters: [
      { ordinal: 12, title: '尾声', content: '再见\r\n世界' },
      { ordinal: 1, title: '开端', content: '你好' },
    ],
  });

  assert.equal(exported.txt,
    '星海 & 远方\n\n001 开端\n\n你好\n\n012 尾声\n\n再见\n世界\n');
  assert.equal(exported.markdown,
    '# 星海 & 远方\n\n## 001 开端\n\n你好\n\n## 012 尾声\n\n再见\n世界\n');
});

test('book export creates an EPUB 3 archive plan with ordered, correctly escaped entries', () => {
  const exported = buildNovelBookExport({
    bookTitle: 'A & <B>',
    chapters: [{ ordinal: 7, title: '"引子" & <开始>', content: '甲 & 乙\n<正文>' }],
  });

  assert.deepEqual(exported.epub.metadata, {
    mimetype: 'application/epub+zip',
    rootfilePath: 'OEBPS/package.opf',
    title: 'A & <B>',
    language: 'zh-CN',
    identifier: 'urn:amber:novel:export',
  });
  assert.deepEqual(exported.epub.entries.map(entry => entry.name), [
    'mimetype',
    'META-INF/container.xml',
    'OEBPS/package.opf',
    'OEBPS/nav.xhtml',
    'OEBPS/text/chapter-007.xhtml',
  ]);
  assert.equal(exported.epub.entries[0].compression, 'stored');
  assert.match(exported.epub.entries[1].text, /full-path="OEBPS\/package\.opf"/);
  assert.match(exported.epub.entries[2].text,
    /<dc:title>A &amp; &lt;B&gt;<\/dc:title>/);
  assert.match(exported.epub.entries[2].text,
    /href="text\/chapter-007\.xhtml"/);
  assert.match(exported.epub.entries[3].text,
    /&quot;引子&quot; &amp; &lt;开始&gt;/);
  assert.match(exported.epub.entries[4].text,
    /<title>&quot;引子&quot; &amp; &lt;开始&gt;<\/title>/);
  assert.match(exported.epub.entries[4].text, /<p>甲 &amp; 乙<\/p>\n    <p>&lt;正文&gt;<\/p>/);
});

test('book export rejects invalid chapter ordinals and duplicates', () => {
  assert.throws(() => buildNovelBookExport({
    bookTitle: '书', chapters: [{ ordinal: 0, title: '坏', content: '' }],
  }));
  assert.throws(() => buildNovelBookExport({
    bookTitle: '书',
    chapters: [
      { ordinal: 1, title: '一', content: '' },
      { ordinal: 1, title: '又一', content: '' },
    ],
  }));
});

test('EPUB encoding delegates its exact archive plan to the injected ZIP encoder', async () => {
  const epub = buildNovelBookExport({
    bookTitle: '书', chapters: [{ ordinal: 1, title: '章', content: '文' }],
  }).epub;
  let receivedNames: string[] = [];
  const encoder: NovelBookZipEncoder = {
    async encode(entries) {
      receivedNames = entries.map(entry => entry.name);
      return new Uint8Array([7, 8]);
    },
  };

  const bytes = await encodeNovelBookEpub(epub, encoder);
  assert.deepEqual(Array.from(bytes), [7, 8]);
  assert.deepEqual(receivedNames, epub.entries.map(entry => entry.name));
});
