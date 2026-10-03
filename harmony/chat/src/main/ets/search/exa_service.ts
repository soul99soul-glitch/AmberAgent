// exa_service — Exa 搜索(D-068b)
// Android 基准: search/.../ExaSearchService.kt(全文 178 行)
import type { SearchRequestContext } from './search_service.ts';
import type { JsonObject } from '../chat/json.ts';
import { makeInputSchemaObj } from '../chat/tool.ts';
import type {
  ExaOptions, ScrapedResult, SearchCommonOptions, SearchResult,
  SearchService, SearchServiceOptions,
} from './search_service.ts';
import { searchSdkRuntime } from './search_service.ts';
import { asArray, asRecord, primitiveContentOrNull, requireQuery, strOrNull } from './json_pick.ts';

const asOptions = (o: SearchServiceOptions): ExaOptions => {
  if (o.type !== 'exa') throw new Error('ExaSearchService requires ExaOptions');
  return o;
};

export const exaSearchService: SearchService = {
  name: 'Exa',

  parameters: makeInputSchemaObj(
    {
      query: { type: 'string', description: 'search keyword' },
      type: {
        type: 'string',
        description: 'Search type: fast (quick results), auto (default, balanced), deep (synthesized answer with citations)',
        enum: ['fast', 'auto', 'deep'],
      },
    },
    ['query'],
  ),

  scrapingParameters: null,

  search: async (
    params: JsonObject, commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
    context?: SearchRequestContext,
  ): Promise<SearchResult> => {
    const opts: ExaOptions = asOptions(serviceOptions);
    const query: string = requireQuery(params);
    const apiKey: string = searchSdkRuntime().keyRoulette.next(opts.apiKey, opts.id);
    const resp = await searchSdkRuntime().http.fetch({
      url: 'https://api.exa.ai/search',
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        query,
        numResults: commonOptions.resultSize,
        type: primitiveContentOrNull(params, 'type') ?? 'auto',
        contents: { text: true },
      }),
    }, context);
    if (resp.status < 200 || resp.status >= 300) {
      throw new Error(`Exa response failed #${resp.status}`);
    }
    let payload: JsonObject;
    try {
      payload = asRecord(JSON.parse(resp.body) as JsonObject);
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      throw new Error(`Failed to decode Exa response (${resp.body.length} chars): ${msg}`);
    }
    return {
      answer: strOrNull(asRecord(payload['output'])['content']),
      items: asArray(payload['results']).map((v) => {
        const o: JsonObject = asRecord(v);
        return {
          title: strOrNull(o['title']) ?? '',
          url: strOrNull(o['url']) ?? '',
          text: strOrNull(o['text']) ?? '',
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
    return Promise.reject(new Error('Scraping is not supported for Exa'));
  },
};
