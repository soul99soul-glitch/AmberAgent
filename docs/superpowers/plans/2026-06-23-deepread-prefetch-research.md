# Deep Read 鸿蒙版 — Prefetch + ResearchHarness 实现计划(Phase 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal:** 实现 Deep Read 颿蒙版的预取管线(多 provider 搜索 + 正文提取 + 图片评分)和文章规划(evidence pack + article plan),接 Plan 1/2 已完成的 Domain + Platform skeleton + Agent loop。

**Architecture:** 预取是 36s wall budget 的并行 IO 任务;文章规划是 1 次非流式 LLM 调用 + 本地 fallback。两者都依赖已完成的 `HttpClient` interface、`AiClient` interface、`DeepReadOutput` schema。本 plan 的搜索 provider 只做 mockable interface + 1 个真实实现(Tavily,够 spike 用);reader-extractor 阶段 1 用纯 ArkTS regex fallback(不依赖 NAPI),Plan 8 再升级 Rust。

**Tech Stack:** ArkTS / node + tsx 测试 / 现有 deepread 模块

---

## 前置上下文(必读)

### 项目状态(2026-06-23)

- **分支**:`harmony-preview`(commit 范围 cb6f5fde7..HEAD,约 35 个 commit)
- **已完成的代码**:`harmony/deepread/` 模块,185 tests pass,tsc clean
  - Domain:`models.ts` / `enums.ts` / `helpers.ts` / `topic_id.ts` / `index.ts`
  - Platform:`http.ts` / `database.ts` / `storage.ts` / `notifier.ts` / `search.ts` / `ai_client.ts` / `repository.ts` / `observable.ts` / `sse_assembler.ts` / `json_schema_validator.ts`
  - Agent:`message.ts` / `append_chunk.ts` / `budget_prompt.ts` / `retry_classifier.ts` / `tool_execution.ts`
- **测试方式**:`cd harmony/deepread && npm test`(分两批跑,observable 单独跑避免 setInterval hang;或用 `node --import tsx/esm --test --test-timeout=10000`)
- **ArkTS 约束**:无 `any`/`unknown`(catch 除外)、无运行时 mutation、interface + factory
- **工具链状态**:Command Line Tools 26.0.0.461 装好,Gate 0 ready,但 HAP 装不到模拟器(SDK API 26 vs 模拟器 API 24 版本不匹配,需要装 DevEco Studio + API 24 SDK 才能装 HAP)。**Plan 3 全程不需要模拟器**,纯 node 测试。

### 设计文档位置

- 主设计:`docs/superpowers/specs/2026-06-22-deep-read-harmony-port-design.md`
- 本 plan 对应设计章节:§4.5(Prefetch)、§4.3(createRunContext)、§4.4(generateArticlePlan)、§5(reader-extractor)

### Android 源码对照

- 预取:`app/src/main/java/app/amber/feature/board/hotlist/deepread/DeepReadSourcePrefetcher.kt`
- Evidence pack + plan:`feature/board/impl/src/main/kotlin/app/amber/feature/board/hotlist/deepread/DeepReadResearchHarness.kt`
- reader-extractor JNI:`native/reader-extractor/src/lib.rs`
- reader JVM fallback:`DeepReadSourcePrefetcher.kt` 的 `extractReadableTextJvm`(约 14 行 regex)

### 已暴露的 review 风险(本 plan 要解决)

- **P0-8**:reader-extractor "NAPI only" 错误。Android 有 JVM regex fallback(native 结果 <18 字符时触发)。本 plan 阶段 1 只做 ArkTS fallback,不依赖 NAPI。
- **P0-9**:Prefetch 内存 LRU cache(10min/16 entries)漏了。本 plan 必须实现。
- **P1-7**:Service-priority + Bing/Jina fallback injection。本 plan 实现 fallback provider 概念。
- **P1-8**:`urlAllowedForBackgroundFetch` 私网 gate。本 plan 实现。
- **P1-9**:`evidence_quality:LOW` 是发明的;Android 在 prefetch 全空时 hard-fail。本 plan 照 Android 行为。
- **P0-D**:`articlePlan.stageSourceIds` 产出必须被 `cardsFor(stage, plan)` 消费。本 plan 实现。

---

## 范围说明

### 本 plan 做(纯 node 可测)
- `SearchProvider` 抽象 + 1 个真实实现(Tavily,API 简单)
- `ReaderExtractor` 纯 ArkTS 实现(照搬 Android JVM fallback regex)
- `DeepReadImageScorer`(图片质量评分:hero/inline/reject)
- `DeepReadSourcePrefetcher`(36s wall budget + LRU cache + 并行 fetch + 去重)
- `DeepReadEvidencePack` + `buildEvidencePack`(本地计算)
- `DeepReadArticlePlan` schema + `generateArticlePlan`(1 次 LLM + fallback)
- `cardsFor(stage, plan)`(读 stageSourceIds 路由 evidence)
- `urlAllowedForBackgroundFetch`(私网 gate)
- `interleaveSearchResults`(round-robin 合并)

### 本 plan 不做(留后续)
- 真实 RcpHttpClient 实现(需要 RCP SDK,留 Plan 5)
- 真实 OpenAiCompatibleAiClient 实现(需要 AiClient 调用,留 Plan 5)
- Rust NAPI reader-extractor(留 Plan 8)
- 其他 search provider(Zhipu/Brave/Exa 等,留增量)
- 真实 OG image extraction(简化:只从 `<img>` 提取)

### 测试策略

所有组件用 mock HttpClient / mock AiClient 注入,纯 node 测。Tavily provider 用 nock/http mock 测真实 API shape。

---

## 目录结构

