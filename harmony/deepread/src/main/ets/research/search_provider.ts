// Tavily search provider — 真实实现,够 spike
// 其他 provider(Zhipu/Brave/Exa)留增量
//
// Tavily API:POST https://api.tavily.com/search
// 认证:Authorization: Bearer <api-key>
// 参考:docs.tavily.com/documentation/api-reference/endpoint/search

import type { HttpClient } from '../platform/http.ts';
import type { SearchProvider, SearchHit } from '../platform/search.ts';

export interface TavilyConfig {
  apiKey: string;
  baseUrl?: string;  // 默认 https://api.tavily.com
}

const TAVILY_DEFAULT_BASE = 'https://api.tavily.com';
const TAVILY_MAX_RESULTS = 10;

export const createTavilyProvider = (http: HttpClient, config: TavilyConfig): SearchProvider => {
  const baseUrl = config.baseUrl ?? TAVILY_DEFAULT_BASE;
  return {
    name: 'tavily',
    search: async (queries: string[]): Promise<SearchHit[]> => {
      // Tavily /search 一次只接受一个 query;Android 实际每个 query 单独调用
      // 简化:取第一个(多个 query 的 round-robin 在 interleaveSearchResults 处理)
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
          max_results: TAVILY_MAX_RESULTS,
          search_depth: 'advanced',
          include_answer: false,
        }),
      });
      if (resp.status !== 200) {
        throw new Error(`Tavily search failed: ${resp.status} ${resp.body.slice(0, 200)}`);
      }
      const data = JSON.parse(resp.body) as {
        results?: Array<{ title: string; url: string; content?: string; published_date?: string }>
      };
      const raw = data.results ?? [];
      const hits: SearchHit[] = [];
      for (const r of raw) {
        const hit: SearchHit = {
          title: r.title,
          url: r.url,
          snippet: r.content ?? null,
          source: 'tavily',
        };
        if (typeof r.published_date === 'string' && r.published_date.length > 0) {
          hit.publishedAt = r.published_date;
        }
        hits.push(hit);
      }
      return hits;
    },
  };
};

// Fallback providers — 始终可用(无需 API key),Android 同样行为(P1-7)
// BingLocal 和 Jina 是 Android 的兜底;这里保留概念性实现
// 真实 http 调用留增量(Jina 需要 https://s.jina.ai/)
export const createFallbackProviders = (): SearchProvider[] => {
  return [
    {
      name: 'jina_fallback',
      search: async (_queries: string[]): Promise<SearchHit[]> => {
        // Jina AI 免费读卡器 https://s.jina.ai/<query> — rate-limited
        // 简化:留后续真实实现
        return [];
      },
    },
  ];
};
