// search_aggregator — 多源聚合搜索(D-069)
// Android 基准: app/.../core/ai/tools/SearchAggregator.kt(全文 384 行)
// 裁剪/偏差登记:
//   - Settings → SearchSettings 子集接口(仅搜索字段;完整 Settings 面随 D-070 落地)
//   - LocalDate.now().toLocalString(true) 为 locale MEDIUM 格式,跨平台不可忠实 →
//     deps.today 注入(默认 ISO 'YYYY-MM-DD'),测试钉住
//   - coroutineScope async/await → Promise.all(语义等价:全部并发后聚合)
//   - Uuid.random().take(6) → newId().substring(0,6)
//   - java.net.URI → 手写容错解析子集(仅 scheme/host/path/query;解析失败回退 trim+lowercase)
import type { JsonObject, JsonValue } from '../chat/json.ts';
import { newId } from '../chat/ids.ts';
import type {
  SearchCommonOptions, SearchResult, SearchResultItem, SearchServiceOptions,
} from './search_service.ts';
import { SEARCH_SERVICE_TYPES } from './search_service.ts';
import { getSearchService } from './service_registry.ts';
import { stringContentOrNull } from './json_pick.ts';

// ===== Settings 子集(Settings.kt 搜索字段面) =====

export interface SearchSettings {
  enableWebSearch: boolean;
  searchCommonOptions: SearchCommonOptions;
  searchServices: SearchServiceOptions[];
  searchEnabledServiceIds: string[];
  searchBuiltinJinaEnabled: boolean;
  searchBuiltinDuckDuckGoEnabled: boolean;
  searchBuiltinBingEnabled: boolean;
  searchBuiltinWikipediaEnabled: boolean;
  searchBuiltinHackerNewsEnabled: boolean;
  searchGoogleWebViewFallbackEnabled: boolean;
}

export interface AggregatorDeps {
  today?: () => string;
}

const MAX_PARALLEL_SERVICES = 3;
const ALLOWED_TOPICS: string[] = ['general', 'news', 'finance'];
const ALLOWED_TIME_RANGES: string[] = ['day', 'week', 'month', 'year', 'any'];

export type SearchExecutor = (
  options: SearchServiceOptions,
  params: JsonObject,
  commonOptions: SearchCommonOptions,
) => Promise<SearchResult>;

interface SourceOutcome {
  ok: boolean;
  result?: SearchResult;
  error?: Error;
}

export interface SearchSourceResult {
  options: SearchServiceOptions;
  sourceIndex: number;
  serviceName: string;
  params: JsonObject;
  outcome: SourceOutcome;
}

// kotlinx jsonPrimitive.intOrNull:number 整数/串精确解析;5.5 → null
const jsonIntOrNull = (j: JsonObject, key: string): number | null => {
  const v: JsonValue | undefined = j[key];
  if (typeof v === 'number') return Number.isInteger(v) ? v : null;
  if (typeof v === 'string' && /^[+-]?\d+$/.test(v)) return parseInt(v, 10);
  return null;
};

// :313-320 services 选择器(aggregator 版:trim 但不滤空串)
const serviceSelectors = (params: JsonObject): string[] => {
  const raw: JsonValue | undefined = params['services'];
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
  return (list ?? []).map((s: string) => s.trim());
};

const serviceName = (options: SearchServiceOptions): string =>
  SEARCH_SERVICE_TYPES[options.type] ?? 'Search';

const supportsNativeTopic = (options: SearchServiceOptions): boolean =>
  options.type === 'tavily';

const domainOf = (url: string): string => {
  try {
    const host: string = parseUrlHost(url.trim());
    return host.toLowerCase().replace(/^www\./, '');
  } catch {
    return '';
  }
};

// ===== 公开函数 =====

// :129-146
export const enabledServices = (
  settings: SearchSettings,
  requestedServices: string[] = [],
): SearchServiceOptions[] => {
  const enabledIds: string[] = settings.searchEnabledServiceIds;
  const enabled: SearchServiceOptions[] =
    settings.searchServices.filter((o: SearchServiceOptions) => enabledIds.includes(o.id));
  if (requestedServices.length === 0) return enabled;
  const selectors: string[] = requestedServices
    .map((s: string) => s.trim().toLowerCase())
    .filter((s: string) => s.trim().length > 0);
  if (selectors.length === 0) return enabled;
  return enabled.filter((options: SearchServiceOptions) => {
    const id: string = options.id.toLowerCase();
    const name: string = serviceName(options).toLowerCase();
    return selectors.some(
      (selector: string) => id === selector || id.startsWith(selector) || name === selector,
    );
  });
};

