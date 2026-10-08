// markdown_cache 纯逻辑测试(node:test,零 SDK 依赖)
//
// Android 基准: MessageRenderCache.markdownParseResult(MarkdownParseCache:
//   按内容精确缓存 + 128 条目 / 1.2M 字符)+ StreamingMarkdownParseCache
//   (streaming 前缀块 + 尾部增量)+ MarkdownPrewarmMaxTexts=32 规模语义。
// 裁剪:单 Map 同时承载精确/前缀缓存;parse 可注入(计数伪解析器)。

import { test } from 'node:test';
import assert from 'node:assert/strict';

import { parseMarkdown } from '../main/ets/chat/markdown_blocks.ts';
import {
  MarkdownCache,
  markdownCacheKey,
  markdownPrefixReuseSafe,
  MARKDOWN_CACHE_MAX_ENTRIES,
} from '../main/ets/chat/markdown_cache.ts';
import type { MarkdownBlock } from '../main/ets/chat/markdown_blocks.ts';

const blockKinds = (blocks: MarkdownBlock[]): string[] =>
  blocks.map((b: MarkdownBlock): string => b.kind);

// 计数伪解析器:直接产出与 parseMarkdown 一致的块(避免引入解析成本),
// 用块 kind 序列 + 调用次数断言增量行为。
const countingParser = (): { parse: (md: string) => MarkdownBlock[]; calls: () => number } => {
  let calls: number = 0;
  return {
    parse: (md: string): MarkdownBlock[] => {
      calls++;
      return parseMarkdown(md);
    },
    calls: (): number => calls,
  };
};

// ===== markdownPrefixReuseSafe:代码围栏/表格边界安全判定 =====

test('prefixReuseSafe:空内容安全', () => {
  assert.equal(markdownPrefixReuseSafe(''), true);
});

test('prefixReuseSafe:末尾空行安全(块间分隔)', () => {
  assert.equal(markdownPrefixReuseSafe('# Title\n\n'), true);
  assert.equal(markdownPrefixReuseSafe('para\n\n'), true);
});

test('prefixReuseSafe:完整块行安全(标题/水平线/闭合围栏)', () => {
  assert.equal(markdownPrefixReuseSafe('# Title\n'), true);
  assert.equal(markdownPrefixReuseSafe('---\n'), true);
  assert.equal(markdownPrefixReuseSafe('```\ncode\n```\n'), true);
});

test('prefixReuseSafe:段落末行不安全(续行合并)', () => {
  assert.equal(markdownPrefixReuseSafe('para\n'), false);
  assert.equal(markdownPrefixReuseSafe('para'), false);
});

test('prefixReuseSafe:未闭合代码围栏内部不安全', () => {
  assert.equal(markdownPrefixReuseSafe('```\ncode'), false);
  // 奇数次 ``` → 仍在围栏内
  assert.equal(markdownPrefixReuseSafe('```\ncode\n```\nmore'), false);
});

test('prefixReuseSafe:列表/引用/表格续行不安全', () => {
  assert.equal(markdownPrefixReuseSafe('- item\n'), false);
  assert.equal(markdownPrefixReuseSafe('> quote\n'), false);
  assert.equal(markdownPrefixReuseSafe('| a | b |\n|---|'), false);
});

// ===== 精确内容缓存(MessageRenderCache 语义) =====

test('MarkdownCache:同内容同 offset 二次解析精确命中', () => {
  const p = countingParser();
  const cache = new MarkdownCache(p.parse);
  const r1 = cache.getOrParse('msg1', 0, '# Hello\n');
  assert.equal(r1.exact, false);
  assert.equal(p.calls(), 1);
  const r2 = cache.getOrParse('msg1', 0, '# Hello\n');
  assert.equal(r2.exact, true);
  assert.equal(r2.incremental, false);
  assert.equal(p.calls(), 1, '精确命中不触发 parse');
  assert.deepEqual(blockKinds(r2.blocks), blockKinds(r1.blocks));
});

test('MarkdownCache:同 offset 不同内容全量重解析(失效)', () => {
  const p = countingParser();
  const cache = new MarkdownCache(p.parse);
  cache.getOrParse('msg1', 0, '# A\n');
  cache.getOrParse('msg1', 0, '# B\n');
  assert.equal(p.calls(), 2, '同 key 内容变化 → 重解析');
});

