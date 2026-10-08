import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { parseInline, parseMarkdown } from '../main/ets/chat/markdown_blocks.ts';
import { MarkdownCache } from '../main/ets/chat/markdown_cache.ts';

describe('math Markdown recognition', () => {
  it('supports inline and display standard delimiters without consuming escaped dollars or code', () => {
    const tokens = parseInline(String.raw`inline $x^2$ and \(\frac{a}{b}\), $$\int_0^1 x dx$$ and \[A\], \$literal\$, code \`$no$\``.replace(/\\`/g, '`'));
    assert.deepEqual(tokens.filter(token => token.type === 'math').map(token => [token.latex, token.display]), [
      ['x^2', false], [String.raw`\frac{a}{b}`, false], [String.raw`\int_0^1 x dx`, true], ['A', true],
    ]);
    assert.equal(parseInline('\\$x\\$').some(token => token.type === 'math'), false);
    assert.deepEqual(parseInline('`$unfinished` then $valid$').filter(token => token.type === 'math')
      .map(token => token.latex), ['valid']);
  });

  it('keeps multiline display formulas including internal blanks and Markdown-like lines in one block', () => {
    const formula = String.raw`\begin{aligned}
a &= \frac{1}{2} \\

-b &= \sqrt{x}
\end{aligned}`;
    for (const [open, close] of [['$$', '$$'], ['\\[', '\\]']]) {
      const blocks = parseMarkdown(`before\n\n${open}\n${formula}\n${close}\n\nafter`);
      assert.equal(blocks.length, 3);
      assert.equal(blocks[1].kind, 'paragraph');
      if (blocks[1].kind === 'paragraph') {
        assert.equal(blocks[1].inlines.length, 1);
        assert.equal(blocks[1].inlines[0].type, 'math');
        if (blocks[1].inlines[0].type === 'math') assert.equal(blocks[1].inlines[0].latex.trim(), formula);
      }
    }
    const fenced = parseMarkdown('```latex\n$$x$$\n\\[y\\]\n```');
    assert.equal(fenced[0].kind, 'code_block');
    assert.equal(parseMarkdown('```latex\n$x$')[0].kind, 'code_block');
  });

  it('replaces an incomplete streaming formula when its closing delimiter arrives', () => {
    const cache = new MarkdownCache();
    const prefix = 'before\n\n$$\nx=1\n\n';
    cache.getOrParse('math-stream', 0, prefix);
    const completed = `${prefix}y=2\n$$\n\nafter`;
    const result = cache.getOrParse('math-stream', prefix.length, completed);
    assert.deepEqual(result.blocks, parseMarkdown(completed));
  });
});
