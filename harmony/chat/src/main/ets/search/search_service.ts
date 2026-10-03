// search_service — 搜索 SDK 核心(D-065)
//
// Android 基准:
//   search/src/main/java/app/amber/search/SearchService.kt(全文 314 行)
//   ai/src/main/java/app/amber/ai/util/KeyRoulette.kt(全文)
// 裁剪/偏差登记:
//   - sealed class SearchServiceOptions → type 判别联合(SerialName 逐字);
//     Uuid → string(newId);kotlinx 序列化 = classDiscriminator 'type' 居首
//     + 声明序 + explicitNulls=false(null 省略)+ ignoreUnknownKeys
//   - SearchService<T> 泛型 → 联合类型参数(ArkTS 泛型擦除下 when 分派等价,
//     provider 内自行窄化);suspend Result<> → Promise + 抛错(失败经
//     dispatcher/tool 层归一,语义等价);@Composable Description() 属 UI,不移植
//   - Call.await() 协程桥 → HttpClient Port 原生 Promise(@amber/deepread-domain,
//     与 chat 既有 provider 同 Port)
//   - companion httpClient 默认 OkHttpClient → 鸿蒙 HAR 无平台 HTTP,
//     必须 initSearchSdk 注入(未注入调用运行时装 → 抛错,偏差登记)
//   - KeyRoulette.lru(context) 文件持久化 → read/write Port 注入(cacheDir
//     文件由 entry 实现);DefaultKeyRoulette random → 注入随机源(测试可钉)
//   - getService(options) when 分派:随 provider 批次落地(D-066/D-067),
//     本切片仅接口 + 运行时装

import type { AbortSignalLike, HttpClient } from '@amber/deepread-domain';
import type { JsonObject, JsonValue } from '../chat/json.ts';
import type { InputSchemaObj } from '../chat/tool.ts';
import { newId } from '../chat/ids.ts';

// ===== 公共选项/结果模型(:93-131) =====

export interface SearchCommonOptions {
  resultSize: number; // 默认 10
}

export const DEFAULT_SEARCH_COMMON_OPTIONS: SearchCommonOptions = { resultSize: 10 };

export interface SearchResultItem {
  title: string;
  url: string;
  text: string;
  publishedAt: string | null;
  /** 关联图片 URL,每项至多 5 个(约束在 provider 侧) */
  images: string[];
}

export interface SearchResult {
  answer: string | null;
  items: SearchResultItem[];
}

export interface ScrapedResultMetadata {
  title: string | null;
  description: string | null;
  language: string | null;
}

export interface ScrapedResultUrl {
  url: string;
  content: string;
  metadata: ScrapedResultMetadata | null;
}

export interface ScrapedResult {
  urls: ScrapedResultUrl[];
}

// kotlinx explicitNulls=false:非 null 才落键(声明序)
export const searchResultToJson = (r: SearchResult): JsonObject => {
  const out: JsonObject = {};
  if (r.answer !== null) out['answer'] = r.answer;
  const items: JsonValue[] = r.items.map((it: SearchResultItem): JsonObject => {
    const o: JsonObject = {
      title: it.title,
      url: it.url,
      text: it.text,
    };
    if (it.publishedAt !== null) o['publishedAt'] = it.publishedAt;
    o['images'] = it.images;
    return o;
  });
  out['items'] = items;
  return out;
};

export const scrapedResultToJson = (r: ScrapedResult): JsonObject => {
  const urls: JsonValue[] = r.urls.map((u: ScrapedResultUrl): JsonObject => {
    const o: JsonObject = {
      url: u.url,
      content: u.content,
    };
    if (u.metadata !== null) {
      const md: JsonObject = {};
      if (u.metadata.title !== null) md['title'] = u.metadata.title;
      if (u.metadata.description !== null) md['description'] = u.metadata.description;
      if (u.metadata.language !== null) md['language'] = u.metadata.language;
      o['metadata'] = md;
    }
    return o;
  });
  return { urls };
};

// ===== SearchServiceOptions(:133-293;type 判别联合) =====

export type SearchServiceOptionsType =
  | 'bing_local' | 'zhipu' | 'tavily' | 'exa' | 'searxng' | 'linkup' | 'brave'
  | 'serper' | 'serpapi' | 'metaso' | 'ollama' | 'perplexity' | 'firecrawl'
  | 'jina' | 'bocha' | 'amber_agent' | 'grok';

