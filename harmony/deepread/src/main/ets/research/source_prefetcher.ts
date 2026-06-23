// DeepReadSourcePrefetcher — 36s wall budget 并行预取
// 照搬 Android DeepReadSourcePrefetcher.kt(collect / fetchSource / LRU cache)
//
// 含 LRU cache(P0-9)、seed source 单独 fetch(低阈值)、并行 scrape + reader + image score。
// 注意:用 factory function(createSourcePrefetcher)+ 闭包捕获状态,
// 而非 class mutation(ArkTS 友好,与 observable.ts 一致)。

import type { HttpClient } from '../platform/http.ts';
import type { SearchProviderRegistry, SearchHit } from '../platform/search.ts';
import type { ScoredImageCandidate, ImageCandidate } from './image_scorer.ts';
import {
  PREFETCH_WALL_BUDGET_MS, PREFETCH_LRU_TTL_MS, PREFETCH_LRU_MAX_ENTRIES,
  MAX_SOURCES, MIN_SOURCE_CHARS, MIN_SEED_SOURCE_CHARS,
} from '../domain/enums.ts';
import { extractReadableText } from './reader_extractor.ts';
import { scoreAndDedup } from './image_scorer.ts';
import { interleaveSearchResults } from './search_merger.ts';
import { urlAllowedForBackgroundFetch } from './url_filter.ts';

export type SourceCredibility = 'high' | 'medium' | 'low';
export type SourceFreshness = 'fresh' | 'recent' | 'old' | 'unknown';

export interface DeepReadSource {
  sourceId: string;        // 内部 id(prefetch 生成,如 "src-1")
  url: string;
  title: string;
  source: string;          // provider name
  evidenceText: string;    // 提取的正文(供 evidence pack 用)
  credibility: SourceCredibility;
  freshness: SourceFreshness;
  publishedAt: string | null;
  imageCandidates: ScoredImageCandidate[];
}

export interface SourcePrefetcher {
  collect(
    topicId: string,
    title: string,
    seedUrl: string | null,
    force: boolean,
  ): Promise<DeepReadSource[]>;
  /** 测试/诊断用:查看 LRU cache 大小 */
  cacheSize(): number;
}

interface CacheEntry {
  value: DeepReadSource[];
  expiresAt: number;
}

const TOP_SCRAPE_CANDIDATES = 20;

// factory — 闭包捕获 LRU cache + cacheOrder
export const createSourcePrefetcher = (
  http: HttpClient,
  searchRegistry: SearchProviderRegistry,
): SourcePrefetcher => {
  const cache = new Map<string, CacheEntry>();
  const cacheOrder: string[] = [];

  const setCache = (key: string, value: DeepReadSource[]): void => {
    // LRU evict:容量达到上限时淘汰最旧(若 key 已存在先移除旧位置)
    const existingIdx = cacheOrder.indexOf(key);
    if (existingIdx >= 0) cacheOrder.splice(existingIdx, 1);

    while (cache.size >= PREFETCH_LRU_MAX_ENTRIES && cacheOrder.length > 0) {
      const oldest = cacheOrder.shift();
      if (oldest !== undefined) cache.delete(oldest);
    }
    cache.set(key, { value, expiresAt: Date.now() + PREFETCH_LRU_TTL_MS });
    cacheOrder.push(key);
  };

  const fetchSource = async (
    url: string,
    fallbackTitle: string,
    source: string,
    minChars: number,
    fetchOpts: { autoApproveAll: boolean; autoApproveHighRisk: boolean },
  ): Promise<DeepReadSource | null> => {
    // 私网 gate(默认不允许;caller 控制 flags)
    if (!urlAllowedForBackgroundFetch(url, fetchOpts.autoApproveAll, fetchOpts.autoApproveHighRisk)) {
      return null;
    }
    const resp = await http.fetch({ url, method: 'GET', headers: {} });
    if (resp.status !== 200) return null;
    const html = resp.body;
    const evidenceText = extractReadableText(html, url);
    if (evidenceText.length < minChars) return null;

    const imageCandidates = extractImgTags(html, url);
    const scored = scoreAndDedup(imageCandidates, fallbackTitle);

    return {
      sourceId: makeSourceId(),
      url,
      title: fallbackTitle,
      source,
      evidenceText,
      credibility: scoreCredibility(url),
      freshness: 'unknown',
      publishedAt: null,
      imageCandidates: scored,
    };
  };

  const collect = async (
    topicId: string,
    title: string,
    seedUrl: string | null,
    force: boolean,
  ): Promise<DeepReadSource[]> => {
    const cacheKey = `${topicId}|${seedUrl ?? ''}`;
    if (!force) {
      const cached = cache.get(cacheKey);
      if (cached && cached.expiresAt > Date.now()) {
        return cached.value;
      }
    }

    // 36s wall budget
    const deadline = Date.now() + PREFETCH_WALL_BUDGET_MS;

    // 1. 多 provider 并行搜索(失败 provider → 空 bucket)
    const queries = buildDeepReadQueries(title, seedUrl);
    const providers = [...searchRegistry.enabled(), ...searchRegistry.fallback()];
    const searchBuckets = await Promise.all(
      providers.map(p => withDeadline(deadline, p.search(queries).catch(() => [] as SearchHit[]))),
    );
    const interleaved = interleaveSearchResults(searchBuckets);

    // 2. seed URL 直接 fetch(优先,低阈值)— 即使私网也走 gate
    const sources: DeepReadSource[] = [];
    if (seedUrl) {
      const seedSource = await withDeadline(
        deadline,
        fetchSource(seedUrl, title, 'seed', MIN_SEED_SOURCE_CHARS, { autoApproveAll: false, autoApproveHighRisk: false })
          .catch(() => null),
      );
      if (seedSource) sources.push(seedSource);
    }

    // 3. 并行 scrape + extract + score(失败单个 → null,不影响其他)
    const scraped = await Promise.all(
      interleaved.slice(0, TOP_SCRAPE_CANDIDATES).map(hit =>
        withDeadline(
          deadline,
          fetchSource(hit.url, hit.title, hit.source, MIN_SOURCE_CHARS, { autoApproveAll: false, autoApproveHighRisk: false }),
        ).catch(() => null),
      ),
    );
    for (const s of scraped) {
      if (s) sources.push(s);
    }

    // 4. 去重(同 URL 取第一个)+ cap MAX_SOURCES
    const seen = new Set<string>();
    const deduped: DeepReadSource[] = [];
    for (const s of sources) {
      if (seen.has(s.url)) continue;
      seen.add(s.url);
      deduped.push(s);
      if (deduped.length >= MAX_SOURCES) break;
    }

    // 5. 写 LRU cache
    setCache(cacheKey, deduped);
    return deduped;
  };

  return {
    collect,
    cacheSize: () => cache.size,
  };
};

