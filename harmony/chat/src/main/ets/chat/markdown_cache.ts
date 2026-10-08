// markdown_cache — 有界 Markdown 缓存(纯逻辑,零 SDK 依赖)
//
// 精确内容命中不解析；流式追加保留已闭合前缀块，只解析新闭合片段和
// 活动尾。围栏、展示公式内部的空行不是 checkpoint，列表/表格/引用等
// 延续块等到空行再固定。替换/截短失效，LRU 同时受条目和字符预算约束。
// parse 可注入；parseCalls 记录每次实际解析(推进 checkpoint 最多两次)。

import type { MarkdownBlock } from './markdown_blocks.ts';
import { parseMarkdown } from './markdown_blocks.ts';

// 规模语义对齐 MarkdownPrewarmMaxTexts=32(预取文本数)。
// Android MarkdownParseCache 为 128 条目 / 1.2M 字符;此处 32 条目按计划取
// Prewarm 规模,字符预算用 200k(单消息流式尾部典型规模),均为保守下界。
export const MARKDOWN_CACHE_MAX_ENTRIES: number = 32;
export const MARKDOWN_CACHE_MAX_CHARS: number = 200_000;

const KEY_SEP: string = '\u0000';

/** 缓存条目 */
export interface MarkdownCacheEntry {
  /** 该条目对应的精确原始内容 */
  content: string;
  /** parse(content) 得到的块序列(前缀复用后为 前缀块 + 尾部块) */
  blocks: MarkdownBlock[];
  /** 该内容是否结束于安全块边界(可被更长内容前缀复用) */
  prefixSafe: boolean;
  /** 原始内容中最后一个已闭合块边界；其后仍会随流式追加变化。 */
  stableLength: number;
  /** blocks 中可直接复用的稳定前缀块数。 */
  stableBlockCount: number;
}

/** getOrParse 的结果 */
export interface MarkdownCacheResult {
  blocks: MarkdownBlock[];
  /** 精确内容命中(未调用 parse) */
  exact: boolean;
  /** 前缀命中(仅解析了尾部增量,复用了前缀块) */
  incremental: boolean;
}

/** 统计(测试断言用) */
export interface MarkdownCacheStats {
  entries: number;
  totalChars: number;
  exactHits: number;
  prefixHits: number;
  fullParses: number;
  parseCalls: number;
}

// ===== 纯函数:块边界安全性 =====

interface ProtectedMarkdownRange {
  start: number;
  end: number;
}

const escapedAt = (text: string, index: number): boolean => {
  let count: number = 0;
  for (let i: number = index - 1; i >= 0 && text[i] === '\\'; i--) count++;
  return count % 2 !== 0;
};

/**
 * 只保存解析器已经闭合的边界。围栏匹配与 parseMarkdown 的全局 regex
 * 一致(同时支持其归一化的 CRLF/CR)；展示公式的空行也不能拆开。
 * 普通段落/列表/引用/表格等到空行后
 * 才固定下来，避免流式中的半行被当作完成块。
 * 返回 -1 表示当前 parser 的嵌入围栏占位语义要求整段解析。
 */
