// serper_service — Serper 搜索(D-068a)
// Android 基准: search/.../SerperSearchService.kt(全文 110 行)
import type { SearchRequestContext } from './search_service.ts';
import type { JsonObject } from '../chat/json.ts';
import { makeInputSchemaObj } from '../chat/tool.ts';
import type {
  ScrapedResult, SearchCommonOptions, SearchResult, SearchResultItem,
  SearchService, SearchServiceOptions, SerperOptions,
} from './search_service.ts';
import { searchSdkRuntime } from './search_service.ts';
import { asArray, asRecord, requireQuery, strOrNull, stringContentOrNull } from './json_pick.ts';

const asOptions = (o: SearchServiceOptions): SerperOptions => {
  if (o.type !== 'serper') throw new Error('SerperSearchService requires SerperOptions');
  return o;
};

export const serperSearchService: SearchService = {
  name: 'Serper',

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
    const opts: SerperOptions = asOptions(serviceOptions);
    if (opts.apiKey.trim().length === 0) throw new Error('Serper API key is required');
    const query: string = requireQuery(params);
    const topic: string | null = stringContentOrNull(params, 'topic');
    const endpoint: string = topic === 'news' ? 'news' : 'search';
    const num: number = Math.min(20, Math.max(1, commonOptions.resultSize)); // coerceIn(1,20)
    const resp = await searchSdkRuntime().http.fetch({
      url: `https://google.serper.dev/${endpoint}`,
      method: 'POST',
      headers: {
        'X-API-KEY': opts.apiKey,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ q: query, num }),
    }, context);
    if (resp.status < 200 || resp.status >= 300) {
      throw new Error(`Serper request failed #${resp.status}`);
    }
    const payload: JsonObject = asRecord(JSON.parse(resp.body) as JsonObject);
    // payload.news ?: payload.organic ?: emptyList()
    const newsArr = payload['news'];
    const organicArr = payload['organic'];
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
    return Promise.reject(new Error('Scraping is not supported for Serper'));
  },
};
