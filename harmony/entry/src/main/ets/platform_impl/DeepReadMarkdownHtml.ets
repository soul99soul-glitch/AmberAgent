// DeepRead Web reading uses the same Markdown AST as native chat reading.
import {
  parseMarkdown, parseInline, MarkdownBlock, MarkdownBlockHeading,
  MarkdownBlockParagraph, MarkdownBlockCode, MarkdownBlockList,
  MarkdownBlockBlockquote, MarkdownBlockTable, InlineToken,
  InlineTextToken, InlineBoldToken, InlineItalicToken, InlineCodeToken,
  InlineLinkToken, InlineImageToken, InlineMathToken,
} from '@amber/chat-domain';

export const escapeDeepReadHtml = (raw: string): string => raw
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;').replace(/'/g, '&#39;');

export const safeDeepReadLink = (raw: string): string =>
  /^https?:\/\/[^\s<>"']+$/i.test(raw.trim()) ? raw.trim() : '';

export const safeDeepReadImage = (raw: string): string => {
  const url: string = raw.trim();
  return safeDeepReadLink(url).length > 0 || /^data:image\/(png|jpeg|jpg|gif|webp);base64,[a-z0-9+/=\s]+$/i.test(url)
    ? url : '';
};

const renderInlines = (tokens: InlineToken[]): string => {
  let html: string = '';
  for (const token of tokens) {
    switch (token.type) {
      case 'text':
        html += escapeDeepReadHtml((token as InlineTextToken).text).replace(/\n/g, '<br/>');
        break;
      case 'bold':
        html += `<strong>${renderInlines(parseInline((token as InlineBoldToken).text))}</strong>`;
        break;
      case 'italic':
        html += `<em>${renderInlines(parseInline((token as InlineItalicToken).text))}</em>`;
        break;
      case 'code':
        html += `<code>${escapeDeepReadHtml((token as InlineCodeToken).text)}</code>`;
        break;
      case 'link': {
        const link: InlineLinkToken = token as InlineLinkToken;
        const url: string = safeDeepReadLink(link.url);
        const label: string = escapeDeepReadHtml(link.text);
        html += url.length > 0 ? `<a href="${escapeDeepReadHtml(url)}">${label}</a>` : label;
        break;
      }
      case 'image': {
        const img: InlineImageToken = token as InlineImageToken;
        const url: string = safeDeepReadImage(img.url);
        if (url.length > 0) html += `<img class="inline" src="${escapeDeepReadHtml(url)}" alt="${escapeDeepReadHtml(img.alt)}"/>`;
        else html += escapeDeepReadHtml(img.alt);
        break;
      }
      case 'math':
        html += `<span class="math">${escapeDeepReadHtml((token as InlineMathToken).latex)}</span>`;
        break;
    }
  }
  return html;
};

const renderList = (list: MarkdownBlockList): string => {
  const tag: string = list.ordered ? 'ol' : 'ul';
  let html: string = list.ordered ? `<ol start="${list.start}">` : '<ul>';
  let depth: number = 0;
  for (let i: number = 0; i < list.items.length; i++) {
    // Parser depths preserve indentation; avoid introducing empty intermediate items.
    const requested: number = list.depths !== undefined ? list.depths[i] : 0;
    const next: number = i === 0 ? 0 : Math.min(requested, depth + 1);
    if (i > 0) {
      if (next > depth) html += `<${tag}>`;
      else {
        html += '</li>';
        while (depth > next) { html += `</${tag}></li>`; depth--; }
      }
    }
    depth = next;
    html += `<li>${renderInlines(list.items[i])}`;
  }
  if (list.items.length > 0) html += '</li>';
  while (depth > 0) { html += `</${tag}></li>`; depth--; }
  return html + `</${tag}>`;
};

export const deepReadMarkdownHtml = (raw: string): string => {
  let html: string = '';
  const blocks: MarkdownBlock[] = parseMarkdown(raw);
  for (const block of blocks) {
    switch (block.kind) {
      case 'heading': {
        const heading: MarkdownBlockHeading = block as MarkdownBlockHeading;
        html += `<h${heading.level}>${renderInlines(heading.inlines)}</h${heading.level}>`;
        break;
      }
      case 'paragraph':
        html += `<p>${renderInlines((block as MarkdownBlockParagraph).inlines)}</p>`;
        break;
      case 'code_block': {
        const code: MarkdownBlockCode = block as MarkdownBlockCode;
        html += `<pre><code>${escapeDeepReadHtml(code.code)}</code></pre>`;
        break;
      }
      case 'list': html += renderList(block as MarkdownBlockList); break;
      case 'blockquote':
        html += `<blockquote>${renderInlines((block as MarkdownBlockBlockquote).inlines)}</blockquote>`;
        break;
      case 'table': {
        const table: MarkdownBlockTable = block as MarkdownBlockTable;
        html += '<div class="table-wrap"><table><thead><tr>';
        for (let i: number = 0; i < table.headers.length; i++) {
          html += `<th style="text-align:${table.alignments[i]}">${renderInlines(table.headers[i])}</th>`;
        }
        html += '</tr></thead><tbody>';
        for (const row of table.rows) {
          html += '<tr>';
          for (let i: number = 0; i < row.length; i++) {
            html += `<td style="text-align:${table.alignments[i]}">${renderInlines(row[i])}</td>`;
          }
          html += '</tr>';
        }
        html += '</tbody></table></div>';
        break;
      }
      case 'hr': html += '<hr/>'; break;
    }
  }
  return html;
};
