// html_dom.test.ts — D-067 mini-DOM(Jsoup 子集等价)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { HtmlElement } from '../main/ets/search/html_dom.ts';
import {
  parseHtml, select, selectFirst, elementText, attr, hasClass,
  parentsOf, parentOf, nextElementSiblingOf,
} from '../main/ets/search/html_dom.ts';

const DOC: string = `<!DOCTYPE html><html><body>
<main>
  <ol id="b_results">
    <li class="b_algo" data-x="1">
      <h2><a href="https://a.com/1"> Title &amp; One </a></h2>
      <div class="b_caption"><p>snippet  one</p></div>
    </li>
    <li class="b_algo sponsored">
      <h2><a href="//redir?u=https%3A%2F%2Fb.com%2F2">Two</a></h2>
      <p class="b_snippet">second</p>
    </li>
  </ol>
  <h2><a href="/relative">Fallback A</a></h2>
</main>
<script>if (a < b) { document.write("<li class='b_algo'>fake</li>"); }</script>
<style>.x > .y { color: red; }</style>
</body></html>`;

test('select:tag.class / 后代 / 逗号组 / id', () => {
  const doc = parseHtml(DOC);
  assert.equal(select(doc, 'li.b_algo').length, 2);
  assert.equal(select(doc, 'h2 a').length, 3);
  // 逗号组并集按文档序
  const caps = select(doc, '.b_caption p, .b_snippet, h2 a');
  assert.equal(caps.length, 5);
  assert.equal(elementText(caps[0]).trim(), 'Title & One');
  // id + 后代
  assert.equal(select(doc, '#b_results h2 a').length, 2);
});

test('selectFirst:首命中或 null', () => {
  const doc = parseHtml(DOC);
  const first = selectFirst(doc, 'li.b_algo');
  assert.ok(first !== null);
  assert.equal(attr(first, 'data-x'), '1');
  assert.equal(selectFirst(doc, 'table.zzz'), null);
});

test('elementText:递归聚合 + 空白折叠(Jsoup 近似) + 实体解码', () => {
  const doc = parseHtml(DOC);
  const li = select(doc, 'li.b_algo')[0];
  const t: string = elementText(li);
  assert.ok(t.includes('Title & One'));
  assert.ok(t.includes('snippet one')); // 双空格折叠
  assert.ok(!t.includes('  '));
});

test('attr:缺省空串;script/style 内容不成元素', () => {
  const doc = parseHtml(DOC);
  const a = selectFirst(doc, 'li.b_algo h2 a');
  assert.ok(a !== null);
  assert.equal(attr(a, 'href'), 'https://a.com/1');
  assert.equal(attr(a, 'missing'), '');
  // script 内的假 li 不应出现
  assert.equal(select(doc, 'li.b_algo').length, 2);
});

test('hasClass / parentsOf / parentOf / nextElementSiblingOf', () => {
  const doc = parseHtml(DOC);
  const lis = select(doc, 'li.b_algo');
  assert.equal(hasClass(lis[1], 'sponsored'), true);
  assert.equal(hasClass(lis[0], 'sponsored'), false);
  const a = selectFirst(doc, 'main > h2 a');
  assert.ok(a !== null); // '>' 子代组合:main 直系 h2(/relative 那条)
  const ps: HtmlElement[] = parentsOf(a);
  assert.ok(ps.length >= 3); // h2, li/main..., html...
  const h2 = parentOf(a);
  assert.ok(h2 !== null && h2.tagName === 'h2');
  const sib = nextElementSiblingOf(lis[0]);
  assert.ok(sib !== null && hasClass(sib, 'sponsored'));
  assert.equal(nextElementSiblingOf(lis[1]), null);
});

test('裸标签选择器 + void 元素不误吞子节点', () => {
  const doc = parseHtml('<div><p>a</p><br><p>b</p><img src="x.png"></div>');
  assert.equal(select(doc, 'p').length, 2);
  assert.equal(elementText(doc), 'a b');
  const img = selectFirst(doc, 'img');
  assert.ok(img !== null);
  assert.equal(attr(img, 'src'), 'x.png');
});

test('自闭合与未闭合标签容错;属性无引号/单引号', () => {
  const doc = parseHtml("<ul><li class=a>1<li class='b'>2</ul><p x=1>tail");
  assert.equal(select(doc, 'li').length, 2);
  assert.equal(attr(select(doc, 'li')[1], 'class'), 'b');
  assert.equal(elementText(selectFirst(doc, 'p') as HtmlElement), 'tail');
});

test('数字实体 + &nbsp; 归一', () => {
  const doc = parseHtml('<p>&#65;&nbsp;B&#x43;</p>');
  const t: string = elementText(doc);
  assert.ok(t.includes('A'));
  assert.ok(t.includes('BC'));
});

// Phase 6 回归:混排文本/子元素按文档序输出(标题含 <strong> 高亮不再乱序)
test('elementText: mixed text and inline elements keep document order', () => {
  const doc = parseHtml('<html><body><a href="https://x.com">Foo <strong>Bar</strong> Baz</a></body></html>');
  const a = selectFirst(doc, 'a');
  assert.ok(a !== null);
  assert.equal(elementText(a).trim(), 'Foo Bar Baz');
});

test('elementText: deeper interleaving stays ordered', () => {
  const doc = parseHtml('<p>1<b>2</b>3<i>4</i>5</p>');
  const p = selectFirst(doc, 'p');
  assert.ok(p !== null);
  assert.equal(elementText(p).replace(/\s+/g, ''), '12345');
});

// ===== Phase8 解析加固回归 =====

test('属性值内的 > 不截断标签(引号感知)', () => {
  const doc = parseHtml('<a href="https://x.test/?a=>b">T</a>');
  const a = doc.root.children.find((c) => c.tagName === 'a');
  assert.ok(a !== undefined);
  assert.equal(a.attributes['href'], 'https://x.test/?a=>b');
  assert.equal(a !== undefined, true);
});

test('文本中的 < 后非标签形态按普通文本处理("2 < 3")', () => {
  const doc = parseHtml('<p>2 < 3 and > 1</p>');
  const p = doc.root.children.find((c) => c.tagName === 'p');
  assert.ok(p !== undefined);
  const text = p.childNodes.map(
    (n) => n.kind === 'text' ? n.text : '').join('');
  assert.ok(text.includes('2 < 3'), `text=${text}`);
});

test('无引号 URL 尾部 / 不被误判 self-close', () => {
  const doc = parseHtml('<img src=http://x.test/>');
  const img = doc.root.children.find((c) => c.tagName === 'img');
  assert.ok(img !== undefined);
  assert.equal(img.attributes['src'], 'http://x.test/');
});

test('数字实体:非 BMP 用 codePoint(#x1F600 emoji);大写 #X 同收', () => {
  const doc = parseHtml('<p>&#x1F600;&#X41;</p>');
  const p = doc.root.children.find((c) => c.tagName === 'p');
  assert.ok(p !== undefined);
  const text = p.childNodes.map(
    (n) => n.kind === 'text' ? n.text : '').join('');
  assert.equal(text, '\u{1F600}A');
});

test('<br/> 无空白自闭合仍按 void 标签处理(相邻 p 不误挂)', () => {
  const doc = parseHtml('<div>a<br/><p>next</p></div>');
  const div = doc.root.children.find((c) => c.tagName === 'div');
  assert.ok(div !== undefined);
  const tags: string[] = div.children.map((c) => c.tagName);
  assert.deepEqual(tags, ['br', 'p']);
});
