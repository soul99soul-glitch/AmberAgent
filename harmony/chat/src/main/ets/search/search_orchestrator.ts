// search_orchestrator — 编排搜索(变体查询/内置源/状态/解释/webview 兜底)(D-069)
// Android 基准: app/.../core/ai/tools/SearchOrchestrator.kt(全文 848 行)
// 裁剪/偏差登记:同 search_aggregator(Settings 子集/today 注入/Promise.all/手写 URI 子集)
//   OrchestratorSource sealed class → 判别联合(source 字段)
import type { JsonObject, JsonValue } from '../chat/json.ts';
import { newId } from '../chat/ids.ts';
import type { SearchCommonOptions, SearchRequestContext, SearchResult, SearchServiceOptions } from './search_service.ts';
import { SEARCH_SERVICE_TYPES } from './search_service.ts';
import { getSearchService } from './service_registry.ts';
import type { FreeEngineId } from './free_web_engines.ts';
import { looksTechnicalQuery, runFreeEngine } from './free_web_engines.ts';
import {
  SearchSettings, buildServiceParams, canonicalizeUrlWithTracker, enabledServices,
  normalizedTitle,
} from './search_aggregator.ts';
import { stringContentOrNull } from './json_pick.ts';
import { javaUrlEncodeForm } from './url_codec.ts';

// 免费网页引擎扩到 5 个 + 2 个垂直源,上限随之放宽(配置的 API 源按优先级仍排最前)
const MAX_SOURCES = 8;
const MAX_SOURCE_VARIANT_CALLS = 16;
const ALLOWED_TOPICS: string[] = ['general', 'news', 'market', 'technical', 'finance'];
const ALLOWED_TIME_RANGES: string[] = ['day', 'week', 'month', 'year', 'any'];
const ALLOWED_DEPTHS: string[] = ['quick', 'standard', 'deep'];

export interface OrchestratorDeps {
  today?: () => string;
}

// ===== OrchestratorSource(:689-752)→ 判别联合 =====

// Jina 无 Key 搜索(s.jina.ai)现返回 401,已从搜索源移除;Jina 开关只管 scrape_web 的 Reader
export type BuiltinSourceId =
  | 'duckduckgo_builtin' | 'brave_builtin' | 'bing_builtin' | 'so360_builtin' | 'quark_builtin'
  | 'wikipedia_builtin' | 'hackernews_builtin';

const BUILTIN_ENGINE: Record<BuiltinSourceId, FreeEngineId> = {
  duckduckgo_builtin: 'duckduckgo',
  brave_builtin: 'brave',
  bing_builtin: 'bing',
  so360_builtin: 'so360',
  quark_builtin: 'quark',
  wikipedia_builtin: 'wikipedia',
  hackernews_builtin: 'hackernews',
};

export interface OrchestratorSource {
  source: BuiltinSourceId | 'configured';
  id: string;
  name: string;
  kind: string;
  builtin: boolean;
  priority: number;
  reason: string;
  options?: SearchServiceOptions; // source === 'configured'
}

const builtinSource = (id: BuiltinSourceId): OrchestratorSource => {
  switch (id) {
    case 'duckduckgo_builtin':
      return {
        source: id, id, name: 'DuckDuckGo', kind: 'public_html', builtin: true,
        priority: 200, reason: 'Built-in free public recall source',
      };
    case 'brave_builtin':
      return {
        source: id, id, name: 'Brave', kind: 'public_html', builtin: true,
        priority: 201, reason: 'Built-in free public recall source',
      };
    case 'so360_builtin':
      return {
        source: id, id, name: '360 搜索', kind: 'public_html', builtin: true,
        priority: 212, reason: 'Built-in free public recall source reachable from mainland China',
      };
    case 'quark_builtin':
      return {
        source: id, id, name: '夸克', kind: 'public_html', builtin: true,
        priority: 214, reason: 'Built-in free public recall source reachable from mainland China',
      };
    case 'bing_builtin':
      return {
        source: id, id, name: 'Bing HTML 兜底', kind: 'public_html', builtin: true,
        priority: 210, reason: 'Built-in free public recall source',
      };
    case 'wikipedia_builtin':
      return {
        source: id, id, name: 'Wikipedia', kind: 'vertical_knowledge', builtin: true,
        priority: 230, reason: 'Vertical source for entity and background knowledge',
      };
    case 'hackernews_builtin':
      return {
        source: id, id, name: 'Hacker News', kind: 'vertical_technical', builtin: true,
        priority: 220, reason: 'Vertical source for technical and open-source discussions',
      };
  }
};

