// bocha_service — 博查搜索(D-068c)
// Android 基准: search/.../BochaSearchService.kt(全文 182 行)
import type { SearchRequestContext } from './search_service.ts';
import type { JsonObject } from '../chat/json.ts';
import { makeInputSchemaObj } from '../chat/tool.ts';
import type {
  BochaOptions, ScrapedResult, SearchCommonOptions, SearchResult,
  SearchService, SearchServiceOptions,
} from './search_service.ts';
import { searchSdkRuntime } from './search_service.ts';
import { asArray, asRecord, numOrNull, requireQuery, strOrNull } from './json_pick.ts';

const asOptions = (o: SearchServiceOptions): BochaOptions => {
  if (o.type !== 'bocha') throw new Error('BochaSearchService requires BochaOptions');
  return o;
};

export const bochaSearchService: SearchService = {
  name: 'Bocha',

  parameters: makeInputSchemaObj(
    { query: { type: 'string', description: 'search keyword' } },
    ['query'],
  ),

  scrapingParameters: null,

  search: async (
    params: JsonObject, commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
    context?: SearchRequestContext,
  ): Promise<SearchResult> => {
    const opts: BochaOptions = asOptions(serviceOptions);
    const query: string = requireQuery(params);
    const resp = await searchSdkRuntime().http.fetch({
      url: 'https://api.bochaai.com/v1/web-search',
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${opts.apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        query,
        summary: opts.summary,
        count: commonOptions.resultSize,
      }),
    }, context);
    if (resp.status < 200 || resp.status >= 300) {
      throw new Error(`Bocha response failed #${resp.status}`);
    }
    let payload: JsonObject;
    try {
      payload = asRecord(JSON.parse(resp.body) as JsonObject);
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      throw new Error(`Failed to decode Bocha response (${resp.body.length} chars): ${msg}`);
    }
    // code != 200 → API error(msg ?: 'Unknown error')
    const code: number = numOrNull(payload['code']) ?? 0;
    if (code !== 200) {
      throw new Error(`Bocha API error: ${strOrNull(payload['msg']) ?? 'Unknown error'}`);
    }
    const value = asRecord(asRecord(payload['data'])['webPages'])['value'];
    return {
      answer: null,
      items: asArray(value).map((v) => {
        const o: JsonObject = asRecord(v);
        return {
          title: strOrNull(o['name']) ?? '',
          url: strOrNull(o['url']) ?? '',
          text: strOrNull(o['summary']) ?? strOrNull(o['snippet']) ?? '',
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
    return Promise.reject(new Error('Scraping is not supported for Bocha'));
  },
};
