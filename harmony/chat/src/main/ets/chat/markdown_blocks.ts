// markdown_blocks — Markdown → block/inline AST(替代 RichText HTML 路径)
//
// 背景: ArkUI RichText 不参与宿主布局测量 → 消息操作按钮不可见(P0)。
//   本模块产出结构化 AST,供 entry 屌 MarkdownText.ets 用原生 ArkUI 组件渲染,
//   彻底解决布局问题。
//
// 对齐: 与 markdown_html.ts 同源解析逻辑(代码块占位符/逐行块级扫描/行内格式优先序),
//   但输出 block/inline 而非 HTML 字符串。
//
// 覆盖: 标题(h1-h6)/段落/粗体(**)/斜体(*)/行内代码(`)/链接[txt](url)/
//   无序列表(-/*)/有序列表(1.)/代码块(```)/引用(>)/图片![](url)/表格| | |/水平线(---)
// 降级: Mermaid/LaTeX 不识别 → 原样保留为文本
// 安全: URL scheme 白名单(http/https/mailto/tel,非白名单降级纯文本)

// ===== 行内 token 类型 =====

export interface InlineTextToken {
  type: 'text';
  text: string;
}

export interface InlineBoldToken {
  type: 'bold';
  text: string;
}

export interface InlineItalicToken {
  type: 'italic';
  text: string;
}

export interface InlineCodeToken {
  type: 'code';
  text: string;
}

export interface InlineLinkToken {
  type: 'link';
  text: string;
  url: string;
}

export interface InlineImageToken {
  type: 'image';
  alt: string;
  url: string;
}

export type InlineToken =
  | InlineTextToken
  | InlineBoldToken
  | InlineItalicToken
  | InlineCodeToken
  | InlineLinkToken
  | InlineImageToken;

// ===== 块类型 =====

export interface MarkdownBlockHeading {
  kind: 'heading';
  level: number;
  inlines: InlineToken[];
}

export interface MarkdownBlockParagraph {
  kind: 'paragraph';
  inlines: InlineToken[];
}

export interface MarkdownBlockCode {
  kind: 'code_block';
  lang: string;
  code: string;
}

export interface MarkdownBlockList {
  kind: 'list';
  ordered: boolean;
  /** The source number for an ordered list; unordered lists use 1. */
  start: number;
  items: InlineToken[][];
  /**
   * Per-item nesting depth (relative to the first item's indent, 2 spaces per
   * level, clamped to 0..3). Omitted when every item is at depth 0, so
   * flat-list consumers stay unchanged.
   */
  depths?: number[];
}

export type MarkdownTableAlignment = 'left' | 'center' | 'right';

export interface MarkdownBlockBlockquote {
  kind: 'blockquote';
  inlines: InlineToken[];
}

export interface MarkdownBlockTable {
  kind: 'table';
  headers: InlineToken[][];
  rows: InlineToken[][][];
  alignments: MarkdownTableAlignment[];
}

export interface MarkdownBlockHr {
  kind: 'hr';
}

export type MarkdownBlock =
  | MarkdownBlockHeading
  | MarkdownBlockParagraph
  | MarkdownBlockCode
  | MarkdownBlockList
  | MarkdownBlockBlockquote
  | MarkdownBlockTable
  | MarkdownBlockHr;

// ===== URL 安全 =====

const SAFE_SCHEMES: ReadonlySet<string> = new Set<string>(['http', 'https', 'mailto', 'tel']);

const sanitizeUrl = (url: string): string | null => {
  const trimmed: string = url.trim();
  if (trimmed.length === 0) return null;
  // 无 scheme 的相对路径允许
  if (!/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(trimmed)) return trimmed;
  const colonIdx: number = trimmed.indexOf(':');
  const scheme: string = trimmed.substring(0, colonIdx).toLowerCase();
  if (!SAFE_SCHEMES.has(scheme)) return null;
  return trimmed;
};

// ===== 行内解析 =====
// 处理优先序(与 markdown_html.ts 一致): 代码 > 图片 > 链接 > 粗体 > 斜体
// 后续格式跳过与先前格式重叠的区间

interface InlineMatch {
  start: number;
  end: number;
  token: InlineToken;
}

const overlaps = (matches: InlineMatch[], start: number, end: number): boolean => {
  for (const m of matches) {
    if (start < m.end && end > m.start) return true;
  }
  return false;
};