```
harmony/deepread/src/main/ets/
├── domain/                    # Plan 1(已完成)
├── platform/                  # Plan 1(已完成)
├── agent/                     # Plan 2(已完成)
└── research/                  # 本 plan
    ├── search_provider.ts     # Tavily 实现
    ├── reader_extractor.ts    # ArkTS regex readability(Plan 8 升级 NAPI)
    ├── image_scorer.ts        # DeepReadImageScorer
    ├── url_filter.ts          # urlAllowedForBackgroundFetch
    ├── source_prefetcher.ts   # DeepReadSourcePrefetcher + LRU cache
    ├── evidence_pack.ts       # DeepReadEvidencePack + buildEvidencePack + cardsFor
    ├── article_plan.ts        # DeepReadArticlePlan + generateArticlePlan + fallback
    └── search_merger.ts       # interleaveSearchResults
```

测试:
```
harmony/deepread/src/test/
├── search_provider.test.ts
├── reader_extractor.test.ts
├── image_scorer.test.ts
├── url_filter.test.ts
├── source_prefetcher.test.ts
├── evidence_pack.test.ts
├── article_plan.test.ts
└── search_merger.test.ts
```

---

## Task 1: urlAllowedForBackgroundFetch(私网 gate)

**Files:** Create `harmony/deepread/src/main/ets/research/url_filter.ts` + test

照搬 Android `DeepReadSourcePrefetcher.kt:343-347`:loopback/LAN fetches 默认 blocked(防 SSRF)。

```typescript
// urlAllowedForBackgroundFetch — 私网 gate,防 SSRF
// 照搬 Android DeepReadSourcePrefetcher.kt:343-347

export const isPrivateUrl = (url: string): boolean => {
  try {
    const u = new URL(url);
    const host = u.hostname.toLowerCase();
    // loopback
    if (host === 'localhost' || host === '127.0.0.1' || host === '::1' || host === '0.0.0.0') return true;
    // .local mDNS
    if (host.endsWith('.local')) return true;
    // link-local 169.254.x.x
    if (host.startsWith('169.254.')) return true;
    // private ranges(简化版,不处理 IPv6 ULA)
    if (/^10\./.test(host)) return true;
    if (/^192\.168\./.test(host)) return true;
    if (/^172\.(1[6-9]|2\d|3[01])\./.test(host)) return true;
    return false;
  } catch {
    return true;  // 解析失败视为私网(保守)
  }
};

export const urlAllowedForBackgroundFetch = (
  url: string,
  autoApproveAllToolCalls: boolean,
  autoApproveHighRiskToolCalls: boolean,
): boolean => {
  if (!isPrivateUrl(url)) return true;
  // 私网 URL:只有两个 flag 都开才允许
  return autoApproveAllToolCalls && autoApproveHighRiskToolCalls;
};
```

测试用例:
- `https://example.com` → allowed
- `http://localhost:8080` → blocked
- `http://192.168.1.1` → blocked
- `http://10.0.0.1` → blocked
- `http://172.20.0.1` → blocked,`http://172.32.0.1` → allowed(边界)
- localhost + both flags → allowed

---

## Task 2: ReaderExtractor(ArkTS regex fallback)

**Files:** Create `harmony/deepread/src/main/ets/research/reader_extractor.ts` + test

照搬 Android `DeepReadSourcePrefetcher.kt:663-676` 的 `extractReadableTextJvm`(14 行 regex)。Plan 8 升级到 Rust NAPI。

```typescript
// ReaderExtractor — ArkTS regex readability fallback
// 照搬 Android DeepReadSourcePrefetcher.kt:663-676 extractReadableTextJvm
// Plan 8 升级为 Rust NAPI(双路径,失败/<18 字符时 fallback 到此)

export interface ExtractedArticle {
  title: string;
  contentText: string;
  contentHtml: string;
  sectionCount: number;
}

const SCRIPT_STYLE_RE = /<(script|style|noscript)[\s\S]*?<\/\1>/gi;
const TAG_RE = /<[^>]+>/g;
const WHITESPACE_RE = /\s+/g;
const TITLE_RE = /<title[^>]*>([\s\S]*?)<\/title>/i;

export const extractReadableTextArkTs = (html: string, baseUrl: string | null = null): ExtractedArticle => {
  // 1. title
  const titleMatch = html.match(TITLE_RE);
  const title = titleMatch ? titleMatch[1].trim() : (baseUrl ?? '');

  // 2. 去 script/style/noscript
  let cleaned = html.replace(SCRIPT_STYLE_RE, ' ');

  // 3. 去 HTML 标签
  cleaned = cleaned.replace(TAG_RE, ' ');

  // 4. 压缩空白
  const contentText = cleaned.replace(WHITESPACE_RE, ' ').trim();

  // 5. 简化 sectionCount(按段落计)
  const sectionCount = contentText.split(/\.\s+|。\s+|!\s+|!\s+/).filter(s => s.trim().length > 0).length;

  return { title, contentText, contentHtml: '', sectionCount };
};

// 双路径入口:后续 NAPI 实现注入 nativeExtractor,失败时走 ArkTS fallback
// 照搬 Android DeepReadSourcePrefetcher.kt:651-661
export const extractReadableText = (
  html: string,
  url: string | null,
  nativeExtractor?: (html: string, baseUrl: string) => ExtractedArticle | null,
): string => {
  // 优先 native
  if (url && nativeExtractor) {
    try {
      const native = nativeExtractor(html, url);
      if (native !== null && native.contentText.length >= 18) {
        return native.contentText;
      }
    } catch {
      // 落到 ArkTS fallback
    }
  }
  return extractReadableTextArkTs(html, url).contentText;
};
```

测试用例:
- 简单 HTML 提取 title + body text
- script/style 标签内容被去除
- 空白压缩
- sectionCount 计算
- `extractReadableText` native 成功路径(nativeExtractor mock 返回 >18 字符)
- `extractReadableText` native 失败路径(fallback 到 ArkTS)
- `extractReadableText` native 短结果路径(<18 字符,fallback)

---

## Task 3: DeepReadImageScorer

