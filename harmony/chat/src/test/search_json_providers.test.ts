// search_json_providers.test.ts — D-066 免费/内置搜索源(JSON 协议三件)
// Android 基准: WikipediaSearchService.kt + HackerNewsSearchService.kt(全文)
//   + JinaSearchService.kt(全文 199 行)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { HttpClient, HttpRequest, HttpResponse } from '@amber/deepread-domain';

import type { SearchServiceOptions } from '../main/ets/search/search_service.ts';
import { makeSearchServiceOptions, initSearchSdk } from '../main/ets/search/search_service.ts';
import { javaUrlEncodeForm, javaUrlDecodeForm } from '../main/ets/search/url_codec.ts';
import { wikipediaSearch } from '../main/ets/search/wikipedia_service.ts';
import { hackerNewsSearch } from '../main/ets/search/hackernews_service.ts';
import { jinaSearchService } from '../main/ets/search/jina_service.ts';

// 捕获请求的 mock HttpClient
interface CapturedCall { req: HttpRequest; }
const mockHttp = (status: number, body: string, captured: CapturedCall[]): HttpClient => ({
  fetch: (req: HttpRequest): Promise<HttpResponse> => {
    captured.push({ req });
    return Promise.resolve({ status, headers: {}, body });
  },
  fetchStream: (
    _req: HttpRequest,
    _opts: { onChunk: (chunk: ArrayBuffer, end: boolean) => void },
  ): Promise<HttpResponse> => Promise.reject(new Error('not used')),
});

// ===== url_codec(Java URLEncoder/URLDecoder + okhttp addQueryParameter) =====

test('javaUrlEncodeForm:空格 +、保留 .-*_、~ 编码、非 ASCII UTF-8 大写 hex', () => {
  assert.equal(javaUrlEncodeForm('hello world'), 'hello+world');
  assert.equal(javaUrlEncodeForm('a.b-c*d_e'), 'a.b-c*d_e');
  assert.equal(javaUrlEncodeForm('a~b'), 'a%7Eb');
  assert.equal(javaUrlEncodeForm('中文'), '%E4%B8%AD%E6%96%87');
  assert.equal(javaUrlEncodeForm('a&b=c'), 'a%26b%3Dc');
});

test('javaUrlDecodeForm:+ 转空格、%XX 解码 UTF-8', () => {
  assert.equal(javaUrlDecodeForm('hello+world'), 'hello world');
  assert.equal(javaUrlDecodeForm('%E4%B8%AD%E6%96%87'), '中文');
  assert.equal(javaUrlDecodeForm('a%26b'), 'a&b');
});

// ===== Wikipedia(WikipediaSearchService.kt 全文) =====

test('wikipedia:CJK 查询 → zh host;ASCII → en host;参数逐字', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttp(200, JSON.stringify({ query: { search: [] } }), calls));
  await wikipediaSearch('中文查询', { resultSize: 5 });
  const url: string = calls[0].req.url;
  assert.ok(url.startsWith('https://zh.wikipedia.org/w/api.php?'));
  assert.ok(url.includes('action=query'));
  assert.ok(url.includes('list=search'));
  assert.ok(url.includes(`srsearch=${encodeURIComponent('中文查询')}`));
  assert.ok(url.includes('srlimit=5'));
  assert.ok(url.includes('format=json'));
  assert.ok(url.includes('utf8=1'));
  assert.equal(calls[0].req.headers['User-Agent'], 'AmberAgent/1.0 search (Android)');

  calls.length = 0;
  await wikipediaSearch('english query', { resultSize: 99 });
  assert.ok(calls[0].req.url.startsWith('https://en.wikipedia.org/w/api.php?'));
  assert.ok(calls[0].req.url.includes('srlimit=10')); // coerceIn(1,10)
});

test('wikipedia:映射 title/url(空格→_ 后编码)/snippet 剥标签/publishedAt', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttp(200, JSON.stringify({
    query: {
      search: [{
        title: 'Foo Bar', snippet: '<span class="x">简介</span>文本',
        timestamp: '2026-01-02T03:04:05Z',
      }],
    },
  }), calls));
  const r = await wikipediaSearch('foo', { resultSize: 10 });
  assert.equal(r.items.length, 1);
  const it = r.items[0];
  assert.equal(it.title, 'Foo Bar');
  assert.equal(it.url, 'https://en.wikipedia.org/wiki/Foo_Bar');
  assert.equal(it.text, '简介文本');
  assert.equal(it.publishedAt, '2026-01-02T03:04:05Z');
});

test('wikipedia:非 2xx → 抛错文案逐字', async () => {
  initSearchSdk(mockHttp(503, '', []));
  await assert.rejects(
    wikipediaSearch('x', { resultSize: 10 }),
    (e: Error): boolean => e.message === 'Wikipedia request failed #503',
  );
});

// ===== Hacker News(HackerNewsSearchService.kt 全文) =====

test('hn:URL 参数逐字(query/tags=story/hitsPerPage coerceIn(1,20))', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttp(200, JSON.stringify({ hits: [] }), calls));
  await hackerNewsSearch('ai news', { resultSize: 99 });
  const url: string = calls[0].req.url;
  assert.ok(url.startsWith('https://hn.algolia.com/api/v1/search?'));
  assert.ok(url.includes('query=ai%20news'));
  assert.ok(url.includes('tags=story'));
  assert.ok(url.includes('hitsPerPage=20'));
});

