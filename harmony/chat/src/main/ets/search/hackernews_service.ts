// hackernews_service — Hacker News(Algolia)内置源(D-066)
// Android 基准: search/.../HackerNewsSearchService.kt(全文 65 行;普通 object 非 SearchService)
import type { JsonObject, JsonValue } from '../chat/json.ts';
import type { SearchCommonOptions, SearchRequestContext, SearchResult, SearchResultItem } from './search_service.ts';
import { searchSdkRuntime } from './search_service.ts';
import { buildQuery } from './url_codec.ts';

const asRecord = (v: JsonValue | undefined): JsonObject =>
  (typeof v === 'object' && v !== null && !Array.isArray(v)) ? v as JsonObject : {};

const asArray = (v: JsonValue | undefined): JsonValue[] => Array.isArray(v) ? v : [];

const strOrNull = (v: JsonValue | undefined): string | null =>
  typeof v === 'string' ? v : null;

const numOrNull = (v: JsonValue | undefined): number | null =>
  typeof v === 'number' ? v : null;

export const hackerNewsSearch = async (
  query: string, commonOptions: SearchCommonOptions,
  context?: SearchRequestContext,
): Promise<SearchResult> => {
  const limit: number = Math.min(20, Math.max(1, commonOptions.resultSize)); // coerceIn(1,20)
  const url: string = 'https://hn.algolia.com/api/v1/search?' + buildQuery([
    ['query', query],
    ['tags', 'story'],
    ['hitsPerPage', String(limit)],
  ]);
  const resp = await searchSdkRuntime().http.fetch({ url, method: 'GET', headers: {} }, context);
  if (resp.status < 200 || resp.status >= 300) {
    throw new Error(`Hacker News request failed #${resp.status}`);
  }
  const payload: JsonObject = asRecord(JSON.parse(resp.body) as JsonValue);
  const items: SearchResultItem[] = [];
  for (const v of asArray(payload['hits'])) {
    const o: JsonObject = asRecord(v);
    const title: string | null = strOrNull(o['title']) ?? strOrNull(o['story_title']);
    if (title === null) continue; // mapNotNull return@mapNotNull null
    const objectId: string = strOrNull(o['objectID']) ?? '';
    const points: number = numOrNull(o['points']) ?? 0;
    const comments: number = numOrNull(o['num_comments']) ?? 0;
    items.push({
      title,
      url: strOrNull(o['url']) ?? `https://news.ycombinator.com/item?id=${objectId}`,
      text: `HN points=${points}, comments=${comments}`,
      publishedAt: strOrNull(o['created_at']),
      images: [],
    });
  }
  return { answer: null, items };
};
