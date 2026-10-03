// serpapi_service — SerpAPI 搜索(D-068a)
// Android 基准: search/.../SerpApiSearchService.kt(全文 111 行)
import type { SearchRequestContext } from './search_service.ts';
import type { JsonObject } from '../chat/json.ts';
import { makeInputSchemaObj } from '../chat/tool.ts';
import type {
  ScrapedResult, SearchCommonOptions, SearchResult, SearchResultItem,
  SearchService, SearchServiceOptions, SerpApiOptions,
} from './search_service.ts';
import { searchSdkRuntime } from './search_service.ts';
import { asRecord, requireQuery, strOrNull, stringContentOrNull } from './json_pick.ts';
import { buildQuery } from './url_codec.ts';

const asOptions = (o: SearchServiceOptions): SerpApiOptions => {
  if (o.type !== 'serpapi') throw new Error('SerpApiSearchService requires SerpApiOptions');
  return o;
};

export const serpApiSearchService: SearchService = {
  name: 'SerpAPI',

  parameters: makeInputSchemaObj(
    {
      query: { type: 'string', description: 'search keyword' },
      topic: { type: 'string', description: 'search topic, use news for recent events' },
    },
    ['query'],
  ),

  scrapingParameters: null,

  search: async (
    params: JsonObject, commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
    context?: SearchRequestContext,
  ): Promise<SearchResult> => {
    const opts: SerpApiOptions = asOptions(serviceOptions);
    if (opts.apiKey.trim().length === 0) throw new Error('SerpAPI key is required');
    const query: string = requireQuery(params);
    const topic: string | null = stringContentOrNull(params, 'topic');
    const num: number = Math.min(20, Math.max(1, commonOptions.resultSize)); // coerceIn(1,20)
    const qp: Array<[string, string]> = [
      ['engine', 'google'],
      ['q', query],
      ['api_key', opts.apiKey],
      ['num', String(num)],
    ];
    if (topic === 'news') qp.push(['tbm', 'nws']);
    const resp = await searchSdkRuntime().http.fetch({
      url: `https://serpapi.com/search.json?${buildQuery(qp)}`,
      method: 'GET',
      headers: {},
    }, context);
    if (resp.status < 200 || resp.status >= 300) {
      throw new Error(`SerpAPI request failed #${resp.status}`);
    }
    const payload: JsonObject = asRecord(JSON.parse(resp.body) as JsonObject);
    // payload.newsResults ?: payload.organicResults ?: emptyList()
    const newsArr = payload['news_results'];
    const organicArr = payload['organic_results'];
    const raw = Array.isArray(newsArr) ? newsArr : (Array.isArray(organicArr) ? organicArr : []);
    const items: SearchResultItem[] = raw.map((v): SearchResultItem => {
      const o: JsonObject = asRecord(v);
      return {
        title: strOrNull(o['title']) ?? '',
        url: strOrNull(o['link']) ?? '',
        text: strOrNull(o['snippet']) ?? '',
        publishedAt: strOrNull(o['date']),
        images: [],
      };
    });
    return { answer: null, items: items.slice(0, commonOptions.resultSize) };
  },

  scrape: (
    _params: JsonObject, _commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
  ): Promise<ScrapedResult> => {
    asOptions(serviceOptions);
    return Promise.reject(new Error('Scraping is not supported for SerpAPI'));
  },
};
