// SearchProvider — 搜索 provider 抽象
// 实现留后续(每 provider 一个 class)

export interface SearchHit {
  title: string;
  url: string;
  snippet: string | null;
  source: string;
}

export interface SearchProvider {
  readonly name: string;
  search(queries: string[]): Promise<SearchHit[]>;
}

export interface SearchProviderRegistry {
  enabled(): SearchProvider[];
  fallback(): SearchProvider[];
}
