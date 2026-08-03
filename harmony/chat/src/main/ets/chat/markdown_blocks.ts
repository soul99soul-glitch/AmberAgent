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
  items: InlineToken[][];
}

export interface MarkdownBlockBlockquote {
  kind: 'blockquote';
  inlines: InlineToken[];
}

export interface MarkdownBlockTable {
  kind: 'table';
  headers: InlineToken[][];
  rows: InlineToken[][][];
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

// 表格行解析
const parseTableRow = (line: string): string[] => {
  const trimmed: string = line.trim().replace(/^\||\|$/g, '');
  return trimmed.split('|').map((cell: string): string => cell.trim());
};

// 判断是否为表格分隔行 |---|---|
const isTableSeparator = (line: string): boolean => {
  return /^\s*\|?[\s-:|-]+\|?\s*$/.test(line) && line.includes('-');
};

// ===== 主解析函数 =====

export const parseMarkdown = (markdown: string): MarkdownBlock[] => {
  const input: string = markdown.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  if (input.trim().length === 0) return [];

  const codeBlocks: CodeBlockRange[] = findCodeBlocks(input);

  // 用占位符替换代码块
  let work: string = input;
  const codeBlockData: { lang: string; code: string }[] = [];
  for (let i: number = codeBlocks.length - 1; i >= 0; i--) {
    const block: CodeBlockRange = codeBlocks[i];
    const placeholder: string = `\x00CODEBLOCK_${i}\x00`;
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
    const codeBlockMatch: RegExpMatchArray | null = line.match(/^\x00CODEBLOCK_(\d+)\x00$/);
    if (codeBlockMatch !== null) {
      const idx: number = parseInt(codeBlockMatch[1], 10);
      const data: { lang: string; code: string } = codeBlockData[idx];
      blocks.push({
        kind: 'code_block',
        lang: data.lang,
        code: data.code,
      } as MarkdownBlockCode);
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

    // 无序列表 - / *
    if (/^\s*[-*]\s+/.test(line)) {
      const items: InlineToken[][] = [];
      while (i < lines.length && /^\s*[-*]\s+/.test(lines[i])) {
        const itemText: string = lines[i].replace(/^\s*[-*]\s+/, '');
        items.push(parseInline(itemText));
        i++;
      }
      blocks.push({
        kind: 'list',
        ordered: false,
        items,
      } as MarkdownBlockList);
      continue;
    }

    // 有序列表 1. / 2.
    if (/^\s*\d+\.\s+/.test(line)) {
      const items: InlineToken[][] = [];
      while (i < lines.length && /^\s*\d+\.\s+/.test(lines[i])) {
        const itemText: string = lines[i].replace(/^\s*\d+\.\s+/, '');
        items.push(parseInline(itemText));
        i++;
      }
      blocks.push({
        kind: 'list',
        ordered: true,
        items,
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
        const headerInlines: InlineToken[][] = headers.map((h: string): InlineToken[] => parseInline(h));
        const bodyRows: InlineToken[][][] = [];
        for (let r: number = 2; r < tableLines.length; r++) {
          const cells: string[] = parseTableRow(tableLines[r]);
          bodyRows.push(cells.map((c: string): InlineToken[] => parseInline(c)));
        }
        blocks.push({
          kind: 'table',
          headers: headerInlines,
          rows: bodyRows,
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
      !/^\x00CODEBLOCK_\d+\x00$/.test(lines[i]) &&
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
