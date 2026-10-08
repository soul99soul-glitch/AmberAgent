// markdown_html.test.ts — 阶段 B:markdown→html 转换器测试
// 方案: ArkUI RichText + HTML(自建轻量 markdown→html converter)
// 覆盖 GFM 核心子集:标题/段落/粗斜体/链接/列表/代码块/行内代码/引用/图片/表格/水平线
// 降级: Mermaid/LaTeX → 纯文本占位(不伪成功)
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { markdownToHtml } from '../main/ets/chat/markdown_html.ts';

describe('markdownToHtml headings', () => {
  it('converts h1-h6', () => {
    assert.equal(markdownToHtml('# Title'), '<h1>Title</h1>');
    assert.equal(markdownToHtml('## Subtitle'), '<h2>Subtitle</h2>');
    assert.equal(markdownToHtml('### H3'), '<h3>H3</h3>');
    assert.equal(markdownToHtml('#### H4'), '<h4>H4</h4>');
    assert.equal(markdownToHtml('##### H5'), '<h5>H5</h5>');
    assert.equal(markdownToHtml('###### H6'), '<h6>H6</h6>');
  });

  it('does not treat # inside text as heading', () => {
    assert.equal(markdownToHtml('not a # heading'), '<p>not a # heading</p>');
  });
});

describe('markdownToHtml paragraphs', () => {

  it('separates paragraphs by blank lines', () => {
    const html = markdownToHtml('First paragraph.\n\nSecond paragraph.');
    assert.ok(html.includes('<p>First paragraph.</p>'));
    assert.ok(html.includes('<p>Second paragraph.</p>'));
  });
});

describe('markdownToHtml inline formatting', () => {

  it('italic with *', () => {
    assert.equal(markdownToHtml('*italic*'), '<p><em>italic</em></p>');
  });

  it('links', () => {
    assert.equal(
      markdownToHtml('[text](https://example.com)'),
      '<p><a href="https://example.com">text</a></p>',
    );
  });
});

describe('markdownToHtml lists', () => {

  it('ordered list', () => {
    const html = markdownToHtml('1. first\n2. second');
    assert.ok(html.includes('<ol>'));
    assert.ok(html.includes('<li>first</li>'));
    assert.ok(html.includes('<li>second</li>'));
    assert.ok(html.includes('</ol>'));
  });
});

describe('markdownToHtml code blocks', () => {

  it('fenced code block without language', () => {
    const html = markdownToHtml('```\nplain code\n```');
    assert.ok(html.includes('<pre><code>plain code</code></pre>'));
  });

  it('does not process markdown inside code blocks', () => {
    const html = markdownToHtml('```\n**not bold**\n```');
    assert.ok(html.includes('**not bold**'));
    assert.equal(html.includes('<strong>'), false);
  });
});

describe('markdownToHtml images', () => {
  it('image syntax', () => {
    const html = markdownToHtml('![alt text](https://example.com/img.png)');
    assert.ok(html.includes('<img'));
    assert.ok(html.includes('src="https://example.com/img.png"'));
    assert.ok(html.includes('alt="alt text"'));
  });
});

describe('markdownToHtml tables', () => {
  it('simple table', () => {
    const md = '| Name | Age |\n| --- | --- |\n| Alice | 30 |\n| Bob | 25 |';
    const html = markdownToHtml(md);
    assert.ok(html.includes('<table>'));
    assert.ok(html.includes('<th>Name</th>'));
    assert.ok(html.includes('<th>Age</th>'));
    assert.ok(html.includes('<td>Alice</td>'));
    assert.ok(html.includes('<td>30</td>'));
    assert.ok(html.includes('<td>Bob</td>'));
    assert.ok(html.includes('<td>25</td>'));
    assert.ok(html.includes('</table>'));
  });
});

describe('markdownToHtml horizontal rule', () => {
  it('--- becomes hr', () => {
    const html = markdownToHtml('---');
    assert.ok(html.includes('<hr'));
  });
});

describe('markdownToHtml HTML escaping', () => {
  it('escapes < > & in text', () => {
    const html = markdownToHtml('a < b & c > d');
    assert.ok(html.includes('&lt;'));
    assert.ok(html.includes('&amp;'));
    assert.ok(html.includes('&gt;'));
  });

  it('does not escape inside code blocks', () => {
    const html = markdownToHtml('```\n<x>\n```');
    // Inside code blocks, escape < > to display literally
    assert.ok(html.includes('&lt;x&gt;'));
  });
});