interface OptionsBase {
  type: SearchServiceOptionsType;
  id: string;
}

export interface BingLocalOptions extends OptionsBase { type: 'bing_local'; }
export interface ZhipuOptions extends OptionsBase { type: 'zhipu'; apiKey: string; }
export interface TavilyOptions extends OptionsBase { type: 'tavily'; apiKey: string; depth: string; }
export interface ExaOptions extends OptionsBase { type: 'exa'; apiKey: string; }
export interface SearXNGOptions extends OptionsBase {
  type: 'searxng';
  url: string;
  engines: string;
  language: string;
  username: string;
  password: string;
}
export interface LinkUpOptions extends OptionsBase { type: 'linkup'; apiKey: string; depth: string; }
export interface BraveOptions extends OptionsBase { type: 'brave'; apiKey: string; }
export interface SerperOptions extends OptionsBase { type: 'serper'; apiKey: string; }
export interface SerpApiOptions extends OptionsBase { type: 'serpapi'; apiKey: string; }
export interface MetasoOptions extends OptionsBase { type: 'metaso'; apiKey: string; }
export interface OllamaOptions extends OptionsBase { type: 'ollama'; apiKey: string; }
export interface PerplexityOptions extends OptionsBase {
  type: 'perplexity';
  apiKey: string;
  maxTokens: number | null;
  maxTokensPerPage: number | null;
}
export interface FirecrawlOptions extends OptionsBase { type: 'firecrawl'; apiKey: string; }
export interface JinaOptions extends OptionsBase {
  type: 'jina';
  apiKey: string;
  searchUrl: string;
  scrapeUrl: string;
}
export interface BochaOptions extends OptionsBase { type: 'bocha'; apiKey: string; summary: boolean; }
export interface AmberAgentSearchOptions extends OptionsBase {
  type: 'amber_agent';
  apiKey: string;
  depth: string;
}
export interface GrokOptions extends OptionsBase {
  type: 'grok';
  apiKey: string;
  model: string;
  customUrl: string;
  systemPrompt: string;
}

export type SearchServiceOptions =
  | BingLocalOptions | ZhipuOptions | TavilyOptions | ExaOptions | SearXNGOptions
  | LinkUpOptions | BraveOptions | SerperOptions | SerpApiOptions | MetasoOptions
  | OllamaOptions | PerplexityOptions | FirecrawlOptions | JinaOptions | BochaOptions
  | AmberAgentSearchOptions | GrokOptions;

// TYPES 显示名(mapOf LinkedHashMap 插入序,:140-158)
export const SEARCH_SERVICE_TYPES: Record<SearchServiceOptionsType, string> = {
  bing_local: 'Bing HTML 兜底',
  amber_agent: 'AmberAgent',
  zhipu: '智谱',
  tavily: 'Tavily',
  exa: 'Exa',
  searxng: 'SearXNG',
  linkup: 'LinkUp',
  brave: 'Brave',
  serper: 'Serper',
  serpapi: 'SerpAPI',
  metaso: '秘塔',
  ollama: 'Ollama',
  perplexity: 'Perplexity',
  firecrawl: 'Firecrawl',
  jina: 'Jina',
  bocha: '博查',
  grok: 'Grok',
};

export const GROK_DEFAULT_SYSTEM_PROMPT: string =
  'You are a helpful search assistant. Search the web to find accurate and up-to-date information for the user\'s query. Provide a comprehensive answer with citations.';

// 各类默认值(:161-292 构造默认参数逐字)
export const makeSearchServiceOptions = (type: SearchServiceOptionsType): SearchServiceOptions => {
  const id: string = newId();
  switch (type) {
    case 'bing_local': return { type, id };
    case 'zhipu': return { type, id, apiKey: '' };
    case 'tavily': return { type, id, apiKey: '', depth: 'advanced' };
    case 'exa': return { type, id, apiKey: '' };
    case 'searxng':
      return { type, id, url: '', engines: '', language: '', username: '', password: '' };
    case 'linkup': return { type, id, apiKey: '', depth: 'standard' };
    case 'brave': return { type, id, apiKey: '' };
    case 'serper': return { type, id, apiKey: '' };
    case 'serpapi': return { type, id, apiKey: '' };
    case 'metaso': return { type, id, apiKey: '' };
    case 'ollama': return { type, id, apiKey: '' };
    case 'perplexity': return { type, id, apiKey: '', maxTokens: null, maxTokensPerPage: null };
    case 'firecrawl': return { type, id, apiKey: '' };
    case 'jina':
      return { type, id, apiKey: '', searchUrl: 'https://s.jina.ai/', scrapeUrl: 'https://r.jina.ai/' };
    case 'bocha': return { type, id, apiKey: '', summary: true };
    case 'amber_agent': return { type, id, apiKey: '', depth: 'standard' };
    case 'grok':
      return {
        type, id, apiKey: '', model: 'grok-4-1-fast-non-reasoning',
        customUrl: 'https://api.x.ai/v1/responses', systemPrompt: GROK_DEFAULT_SYSTEM_PROMPT,
      };
  }
};

