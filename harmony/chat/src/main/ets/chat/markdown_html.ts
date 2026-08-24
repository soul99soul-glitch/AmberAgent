// markdown_html — 轻量 markdown→html 转换器(阶段 B, audit 1.1)
//
// 方案: ArkUI RichText + HTML。Android 用 intellij-markdown AST + HtmlGenerator(3476 行),
//   Harmony 无该库,自建轻量 converter 覆盖 GFM 核心子集。
//
// 覆盖: 标题(h1-h6)/段落/粗体(**)/斜体(*)/行内代码(`)/链接[txt](url)/
//   无序列表(-/*)/有序列表(1.)/代码块(```)/引用(>)/图片![](url)/表格| | |/水平线(---)
// 降级: Mermaid/LaTeX 不识别 → 原样保留为文本(不伪成功,RichText 直出)
// 安全:
//   - 文本中 < > & 转义(防 HTML 注入)
//   - 代码块/行内代码内容转义(防注入)
//   - URL 转义 " < > & + scheme 白名单(http/https/mailto/tel,非白名单降级纯文本)
//   - 行内代码用占位符隔离,后续 bold/italic/link 不侵入 code 内容
//
// 纯域逻辑,零 SDK 依赖,可 node test。RichText 渲染组件在 entry 层(MarkdownRichText.ets)。

import { highlightCode } from './code_highlight.ts';

// ===== HTML 转义 =====

const escapeHtml = (text: string): string =>
  text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');

// URL 属性转义:转义 " < > & 防 HTML 属性逃逸
const escapeUrl = (url: string): string =>
  url
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');

// scheme 白名单:仅允许 http/https/mailto/tel,非白名单 → null(降级纯文本)
const SAFE_SCHEMES: ReadonlySet<string> = new Set<string>(['http', 'https', 'mailto', 'tel']);

const sanitizeUrl = (url: string): string | null => {
  const trimmed: string = url.trim();
  if (trimmed.length === 0) return null;
  // 无 scheme 的相对路径(以 / ./ ../ 开头或纯文件名)允许
  if (!/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(trimmed)) return escapeUrl(trimmed);
  const colonIdx: number = trimmed.indexOf(':');
  const scheme: string = trimmed.substring(0, colonIdx).toLowerCase();
  if (!SAFE_SCHEMES.has(scheme)) return null;
  return escapeUrl(trimmed);
};

// ===== 行内格式 =====
// 处理顺序: 先代码(占位符隔离),再链接/图片,最后 bold/italic
// 行内代码内容用占位符替换,避免后续处理器侵入;
// 段落/引用的多行连接符 <br> 同理用占位符 — 直接拼 <br> 会被前置
// escapeHtml 转义成字面 "&lt;br&gt;" 文本

const INLINE_CODE_PLACEHOLDER = '\x01INLINECODE\x01';
const BR_PLACEHOLDER = '\x01BRTOKEN\x01';
const codePlaceholders: string[] = [];