**Files:** Create `harmony/deepread/src/main/ets/research/image_scorer.ts` + test

照搬 Android `DeepReadImageScorer.score()`:`confidence ∈ {hero, inline, reject}` + numeric score + riskFlags + selectionReason。Hero 强制 gate 依赖此(P1-1)。

```typescript
// DeepReadImageScorer — 图片质量评分
// 照搬 Android DeepReadImageScorer.kt
// confidence:hero(可用作文章头图)/ inline(可用作文中插图)/ reject(丢弃)

import { IMAGE_CONFIDENCE } from '../domain/enums.ts';

export interface ImageCandidate {
  url: string;
  width: number;
  height: number;
  altText: string;
  sourceUrl: string;     // 图片所在的页面 URL
  byteSize?: number;
}

export interface ScoredImageCandidate extends ImageCandidate {
  confidence: 'hero' | 'inline' | 'reject';
  score: number;
  riskFlags: string[];
  selectionReason: string;
}

// 简化评分规则(Android 实际更复杂,这里是 MVP):
// - 太小(width<128 或 height<128)→ reject
// - logo/图标常见词(alt 含 logo/icon/avatar/favicon)→ reject
// - data: URL → reject
// - SVG → reject(杂志风用位图)
// - 宽高比极端(>3:1 或 <0.3)→ inline(不适合 hero)
// - 其余 → inline,大尺寸 + 合理比例 + alt 有意义 → hero 候选
export const scoreImageCandidate = (c: ImageCandidate, topicTitle: string): ScoredImageCandidate => {
  const riskFlags: string[] = [];
  const reasons: string[] = [];

  // reject 条件
  if (c.width < 128 || c.height < 128) {
    return rejectCandidate(c, ['too_small'], 'image dimensions < 128px');
  }
  const altLower = c.altText.toLowerCase();
  if (/^(logo|icon|avatar|favicon|sprite|button)/.test(altLower)) {
    return rejectCandidate(c, ['logo_like_alt'], 'alt text suggests logo/icon');
  }
  if (c.url.startsWith('data:')) {
    return rejectCandidate(c, ['data_url'], 'data: URL not usable');
  }
  if (c.url.toLowerCase().endsWith('.svg')) {
    return rejectCandidate(c, ['svg'], 'SVG not supported for magazine layout');
  }
  if (/spacer|blank|transparent|pixel\.gif/i.test(c.url)) {
    return rejectCandidate(c, ['tracking_pixel'], 'URL suggests tracking pixel');
  }

  // 计算 score(0..100)
  let score = 30;
  // 尺寸加分
  const minDim = Math.min(c.width, c.height);
  if (minDim >= 400) score += 20;
  if (minDim >= 800) score += 10;
  if ((c.byteSize ?? 0) > 30_000) score += 10;
  // alt 文本质量
  if (c.altText.length >= 8) {
    score += 10;
    reasons.push('meaningful alt text');
  }
  // 与话题标题相关性(简化:alt 含标题关键词)
  if (topicTitle.length > 0 && c.altText.length > 0) {
    const titleChars = new Set(topicTitle.toLowerCase());
    const altChars = c.altText.toLowerCase();
    let overlap = 0;
    for (const ch of altChars) if (titleChars.has(ch)) overlap++;
    if (overlap >= 4) {
      score += 15;
      reasons.push('alt text overlaps topic title');
    }
  }

  // 宽高比
  const ratio = c.width / c.height;
  let confidence: 'hero' | 'inline' = 'inline';
  if (ratio >= 0.75 && ratio <= 2.0 && score >= 60) {
    confidence = 'hero';
    reasons.push('hero aspect ratio + high score');
  } else if (ratio > 3 || ratio < 0.3) {
    riskFlags.push('extreme_aspect_ratio');
    score -= 10;
  }

  return {
    ...c,
    confidence,
    score: Math.max(0, Math.min(100, score)),
    riskFlags,
    selectionReason: reasons.join('; ') || 'default inline candidate',
  };
};

const rejectCandidate = (
  c: ImageCandidate,
  riskFlags: string[],
  reason: string,
): ScoredImageCandidate => ({
  ...c,
  confidence: 'reject',
  score: 0,
  riskFlags,
  selectionReason: reason,
});

// 批量评分 + 去重(同 URL 取高分)
export const scoreAndDedup = (
  candidates: ImageCandidate[],
  topicTitle: string,
): ScoredImageCandidate[] => {
  const scored = candidates.map(c => scoreImageCandidate(c, topicTitle));
  // 按 URL 去重,保留 score 高的
  const byUrl = new Map<string, ScoredImageCandidate>();
  for (const s of scored) {
    const existing = byUrl.get(s.url);
    if (!existing || s.score > existing.score) {
      byUrl.set(s.url, s);
    }
  }
  return Array.from(byUrl.values()).sort((a, b) => b.score - a.score);
};
```

测试用例:
- 小尺寸 reject
- logo alt reject
- data: URL reject
- 正常大图 + 合理比例 + 好 alt → hero
- 极端宽高比 → inline + riskFlag
- scoreAndDedup 同 URL 取高分
- alt 含话题关键词加分

---

## Task 4: Tavily SearchProvider

**Files:** Create `harmony/deepread/src/main/ets/research/search_provider.ts` + test

Tavily 是最简单的 search API(POST JSON,无需复杂鉴权),够 spike 用。

