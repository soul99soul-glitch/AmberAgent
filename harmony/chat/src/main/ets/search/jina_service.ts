// jina_service — Jina Search/Reader(D-066)
// Android 基准: search/.../JinaSearchService.kt(全文 199 行;SearchService<JinaOptions>)
import type { SearchRequestContext } from './search_service.ts';
import type { JsonObject, JsonValue } from '../chat/json.ts';
import { makeInputSchemaObj } from '../chat/tool.ts';
import type { InputSchemaObj } from '../chat/tool.ts';
import type {
  JinaOptions, ScrapedResult, SearchCommonOptions, SearchResult, SearchResultItem,
  SearchService, SearchServiceOptions,
} from './search_service.ts';
import { searchSdkRuntime } from './search_service.ts';

const DEFAULT_SEARCH_URL: string = 'https://s.jina.ai/';
const DEFAULT_SCRAPE_URL: string = 'https://r.jina.ai/';

const asRecord = (v: JsonValue | undefined): JsonObject =>
  (typeof v === 'object' && v !== null && !Array.isArray(v)) ? v as JsonObject : {};

const asArray = (v: JsonValue | undefined): JsonValue[] => Array.isArray(v) ? v : [];

const strOrNull = (v: JsonValue | undefined): string | null =>
  typeof v === 'string' ? v : null;

// jsonPrimitive.content:String 取原串;Number/Boolean 取字符串表示;对象/数组/缺失 → null
const primitiveContentOrNull = (j: JsonObject, key: string): string | null => {
  const v: JsonValue | undefined = j[key];
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  return null;
};

const asJinaOptions = (o: SearchServiceOptions): JinaOptions => {
  if (o.type !== 'jina') throw new Error('JinaSearchService requires JinaOptions');
  return o;
};

export const jinaSearchService: SearchService = {
  name: 'Jina',

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
    const opts: JinaOptions = asJinaOptions(serviceOptions);
    const query: string = primitiveContentOrNull(params, 'query') ?? ((): never => {
      throw new Error('query is required');
    })();
    const searchUrl: string = opts.searchUrl.trim().length > 0 ? opts.searchUrl : DEFAULT_SEARCH_URL;
    const headers: Record<string, string> = {
      'Accept': 'application/json',
      'Content-Type': 'application/json',
    };
    if (opts.apiKey.trim().length > 0) {
      headers['Authorization'] = `Bearer ${opts.apiKey}`;
    }
    const resp = await searchSdkRuntime().http.fetch({
      url: searchUrl,
      method: 'POST',
      headers,
      body: JSON.stringify({ q: query }),
    }, context);
    if (resp.status < 200 || resp.status >= 300) {
      throw new Error(`response failed #${resp.status}`);
    }
    const payload: JsonObject = asRecord(JSON.parse(resp.body) as JsonValue);
    const items: SearchResultItem[] = asArray(payload['data'])
      .slice(0, commonOptions.resultSize)
      .map((v: JsonValue): SearchResultItem => {
        const o: JsonObject = asRecord(v);
        return {
          title: strOrNull(o['title']) ?? '',
          url: strOrNull(o['url']) ?? '',
          text: strOrNull(o['description']) ?? '',
          publishedAt: null,
          images: [],
        };
      });
    return { answer: null, items };
  },

  scrape: async (
    params: JsonObject, _commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
    context?: SearchRequestContext,
  ): Promise<ScrapedResult> => {
    const opts: JinaOptions = asJinaOptions(serviceOptions);
    // Kotlin 原文笔误('urls is required')逐字保留
    const url: string = primitiveContentOrNull(params, 'url') ?? ((): never => {
      throw new Error('urls is required');
    })();
    const scrapeUrl: string = opts.scrapeUrl.trim().length > 0 ? opts.scrapeUrl : DEFAULT_SCRAPE_URL;
    const headers: Record<string, string> = {
      'Accept': 'application/json',
      'Content-Type': 'application/json',
      'X-Return-Format': 'markdown',
    };
    if (opts.apiKey.trim().length > 0) {
      headers['Authorization'] = `Bearer ${opts.apiKey}`;
    }
    const resp = await searchSdkRuntime().http.fetch({
      url: scrapeUrl,
      method: 'POST',
      headers,
      body: JSON.stringify({ url }),
    }, context);
    if (resp.status < 200 || resp.status >= 300) {
      throw new Error(`response failed for url ${url} #${resp.status}`);
    }
    const payload: JsonObject = asRecord(JSON.parse(resp.body) as JsonValue);
    const data: JsonObject = asRecord(payload['data']);
    return {
      urls: [{
        url: strOrNull(data['url']) ?? '',
        content: strOrNull(data['content']) ?? '',
        metadata: {
          title: strOrNull(data['title']),
          description: strOrNull(data['description']),
          language: null, // JinaScrapeData 无 language 字段
        },
      }],
    };
  },
};

// InputSchemaObj 显式导出类型用(避免未用告警)
export type { InputSchemaObj as JinaInputSchema };
