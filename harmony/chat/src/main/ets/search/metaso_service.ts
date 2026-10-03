// metaso_service — 秘塔搜索(D-068b)
// Android 基准: search/.../MetasoSearchService.kt(全文 145 行)
import type { SearchRequestContext } from './search_service.ts';
import type { JsonObject } from '../chat/json.ts';
import { makeInputSchemaObj } from '../chat/tool.ts';
import type {
  MetasoOptions, ScrapedResult, SearchCommonOptions, SearchResult,
  SearchService, SearchServiceOptions,
} from './search_service.ts';
import { searchSdkRuntime } from './search_service.ts';
import { asArray, asRecord, requireQuery, strOrNull } from './json_pick.ts';

const asOptions = (o: SearchServiceOptions): MetasoOptions => {
  if (o.type !== 'metaso') throw new Error('MetasoSearchService requires MetasoOptions');
  return o;
};

export const metasoSearchService: SearchService = {
  name: 'Metaso',

  parameters: makeInputSchemaObj(
    { query: { type: 'string', description: 'search keyword' } },
    ['query'],
  ),

  scrapingParameters: null,

  search: async (
    params: JsonObject, commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
    context?: SearchRequestContext,
  ): Promise<SearchResult> => {
    const opts: MetasoOptions = asOptions(serviceOptions);
    const query: string = requireQuery(params);
    const resp = await searchSdkRuntime().http.fetch({
      url: 'https://metaso.cn/api/v1/search',
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${opts.apiKey}`,
        'Accept': 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        q: query,
        scope: 'webpage',
        size: commonOptions.resultSize,
        includeSummary: false,
      }),
    }, context);
    if (resp.status < 200 || resp.status >= 300) {
      throw new Error(`Metaso search request failed with code ${resp.status}`);
    }
    if (resp.body.length === 0) throw new Error('Failed to get response body');
    let payload: JsonObject;
    try {
      payload = asRecord(JSON.parse(resp.body) as JsonObject);
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      throw new Error(`Failed to decode Metaso response (${resp.body.length} chars): ${msg}`);
    }
    return {
      answer: null,
      items: asArray(payload['webpages']).map((v) => {
        const o: JsonObject = asRecord(v);
        return {
          title: strOrNull(o['title']) ?? '',
          url: strOrNull(o['link']) ?? '',
          text: strOrNull(o['snippet']) ?? '',
          publishedAt: null,
          images: [],
        };
      }),
    };
  },

  scrape: (
    _params: JsonObject, _commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
  ): Promise<ScrapedResult> => {
    asOptions(serviceOptions);
    return Promise.reject(new Error('Scraping is not supported for Metaso'));
  },
};