// 行内代码 → 占位符(内容已被前置 escapeHtml 转义,此处不再转义)
const processInlineCode = (text: string): string => {
  return text.replace(/`([^`]+)`/g, (_match: string, code: string): string => {
    const idx: number = codePlaceholders.length;
    codePlaceholders.push(`<code>${code}</code>`);
    return `${INLINE_CODE_PLACEHOLDER}${idx}${INLINE_CODE_PLACEHOLDER}`;
  });
};

// 回填行内代码占位符
const restoreInlineCode = (text: string): string => {
  return text.replace(
    new RegExp(`${INLINE_CODE_PLACEHOLDER}(\\d+)${INLINE_CODE_PLACEHOLDER}`, 'g'),
    (_m: string, idx: string): string => {
      const i: number = parseInt(idx, 10);
      return i < codePlaceholders.length ? codePlaceholders[i] : '';
    },
  );
};

// 图片 → <img src alt>(在链接之前处理,避免 ![] 被 [] 误匹配)
// alt 已被前置 escapeHtml 转义,此处不再转义;url 走 sanitizeUrl
const processImages = (text: string): string => {
  return text.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, (_m: string, alt: string, url: string): string => {
    const safeUrl: string | null = sanitizeUrl(url);
    if (safeUrl === null) return `![${alt}](${url})`;
    return `<img src="${safeUrl}" alt="${alt}" />`;
  });
};

// 链接 → <a href>label</a>
// label 已被前置 escapeHtml 转义,此处不再转义;url 走 sanitizeUrl
const processLinks = (text: string): string => {
  return text.replace(/\[([^\]]+)\]\(([^)]+)\)/g, (_m: string, label: string, url: string): string => {
    const safeUrl: string | null = sanitizeUrl(url);
    if (safeUrl === null) return `[${label}](${url})`;
    return `<a href="${safeUrl}">${label}</a>`;
  });
};

// 粗体 **text** → <strong>
const processBold = (text: string): string => {
  return text.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
};

// 斜体 *text* → <em>(在 bold 之后,避免 ** 被误匹配)
const processItalic = (text: string): string => {
  return text.replace(/(^|[^*])\*([^*]+)\*(?!\*)/g, '$1<em>$2</em>');
};

// processInline: 前置转义 → 代码占位 → 图片 → 链接 → bold → italic → 回填代码
// 前置 escapeHtml 确保所有 < > & 在格式处理前被转义(一次性,子处理器不再转义内容)。
// code/label/alt 内容已被前置转义,子处理器只包裹 HTML 标签不再 escape。
// URL 单独走 sanitizeUrl(转义 " + scheme 白名单)。
const processInline = (text: string): string => {
  let result: string = escapeHtml(text);
  result = processInlineCode(result);
  result = processImages(result);
  result = processLinks(result);
  result = processBold(result);
  result = processItalic(result);
  result = restoreInlineCode(result);
  // 多行连接符回填(占位符不会被 escapeHtml 破坏)
  result = result.split(BR_PLACEHOLDER).join('<br>');
  return result;
};

// ===== 块级解析 =====

interface CodeBlock {
  start: number;
  end: number;
  content: string;
  lang: string;
}

// 找出所有 ``` 代码块,返回位置和内容
const findCodeBlocks = (text: string): CodeBlock[] => {
  const blocks: CodeBlock[] = [];
  const regex: RegExp = /```([^\n]*)\n([\s\S]*?)```/g;
  let match: RegExpExecArray | null;
  while ((match = regex.exec(text)) !== null) {
    blocks.push({
      start: match.index,
      end: match.index + match[0].length,
      content: match[2].replace(/\n$/, ''),
      lang: match[1].trim(),
    });
  }
  return blocks;
};

// ===== 主转换函数 =====

