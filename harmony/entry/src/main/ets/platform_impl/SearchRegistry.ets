// SearchRegistry — 多搜索 provider 并发 + 合并去重
// 对照 Android:20 个搜索源,这里实现主要 provider(免费 + 需 key)

import type { AbortSignalLike, HttpClient, HttpRequest, HttpResponse, Storage, SearchProviderRegistry, SearchProvider, SearchHit } from '@amber/deepread-domain';
import { createTavilyProvider } from '@amber/deepread-domain';
import { createNewsNowProvider } from './NewsNowProvider.ets';
import type { FreeEngineId, SearchServiceOptions, SearchSettings, DeepReadSearchGoogle } from '@amber/chat-domain';
import {
  FREE_ENGINE_NAMES, SEARCH_SERVICE_TYPES, enabledServices, freeEngineCoolingDown, getSearchService,
  looksTechnicalQuery, runFreeEngine, createDeepReadSearchProviders,
} from '@amber/chat-domain';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Search';

// R20:取消已发生时不再进入下一轮 query、不再发起 HTTP;真实中止底层外呼
const isAborted = (signal?: AbortSignalLike): boolean =>
  signal !== undefined && signal.aborted;

// ── 响应类型(顶层声明) ──
interface BochaResult { url?: string; name?: string; snippet?: string; }
interface BochaWebPages { value?: BochaResult[]; }
interface BochaData { webPages?: BochaWebPages; }
interface BochaResponse { data?: BochaData; }
interface BraveResult { url?: string; title?: string; description?: string; }
interface BraveWeb { results?: BraveResult[]; }
interface BraveResponse { web?: BraveWeb; }
interface ExaResult { url?: string; title?: string; text?: string; }
interface ExaResponse { results?: ExaResult[]; }

// ── Bocha 搜索(需 key) ──
class BochaProvider implements SearchProvider {
  readonly name: string = 'bocha';
  private http: HttpClient;
  private apiKey: string;

  constructor(http: HttpClient, apiKey: string) {
    this.http = http;
    this.apiKey = apiKey;
  }

  async search(queries: string[], signal?: AbortSignalLike): Promise<SearchHit[]> {
    if (this.apiKey.length === 0) return [];
    const hits: SearchHit[] = [];
    for (let qi = 0; qi < queries.length; qi++) {
      if (isAborted(signal)) break;
      const q: string = queries[qi];
      try {
        const req: HttpRequest = {
          url: 'https://api.bochaai.com/v1/web-search',
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${this.apiKey}`,
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({ query: q, summary: false, count: 5 }),
        };
        const resp: HttpResponse = await this.http.fetch(req, signal === undefined ? undefined : { signal: signal });
        if (resp.status !== 200) continue;
        const data: BochaResponse = JSON.parse(resp.body) as BochaResponse;
        const items = data.data?.webPages?.value ?? [];
        for (let i = 0; i < items.length; i++) {
          const item = items[i];
          if (item.url !== undefined && item.name !== undefined) {
            hits.push({ title: item.name, url: item.url, snippet: item.snippet ?? '', source: 'bocha' });
          }
        }
      } catch (e) {
        if (isAborted(signal)) break;
        hilog.warn(DOMAIN, TAG, 'Bocha failed: %{public}s', String(e));
      }
    }
    return hits;
  }
}

// ── Brave 搜索(需 key) ──
class BraveProvider implements SearchProvider {
  readonly name: string = 'brave';
  private http: HttpClient;
  private apiKey: string;

  constructor(http: HttpClient, apiKey: string) {
    this.http = http;
    this.apiKey = apiKey;
  }

  async search(queries: string[], signal?: AbortSignalLike): Promise<SearchHit[]> {
    if (this.apiKey.length === 0) return [];
    const hits: SearchHit[] = [];
    for (let qi = 0; qi < queries.length; qi++) {
      if (isAborted(signal)) break;
      const q: string = queries[qi];
      try {
        const url: string = `https://api.search.brave.com/res/v1/web/search?q=${encodeURIComponent(q)}&count=5`;
        const req: HttpRequest = {
          url: url,
          method: 'GET',
          headers: {
            'Accept': 'application/json',
            'X-Subscription-Token': this.apiKey,
          },
        };
        const resp: HttpResponse = await this.http.fetch(req, signal === undefined ? undefined : { signal: signal });
        if (resp.status !== 200) continue;
        const data: BraveResponse = JSON.parse(resp.body) as BraveResponse;
        const items = data.web?.results ?? [];
        for (let i = 0; i < items.length; i++) {
          const item = items[i];
          if (item.url !== undefined && item.title !== undefined) {
            hits.push({ title: item.title, url: item.url, snippet: item.description ?? '', source: 'brave' });
          }
        }
      } catch (e) {
        if (isAborted(signal)) break;
        hilog.warn(DOMAIN, TAG, 'Brave failed: %{public}s', String(e));
      }
    }
    return hits;
  }
}

// ── Exa 搜索(需 key) ──
class ExaProvider implements SearchProvider {
  readonly name: string = 'exa';
  private http: HttpClient;
  private apiKey: string;

