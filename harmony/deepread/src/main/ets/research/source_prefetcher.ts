// DeepReadSourcePrefetcher — 36s wall budget 并行预取
// 照搬 Android DeepReadSourcePrefetcher.kt(collect / fetchSource / LRU cache)
//
// 含 LRU cache(P0-9)、seed source 单独 fetch(低阈值)、并行 scrape + reader + image score。
// 注意:用 factory function(createSourcePrefetcher)+ 闭包捕获状态,
// 而非 class mutation(ArkTS 友好,与 observable.ts 一致)。

import type { HttpClient } from '../platform/http.ts';
import type { AbortSignalLike } from '../platform/runtime_api.ts';
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
    // 取消贯穿(R20):provider search / HTTP fetch 均透传 signal,取消即真实中止;
    // 可选参数,既有调用方不变。
    signal?: AbortSignalLike,
  ): Promise<DeepReadSource[]>;
  /** 测试/诊断用:查看 LRU cache 大小 */
  cacheSize(): number;
}

interface CacheEntry {
  value: DeepReadSource[];
  expiresAt: number;
}

const TOP_SCRAPE_CANDIDATES = 20;

// 直连抓正文带浏览器 UA:不带 UA 的请求常被站点直接 403 或返回精简页
const BROWSER_FETCH_HEADERS: Record<string, string> = {
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
  'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
  'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
};

// 直连失败/正文过短时用 Jina Reader(r.jina.ai,无需 Key)兜底;
// 无 Key 有频率限制(约 20 次/分钟),每次 collect 最多兜底这么多条
const READER_FALLBACK_MAX = 6;
const JINA_READER_PREFIX = 'https://r.jina.ai/';

interface ReaderBudget { remaining: number; }

interface ReaderPage { text: string; publishedAt: string | null; }

