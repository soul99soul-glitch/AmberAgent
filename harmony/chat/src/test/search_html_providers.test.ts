// search_html_providers.test.ts — D-067 HTML 抓取源(Bing + DuckDuckGo)
// Android 基准: BingSearchService.kt(全文 133 行)+ DuckDuckGoSearchService.kt(全文 109 行)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { HttpClient, HttpRequest, HttpResponse } from '@amber/deepread-domain';
import type { SearchServiceOptions } from '../main/ets/search/search_service.ts';
import {
  makeSearchServiceOptions, initSearchSdk,
} from '../main/ets/search/search_service.ts';
import { bingSearchService } from '../main/ets/search/bing_service.ts';
import { duckDuckGoSearch } from '../main/ets/search/duckduckgo_service.ts';

interface CapturedCall { req: HttpRequest; }
const mockHttpSeq = (responses: HttpResponse[], captured: CapturedCall[]): HttpClient => ({
  fetch: (req: HttpRequest): Promise<HttpResponse> => {
    captured.push({ req });
    const r: HttpResponse = responses.length > 0
      ? responses.shift() as HttpResponse
      : { status: 500, headers: {}, body: '' };
    return Promise.resolve(r);
  },
  fetchStream: (
    _req: HttpRequest,
    _opts: { onChunk: (chunk: ArrayBuffer, end: boolean) => void },
  ): Promise<HttpResponse> => Promise.reject(new Error('not used')),
});

const bingOptions = (): SearchServiceOptions => makeSearchServiceOptions('bing_local');

const BING_PAGE: string = `<html><body><ol id="b_results">
<li class="b_algo">
  <h2><a href="https://real.com/one"> Real One </a></h2>
  <div class="b_caption"><p>snippet &amp; 1</p></div>
</li>
<li class="b_algo">
  <h2><a href="https://www.bing.com/ck/a?u=https%3A%2F%2Fredir.com%2Ftwo&amp;p=1">Two</a></h2>
  <p class="b_snippet">second</p>
</li>
<li class="b_algo">
  <h2><a href="https://real.com/one">Dup</a></h2>
</li>
</ol></body></html>`;

// ===== Bing(BingSearchService.kt 全文) =====

test('bing:请求形态逐字(URL/UA/Accept 群/cookie/locale 注入)', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: BING_PAGE }], calls),
    undefined, (): string => 'en-US,en');
  await bingSearchService.search({ query: 'hello world' }, { resultSize: 10 }, bingOptions());
  const req: HttpRequest = calls[0].req;
  assert.equal(req.url, 'https://www.bing.com/search?q=hello+world');
  assert.equal(req.headers['User-Agent'],
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36');
  assert.equal(req.headers['Accept'],
    'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8');
  assert.equal(req.headers['Accept-Language'], 'en-US,en');
  assert.equal(req.headers['Accept-Encoding'], 'gzip, deflate, sdch');
  assert.equal(req.headers['Accept-Charset'], 'utf-8');
  assert.equal(req.headers['Connection'], 'keep-alive');
  assert.equal(req.headers['Referer'], 'https://www.bing.com/');
  assert.equal(req.headers['Cookie'], 'SRCHHPGUSR=ULSR=1');
});

test('bing:主解析 b_algo + u= 跳转解码 + distinctBy(url) + take', async () => {
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: BING_PAGE }], []));
  const r = await bingSearchService.search({ query: 'q' }, { resultSize: 10 }, bingOptions());
  assert.equal(r.items.length, 2); // 第三条 dup url 去重
  assert.equal(r.items[0].title, 'Real One');
  assert.equal(r.items[0].url, 'https://real.com/one');
  assert.equal(r.items[0].text, 'snippet & 1');
  assert.equal(r.items[1].url, 'https://redir.com/two');
  assert.equal(r.items[1].text, 'second');
});

test('bing:人机校验页 → blocked 文案逐字', async () => {
  initSearchSdk(mockHttpSeq(
    [{ status: 200, headers: {}, body: '<html>Please verify you are human</html>' }], []));
  await assert.rejects(
    bingSearchService.search({ query: 'q' }, { resultSize: 10 }, bingOptions()),
    (e: Error): boolean => e.message === 'Bing blocked the request with a verification page',
  );
});

test('bing:无 b_algo → fallback h2 a;全空 → no results 文案逐字', async () => {
  const fb: string = `<html><body><main>
    <h2><a href="https://fb.com/a">FB A</a></h2>
    <h2><a href="/nothttp">skip</a></h2>
  </main></body></html>`;
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: fb }], []));
  const r = await bingSearchService.search({ query: 'q' }, { resultSize: 10 }, bingOptions());
  assert.equal(r.items.length, 1);
  assert.equal(r.items[0].title, 'FB A');
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: '<html><body>nothing</body></html>' }], []));
  await assert.rejects(
    bingSearchService.search({ query: 'q' }, { resultSize: 10 }, bingOptions()),
    (e: Error): boolean => e.message === 'Search failed: no results found',
  );
});