const stablePrefixLength = (text: string): number => {
  const protectedRanges: ProtectedMarkdownRange[] = [];
  const fenceRanges: ProtectedMarkdownRange[] = [];
  const fenceRe: RegExp = /```([^\r\n]*)(?:\r\n|\r|\n)([\s\S]*?)(?:```|$)/g;
  let match: RegExpExecArray | null;
  while ((match = fenceRe.exec(text)) !== null) {
    const end: number = match.index + match[0].length;
    const closed: boolean = match[0].endsWith('```');
    // The current parser replaces fences globally. An embedded fence leaks a
    // placeholder into its surrounding paragraph; splitting could change that
    // placeholder's index or separate the paragraph. Keep that input together.
    if ((match.index > 0 && text[match.index - 1] !== '\n' && text[match.index - 1] !== '\r') ||
      (closed && ((end < text.length && text[end] !== '\n' && text[end] !== '\r') ||
      (end - 3 > 0 && text[end - 4] !== '\n' && text[end - 4] !== '\r')))) return -1;
    const range: ProtectedMarkdownRange = { start: match.index, end };
    protectedRanges.push(range);
    fenceRanges.push(range);
  }
  // parseInline gives inline code priority over math, including literal $$ / \[.
  const inlineCodeRanges: ProtectedMarkdownRange[] = [];
  const inlineCodeRe: RegExp = /`([^`]+)`/g;
  while ((match = inlineCodeRe.exec(text)) !== null) {
    inlineCodeRanges.push({ start: match.index, end: match.index + match[0].length });
  }
  let fenceIndex: number = 0;
  let codeIndex: number = 0;
  for (let pos: number = 0; pos < text.length; pos++) {
    while (fenceIndex < fenceRanges.length && pos >= fenceRanges[fenceIndex].end) fenceIndex++;
    if (fenceIndex < fenceRanges.length && pos >= fenceRanges[fenceIndex].start) {
      pos = fenceRanges[fenceIndex].end - 1;
      continue;
    }
    while (codeIndex < inlineCodeRanges.length && pos >= inlineCodeRanges[codeIndex].end) codeIndex++;
    if (codeIndex < inlineCodeRanges.length && pos >= inlineCodeRanges[codeIndex].start) {
      pos = inlineCodeRanges[codeIndex].end - 1;
      continue;
    }
    if (escapedAt(text, pos)) continue;
    let opening: string = '';
    let closing: string = '';
    let display: boolean = false;
    if (text.startsWith('$$', pos)) {
      opening = '$$'; closing = '$$'; display = true;
    } else if (text.startsWith('\\[', pos)) {
      opening = '\\['; closing = '\\]'; display = true;
    } else if (text.startsWith('\\(', pos)) {
      opening = '\\('; closing = '\\)';
    } else if (text[pos] === '$' && (pos === 0 || text[pos - 1] !== '$')) {
      opening = '$'; closing = '$';
    }
    if (opening.length === 0) continue;
    let end: number = pos + opening.length;
    let mathFenceIndex: number = fenceIndex;
    while (end < text.length) {
      while (mathFenceIndex < fenceRanges.length && end >= fenceRanges[mathFenceIndex].end) mathFenceIndex++;
      if (mathFenceIndex < fenceRanges.length && end >= fenceRanges[mathFenceIndex].start) {
        end = fenceRanges[mathFenceIndex].end;
        continue;
      }
      if (!display && (text[end] === '\n' || text[end] === '\r')) break;
      if (text.startsWith(closing, end) && !escapedAt(text, end) &&
        !(closing === '$' && (text[end + 1] === '$' || text[end - 1] === '$'))) break;
      end++;
    }
    if (display) {
      protectedRanges.push({ start: pos, end: end < text.length ? end + closing.length : text.length });
    }
    if (end < text.length && text.startsWith(closing, end) && end > pos + opening.length) {
      pos = end + closing.length - 1;
    } else {
      pos += opening.length - 1;
    }
  }
  protectedRanges.sort((a: ProtectedMarkdownRange, b: ProtectedMarkdownRange): number => a.start - b.start);
  const newlineRe: RegExp = /\r\n|\r|\n/g;
  let lineStart: number = 0;
  let stable: number = 0;
  let rangeIndex: number = 0;
  while ((match = newlineRe.exec(text)) !== null) {
    const line: string = text.substring(lineStart, match.index);
    const end: number = match.index + match[0].length;
    while (rangeIndex < protectedRanges.length && match.index >= protectedRanges[rangeIndex].end) rangeIndex++;
    const inside: boolean = rangeIndex < protectedRanges.length &&
      match.index >= protectedRanges[rangeIndex].start;
    // An empty line separates every supported block. Heading/hr and a plain
    // closing fence can also finish at a single newline without absorbing text.
    if (!inside && (line.trim().length === 0 || /^#{1,6}\s+.+$/.test(line) ||
      /^(-{3,}|\*{3,}|_{3,})\s*$/.test(line) || line === '```')) {
      stable = end;
    }
    lineStart = end;
  }
  return stable;
};

/** 是否整个内容都已闭合；流式缓存也能复用其中的部分稳定前缀。 */
export const markdownPrefixReuseSafe = (text: string): boolean =>
  text.length === 0 || stablePrefixLength(text) === text.length;

// ===== 缓存 =====

/** 键构造:messageId + offset */
export const markdownCacheKey = (messageId: string, offset: number): string =>
  `${messageId}${KEY_SEP}${offset}`;

/** 有界 Markdown 解析缓存(精确 + 前缀增量,LRU/字符双预算) */
export class MarkdownCache {
  private entries: Map<string, MarkdownCacheEntry> = new Map();
  private totalChars: number = 0;
  private exactHits: number = 0;
  private prefixHits: number = 0;
  private fullParses: number = 0;
  private parseCalls: number = 0;
  private parse: (markdown: string) => MarkdownBlock[];

  constructor(parse: (markdown: string) => MarkdownBlock[] = parseMarkdown) {
    this.parse = parse;
  }

  private touch(key: string): void {
    const entry: MarkdownCacheEntry | undefined = this.entries.get(key);
    if (entry === undefined) return;
    this.entries.delete(key);
    this.entries.set(key, entry);
  }

  private removeKey(key: string): void {
    const entry: MarkdownCacheEntry | undefined = this.entries.get(key);
    if (entry !== undefined) {
      this.totalChars -= entry.content.length;
      this.entries.delete(key);
    }
  }