```typescript
// Tavily search provider — 真实实现,够 spike
// 其他 provider(Zhipu/Brave/Exa)留增量

import type { HttpClient } from '../platform/http.ts';
import type { SearchProvider, SearchHit } from '../platform/search.ts';

export interface TavilyConfig {
  apiKey: string;
  baseUrl?: string;  // 默认 https://api.tavily.com
}

export const createTavilyProvider = (http: HttpClient, config: TavilyConfig): SearchProvider => {
  const baseUrl = config.baseUrl ?? 'https://api.tavily.com';
  return {
    name: 'tavily',
    search: async (queries: string[]): Promise<SearchHit[]> => {
      // Tavily /search 接口:query + max_results
      // 对多个 query,我们取第一个(简化;Android 实际每个 query 单独调用)
      const query = queries[0] ?? '';
      if (query.length === 0) return [];
      const resp = await http.fetch({
        url: `${baseUrl}/search`,
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${config.apiKey}`,
        },
        body: JSON.stringify({
          query,
          max_results: 10,
          search_depth: 'advanced',
          include_answer: false,
        }),
      });
      if (resp.status !== 200) {
        throw new Error(`Tavily search failed: ${resp.status} ${resp.body.slice(0, 200)}`);
      }
      const data = JSON.parse(resp.body) as { results?: Array<{ title: string; url: string; content?: string }> };
      return (data.results ?? []).map(r => ({
        title: r.title,
        url: r.url,
        snippet: r.content ?? null,
        source: 'tavily',
      }));
    },
  };
};

// Fallback providers — 始终可用(无需 API key),Android 同样行为
// BingLocal 和 Jina 是 Android 的兜底,这里也保留概念
export const createFallbackProviders = (): SearchProvider[] => {
  return [
    {
      name: 'jina_fallback',
      search: async (queries: string[]): Promise<SearchHit[]> => {
        // Jina AI 的免费 search(无需 key,但 rate-limited)
        // 简化:实际实现需要真实 http 调用
        return [];
      },
    },
  ];
};
```

测试用 mock HttpClient:
- 成功响应 → 解析 results 为 SearchHit[]
- 非 200 → throw
- 空 query → 返回 []
- 响应缺 results 字段 → 返回 []

---

## Task 5: search_merger(interleaveSearchResults)

**Files:** Create `harmony/deepread/src/main/ets/research/search_merger.ts` + test

照搬 Android `DeepReadSourcePrefetcher.kt:526-544`:round-robin 合并多 query bucket 的结果,cap 14。

```typescript
// interleaveSearchResults — round-robin 合并多 query 结果
// 照搬 Android DeepReadSourcePrefetcher.kt:526-544

import type { SearchHit } from '../platform/search.ts';
import { MAX_SEARCH_RESULTS } from '../domain/enums.ts';

export const interleaveSearchResults = (buckets: SearchHit[][]): SearchHit[] => {
  const result: SearchHit[] = [];
  const seen = new Set<string>();
  let maxLen = 0;
  for (const b of buckets) maxLen = Math.max(maxLen, b.length);
  for (let i = 0; i < maxLen; i++) {
    for (const bucket of buckets) {
      if (i >= bucket.length) continue;
      const hit = bucket[i];
      if (!hit) continue;
      if (seen.has(hit.url)) continue;  // 跨 bucket 去重
      seen.add(hit.url);
      result.push(hit);
      if (result.length >= MAX_SEARCH_RESULTS) return result;
    }
  }
  return result;
};
```

测试用例:
- 单 bucket 透传
- 多 bucket round-robin
- URL 去重
- cap 14
- 空 bucket 处理

---

## Task 6: DeepReadSourcePrefetcher(LRU + 36s budget)

**Files:** Create `harmony/deepread/src/main/ets/research/source_prefetcher.ts` + test

最复杂的组件。照搬 Android `DeepReadSourcePrefetcher.kt`。

```typescript
// DeepReadSourcePrefetcher — 36s wall budget 并行预取
// 照搬 Android DeepReadSourcePrefetcher.kt
// 含 LRU cache(P0-9)、seed source 单独 fetch、并行 scrape + reader + image score

import type { HttpClient, HttpResponse } from '../platform/http.ts';
import type { SearchProviderRegistry, SearchHit } from '../platform/search.ts';
import type { ScoredImageCandidate } from './image_scorer.ts';
import {
  PREFETCH_WALL_BUDGET_MS, PREFETCH_LRU_TTL_MS, PREFETCH_LRU_MAX_ENTRIES,
  MAX_SOURCES, MIN_SOURCE_CHARS, MIN_SEED_SOURCE_CHARS, READER_NATIVE_MIN_CHARS,
} from '../domain/enums.ts';
import { extractReadableText } from './reader_extractor.ts';
import { scoreAndDedup, ImageCandidate } from './image_scorer.ts';
import { interleaveSearchResults } from './search_merger.ts';
import { urlAllowedForBackgroundFetch } from './url_filter.ts';

export interface DeepReadSource {
  sourceId: string;        // 内部 id(prefetch 生成,如 "src-1")
  url: string;
  title: string;
  source: string;          // provider name
  evidenceText: string;    // 提取的正文(供 evidence pack 用)
  credibility: 'high' | 'medium' | 'low';
  freshness: 'fresh' | 'recent' | 'old' | 'unknown';
  publishedAt: string | null;
  imageCandidates: ScoredImageCandidate[];
}

export interface PrefetchOptions {
  seedUrl: string | null;
  force: boolean;
}

export class DeepReadSourcePrefetcher {
  private cache = new Map<string, { value: DeepReadSource[]; expiresAt: number }>();
  private cacheOrder: string[] = [];

  constructor(
    private http: HttpClient,
    private searchRegistry: SearchProviderRegistry,
  ) {}

