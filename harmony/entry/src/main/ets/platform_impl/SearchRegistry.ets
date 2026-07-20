// SearchRegistry — 多搜索 provider 并发 + 合并去重
// 对照 Android:20 个搜索源,这里实现免费无需 key 的(NewsNow/DuckDuckGo/HackerNews/Jina/Tavily)

import type { HttpClient, HttpRequest, HttpResponse, Storage, SearchProviderRegistry, SearchProvider, SearchHit } from '@amber/deepread-domain';
import { createTavilyProvider } from '@amber/deepread-domain';
import { createNewsNowProvider } from './NewsNowProvider.ets';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Search';

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
        // 简化解析:提取 <a> 链接(不含 JS 渲染)
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
interface HnHit { title?: string; url?: string; objectID?: number; points?: number; }
interface HnResponse { hits?: HnHit[]; }

class HackerNewsProvider implements SearchProvider {
  readonly name: string = 'hackernews';
  private http: HttpClient;

  constructor(http: HttpClient) { this.http = http; }

  async search(queries: string[]): Promise<SearchHit[]> {
    const hits: SearchHit[] = [];
    for (let qi = 0; qi < queries.length; qi++) {
      const q: string = queries[qi];
      try {
        const headers: Record<string, string> = {
          'Accept': 'application/json',
        };
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
        // Jina 返回 markdown,提取标题行
        const lines = resp.body.split('\n');
        for (let i = 0; i < lines.length && i < 5; i++) {
          const line = lines[i].trim();
          if (line.startsWith('#')) {
            const title = line.replace(/^#+\s*/, '');
            if (title.length > 5) {
              const item: SearchHit = { title: title, url: `https://s.jina.ai/${encodeURIComponent(q)}`, snippet: 'Jina', source: 'jina' };
              hits.push(item);
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

class TavilyAndFallbackRegistry implements SearchProviderRegistry {
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
    // Tavily 有 key 时追加
    this.loadTavily();
  }

  private async loadTavily(): Promise<void> {
    const apiKey: string = await this.storage.get<string>('tavily_api_key', '');
    if (apiKey.length > 0) {
      this.cachedEnabled = [
        createNewsNowProvider(this.http),
        new DuckDuckGoProvider(this.http),
        new HackerNewsProvider(this.http),
        new JinaProvider(this.http),
        createTavilyProvider(this.http, { apiKey: apiKey }),
      ];
    }
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
  return new TavilyAndFallbackRegistry(http, storage);
};