export const DEFAULT_SEARCH_SERVICE_OPTIONS: SearchServiceOptions =
  makeSearchServiceOptions('bing_local');

// ===== 序列化(kotlinx:classDiscriminator 'type' 居首 + 声明序 + null 省略) =====

export const searchServiceOptionsToJson = (o: SearchServiceOptions): JsonObject => {
  const out: JsonObject = { type: o.type, id: o.id };
  switch (o.type) {
    case 'bing_local': break;
    case 'zhipu': out['apiKey'] = o.apiKey; break;
    case 'tavily': out['apiKey'] = o.apiKey; out['depth'] = o.depth; break;
    case 'exa': out['apiKey'] = o.apiKey; break;
    case 'searxng':
      out['url'] = o.url; out['engines'] = o.engines; out['language'] = o.language;
      out['username'] = o.username; out['password'] = o.password;
      break;
    case 'linkup': out['apiKey'] = o.apiKey; out['depth'] = o.depth; break;
    case 'brave': out['apiKey'] = o.apiKey; break;
    case 'serper': out['apiKey'] = o.apiKey; break;
    case 'serpapi': out['apiKey'] = o.apiKey; break;
    case 'metaso': out['apiKey'] = o.apiKey; break;
    case 'ollama': out['apiKey'] = o.apiKey; break;
    case 'perplexity':
      out['apiKey'] = o.apiKey;
      if (o.maxTokens !== null) out['maxTokens'] = o.maxTokens;
      if (o.maxTokensPerPage !== null) out['maxTokensPerPage'] = o.maxTokensPerPage;
      break;
    case 'firecrawl': out['apiKey'] = o.apiKey; break;
    case 'jina': out['apiKey'] = o.apiKey; out['searchUrl'] = o.searchUrl; out['scrapeUrl'] = o.scrapeUrl; break;
    case 'bocha': out['apiKey'] = o.apiKey; out['summary'] = o.summary; break;
    case 'amber_agent': out['apiKey'] = o.apiKey; out['depth'] = o.depth; break;
    case 'grok':
      out['apiKey'] = o.apiKey; out['model'] = o.model; out['customUrl'] = o.customUrl;
      out['systemPrompt'] = o.systemPrompt;
      break;
  }
  return out;
};

const strOr = (j: JsonObject, key: string, fallback: string): string => {
  const v: JsonValue | undefined = j[key];
  return typeof v === 'string' ? v : fallback;
};

const numOrNull = (j: JsonObject, key: string): number | null => {
  const v: JsonValue | undefined = j[key];
  return typeof v === 'number' ? v : null;
};

// ignoreUnknownKeys;缺字段回该类默认;未知 type → 抛错(调用方逐条解码、
// 只跳过未知条目 — 整体 catch 会把已配置的有效服务全部抹成默认)
export const isKnownSearchServiceType = (t: string): boolean =>
  Object.keys(SEARCH_SERVICE_TYPES).includes(t);