const configuredSource = (options: SearchServiceOptions, priority: number): OrchestratorSource => ({
  source: 'configured',
  id: options.id,
  name: SEARCH_SERVICE_TYPES[options.type] ?? 'Search',
  kind: 'configured',
  builtin: false,
  priority,
  reason: 'Enabled configured search service',
  options,
});

// :754-770
const applicableFor = (source: OrchestratorSource, query: string | null, topic: string): boolean => {
  if (query === null) return true;
  if (source.source !== 'wikipedia_builtin' && source.source !== 'hackernews_builtin') return true;
  const lower: string = query.toLowerCase();
  // Wikipedia API 稳定且无 Key:普通/技术话题都作为背景知识源(新闻/行情类时效性不匹配)
  if (source.source === 'wikipedia_builtin') {
    return topic === 'general' || topic === 'technical' ||
      ['百科', 'wiki', 'wikipedia'].some((k: string) => lower.includes(k));
  }
  return topic === 'technical' || looksTechnicalQuery(query);
};

// :772-793
const apiKeyConfigured = (source: OrchestratorSource): boolean | null => {
  if (source.source !== 'configured' || source.options === undefined) return null;
  const o: SearchServiceOptions = source.options;
  if (o.type === 'bing_local' || o.type === 'searxng') return null;
  if ('apiKey' in o) return o.apiKey.trim().length > 0;
  return null;
};

// ===== 参数取值(:639-658) =====

const jsonIntOrNull = (j: JsonObject, key: string): number | null => {
  const v: JsonValue | undefined = j[key];
  if (typeof v === 'number') return Number.isInteger(v) ? v : null;
  if (typeof v === 'string' && /^[+-]?\d+$/.test(v)) return parseInt(v, 10);
  return null;
};

// toBooleanStrictOrNull:仅小写 'true'/'false'(content 语义:布尔原始类型也算)
const jsonBoolStrictOrNull = (j: JsonObject, key: string): boolean | null => {
  const v: JsonValue | undefined = j[key];
  if (typeof v === 'boolean') return v;
  if (typeof v === 'string') {
    if (v === 'true') return true;
    if (v === 'false') return false;
  }
  return null;
};

// :651-658 preferred_sources ?: services;trim + 滤空
const serviceSelectors = (params: JsonObject): string[] => {
  const raw: JsonValue | undefined =
    params['preferred_sources'] !== undefined ? params['preferred_sources'] : params['services'];
  if (raw === undefined || raw === null) return [];
  let list: string[] | null = null;
  if (Array.isArray(raw)) {
    list = [];
    for (const e of raw) {
      if (typeof e === 'string') list.push(e);
    }
  } else if (typeof raw === 'string') {
    list = raw.split(',');
  }
  return (list ?? []).map((s: string) => s.trim()).filter((s: string) => s.length > 0);
};

// ===== buildSources(:238-283) =====

export const buildSources = (
  settings: SearchSettings,
  requestedServices: string[] = [],
  query: string | null = null,
  topic: string = 'general',
): OrchestratorSource[] => {
  const requested: string[] = requestedServices
    .map((s: string) => s.toLowerCase().trim())
    .filter((s: string) => s.length > 0);
  const allowed = (id: string, name: string): boolean => {
    if (requested.length === 0) return true;
    const lowerName: string = name.toLowerCase();
    return requested.some((selector: string) =>
      id.toLowerCase().startsWith(selector) ||
      lowerName === selector ||
      lowerName.includes(selector));
  };

  const configured: OrchestratorSource[] = enabledServices(settings, requestedServices)
    .map((options: SearchServiceOptions, index: number) => configuredSource(options, index));
  const hasConfiguredBing: boolean = configured.some(
    (s: OrchestratorSource) => s.options !== undefined && s.options.type === 'bing_local',
  );
  const builtins: OrchestratorSource[] = [];
  // searchBuiltinDuckDuckGoEnabled 即"免费网页聚合"总开关:DDG/Brave/360/夸克并发,
  // 单个被拦截就跳过(不新增 Settings 字段,对齐 iOS)
  if (settings.searchBuiltinDuckDuckGoEnabled) {
    if (allowed('duckduckgo_builtin', 'DuckDuckGo')) builtins.push(builtinSource('duckduckgo_builtin'));
    if (allowed('brave_builtin', 'Brave')) builtins.push(builtinSource('brave_builtin'));
    if (allowed('so360_builtin', '360 搜索')) builtins.push(builtinSource('so360_builtin'));
    if (allowed('quark_builtin', '夸克')) builtins.push(builtinSource('quark_builtin'));
  }
  if (settings.searchBuiltinBingEnabled && !hasConfiguredBing && allowed('bing_builtin', 'Bing')) {
    builtins.push(builtinSource('bing_builtin'));
  }
  if (settings.searchBuiltinWikipediaEnabled && allowed('wikipedia_builtin', 'Wikipedia')) {
    builtins.push(builtinSource('wikipedia_builtin'));
  }
  if (settings.searchBuiltinHackerNewsEnabled && allowed('hackernews_builtin', 'Hacker News')) {
    builtins.push(builtinSource('hackernews_builtin'));
  }
  return configured.concat(builtins)
    .filter((source: OrchestratorSource) => applicableFor(source, query, topic))
    .sort((a: OrchestratorSource, b: OrchestratorSource) => a.priority - b.priority);
};

