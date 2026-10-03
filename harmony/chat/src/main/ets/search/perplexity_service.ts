// perplexity_service — Perplexity 搜索(D-068b)
// Android 基准: search/.../PerplexitySearchService.kt(全文 176 行)
// 偏差登记:Log.i(body/images) 不移植(宪章隐私)
import type { SearchRequestContext } from './search_service.ts';
import type { JsonObject, JsonValue } from '../chat/json.ts';
import { makeInputSchemaObj } from '../chat/tool.ts';
import type {
  PerplexityOptions, ScrapedResult, SearchCommonOptions, SearchResult, SearchResultItem,
  SearchService, SearchServiceOptions,
} from './search_service.ts';
import { searchSdkRuntime } from './search_service.ts';
import { asArray, asRecord, requireQuery, strOrNull } from './json_pick.ts';

const ENDPOINT: string = 'https://api.perplexity.ai/search';

const asOptions = (o: SearchServiceOptions): PerplexityOptions => {
  if (o.type !== 'perplexity') throw new Error('PerplexitySearchService requires PerplexityOptions');
  return o;
};

export const perplexitySearchService: SearchService = {
  name: 'Perplexity',

  parameters: makeInputSchemaObj(
    { query: { type: 'string', description: 'search keyword' } },
    ['query'],
  ),

  scrapingParameters: null,

  search: async (
    params: JsonObject, commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
    context?: SearchRequestContext,
  ): Promise<SearchResult> => {
    const opts: PerplexityOptions = asOptions(serviceOptions);
    if (opts.apiKey.trim().length === 0) throw new Error('Perplexity API key is required');
    const query: string = requireQuery(params);
    const body: JsonObject = {
      query,
      max_results: commonOptions.resultSize,
    };
    // maxTokens/maxTokensPerPage 仅 >0 落键
    if (opts.maxTokens !== null && opts.maxTokens > 0) body['max_tokens'] = opts.maxTokens;
    if (opts.maxTokensPerPage !== null && opts.maxTokensPerPage > 0) {
      body['max_tokens_per_page'] = opts.maxTokensPerPage;
    }
    const resp = await searchSdkRuntime().http.fetch({
      url: ENDPOINT,
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
    // 响应级 images:image_url 收集 distinct take(5)(origin_url 不取)
    const allImages: string[] = [];
    for (const v of asArray(payload['images'])) {
      const u: string | null = strOrNull(asRecord(v)['image_url']);
      if (u !== null && !allImages.includes(u)) allImages.push(u);
      if (allImages.length >= 5) break;
    }
    const rawItems: JsonObject[] = asArray(payload['results'])
      .map((v: JsonValue): JsonObject => asRecord(v))
      .filter((o: JsonObject): boolean => {
        const t: string | null = strOrNull(o['title']);
        const u: string | null = strOrNull(o['url']);
        return t !== null && t.trim().length > 0 && u !== null && u.trim().length > 0;
      })
      .slice(0, commonOptions.resultSize);
    // 全部响应级图片挂首个有效结果(跨结果分发由聚合层处理)
    let imagesAttached: boolean = false;
    const items: SearchResultItem[] = rawItems.map((o: JsonObject): SearchResultItem => {
      let imgs: string[] = [];
      if (!imagesAttached && allImages.length > 0) {
        imagesAttached = true;
        imgs = allImages;
      }
      return {
        title: strOrNull(o['title']) ?? '',
        url: strOrNull(o['url']) ?? '',
        text: strOrNull(o['snippet']) ?? strOrNull(o['text']) ?? '',
        publishedAt: null,
        images: imgs,
      };
    });
    return { answer: strOrNull(payload['answer']), items };
  },

  scrape: (
    _params: JsonObject, _commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
  ): Promise<ScrapedResult> => {
    asOptions(serviceOptions);
    return Promise.reject(new Error('Scraping is not supported for Perplexity'));
  },
};