describe('markdownToHtml mixed content', () => {
  it('heading + paragraph + code block + list', () => {
    const md = '# Title\n\nSome text with **bold**.\n\n```js\nconst x = 1;\n```\n\n- item 1\n- item 2';
    const html = markdownToHtml(md);
    assert.ok(html.includes('<h1>Title</h1>'));
    assert.ok(html.includes('<strong>bold</strong>'));
    assert.ok(html.includes('<pre>'));
    // M2.3:有 lang → 语法高亮(const 关键字 span + x 标识符 + 1 数字)
    assert.ok(html.includes('>const<'));
    assert.ok(html.includes('>1<'));
    assert.ok(html.includes('<ul>'));
    assert.ok(html.includes('<li>item 1</li>'));
  });
});

describe('markdownToHtml edge cases', () => {
  it('empty string returns empty', () => {
    assert.equal(markdownToHtml(''), '');
  });

  it('only whitespace returns empty', () => {
    assert.equal(markdownToHtml('   \n\n  '), '');
  });

  it('preserves URLs in text', () => {
    const html = markdownToHtml('Visit https://example.com today');
    assert.ok(html.includes('https://example.com'));
  });
});

describe('markdownToHtml bug-fix coverage (review)', () => {
  // BUG-1: 双重转义 — < > & 在文本和行内代码中只转义一次
  it('does not double-escape < > & in inline code', () => {
    const html = markdownToHtml('use `<b>` here');
    assert.ok(html.includes('<code>&lt;b&gt;</code>'));
    assert.equal(html.includes('&amp;lt;'), false);
    assert.equal(html.includes('&amp;gt;'), false);
  });

  it('does not double-escape & in text', () => {
    const html = markdownToHtml('a & b');
    assert.ok(html.includes('&amp;'));
    assert.equal(html.includes('&amp;amp;'), false);
  });

  // BUG-2: 行内代码内容隔离 — code 内的 markdown 语法不被处理
  it('does not process markdown inside inline code', () => {
    const html = markdownToHtml('use `**not bold**` here');
    assert.ok(html.includes('<code>**not bold**</code>'));
    assert.equal(html.includes('<strong>'), false);
  });

  it('does not process links inside inline code', () => {
    const html = markdownToHtml('use `[text](http://x)` here');
    assert.ok(html.includes('<code>'));
    assert.equal(html.includes('<a href'), false);
  });

  // BUG-3: URL 注入防护 — " 被转义防属性逃逸;javascript: scheme 被拒绝降级为纯文本
  it('escapes quotes in link URLs to prevent attribute injection', () => {
    const html = markdownToHtml('[click](https://example.com)');
    assert.ok(html.includes('href="https://example.com"'));
    // 注入尝试:引号逃逸 → URL 中 " 被转义为 &quot;,无法逃逸 href 属性
    const malicious = markdownToHtml('[click](a" onclick="alert(1))');
    // 关键:不能出现未转义的 onclick=" (即 onclick= 后跟裸引号能逃逸属性)
    assert.equal(malicious.includes('onclick="alert'), false);
    // 引号被转义为 &quot;,属性无法逃逸
    assert.ok(malicious.includes('&quot;'));
  });

  it('rejects javascript: scheme URLs', () => {
    const html = markdownToHtml('[click](javascript:alert(1))');
    // scheme 被拒绝 → 不生成 <a href>,降级为纯文本(原样保留可读)
    assert.equal(html.includes('<a href'), false);
  });

  it('allows relative URLs without scheme', () => {
    const html = markdownToHtml('[link](/path/to/page)');
    assert.ok(html.includes('href="/path/to/page"'));
  });

  it('allows mailto scheme', () => {
    const html = markdownToHtml('[email](mailto:test@example.com)');
    assert.ok(html.includes('href="mailto:test@example.com"'));
  });

  // LOGIC-C: 表格分隔行收紧 — 普通管道符文本不被误判为表格
  it('does not misidentify pipe-separated text as table', () => {
    const html = markdownToHtml('Options: yes | no');
    assert.equal(html.includes('<table>'), false);
    assert.ok(html.includes('<p>'));
  });
});

// Phase 8 回归:多行段落/引用的换行必须是真 <br>,不得被 escape 成字面文本
it('multiline paragraph and blockquote render real <br>', () => {
  assert.equal(markdownToHtml('para line1\npara line2'), '<p>para line1<br>para line2</p>');
  assert.equal(markdownToHtml('> quote1\n> quote2'), '<blockquote>quote1<br>quote2</blockquote>');
});
