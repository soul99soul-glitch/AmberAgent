// amberagent_service — AmberAgent 搜索(D-068a)
// Android 基准: search/.../AmberAgentSearchService.kt(全文 110 行)
// 偏差登记:Log.i(TAG,"search: $query") 不移植(宪章:日志不落用户查询等隐私数据)
import type { SearchRequestContext } from './search_service.ts';
import type { JsonObject } from '../chat/json.ts';
import { makeInputSchemaObj } from '../chat/tool.ts';
import type {
  AmberAgentSearchOptions, ScrapedResult, SearchCommonOptions, SearchResult,
  SearchService, SearchServiceOptions,
} from './search_service.ts';
import { searchSdkRuntime } from './search_service.ts';
import { asArray, asRecord, requireQuery, strOrNull } from './json_pick.ts';

const asOptions = (o: SearchServiceOptions): AmberAgentSearchOptions => {
  if (o.type !== 'amber_agent') throw new Error('AmberAgentSearchService requires AmberAgentSearchOptions');
  return o;
};

export const amberAgentSearchService: SearchService = {
  name: 'AmberAgent',

  parameters: makeInputSchemaObj(
    { query: { type: 'string', description: 'search keyword' } },
    ['query'],
  ),

  scrapingParameters: null,

  search: async (
    params: JsonObject, commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
    context?: SearchRequestContext,
  ): Promise<SearchResult> => {
    const opts: AmberAgentSearchOptions = asOptions(serviceOptions);
    const query: string = requireQuery(params);
    // 四键恒 JsonPrimitive 字符串(includeImages 'false' 字符串逐字)
    const body: JsonObject = {
      q: query,
      depth: opts.depth,
      outputType: 'sourcedAnswer',
      includeImages: 'false',
    };
    const resp = await searchSdkRuntime().http.fetch({
      url: 'https://api.rikka-ai.com/v1/search',
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${opts.apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(body),
    }, context);
    if (resp.status < 200 || resp.status >= 300) {
      throw new Error(`response failed #${resp.status}: ${resp.body}`);
    }
    const payload: JsonObject = asRecord(JSON.parse(resp.body) as JsonObject);
    return {
      answer: strOrNull(payload['answer']),
      items: asArray(payload['sources'])
        .slice(0, commonOptions.resultSize)
        .map((v): { title: string; url: string; text: string; publishedAt: null; images: string[] } => {
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
    _params: JsonObject, _commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
  ): Promise<ScrapedResult> => {
    asOptions(serviceOptions);
    // Android scrape 用 error()(suspend 内 = 异步抛出;非 Result.failure)
    throw new Error('AmberAgent does not support scraping');
  },
};