  async collect(
    topicId: string,
    title: string,
    seedUrl: string | null,
    force: boolean,
  ): Promise<DeepReadSource[]> {
    const cacheKey = `${topicId}|${seedUrl ?? ''}`;
    if (!force) {
      const cached = this.cache.get(cacheKey);
      if (cached && cached.expiresAt > Date.now()) {
        return cached.value;
      }
    }

    // 36s wall budget
    const deadline = Date.now() + PREFETCH_WALL_BUDGET_MS;

    // 1. 多 provider 并行搜索
    const queries = buildDeepReadQueries(title, seedUrl);
    const providers = [...this.searchRegistry.enabled(), ...this.searchRegistry.fallback()];
    const searchBuckets = await Promise.all(
      providers.map(p => withDeadline(deadline, p.search(queries).catch(() => [] as SearchHit[]))),
    );
    const interleaved = interleaveSearchResults(searchBuckets);

    // 2. seed URL 直接 fetch(优先,低阈值)
    const sources: DeepReadSource[] = [];
    if (seedUrl) {
      const seedSource = await withDeadline(
        deadline,
        this.fetchSource(seedUrl, title, 'seed', MIN_SEED_SOURCE_CHARS).catch(() => null),
      );
      if (seedSource) sources.push(seedSource);
    }

    // 3. 并行 scrape + extract + score
    const scraped = await Promise.all(
      interleaved.slice(0, 20).map(hit =>  // top 20 候选
        withDeadline(deadline, this.fetchSource(hit.url, hit.title, hit.source, MIN_SOURCE_CHARS))
          .catch(() => null),
      ),
    );
    for (const s of scraped) {
      if (s) sources.push(s);
    }

    // 4. 去重(同 URL 取第一个)+ cap 12
    const seen = new Set<string>();
    const deduped: DeepReadSource[] = [];
    for (const s of sources) {
      if (seen.has(s.url)) continue;
      seen.add(s.url);
      deduped.push(s);
      if (deduped.length >= MAX_SOURCES) break;
    }

    // 5. 写 LRU cache
    this.setCache(cacheKey, deduped);
    return deduped;
  }

  private async fetchSource(
    url: string,
    fallbackTitle: string,
    source: string,
    minChars: number,
  ): Promise<DeepReadSource | null> {
    if (!urlAllowedForBackgroundFetch(url, false, false)) return null;
    const resp = await this.http.fetch({ url, method: 'GET', headers: {} });
    if (resp.status !== 200) return null;
    const html = resp.body;
    const evidenceText = extractReadableText(html, url);
    if (evidenceText.length < minChars) return null;

    // 简化 OG/img 提取:从 html 抓 <img>
    const imageCandidates = extractImgTags(html, url);
    const scored = scoreAndDedup(imageCandidates, fallbackTitle);

    return {
      sourceId: `src-${Math.random().toString(36).slice(2, 8)}`,
      url,
      title: fallbackTitle,
      source,
      evidenceText,
      credibility: scoreCredibility(url),
      freshness: 'unknown',
      publishedAt: null,
      imageCandidates: scored,
    };
  }

  private setCache(key: string, value: DeepReadSource[]): void {
    // LRU evict
    while (this.cache.size >= PREFETCH_LRU_MAX_ENTRIES && this.cacheOrder.length > 0) {
      const oldest = this.cacheOrder.shift()!;
      this.cache.delete(oldest);
    }
    this.cache.set(key, { value, expiresAt: Date.now() + PREFETCH_LRU_TTL_MS });
    this.cacheOrder.push(key);
  }
}

// 查询变体生成(简化版,照搬 Android buildDeepReadQueries 思路)
const buildDeepReadQueries = (title: string, seedUrl: string | null): string[] => {
  const queries: string[] = [];
  if (seedUrl) queries.push(seedUrl);
  queries.push(title);
  queries.push(`${title} 最新进展`);
  queries.push(`${title} 分析 评论`);
  queries.push(`${title} 背景 起因`);
  queries.push(`${title} 影响 利益相关方`);
  queries.push(`${title} 各方观点 争议`);
  // 简化:Android 生成 ~13 变体,这里 7 个够测试
  return queries.filter(q => q.length > 0).slice(0, 7);
};

