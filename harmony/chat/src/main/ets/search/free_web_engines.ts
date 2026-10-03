// free_web_engines — 无 Key 免费网页引擎 + 验证码熔断 + 统一分派
// 对齐 iOS 免费搜索重做(线程"免费搜索方案导致深度阅读来源不足"的实测结论):
//   - 多引擎并发、单个被拦就跳过,不再依赖单一 DDG
//   - Brave 网页版:解析规则参照 deedy5/ddgs engines/brave.py(MIT)
//   - 360 / 夸克:国内兜底;解析思路参照 searxng(AGPL,仅参考思路,未搬代码)
//   - 遇到验证页/限流的引擎短期熔断,避免深度阅读多角度查询连续撞墙
import type { JsonObject, JsonValue } from '../chat/json.ts';
import type { SearchCommonOptions, SearchRequestContext, SearchResult, SearchResultItem } from './search_service.ts';
import { searchSdkRuntime, makeSearchServiceOptions } from './search_service.ts';
import { javaUrlEncodeForm } from './url_codec.ts';
import { asRecord, strOrNull } from './json_pick.ts';
import type { HtmlElement } from './html_dom.ts';
import { parseHtml, select, selectFirst, elementText, attr } from './html_dom.ts';
import { duckDuckGoSearch } from './duckduckgo_service.ts';
import { bingSearchService } from './bing_service.ts';
import { wikipediaSearch } from './wikipedia_service.ts';
import { hackerNewsSearch } from './hackernews_service.ts';

export type FreeEngineId =
  | 'duckduckgo' | 'brave' | 'bing' | 'so360' | 'quark' | 'wikipedia' | 'hackernews';

export const FREE_ENGINE_NAMES: Record<FreeEngineId, string> = {
  duckduckgo: 'DuckDuckGo',
  brave: 'Brave',
  bing: 'Bing',
  so360: '360 搜索',
  quark: '夸克',
  wikipedia: 'Wikipedia',
  hackernews: 'Hacker News',
};

// 通用网页引擎(不含 Wikipedia/HN 这类垂直源)
export const FREE_WEB_ENGINE_IDS: FreeEngineId[] = ['duckduckgo', 'brave', 'bing', 'so360', 'quark'];

const DESKTOP_UA: string =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const MOBILE_UA: string =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1';
const HTML_ACCEPT: string = 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8';

// ===== 熔断 =====

const BREAKER_COOLDOWN_MS: number = 10 * 60 * 1000;
const breakerUntil = new Map<FreeEngineId, number>();

const isBlockedError = (e: Error): boolean =>
  /verification|captcha|HTTP (202|403|429)/i.test(e.message);

export const resetFreeEngineBreakers = (): void => {
  breakerUntil.clear();
};

export const freeEngineCoolingDown = (id: FreeEngineId, now: number = Date.now()): boolean =>
  (breakerUntil.get(id) ?? 0) > now;

// ===== 话题判定 =====

const TECH_KEYWORDS: string[] = [
  'github', '开源', 'developer', 'claude code', 'codex', 'mcp', 'llm', 'ai agent', 'hacker news',
  'api', 'sdk', 'rust', 'python', 'javascript', 'typescript', 'kotlin', 'swift', 'linux',
  '编程', '程序员', '框架', '数据库',
];

export const looksTechnicalQuery = (query: string): boolean => {
  const lower: string = query.toLowerCase();
  return TECH_KEYWORDS.some((k: string) => lower.includes(k));
};

// ===== 公共 =====

const stripTags = (s: string): string =>
  s.replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim();

// mini-DOM 会在 <em> 高亮边界插空格:去掉两个汉字之间的空白
const tidyCjk = (s: string): string =>
  s.replace(/([\u4e00-\u9fff])\s+(?=[\u4e00-\u9fff])/g, '$1').trim();

const distinctTake = (items: SearchResultItem[], limit: number): SearchResultItem[] => {
  const seen: string[] = [];
  const out: SearchResultItem[] = [];
  for (const it of items) {
    if (it.title.length === 0 || !it.url.startsWith('http') || seen.includes(it.url)) continue;
    seen.push(it.url);
    out.push(it);
    if (out.length >= limit) break;
  }
  return out;
};

const fetchHtml = async (
  engine: string, url: string, headers: Record<string, string>,
  context?: SearchRequestContext,
): Promise<string> => {
  const resp = await searchSdkRuntime().http.fetch({ url, method: 'GET', headers }, context);
  if (resp.status < 200 || resp.status >= 300) {
    throw new Error(`${engine} request failed with HTTP ${resp.status}`);
  }
  return resp.body;
};

