// Tavily search provider — 真实实现,够 spike
// 其他 provider(Zhipu/Brave/Exa)留增量
//
// Tavily API:POST https://api.tavily.com/search
// 认证:Authorization: Bearer <api-key>
// 参考:docs.tavily.com/documentation/api-reference/endpoint/search

import type { HttpClient } from '../platform/http.ts';
import type { AbortSignalLike } from '../platform/runtime_api.ts';
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
    search: async (queries: string[], signal?: AbortSignalLike): Promise<SearchHit[]> => {
      // Tavily /search 一次只接受一个 query:逐个执行后按 URL 去重合并
      // (原实现只取 queries[0],多变体查询实际只查了一个,来源召回偏窄);
      // 单个 query 失败只丢该 query,不让整个 provider 失败
      // signal 透传 → 取消时底层 HTTP 真实中止(R20)
      const merged: SearchHit[] = [];
      const seenUrls = new Set<string>();
      let lastError: Error | null = null;
      for (const query of queries) {
        if (query.trim().length === 0) continue;
        // 取消已发生:不再发起下一个 query 请求(真实网络取消)
        if (signal !== undefined && signal.aborted) break;
        try {
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
          }, { signal });
          if (resp.status !== 200) {
            throw new Error(`Tavily search failed: ${resp.status} ${resp.body.slice(0, 200)}`);
          }
          const data = JSON.parse(resp.body) as {
            results?: Array<{ title: string; url: string; content?: string; published_date?: string }>
          };
          const raw = data.results ?? [];
          for (const r of raw) {
            if (seenUrls.has(r.url)) continue;
            seenUrls.add(r.url);
            const hit: SearchHit = {
              title: r.title,
              url: r.url,
              snippet: r.content ?? null,
              source: 'tavily',
            };
            if (typeof r.published_date === 'string' && r.published_date.length > 0) {
              hit.publishedAt = r.published_date;
            }
            merged.push(hit);
          }
        } catch (e) {
          // 取消:立即停止,不把取消当 provider 失败继续下一个 query
          if (signal !== undefined && signal.aborted) break;
          // 该 query 失败:跳过继续下一个;但全部失败时保留抛错语义
          // (坏 API key 等配置错误不能静默成空结果)
          lastError = e instanceof Error ? e : new Error(String(e));
        }
      }
      if (merged.length === 0 && lastError !== null && !(signal !== undefined && signal.aborted)) throw lastError;
      return merged;
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
      search: async (_queries: string[], _signal?: AbortSignalLike): Promise<SearchHit[]> => {
        // Jina AI 免费读卡器 https://s.jina.ai/<query> — rate-limited
        // 简化:留后续真实实现
        return [];
      },
    },
  ];
};
