// bing_service — Bing HTML 兜底源(D-067)
// Android 基准: search/.../BingSearchService.kt(全文 133 行;SearchService<BingLocalOptions>)
// 偏差登记:Jsoup → mini-DOM(html_dom.ts,树形/选择器子集);Jsoup timeout 10s →
//   HttpClient Port 默认超时;Locale.getDefault() → runtime.acceptLanguage 注入
import type { SearchRequestContext } from './search_service.ts';
import type { JsonObject, JsonValue } from '../chat/json.ts';
import { makeInputSchemaObj } from '../chat/tool.ts';
import type {
  BingLocalOptions, ScrapedResult, SearchCommonOptions, SearchResult, SearchResultItem,
  SearchService, SearchServiceOptions,
} from './search_service.ts';
import { searchSdkRuntime } from './search_service.ts';
import { javaUrlEncodeForm, javaUrlDecodeForm, base64UrlDecodeUtf8 } from './url_codec.ts';
import type { HtmlElement } from './html_dom.ts';
import { parseHtml, select, selectFirst, elementText, attr } from './html_dom.ts';

const BING_UA: string =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

const asBingOptions = (o: SearchServiceOptions): BingLocalOptions => {
  if (o.type !== 'bing_local') throw new Error('BingSearchService requires BingLocalOptions');
  return o;
};

// decodeBingUrl(:115-124):query 首段 key ∈ {u,url} → URLDecoder;否则原文
// 国际版 /ck/a 跳转的 u 值为 'a1' + base64url(目标 URL),需再解一层
const decodeBingUrl = (raw: string): string => {
  const qIdx: number = raw.indexOf('?');
  if (qIdx < 0) return raw;
  const query: string = raw.slice(qIdx + 1).split('#')[0];
  for (const seg of query.split('&')) {
    const eq: number = seg.indexOf('=');
    const key: string = eq >= 0 ? seg.slice(0, eq) : seg;
    if (key === 'u' || key === 'url') {
      const target: string = eq >= 0 ? seg.slice(eq + 1) : '';
      if (target.trim().length === 0) return raw;
      const decoded: string = javaUrlDecodeForm(target);
      if (decoded.startsWith('a1')) {
        const unwrapped: string | null = base64UrlDecodeUtf8(decoded.substring(2));
        if (unwrapped !== null && unwrapped.startsWith('http')) return unwrapped;
      }
      return decoded;
    }
  }
  return raw;
};

// parseBingResult(:90-100)
const parseBingResult = (element: HtmlElement): SearchResultItem | null => {
  const anchor = selectFirst(element, 'h2 a');
  if (anchor === null) return null;
  return {
    title: elementText(anchor).trim(),
    url: decodeBingUrl(attr(anchor, 'href')).trim(),
    text: ((): string => {
      const first = select(element, '.b_caption p, .b_snippet, p');
      return first.length > 0 ? elementText(first[0]).trim() : '';
    })(),
    publishedAt: null,
    images: [],
  };
};

// parseFallbackLinks(:102-113)
const parseFallbackLinks = (anchors: HtmlElement[]): SearchResultItem[] => {
  const out: SearchResultItem[] = [];
  for (const anchor of anchors) {
    const title: string = elementText(anchor).trim();
    const link: string = decodeBingUrl(attr(anchor, 'href')).trim();
    if (title.length === 0 || !link.startsWith('http')) continue;
    const grand = anchor.parent !== null ? anchor.parent.parent : null;
    out.push({
      title,
      url: link,
      text: (grand !== null ? elementText(grand) : '').slice(0, 500),
      publishedAt: null,
      images: [],
    });
  }
  return out;
};

export const bingSearchService: SearchService = {
  name: 'Bing',

  parameters: makeInputSchemaObj(
    { query: { type: 'string', description: 'search keyword' } },
    ['query'],
  ),

  scrapingParameters: null,

  search: async (
    params: JsonObject, commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
    context?: SearchRequestContext,
  ): Promise<SearchResult> => {
    asBingOptions(serviceOptions);
    const qv: JsonValue | undefined = params['query'];
    const query: string | null =
      typeof qv === 'string' ? qv
        : (typeof qv === 'number' || typeof qv === 'boolean') ? String(qv) : null;
    if (query === null) throw new Error('query is required');
    const url: string = 'https://www.bing.com/search?q=' + javaUrlEncodeForm(query);
    const resp = await searchSdkRuntime().http.fetch({
      url,
      method: 'GET',
      headers: {
        'User-Agent': BING_UA,
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
        'Accept-Language': searchSdkRuntime().acceptLanguage(),
        'Accept-Encoding': 'gzip, deflate, sdch',
        'Accept-Charset': 'utf-8',
        'Connection': 'keep-alive',
        'Referer': 'https://www.bing.com/',
        'Cookie': 'SRCHHPGUSR=ULSR=1',
      },
    }, context);
    if (resp.status < 200 || resp.status >= 300) {
      // 429/403/5xx 的挑战页/错误页不得当结果页解析(会被误报成无结果或返回错误页链接)
      throw new Error(`Bing request failed with HTTP ${resp.status}`);
    }
    const doc = parseHtml(resp.body);
    const pageText: string = elementText(doc);
    const lower: string = pageText.toLowerCase();
    if (lower.includes('verify you are human') || lower.includes('unusual traffic')
      || lower.includes('captcha')) {
      throw new Error('Bing blocked the request with a verification page');
    }
    const primary: SearchResultItem[] = [];
    for (const el of select(doc, 'li.b_algo')) {
      const item = parseBingResult(el);
      if (item !== null) primary.push(item);
    }
    const fallback: SearchResultItem[] = primary.length === 0
      ? parseFallbackLinks(select(doc, 'main h2 a, #b_results h2 a, h2 a'))
      : [];
    const seen: string[] = [];
    const results: SearchResultItem[] = [];
    for (const it of [...primary, ...fallback]) {
      if (it.title.length === 0 || !it.url.startsWith('http')) continue;
      if (seen.includes(it.url)) continue; // distinctBy(url) 保首序
      seen.push(it.url);
      results.push(it);
      if (results.length >= commonOptions.resultSize) break; // take
    }
    if (results.length === 0) {
      throw new Error('Search failed: no results found');
    }
    return { answer: null, items: results };
  },

  scrape: (
    _params: JsonObject, _commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
  ): Promise<ScrapedResult> => {
    asBingOptions(serviceOptions);
    return Promise.reject(new Error('Scraping is not supported for Bing'));
  },
};
