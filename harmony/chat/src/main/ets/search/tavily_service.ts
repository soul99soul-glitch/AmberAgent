// tavily_service — Tavily 搜索/抓取(D-068c)
// Android 基准: search/.../TavilySearchService.kt(全文 203 行;含 scrape)
import type { SearchRequestContext } from './search_service.ts';
import type { JsonObject, JsonValue } from '../chat/json.ts';
import { makeInputSchemaObj } from '../chat/tool.ts';
import type {
  ScrapedResult, SearchCommonOptions, SearchResult, SearchResultItem,
  SearchService, SearchServiceOptions, TavilyOptions,
} from './search_service.ts';
import { searchSdkRuntime } from './search_service.ts';
import { asArray, asRecord, primitiveContentOrNull, requireQuery, strOrNull, stringContentOrNull } from './json_pick.ts';

const asOptions = (o: SearchServiceOptions): TavilyOptions => {
  if (o.type !== 'tavily') throw new Error('TavilySearchService requires TavilyOptions');
  return o;
};

export const tavilySearchService: SearchService = {
  name: 'Tavily',

  parameters: makeInputSchemaObj(
    {
      query: { type: 'string', description: 'search keyword' },
      topic: {
        type: 'string',
        description: 'search topic (one of `general`, `news`, `finance`)',
        enum: ['general', 'news', 'finance'],
      },
    },
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
    const opts: TavilyOptions = asOptions(serviceOptions);
    const query: string = requireQuery(params);
    const topic: string = stringContentOrNull(params, 'topic') ?? 'general';
    if (!['general', 'news', 'finance'].includes(topic)) {
      throw new Error('topic must be one of `general`, `news`, `finance`');
    }
    const apiKey: string = searchSdkRuntime().keyRoulette.next(opts.apiKey, opts.id);
    const resp = await searchSdkRuntime().http.fetch({
      url: 'https://api.tavily.com/search',
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        query,
        max_results: commonOptions.resultSize,
        search_depth: opts.depth.length > 0 ? opts.depth : 'advanced',
        topic,
        include_answer: 'advanced',
      }),
    }, context);
    if (resp.status < 200 || resp.status >= 300) {
      throw new Error(`response failed #${resp.status}`);
    }
    const payload: JsonObject = asRecord(JSON.parse(resp.body) as JsonObject);
    // images distinct take(5) 挂首条(同 Perplexity 响应级图片规则)
    const tavilyImages: string[] = [];
    for (const v of asArray(payload['images'])) {
      const u: string | null = strOrNull(v);
      if (u !== null && !tavilyImages.includes(u)) tavilyImages.push(u);
      if (tavilyImages.length >= 5) break;
    }
    let imagesAttached: boolean = false;
    const items: SearchResultItem[] = asArray(payload['results'])
      .map((v: JsonValue): SearchResultItem => {
        const o: JsonObject = asRecord(v);
        let imgs: string[] = [];
        if (!imagesAttached && tavilyImages.length > 0) {
          imagesAttached = true;
          imgs = tavilyImages;
        }
        return {
          title: strOrNull(o['title']) ?? '',
          url: strOrNull(o['url']) ?? '',
          text: strOrNull(o['content']) ?? '',
          publishedAt: null,
          images: imgs,
        };
      });
    return { answer: strOrNull(payload['answer']), items };
  },

  scrape: async (
    params: JsonObject, _commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
    context?: SearchRequestContext,
  ): Promise<ScrapedResult> => {
    const opts: TavilyOptions = asOptions(serviceOptions);
    const url: string | null = primitiveContentOrNull(params, 'url');
    if (url === null) throw new Error('url is required');
    const apiKey: string = searchSdkRuntime().keyRoulette.next(opts.apiKey, opts.id);
    const resp = await searchSdkRuntime().http.fetch({
      url: 'https://api.tavily.com/extract',
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ urls: [url] }),
    }, context);
    if (resp.status < 200 || resp.status >= 300) {
      throw new Error(`response failed #${resp.status}`);
    }
    const payload: JsonObject = asRecord(JSON.parse(resp.body) as JsonObject);
    return {
      urls: asArray(payload['results']).map((v: JsonValue) => {
        const o: JsonObject = asRecord(v);
        return {
          url: strOrNull(o['url']) ?? '',
          content: strOrNull(o['raw_content']) ?? '',
          metadata: null,
        };
      }),
    };
  },
};
