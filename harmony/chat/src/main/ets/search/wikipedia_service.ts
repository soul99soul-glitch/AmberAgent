// wikipedia_service — Wikipedia 内置源(D-066)
// Android 基准: search/.../WikipediaSearchService.kt(全文 74 行;普通 object 非 SearchService)
import type { JsonObject, JsonValue } from '../chat/json.ts';
import type { SearchCommonOptions, SearchRequestContext, SearchResult, SearchResultItem } from './search_service.ts';
import { searchSdkRuntime } from './search_service.ts';
import { buildQuery, javaUrlEncodeForm } from './url_codec.ts';

const TAG_STRIP_RE: RegExp = /<[^>]+>/g; // Regex("<[^>]+>") 逐字

const asRecord = (v: JsonValue | undefined): JsonObject =>
  (typeof v === 'object' && v !== null && !Array.isArray(v)) ? v as JsonObject : {};

const asArray = (v: JsonValue | undefined): JsonValue[] => Array.isArray(v) ? v : [];

const strOrNull = (v: JsonValue | undefined): string | null =>
  typeof v === 'string' ? v : null;

export const wikipediaSearch = async (
  query: string, commonOptions: SearchCommonOptions,
  context?: SearchRequestContext,
): Promise<SearchResult> => {
  // any { it.code in 0x4E00..0x9FFF }(UTF-16 code unit 语义 = charCodeAt)
  let hasCjk: boolean = false;
  for (let i = 0; i < query.length; i++) {
    const c: number = query.charCodeAt(i);
    if (c >= 0x4E00 && c <= 0x9FFF) {
      hasCjk = true;
      break;
    }
  }
  const domain: string = hasCjk ? 'zh.wikipedia.org' : 'en.wikipedia.org';
  const host: string = `https://${domain}/w/api.php`;
  const limit: number = Math.min(10, Math.max(1, commonOptions.resultSize)); // coerceIn(1,10)
  const url: string = host + '?' + buildQuery([
    ['action', 'query'],
    ['list', 'search'],
    ['srsearch', query],
    ['srlimit', String(limit)],
    ['format', 'json'],
    ['utf8', '1'],
  ]);
  const resp = await searchSdkRuntime().http.fetch({
    url,
    method: 'GET',
    headers: { 'User-Agent': 'AmberAgent/1.0 search (Android)' },
  }, context);
  if (resp.status < 200 || resp.status >= 300) {
    throw new Error(`Wikipedia request failed #${resp.status}`);
  }
  const payload: JsonObject = asRecord(JSON.parse(resp.body) as JsonValue);
  const base: string = `https://${domain}/wiki/`; // "${url.scheme}://${url.host}/wiki/"
  const items: SearchResultItem[] = asArray(asRecord(payload['query'])['search'])
    .map((v: JsonValue): SearchResultItem => {
      const o: JsonObject = asRecord(v);
      const title: string = strOrNull(o['title']) ?? '';
      const snippet: string = strOrNull(o['snippet']) ?? '';
      return {
        title,
        // URLEncoder.encode(title.replace(' ','_'),"UTF-8")
        url: base + javaUrlEncodeForm(title.replace(/ /g, '_')),
        text: snippet.replace(TAG_STRIP_RE, ''),
        publishedAt: strOrNull(o['timestamp']),
        images: [],
      };
    });
  return { answer: null, items };
};
