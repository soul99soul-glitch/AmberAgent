// duckduckgo_service — DuckDuckGo 内置源(D-067)
// Android 基准: search/.../DuckDuckGoSearchService.kt(全文 109 行;普通 object 非 SearchService)
// 偏差:Lite 端点已被 202 验证页拦截,改为仅走 HTML POST(对齐 iOS 免费搜索重做)
// 偏差登记:Jsoup → mini-DOM;Jsoup timeout → HttpClient Port 默认
import type { SearchCommonOptions, SearchRequestContext, SearchResult, SearchResultItem } from './search_service.ts';
import { searchSdkRuntime } from './search_service.ts';
import { javaUrlEncodeForm, javaUrlDecodeForm } from './url_codec.ts';
import type { HtmlDocument, HtmlElement } from './html_dom.ts';
import { parseHtml, select, selectFirst, elementText, attr } from './html_dom.ts';

const DDG_HTML_UA: string =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const DDG_ACCEPT: string = 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8';
const DDG_ACCEPT_LANG: string = 'zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7';

// decodeDuckDuckGoUrl(:93-108)
const decodeDuckDuckGoUrl = (rawHref: string): string => {
  const href: string = rawHref.trim();
  let normalized: string = href;
  if (href.startsWith('//')) {
    normalized = 'https:' + href;
  } else if (href.startsWith('/')) {
    normalized = 'https://duckduckgo.com' + href;
  }
  const qIdx: number = normalized.indexOf('?');
  if (qIdx < 0) return normalized;
  const query: string = normalized.slice(qIdx + 1).split('#')[0];
  for (const seg of query.split('&')) {
    const eq: number = seg.indexOf('=');
    const key: string = eq >= 0 ? seg.slice(0, eq) : seg;
    if (key === 'uddg') {
      const uddg: string = eq >= 0 ? seg.slice(eq + 1) : '';
      if (uddg.trim().length === 0) return normalized;
      return javaUrlDecodeForm(uddg);
    }
  }
  return normalized;
};

const distinctTake = (items: SearchResultItem[], limit: number): SearchResultItem[] => {
  const seen: string[] = [];
  const out: SearchResultItem[] = [];
  for (const it of items) {
    if (seen.includes(it.url)) continue;
    seen.push(it.url);
    out.push(it);
    if (out.length >= limit) break;
  }
  return out;
};

// parseHtml(:69-81)
const parseHtmlResults = (doc: HtmlDocument, limit: number): SearchResultItem[] => {
  const items: SearchResultItem[] = [];
  for (const result of select(doc, '.result')) {
    const anchor = selectFirst(result, 'a.result__a') ?? selectFirst(result, 'h2 a');
    if (anchor === null) continue;
    const title: string = elementText(anchor).trim();
    const href: string = decodeDuckDuckGoUrl(attr(anchor, 'href')).trim();
    if (title.length === 0 || !href.startsWith('http')) continue;
    // 广告位跳转(duckduckgo.com/y.js?...)不是自然结果
    if (href.startsWith('https://duckduckgo.com/y.js')) continue;
    // 只取 .result__snippet:真实页面的 .result__body 包着整条结果,会把标题重复拼进摘要
    const snippetEl: HtmlElement | null = selectFirst(result, '.result__snippet');
    items.push({
      title,
      url: href,
      text: snippetEl !== null ? elementText(snippetEl).trim() : '',
      publishedAt: null,
      images: [],
    });
  }
  return distinctTake(items, limit);
};

// POST html.duckduckgo.com/html/(deedy5/ddgs 同款端点);Lite 端点现已对首个请求
// 返回 202 验证页,不再使用
const fetchDdgHtml = (query: string, context?: SearchRequestContext): Promise<string> =>
  searchSdkRuntime().http.fetch({
    url: 'https://html.duckduckgo.com/html/',
    method: 'POST',
    headers: {
      'User-Agent': DDG_HTML_UA,
      'Accept': DDG_ACCEPT,
      'Accept-Language': DDG_ACCEPT_LANG,
      'Content-Type': 'application/x-www-form-urlencoded',
      'Referer': 'https://html.duckduckgo.com/',
    },
    body: `q=${javaUrlEncodeForm(query)}&b=&kl=wt-wt`,
  }, context).then((r): string => {
    // 202 = 反爬验证页;429/403/5xx 的限流页/挑战页不得当结果页解析
    if (r.status === 202) throw new Error('DuckDuckGo requires human verification');
    if (r.status < 200 || r.status >= 300) {
      throw new Error(`DuckDuckGo request failed with HTTP ${r.status}`);
    }
    return r.body;
  });

export const duckDuckGoSearch = async (
  query: string, commonOptions: SearchCommonOptions,
  context?: SearchRequestContext,
): Promise<SearchResult> => {
  const doc = parseHtml(await fetchDdgHtml(query, context));
  const results: SearchResultItem[] = parseHtmlResults(doc, commonOptions.resultSize);
  if (results.length > 0) {
    return { answer: null, items: results };
  }
  const pageText: string = elementText(doc).toLowerCase();
  if (pageText.includes('bots use duckduckgo') || pageText.includes('anomaly-modal')
    || pageText.includes('select all squares')) {
    throw new Error('DuckDuckGo requires human verification');
  }
  throw new Error('DuckDuckGo returned no parseable organic results');
};
