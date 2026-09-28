// Copy text through the same Markdown AST used by MarkdownText; code remains literal.
import type { InlineToken } from './markdown_blocks.ts';
import { parseMarkdown, parseInline, orderedListLabel } from './markdown_blocks.ts';
import type { UIMessage } from './message.ts';
import { toText } from './message.ts';

const inlinePlainText = (tokens: InlineToken[]): string => {
  let text: string = '';
  for (const token of tokens) {
    if (token.type === 'image') text += token.alt;
    else if (token.type === 'math') text += token.latex;
    else if (token.type === 'bold' || token.type === 'italic' || token.type === 'link') {
      text += inlinePlainText(parseInline(token.text));
    } else text += token.text;
  }
  return text;
};

export const markdownToPlainText = (markdown: string): string => {
  const sections: string[] = [];
  for (const block of parseMarkdown(markdown)) {
    if (block.kind === 'code_block') {
      sections.push(block.code);
    } else if (block.kind === 'list') {
      const lines: string[] = [];
      const depths: number[] = block.depths ?? [];
      for (let i: number = 0; i < block.items.length; i++) {
        const marker: string = block.ordered ? `${orderedListLabel(block.start, depths, i)}.` : '•';
        lines.push(`${'  '.repeat(depths[i] ?? 0)}${marker} ${inlinePlainText(block.items[i])}`);
      }
      sections.push(lines.join('\n'));
    } else if (block.kind === 'table') {
      const lines: string[] = [block.headers.map(inlinePlainText).join('\t')];
      for (const row of block.rows) lines.push(row.map(inlinePlainText).join('\t'));
      sections.push(lines.join('\n'));
    } else if (block.kind !== 'hr') {
      sections.push(inlinePlainText(block.inlines));
    }
  }
  return sections.join('\n\n');
};

export const textPartCopyText = (text: string, formatMarkdown: boolean): string =>
  formatMarkdown ? text : markdownToPlainText(text);

export const messageCopyText = (message: UIMessage, formatMarkdown: boolean): string => {
  if (!formatMarkdown) return markdownToPlainText(toText(message));
  let markdown: string = '';
  for (const part of message.parts) {
    if (part.type === 'text') {
      markdown += `${part.text}\n\n`;
    } else if (part.type === 'reasoning') {
      markdown += `> **思考过程:**\n> ${part.reasoning.replace(/\n/g, '\n> ')}\n\n`;
    } else if (part.type === 'tool') {
      markdown += `**工具调用:** \`${part.toolName}\`\n\n`;
    }
  }
  return markdown.trim();
};