  constructor(http: HttpClient, apiKey: string) {
    this.http = http;
    this.apiKey = apiKey;
  }

  async search(queries: string[], signal?: AbortSignalLike): Promise<SearchHit[]> {
    if (this.apiKey.length === 0) return [];
    const hits: SearchHit[] = [];
    for (let qi = 0; qi < queries.length; qi++) {
      if (isAborted(signal)) break;
      const q: string = queries[qi];
      try {
        const req: HttpRequest = {
          url: 'https://api.exa.ai/search',
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${this.apiKey}`,
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({ query: q, numResults: 5, type: 'auto' }),
        };
        const resp: HttpResponse = await this.http.fetch(req, signal === undefined ? undefined : { signal: signal });
        if (resp.status !== 200) continue;
        const data: ExaResponse = JSON.parse(resp.body) as ExaResponse;
        const items = data.results ?? [];
        for (let i = 0; i < items.length; i++) {
          const item = items[i];
          if (item.url !== undefined && item.title !== undefined) {
            hits.push({ title: item.title, url: item.url, snippet: item.text?.substring(0, 100) ?? '', source: 'exa' });
          }
        }
      } catch (e) {
        if (isAborted(signal)) break;
        hilog.warn(DOMAIN, TAG, 'Exa failed: %{public}s', String(e));
      }
    }
    return hits;
  }
}

// ── SearXNG 搜索(自建实例,无 key 需配 URL) ──
class SearXNGProvider implements SearchProvider {
  readonly name: string = 'searxng';
  private http: HttpClient;
  private baseUrl: string;

  constructor(http: HttpClient, baseUrl: string) {
    this.http = http;
    this.baseUrl = baseUrl;
  }

  async search(queries: string[], signal?: AbortSignalLike): Promise<SearchHit[]> {
    if (this.baseUrl.length === 0) return [];
    const hits: SearchHit[] = [];
    for (let qi = 0; qi < queries.length; qi++) {
      if (isAborted(signal)) break;
      const q: string = queries[qi];
      try {
        const base: string = this.baseUrl.endsWith('/') ? this.baseUrl.substring(0, this.baseUrl.length - 1) : this.baseUrl;
        const url: string = `${base}/search?q=${encodeURIComponent(q)}&format=json`;
        const headers: Record<string, string> = { 'Accept': 'application/json' };
        const req: HttpRequest = {
          url: url,
          method: 'GET',
          headers: headers,
        };
        const resp: HttpResponse = await this.http.fetch(req, signal === undefined ? undefined : { signal: signal });
        if (resp.status !== 200) continue;
        interface SearxResult { url?: string; title?: string; content?: string; }
        interface SearxResponse { results?: SearxResult[]; }
        const data: SearxResponse = JSON.parse(resp.body) as SearxResponse;
        const items = data.results ?? [];
        for (let i = 0; i < items.length; i++) {
          const item = items[i];
          if (item.url !== undefined && item.title !== undefined) {
            hits.push({ title: item.title, url: item.url, snippet: item.content ?? '', source: 'searxng' });
          }
        }
      } catch (e) {
        if (isAborted(signal)) break;
        hilog.warn(DOMAIN, TAG, 'SearXNG failed: %{public}s', String(e));
      }
    }
    return hits;
  }
}

// ── 免费网页引擎(DDG/Brave/Bing/360/夸克 + Wikipedia/HN)──
// 复用 chat 搜索层的解析器与验证码熔断(对齐 iOS 免费搜索重做)。
// 旧实现(DDG Lite 正则抓链接 / 无 Key Jina 搜索 / HN 全话题)已移除:
// Lite 首个请求即 202 验证页;s.jina.ai 无 Key 返回 401 且把搜索页当来源 URL。
// 每个引擎单独一个 provider:慢/被拦的引擎只丢自己的 bucket,不拖累其它引擎。
// Historical first-four cap is no longer used; the wall budget limits queries.
const FREE_ENGINE_MAX_QUERIES: number = 4;
// 严格早于 search 的 10s 外层截止,让内部超时先返回已经获得的 hits。
const PROVIDER_SOFT_BUDGET_MS: number = 9_000;

const isUrlQuery = (q: string): boolean => /^https?:\/\//i.test(q);

// 单次调用与剩余预算赛跑:超时只放弃这一条,已拿到的结果照常返回
const withinBudget = <T>(p: Promise<T>, remainingMs: number): Promise<T> =>
  new Promise<T>((resolve, reject): void => {
    const timer: number = setTimeout((): void => { reject(new Error('provider budget exceeded')); },
      Math.max(remainingMs, 0));
    p.then((v: T): void => { clearTimeout(timer); resolve(v); },
      (e: Error): void => { clearTimeout(timer); reject(e); });
  });

// 免费引擎受聊天「搜索服务」里的内置源开关控制(DDG 开关 = 免费网页聚合总开关)
const freeEngineEnabled = (id: FreeEngineId, s: SearchSettings): boolean => {
  if (id === 'bing') return s.searchBuiltinBingEnabled;
  if (id === 'wikipedia') return s.searchBuiltinWikipediaEnabled;
  if (id === 'hackernews') return s.searchBuiltinHackerNewsEnabled;
  return s.searchBuiltinDuckDuckGoEnabled;
};

// 深度阅读的引擎顺序(interleave 按此轮转):国外网页 → 国内网页 → 垂直源
const DEEPREAD_FREE_ENGINES: FreeEngineId[] =
  ['duckduckgo', 'brave', 'bing', 'quark', 'so360', 'wikipedia', 'hackernews'];

class FreeEngineProvider implements SearchProvider {
  readonly name: string;
  private id: FreeEngineId;
  private loadSettings: () => Promise<SearchSettings>;
  private ensureSdk: () => void;

  constructor(id: FreeEngineId, loadSettings: () => Promise<SearchSettings>, ensureSdk: () => void) {
    this.id = id;
    this.name = FREE_ENGINE_NAMES[id];
    this.loadSettings = loadSettings;
    this.ensureSdk = ensureSdk;
  }

  // Wikipedia/HN 保留主题检索;网页引擎在预算内尝试全部研究角度。
  private pickQueries(queries: string[]): string[] {
    const plain: string[] = queries.filter((q: string) => !isUrlQuery(q));
    if (plain.length === 0) return [];
    if (this.id === 'wikipedia') return [plain[0]];
    if (this.id === 'hackernews') return looksTechnicalQuery(plain[0]) ? [plain[0]] : [];
    return plain;
  }

  async search(
    queries: string[], signal?: AbortSignalLike, onFailure?: (query: string, error: string) => void,
  ): Promise<SearchHit[]> {
    const settings: SearchSettings = await this.loadSettings();
    if (!freeEngineEnabled(this.id, settings)) return [];
    const resultSize: number = settings.searchCommonOptions.resultSize;
    this.ensureSdk();
    const startedAt: number = Date.now();
    const hits: SearchHit[] = [];
    const picked: string[] = this.pickQueries(queries);
    // 同一引擎内串行(天然限流),不同引擎之间并发
    for (let qi = 0; qi < picked.length; qi++) {
      if (isAborted(signal) || freeEngineCoolingDown(this.id)) break;
      const remaining: number = PROVIDER_SOFT_BUDGET_MS - (Date.now() - startedAt);
      if (remaining <= 0) break;
      try {
        const result = await withinBudget(
          runFreeEngine(this.id, picked[qi], { resultSize }, { signal }), remaining);
        for (let i = 0; i < result.items.length; i++) {
          const item = result.items[i];
          hits.push({ title: item.title, url: item.url, snippet: item.text, source: this.name,
            publishedAt: item.publishedAt });
        }
      } catch (e) {
        if (isAborted(signal)) break;
        onFailure?.(picked[qi], `${this.name}: ${e instanceof Error ? e.message : String(e)}`);
        hilog.warn(DOMAIN, TAG, '%{public}s failed: %{public}s', this.name, String(e));
      }
    }
    return hits;
  }
}

// ── 用户在「搜索服务」里配置并启用的 API 源(Tavily/Serper/Exa/智谱/博查…)──
// 每次搜索时现读设置:用户配完 Key 后深度阅读立即生效
class ConfiguredServicesProvider implements SearchProvider {
  readonly name: string = 'configured';
  private loadSettings: () => Promise<SearchSettings>;
  private ensureSdk: () => void;

  constructor(loadSettings: () => Promise<SearchSettings>, ensureSdk: () => void) {
    this.loadSettings = loadSettings;
    this.ensureSdk = ensureSdk;
  }

  private async searchOne(
    options: SearchServiceOptions, queries: string[], startedAt: number, resultSize: number, signal?: AbortSignalLike,
    onFailure?: (query: string, error: string) => void,
  ): Promise<SearchHit[]> {
    const hits: SearchHit[] = [];
    const serviceName: string = SEARCH_SERVICE_TYPES[options.type] ?? 'Search';
    for (let qi = 0; qi < queries.length; qi++) {
      if (isAborted(signal)) break;
      const remaining: number = PROVIDER_SOFT_BUDGET_MS - (Date.now() - startedAt);
      if (remaining <= 0) break;
      try {
        const result = await withinBudget(getSearchService(options).search(
          { query: queries[qi] }, { resultSize }, options, { signal }), remaining);
        for (let i = 0; i < result.items.length; i++) {
          const item = result.items[i];
          hits.push({ title: item.title, url: item.url, snippet: item.text, source: serviceName,
            publishedAt: item.publishedAt });
        }
      } catch (e) {
        if (isAborted(signal)) break;
        onFailure?.(queries[qi], `${serviceName}: ${e instanceof Error ? e.message : String(e)}`);
        hilog.warn(DOMAIN, TAG, '%{public}s failed: %{public}s', serviceName, String(e));
      }
    }
    return hits;
  }

  async search(
    queries: string[], signal?: AbortSignalLike, onFailure?: (query: string, error: string) => void,
  ): Promise<SearchHit[]> {
    this.ensureSdk();
    const startedAt: number = Date.now();
    const settings: SearchSettings = await this.loadSettings();
    const services: SearchServiceOptions[] = enabledServices(settings);
    if (services.length === 0) return [];
    const picked: string[] = queries.filter((q: string) => !isUrlQuery(q));
    const buckets: SearchHit[][] = await Promise.all(services.map(
      (o: SearchServiceOptions) => this.searchOne(o, picked, startedAt, settings.searchCommonOptions.resultSize, signal, onFailure)));
    const hits: SearchHit[] = [];
    for (let i = 0; i < buckets.length; i++) hits.push(...buckets[i]);
    return hits;
  }
}

interface RegistrySnapshot {
  providers: SearchProvider[];
  readerEnabled: boolean;
  cacheKey: string;
}

export interface DeepReadSearchRegistryOptions {
  searchGoogle?: DeepReadSearchGoogle;
}

class MultiProviderRegistry implements SearchProviderRegistry {
  private http: HttpClient;
  private storage: Storage;
  private cachedEnabled: SearchProvider[] = [];

  private loadSearchSettings: () => Promise<SearchSettings>;
  private ensureSdk: () => void;
  private searchGoogle: DeepReadSearchGoogle | undefined;

  constructor(
    http: HttpClient, storage: Storage,
    loadSearchSettings: () => Promise<SearchSettings>, ensureSdk: () => void,
    options?: DeepReadSearchRegistryOptions,
  ) {
    this.http = http;
    this.storage = storage;
    this.loadSearchSettings = loadSearchSettings;
    this.ensureSdk = ensureSdk;
    this.searchGoogle = options?.searchGoogle;
    // The first call has the same configured/free sources as later calls.
    // NewsNow is consumed through explicit topic seeds, not another hot-list fetch.
    this.cachedEnabled = [{ name: 'deepread', search: async (
      queries: string[], signal?: AbortSignalLike, onFailure?: (query: string, error: string) => void,
    ): Promise<SearchHit[]> => {
      const settings: SearchSettings = await this.loadSearchSettings();
      return this.providersForSettings(settings)[0].search(queries, signal, onFailure);
    } }];
  }

  private providersForSettings(settings: SearchSettings): SearchProvider[] {
    const loadSettings = (): Promise<SearchSettings> => Promise.resolve(settings);
    const providers: SearchProvider[] = [];
    for (let i = 0; i < DEEPREAD_FREE_ENGINES.length; i++) {
      providers.push(new FreeEngineProvider(DEEPREAD_FREE_ENGINES[i], loadSettings, this.ensureSdk));
    }
    return createDeepReadSearchProviders(settings, {
      freeProviders: providers, ensureSdk: this.ensureSdk, searchGoogle: this.searchGoogle,
    });
  }

  async snapshot(): Promise<RegistrySnapshot> {
    const settings: SearchSettings = await this.loadSearchSettings();
    return {
      providers: this.providersForSettings(settings),
      readerEnabled: settings.searchBuiltinJinaEnabled,
      // Only held by the prefetch cache; never printed or sent to providers.
      cacheKey: JSON.stringify(settings),
    };
  }

  // Historical independent-key adapters remain for reference. They are not
  // activated: the search settings page is the source of truth for each run.
  private async loadKeyedProviders(): Promise<void> {
    // 用户配置的 API 源排最前(质量最好)
    const providers: SearchProvider[] = [
      new ConfiguredServicesProvider(this.loadSearchSettings, this.ensureSdk),
      createNewsNowProvider(this.http),
    ];
    // 开关在每次搜索时现读(构造期读设置会在 AppContainer 未建好时回调 getAppContainer)
    for (let i = 0; i < DEEPREAD_FREE_ENGINES.length; i++) {
      providers.push(new FreeEngineProvider(DEEPREAD_FREE_ENGINES[i], this.loadSearchSettings, this.ensureSdk));
    }
    // 以下独立存储键目前没有任何写入方(设置页不写),属历史遗留;保留不动
    // Tavily
    const tavilyKey: string = await this.storage.get<string>('tavily_api_key', '');
    if (tavilyKey.length > 0) {
      providers.push(createTavilyProvider(this.http, { apiKey: tavilyKey }));
    }
    // Bocha
    const bochaKey: string = await this.storage.get<string>('bocha_api_key', '');
    if (bochaKey.length > 0) {
      providers.push(new BochaProvider(this.http, bochaKey));
    }
    // Brave
    const braveKey: string = await this.storage.get<string>('brave_api_key', '');
    if (braveKey.length > 0) {
      providers.push(new BraveProvider(this.http, braveKey));
    }
    // Exa
    const exaKey: string = await this.storage.get<string>('exa_api_key', '');
    if (exaKey.length > 0) {
      providers.push(new ExaProvider(this.http, exaKey));
    }
    // SearXNG
    const searxUrl: string = await this.storage.get<string>('searxng_base_url', '');
    if (searxUrl.length > 0) {
      providers.push(new SearXNGProvider(this.http, searxUrl));
    }
    this.cachedEnabled = providers;
  }

  enabled(): SearchProvider[] {
    return this.cachedEnabled;
  }

  fallback(): SearchProvider[] {
    return [];
  }
}

export const createTavilyAndFallbackRegistry = (
  http: HttpClient,
  storage: Storage,
  loadSearchSettings: () => Promise<SearchSettings>,
  ensureSdk: () => void,
  options?: DeepReadSearchRegistryOptions,
): SearchProviderRegistry => {
  return new MultiProviderRegistry(http, storage, loadSearchSettings, ensureSdk, options);
};