// 查询变体生成(简化版)
// Android buildDeepReadQueries 生成 ~13 变体 + 话题特定加成,这里取核心 7 个够测试。
// 完整变体集留增量(本 plan 测试用不到那么多)。
const buildDeepReadQueries = (title: string, seedUrl: string | null): string[] => {
  const queries: string[] = [];
  if (seedUrl) queries.push(seedUrl);
  queries.push(title);
  queries.push(`${title} 最新进展`);
  queries.push(`${title} 分析 评论`);
  queries.push(`${title} 背景 起因`);
  queries.push(`${title} 影响 利益相关方`);
  queries.push(`${title} 各方观点 争议`);
  return queries.filter(q => q.length > 0).slice(0, 7);
};

// 从 HTML 提取 <img> 标签(简化;Android 用 jsoup + og:image/twitter:image)
// OG/twitter image 提取留增量
const extractImgTags = (html: string, baseUrl: string): ImageCandidate[] => {
  const candidates: ImageCandidate[] = [];
  const imgRe = /<img\s+([^>]*)>/gi;
  let m: RegExpExecArray | null;
  while ((m = imgRe.exec(html)) !== null) {
    const attrs = m[1];
    const srcMatch = attrs.match(/\bsrc\s*=\s*["']([^"']+)["']/i);
    if (!srcMatch) continue;
    let url = srcMatch[1];
    if (url.startsWith('//')) {
      url = 'https:' + url;
    } else if (url.startsWith('/')) {
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

const scoreCredibility = (url: string): SourceCredibility => {
  const u = url.toLowerCase();
  if (/\.gov\b|\.edu\b|reuters|bloomberg|apnews|nytimes|xinhua|people\.com/.test(u)) return 'high';
  if (/medium\.com|substack|wordpress|blogspot|weibo|zhihu/.test(u)) return 'low';
  return 'medium';
};

let sourceIdCounter = 0;
const makeSourceId = (): string => {
  sourceIdCounter += 1;
  return `src-${sourceIdCounter}`;
};

// 带 deadline 的 promise 包装:剩余时间 <= 0 时直接返回原 promise(给已完成结果机会)
const withDeadline = async <T>(deadline: number, p: Promise<T>): Promise<T> => {
  const remaining = deadline - Date.now();
  if (remaining <= 0) return p;
  return Promise.race([
    p,
    new Promise<T>((_, reject) =>
      setTimeout(() => reject(new Error('prefetch deadline exceeded')), remaining),
    ),
  ]);
};

// 重新导出辅助(测试可能用到)
export { buildDeepReadQueries, extractImgTags, scoreCredibility };
