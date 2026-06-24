// SearchRegistry — SearchProviderRegistry 真实实现
// 对照设计 §8.6。ArkTS 兼容:class implements SearchProviderRegistry。
// Tavily(enabled)+ fallback(jina,无 key)。apiKey 从 Storage 异步读后同步缓存。

import type { HttpClient, Storage, SearchProviderRegistry, SearchProvider, SearchHit } from '@amber/deepread-domain';
import { createTavilyProvider } from '@amber/deepread-domain';

// fallback provider class(ArkTS 不允许对象字面量实现 interface)
class JinaFallbackProvider implements SearchProvider {
  readonly name: string = 'jina_fallback';

  async search(_queries: string[]): Promise<SearchHit[]> {
    // TODO(jina): 接真实 s.jina.ai reader
    return [];
  }
}

class TavilyAndFallbackRegistry implements SearchProviderRegistry {
  private http: HttpClient;
  private storage: Storage;
  private cachedEnabled: SearchProvider[] = [];

  constructor(http: HttpClient, storage: Storage) {
    this.http = http;
    this.storage = storage;
    // 异步加载 Tavily key 后缓存
    this.loadEnabled();
  }

  private async loadEnabled(): Promise<void> {
    const apiKey: string = await this.storage.get<string>('tavily_api_key', '');
    if (apiKey.length > 0) {
      this.cachedEnabled = [createTavilyProvider(this.http, { apiKey: apiKey })];
    }
  }

  enabled(): SearchProvider[] {
    return this.cachedEnabled;
  }

  fallback(): SearchProvider[] {
    return [new JinaFallbackProvider()];
  }
}

export const createTavilyAndFallbackRegistry = (
  http: HttpClient,
  storage: Storage,
): SearchProviderRegistry => {
  return new TavilyAndFallbackRegistry(http, storage);
};