test('hn:映射 title→story_title→跳过;url 缺省 objectID 兜底;points/comments null→0', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttp(200, JSON.stringify({
    hits: [
      { objectID: '1', title: 'T1', url: 'https://a.b/c', points: 5, num_comments: 3, created_at: '2026-01-01' },
      { objectID: '2', story_title: 'ST', points: null, num_comments: null },
      { objectID: '3' }, // 无 title/story_title → 跳过
    ],
  }), calls));
  const r = await hackerNewsSearch('x', { resultSize: 10 });
  assert.equal(r.items.length, 2);
  assert.equal(r.items[0].title, 'T1');
  assert.equal(r.items[0].text, 'HN points=5, comments=3');
  assert.equal(r.items[0].publishedAt, '2026-01-01');
  assert.equal(r.items[1].title, 'ST');
  assert.equal(r.items[1].url, 'https://news.ycombinator.com/item?id=2');
  assert.equal(r.items[1].text, 'HN points=0, comments=0');
});

test('hn:非 2xx → 抛错文案逐字', async () => {
  initSearchSdk(mockHttp(500, '', []));
  await assert.rejects(
    hackerNewsSearch('x', { resultSize: 10 }),
    (e: Error): boolean => e.message === 'Hacker News request failed #500',
  );
});

test('jina search:POST 默认 URL + body {q} + 无 key 不带 Authorization', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttp(200, JSON.stringify({
    code: 200, status: 200,
    data: [{ title: 't', url: 'u', description: 'd' }],
  }), calls));
  const jina = makeSearchServiceOptions('jina');
  const r = await jinaSearchService.search({ query: 'q1' }, { resultSize: 10 }, jina);
  const req: HttpRequest = calls[0].req;
  assert.equal(req.method, 'POST');
  assert.equal(req.url, 'https://s.jina.ai/');
  assert.equal(req.body, JSON.stringify({ q: 'q1' }));
  assert.equal(req.headers['Accept'], 'application/json');
  assert.equal(req.headers['Content-Type'], 'application/json');
  assert.equal(req.headers['Authorization'], undefined);
  assert.equal(r.items.length, 1);
  assert.equal(r.items[0].text, 'd');
});

test('jina search:apiKey 非空 → Bearer;自定义 URL;take(resultSize)', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttp(200, JSON.stringify({
    code: 200, status: 200,
    data: [
      { title: '1', url: 'u1', description: 'a' },
      { title: '2', url: 'u2', description: 'b' },
      { title: '3', url: 'u3', description: 'c' },
    ],
  }), calls));
  const jina: SearchServiceOptions = makeSearchServiceOptions('jina');
  if (jina.type === 'jina') {
    jina.apiKey = 'secret';
    jina.searchUrl = 'https://custom.jina/';
  }
  const r = await jinaSearchService.search({ query: 'q' }, { resultSize: 2 }, jina);
  assert.equal(calls[0].req.url, 'https://custom.jina/');
  assert.equal(calls[0].req.headers['Authorization'], 'Bearer secret');
  assert.equal(r.items.length, 2);
});

test('jina search:query 缺失 → error 逐字;非 2xx → response failed #code', async () => {
  initSearchSdk(mockHttp(401, '', []));
  const jina = makeSearchServiceOptions('jina');
  await assert.rejects(
    jinaSearchService.search({}, { resultSize: 10 }, jina),
    (e: Error): boolean => e.message === 'query is required',
  );
  await assert.rejects(
    jinaSearchService.search({ query: 'q' }, { resultSize: 10 }, jina),
    (e: Error): boolean => e.message === 'response failed #401',
  );
});

test('jina scrape:POST scrapeUrl + X-Return-Format markdown + 元数据映射', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttp(200, JSON.stringify({
    code: 200, status: 200,
    data: { title: 'T', description: 'D', url: 'https://x.y/', content: '# md' },
  }), calls));
  const jina = makeSearchServiceOptions('jina');
  const r = await jinaSearchService.scrape({ url: 'https://x.y/' }, { resultSize: 10 }, jina);
  const req: HttpRequest = calls[0].req;
  assert.equal(req.url, 'https://r.jina.ai/');
  assert.equal(req.body, JSON.stringify({ url: 'https://x.y/' }));
  assert.equal(req.headers['X-Return-Format'], 'markdown');
  assert.equal(r.urls.length, 1);
  assert.equal(r.urls[0].url, 'https://x.y/');
  assert.equal(r.urls[0].content, '# md');
  assert.equal(r.urls[0].metadata?.title, 'T');
  assert.equal(r.urls[0].metadata?.description, 'D');
});

test('jina scrape:url 缺失 → Kotlin 笔误文案逐字(urls is required);非 2xx 文案', async () => {
  initSearchSdk(mockHttp(404, '', []));
  const jina = makeSearchServiceOptions('jina');
  await assert.rejects(
    jinaSearchService.scrape({}, { resultSize: 10 }, jina),
    (e: Error): boolean => e.message === 'urls is required',
  );
  await assert.rejects(
    jinaSearchService.scrape({ url: 'u' }, { resultSize: 10 }, jina),
    (e: Error): boolean => e.message === 'response failed for url u #404',
  );
});
