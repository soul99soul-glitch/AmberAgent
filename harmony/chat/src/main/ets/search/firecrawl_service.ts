// firecrawl_service — Firecrawl 搜索/抓取(D-068c)
// Android 基准: search/.../FirecrawlSearchService.kt(全文 244 行)
// **bug 钉住**: search 失败文案 Android 为字面量 'response failed #{response.code}'
//   (Kotlin "response failed #${'$'}{response.code}" — ${'$'} 渲染 $,其余字面量)
import type { SearchRequestContext } from './search_service.ts';
import type { JsonObject, JsonValue } from '../chat/json.ts';
import { makeInputSchemaObj } from '../chat/tool.ts';
import type {
  FirecrawlOptions, ScrapedResult, SearchCommonOptions, SearchResult,
  SearchResultItem, SearchService, SearchServiceOptions,
} from './search_service.ts';
import { searchSdkRuntime } from './search_service.ts';
import { asArray, asRecord, primitiveContentOrNull, requireQuery, strOrNull } from './json_pick.ts';

const asOptions = (o: SearchServiceOptions): FirecrawlOptions => {
  if (o.type !== 'firecrawl') throw new Error('FirecrawlSearchService requires FirecrawlOptions');
  return o;
};

// JsonElement?.asStringList():数组 → contentOrNull 非 blank;串 → 逗号切分 trim 非空;否则 null
const asStringList = (v: JsonValue | undefined): string[] | null => {
  if (v === undefined || v === null) return null;
  if (Array.isArray(v)) {
    const out: string[] = [];
    for (const e of v) {
      const s: string | null = typeof e === 'string' ? e : null;
      if (s !== null && s.trim().length > 0) out.push(s);
    }
    return out;
  }
  if (typeof v === 'string') {
    return v.split(',').map((s: string) => s.trim()).filter((s: string) => s.length > 0);
  }
  return null;
};

export const firecrawlSearchService: SearchService = {
  name: 'Firecrawl',

  parameters: makeInputSchemaObj(
    {
      query: { type: 'string', description: 'Search query string' },
      sources: {
        type: 'array',
        description: 'Optional list of sources: `web`, `news`, default value is `web`',
        items: { type: 'string' },
      },
      categories: {
        type: 'array',
        description:
          'Optional list of categories to filter search results by: `github`, `research`, empty value means no filtering, default value is empty',
        items: { type: 'string' },
      },
    },
    ['query'],
  ),

  scrapingParameters: makeInputSchemaObj(
    {
      url: { type: 'string', description: 'URL to scrape' },
      onlyMainContent: {
        type: 'boolean',
        description: 'Whether to only scrape main content, default is true',
      },
    },
    ['url'],
  ),

  search: async (
    params: JsonObject, commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
    context?: SearchRequestContext,
  ): Promise<SearchResult> => {
    const opts: FirecrawlOptions = asOptions(serviceOptions);
    const query: string = requireQuery(params);
    const sources: string[] | null = asStringList(params['sources']);
    const categories: string[] | null = asStringList(params['categories']);

    const body: JsonObject = {
      query,
      limit: commonOptions.resultSize,
    };
    if (sources !== null && sources.length > 0) body['sources'] = sources;
    if (categories !== null && categories.length > 0) body['categories'] = categories;

    const resp = await searchSdkRuntime().http.fetch({
      url: 'https://api.firecrawl.dev/v2/search',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${opts.apiKey}`,
      },
      body: JSON.stringify(body),
    }, context);
    if (resp.status < 200 || resp.status >= 300) {
      // Android bug 逐字保留(见文件头注)
      throw new Error('response failed #{response.code}');
    }
    const payload: JsonObject = asRecord(JSON.parse(resp.body) as JsonObject);
    const dataRaw = payload['data'];
    if (dataRaw === undefined || dataRaw === null || typeof dataRaw !== 'object' || Array.isArray(dataRaw)) {
      throw new Error('empty response data');
    }
    const data: JsonObject = dataRaw as JsonObject;
    const items: SearchResultItem[] = [];
    for (const v of asArray(data['web'])) {
      const o: JsonObject = asRecord(v);
      items.push({
        title: strOrNull(o['title']) ?? '',
        url: strOrNull(o['url']) ?? '',
        text: strOrNull(o['description']) ?? '',
        publishedAt: null,
        images: [],
      });
    }
    for (const v of asArray(data['news'])) {
      const o: JsonObject = asRecord(v);
      items.push({
        title: strOrNull(o['title']) ?? '',
        url: strOrNull(o['url']) ?? '',
        // """${snippet}\n${date}""".trimIndent()
        text: `${strOrNull(o['snippet']) ?? ''}\n${strOrNull(o['date']) ?? ''}`,
        publishedAt: null,
        images: [],
      });
    }
    return { answer: null, items };
  },

  scrape: async (
    params: JsonObject, _commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
    context?: SearchRequestContext,
  ): Promise<ScrapedResult> => {
    const opts: FirecrawlOptions = asOptions(serviceOptions);
    const url: string | null = primitiveContentOrNull(params, 'url');
    if (url === null) throw new Error('url is required');
    // contentOrNull(串才取)?.toBoolean() ?: true — toBoolean 大小写不敏感 'true'
    const omcStr: string | null =
      typeof params['onlyMainContent'] === 'string' ? params['onlyMainContent'] as string : null;
    const onlyMainContent: boolean =
      omcStr === null ? true : omcStr.toLowerCase() === 'true';

    const resp = await searchSdkRuntime().http.fetch({
      url: 'https://api.firecrawl.dev/v2/scrape',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${opts.apiKey}`,
      },
      body: JSON.stringify({
        url,
        onlyMainContent,
        maxAge: 172800000,
        parsers: [],
        formats: ['markdown'],
      }),
    }, context);
    if (resp.status < 200 || resp.status >= 300) {
      throw new Error(`response failed #${resp.status}`);
    }
    const payload: JsonObject = asRecord(JSON.parse(resp.body) as JsonObject);
    const successRaw = payload['success'];
    const success: boolean =
      (typeof successRaw === 'string' && successRaw.toLowerCase() === 'true')
      || successRaw === true;
    if (!success) throw new Error('scrape request failed');
    const data: JsonObject = asRecord(payload['data']);
    const markdown: string = strOrNull(data['markdown']) ?? '';
    return {
      urls: [{ url, content: markdown, metadata: null }],
    };
  },
};