export const searchServiceOptionsFromJson = (j: JsonObject): SearchServiceOptions => {
  const t: JsonValue | undefined = j['type'];
  if (typeof t !== 'string') throw new Error('SearchServiceOptions.type is required');
  if (!isKnownSearchServiceType(t)) {
    throw new Error(`unknown search service type: ${t}`);
  }
  const id: string = strOr(j, 'id', newId());
  const base = makeSearchServiceOptions(t as SearchServiceOptionsType);
  // 逐项覆盖(缺字段/类型不符 → 保留默认;未知键忽略)
  switch (base.type) {
    case 'bing_local': base.id = id; return base;
    case 'zhipu': base.id = id; base.apiKey = strOr(j, 'apiKey', base.apiKey); return base;
    case 'tavily':
      base.id = id; base.apiKey = strOr(j, 'apiKey', base.apiKey);
      base.depth = strOr(j, 'depth', base.depth);
      return base;
    case 'exa': base.id = id; base.apiKey = strOr(j, 'apiKey', base.apiKey); return base;
    case 'searxng':
      base.id = id;
      base.url = strOr(j, 'url', base.url);
      base.engines = strOr(j, 'engines', base.engines);
      base.language = strOr(j, 'language', base.language);
      base.username = strOr(j, 'username', base.username);
      base.password = strOr(j, 'password', base.password);
      return base;
    case 'linkup':
      base.id = id; base.apiKey = strOr(j, 'apiKey', base.apiKey);
      base.depth = strOr(j, 'depth', base.depth);
      return base;
    case 'brave': base.id = id; base.apiKey = strOr(j, 'apiKey', base.apiKey); return base;
    case 'serper': base.id = id; base.apiKey = strOr(j, 'apiKey', base.apiKey); return base;
    case 'serpapi': base.id = id; base.apiKey = strOr(j, 'apiKey', base.apiKey); return base;
    case 'metaso': base.id = id; base.apiKey = strOr(j, 'apiKey', base.apiKey); return base;
    case 'ollama': base.id = id; base.apiKey = strOr(j, 'apiKey', base.apiKey); return base;
    case 'perplexity': {
      base.id = id; base.apiKey = strOr(j, 'apiKey', base.apiKey);
      const mt: number | null = numOrNull(j, 'maxTokens');
      const mtp: number | null = numOrNull(j, 'maxTokensPerPage');
      base.maxTokens = mt;
      base.maxTokensPerPage = mtp;
      return base;
    }
    case 'firecrawl': base.id = id; base.apiKey = strOr(j, 'apiKey', base.apiKey); return base;
    case 'jina':
      base.id = id; base.apiKey = strOr(j, 'apiKey', base.apiKey);
      base.searchUrl = strOr(j, 'searchUrl', base.searchUrl);
      base.scrapeUrl = strOr(j, 'scrapeUrl', base.scrapeUrl);
      return base;
    case 'bocha': {
      base.id = id; base.apiKey = strOr(j, 'apiKey', base.apiKey);
      const s: JsonValue | undefined = j['summary'];
      base.summary = typeof s === 'boolean' ? s : base.summary;
      return base;
    }
    case 'amber_agent':
      base.id = id; base.apiKey = strOr(j, 'apiKey', base.apiKey);
      base.depth = strOr(j, 'depth', base.depth);
      return base;
    case 'grok':
      base.id = id; base.apiKey = strOr(j, 'apiKey', base.apiKey);
      base.model = strOr(j, 'model', base.model);
      base.customUrl = strOr(j, 'customUrl', base.customUrl);
      base.systemPrompt = strOr(j, 'systemPrompt', base.systemPrompt);
      return base;
  }
};

// ===== SearchService 接口(:22-43) =====

export interface SearchRequestContext {
  // 每次请求独立传递,不改动共享 SDK 运行时。
  signal?: AbortSignalLike;
}

export interface SearchService {
  readonly name: string;
  readonly parameters: InputSchemaObj | null;
  readonly scrapingParameters: InputSchemaObj | null;
  search(
    params: JsonObject, commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
    context?: SearchRequestContext,
  ): Promise<SearchResult>;
  scrape(
    params: JsonObject, commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
    context?: SearchRequestContext,
  ): Promise<ScrapedResult>;
}

// ===== 运行时装(companion httpClient/keyRoulette + init,:68-90) =====

export interface SearchSdkRuntime {
  http: HttpClient;
  keyRoulette: KeyRoulette;
  // Locale.getDefault() → "${language}-${country},${language}"(Bing Accept-Language);
  // 平台 locale 由 entry 注入,缺省 zh-CN,zh(偏差登记:无系统 locale 默认值)
  acceptLanguage: () => string;
}

let sdkRuntime: SearchSdkRuntime | null = null;

// Android init(client, context?):鸿蒙必须注入 http;roulette 缺省 = default()
export const initSearchSdk = (
  http: HttpClient, keyRoulette?: KeyRoulette, acceptLanguage?: () => string,
): void => {
  sdkRuntime = {
    http,
    keyRoulette: keyRoulette ?? createDefaultKeyRoulette(),
    acceptLanguage: acceptLanguage ?? ((): string => 'zh-CN,zh'),
  };
};