// ===== 变体/调用构建 =====

export interface SourceSearchRequest {
  source: OrchestratorSource;
  sourceIndex: number;
  variantIndex: number;
  query: string;
  originalQuery: string;
  topic: string;
  timeRange: string;
  recencyDays: number | null;
  maxResults: number;
}

interface SourceOutcome {
  ok: boolean;
  result?: SearchResult;
  error?: Error;
}

export interface SourceSearchResult {
  request: SourceSearchRequest;
  outcome: SourceOutcome;
}

export type OrchestratorSearchExecutor = (
  request: SourceSearchRequest,
  commonOptions: SearchCommonOptions,
  context?: SearchRequestContext,
) => Promise<SearchResult>;

// :285-328
const buildSourceCalls = (
  sources: OrchestratorSource[],
  variants: string[],
  originalQuery: string,
  topic: string,
  timeRange: string,
  recencyDays: number | null,
  maxResults: number,
): SourceSearchRequest[] => {
  if (sources.length === 0 || variants.length === 0) return [];
  const calls: SourceSearchRequest[] = [];
  sources.forEach((source: OrchestratorSource, sourceIndex: number) => {
    calls.push({
      source, sourceIndex, variantIndex: 0, query: variants[0],
      originalQuery, topic, timeRange, recencyDays, maxResults,
    });
  });
  let variantIndex: number = 1;
  while (calls.length < MAX_SOURCE_VARIANT_CALLS && variantIndex < variants.length) {
    for (let sourceIndex = 0; sourceIndex < sources.length; sourceIndex++) {
      if (calls.length >= MAX_SOURCE_VARIANT_CALLS) break;
      calls.push({
        source: sources[sourceIndex], sourceIndex, variantIndex,
        query: variants[variantIndex],
        originalQuery, topic, timeRange, recencyDays, maxResults,
      });
    }
    variantIndex++;
  }
  return calls;
};

// :330-368
export const buildQueryVariants = (
  query: string,
  topic: string,
  timeRange: string,
  recencyDays: number | null,
  depth: string,
  deps: OrchestratorDeps = {},
): string[] => {
  const today: string = (deps.today ?? ((): string => new Date().toISOString().substring(0, 10)))();
  // 规范化一次,所有 variant 基于同一形态(原 query 带首尾空白时后续变体会残留)
  const q: string = query.trim();
  const query2 = q;
  const variants: string[] = [q]; // linkedSetOf → 数组+includes
  let timeText: string | null = null;
  if (recencyDays !== null) timeText = `last ${recencyDays} days`;
  else if (timeRange === 'day') timeText = `today ${today}`;
  else if (timeRange === 'week') timeText = `this week ${today}`;
  else if (timeRange === 'month') timeText = `this month ${today}`;
  else if (timeRange === 'year') timeText = `this year ${today}`;
  const push = (v: string): void => {
    if (!variants.includes(v)) variants.push(v);
  };
  if (topic === 'news' || timeRange !== 'any') {
    const parts: string[] = [query2, 'news'];
    if (timeText !== null) parts.push(timeText);
    push(parts.join(' '));
  }
  if (topic === 'market' || looksLikeMarketQuery(query)) {
    push(`${query2} market share shipment sales Counterpoint Canalys IDC`);
  }
  if (topic === 'technical') {
    push(`${query2} documentation GitHub issue release`);
  }
  if (topic === 'finance') {
    push(`${query2} finance stock market latest`);
  }
  if (depth === 'deep') {
    push(`${query2} site:reuters.com OR site:apnews.com OR site:bloomberg.com OR site:canalys.com OR site:idc.com`);
  }
  const limit: number = depth === 'quick' ? 1 : depth === 'deep' ? 4 : 3;
  return variants.filter((v: string) => v.trim().length > 0).slice(0, limit);
};