export const parseInline = (text: string): InlineToken[] => {
  if (text.length === 0) return [];

  const matches: InlineMatch[] = [];
  let m: RegExpExecArray | null;

  // 1. 行内代码 `code`
  const codeRe = /`([^`]+)`/g;
  while ((m = codeRe.exec(text)) !== null) {
    matches.push({
      start: m.index,
      end: m.index + m[0].length,
      token: { type: 'code', text: m[1] } as InlineCodeToken,
    });
  }

  // 2. 图片 ![alt](url)
  const imgRe = /!\[([^\]]*)\]\(([^)]+)\)/g;
  while ((m = imgRe.exec(text)) !== null) {
    const start: number = m.index;
    const end: number = m.index + m[0].length;
    if (overlaps(matches, start, end)) continue;
    const safeUrl: string | null = sanitizeUrl(m[2]);
    if (safeUrl === null) continue;
    matches.push({
      start,
      end,
      token: { type: 'image', alt: m[1], url: safeUrl } as InlineImageToken,
    });
  }

  // 3. 链接 [text](url)
  const linkRe = /\[([^\]]+)\]\(([^)]+)\)/g;
  while ((m = linkRe.exec(text)) !== null) {
    const start: number = m.index;
    const end: number = m.index + m[0].length;
    if (overlaps(matches, start, end)) continue;
    const safeUrl: string | null = sanitizeUrl(m[2]);
    if (safeUrl === null) continue;
    matches.push({
      start,
      end,
      token: { type: 'link', text: m[1], url: safeUrl } as InlineLinkToken,
    });
  }

  // 4. 粗体 **text**
  const boldRe = /\*\*([^*]+)\*\*/g;
  while ((m = boldRe.exec(text)) !== null) {
    const start: number = m.index;
    const end: number = m.index + m[0].length;
    if (overlaps(matches, start, end)) continue;
    matches.push({
      start,
      end,
      token: { type: 'bold', text: m[1] } as InlineBoldToken,
    });
  }

  // 5. 斜体 *text*
  const italicRe = /(^|[^*])\*([^*]+)\*(?!\*)/g;
  while ((m = italicRe.exec(text)) !== null) {
    // m[0] 可能包含前导非 * 字符,需调整 start
    const prefixLen: number = m[1].length;
    const start: number = m.index + prefixLen;
    const end: number = m.index + m[0].length;
    if (overlaps(matches, start, end)) continue;
    matches.push({
      start,
      end,
      token: { type: 'italic', text: m[2] } as InlineItalicToken,
    });
  }

  // 按位置排序
  matches.sort((a: InlineMatch, b: InlineMatch): number => a.start - b.start);

  // 填充纯文本并组装 token 序列
  const tokens: InlineToken[] = [];
  let pos: number = 0;
  for (const match of matches) {
    if (match.start > pos) {
      tokens.push({ type: 'text', text: text.substring(pos, match.start) } as InlineTextToken);
    }
    tokens.push(match.token);
    pos = match.end;
  }
  if (pos < text.length) {
    tokens.push({ type: 'text', text: text.substring(pos) } as InlineTextToken);
  }

  return tokens.length > 0 ? tokens : [{ type: 'text', text } as InlineTextToken];
};

// ===== 块级解析 =====

interface CodeBlockRange {
  start: number;
  end: number;
  content: string;
  lang: string;
}

const findCodeBlocks = (text: string): CodeBlockRange[] => {
  const blocks: CodeBlockRange[] = [];
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

// 表格行解析：只把未转义的 | 当分隔符，\| 还原为字面管道。
const parseTableRow = (line: string): string[] => {
  let value: string = line.trim();
  if (value.startsWith('|')) value = value.substring(1);
  if (value.endsWith('|') && !value.endsWith('\\|')) {
    value = value.substring(0, value.length - 1);
  }
  const cells: string[] = [];
  let cell: string = '';
  for (let i = 0; i < value.length; i++) {
    const ch: string = value.charAt(i);
    if (ch === '\\' && i + 1 < value.length && value.charAt(i + 1) === '|') {
      cell += '|';
      i++;
    } else if (ch === '|') {
      cells.push(cell.trim());
      cell = '';
    } else {
      cell += ch;
    }
  }
  cells.push(cell.trim());
  return cells;
};

const parseTableAlignment = (cell: string): MarkdownTableAlignment => {
  const value: string = cell.trim();
  if (value.startsWith(':') && value.endsWith(':')) return 'center';
  if (value.endsWith(':')) return 'right';
  return 'left';
};

// 判断是否为表格分隔行 |---|---|
const isTableSeparator = (line: string): boolean => {
  return /^\s*\|?(?:\s*:?-{1,}:?\s*\|)+\s*:?-{1,}:?\s*\|?\s*$/.test(line);
};

// ===== 主解析函数 =====

const parseMarkdownInternal = (markdown: string): MarkdownBlock[] => {
  const input: string = markdown.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  if (input.trim().length === 0) return [];

  const codeBlocks: CodeBlockRange[] = findCodeBlocks(input);

  // 用不可能与用户文本碰撞的可打印占位符替换代码块。
  // NUL 字符会被 ArkUI/字符串扫描器截断，并且用户文本可能包含相同占位符。
  let work: string = input;
  const codeBlockData: { lang: string; code: string }[] = [];
  let placeholderPrefix: string = 'AMBER_CODE_BLOCK_';
  while (input.includes(placeholderPrefix)) placeholderPrefix += '_';
  const placeholderPattern: RegExp = new RegExp(`^${placeholderPrefix}(\\d+)_END$`);
  for (let i: number = codeBlocks.length - 1; i >= 0; i--) {
    const block: CodeBlockRange = codeBlocks[i];
    const placeholder: string = `${placeholderPrefix}${i}_END`;
    codeBlockData[i] = { lang: block.lang, code: block.content };
    work = work.substring(0, block.start) + placeholder + work.substring(block.end);
  }

  const lines: string[] = work.split('\n');
  const blocks: MarkdownBlock[] = [];
  let i: number = 0;

  while (i < lines.length) {
    const line: string = lines[i];

    // 空行跳过
    if (line.trim().length === 0) {
      i++;
      continue;
    }

    // 代码块占位符
    const codeBlockMatch: RegExpMatchArray | null = line.match(placeholderPattern);
    if (codeBlockMatch !== null) {
      const idx: number = parseInt(codeBlockMatch[1], 10);
      const data: { lang: string; code: string } | undefined = codeBlockData[idx];
      if (data !== undefined) {
        blocks.push({
          kind: 'code_block',
          lang: data.lang,
          code: data.code,
        } as MarkdownBlockCode);
      } else {
        blocks.push({ kind: 'paragraph', inlines: parseInline(line) } as MarkdownBlockParagraph);
      }
      i++;
      continue;
    }

    // 标题 # ~ ######
    const headingMatch: RegExpMatchArray | null = line.match(/^(#{1,6})\s+(.+)$/);
    if (headingMatch !== null) {
      const level: number = headingMatch[1].length;
      blocks.push({
        kind: 'heading',
        level,
        inlines: parseInline(headingMatch[2]),
      } as MarkdownBlockHeading);
      i++;
      continue;
    }

    // 水平线 --- / *** / ___
    if (/^(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
      blocks.push({ kind: 'hr' } as MarkdownBlockHr);
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
      blocks.push({
        kind: 'blockquote',
        inlines: parseInline(quoteLines.join('\n')),
      } as MarkdownBlockBlockquote);
      continue;
    }

    // 无序列表 - / *(连续行合并;首行缩进为基准,2 空格一级嵌套)
    if (/^\s*[-*]\s+/.test(line)) {
      const items: InlineToken[][] = [];
      const depths: number[] = [];
      const baseIndent: number = countLeadingSpaces(line);
      while (i < lines.length && /^\s*[-*]\s+/.test(lines[i])) {
        const itemText: string = lines[i].replace(/^\s*[-*]\s+/, '');
        items.push(parseInline(itemText));
        depths.push(listDepthAt(lines[i], baseIndent));
        i++;
      }
      blocks.push({
        kind: 'list',
        ordered: false,
        start: 1,
        items,
        depths: listDepthsField(depths),
      } as MarkdownBlockList);
      continue;
    }

    // 有序列表 1. / 2.
    if (/^\s*\d+\.\s+/.test(line)) {
      const firstMatch: RegExpMatchArray | null = line.match(/^\s*(\d+)\.\s+/);
      const parsedStart: number = firstMatch !== null ? parseInt(firstMatch[1], 10) : 1;
      const start: number = Number.isFinite(parsedStart) && parsedStart >= 0 ? parsedStart : 1;
      const items: InlineToken[][] = [];
      const depths: number[] = [];
      const baseIndent: number = countLeadingSpaces(line);
      while (i < lines.length && /^\s*\d+\.\s+/.test(lines[i])) {
        const itemText: string = lines[i].replace(/^\s*\d+\.\s+/, '');
        items.push(parseInline(itemText));
        depths.push(listDepthAt(lines[i], baseIndent));
        i++;
      }
      blocks.push({
        kind: 'list',
        ordered: true,
        start,
        items,
        depths: listDepthsField(depths),
      } as MarkdownBlockList);
      continue;
    }

    // 表格 | ... | ... |
    if (line.includes('|') && (line.match(/\|/g) ?? []).length >= 2 &&
      i + 1 < lines.length && isTableSeparator(lines[i + 1])) {
      const tableLines: string[] = [];
      while (i < lines.length && lines[i].includes('|') && lines[i].trim().length > 0) {
        tableLines.push(lines[i]);
        i++;
      }
      // 解析表格
      if (tableLines.length >= 2) {
        const headers: string[] = parseTableRow(tableLines[0]);
        const separator: string[] = parseTableRow(tableLines[1]);
        const columnCount: number = headers.length;
        const headerInlines: InlineToken[][] = headers.map((h: string): InlineToken[] => parseInline(h));
        const alignments: MarkdownTableAlignment[] = [];
        for (let ci: number = 0; ci < columnCount; ci++) {
          alignments.push(parseTableAlignment(separator[ci] ?? ''));
        }
        const bodyRows: InlineToken[][][] = [];
        for (let r: number = 2; r < tableLines.length; r++) {
          const cells: string[] = parseTableRow(tableLines[r]);
          while (cells.length < columnCount) cells.push('');
          bodyRows.push(cells.slice(0, columnCount).map((c: string): InlineToken[] => parseInline(c)));
        }
        blocks.push({
          kind: 'table',
          headers: headerInlines,
          rows: bodyRows,
          alignments,
        } as MarkdownBlockTable);
      }
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
      !placeholderPattern.test(lines[i]) &&

      !(lines[i].includes('|') && (lines[i].match(/\|/g) ?? []).length >= 2 &&
        i + 1 < lines.length && isTableSeparator(lines[i + 1]))) {
      paraLines.push(lines[i]);
      i++;
    }
    if (paraLines.length > 0) {
      blocks.push({
        kind: 'paragraph',
        inlines: parseInline(paraLines.join('\n')),
      } as MarkdownBlockParagraph);
    }
  }

  return blocks;
};

/** Count leading spaces of a raw markdown list line. */
const countLeadingSpaces = (line: string): number => {
  const m: RegExpMatchArray | null = line.match(/^\s*/);
  if (m === null) return 0;
  return m[0].length;
};

/** Nesting depth of a list line relative to the block's base indent (2 spaces per level, 0..3). */
const listDepthAt = (line: string, baseIndent: number): number => {
  const d: number = Math.floor((countLeadingSpaces(line) - baseIndent) / 2);
  return Math.min(3, Math.max(0, d));
};

/** Emit the depths field only when some item is actually nested (keeps flat lists unchanged). */
const listDepthsField = (depths: number[]): number[] | undefined => {
  for (const d of depths) {
    if (d > 0) return depths;
  }
  return undefined;
};

/**
 * Ordered-list numbering (CommonMark-style): the top-level sequence starts at
 * `start` and continues across nested runs; each nested depth restarts at 1 and
 * resets anything deeper. `depths` may omit entries (treated as depth 0).
 */
export const orderedListLabel = (start: number, depths: number[], idx: number): string => {
  const counters: number[] = [0, 0, 0, 0];
  const seen: boolean[] = [false, false, false, false];
  let depth: number = 0;
  for (let i: number = 0; i <= idx; i++) {
    depth = i < depths.length ? depths[i] : 0;
    if (depth < 0) depth = 0;
    if (depth > 3) depth = 3;
    if (!seen[depth]) {
      counters[depth] = i === 0 ? start : 1;
      seen[depth] = true;
    } else {
      counters[depth] += 1;
    }
    for (let k: number = depth + 1; k < 4; k++) {
      seen[k] = false;
    }
  }
  return `${counters[depth]}`;
};

/** Parse Markdown without allowing malformed input to break message rendering. */
export const parseMarkdown = (markdown: string): MarkdownBlock[] => {
  try {
    return parseMarkdownInternal(markdown);
  } catch (_error) {
    const text: string = typeof markdown === 'string' ? markdown : String(markdown);
    return text.length > 0
      ? [{ kind: 'paragraph', inlines: [{ type: 'text', text }] } as MarkdownBlockParagraph]
      : [];
  }
};
