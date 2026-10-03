// ollama_service — Ollama 搜索(D-068a)
// Android 基准: search/.../OllamaSearchService.kt(全文 110 行)
// 偏差登记:okhttp response.message 无对应字段(HttpClient Port 无状态文本)→ 尾空串
import type { SearchRequestContext } from './search_service.ts';
import type { JsonObject } from '../chat/json.ts';
import { makeInputSchemaObj } from '../chat/tool.ts';
import type {
  OllamaOptions, ScrapedResult, SearchCommonOptions, SearchResult,
  SearchService, SearchServiceOptions,
} from './search_service.ts';
import { searchSdkRuntime } from './search_service.ts';
import { asArray, asRecord, requireQuery, strOrNull } from './json_pick.ts';

const asOptions = (o: SearchServiceOptions): OllamaOptions => {
  if (o.type !== 'ollama') throw new Error('OllamaSearchService requires OllamaOptions');
  return o;
};

export const ollamaSearchService: SearchService = {
  name: 'Ollama',

  parameters: makeInputSchemaObj(
    { query: { type: 'string', description: 'search keyword' } },
    ['query'],
  ),

  scrapingParameters: null,

  search: async (
    params: JsonObject, commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
    context?: SearchRequestContext,
  ): Promise<SearchResult> => {
    const opts: OllamaOptions = asOptions(serviceOptions);
    const query: string = requireQuery(params);
    const maxResults: number = Math.min(10, Math.max(5, commonOptions.resultSize)); // coerceIn(5..10)
    const resp = await searchSdkRuntime().http.fetch({
      url: 'https://ollama.com/api/web_search',
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${opts.apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ query, max_results: maxResults }),
    }, context);
    if (resp.status < 200 || resp.status >= 300) {
      throw new Error(`Ollama search failed with code ${resp.status}: `);
    }
    const payload: JsonObject = asRecord(JSON.parse(resp.body) as JsonObject);
    return {
      answer: null,
      items: asArray(payload['results']).map((v) => {
        const o: JsonObject = asRecord(v);
        return {
          title: strOrNull(o['title']) ?? '',
          url: strOrNull(o['url']) ?? '',
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
    return Promise.reject(new Error('Scraping is not supported for Ollama'));
  },
};