export const searchSdkRuntime = (): SearchSdkRuntime => {
  if (sdkRuntime === null) {
    throw new Error('SearchSdk not initialized: call initSearchSdk(http) first');
  }
  return sdkRuntime;
};

// ===== KeyRoulette(KeyRoulette.kt 全文) =====

export interface KeyRoulette {
  next(keys: string, providerId?: string): string;
}

const SPLIT_KEY_REGEX: RegExp = /[\s,]+/; // 空格换行和逗号

// splitKey:split → trim → 非空白 → distinct(保首次出现序)
const splitKey = (key: string): string[] => {
  const seen: string[] = [];
  for (const part of key.split(SPLIT_KEY_REGEX)) {
    const t: string = part.trim();
    if (t.length > 0 && !seen.includes(t)) seen.push(t);
  }
  return seen;
};

// DefaultKeyRoulette:随机取一;空列表 → 原串
export const createDefaultKeyRoulette = (
  random: () => number = Math.random,
): KeyRoulette => ({
  next: (keys: string, _providerId: string = ''): string => {
    const keyList: string[] = splitKey(keys);
    if (keyList.length === 0) return keys;
    const idx: number = Math.min(keyList.length - 1, Math.floor(random() * keyList.length));
    return keyList[idx];
  },
});

const LRU_EXPIRE_DURATION_MS: number = 24 * 60 * 60 * 1000; // 1 天

// 文件结构: Map<providerId, Map<apiKey, lastUsedTimestamp>> → 嵌套 JSON 对象
export interface LruKeyRouletteStore {
  // 读取持久化 JSON;不存在 → null;解析失败按空缓存(Android catch → emptyMap)
  read: () => string | null;
  // 写入失败吞咽(Android catch {} 静默 — 平台缓存,非业务数据)
  write: (content: string) => void;
  nowMs: () => number;
}

export const createLruKeyRoulette = (store: LruKeyRouletteStore): KeyRoulette => ({
  next: (keys: string, providerId: string = ''): string => {
    const keyList: string[] = splitKey(keys);
    if (keyList.length === 0) return keys;
    const now: number = store.nowMs();
    // loadCache:解析失败 → 空
    let allCache: Record<string, Record<string, number>> = {};
    const raw: string | null = store.read();
    if (raw !== null) {
      try {
        allCache = JSON.parse(raw) as Record<string, Record<string, number>>;
      } catch {
        allCache = {};
      }
    }
    // 取本 provider 记录,过滤已过期与不在当前 key 列表的条目
    const providerCache: Record<string, number> = {};
    const existing: Record<string, number> | undefined = allCache[providerId];
    if (existing !== undefined) {
      for (const k of Object.keys(existing)) {
        if (keyList.includes(k) && now - existing[k] < LRU_EXPIRE_DURATION_MS) {
          providerCache[k] = existing[k];
        }
      }
    }
    // 优先从未使用,否则最久未使用(minByOrNull 平手取先遇 — 对象插入序)
    let selected: string | null = null;
    for (const k of keyList) {
      if (providerCache[k] === undefined) {
        selected = k;
        break;
      }
    }
    if (selected === null) {
      let minTs: number = Number.MAX_SAFE_INTEGER;
      for (const k of Object.keys(providerCache)) {
        if (providerCache[k] < minTs) {
          minTs = providerCache[k];
          selected = k;
        }
      }
    }
    // keyList 非空且 providerCache 覆盖全部时 selected 必非 null
    const picked: string = selected ?? keyList[0];
    providerCache[picked] = now;
    allCache[providerId] = providerCache;
    // 清理他 provider 全过期条目
    for (const id of Object.keys(allCache)) {
      if (id === providerId) continue;
      const cache: Record<string, number> = allCache[id];
      const values: number[] = Object.keys(cache).map((k: string): number => cache[k]);
      const allExpired: boolean =
        values.every((ts: number): boolean => now - ts >= LRU_EXPIRE_DURATION_MS);
      if (allExpired) delete allCache[id];
    }
    try {
      store.write(JSON.stringify(allCache));
    } catch {
      // Android saveCache catch {} — 吞咽
    }
    return picked;
  },
});