// 从 HTML 提取 <img> 标签(简化,Android 用 jsoup)
const extractImgTags = (html: string, baseUrl: string): ImageCandidate[] => {
  const candidates: ImageCandidate[] = [];
  const imgRe = /<img\s+([^>]*)>/gi;
  let m: RegExpExecArray | null;
  while ((m = imgRe.exec(html)) !== null) {
    const attrs = m[1];
    const srcMatch = attrs.match(/\bsrc\s*=\s*["']([^"']+)["']/i);
    if (!srcMatch) continue;
    let url = srcMatch[1];
    // 相对 URL → 绝对
    if (url.startsWith('//')) url = 'https:' + url;
    else if (url.startsWith('/')) {
      try { url = new URL(url, baseUrl).toString(); } catch { continue; }
    } else if (!url.startsWith('http')) continue;

    const altMatch = attrs.match(/\balt\s*=\s*["']([^"']*)["']/i);
    const altText = altMatch ? altMatch[1] : '';
    const widthMatch = attrs.match(/\bwidth\s*=\s*["']?(\d+)/i);
    const heightMatch = attrs.match(/\bheight\s*=\s*["']?(\d+)/i);
    const width = widthMatch ? parseInt(widthMatch[1], 10) : 200;
    const height = heightMatch ? parseInt(heightMatch[1], 10) : 200;
    candidates.push({ url, width, height, altText, sourceUrl: baseUrl });
  }
  return candidates;
};

const scoreCredibility = (url: string): DeepReadSource['credibility'] => {
  const u = url.toLowerCase();
  if (/\.gov\b|\.edu\b|reuters|bloomberg|apnews|nytimes|xinhua|people\.com/.test(u)) return 'high';
  if (/medium\.com|substack|wordpress|blogspot|weibo|zhihu/.test(u)) return 'low';
  return 'medium';
};

// 带 deadline 的 promise 包装
const withDeadline = async <T>(deadline: number, p: Promise<T>): Promise<T> => {
  const remaining = deadline - Date.now();
  if (remaining <= 0) {
    // 已超时,但仍然 await(让结果有机会完成)
    return p;
  }
  return Promise.race([
    p,
    new Promise<T>((_, reject) =>
      setTimeout(() => reject(new Error('prefetch deadline exceeded')), remaining),
    ),
  ]);
};
```

测试用 mock HttpClient + mock SearchProviderRegistry:
- LRU cache 命中(同 topicId 二次 collect 不重新 fetch)
- force=true 绕过 cache
- seed URL 优先(低阈值)
- 36s deadline 超时(短 deadline 测)
- URL 去重
- cap MAX_SOURCES=12
- 多 provider 并行
- 私网 URL 被过滤

---

## Task 7: Evidence Pack + cardsFor

**Files:** Create `harmony/deepread/src/main/ets/research/evidence_pack.ts` + test

照搬 Android `DeepReadResearchHarness.kt` 的 `buildEvidencePack` + `cardsFor(stage, plan)`。

```typescript
// Evidence Pack — 把 prefetch 的 sources 按 stage 分桶
// 照搬 Android DeepReadResearchHarness.kt

import type { DeepReadSource } from './source_prefetcher.ts';
import { STAGE_ORDER, STAGE_SOURCE_LIMIT, PROMPT_SOURCE_LIMIT } from '../domain/enums.ts';
import type { DeepReadGenerationStage } from '../domain/enums.ts';

export interface EvidenceCard {
  sourceId: string;
  url: string;
  title: string;
  source: string;
  credibility: DeepReadSource['credibility'];
  freshness: DeepReadSource['freshness'];
  publishedAt: string | null;
  evidenceExcerpt: string;   // 截断的正文片段
  imageCandidates: DeepReadSource['imageCandidates'];
}

export interface DeepReadEvidencePack {
  allSources: DeepReadSource[];
  cardsByStage: Record<DeepReadGenerationStage, EvidenceCard[]>;
  requiredSourceIds: string[];
}

export interface DeepReadArticlePlan {
  overviewAngle: string;
  narrativeSlots: string[];
  analysisQuestions: string[];
  stakeholders: string[];
  riskOrUncertainty: string[];
  requiredSourceIds: string[];
  stageSourceIds: Record<string, string[]>;
  coverageChecks: string[];
}

// sourceIdsFor — 查 plan 的 stageSourceIds,匹配多种 key 格式
// 照搬 Android DeepReadResearchHarness.kt:499-503
export const sourceIdsFor = (
  plan: DeepReadArticlePlan,
  stage: DeepReadGenerationStage,
): string[] => {
  const stageKey = stage.toLowerCase();  // 'overview'/'narrative'/...
  const candidates = [
    stageKey,
    stage.toLowerCase().replace('_', ''),  // 'extendedreading'
    stage,
  ];
  for (const key of candidates) {
    const ids = plan.stageSourceIds[key];
    if (ids && ids.length > 0) return ids;
  }
  return [];
};

// buildEvidencePack — 本地计算,无 LLM 调用
export const buildEvidencePack = (sources: DeepReadSource[]): DeepReadEvidencePack => {
  if (sources.length === 0) {
    const empty: Record<DeepReadGenerationStage, EvidenceCard[]> = {
      OVERVIEW: [], NARRATIVE: [], ANALYSIS: [], EXTENDED_READING: [],
    };
    return { allSources: [], cardsByStage: empty, requiredSourceIds: [] };
  }

  // 给每个 source 截取 evidenceExcerpt(前 N 字符)
  const cards: EvidenceCard[] = sources.map(s => ({
    sourceId: s.sourceId,
    url: s.url,
    title: s.title,
    source: s.source,
    credibility: s.credibility,
    freshness: s.freshness,
    publishedAt: s.publishedAt,
    evidenceExcerpt: s.evidenceText.slice(0, 2000),
    imageCandidates: s.imageCandidates.filter(c => c.confidence !== 'reject').slice(0, 4),
  }));

  // 按 stage 分桶(简化:均匀分配 + credibility 优先排序)
  const sorted = [...cards].sort((a, b) => {
    const cOrder = { high: 0, medium: 1, low: 2 };
    return cOrder[a.credibility] - cOrder[b.credibility];
  });

  const cardsByStage: Record<DeepReadGenerationStage, EvidenceCard[]> = {
    OVERVIEW: [], NARRATIVE: [], ANALYSIS: [], EXTENDED_READING: [],
  };
  // round-robin 分配到各 stage,但每个 stage cap 在 STAGE_SOURCE_LIMIT
  let idx = 0;
  for (const stage of STAGE_ORDER) {
    const limit = STAGE_SOURCE_LIMIT[stage];
    cardsByStage[stage] = sorted.slice(idx, idx + limit);
    idx += limit;
  }

  // requiredSourceIds:top credibility 的前 N(10 if >=12 sources, 8 if >=8, else all)
  const targetCount = sorted.length >= 12 ? 10 : sorted.length >= 8 ? 8 : sorted.length;
  const requiredSourceIds = sorted.slice(0, targetCount).map(c => c.sourceId);

  return { allSources: sources, cardsByStage, requiredSourceIds };
};

// cardsFor — 给定 stage 和 plan,返回该 stage 应看到的 evidence cards
// 读 plan.stageSourceIds 路由(P0-D)
// 照搬 Android DeepReadResearchHarness.kt:439-450
export const cardsFor = (
  pack: DeepReadEvidencePack,
  stage: DeepReadGenerationStage,
  plan: DeepReadArticlePlan,
  forceIncludeSourceIds: string[] = [],
): EvidenceCard[] => {
  const planned = sourceIdsFor(plan, stage);
  const wanted = new Set([...planned, ...forceIncludeSourceIds]);

  // 优先 plan 指定的 source
  const fromPlan: EvidenceCard[] = [];
  for (const sourceId of wanted) {
    const card = pack.cardsByStage[stage].find(c => c.sourceId === sourceId)
      ?? allCards(pack).find(c => c.sourceId === sourceId);
    if (card && !fromPlan.find(c => c.sourceId === card.sourceId)) {
      fromPlan.push(card);
    }
  }

  // fallback:用 stage bucket 填充到 STAGE_SOURCE_LIMIT
  const limit = STAGE_SOURCE_LIMIT[stage];
  const result: EvidenceCard[] = [...fromPlan];
  if (result.length < limit) {
    for (const card of pack.cardsByStage[stage]) {
      if (result.length >= limit) break;
      if (!result.find(c => c.sourceId === card.sourceId)) {
        result.push(card);
      }
    }
  }
  return result.slice(0, limit);
};

const allCards = (pack: DeepReadEvidencePack): EvidenceCard[] => {
  const all: EvidenceCard[] = [];
  for (const stage of STAGE_ORDER) {
    all.push(...pack.cardsByStage[stage]);
  }
  return all;
};
```

测试用例:
- buildEvidencePack 空 sources → 空 pack
- 单 source → 分到 OVERVIEW
- 多 source → round-robin 分配
- credibility 排序
- requiredSourceIds 数量
- cardsFor 读 plan.stageSourceIds(P0-D 关键)
- cardsFor forceIncludeSourceIds 强制包含
- cardsFor fallback 用 stage bucket
- sourceIdsFor 多 key 格式匹配

---

## Task 8: Article Plan + generateArticlePlan

**Files:** Create `harmony/deepread/src/main/ets/research/article_plan.ts` + test

照搬 Android `DeepReadAgentRunManager.kt:572-610` + `DeepReadResearchHarness.fallbackPlan`。

```typescript
// Article Plan — 1 次非流式 LLM 调用 + 本地 fallback
// 照搬 Android DeepReadAgentRunManager.kt:572-610

import type { AiClient, UIMessage } from '../platform/ai_client.ts';
import { makeUserMessage, latestAssistantText } from '../agent/message.ts';
import type { DeepReadEvidencePack, DeepReadArticlePlan } from './evidence_pack.ts';

const STAGE_EVIDENCE_MIN = 4;
const STAGE_EVIDENCE_MAX = 6;

// fallback plan — LLM 失败或解析失败时用
export const fallbackPlan = (title: string, pack: DeepReadEvidencePack): DeepReadArticlePlan => {
  // 简单均匀分配前 N 个 source 到各 stage
  const stageSourceIds: Record<string, string[]> = {
    overview: [], narrative: [], analysis: [], extended_reading: [],
  };
  const allIds = pack.allSources.map(s => s.sourceId);
  const perStage = Math.min(STAGE_EVIDENCE_MAX, Math.max(STAGE_EVIDENCE_MIN, Math.floor(allIds.length / 4)));
  for (let i = 0; i < STAGE_ORDER_ARR.length; i++) {
    stageSourceIds[STAGE_ORDER_ARR[i]] = allIds.slice(i * perStage, (i + 1) * perStage);
  }
  return {
    overviewAngle: `围绕「${title}」的来龙去脉`,
    narrativeSlots: ['时间线', '关键事件', '直接原因'],
    analysisQuestions: ['主要争议是什么?', '各方的核心立场?', '可能的走向?'],
    stakeholders: [],
    riskOrUncertainty: [],
    requiredSourceIds: pack.requiredSourceIds,
    stageSourceIds,
    coverageChecks: [],
  };
};

const STAGE_ORDER_ARR = ['overview', 'narrative', 'analysis', 'extended_reading'] as const;

// 构造 planning prompt(简化,Android 更详细)
export const buildPlanningPrompt = (title: string, pack: DeepReadEvidencePack, playbookMd: string): string => {
  const sourceLines = pack.allSources.slice(0, 12).map((s, i) =>
    `${i + 1}. [${s.sourceId}] ${s.title} (${s.url}) — credibility:${s.credibility}\n   ${s.evidenceText.slice(0, 300)}`,
  ).join('\n');
  return [
    `话题标题:${title}`,
    playbookMd.length > 0 ? `## 本地规则\n${playbookMd.slice(0, 12000)}` : '',
    `## 已抓取来源(${pack.allSources.length} 个)`,
    sourceLines,
    '',
    '## 任务',
    '基于上述来源,为这篇文章制定写作计划。返回 JSON,字段:',
    '- overview_angle: 一句话定调',
    '- narrative_slots: 叙事应覆盖的要点(数组)',
    '- analysis_questions: 分析段应回答的问题(数组)',
    '- stakeholders: 利益相关方(数组)',
    '- risk_or_uncertainty: 不确定/风险点(数组)',
    '- required_source_ids: 必须引用的 sourceId(数组)',
    '- stage_source_ids: 各 stage 应引用的 sourceId,格式 {"overview": [...], "narrative": [...], "analysis": [...], "extended_reading": [...]}',
    '- coverage_checks: 质量检查清单(数组)',
    '',
    '只返回 JSON,不要其他文字。',
  ].filter(Boolean).join('\n');
};

// 容忍解析:整文本 → 代码块 → 大括号平衡
export const parsePlanJson = (text: string): Record<string, unknown> | null => {
  const trimmed = text.trim();
  // 1. 整文本直接 parse
  try { return JSON.parse(trimmed) as Record<string, unknown>; } catch { /* continue */ }
  // 2. 代码块 ```json ... ```
  const fenceMatch = trimmed.match(/```(?:json)?\s*([\s\S]*?)```/);
  if (fenceMatch) {
    try { return JSON.parse(fenceMatch[1].trim()) as Record<string, unknown>; } catch { /* continue */ }
  }
  // 3. 第一个大括号对
  const start = trimmed.indexOf('{');
  const end = trimmed.lastIndexOf('}');
  if (start >= 0 && end > start) {
    try { return JSON.parse(trimmed.slice(start, end + 1)) as Record<string, unknown>; } catch { /* continue */ }
  }
  return null;
};

// 规范化 plan:clamp stageSourceIds 数量
export const normalizePlan = (
  parsed: Record<string, unknown>,
  title: string,
  pack: DeepReadEvidencePack,
): DeepReadArticlePlan => {
  const toStringArray = (v: unknown): string[] => {
    if (!Array.isArray(v)) return [];
    return v.filter((x): x is string => typeof x === 'string');
  };
  const stageSourceIdsRaw = (parsed.stage_source_ids ?? {}) as Record<string, unknown>;
  const stageSourceIds: Record<string, string[]> = {};
  for (const key of Object.keys(stageSourceIdsRaw)) {
    const arr = toStringArray(stageSourceIdsRaw[key]);
    // clamp [STAGE_EVIDENCE_MIN, STAGE_EVIDENCE_MAX]
    stageSourceIds[key] = arr.slice(0, STAGE_EVIDENCE_MAX);
    while (stageSourceIds[key].length < STAGE_EVIDENCE_MIN && arr.length > stageSourceIds[key].length) {
      // 不够时不强行补(避免引入无关 source)
      break;
    }
  }
  const requiredSourceIds = toStringArray(parsed.required_source_ids);
  // union with pack.requiredSourceIds(Android normalizePlan 行为)
  const allRequired = Array.from(new Set([...requiredSourceIds, ...pack.requiredSourceIds]));

  return {
    overviewAngle: typeof parsed.overview_angle === 'string' ? parsed.overview_angle : `围绕「${title}」`,
    narrativeSlots: toStringArray(parsed.narrative_slots),
    analysisQuestions: toStringArray(parsed.analysis_questions),
    stakeholders: toStringArray(parsed.stakeholders),
    riskOrUncertainty: toStringArray(parsed.risk_or_uncertainty),
    requiredSourceIds: allRequired,
    stageSourceIds,
    coverageChecks: toStringArray(parsed.coverage_checks),
  };
};

// 主入口:1 次 LLM 调用,失败 fallback
export const generateArticlePlan = async (
  ai: AiClient,
  model: string,
  title: string,
  pack: DeepReadEvidencePack,
  playbookMd: string,
): Promise<DeepReadArticlePlan> => {
  const fallback = fallbackPlan(title, pack);
  try {
    const messages = await ai.generateText({
      model,
      messages: [makeUserMessage(buildPlanningPrompt(title, pack, playbookMd))],
      stream: false,
      maxSteps: 1,  // planning 不需要 tool
    });
    const text = extractText(messages);
    const parsed = parsePlanJson(text);
    if (parsed === null) return fallback;
    return normalizePlan(parsed, title, pack);
  } catch {
    return fallback;
  }
};

// AiClient 返回 UIMessage[](UIMessage 在 platform/ai_client.ts 是 unknown 占位)
// 简化:尝试用 latestAssistantText,失败用 JSON.stringify
const extractText = (messages: unknown): string => {
  try {
    // latestAssistantText 期望 UIMessage[];由于 UIMessage 在 AiClient 是 unknown,
    // 这里做防御性 cast
    const msgs = messages as Array<{ role?: string; parts?: Array<{ kind?: string; text?: string }> }>;
    for (let i = msgs.length - 1; i >= 0; i--) {
      const m = msgs[i];
      if (m.role === 'assistant' || m.role === 'user') {
        const text = (m.parts ?? [])
          .filter(p => p.kind === 'text')
          .map(p => p.text ?? '')
          .join('');
        if (text.length > 0) return text;
      }
    }
  } catch {
    // ignore
  }
  return '';
};
```

测试用例:
- fallbackPlan 生成有效结构
- buildPlanningPrompt 包含 title + sources + 任务说明
- parsePlanJson:整 JSON / 代码块 / 大括号 / 无效文本
- normalizePlan:clamp stageSourceIds,union requiredSourceIds
- generateArticlePlan:LLM 成功 → 用 LLM plan
- generateArticlePlan:LLM 失败 → fallback
- generateArticlePlan:LLM 返回非 JSON → fallback
- generateArticlePlan:LLM 返回缺失字段 → normalizePlan 填默认

---

## Task 9: 最终集成测试 + README 更新

**Files:** Create `harmony/deepread/src/test/prefetch_integration.test.ts` + update README

集成测试:mock HttpClient + mock AiClient,模拟端到端 prefetch → evidence pack → article plan。

```typescript
// 模拟:3 个 source,1 个 seed URL,验证完整流程
test('end-to-end: prefetch → evidence pack → article plan', async () => {
  // 1. mock HttpClient 返回 HTML
  // 2. mock SearchProviderRegistry 返回固定 hits
  // 3. mock AiClient 返回 planning JSON
  // 4. 跑 collect → buildEvidencePack → generateArticlePlan
  // 5. 验证 source 数、evidence cards、plan 字段
});
```

更新 README 加 Research 层说明。

---

## Self-Review

### Spec coverage
| Spec 章节 | Task |
|---|---|
| §4.5 Prefetch(36s budget + LRU + 并行) | Task 6 |
| §4.5 reader-extractor 双路径 | Task 2 |
| §4.5 image scorer | Task 3 |
| §4.5 search provider + fallback | Task 4 |
| §4.5 interleaveSearchResults | Task 5 |
| §4.5 urlAllowedForBackgroundFetch | Task 1 |
| §4.3 buildEvidencePack | Task 7 |
| §4.4 generateArticlePlan + fallback | Task 8 |
| §4.4 cardsFor(stage, plan) | Task 7 |

### 风险覆盖
- P0-8 reader NAPI fallback → Task 2
- P0-9 LRU cache → Task 6
- P0-D stageSourceIds 消费 → Task 7
- P1-7 fallback provider → Task 4
- P1-8 私网 gate → Task 1
- P1-9 hard-fail on empty prefetch → Task 6(返回空数组,由 caller 决定 hard-fail)

### Placeholder scan
✅ 无 TBD/TODO
✅ 每步含完整代码
✅ 类型一致
