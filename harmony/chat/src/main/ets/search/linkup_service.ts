// linkup_service — LinkUp 搜索/抓取(D-068b)
// Android 基准: search/.../LinkUpService.kt(全文 174 行;含 scrape)
// 偏差登记:Log.i(query) 不移植(宪章隐私)
import type { SearchRequestContext } from './search_service.ts';
import type { JsonObject } from '../chat/json.ts';
import { makeInputSchemaObj } from '../chat/tool.ts';
import type {
  LinkUpOptions, ScrapedResult, SearchCommonOptions, SearchResult,
  SearchService, SearchServiceOptions,
} from './search_service.ts';
import { searchSdkRuntime } from './search_service.ts';
import { asArray, asRecord, requireQuery, strOrNull } from './json_pick.ts';

const asOptions = (o: SearchServiceOptions): LinkUpOptions => {
  if (o.type !== 'linkup') throw new Error('LinkUpService requires LinkUpOptions');
  return o;
};

export const linkUpService: SearchService = {
  name: 'LinkUp',

  parameters: makeInputSchemaObj(
    { query: { type: 'string', description: 'search keyword' } },
    ['query'],
  ),

  scrapingParameters: makeInputSchemaObj(
    { url: { type: 'string', description: 'url to scrape' } },
    ['url'],
  ),

  search: async (
    params: JsonObject, commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
    context?: SearchRequestContext,
  ): Promise<SearchResult> => {
    const opts: LinkUpOptions = asOptions(serviceOptions);
    const query: string = requireQuery(params);
    // keyRoulette.next(apiKey, id.toString())
    const apiKey: string = searchSdkRuntime().keyRoulette.next(opts.apiKey, opts.id);
    const resp = await searchSdkRuntime().http.fetch({
      url: 'https://api.linkup.so/v1/search',
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        q: query,
        depth: opts.depth,
        outputType: 'sourcedAnswer',
        includeImages: 'false',
      }),
    }, context);
    if (resp.status < 200 || resp.status >= 300) {
      throw new Error(`response failed #${resp.status}: ${resp.body}`);
    }
    const payload: JsonObject = asRecord(JSON.parse(resp.body) as JsonObject);
    return {
      answer: strOrNull(payload['answer']),
      items: asArray(payload['sources'])
        .slice(0, commonOptions.resultSize)
        .map((v) => {
          const o: JsonObject = asRecord(v);
          return {
            title: strOrNull(o['name']) ?? '',
            url: strOrNull(o['url']) ?? '',
            text: strOrNull(o['snippet']) ?? '',
            publishedAt: null,
            images: [],
          };
        }),
    };
  },

  scrape: async (
    params: JsonObject, _commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
    context?: SearchRequestContext,
  ): Promise<ScrapedResult> => {
    const opts: LinkUpOptions = asOptions(serviceOptions);
    const urlv = params['url'];
    const url: string | null =
      typeof urlv === 'string' ? urlv
        : (typeof urlv === 'number' || typeof urlv === 'boolean') ? String(urlv) : null;
    if (url === null) throw new Error('url is required');
    const apiKey: string = searchSdkRuntime().keyRoulette.next(opts.apiKey, opts.id);
    const resp = await searchSdkRuntime().http.fetch({
      url: 'https://api.linkup.so/v1/fetch',
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        url,
        includeRawHtml: false,
        renderJs: false,
        extractImages: false,
      }),
    }, context);
    if (resp.status < 200 || resp.status >= 300) {
      throw new Error(`response failed #${resp.status}: ${resp.body}`);
    }
    const payload: JsonObject = asRecord(JSON.parse(resp.body) as JsonObject);
    return {
      urls: [{
        url,
        content: strOrNull(payload['markdown']) ?? '',
        metadata: null,
      }],
    };
  },
};