// :148-170
export const buildServiceParams = (
  query: string,
  topic: string,
  timeRange: string,
  recencyDays: number | null,
  options: SearchServiceOptions,
  deps: AggregatorDeps = {},
): JsonObject => {
  const nativeTopic: boolean = supportsNativeTopic(options);
  const effectiveQuery: string = nativeTopic
    ? query
    : enhanceQuery(query, topic, timeRange, recencyDays, deps);
  const out: JsonObject = { query: effectiveQuery };
  if (nativeTopic) out['topic'] = topic;
  if (options.type === 'firecrawl' && topic === 'news') {
    out['sources'] = ['news', 'web'];
  }
  return out;
};

// :172-195
export const canonicalizeUrl = (url: string): string =>
  canonicalizeUrlWithTracker(url, ['fbclid', 'gclid', 'igshid', 'mc_cid', 'mc_eid']);

// orchestrator 版多滤 yclid(:529)——共用实现,黑名单参数化
export const canonicalizeUrlWithTracker = (url: string, blocked: string[]): string => {
  try {
    const u: string = url.trim();
    const parsed = parseUrl(u);
    const scheme: string = (parsed.scheme ?? 'https').toLowerCase();
    const host: string = parsed.host.toLowerCase().replace(/^www\./, '');
    let path: string = parsed.path.replace(/\/+$/, '');
    if (path.trim().length === 0) path = '/';
    let query: string | null = null;
    if (parsed.query !== null) {
      const kept: string[] = parsed.query.split('&').filter((part: string) => {
        const eq: number = part.indexOf('=');
        const key: string = (eq >= 0 ? part.substring(0, eq) : part).toLowerCase();
        return !blocked.includes(key) && !key.startsWith('utm_');
      }).sort();
      const joined: string = kept.join('&');
      if (joined.trim().length > 0) query = joined;
    }
    let out: string = `${scheme}://${host}${path}`;
    if (query !== null) out += `?${query}`;
    return out;
  } catch {
    return url.trim().toLowerCase();
  }
};

