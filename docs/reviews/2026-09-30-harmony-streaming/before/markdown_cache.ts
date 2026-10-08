// markdown_cache — Markdown 增量解析缓存(纯逻辑,零 SDK 依赖,可 node:test)
//
// Android 基准:
//   MessageRenderCache.markdownParseResult(MarkdownParseCache: 按内容精确缓存 +
//     128 条目 / 1.2M 字符上限)
//   StreamingMarkdownParseCache(streaming: stable 前缀块 + 尾部增量解析)
//   MarkdownPrewarmMaxTexts=32(ChatList.kt:121 预取规模语义)
//
// 裁剪(保持抽象小,ArkTS API 12):
//   - 单 Map 同时承担「精确内容缓存」与「前缀/增量缓存」:
//       key = `${messageId}\u0000${offset}`(计划 §4.4 语义)
//       entry = { content, blocks, prefixSafe }
//   - 前缀命中: 新内容以某条已缓存 content 为真前缀,且该前缀结束于安全块边界
//     (非代码围栏内部 / 非表格延续 / 非列表·引用·段落续行)时,只解析尾部增量,
//     复用前缀 blocks —— 语义与整段 parseMarkdown 一致。
//   - 安全回退: 代码围栏/表格边界使前缀复用不安全时 → 整段全量重解析。
//   - 容量: 条目数 ≤ 32 + 总字符预算,LRU 淘汰;同一 message 的旧 offset 视为失效
//     (offset 递增失效),存储新条目时回收同 message 更早的 offset 条目。
// 零 UI/零 IO,可单测;parse 可注入(测试用计数伪解析器)。

import type { MarkdownBlock } from './markdown_blocks.ts';
import { parseMarkdown, hasUnclosedDisplayMath } from './markdown_blocks.ts';

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

const isFenceLine = (line: string): boolean => line.startsWith('```');

/** 内容是否结束在未闭合代码围栏内部(奇数次 ``` → 仍处于围栏内) */
const endsInsideCodeFence = (text: string): boolean => {
  let inFence: boolean = false;
  const lines: string[] = text.split('\n');
  for (const line of lines) {
    if (isFenceLine(line)) inFence = !inFence;
  }
  return inFence;
};

const lastNonEmptyLine = (text: string): string | null => {
  const lines: string[] = text.split('\n');
  for (let i = lines.length - 1; i >= 0; i--) {
    if (lines[i].trim().length > 0) return lines[i];
  }
  return null;
};

const isListLine = (line: string): boolean => /^\s*[-*]\s+/.test(line) || /^\s*\d+\.\s+/.test(line);
const isBlockquoteLine = (line: string): boolean => line.startsWith('>');
const isHeadingLine = (line: string): boolean => /^#{1,6}\s+/.test(line);
const isHrLine = (line: string): boolean => /^(-{3,}|\*{3,}|_{3,})\s*$/.test(line);
// 保守:含未转义 | 的行都视为「可能处于表格延续」→ 前缀复用不安全
const isTableRowLike = (line: string): boolean => line.includes('|');

/**
 * 前缀内容是否结束于「安全块边界」——若在该位置追加后缀,
 * parse(prefix) + parse(tail) 与 parse(prefix+tail) 结果一致。
 *
 * 安全情形:
 *   - 空内容;
 *   - 末尾空行(块间空行分隔)且不在未闭合代码围栏内;
 *   - 末行为标题 / 水平线 / 已闭合代码围栏结束 ```(块级结构不可被续行吸收)。
 *
 * 不安全情形(回退全量解析):
 *   - 结束在未闭合代码围栏内;
 *   - 末行为列表项 / 引用 / 表格行(后缀可续行合并进同一块);
 *   - 末行为普通文本(段落续行);内容不以换行结尾(行中间截断)。
 */
export const markdownPrefixReuseSafe = (text: string): boolean => {
  if (text.length === 0) return true;
  if (endsInsideCodeFence(text) || hasUnclosedDisplayMath(text)) return false;
  if (text.endsWith('\n\n')) return true;
  if (!text.endsWith('\n')) return false;
  const lastLine: string | null = lastNonEmptyLine(text);
  if (lastLine === null) return true;
  if (isListLine(lastLine) || isBlockquoteLine(lastLine) || isTableRowLike(lastLine)) {
    return false;
  }
  if (isHeadingLine(lastLine) || isHrLine(lastLine) || isFenceLine(lastLine)) {
    return true;
  }
  // 末行为普通文本:后缀非空行会并入同一段落
  return false;
};

// ===== 缓存 =====

/** 键构造:messageId + offset */
export const markdownCacheKey = (messageId: string, offset: number): string =>
  `${messageId}${KEY_SEP}${offset}`;

/** 有界 Markdown 解析缓存(精确 + 前缀增量,LRA/字符双预算) */
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

    // 前缀候选:同 message 中 content 为真前缀的最长条目(其结束于安全边界)
    const messagePrefix: string = `${messageId}${KEY_SEP}`;
    let candidate: MarkdownCacheEntry | undefined;
    let candidateKey: string = '';
    this.entries.forEach((entry: MarkdownCacheEntry, k: string): void => {
      if (k.startsWith(messagePrefix) && k !== key && entry.prefixSafe &&
        entry.content.length > 0 && content.length > entry.content.length &&
        content.startsWith(entry.content) &&
        (candidate === undefined || entry.content.length > candidate.content.length)) {
        candidate = entry;
        candidateKey = k;
      }
    });

    if (candidate !== undefined) {
      const tail: string = content.substring(candidate.content.length);
      const tailBlocks: MarkdownBlock[] = this.parse(tail);
      this.parseCalls++;
      this.prefixHits++;
      const blocks: MarkdownBlock[] = candidate.blocks.concat(tailBlocks);
      const entry: MarkdownCacheEntry = {
        content,
        blocks,
        prefixSafe: markdownPrefixReuseSafe(content),
      };
      this.store(key, entry);
      this.touch(candidateKey);
      return { blocks, exact: false, incremental: true };
    }

    const blocks: MarkdownBlock[] = this.parse(content);
    this.parseCalls++;
    this.fullParses++;
    this.store(key, {
      content,
      blocks,
      prefixSafe: markdownPrefixReuseSafe(content),
    });
    return { blocks, exact: false, incremental: false };
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