// :370-375
const looksLikeMarketQuery = (query: string): boolean => {
  const lower: string = query.toLowerCase();
  return ['销量', '出货', '市场份额', '份额', 'sales', 'shipment', 'market share']
    .some((k: string) => lower.includes(k));
};

// ===== 合并(:442-559) =====

interface OrchestratedSearchItem {
  title: string;
  url: string;
  text: string;
  domain: string;
  sourceService: string;
  sourceServices: string[];
  sourceRank: number;
  duplicateCount: number;
  score: number;
  publishedAt: string | null;
  freshnessScore: number;
  images: string[];
}

const distinctKeepOrder = (list: string[]): string[] => {
  const out: string[] = [];
  for (const s of list) {
    if (!out.includes(s)) out.push(s);
  }
  return out;
};

// :519-542(orchestrator 版:黑名单多 yclid)
export const orchestratorCanonicalizeUrl = (url: string): string =>
  canonicalizeUrlWithTracker(url, ['fbclid', 'gclid', 'igshid', 'mc_cid', 'mc_eid', 'yclid']);

// 域键含端口(IPv6 保留方括号;丢端口会把不同服务经标题别名误合并)
const domainOf = (url: string): string => {
  try {
    const m: RegExpExecArray | null = /^[a-zA-Z][a-zA-Z0-9+.-]*:\/\/([^/?#]*)/.exec(url.trim());
    if (m === null) return '';
    let authority: string = m[1];
    const at: number = authority.lastIndexOf('@');
    if (at >= 0) authority = authority.substring(at + 1);
    if (authority.startsWith('[')) {
      const close: number = authority.indexOf(']');
      const host: string = close >= 0 ? authority.substring(0, close + 1) : authority;
      return host.toLowerCase();
    }
    const colon: number = authority.indexOf(':');
    const host: string = colon >= 0 && /^\d+$/.test(authority.substring(colon + 1))
      ? authority.substring(0, colon) + authority.substring(colon) // host:port
      : authority;
    return host.toLowerCase().replace(/^www\./, '');
  } catch {
    return '';
  }
};

// :550-553
const freshnessScore = (publishedAt: string | null, topic: string, timeRange: string): number => {
  if (topic !== 'news' || timeRange === 'any') return 0;
  return (publishedAt === null || publishedAt.trim().length === 0) ? 0 : 12;
};

const containsIgnoreCase = (haystack: string, needle: string): boolean =>
  haystack.toLowerCase().includes(needle.toLowerCase());

const mergeResults = (
  results: SourceSearchResult[],
  originalQuery: string,
  topic: string,
  timeRange: string,
): OrchestratedSearchItem[] => {
  const byKey = new Map<string, OrchestratedSearchItem>();
  const byTitle = new Map<string, OrchestratedSearchItem>();
  const queryTokens: string[] = originalQuery.toLowerCase()
    .split(/[\s/,_，。:：-]+/)
    .filter((t: string) => t.length >= 2)
    .slice(0, 10);

  for (const sourceResult of results) {
    if (!sourceResult.outcome.ok || sourceResult.outcome.result === undefined) continue;
    sourceResult.outcome.result.items.forEach((item, rank: number) => {
      // 空 URL/空标题条目不入合并(空 canonical 会让所有无 URL 项共享一个键)
      if (item.url.trim().length === 0 || item.title.trim().length === 0) return;
      const canonical: string = orchestratorCanonicalizeUrl(item.url);
      const domain: string = domainOf(item.url);
      const normalized: string = normalizedTitle(item.title);
      // 保守标题键:域非空 + 归一化标题足够长,防不同文章同名误合并
      const titleKeyEligible: boolean = domain.length > 0 && normalized.length >= 3;
      const titleKey: string = titleKeyEligible ? `${domain}|${normalized}` : '';
      const existing: OrchestratedSearchItem | undefined = canonical.length > 0
        ? byKey.get(canonical)
        : undefined;
      const byTitleHit: OrchestratedSearchItem | undefined = titleKeyEligible
        ? byTitle.get(titleKey) : undefined;
      const mergedInto: OrchestratedSearchItem | undefined = existing ?? byTitleHit;
      if (mergedInto === undefined) {
        const hitBoost: number = queryTokens.filter((token: string) =>
          containsIgnoreCase(item.title, token) || containsIgnoreCase(item.text, token)
        ).length * 3;
        const fresh: number = freshnessScore(item.publishedAt, topic, timeRange);
        const score: number =
          (60 - sourceResult.request.sourceIndex * 5 - sourceResult.request.variantIndex * 3 - rank)
          + hitBoost + fresh;
        const created: OrchestratedSearchItem = {
          title: item.title,
          url: item.url,
          text: item.text,
          domain,
          sourceService: sourceResult.request.source.name,
          sourceServices: [sourceResult.request.source.name],
          sourceRank: rank + 1,
          duplicateCount: 1,
          score,
          publishedAt: item.publishedAt,
          freshnessScore: fresh,
          images: distinctKeepOrder(item.images).slice(0, 5),
        };
        if (canonical.length > 0) byKey.set(canonical, created);
        if (titleKeyEligible) byTitle.set(titleKey, created);
      } else {
        // 合并命中(含标题别名路径)后回写 canonical 索引:后续同 URL
        // 不同标题的条目不得再新建重复项
        if (canonical.length > 0 && byKey.get(canonical) === undefined) {
          byKey.set(canonical, mergedInto);
        }
        if (!mergedInto.sourceServices.includes(sourceResult.request.source.name)) {
          mergedInto.sourceServices.push(sourceResult.request.source.name);
        }
        mergedInto.duplicateCount += 1;
        mergedInto.score += 12;
        if (item.text.length > mergedInto.text.length) mergedInto.text = item.text;
        if (mergedInto.publishedAt === null && item.publishedAt !== null) {
          mergedInto.publishedAt = item.publishedAt;
          mergedInto.freshnessScore = freshnessScore(mergedInto.publishedAt, topic, timeRange);
        }
        if (item.images.length > 0 && mergedInto.images.length < 5) {
          mergedInto.images = distinctKeepOrder(mergedInto.images.concat(item.images)).slice(0, 5);
        }
      }
    });
  }
  // byKey 中混有"标题合并回写的既有项"(与 byTitle 同引用)——用去重集合收集
  const seen = new Set<OrchestratedSearchItem>();
  const list: OrchestratedSearchItem[] = [];
  for (const item of Array.from(byKey.values())) {
    if (seen.has(item)) continue;
    seen.add(item);
    list.push(item);
  }
  list.sort((a: OrchestratedSearchItem, b: OrchestratedSearchItem): number =>
    (b.score - a.score) || (b.duplicateCount - a.duplicateCount) ||
    (b.freshnessScore - a.freshnessScore) || (a.sourceRank - b.sourceRank));
  return list;
};

// :812-841 item toJson
const orchestratedItemToJson = (item: OrchestratedSearchItem, index: number, maxImages: number): JsonObject => {
  const out: JsonObject = {
    id: newId().substring(0, 6),
    index,
    title: item.title,
    url: item.url,
    domain: item.domain,
    text: item.text.substring(0, 2000),
    source_service: item.sourceService,
    source_services: item.sourceServices,
    source_rank: item.sourceRank,
    duplicate_count: item.duplicateCount,
    rank_score: item.score,
    freshness_score: item.freshnessScore,
    verified_by_scrape: false,
  };
  if (item.publishedAt === null) {
    out['published_at'] = null;
    out['published_at_unknown'] = true;
  } else {
    out['published_at'] = item.publishedAt;
    out['published_at_unknown'] = false;
  }
  const cap: number = Math.min(Math.max(maxImages, 0), 5);
  const cappedImages: string[] = distinctKeepOrder(item.images).slice(0, cap);
  if (cappedImages.length > 0) out['images'] = cappedImages;
  return out;
};

// ===== webview 兜底(:561-585) =====

const webViewFallbackItem = (source: string, engine: string, query: string): JsonObject => {
  const encoded: string = javaUrlEncodeForm(query);
  let url: string;
  if (engine === 'duckduckgo') url = `https://duckduckgo.com/?q=${encoded}`;
  else if (engine === 'bing') url = `https://www.bing.com/search?q=${encoded}`;
  else url = `https://www.google.com/search?q=${encoded}`;
  return { source_service: source, engine, url };
};

const webViewFallback = (query: string): JsonObject => ({
  status: 'available',
  note: 'Use webview_search_open to open a visible search results page when ordinary sources are weak or blocked.',
  suggestions: [
    webViewFallbackItem('google_webview', 'google', query),
    webViewFallbackItem('duckduckgo_webview', 'duckduckgo', query),
    webViewFallbackItem('bing_webview', 'bing', query),
  ],
});

// :587-593
const sanitizeError = (throwable: Error): string => {
  const raw: string = throwable.message; // Kotlin message ?:(JS Error.message 恒为串)
  return raw
    .replace(/\b[a-zA-Z0-9_.]+Exception\b/g, 'error')
    .replace(/\b[a-zA-Z0-9_.]+Error\b/g, 'error')
    .substring(0, 500);
};

// :595-637
const sourceStatusJson = (
  sourceResults: SourceSearchResult[],
  sources: OrchestratorSource[],
  calls: SourceSearchRequest[],
): JsonValue[] =>
  sources.map((source: OrchestratorSource): JsonObject => {
    const attemptedCalls: SourceSearchRequest[] = calls.filter((c) => c.source.id === source.id);
    const attemptedResults: SourceSearchResult[] =
      sourceResults.filter((r) => r.request.source.id === source.id);
    const successCount: number = attemptedResults.filter((r) => r.outcome.ok).length;
    const failureCount: number = attemptedResults.filter((r) => !r.outcome.ok).length;
    let resultCount: number = 0;
    for (const r of attemptedResults) resultCount += r.outcome.result?.items.length ?? 0;
    const out: JsonObject = {
      service: source.name,
      service_id: source.id,
      source_kind: source.kind,
      builtin: source.builtin,
      called: attemptedCalls.length > 0,
      variant_count: attemptedCalls.length,
      result_count: resultCount,
      status: attemptedCalls.length === 0 ? 'skipped'
        : successCount > 0 && failureCount > 0 ? 'partial'
        : successCount > 0 ? 'ok' : 'error',
    };
    if (attemptedCalls.length === 0) {
      out['skipped_reason'] = 'not selected, not applicable, or call budget exhausted';
    }
    const firstFailure: SourceSearchResult | undefined =
      attemptedResults.find((r) => !r.outcome.ok);
    if (firstFailure !== undefined && firstFailure.outcome.error !== undefined) {
      out['error'] = sanitizeError(firstFailure.outcome.error);
    }
    const queries: string[] = [];
    for (const c of attemptedCalls) {
      if (!queries.includes(c.query)) queries.push(c.query);
    }
    out['queries'] = queries;
    return out;
  });

// ===== 默认执行器(:377-409 + :411-432 serviceParams) =====

const providerTopic = (topic: string): string => {
  if (topic === 'news') return 'news';
  if (topic === 'finance') return 'finance';
  return 'general';
};

const requestServiceParams = (request: SourceSearchRequest, deps: OrchestratorDeps): JsonObject => {
  if (request.source.source === 'configured' && request.source.options !== undefined) {
    return buildServiceParams(
      request.query, providerTopic(request.topic), request.timeRange,
      request.recencyDays, request.source.options, deps,
    );
  }
  return { query: request.query };
};

const defaultExecutor = (deps: OrchestratorDeps): OrchestratorSearchExecutor =>
  async (request: SourceSearchRequest, commonOptions: SearchCommonOptions,
    context?: SearchRequestContext): Promise<SearchResult> => {
    if (request.source.source === 'configured') {
      const options: SearchServiceOptions = request.source.options as SearchServiceOptions;
      return getSearchService(options).search(
        requestServiceParams(request, deps), commonOptions, options, context,
      );
    }
    // 内置源统一走带验证码熔断的免费引擎分派
    return runFreeEngine(BUILTIN_ENGINE[request.source.source], request.query, commonOptions, context);
  };

export const FREE_TIER_SETUP_HINT: string =
  'Only free public sources are enabled and they returned few results (public engines may rate-limit or show verification pages). ' +
  'If the user needs more reliable search, suggest configuring a search API with a free tier in Search Service settings: ' +
  'Tavily (1,000 free credits/month), Serper (2,500 free queries on sign-up), Exa (free credits), ' +
  'or Zhipu / Bocha (low cost, reachable from mainland China).';

// ===== 主入口(:43-171) =====

export const searchOrchestratorSearch = async (
  settings: SearchSettings,
  params: JsonObject,
  executor?: OrchestratorSearchExecutor,
  deps: OrchestratorDeps = {},
  context: SearchRequestContext = {},
): Promise<JsonObject> => {
  const throwIfCancelled = (): void => {
    if (!context.signal?.aborted) return;
    const error = new Error('search aborted');
    error.name = 'AbortError';
    throw error;
  };
  throwIfCancelled();
  const exec: OrchestratorSearchExecutor = executor ?? defaultExecutor(deps);
  const queryMaybe: string | null = stringContentOrNull(params, 'query') ?? stringContentOrNull(params, 'q');
  if (queryMaybe === null) throw new Error('query is required');
  const query: string = queryMaybe;
  const topicRaw: string | null = stringContentOrNull(params, 'topic');
  const topicLower: string | null = topicRaw !== null ? topicRaw.toLowerCase() : null;
  const topic: string =
    topicLower !== null && ALLOWED_TOPICS.includes(topicLower) ? topicLower : 'general';
  const depthRaw: string | null = stringContentOrNull(params, 'depth');
  const depthLower: string | null = depthRaw !== null ? depthRaw.toLowerCase() : null;
  const depth: string =
    depthLower !== null && ALLOWED_DEPTHS.includes(depthLower) ? depthLower : 'standard';
  const trRaw: string | null = stringContentOrNull(params, 'time_range');
  const trLower: string | null = trRaw !== null ? trRaw.toLowerCase() : null;
  const explicitTimeRange: string | null =
    trLower !== null && ALLOWED_TIME_RANGES.includes(trLower) ? trLower : null;
  const timeRange: string = explicitTimeRange ?? (topic === 'news' ? 'week' : 'any');
  const recencyRaw: number | null = jsonIntOrNull(params, 'recency_days');
  const recencyDays: number | null =
    recencyRaw !== null ? Math.min(Math.max(recencyRaw, 1), 366) : null;
  const maxRaw: number | null = jsonIntOrNull(params, 'max_results');
  const maxResults: number = maxRaw !== null
    ? Math.min(Math.max(maxRaw, 1), 40)
    : Math.min(Math.max(settings.searchCommonOptions.resultSize, 1), 40);
  const allowWebView: boolean = jsonBoolStrictOrNull(params, 'allow_webview') ?? false;
  const requestedServices: string[] = serviceSelectors(params);
  const sources: OrchestratorSource[] =
    buildSources(settings, requestedServices, query, topic).slice(0, MAX_SOURCES);

  if (sources.length === 0) {
    return {
      status: 'error',
      query,
      error: 'No search sources are enabled. Enable built-in free sources or at least one configured search service.',
      items: [],
      sources: [],
    };
  }

  const variants: string[] = buildQueryVariants(query, topic, timeRange, recencyDays, depth, deps);
  const perCallSize: number = Math.min(Math.max(
    Math.max(4, Math.floor((maxResults + sources.length - 1) / sources.length) + 3), 4), 20);
  const calls: SourceSearchRequest[] =
    buildSourceCalls(sources, variants, query, topic, timeRange, recencyDays, perCallSize);

  const sourceResults: SourceSearchResult[] = await Promise.all(
    calls.map(async (request: SourceSearchRequest): Promise<SourceSearchResult> => {
      let outcome: SourceOutcome;
      try {
        throwIfCancelled();
        const result: SearchResult = await exec(request, { resultSize: request.maxResults }, context);
        throwIfCancelled();
        outcome = { ok: true, result };
      } catch (e) {
        throwIfCancelled();
        if (e instanceof Error && e.name === 'AbortError') throw e;
        outcome = { ok: false, error: e instanceof Error ? e : new Error(String(e)) };
      }
      return { request, outcome };
    }),
  );

  const merged: OrchestratedSearchItem[] =
    mergeResults(sourceResults, query, topic, timeRange).slice(0, maxResults);
  const anyFailure: boolean = sourceResults.some((r: SourceSearchResult) => !r.outcome.ok);
  const shouldOfferWebView: boolean = settings.searchGoogleWebViewFallbackEnabled &&
    (allowWebView || depth === 'deep' || merged.length < Math.min(maxResults, 3));

  const out: JsonObject = {
    status: merged.length > 0 && anyFailure ? 'partial'
      : merged.length > 0 ? 'ok'
      : 'empty',
    query,
    topic,
    time_range: timeRange,
    depth,
  };
  if (recencyDays !== null) out['recency_days'] = recencyDays;
  // 图片预算(toEmit 先算后减,:124-138 注释所述修正版)
  let imagesBudget: number = 5;
  const itemsJson: JsonValue[] = [];
  merged.forEach((item: OrchestratedSearchItem, index: number) => {
    const available: number = distinctKeepOrder(item.images).length;
    const toEmit: number = Math.max(Math.min(available, imagesBudget), 0);
    itemsJson.push(orchestratedItemToJson(item, index + 1, toEmit));
    imagesBudget = Math.max(imagesBudget - toEmit, 0);
  });
  out['items'] = itemsJson;
  const allImages: string[] = distinctKeepOrder(
    merged.flatMap((item: OrchestratedSearchItem) => item.images),
  ).slice(0, 5);
  out['total_images'] = allImages.length;
  if (allImages.length > 0) {
    out['image_instruction'] =
      `搜索结果包含 ${allImages.length} 张相关图片。图片由 AmberAgent 客户端单独处理；` +
      '请不要在回复正文中使用 ![](url) Markdown 图片语法，也不要输出任何图片渲染代码块。' +
      '只需要写好文字内容，并优先给用到的来源附上 [站点名](来源URL) 链接。';
  }
  out['sources'] = sourceStatusJson(sourceResults, sources, calls);
  out['query_variants'] = variants;
  if (shouldOfferWebView) {
    out['webview_fallback'] = webViewFallback(query);
  }
  // 只靠免费源且结果偏少:提示用户配置有免费额度的 API(模型可转述给用户)
  if (merged.length < Math.min(maxResults, 5) && sources.every((s: OrchestratorSource) => s.builtin)) {
    out['setup_hint'] = FREE_TIER_SETUP_HINT;
  }
  if (merged.length === 0) {
    out['message'] = shouldOfferWebView
      ? 'No ordinary source produced results. Use webview_search_open with the suggested fallback URL, or enable more search services.'
      : 'No source produced parseable results. Enable Google WebView fallback or another search service.';
  }
  return out;
};

// ===== status(:173-199) =====

export const searchOrchestratorStatus = (settings: SearchSettings): JsonObject => {
  const enabledConfigured: SearchServiceOptions[] = enabledServices(settings);
  const sourcesJson: JsonValue[] = buildSources(settings).map((source: OrchestratorSource): JsonObject => {
    const out: JsonObject = {
      id: source.id,
      name: source.name,
      kind: source.kind,
      builtin: source.builtin,
      priority: source.priority,
    };
    out['api_key_configured'] = apiKeyConfigured(source);
    return out;
  });
  return {
    enabled: settings.enableWebSearch,
    configured_service_count: settings.searchServices.length,
    enabled_configured_service_count: enabledConfigured.length,
    builtin_duckduckgo_enabled: settings.searchBuiltinDuckDuckGoEnabled,
    builtin_bing_enabled: settings.searchBuiltinBingEnabled,
    google_webview_fallback_enabled: settings.searchGoogleWebViewFallbackEnabled,
    sources: sourcesJson,
  };
};

// ===== explain(:201-236) =====

export const searchOrchestratorExplain = (settings: SearchSettings, params: JsonObject, deps: OrchestratorDeps = {}): JsonObject => {
  const query: string =
    stringContentOrNull(params, 'query') ?? stringContentOrNull(params, 'q') ?? '';
  const topicRaw: string | null = stringContentOrNull(params, 'topic');
  const topicLower: string | null = topicRaw !== null ? topicRaw.toLowerCase() : null;
  const topic: string =
    topicLower !== null && ALLOWED_TOPICS.includes(topicLower) ? topicLower : 'general';
  const depthRaw: string | null = stringContentOrNull(params, 'depth');
  const depthLower: string | null = depthRaw !== null ? depthRaw.toLowerCase() : null;
  const depth: string =
    depthLower !== null && ALLOWED_DEPTHS.includes(depthLower) ? depthLower : 'standard';
  const trRaw: string | null = stringContentOrNull(params, 'time_range');
  const trLower: string | null = trRaw !== null ? trRaw.toLowerCase() : null;
  const explicitTimeRange: string | null =
    trLower !== null && ALLOWED_TIME_RANGES.includes(trLower) ? trLower : null;
  const timeRange: string = explicitTimeRange ?? (topic === 'news' ? 'week' : 'any');
  const recencyRaw: number | null = jsonIntOrNull(params, 'recency_days');
  const recencyDays: number | null =
    recencyRaw !== null ? Math.min(Math.max(recencyRaw, 1), 366) : null;
  const allowWebView: boolean = jsonBoolStrictOrNull(params, 'allow_webview') ?? false;
  const requestedServices: string[] = serviceSelectors(params);
  const sources: OrchestratorSource[] =
    buildSources(settings, requestedServices, query, topic).slice(0, MAX_SOURCES);
  const variants: string[] = query.trim().length === 0
    ? []
    : buildQueryVariants(query, topic, timeRange, recencyDays, depth, deps);
  return {
    query,
    topic,
    time_range: timeRange,
    depth,
    source_count: sources.length,
    sources: sources.map((source: OrchestratorSource): JsonObject => ({
      id: source.id,
      name: source.name,
      kind: source.kind,
      builtin: source.builtin,
      reason: source.reason,
    })),
    query_variants: variants,
    webview_fallback_would_be_available:
      settings.searchGoogleWebViewFallbackEnabled && (allowWebView || depth === 'deep'),
  };
};