export const markdownToHtml = (markdown: string): string => {
  const input: string = markdown.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  if (input.trim().length === 0) return '';

  // 重置行内代码占位符(每次调用独立)
  codePlaceholders.length = 0;

  const codeBlocks: CodeBlock[] = findCodeBlocks(input);

  // 用占位符替换代码块,处理完其余 markdown 后再回填
  let work: string = input;
  const placeholders: string[] = [];
  for (let i: number = codeBlocks.length - 1; i >= 0; i--) {
    const block: CodeBlock = codeBlocks[i];
    const placeholder: string = `\x00CODEBLOCK_${i}\x00`;
    // M2.3:有 lang 标记 → 语法高亮(highlightCode 内部含 escapeHtml);
    //   无 lang → 纯 escapeHtml(不伪高亮)
    const codeHtml: string = block.lang.length > 0
      ? highlightCode(block.content, block.lang)
      : escapeHtml(block.content);
    placeholders[i] = `<pre><code>${codeHtml}</code></pre>`;
    work = work.substring(0, block.start) + placeholder + work.substring(block.end);
  }

  // 按行分割,逐行识别块级结构
  const lines: string[] = work.split('\n');
  const htmlParts: string[] = [];
  let i: number = 0;

  while (i < lines.length) {
    const line: string = lines[i];

    // 空行跳过
    if (line.trim().length === 0) {
      i++;
      continue;
    }

    // 代码块占位符 → 直接输出
    const codeBlockMatch: RegExpMatchArray | null = line.match(/^\x00CODEBLOCK_(\d+)\x00$/);
    if (codeBlockMatch !== null) {
      const idx: number = parseInt(codeBlockMatch[1], 10);
      htmlParts.push(placeholders[idx]);
      i++;
      continue;
    }

    // 标题 # ~ ######
    const headingMatch: RegExpMatchArray | null = line.match(/^(#{1,6})\s+(.+)$/);
    if (headingMatch !== null) {
      const level: number = headingMatch[1].length;
      htmlParts.push(`<h${level}>${processInline(headingMatch[2])}</h${level}>`);
      i++;
      continue;
    }

    // 水平线 --- / *** / ___
    if (/^(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
      htmlParts.push('<hr />');
      i++;
      continue;
    }

    // 引用 >
    if (line.startsWith('>')) {
      const quoteLines: string[] = [];
      while (i < lines.length && lines[i].startsWith('>')) {
        quoteLines.push(lines[i].replace(/^>\s?/, ''));
        i++;
      }
      htmlParts.push(`<blockquote>${processInline(quoteLines.join(BR_PLACEHOLDER))}</blockquote>`);
      continue;
    }

    // 无序列表 - / *
    if (/^\s*[-*]\s+/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\s*[-*]\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\s*[-*]\s+/, ''));
        i++;
      }
      const itemHtml: string = items.map((item: string): string =>
        `<li>${processInline(item)}</li>`).join('');
      htmlParts.push(`<ul>${itemHtml}</ul>`);
      continue;
    }

    // 有序列表 1. / 2.
    if (/^\s*\d+\.\s+/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\s*\d+\.\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\s*\d+\.\s+/, ''));
        i++;
      }
      const itemHtml: string = items.map((item: string): string =>
        `<li>${processInline(item)}</li>`).join('');
      htmlParts.push(`<ol>${itemHtml}</ol>`);
      continue;
    }

    // 表格 | ... | ... |(要求表头行含至少 2 个 |,且下一行是分隔行含 -)
    if (line.includes('|') && (line.match(/\|/g) ?? []).length >= 2 &&
      i + 1 < lines.length && /^\s*\|?[\s-:|-]+\|?\s*$/.test(lines[i + 1]) &&
      lines[i + 1].includes('-')) {
      const tableLines: string[] = [];
      while (i < lines.length && lines[i].includes('|') && lines[i].trim().length > 0) {
        tableLines.push(lines[i]);
        i++;
      }
      htmlParts.push(buildTable(tableLines));
      continue;
    }

    // 段落:收集连续非空非块行
    const paraLines: string[] = [];
    while (i < lines.length &&
      lines[i].trim().length > 0 &&
      !/^(#{1,6})\s/.test(lines[i]) &&
      !/^(-{3,}|\*{3,}|_{3,})\s*$/.test(lines[i]) &&
      !lines[i].startsWith('>') &&
      !/^\s*[-*]\s+/.test(lines[i]) &&
      !/^\s*\d+\.\s+/.test(lines[i]) &&
      !/^\x00CODEBLOCK_\d+\x00$/.test(lines[i]) &&
      !(lines[i].includes('|') && (lines[i].match(/\|/g) ?? []).length >= 2 &&
        i + 1 < lines.length && /^\s*\|?[\s-:|-]+\|?\s*$/.test(lines[i + 1]) &&
        lines[i + 1].includes('-'))) {
      paraLines.push(lines[i]);
      i++;
    }
    if (paraLines.length > 0) {
      htmlParts.push(`<p>${processInline(paraLines.join(BR_PLACEHOLDER))}</p>`);
    }
  }

  return htmlParts.join('\n');
};

// ===== 表格构建 =====

const buildTable = (lines: string[]): string => {
  if (lines.length < 2) return `<p>${processInline(lines.join('\n'))}</p>`;

  const parseRow = (line: string): string[] => {
    const trimmed: string = line.trim().replace(/^\||\|$/g, '');
    return trimmed.split('|').map((cell: string): string => cell.trim());
  };

  const headers: string[] = parseRow(lines[0]);
  const bodyRows: string[][] = [];
  for (let r: number = 2; r < lines.length; r++) {
    bodyRows.push(parseRow(lines[r]));
  }

  const headerHtml: string = headers.map((h: string): string =>
    `<th>${processInline(h)}</th>`).join('');
  const bodyHtml: string = bodyRows.map((row: string[]): string => {
    const cells: string = row.map((cell: string): string =>
      `<td>${processInline(cell)}</td>`).join('');
    return `<tr>${cells}</tr>`;
  }).join('');

  return `<table><thead><tr>${headerHtml}</tr></thead><tbody>${bodyHtml}</tbody></table>`;
};
