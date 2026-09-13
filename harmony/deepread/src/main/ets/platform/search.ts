// SearchProvider — 搜索 provider 抽象
// 实现留后续(每 provider 一个 class)

import type { AbortSignalLike } from './runtime_api.ts';

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
  // signal 可选:真实取消底层搜索请求(R20)。既有实现可忽略,调用方仍可不传。
  search(queries: string[], signal?: AbortSignalLike): Promise<SearchHit[]>;
}

export interface SearchProviderRegistry {
  enabled(): SearchProvider[];
  fallback(): SearchProvider[];
}
