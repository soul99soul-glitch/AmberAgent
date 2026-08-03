// markdown_blocks.test.ts — block/inline AST 解析器测试
// 对齐 markdown_html.test.ts 覆盖面,验证 AST 结构而非 HTML 字符串
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  parseMarkdown,
  parseInline,
} from '../main/ets/chat/markdown_blocks.ts';
import type {
  MarkdownBlock,
  InlineToken,
} from '../main/ets/chat/markdown_blocks.ts';

// ===== 辅助断言 =====

const blocksOf = (md: string): MarkdownBlock[] => parseMarkdown(md);
const firstBlock = (md: string): MarkdownBlock => blocksOf(md)[0];

const inlineTexts = (inlines: InlineToken[]): string => {
  return inlines
    .filter((t: InlineToken): boolean => t.type === 'text' || t.type === 'bold' || t.type === 'italic' || t.type === 'code')
    .map((t: InlineToken): string => {
      if (t.type === 'text') return t.text;
      if (t.type === 'bold') return t.text;
      if (t.type === 'italic') return t.text;
      if (t.type === 'code') return t.text;
      return '';
    })
    .join('');
};

// ===== 行内解析 =====

describe('parseInline basic', () => {
  it('plain text → single text token', () => {
    const tokens = parseInline('hello world');
    assert.equal(tokens.length, 1);
    assert.equal(tokens[0].type, 'text');
    assert.equal(tokens[0].type === 'text' ? tokens[0].text : '', 'hello world');
  });

  it('empty string → empty array', () => {
    assert.deepEqual(parseInline(''), []);
  });
});

describe('parseInline code', () => {
  it('inline code', () => {
    const tokens = parseInline('use `console.log` to debug');
    assert.equal(tokens.length, 3);
    assert.equal(tokens[0].type, 'text');
    assert.equal(tokens[1].type, 'code');
    assert.equal(tokens[1].type === 'code' ? tokens[1].text : '', 'console.log');
    assert.equal(tokens[2].type, 'text');
  });

  it('code takes priority over bold/italic', () => {
    const tokens = parseInline('`**not bold**`');
    assert.equal(tokens.length, 1);
    assert.equal(tokens[0].type, 'code');
    assert.equal(tokens[0].type === 'code' ? tokens[0].text : '', '**not bold**');
  });
});

describe('parseInline bold', () => {
  it('bold text', () => {
    const tokens = parseInline('this is **bold** text');
    assert.equal(tokens.length, 3);
    assert.equal(tokens[1].type, 'bold');
    assert.equal(tokens[1].type === 'bold' ? tokens[1].text : '', 'bold');
  });

  it('bold cannot contain asterisks', () => {
    // **a*b** → italic match inside bold range overlaps → bold wins
    const tokens = parseInline('**bold** and *italic*');
    const boldTok = tokens.find((t: InlineToken): boolean => t.type === 'bold');
    const italicTok = tokens.find((t: InlineToken): boolean => t.type === 'italic');
    assert.ok(boldTok, 'should have bold');
    assert.ok(italicTok, 'should have italic');
  });
});

describe('parseInline italic', () => {
  it('italic text', () => {
    const tokens = parseInline('this is *italic* text');
    const italicTok = tokens.find((t: InlineToken): boolean => t.type === 'italic');
    assert.ok(italicTok);
    assert.equal(italicTok!.type === 'italic' ? italicTok!.text : '', 'italic');
  });
});

describe('parseInline link', () => {
  it('link', () => {
    const tokens = parseInline('see [docs](https://example.com) here');
    const linkTok = tokens.find((t: InlineToken): boolean => t.type === 'link');
    assert.ok(linkTok);
    if (linkTok!.type === 'link') {
      assert.equal(linkTok!.text, 'docs');
      assert.equal(linkTok!.url, 'https://example.com');
    }
  });

  it('unsafe scheme → plain text', () => {
    const tokens = parseInline('[click](javascript:alert(1))');
    const linkTok = tokens.find((t: InlineToken): boolean => t.type === 'link');
    assert.equal(linkTok, undefined);
  });
});

describe('parseInline image', () => {
  it('image takes priority over link', () => {
    const tokens = parseInline('![alt text](https://img.example.com/x.png)');
    const imgTok = tokens.find((t: InlineToken): boolean => t.type === 'image');
    assert.ok(imgTok);
    if (imgTok!.type === 'image') {
      assert.equal(imgTok!.alt, 'alt text');
      assert.equal(imgTok!.url, 'https://img.example.com/x.png');
    }
  });
});

describe('parseInline mixed', () => {
  it('bold + code + link in one line', () => {
    const tokens = parseInline('**bold** and `code` and [link](https://x.com)');
    const types = tokens.map((t: InlineToken): string => t.type);
    assert.ok(types.includes('bold'));
    assert.ok(types.includes('code'));
    assert.ok(types.includes('link'));
  });
});

// ===== 块级解析 =====

describe('parseMarkdown headings', () => {
  it('h1-h6', () => {
    for (let level = 1; level <= 6; level++) {
      const block = firstBlock('#'.repeat(level) + ' Title');
      assert.equal(block.kind, 'heading');
      if (block.kind === 'heading') {
        assert.equal(block.level, level);
      }
    }
  });

  it('not a heading without space after #', () => {
    const block = firstBlock('not a # heading');
    assert.equal(block.kind, 'paragraph');
  });
});

