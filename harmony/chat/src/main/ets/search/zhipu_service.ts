// zhipu_service — 智谱搜索(D-068a)
// Android 基准: search/.../ZhipuSearchService.kt(全文 128 行)
// 偏差登记:okhttp body 可空 → Port body string;空串视为缺失('Failed to get response body')
import type { SearchRequestContext } from './search_service.ts';
import type { JsonObject } from '../chat/json.ts';
import { makeInputSchemaObj } from '../chat/tool.ts';
import type {
  ScrapedResult, SearchCommonOptions, SearchResult, SearchService, SearchServiceOptions,
  ZhipuOptions,
} from './search_service.ts';
import { searchSdkRuntime } from './search_service.ts';
import { asArray, asRecord, requireQuery, strOrNull } from './json_pick.ts';

const asOptions = (o: SearchServiceOptions): ZhipuOptions => {
  if (o.type !== 'zhipu') throw new Error('ZhipuSearchService requires ZhipuOptions');
  return o;
};

export const zhipuSearchService: SearchService = {
  name: 'Zhipu',

  parameters: makeInputSchemaObj(
    { query: { type: 'string', description: 'search keyword' } },
    ['query'],
  ),

  scrapingParameters: null,

  search: async (
    params: JsonObject, commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
    context?: SearchRequestContext,
  ): Promise<SearchResult> => {
    const opts: ZhipuOptions = asOptions(serviceOptions);
    const query: string = requireQuery(params);
    const resp = await searchSdkRuntime().http.fetch({
      url: 'https://open.bigmodel.cn/api/paas/v4/web_search',
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${opts.apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        search_query: query,
        search_engine: 'search_std',
        count: commonOptions.resultSize,
      }),
    }, context);
    if (resp.status < 200 || resp.status >= 300) {
      throw new Error(`Zhipu response failed #${resp.status}`);
    }
    // response.body?.string() ?: error(...)
    if (resp.body.length === 0) throw new Error('Failed to get response body');
    let payload: JsonObject;
    try {
      payload = asRecord(JSON.parse(resp.body) as JsonObject);
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      throw new Error(`Failed to decode Zhipu response (${resp.body.length} chars): ${msg}`);
    }
    return {
      answer: null,
      items: asArray(payload['search_result']).map((v) => {
        const o: JsonObject = asRecord(v);
        return {
          title: strOrNull(o['title']) ?? '',
          url: strOrNull(o['link']) ?? '',
          text: strOrNull(o['content']) ?? '',
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
    return Promise.reject(new Error('Scraping is not supported for Zhipu'));
  },
};
