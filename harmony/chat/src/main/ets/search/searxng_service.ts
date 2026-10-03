// searxng_service — SearXNG 搜索(D-068c)
// Android 基准: search/.../SearXNGService.kt(全文 183 行)
// 偏差: Log.i(query) 省略(隐私:用户查询不入日志,章程禁止)
import type { SearchRequestContext } from './search_service.ts';
import type { JsonObject, JsonValue } from '../chat/json.ts';
import { makeInputSchemaObj } from '../chat/tool.ts';
import type {
  ScrapedResult, SearchCommonOptions, SearchResult, SearchResultItem,
  SearchService, SearchServiceOptions, SearXNGOptions,
} from './search_service.ts';
import { searchSdkRuntime } from './search_service.ts';
import { asArray, asRecord, requireQuery, strOrNull } from './json_pick.ts';
import { basicAuthHeader, javaUrlEncodeForm } from './url_codec.ts';

const asOptions = (o: SearchServiceOptions): SearXNGOptions => {
  if (o.type !== 'searxng') throw new Error('SearXNGService requires SearXNGOptions');
  return o;
};

// java.net.URI(base).resolve(raw) 子集(仅移植用到的相对/绝对路径情形)
const resolveImage = (base: string, raw: string): string => {
  if (raw.startsWith('http')) return raw;
  if (raw.startsWith('//')) return `https:${raw}`;
  const m: RegExpMatchArray | null = base.match(/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\/[^/]+/);
  if (m === null) return raw;
  const origin: string = m[0];
  if (raw.startsWith('/')) return origin + raw;
  // base 路径的目录(URI.resolve 丢掉最后一段)
  const path: string = base.substring(origin.length);
  const dir: string = path.length > 0 ? path.substring(0, path.lastIndexOf('/') + 1) : '/';
  return origin + dir + raw;
};

export const searXNGService: SearchService = {
  name: 'SearXNG',

  parameters: makeInputSchemaObj(
    { query: { type: 'string', description: 'search keyword' } },
    ['query'],
  ),

  scrapingParameters: null,

  search: async (
    params: JsonObject, commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
    context?: SearchRequestContext,
  ): Promise<SearchResult> => {
    const opts: SearXNGOptions = asOptions(serviceOptions);
    if (opts.url.trim().length === 0) throw new Error('SearXNG URL cannot be empty');
    const query: string = requireQuery(params);

    const baseUrl: string = opts.url.replace(/\/+$/, '');
    let url: string =
      `${baseUrl}/search?q=${javaUrlEncodeForm(query)}&format=json`;
    if (opts.engines.trim().length > 0) {
      url += `&engines=${encodeURIComponent(opts.engines)}`;
    }
    if (opts.language.trim().length > 0) {
      url += `&language=${encodeURIComponent(opts.language)}`;
    }

    const headers: Record<string, string> = {};
    if (opts.username.trim().length > 0 && opts.password.trim().length > 0) {
      headers['Authorization'] = basicAuthHeader(opts.username, opts.password);
    }
    const resp = await searchSdkRuntime().http.fetch({ url, method: 'GET', headers }, context);
    if (resp.status < 200 || resp.status >= 300) {
      throw new Error(`SearXNG request failed with status ${resp.status}`);
    }
    let payload: JsonObject;
    try {
      payload = asRecord(JSON.parse(resp.body) as JsonObject);
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      throw new Error(`Failed to decode SearXNG response (${resp.body.length} chars): ${msg}`);
    }
    const items: SearchResultItem[] = [];
    for (const v of asArray(payload['results'])) {
      if (items.length >= commonOptions.resultSize) break;
      const o: JsonObject = asRecord(v);
      const imgs: string[] = [];
      const raw: (string | null)[] = [strOrNull(o['thumbnail']), strOrNull(o['img_src'])];
      for (const r of raw) {
        if (r === null || r.trim().length === 0) continue;
        const resolved: string = resolveImage(baseUrl, r);
        if (!resolved.startsWith('http')) continue;
        if (!imgs.includes(resolved)) imgs.push(resolved);
      }
      items.push({
        title: strOrNull(o['title']) ?? '',
        url: strOrNull(o['url']) ?? '',
        text: strOrNull(o['content']) ?? '',
        publishedAt: null,
        images: imgs.slice(0, 2),
      });
    }
    return { answer: null, items };
  },

  scrape: (
    _params: JsonObject, _commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
  ): Promise<ScrapedResult> => {
    asOptions(serviceOptions);
    return Promise.reject(new Error('Scraping is not supported for SearXNG'));
  },
};
