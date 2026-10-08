const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const filename = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/HotListRss.ets');
const exportsObject = {};
vm.runInNewContext(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText, { exports: exportsObject, RegExp, Number, String, parseInt }, { filename });
const parse = exportsObject.parseHotListRss;

test('官方 RSS 标题与链接解码，CDATA 文本不再次实体解码', () => {
  const items = parse('<rss><channel><title>Channel title</title>' +
    '<item><title>AI &amp; language &#x1f916;</title><link>https://arxiv.org/a?a=1&amp;b=2</link></item>' +
    '<item><title><![CDATA[Research &amp; literal]]></title><link><![CDATA[https://infoq.com/ai/]]></link></item>' +
    '</channel></rss>');
  assert.equal(items.length, 2);
  assert.equal(items[0].title, 'AI & language 🤖');
  assert.equal(items[0].url, 'https://arxiv.org/a?a=1&b=2');
  assert.equal(items[1].title, 'Research &amp; literal');
});

test('重复、空标题、无效链接不进榜单，条目上限真实生效', () => {
  const xml = '<item><title>One</title><link>https://example.com/one</link></item>' +
    '<item><title>Duplicate</title><link>https://example.com/one</link></item>' +
    '<item><title>Unsafe</title><link>javascript:bad()</link></item>' +
    '<item><title> </title><link>https://example.com/empty</link></item>' +
    '<item><title>Two</title><link>https://example.com/two</link></item>';
  assert.equal(parse(xml).length, 2);
  assert.equal(parse(xml, 1).length, 1);
  assert.equal(parse('<html>upstream blocked</html>').length, 0);
});