test('MarkdownCache:不同 message 互不干扰', () => {
  const p = countingParser();
  const cache = new MarkdownCache(p.parse);
  cache.getOrParse('m1', 0, 'a\n');
  const r = cache.getOrParse('m2', 0, 'a\n');
  assert.equal(r.exact, false, '不同 message 不共享条目');
  assert.equal(p.calls(), 2);
});

// ===== 前缀/增量缓存(StreamingMarkdownParseCache 语义) =====

test('MarkdownCache:前缀命中只解析尾部增量', () => {
  const p = countingParser();
  const cache = new MarkdownCache(p.parse);
  // 首段以安全边界结束(标题 + 空行)
  const r1 = cache.getOrParse('msg1', 0, '# Title\n\n');
  assert.equal(r1.incremental, false);
  assert.equal(p.calls(), 1);
  // 下一段:offset 递增,内容为前缀超集 → 增量命中,复用前缀块
  const r2 = cache.getOrParse('msg1', r1.blocks.length > 0 ? 8 : 0, '# Title\n\nBody');
  assert.equal(r2.incremental, true);
  assert.equal(r2.exact, false);
  assert.equal(p.calls(), 2, '前缀命中只解析一次尾部');
  assert.deepEqual(blockKinds(r2.blocks), ['heading', 'paragraph']);
});

test('MarkdownCache:前缀命中结果与全量解析一致', () => {
  const cache = new MarkdownCache();
  const r1 = cache.getOrParse('msg1', 0, '## Section\n\n');
  const r2 = cache.getOrParse('msg1', 8, '## Section\n\nSome body text');
  const full = parseMarkdown('## Section\n\nSome body text');
  assert.deepEqual(blockKinds(r2.blocks), blockKinds(full));
  assert.equal(r1.blocks.length, 1);
  assert.equal(r2.blocks.length, 2);
});

test('MarkdownCache:取最长安全前缀候选', () => {
  const p = countingParser();
  const cache = new MarkdownCache(p.parse);
  cache.getOrParse('m', 0, '# A\n\n');
  cache.getOrParse('m', 4, '# A\n\nB\n\n');
  // 最长的安全前缀 = "# A\n\nB\n\n"(len 8)
  const r = cache.getOrParse('m', 8, '# A\n\nB\n\nC\n');
  assert.equal(r.incremental, true);
  // 只解析尾部 "C\n"(1 次)
  assert.equal(p.calls(), 3);
  // '# A' / 'B' / 'C' 均为段落级块:heading + paragraph + paragraph
  assert.deepEqual(blockKinds(r.blocks), ['heading', 'paragraph', 'paragraph']);
});

// ===== 代码围栏/表格边界的失效回退 =====

test('MarkdownCache:围栏未闭合 → 前缀复用不安全 → 全量解析', () => {
  const p = countingParser();
  const cache = new MarkdownCache(p.parse);
  // 首段内容结束在未闭合围栏内(prefixSafe=false)
  cache.getOrParse('m', 0, '```\ncode\n');
  assert.equal(p.calls(), 1);
  // 更长内容仍以它为前缀,但前缀不安全 → 全量重解析
  const r = cache.getOrParse('m', 6, '```\ncode\n```\n');
  assert.equal(r.incremental, false);
  assert.equal(p.calls(), 2, '不安全前缀回退全量解析');
  assert.deepEqual(blockKinds(r.blocks), ['code_block']);
});

test('MarkdownCache:闭合围栏后可安全增量', () => {
  const cache = new MarkdownCache();
  const r1 = cache.getOrParse('m', 0, '```\ncode\n```\n');
  assert.equal(r1.blocks.length, 1);
  const r2 = cache.getOrParse('m', 12, '```\ncode\n```\n\nafter');
  assert.equal(r2.incremental, true);
  assert.deepEqual(blockKinds(r2.blocks), ['code_block', 'paragraph']);
});

test('MarkdownCache:表格续行回退全量(前缀复用会产出错误块)', () => {
  const p = countingParser();
  const cache = new MarkdownCache(p.parse);
  cache.getOrParse('m', 0, '| a | b |\n|---|---|\n| 1 | 2 |');
  // 表格行续行:prefixReuseSafe=false → 全量
  const r = cache.getOrParse('m', 16, '| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |');
  assert.equal(r.incremental, false);
  assert.equal(r.exact, false);
  assert.equal(p.calls(), 2);
  assert.deepEqual(blockKinds(r.blocks), ['table']);
});

