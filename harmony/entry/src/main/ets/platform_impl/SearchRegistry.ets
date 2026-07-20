// SearchRegistry — 多搜索 provider 并发 + 合并去重
// 对照 Android:20 个搜索源,这里实现主要 provider(免费 + 需 key)

import type { HttpClient, HttpRequest, HttpResponse, Storage, SearchProviderRegistry, SearchProvider, SearchHit } from '@amber/deepread-domain';
import { createTavilyProvider } from '@amber/deepread-domain';
import { createNewsNowProvider } from './NewsNowProvider.ets';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Search';

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
interface HnHit { title?: string; url?: string; objectID?: number; points?: number; }
interface HnResponse { hits?: HnHit[]; }

// ── Bocha 搜索(需 key) ──
class BochaProvider implements SearchProvider {
  readonly name: string = 'bocha';
  private http: HttpClient;
  private apiKey: string;

  constructor(http: HttpClient, apiKey: string) {
    this.http = http;
    this.apiKey = apiKey;
  }

  async search(queries: string[]): Promise<SearchHit[]> {
    if (this.apiKey.length === 0) return [];
    const hits: SearchHit[] = [];
    for (let qi = 0; qi < queries.length; qi++) {
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
        const resp: HttpResponse = await this.http.fetch(req);
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

  async search(queries: string[]): Promise<SearchHit[]> {
    if (this.apiKey.length === 0) return [];
    const hits: SearchHit[] = [];
    for (let qi = 0; qi < queries.length; qi++) {
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
        const resp: HttpResponse = await this.http.fetch(req);
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

  async search(queries: string[]): Promise<SearchHit[]> {
    if (this.apiKey.length === 0) return [];
    const hits: SearchHit[] = [];
    for (let qi = 0; qi < queries.length; qi++) {
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
        const resp: HttpResponse = await this.http.fetch(req);
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

  async search(queries: string[]): Promise<SearchHit[]> {
    if (this.baseUrl.length === 0) return [];
    const hits: SearchHit[] = [];
    for (let qi = 0; qi < queries.length; qi++) {
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
        const resp: HttpResponse = await this.http.fetch(req);
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
        hilog.warn(DOMAIN, TAG, 'SearXNG failed: %{public}s', String(e));
      }
    }
    return hits;
  }
}

// ── DuckDuckGo 搜索(免费,网页解析,无 key) ──
class DuckDuckGoProvider implements SearchProvider {
  readonly name: string = 'duckduckgo';
  private http: HttpClient;

  constructor(http: HttpClient) { this.http = http; }

  async search(queries: string[]): Promise<SearchHit[]> {
    const hits: SearchHit[] = [];
    for (let qi = 0; qi < queries.length; qi++) {
      const q: string = queries[qi];
      try {
        const headers: Record<string, string> = {
          'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36',
          'Accept': 'text/html,application/xhtml+xml',
        };
        const req: HttpRequest = {
          url: `https://lite.duckduckgo.com/lite/?q=${encodeURIComponent(q)}`,
          method: 'GET',
          headers: headers,
        };
        const resp: HttpResponse = await this.http.fetch(req);
        if (resp.status !== 200) continue;
        const linkRe = /<a[^>]*href="([^"]+)"[^>]*>([^<]+)<\/a>/g;
        let m: RegExpExecArray | null;
        let count = 0;
        while ((m = linkRe.exec(resp.body)) !== null && count < 5) {
          const url = m[1];
          const title = m[2].trim();
          if (url.startsWith('http') && title.length > 5) {
            hits.push({ title: title, url: url, snippet: 'DuckDuckGo', source: 'duckduckgo' });
            count++;
          }
        }
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'DuckDuckGo failed: %{public}s', String(e));
      }
    }
    return hits;
  }
}

// ── HackerNews 搜索(免费,官方 API,无 key) ──

class HackerNewsProvider implements SearchProvider {
  readonly name: string = 'hackernews';
  private http: HttpClient;

  constructor(http: HttpClient) { this.http = http; }

  async search(queries: string[]): Promise<SearchHit[]> {
    const hits: SearchHit[] = [];
    for (let qi = 0; qi < queries.length; qi++) {
      const q: string = queries[qi];
      try {
        const headers: Record<string, string> = { 'Accept': 'application/json' };
        const req: HttpRequest = {
          url: `https://hn.algolia.com/api/v1/search?query=${encodeURIComponent(q)}&tags=story&hitsPerPage=5`,
          method: 'GET',
          headers: headers,
        };
        const resp: HttpResponse = await this.http.fetch(req);
        if (resp.status !== 200) continue;
        const data: HnResponse = JSON.parse(resp.body) as HnResponse;
        if (data.hits === undefined || data.hits === null) continue;
        for (let i = 0; i < data.hits.length; i++) {
          const hit = data.hits[i];
          if (hit.title !== undefined && hit.title !== null) {
            const item: SearchHit = {
              title: hit.title,
              url: hit.url ?? `https://news.ycombinator.com/item?id=${hit.objectID}`,
              snippet: `HN ${hit.points ?? 0} pts`,
              source: 'hackernews',
            };
            hits.push(item);
          }
        }
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'HackerNews failed: %{public}s', String(e));
      }
    }
    return hits;
  }
}

// ── Jina 搜索(免费,可选 key) ──
class JinaProvider implements SearchProvider {
  readonly name: string = 'jina';
  private http: HttpClient;

  constructor(http: HttpClient) { this.http = http; }

  async search(queries: string[]): Promise<SearchHit[]> {
    const hits: SearchHit[] = [];
    for (let qi = 0; qi < queries.length; qi++) {
      const q: string = queries[qi];
      try {
        const headers: Record<string, string> = {
          'Accept': 'application/json',
          'User-Agent': 'Mozilla/5.0',
        };
        const req: HttpRequest = {
          url: `https://s.jina.ai/${encodeURIComponent(q)}`,
          method: 'GET',
          headers: headers,
        };
        const resp: HttpResponse = await this.http.fetch(req);
        if (resp.status !== 200) continue;
        const lines = resp.body.split('\n');
        for (let i = 0; i < lines.length && i < 5; i++) {
          const line = lines[i].trim();
          if (line.startsWith('#')) {
            const title = line.replace(/^#+\s*/, '');
            if (title.length > 5) {
              hits.push({ title: title, url: `https://s.jina.ai/${encodeURIComponent(q)}`, snippet: 'Jina', source: 'jina' });
            }
          }
        }
      } catch (e) {
        hilog.warn(DOMAIN, TAG, 'Jina failed: %{public}s', String(e));
      }
    }
    return hits;
  }
}

class MultiProviderRegistry implements SearchProviderRegistry {
  private http: HttpClient;
  private storage: Storage;
  private cachedEnabled: SearchProvider[] = [];

  constructor(http: HttpClient, storage: Storage) {
    this.http = http;
    this.storage = storage;
    // 免费源始终可用(NewsNow + DuckDuckGo + HackerNews + Jina)
    this.cachedEnabled = [
      createNewsNowProvider(http),
      new DuckDuckGoProvider(http),
      new HackerNewsProvider(http),
      new JinaProvider(http),
    ];
    // 加载需 key 的源
    this.loadKeyedProviders();
  }

  private async loadKeyedProviders(): Promise<void> {
    const providers: SearchProvider[] = [
      createNewsNowProvider(this.http),
      new DuckDuckGoProvider(this.http),
      new HackerNewsProvider(this.http),
      new JinaProvider(this.http),
    ];
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
): SearchProviderRegistry => {
  return new MultiProviderRegistry(http, storage);
};