test('bing:query 缺失 → 逐字;scrape → not supported 逐字;parameters schema', async () => {
  initSearchSdk(mockHttpSeq([], []));
  await assert.rejects(
    bingSearchService.search({}, { resultSize: 10 }, bingOptions()),
    (e: Error): boolean => e.message === 'query is required',
  );
  await assert.rejects(
    bingSearchService.scrape({ url: 'u' }, { resultSize: 10 }, bingOptions()),
    (e: Error): boolean => e.message === 'Scraping is not supported for Bing',
  );
  assert.equal(bingSearchService.name, 'Bing');
  assert.equal(bingSearchService.scrapingParameters, null);
  const p = bingSearchService.parameters;
  assert.ok(p !== null);
  assert.deepEqual(p.required, ['query']);
});

// ===== DuckDuckGo(DuckDuckGoSearchService.kt 全文) =====

const DDG_HTML: string = `<html><body>
<div class="result results_links">
  <div class="links_main result__body">
    <h2 class="result__title"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Freal.com%2Fddg1&amp;rut=x">DDG One</a></h2>
    <a class="result__snippet" href="#">snippet ddg1</a>
  </div>
</div>
<div class="result">
  <a class="result__a" href="https://duckduckgo.com/y.js?ad_domain=ads.com">Ad</a>
</div>
<div class="result">
  <a class="result__a" href="https://html.com/one">HTML One</a>
  <a class="result__snippet">html snippet</a>
</div>
</body></html>`;

test('ddg:HTML POST 请求形态 + uddg 解码 + 广告 y.js 过滤', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: DDG_HTML }], calls));
  const r = await duckDuckGoSearch('test q', { resultSize: 10 });
  assert.equal(calls.length, 1); // 不再请求 Lite
  assert.equal(calls[0].req.url, 'https://html.duckduckgo.com/html/');
  assert.equal(calls[0].req.method, 'POST');
  assert.equal(calls[0].req.body, 'q=test+q&b=&kl=wt-wt');
  assert.equal(calls[0].req.headers['Content-Type'], 'application/x-www-form-urlencoded');
  assert.equal(r.items.length, 2);
  assert.equal(r.items[0].title, 'DDG One');
  assert.equal(r.items[0].url, 'https://real.com/ddg1');
  assert.equal(r.items[0].text, 'snippet ddg1');
  assert.equal(r.items[1].url, 'https://html.com/one');
});

test('ddg:202 验证页 / 风控词 → human verification;空页 → no parseable', async () => {
  initSearchSdk(mockHttpSeq([{ status: 202, headers: {}, body: '<html>anomaly</html>' }], []));
  await assert.rejects(
    duckDuckGoSearch('q', { resultSize: 10 }),
    (e: Error): boolean => e.message === 'DuckDuckGo requires human verification',
  );
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: '<html>bots use DuckDuckGo too</html>' }], []));
  await assert.rejects(
    duckDuckGoSearch('q', { resultSize: 10 }),
    (e: Error): boolean => e.message === 'DuckDuckGo requires human verification',
  );
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: '<html>empty</html>' }], []));
  await assert.rejects(
    duckDuckGoSearch('q', { resultSize: 10 }),
    (e: Error): boolean => e.message === 'DuckDuckGo returned no parseable organic results',
  );
});

test('bing:国际版 /ck/a 跳转 u=a1<base64url> 解包(含中文路径)', async () => {
  const target: string = 'https://example.com/路径?x=1';
  const b64: string = Buffer.from(target, 'utf-8').toString('base64url');
  const page: string = `<html><body><ol id="b_results">
<li class="b_algo"><h2><a href="https://www.bing.com/ck/a?!&amp;&amp;p=abc&amp;u=a1${b64}&amp;ntb=1">Wrapped</a></h2>
<div class="b_caption"><p>s</p></div></li></ol></body></html>`;
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: page }], []));
  const r = await bingSearchService.search({ query: 'q' }, { resultSize: 10 }, bingOptions());
  assert.equal(r.items.length, 1);
  assert.equal(r.items[0].url, target);
});

// ===== Phase 6 回归:非 2xx 响应不得被当作结果页解析 =====

test('bing: HTTP 429 rate-limit page rejects with HTTP error (not "no results")', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{ status: 429, headers: {}, body: '<html><body>slow down</body></html>' }], calls),
    undefined, (): string => 'en-US,en');
  await assert.rejects(
    bingSearchService.search({ query: 'q' }, { resultSize: 10 }, bingOptions()),
    /HTTP 429/);
});

test('duckduckgo: HTTP 403 challenge page rejects with HTTP error', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{ status: 403, headers: {}, body: '<html>forbidden</html>' }], calls),
    undefined, (): string => 'en-US,en');
  await assert.rejects(
    duckDuckGoSearch('q', { resultSize: 10 }),
    /HTTP 403/);
});