// Jina Reader 默认 Markdown 返回:Title/URL Source/Published Time 头 + "Markdown Content:" 正文
// 无 "Markdown Content:" 标记(错误页/限流页)→ null,不把非正文当证据
export const parseJinaReaderBody = (body: string): ReaderPage | null => {
  const marker = body.indexOf('Markdown Content:');
  if (marker < 0) return null;
  const head = body.slice(0, marker);
  const content = body.slice(marker + 'Markdown Content:'.length);
  const published = /^Published Time:\s*(\S+)/m.exec(head);
  const text = content
    .replace(/!\[[^\]]*\]\([^)]*\)/g, '')          // 图片
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')        // 链接留文字
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
  return { text, publishedAt: published !== null ? published[1] : null };
};

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

  const readViaJina = async (
    url: string, fallbackTitle: string, source: string, minChars: number,
    budget: ReaderBudget, signal?: AbortSignalLike,
  ): Promise<DeepReadSource | null> => {
    if (budget.remaining <= 0 || isAborted(signal)) return null;
    budget.remaining -= 1;
    const resp = await http.fetch({ url: JINA_READER_PREFIX + url, method: 'GET', headers: {} }, { signal });
    if (resp.status !== 200) return null;
    const page = parseJinaReaderBody(resp.body);
    if (page === null || page.text.length < minChars) return null;
    return {
      sourceId: makeSourceId(),
      url,
      title: fallbackTitle,
      source,
      evidenceText: page.text,
      credibility: scoreCredibility(url),
      freshness: 'unknown',
      publishedAt: page.publishedAt,
      imageCandidates: [],
    };
  };

  const fetchSource = async (
    url: string,
    fallbackTitle: string,
    source: string,
    minChars: number,
    fetchOpts: { autoApproveAll: boolean; autoApproveHighRisk: boolean },
    budget: ReaderBudget,
    signal?: AbortSignalLike,
  ): Promise<DeepReadSource | null> => {
    // 私网 gate(默认不允许;caller 控制 flags)— Reader 兜底同样受限,不把私网 URL 交给第三方
    if (!urlAllowedForBackgroundFetch(url, fetchOpts.autoApproveAll, fetchOpts.autoApproveHighRisk)) {
      return null;
    }
    let html = '';
    try {
      const resp = await http.fetch({ url, method: 'GET', headers: BROWSER_FETCH_HEADERS }, { signal });
      if (resp.status === 200) html = resp.body;
    } catch (e) {
      if (isAborted(signal)) throw e;
    }
    const evidenceText = html.length > 0 ? extractReadableText(html, url) : '';
    if (evidenceText.length < minChars) {
      return readViaJina(url, fallbackTitle, source, minChars, budget, signal);
    }

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
    signal?: AbortSignalLike,
  ): Promise<DeepReadSource[]> => {
    // 取消已发生 → 不查缓存/不发起任何请求,立即返回空(调用方统一归一 aborted)
    if (isAborted(signal)) return [];
    const cacheKey = `${topicId}|${seedUrl ?? ''}`;
    if (!force) {
      const cached = cache.get(cacheKey);
      if (cached && cached.expiresAt > Date.now()) {
        // 命中重排 LRU(否则频繁访问的旧 key 被提前淘汰)并返回副本
        // (调用方变异会污染缓存)
        const idx = cacheOrder.indexOf(cacheKey);
        if (idx >= 0) cacheOrder.splice(idx, 1);
        cacheOrder.push(cacheKey);
        return cached.value.slice();
      }
    }

    // 36s wall budget
    const deadline = Date.now() + PREFETCH_WALL_BUDGET_MS;
    const readerBudget: ReaderBudget = { remaining: READER_FALLBACK_MAX };

    // 1. 多 provider 并行搜索(失败 provider → 空 bucket;signal 透传真实取消)
    const queries = buildDeepReadQueries(title, seedUrl);
    const providers = [...searchRegistry.enabled(), ...searchRegistry.fallback()];
    // catch 在 withDeadline 外:provider 超时只丢该 bucket,
    // 不让 Promise.all 整体失败丢掉其它 provider 已完成的结果
    const searchBuckets = await Promise.all(
      providers.map(p => withDeadline(deadline, p.search(queries, signal), signal).catch(() => [] as SearchHit[])),
    );
    if (isAborted(signal)) return [];
    const interleaved = interleaveSearchResults(searchBuckets);

    // 2. seed URL 直接 fetch(优先,低阈值)— 即使私网也走 gate
    const sources: DeepReadSource[] = [];
    if (seedUrl) {
      const seedSource = await withDeadline(
        deadline,
        fetchSource(seedUrl, title, 'seed', MIN_SEED_SOURCE_CHARS, { autoApproveAll: false, autoApproveHighRisk: false }, readerBudget, signal),
        signal,
      ).catch(() => null); // catch 在外:seed 超时只丢 seed,不中止整个 collect
      if (seedSource) sources.push(seedSource);
    }
    if (isAborted(signal)) return [];

    // 3. 并行 scrape + extract + score(失败单个 → null,不影响其他)
    const scraped = await Promise.all(
      interleaved.slice(0, TOP_SCRAPE_CANDIDATES).map(hit =>
        withDeadline(
          deadline,
          fetchSource(hit.url, hit.title, hit.source, MIN_SOURCE_CHARS, { autoApproveAll: false, autoApproveHighRisk: false }, readerBudget, signal),
          signal,
        ).catch(() => null),
      ),
    );
    if (isAborted(signal)) return [];
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

    // 5. 写 LRU cache(返回副本:调用方变异不得污染缓存)
    setCache(cacheKey, deduped);
    return deduped.slice();
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
      // 相对路径解析:取 baseUrl 的 origin + url(ArkTS 兼容,不依赖 URL 类)
      const originMatch = /^[^:]+:\/\/[^/]+/.exec(baseUrl);
      if (originMatch !== null) {
        url = originMatch[0] + url;
      } else {
        continue;
      }
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

// 带 deadline 的 promise 包装:到期立即拒绝 — 原实现 return p 会无限等待
// 未完成的请求(调用方 collect 卡死)
// signal 可选:取消时立即拒绝(监听 abort;settle 后移除监听,避免泄漏)
const withDeadline = async <T>(deadline: number, p: Promise<T>, signal?: AbortSignalLike): Promise<T> => {
  if (isAborted(signal)) {
    p.catch((): void => {}); // 消化败者 rejection
    throw makeAbortedError();
  }
  const remaining = deadline - Date.now();
  if (remaining <= 0) {
    p.catch((): void => {}); // 只挂消化 handler,不等待(原请求可能永不完成)
    throw new Error('prefetch deadline exceeded');
  }
  let timer: ReturnType<typeof setTimeout> | null = null;
  const timeoutPromise = new Promise<T>((_, reject): void => {
    timer = setTimeout((): void => { reject(new Error('prefetch deadline exceeded')); }, remaining);
  });
  timeoutPromise.catch((): void => {}); // 败者 rejection 不外溢

  let onAbort: (() => void) | null = null;
  let abortPromise: Promise<T> = new Promise<T>((): void => {}); // 无 signal 时永挂,race 不误触
  if (signal !== undefined) {
    // 捕获局部引用:可选方法在闭包内不再被 TS 收窄
    const addListener = signal.addEventListener;
    if (typeof addListener === 'function') {
      abortPromise = new Promise<T>((_, reject): void => {
        onAbort = (): void => { reject(makeAbortedError()); };
        addListener('abort', onAbort);
      });
      abortPromise.catch((): void => {});
    }
  }
  try {
    return await Promise.race([p, timeoutPromise, abortPromise]);
  } finally {
    if (timer !== null) clearTimeout(timer); // 快请求不留定时器
    if (signal !== undefined && onAbort !== null && typeof signal.removeEventListener === 'function') {
      signal.removeEventListener('abort', onAbort);
    }
  }
};

const isAborted = (signal?: AbortSignalLike): boolean =>
  signal !== undefined && signal.aborted;

const makeAbortedError = (): Error => {
  const e = new Error('aborted');
  e.name = 'AbortError';
  return e;
};

// 重新导出辅助(测试可能用到)
export { buildDeepReadQueries, extractImgTags, scoreCredibility };