// java.net.URI 容错解析子集:无 scheme:// 时 host 为空(path 保留原串)
interface ParsedUrl { scheme: string | null; host: string; path: string; query: string | null; }
const parseUrl = (u: string): ParsedUrl => {
  if (u.length === 0) throw new Error('empty url');
  // java.net.URI 对空白字符抛 URISyntaxException → 走回退分支
  if (/\s/.test(u)) throw new Error('illegal char in url');
  const m: RegExpExecArray | null = /^([a-zA-Z][a-zA-Z0-9+.-]*):\/\/([^/?#]*)([^?#]*)(?:\?([^#]*))?/.exec(u);
  if (m !== null) {
    let authority: string = m[2];
    const at: number = authority.lastIndexOf('@');
    if (at >= 0) authority = authority.substring(at + 1);
    const colon: number = authority.indexOf(':');
    const host: string = colon >= 0 ? authority.substring(0, colon) : authority;
    return { scheme: m[1], host, path: m[3], query: m[4] ?? null };
  }
  // 相对/无 scheme:host 空,path 取 ? 之前
  const q: number = u.indexOf('?');
  return {
    scheme: null,
    host: '',
    path: q >= 0 ? u.substring(0, q) : u,
    query: q >= 0 ? u.substring(q + 1) : null,
  };
};

const parseUrlHost = (u: string): string => parseUrl(u).host;

// :197-201
export const normalizedTitle = (title: string): string =>
  title.toLowerCase()
    .replace(/[!"#$%&'()*+,\-./:;<=>?@[\\\]^_`{|}~\s]+/g, '')
    .substring(0, 80);

interface AggregatedSearchItem {
  title: string;
  url: string;
  text: string;
  sourceService: string;
  sourceServices: string[]; // LinkedHashSet → 数组+includes(插入序)
  sourceRank: number;
  duplicateCount: number;
  score: number;
  images: string[];
}

const distinctKeepOrder = (list: string[]): string[] => {
  const out: string[] = [];
  for (const s of list) {
    if (!out.includes(s)) out.push(s);
  }
  return out;
};

// :352-384 toJson
const aggregatedItemToJson = (item: AggregatedSearchItem, index: number, maxImages: number): JsonObject => {
  const out: JsonObject = {
    id: newId().substring(0, 6),
    index,
    title: item.title,
    url: item.url,
    text: item.text.substring(0, 2000),
    source_service: item.sourceService,
    source_services: item.sourceServices,
    source_rank: item.sourceRank,
    duplicate_count: item.duplicateCount,
    rank_score: item.score,
    published_at: null,
  };
  const cap: number = Math.min(Math.max(maxImages, 0), 5);
  const cappedImages: string[] = distinctKeepOrder(item.images).slice(0, cap);
  if (cappedImages.length > 0) out['images'] = cappedImages;
  return out;
};

// :336-343 SearchSourceResult.toJson
const sourceResultToJson = (source: SearchSourceResult): JsonObject => {
  const out: JsonObject = {
    service: source.serviceName,
    service_id: source.options.id,
    status: source.outcome.ok ? 'ok' : 'error',
    result_count: source.outcome.result?.items.length ?? 0,
  };
  if (source.outcome.error !== undefined) {
    const msg: string | null = source.outcome.error.message;
    if (msg !== null) out['error'] = msg.substring(0, 500);
  }
  out['query'] = source.params['query'] ?? null;
  return out;
};

// :203-260
const mergeResults = (
  results: SearchSourceResult[],
  query: string,
  topic: string,
  timeRange: string,
): AggregatedSearchItem[] => {
  const byKey = new Map<string, AggregatedSearchItem>();
  const queryTokens: string[] = query.toLowerCase()
    .split(/\s+/)
    .filter((t: string) => t.length >= 2)
    .slice(0, 8);

  for (const source of results) {
    if (!source.outcome.ok || source.outcome.result === undefined) continue;
    const items: SearchResultItem[] = source.outcome.result.items;
    items.forEach((item: SearchResultItem, index: number) => {
      const canonical: string = canonicalizeUrl(item.url);
      const titleKey: string = domainOf(item.url) + '|' + normalizedTitle(item.title);
      const key: string = canonical.trim().length > 0 ? canonical : titleKey;
      const existing: AggregatedSearchItem | undefined = byKey.get(key) ?? byKey.get(titleKey);
      if (existing === undefined) {
        const sourceBoost: number = (results.length - source.sourceIndex) * 10;
        const hitBoost: number = queryTokens.filter((token: string) =>
          item.title.toLowerCase().includes(token) || item.text.toLowerCase().includes(token)
        ).length * 3;
        const freshnessBoost: number = (topic === 'news' && timeRange !== 'any') ? 8 : 0;
        byKey.set(key, {
          title: item.title,
          url: item.url,
          text: item.text,
          sourceService: source.serviceName,
          sourceServices: [source.serviceName],
          sourceRank: index + 1,
          duplicateCount: 1,
          score: sourceBoost + hitBoost + freshnessBoost - index,
          images: item.images.slice(0, 5),
        });
      } else {
        if (!existing.sourceServices.includes(source.serviceName)) {
          existing.sourceServices.push(source.serviceName);
        }
        existing.duplicateCount += 1;
        existing.score += 8;
        if (item.text.length > existing.text.length) existing.text = item.text;
        if (item.images.length > 0 && existing.images.length < 5) {
          existing.images = distinctKeepOrder(existing.images.concat(item.images)).slice(0, 5);
        }
      }
    });
  }
  const list: AggregatedSearchItem[] = Array.from(byKey.values());
  list.sort((a: AggregatedSearchItem, b: AggregatedSearchItem): number =>
    (b.score - a.score) || (b.duplicateCount - a.duplicateCount) || (a.sourceRank - b.sourceRank));
  return list;
};

// :262-282
const enhanceQuery = (
  query: string,
  topic: string,
  timeRange: string,
  recencyDays: number | null,
  deps: AggregatorDeps,
): string => {
  const today: string = (deps.today ?? ((): string => new Date().toISOString().substring(0, 10)))();
  let rangeText: string | null = null;
  if (recencyDays !== null) rangeText = `last ${recencyDays} days`;
  else if (timeRange === 'day') rangeText = `today ${today}`;
  else if (timeRange === 'week') rangeText = `this week ${today}`;
  else if (timeRange === 'month') rangeText = `this month ${today}`;
  else if (timeRange === 'year') rangeText = `this year ${today}`;
  const parts: string[] = [query];
  if (topic === 'news') parts.push('news');
  if (rangeText !== null) parts.push(rangeText);
  return parts.join(' ');
};

// :284-295 默认执行器(经 getService 分派;失败经抛错传递)
const defaultExecutor: SearchExecutor = async (
  options: SearchServiceOptions,
  params: JsonObject,
  commonOptions: SearchCommonOptions,
): Promise<SearchResult> => getSearchService(options).search(params, commonOptions, options);

// :33-127 主入口
export const searchAggregatorSearch = async (
  settings: SearchSettings,
  params: JsonObject,
  executor: SearchExecutor = defaultExecutor,
  deps: AggregatorDeps = {},
): Promise<JsonObject> => {
  const queryMaybe: string | null = stringContentOrNull(params, 'query') ?? stringContentOrNull(params, 'q');
  if (queryMaybe === null) throw new Error('query is required');
  const query: string = queryMaybe;
  const topicRaw: string | null = stringContentOrNull(params, 'topic');
  const topic: string = topicRaw !== null && ALLOWED_TOPICS.includes(topicRaw) ? topicRaw : 'general';
  const trRaw: string | null = stringContentOrNull(params, 'time_range');
  const explicitTimeRange: string | null =
    trRaw !== null && ALLOWED_TIME_RANGES.includes(trRaw) ? trRaw : null;
  const timeRange: string = explicitTimeRange ?? (topic === 'news' ? 'week' : 'any');
  const recencyRaw: number | null = jsonIntOrNull(params, 'recency_days');
  const recencyDays: number | null =
    recencyRaw !== null ? Math.min(Math.max(recencyRaw, 1), 366) : null;
  const maxRaw: number | null = jsonIntOrNull(params, 'max_results');
  const maxResults: number = maxRaw !== null
    ? Math.min(Math.max(maxRaw, 1), 30)
    : Math.min(Math.max(settings.searchCommonOptions.resultSize, 1), 30);
  const requestedServices: string[] = serviceSelectors(params);
  const candidates: SearchServiceOptions[] =
    enabledServices(settings, requestedServices).slice(0, MAX_PARALLEL_SERVICES);

  if (candidates.length === 0) {
    return {
      status: 'error',
      error: 'No enabled search services are available. Enable at least one search service in Search Service settings.',
      items: [],
      sources: [],
    };
  }

  const perServiceSize: number =
    Math.floor((maxResults + candidates.length - 1) / candidates.length) + 3;
  const calls: SearchSourceResult[] = await Promise.all(
    candidates.map(async (options: SearchServiceOptions, sourceIndex: number): Promise<SearchSourceResult> => {
      const name: string = serviceName(options);
      const serviceParams: JsonObject = buildServiceParams(query, topic, timeRange, recencyDays, options, deps);
      let outcome: SourceOutcome;
      try {
        const result: SearchResult = await executor(
          options,
          serviceParams,
          { resultSize: Math.min(Math.max(perServiceSize, 1), 20) },
        );
        outcome = { ok: true, result };
      } catch (e) {
        outcome = { ok: false, error: e instanceof Error ? e : new Error(String(e)) };
      }
      return { options, sourceIndex, serviceName: name, params: serviceParams, outcome };
    }),
  );

  const items: AggregatedSearchItem[] =
    mergeResults(calls, query, topic, timeRange).slice(0, maxResults);

  const out: JsonObject = {
    status: items.length > 0 ? 'ok' : 'empty',
    query,
    topic,
    time_range: timeRange,
  };
  if (recencyDays !== null) out['recency_days'] = recencyDays;
  out['service_count'] = candidates.length;
  // 全局图片预算:一次响应跨 items 至多 5 张(:100-108,emitted 按未预算 distinct 计)
  let imagesBudget: number = 5;
  const itemsJson: JsonValue[] = [];
  items.forEach((item: AggregatedSearchItem, index: number) => {
    const emittedImages: number =
      distinctKeepOrder(item.images).slice(0, Math.min(Math.max(imagesBudget, 0), 5)).length;
    itemsJson.push(aggregatedItemToJson(item, index + 1, imagesBudget));
    imagesBudget = Math.max(imagesBudget - emittedImages, 0);
  });
  out['items'] = itemsJson;
  const allImages: string[] = distinctKeepOrder(
    items.flatMap((item: AggregatedSearchItem) => item.images),
  ).slice(0, 5);
  out['total_images'] = allImages.length;
  if (allImages.length > 0) {
    out['image_instruction'] =
      `搜索结果包含 ${allImages.length} 张相关图片。图片由 AmberAgent 客户端单独处理；` +
      '请不要在回复正文中使用 ![](url) Markdown 图片语法，也不要输出任何图片渲染代码块。' +
      '只需要写好文字内容，并优先给用到的来源附上 [站点名](来源URL) 链接。';
  }
  out['sources'] = calls.map((source: SearchSourceResult): JsonObject => sourceResultToJson(source));
  return out;
};
