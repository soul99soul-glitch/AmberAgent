// SearchProvider — 搜索 provider 抽象
// 实现留后续(每 provider 一个 class)

export interface SearchHit {
  title: string;
  url: string;
  snippet: string | null;
  source: string;
  // 发布日期(provider 有则填,如 Tavily published_date;无则省略)
  publishedAt?: string | null;
}

export interface SearchProvider {
  readonly name: string;
  search(queries: string[]): Promise<SearchHit[]>;
}

export interface SearchProviderRegistry {
  enabled(): SearchProvider[];
  fallback(): SearchProvider[];
}