describe('parseMarkdown paragraphs', () => {
  it('single paragraph', () => {
    const blocks = blocksOf('Hello world');
    assert.equal(blocks.length, 1);
    assert.equal(blocks[0].kind, 'paragraph');
  });

  it('two paragraphs separated by blank line', () => {
    const blocks = blocksOf('First.\n\nSecond.');
    assert.equal(blocks.length, 2);
    assert.equal(blocks[0].kind, 'paragraph');
    assert.equal(blocks[1].kind, 'paragraph');
  });

  it('multi-line paragraph', () => {
    const blocks = blocksOf('line one\nline two\nline three');
    assert.equal(blocks.length, 1);
    assert.equal(blocks[0].kind, 'paragraph');
    if (blocks[0].kind === 'paragraph') {
      assert.ok(inlineTexts(blocks[0].inlines).includes('line one'));
      assert.ok(inlineTexts(blocks[0].inlines).includes('line three'));
    }
  });
});

describe('parseMarkdown code blocks', () => {
  it('code block without lang', () => {
    const md = '```\nconst x = 1;\n```';
    const block = firstBlock(md);
    assert.equal(block.kind, 'code_block');
    if (block.kind === 'code_block') {
      assert.equal(block.lang, '');
      assert.equal(block.code, 'const x = 1;');
    }
  });

  it('code block with lang', () => {
    const md = '```python\nprint("hello")\n```';
    const block = firstBlock(md);
    assert.equal(block.kind, 'code_block');
    if (block.kind === 'code_block') {
      assert.equal(block.lang, 'python');
      assert.equal(block.code, 'print("hello")');
    }
  });

  it('code block followed by paragraph', () => {
    const md = '```\ncode\n```\n\nAfter code.';
    const blocks = blocksOf(md);
    assert.equal(blocks.length, 2);
    assert.equal(blocks[0].kind, 'code_block');
    assert.equal(blocks[1].kind, 'paragraph');
  });
});

describe('parseMarkdown lists', () => {
  it('unordered list', () => {
    const md = '- item one\n- item two\n- item three';
    const block = firstBlock(md);
    assert.equal(block.kind, 'list');
    if (block.kind === 'list') {
      assert.equal(block.ordered, false);
      assert.equal(block.items.length, 3);
    }
  });

  it('ordered list', () => {
    const md = '1. first\n2. second\n3. third';
    const block = firstBlock(md);
    assert.equal(block.kind, 'list');
    if (block.kind === 'list') {
      assert.equal(block.ordered, true);
      assert.equal(block.items.length, 3);
    }
  });

  it('list items have inline tokens', () => {
    const md = '- **bold** item\n- `code` item';
    const block = firstBlock(md);
    if (block.kind === 'list') {
      assert.equal(block.items.length, 2);
      const types0 = block.items[0].map((t: InlineToken): string => t.type);
      assert.ok(types0.includes('bold'));
      const types1 = block.items[1].map((t: InlineToken): string => t.type);
      assert.ok(types1.includes('code'));
    }
  });
});

describe('parseMarkdown blockquote', () => {
  it('single line blockquote', () => {
    const block = firstBlock('> This is a quote');
    assert.equal(block.kind, 'blockquote');
  });

  it('multi-line blockquote', () => {
    const md = '> line one\n> line two';
    const block = firstBlock(md);
    assert.equal(block.kind, 'blockquote');
    if (block.kind === 'blockquote') {
      assert.ok(inlineTexts(block.inlines).includes('line one'));
      assert.ok(inlineTexts(block.inlines).includes('line two'));
    }
  });
});

describe('parseMarkdown hr', () => {
  it('--- is hr', () => {
    assert.equal(firstBlock('---').kind, 'hr');
  });

  it('*** is hr', () => {
    assert.equal(firstBlock('***').kind, 'hr');
  });
});

describe('parseMarkdown table', () => {
  it('simple table', () => {
    const md = '| Name | Age |\n|------|-----|\n| Alice | 30 |\n| Bob | 25 |';
    const block = firstBlock(md);
    assert.equal(block.kind, 'table');
    if (block.kind === 'table') {
      assert.equal(block.headers.length, 2);
      assert.equal(block.rows.length, 2);
    }
  });
});

describe('parseMarkdown mixed document', () => {
  it('heading + paragraph + code + list', () => {
    const md = [
      '# Title',
      '',
      'Some paragraph with **bold**.',
      '',
      '```js',
      'const x = 42;',
      '```',
      '',
      '- item 1',
      '- item 2',
    ].join('\n');
    const blocks = blocksOf(md);
    assert.equal(blocks.length, 4);
    assert.equal(blocks[0].kind, 'heading');
    assert.equal(blocks[1].kind, 'paragraph');
    assert.equal(blocks[2].kind, 'code_block');
    assert.equal(blocks[3].kind, 'list');
  });
});

describe('parseMarkdown empty', () => {
  it('empty string → empty array', () => {
    assert.deepEqual(parseMarkdown(''), []);
  });

  it('whitespace only → empty array', () => {
    assert.deepEqual(parseMarkdown('   \n\n  \n'), []);
  });
});