  private store(key: string, entry: MarkdownCacheEntry): void {
    const old: MarkdownCacheEntry | undefined = this.entries.get(key);
    if (old !== undefined) this.totalChars -= old.content.length;
    this.entries.set(key, entry);
    this.totalChars += entry.content.length;
    // offset 递增失效:同 message 更早 offset 的条目已被本内容取代,回收
    const messagePrefix: string = key.substring(0, key.indexOf(KEY_SEP));
    const staleKeys: string[] = [];
    this.entries.forEach((_v: MarkdownCacheEntry, k: string): void => {
      if (k !== key && k.startsWith(`${messagePrefix}${KEY_SEP}`)) staleKeys.push(k);
    });
    for (const k of staleKeys) this.removeKey(k);
    this.trim();
  }

  private trim(): void {
    while (
      this.entries.size > MARKDOWN_CACHE_MAX_ENTRIES ||
      this.totalChars > MARKDOWN_CACHE_MAX_CHARS
    ) {
      let eldest: string = '';
      let found: boolean = false;
      this.entries.forEach((_v: MarkdownCacheEntry, k: string): void => {
        if (!found) {
          eldest = k;
          found = true;
        }
      });
      if (!found) break;
      this.removeKey(eldest);
    }
  }

  /**
   * 获取 content 的解析块(优先精确命中,其次前缀增量,否则全量解析)。
   * @param messageId 消息 id
   * @param offset 内容偏移(流式增长中 = 上一段内容长度;再生内容变化 → 失效)
   * @param content 待解析内容
   */
  getOrParse(messageId: string, offset: number, content: string): MarkdownCacheResult {
    const key: string = markdownCacheKey(messageId, offset);
    const cached: MarkdownCacheEntry | undefined = this.entries.get(key);
    if (cached !== undefined && cached.content === content) {
      this.exactHits++;
      this.touch(key);
      return { blocks: cached.blocks, exact: true, incremental: false };
    }

    // 同一个 offset 也可能连续收流；内容必须是追加关系，替换/截短会失效。
    const messagePrefix: string = `${messageId}${KEY_SEP}`;
    let candidate: MarkdownCacheEntry | undefined;
    this.entries.forEach((entry: MarkdownCacheEntry, k: string): void => {
      if (k.startsWith(messagePrefix) && entry.stableLength > 0 &&
        content.length > entry.content.length && content.startsWith(entry.content) &&
        (candidate === undefined || entry.content.length > candidate.content.length)) {
        candidate = entry;
      }
    });

    let prefixLength: number = candidate === undefined ? 0 : candidate.stableLength;
    let tail: string = content.substring(prefixLength);
    let closedTailLength: number = stablePrefixLength(tail);
    if (closedTailLength < 0) {
      candidate = undefined;
      prefixLength = 0;
      tail = content;
      closedTailLength = 0;
    }
    const prefixBlocks: MarkdownBlock[] = candidate === undefined ? []
      : candidate.blocks.slice(0, candidate.stableBlockCount);
    // 仅新闭合的片段和活动尾参与解析；既有稳定前缀永远不再进入 parser。
    const closedBlocks: MarkdownBlock[] = closedTailLength > 0
      ? this.parsePart(tail.substring(0, closedTailLength)) : [];
    const activeBlocks: MarkdownBlock[] = closedTailLength < tail.length
      ? this.parsePart(tail.substring(closedTailLength)) : [];
    if (tail.length === 0) this.parsePart(tail);
    const blocks: MarkdownBlock[] = prefixBlocks.concat(closedBlocks, activeBlocks);
    const incremental: boolean = candidate !== undefined;
    if (incremental) this.prefixHits++;
    else this.fullParses++;
    this.store(key, {
      content,
      blocks,
      prefixSafe: prefixLength + closedTailLength === content.length,
      stableLength: prefixLength + closedTailLength,
      stableBlockCount: prefixBlocks.length + closedBlocks.length,
    });
    return { blocks, exact: false, incremental };
  }

  private parsePart(content: string): MarkdownBlock[] {
    this.parseCalls++;
    return this.parse(content);
  }

  /** 直接查询(不解析):命中返回已缓存块,否则 null */
  get(messageId: string, offset: number, content: string): MarkdownBlock[] | null {
    const cached: MarkdownCacheEntry | undefined = this.entries.get(markdownCacheKey(messageId, offset));
    if (cached !== undefined && cached.content === content) {
      this.exactHits++;
      this.touch(markdownCacheKey(messageId, offset));
      return cached.blocks;
    }
    return null;
  }

  /** 清空缓存(消息离开视口/会话切换) */
  clear(): void {
    this.entries.clear();
    this.totalChars = 0;
    this.exactHits = 0;
    this.prefixHits = 0;
    this.fullParses = 0;
    this.parseCalls = 0;
  }

  /** 统计(测试断言) */
  stats(): MarkdownCacheStats {
    return {
      entries: this.entries.size,
      totalChars: this.totalChars,
      exactHits: this.exactHits,
      prefixHits: this.prefixHits,
      fullParses: this.fullParses,
      parseCalls: this.parseCalls,
    };
  }
}