const hostOf = (url: string): string => {
  const m: RegExpExecArray | null = /^https?:\/\/([^/?#:]+)/i.exec(url);
  return m !== null ? m[1].toLowerCase() : '';
};

// 自身 + 全部后代(文档序)
const descendants = (el: HtmlElement): HtmlElement[] => {
  const out: HtmlElement[] = [el];
  for (const c of el.children) {
    for (const d of descendants(c)) out.push(d);
  }
  return out;
};

const classContains = (el: HtmlElement, part: string): boolean =>
  attr(el, 'class').includes(part);

// ===== Brave 网页版 =====

export const parseBraveHtml = (html: string, limit: number): SearchResultItem[] => {
  const doc = parseHtml(html);
  const blocks: HtmlElement[] = descendants(doc.root)
    .filter((e: HtmlElement) => e.attributes['data-type'] === 'web');
  const items: SearchResultItem[] = [];
  for (const block of blocks) {
    const all: HtmlElement[] = descendants(block);
    const anchor: HtmlElement | undefined = all.find((e: HtmlElement) => e.tagName === 'a'
      && e.children.some((c: HtmlElement) => c.tagName === 'div' && classContains(c, 'title')));
    // 标题取最后一个 title/sitename-container 块(ddgs: position()=last())
    const titles: HtmlElement[] = all.filter((e: HtmlElement) => e.tagName === 'div'
      && (classContains(e, 'title') || classContains(e, 'sitename-container')));
    if (anchor === undefined || titles.length === 0) continue;
    const snippet: HtmlElement | undefined =
      all.find((e: HtmlElement) => e.tagName === 'div' && classContains(e, 'snippet'));
    const content: HtmlElement | undefined = snippet === undefined ? undefined
      : descendants(snippet).find((e: HtmlElement) => e.tagName === 'div' && classContains(e, 'content'));
    items.push({
      title: elementText(titles[titles.length - 1]).trim(),
      url: attr(anchor, 'href').trim(),
      text: content !== undefined ? elementText(content).trim() : '',
      publishedAt: null,
      images: [],
    });
  }
  return distinctTake(items, limit);
};

export const braveWebSearch = async (
  query: string, commonOptions: SearchCommonOptions,
  context?: SearchRequestContext,
): Promise<SearchResult> => {
  const html: string = await fetchHtml(
    'Brave',
    `https://search.brave.com/search?q=${javaUrlEncodeForm(query)}&source=web`,
    {
      'User-Agent': DESKTOP_UA,
      'Accept': HTML_ACCEPT,
      'Accept-Language': searchSdkRuntime().acceptLanguage(),
      'Cookie': 'safesearch=off; useLocation=0; summarizer=0',
    },
    context,
  );
  const items: SearchResultItem[] = parseBraveHtml(html, commonOptions.resultSize);
  if (items.length > 0) return { answer: null, items };
  if (/captcha/i.test(html)) throw new Error('Brave requires human verification');
  throw new Error('Brave returned no parseable results');
};

// ===== 360 搜索 =====

// 360 自家聚合页(新闻/图片/视频卡片)不是正文来源
const SO360_SKIP_HOSTS: string[] = [
  'www.so.com', 'm.so.com', 'news.so.com', 'image.so.com', 'video.so.com', 'ranks.hao.360.com',
];

export const parseSo360Html = (html: string, limit: number): SearchResultItem[] => {
  const doc = parseHtml(html);
  const items: SearchResultItem[] = [];
  for (const li of select(doc, 'li.res-list')) {
    const anchor: HtmlElement | null = selectFirst(li, 'h3 a');
    if (anchor === null) continue;
    const href: string = attr(anchor, 'href').trim();
    const mdurl: string = attr(anchor, 'data-mdurl').trim();
    // so.com/link 跳转优先用 data-mdurl 真实地址
    const url: string = hostOf(href) === 'www.so.com' && mdurl.startsWith('http') ? mdurl : href;
    if (SO360_SKIP_HOSTS.includes(hostOf(url))) continue;
    const snippetEl: HtmlElement | null =
      selectFirst(li, '.res-desc') ?? selectFirst(li, '.res-rich') ?? selectFirst(li, 'p');
    items.push({
      title: tidyCjk(elementText(anchor)),
      url,
      text: snippetEl !== null ? tidyCjk(elementText(snippetEl)) : '',
      publishedAt: null,
      images: [],
    });
  }
  return distinctTake(items, limit);
};

export const so360Search = async (
  query: string, commonOptions: SearchCommonOptions,
  context?: SearchRequestContext,
): Promise<SearchResult> => {
  const html: string = await fetchHtml(
    '360',
    `https://www.so.com/s?q=${javaUrlEncodeForm(query)}`,
    { 'User-Agent': DESKTOP_UA, 'Accept': HTML_ACCEPT, 'Accept-Language': 'zh-CN,zh;q=0.9' },
    context,
  );
  const items: SearchResultItem[] = parseSo360Html(html, commonOptions.resultSize);
  if (items.length > 0) return { answer: null, items };
  if (/qcaptcha|安全验证|captcha/i.test(html)) throw new Error('360 requires human verification');
  throw new Error('360 returned no parseable results');
};

// ===== 夸克 =====

// 夸克把结果卡片放在 <script type="application/json" id="s-data-*"> 里
const QUARK_BLOB_RE: RegExp =
  /<script[^>]*type="application\/json"[^>]*id="s-data-[^"]*"[^>]*>([\s\S]*?)<\/script>/g;

// 站内视频/聚合页不是可抓取的正文来源
const quarkSkipUrl = (url: string): boolean => {
  const host: string = hostOf(url);
  return host.endsWith('sm.cn') || host.endsWith('quark.cn');
};

const str = (v: JsonValue | undefined): string => strOrNull(v) ?? '';

export const parseQuarkHtml = (html: string, limit: number): SearchResultItem[] => {
  const items: SearchResultItem[] = [];
  QUARK_BLOB_RE.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = QUARK_BLOB_RE.exec(html)) !== null) {
    let parsed: JsonValue;
    try {
      parsed = JSON.parse(m[1]) as JsonValue;
    } catch {
      continue;
    }
    const init: JsonObject = asRecord(asRecord(asRecord(parsed)['data'])['initialData']);
    let title: string = '';
    let url: string = '';
    let text: string = '';
    const titleProps: JsonObject = asRecord(init['titleProps']);
    const sourceProps: JsonObject = asRecord(init['sourceProps']);
    if (str(sourceProps['dest_url']).length > 0) {
      // 普通网页卡片(ss_text 等)
      title = str(titleProps['content']);
      url = str(sourceProps['dest_url']);
      text = str(asRecord(init['summaryProps'])['content']);
    } else if (str(init['title']).length > 0 && str(init['url']).startsWith('http')) {
      // 摘要卡片(ss_kv / nature_result)
      title = str(init['title']);
      url = str(init['url']);
      text = str(init['desc']) || str(init['text']);
    }
    if (url.length === 0 || quarkSkipUrl(url)) continue;
    items.push({ title: stripTags(title), url, text: stripTags(text), publishedAt: null, images: [] });
  }
  return distinctTake(items, limit);
};

