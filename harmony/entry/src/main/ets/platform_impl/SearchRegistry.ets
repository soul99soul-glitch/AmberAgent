// SearchRegistry — SearchProviderRegistry 真实实现
// 对照设计 §8.6:Tavily(enabled)+ fallback providers(jina 等)
// deepread 已有 createTavilyProvider / createFallbackProviders(Plan 3),
// 这里注入真实 HttpClient + 从 preferences 读 Tavily API key。

import type { HttpClient, Storage, SearchProviderRegistry, SearchProvider } from '@amber/deepread-domain';
import { createTavilyProvider } from '@amber/deepread-domain/src/main/ets/research/search_provider.ts';

export const createTavilyAndFallbackRegistry = (
  http: HttpClient,
  storage: Storage,
): SearchProviderRegistry => {
  const enabled = async (): Promise<SearchProvider[]> => {
    const apiKey = (await storage.getString('tavily_api_key')) ?? '';
    if (apiKey.length === 0) {
      // 无 key → 不暴露 Tavily(只用 fallback)
      return [];
    }
    return [createTavilyProvider(http, { apiKey })];
  };

  const fallback = (): SearchProvider[] => {
    // 对照 P1-7:始终可用的兜底(jina 等,无 key)
    // TODO(phase6): 接真实 jina reader(s.jina.ai)实现
    return [{
      name: 'jina_fallback',
      search: async () => [],
    }];
  };

  // 注意:enabled() 是 async,但 SearchProviderRegistry.enabled() 契约是同步返回 SearchProvider[]。
  // TODO(arkts/契约修正): 需在 deepread 的 SearchProviderRegistry 改 enabled 为 Promise,或此处
  // 在容器初始化时提前 resolve(apiKey 同步缓存)。当前简化:容器构造时同步取 key(若已缓存)。
  // 为保持契约同步,用同步缓存:
  let cachedEnabled: SearchProvider[] = [];
  void enabled().then(ps => { cachedEnabled = ps; });

  return {
    enabled: () => cachedEnabled,
    fallback,
  };
};
