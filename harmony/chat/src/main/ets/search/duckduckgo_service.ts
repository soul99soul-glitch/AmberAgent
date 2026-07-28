// duckduckgo_service — DuckDuckGo 内置源(D-067)
// Android 基准: search/.../DuckDuckGoSearchService.kt(全文 109 行;普通 object 非 SearchService)
// 偏差登记:Jsoup → mini-DOM;Jsoup timeout → HttpClient Port 默认
import type { SearchCommonOptions, SearchResult, SearchResultItem } from './search_service.ts';
import { searchSdkRuntime } from './search_service.ts';
import { javaUrlEncodeForm, javaUrlDecodeForm } from './url_codec.ts';
import type { HtmlDocument, HtmlElement } from './html_dom.ts';
import {
  parseHtml, select, selectFirst, elementText, attr, hasClass, parentsOf, nextElementSiblingOf,
} from './html_dom.ts';

const DDG_LITE_UA: string =
  'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36';
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

// Element.resultSnippet(:83-91):上两级行的后续 3 行内首个 td.result-snippet
const resultSnippet = (anchor: HtmlElement): string => {
  const row: HtmlElement | null =
    anchor.parent !== null ? anchor.parent.parent : null;
  let candidate: HtmlElement | null = row !== null ? nextElementSiblingOf(row) : null;
  for (let i = 0; i < 3 && candidate !== null; i++) {
    const hits: HtmlElement[] = select(candidate, 'td.result-snippet');
    if (hits.length > 0) {
      const t: string = elementText(hits[0]).trim();
      if (t.length > 0) return t;
    }
    candidate = nextElementSiblingOf(candidate);
  }
  return '';
};

// 收集锚点(候选元素自身为 a 时也计入 — select 只看后代,parseLite 的
//   a.result-link 在 tr 后代中;保持与 Jsoup doc.select 语义一致:对 doc 调用)
const collectAnchors = (doc: HtmlDocument, selector: string): HtmlElement[] =>
  select(doc, selector);

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

// parseLite(:53-67)
const parseLite = (doc: HtmlDocument, limit: number): SearchResultItem[] => {
  const items: SearchResultItem[] = [];
  for (const anchor of collectAnchors(doc, 'a.result-link')) {
    // filterNot parents any hasClass('result-sponsored')
    const sponsored: boolean = parentsOf(anchor)
      .some((p: HtmlElement): boolean => hasClass(p, 'result-sponsored'));
    if (sponsored) continue;
    const title: string = elementText(anchor).trim();
    const href: string = decodeDuckDuckGoUrl(attr(anchor, 'href')).trim();
    if (title.length === 0 || !href.startsWith('http')) continue;
    items.push({
      title,
      url: href,
      text: resultSnippet(anchor),
      publishedAt: null,
      images: [],
    });
  }
  return distinctTake(items, limit);
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
    const snippetEls: HtmlElement[] = select(result, '.result__snippet, .result__body');
    items.push({
      title,
      url: href,
      text: snippetEls.length > 0
        ? snippetEls.map((e: HtmlElement): string => elementText(e)).join(' ').trim()
        : '',
      publishedAt: null,
      images: [],
    });
  }
  return distinctTake(items, limit);
};

const fetchDdg = (url: string, ua: string): Promise<string> =>
  searchSdkRuntime().http.fetch({
    url,
    method: 'GET',
    headers: {
      'User-Agent': ua,
      'Accept': DDG_ACCEPT,
      'Accept-Language': DDG_ACCEPT_LANG,
      'Referer': 'https://duckduckgo.com/',
    },
  }).then((r): string => r.body);

export const duckDuckGoSearch = async (
  query: string, commonOptions: SearchCommonOptions,
): Promise<SearchResult> => {
  const encoded: string = javaUrlEncodeForm(query);
  const liteBody: string =
    await fetchDdg(`https://lite.duckduckgo.com/lite/?q=${encoded}`, DDG_LITE_UA);
  const liteDoc = parseHtml(liteBody);
  const liteResults: SearchResultItem[] = parseLite(liteDoc, commonOptions.resultSize);
  if (liteResults.length > 0) {
    return { answer: null, items: liteResults };
  }
  const htmlBody: string =
    await fetchDdg(`https://html.duckduckgo.com/html/?q=${encoded}`, DDG_HTML_UA);
  const htmlDoc = parseHtml(htmlBody);
  const htmlResults: SearchResultItem[] = parseHtmlResults(htmlDoc, commonOptions.resultSize);
  if (htmlResults.length > 0) {
    return { answer: null, items: htmlResults };
  }
  const pageText: string = `${elementText(liteDoc)} ${elementText(htmlDoc)}`.toLowerCase();
  if (pageText.includes('bots use duckduckgo') || pageText.includes('anomaly-modal')) {
    throw new Error('DuckDuckGo requires human verification');
  }
  throw new Error('DuckDuckGo returned no parseable organic results');
};