export const quarkSearch = async (
  query: string, commonOptions: SearchCommonOptions,
  context?: SearchRequestContext,
): Promise<SearchResult> => {
  const html: string = await fetchHtml(
    '夸克',
    `https://quark.sm.cn/s?q=${javaUrlEncodeForm(query)}&safe=1`,
    { 'User-Agent': MOBILE_UA, 'Accept': HTML_ACCEPT, 'Accept-Language': 'zh-CN,zh;q=0.9' },
    context,
  );
  const items: SearchResultItem[] = parseQuarkHtml(html, commonOptions.resultSize);
  if (items.length > 0) return { answer: null, items };
  if (/captcha|验证码/i.test(html)) throw new Error('Quark requires human verification');
  throw new Error('Quark returned no parseable results');
};

// ===== 统一分派 =====

const dispatch = (
  id: FreeEngineId, query: string, commonOptions: SearchCommonOptions,
  context?: SearchRequestContext,
): Promise<SearchResult> => {
  switch (id) {
    case 'duckduckgo': return duckDuckGoSearch(query, commonOptions, context);
    case 'brave': return braveWebSearch(query, commonOptions, context);
    case 'bing':
      return bingSearchService.search({ query }, commonOptions, makeSearchServiceOptions('bing_local'), context);
    case 'so360': return so360Search(query, commonOptions, context);
    case 'quark': return quarkSearch(query, commonOptions, context);
    case 'wikipedia': return wikipediaSearch(query, commonOptions, context);
    case 'hackernews': return hackerNewsSearch(query, commonOptions, context);
  }
};

// 熔断中的引擎直接跳过(抛错,由聚合层记为失败源);遇验证页/限流则熔断
export const runFreeEngine = async (
  id: FreeEngineId, query: string, commonOptions: SearchCommonOptions,
  context?: SearchRequestContext,
): Promise<SearchResult> => {
  if (context?.signal?.aborted) {
    const error = new Error('search aborted');
    error.name = 'AbortError';
    throw error;
  }
  if (freeEngineCoolingDown(id)) {
    throw new Error(`${FREE_ENGINE_NAMES[id]} skipped: cooling down after verification page`);
  }
  try {
    return await dispatch(id, query, commonOptions, context);
  } catch (e) {
    const err: Error = e instanceof Error ? e : new Error(String(e));
    if (context?.signal?.aborted || err.name === 'AbortError') throw err;
    if (isBlockedError(err)) breakerUntil.set(id, Date.now() + BREAKER_COOLDOWN_MS);
    throw err;
  }
};
