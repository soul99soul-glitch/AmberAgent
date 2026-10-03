// brave_service — Brave 搜索(D-068b)
// Android 基准: search/.../BraveSearchService.kt(全文 142 行)
// 偏差登记:okhttp response.message 无 Port 对应 → 尾空串;Log.i 不移植(宪章隐私)
import type { SearchRequestContext } from './search_service.ts';
import type { JsonObject } from '../chat/json.ts';
import { makeInputSchemaObj } from '../chat/tool.ts';
import type {
  BraveOptions, ScrapedResult, SearchCommonOptions, SearchResult, SearchResultItem,
  SearchService, SearchServiceOptions,
} from './search_service.ts';
import { searchSdkRuntime } from './search_service.ts';
import { asArray, asRecord, requireQuery, strOrNull } from './json_pick.ts';
import { javaUrlEncodeForm } from './url_codec.ts';

const asOptions = (o: SearchServiceOptions): BraveOptions => {
  if (o.type !== 'brave') throw new Error('BraveSearchService requires BraveOptions');
  return o;
};

export const braveSearchService: SearchService = {
  name: 'Brave',

  parameters: makeInputSchemaObj(
    { query: { type: 'string', description: 'search keyword' } },
    ['query'],
  ),

  scrapingParameters: null,

  search: async (
    params: JsonObject, commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
    context?: SearchRequestContext,
  ): Promise<SearchResult> => {
    const opts: BraveOptions = asOptions(serviceOptions);
    const query: string = requireQuery(params);
    const url: string = 'https://api.search.brave.com/res/v1/web/search'
      + `?q=${javaUrlEncodeForm(query)}&count=${commonOptions.resultSize}`;
    const resp = await searchSdkRuntime().http.fetch({
      url,
      method: 'GET',
      headers: {
        'Accept': 'application/json',
        'X-Subscription-Token': opts.apiKey,
      },
    }, context);
    if (resp.status < 200 || resp.status >= 300) {
      throw new Error(`Brave search failed with code ${resp.status}: `);
    }
    const payload: JsonObject = asRecord(JSON.parse(resp.body) as JsonObject);
    const results = asRecord(payload['web'])['results'];
    const raw = Array.isArray(results) ? results : [];
    const items: SearchResultItem[] = raw.map((v): SearchResultItem => {
      const o: JsonObject = asRecord(v);
      const thumb: JsonObject = asRecord(o['thumbnail']);
      // original(真实源)优先,src(Brave 代理防盗链)兜底;http 前缀才取
      const imgCandidate: string | null = strOrNull(thumb['original']) ?? strOrNull(thumb['src']);
      const imgUrl: string | null =
        imgCandidate !== null && imgCandidate.startsWith('http') ? imgCandidate : null;
      return {
        title: strOrNull(o['title']) ?? '',
        url: strOrNull(o['url']) ?? '',
        text: strOrNull(o['description']) ?? '',
        publishedAt: null,
        images: imgUrl !== null ? [imgUrl] : [],
      };
    });
    return { answer: null, items };
  },

  scrape: (
    _params: JsonObject, _commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
  ): Promise<ScrapedResult> => {
    asOptions(serviceOptions);
    return Promise.reject(new Error('Scraping is not supported for Brave'));
  },
};
