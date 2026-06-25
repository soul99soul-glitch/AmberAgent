// SearchRegistry — SearchProviderRegistry 真实实现
// 对照设计 §8.6。ArkTS 兼容:class implements SearchProviderRegistry。
// Tavily(enabled)+ fallback(jina,无 key)。apiKey 从 Storage 异步读后同步缓存。

import type { HttpClient, Storage, SearchProviderRegistry, SearchProvider, SearchHit } from '@amber/deepread-domain';
import { createTavilyProvider } from '@amber/deepread-domain';
import { createNewsNowProvider } from './NewsNowProvider.ets';

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
    // NewsNow 作为始终可用的 provider(无 key 需求)
    this.cachedEnabled = [createNewsNowProvider(http)];
    // 异步加载 Tavily key 后追加
    this.loadTavily();
  }

  private async loadTavily(): Promise<void> {
    const apiKey: string = await this.storage.get<string>('tavily_api_key', '');
    if (apiKey.length > 0) {
      this.cachedEnabled = [createNewsNowProvider(this.http), createTavilyProvider(this.http, { apiKey: apiKey })];
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