// ===== 有界缓存:条目上限 + 字符预算 =====

test('MarkdownCache:条目数不超上限,LRU 淘汰最旧', () => {
  const cache = new MarkdownCache();
  // 每个 message 独立 key,各占 1 条目;连续写入超出上限
  for (let i = 0; i < MARKDOWN_CACHE_MAX_ENTRIES + 10; i++) {
    cache.getOrParse(`m${i}`, 0, 'content\n');
  }
  const stats = cache.stats();
  assert.ok(stats.entries <= MARKDOWN_CACHE_MAX_ENTRIES);
  // 最早写入的 m0 已被淘汰(精确命中 false)
  const r = cache.getOrParse('m0', 0, 'content\n');
  assert.equal(r.exact, false);
});

test('MarkdownCache:同一消息旧 offset 条目被回收(offset 递增失效)', () => {
  const p = countingParser();
  const cache = new MarkdownCache(p.parse);
  cache.getOrParse('m', 0, '# A\n\n');
  cache.getOrParse('m', 4, '# A\n\nB\n\n');
  const stats = cache.stats();
  // 同消息只保留最新 offset 的 1 条(旧 offset 条目被回收)
  assert.equal(stats.entries, 1);
  assert.equal(p.calls(), 2);
  // 继续增长:复用最新安全前缀
  const r = cache.getOrParse('m', 8, '# A\n\nB\n\nC\n');
  assert.equal(r.exact, false);
  assert.equal(r.incremental, true);
  assert.equal(p.calls(), 3, '只解析尾部增量');
  assert.equal(cache.stats().entries, 1);
});

test('MarkdownCache:get 直查不解析(MessageRenderCache.markdownParseResult 语义)', () => {
  const p = countingParser();
  const cache = new MarkdownCache(p.parse);
  cache.getOrParse('m', 0, 'hit\n');
  const cached = cache.get('m', 0, 'hit\n');
  assert.ok(cached !== null);
  assert.equal(p.calls(), 1, '直查不触发 parse');
  const miss = cache.get('m', 0, 'miss\n');
  assert.equal(miss, null);
});

test('MarkdownCache:clear 清空并重置统计', () => {
  const cache = new MarkdownCache();
  cache.getOrParse('m', 0, 'x\n');
  cache.clear();
  const stats = cache.stats();
  assert.equal(stats.entries, 0);
  assert.equal(stats.totalChars, 0);
  assert.equal(stats.fullParses, 0);
  assert.equal(stats.prefixHits, 0);
  assert.equal(stats.exactHits, 0);
});

test('MarkdownCache:总字符预算不超上限', () => {
  const cache = new MarkdownCache();
  // 长内容单条接近预算上限;连续写入触发字符预算淘汰
  for (let i = 0; i < 100; i++) {
    cache.getOrParse(`m${i}`, 0, 'x'.repeat(5000) + '\n');
  }
  const stats = cache.stats();
  assert.ok(stats.totalChars <= 200_000, `totalChars=${stats.totalChars}`);
});

// ===== 键构造 =====

test('markdownCacheKey:messageId + offset 组合且不碰撞', () => {
  assert.equal(markdownCacheKey('msg', 3), 'msg\u00003');
  assert.notEqual(markdownCacheKey('msg1', 0), markdownCacheKey('msg', 10));
  // 组合歧义防护:messageId "a1" + offset 2 与 messageId "a" + offset 12 不应同键
  assert.notEqual(markdownCacheKey('a1', 2), markdownCacheKey('a', 12));
});

// ===== 统计 =====

test('MarkdownCache:stats 累计命中/解析次数', () => {
  const p = countingParser();
  const cache = new MarkdownCache(p.parse);
  cache.getOrParse('m', 0, 'a\n'); // full
  cache.getOrParse('m', 0, 'a\n'); // exact hit(同内容同 offset)
  // 段落前缀不安全 → 续行全量
  cache.getOrParse('m', 2, 'a\nb\n'); // full
  // 安全边界 → 前缀增量
  cache.getOrParse('m2', 0, '# H\n\n'); // full
  cache.getOrParse('m2', 4, '# H\n\nB\n'); // incremental
  const stats = cache.stats();
  assert.equal(stats.fullParses, 3);
  assert.equal(stats.exactHits, 1);
  assert.equal(stats.prefixHits, 1);
  assert.equal(stats.parseCalls, 4);
});
// A normal streaming frame contains closed paragraphs and an unfinished final block.
test('MarkdownCache:20KB稳定段落只解析一次,后续帧只解析活动尾并复用块对象', () => {
  const inputs: string[] = [];
  const cache = new MarkdownCache((md: string): MarkdownBlock[] => {
    inputs.push(md);
    return parseMarkdown(md);
  });
  const prefix = `${'已完成的正文。'.repeat(3000)}\n\n`;
  let content = `${prefix}活动尾`;
  const first = cache.getOrParse('stream', 0, content);
  const stable = first.blocks[0];
  inputs.length = 0;
  for (const chunk of ['继续', '**强调', '**', '\n后续行']) {
    content += chunk;
    const result = cache.getOrParse('stream', 0, content);
    assert.equal(result.incremental, true);
    assert.strictEqual(result.blocks[0], stable);
    assert.deepEqual(result.blocks, parseMarkdown(content));
  }
  assert.equal(inputs.length, 4);
  assert.ok(inputs.every((input: string): boolean => input.length < 100));
});

test('MarkdownCache:每字符流式代码/表格/列表/数学/换行与全量解析深等', () => {
  const samples: string[] = [
    '前缀\n\n```ts\nconst x = 1;\n\nconst y = 2;\n```\n\n尾部',
    '前缀\n\n| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |\n\n尾部',
    '# 标题\n\n- 第一项\n  - 嵌套项\n- 最后一项\n\n1. 一\n2. 二\n\n> 引用\n> 第二行\n\n尾部',
    '前缀\n\n$$\na+b\n\n-c\n$$\n\n尾部 $x$ 和 \\(y\\)',
    '前缀\n\n\\[\na+b\n\n-c\n\\]\n\n尾部',
    '前缀\n\n文本 `$$` 与转义 \\$$\n\n后文',
    '前缀\r\n\r\n```\r\ncode\r\n\r\n```\r\n\r\n尾部',
    '前缀\n\n$$x\n\n仍未闭合\n\n继续',
    '前缀\n\n嵌入 ```ts\ncode\n\n``` 后缀\n\n尾部',
    '```ts\nx\n```\n\n嵌入 ```ts\ncode\n```\n\n尾部',
    '\\[\n> 引用\n```\n\\]\n```\n\\]\n\n尾部',
    '前缀\n \t\n活动段落\n\n尾部',
  ];
  for (const sample of samples) {
    const cache = new MarkdownCache();
    for (let length = 1; length <= sample.length; length++) {
      const content = sample.slice(0, length);
      const result = cache.getOrParse('sample', length - 1, content);
      assert.deepEqual(result.blocks, parseMarkdown(content), `${JSON.stringify(sample)} at ${length}`);
    }
  }
});

test('MarkdownCache:替换/截短失效后重建稳定前缀', () => {
  const cache = new MarkdownCache();
  cache.getOrParse('m', 0, '旧段落\n\n活动尾');
  for (const content of ['新段落\n\n新尾部', '新段落', '新段落\n\n继续']) {
    assert.deepEqual(cache.getOrParse('m', 0, content).blocks, parseMarkdown(content));
  }
});


test('MarkdownCache:checkpoint前进只解析新完成片段和活动尾,统计实际parse调用', () => {
  const inputs: string[] = [];
  const cache = new MarkdownCache((md: string): MarkdownBlock[] => {
    inputs.push(md);
    return parseMarkdown(md);
  });
  const first = cache.getOrParse('m', 0, '首段\n\n活动');
  assert.deepEqual(inputs, ['首段\n\n', '活动']);
  inputs.length = 0;
  const next = cache.getOrParse('m', 0, '首段\n\n活动完成\n\n新活动');
  assert.deepEqual(inputs, ['活动完成\n\n', '新活动']);
  assert.strictEqual(next.blocks[0], first.blocks[0]);
  inputs.length = 0;
  const last = cache.getOrParse('m', 0, '首段\n\n活动完成\n\n新活动继续');
  assert.deepEqual(inputs, ['新活动继续']);
  assert.strictEqual(last.blocks[1], next.blocks[1]);
  assert.equal(cache.stats().parseCalls, 5);
  assert.equal(cache.stats().totalChars, '首段\n\n活动完成\n\n新活动继续'.length);
});
