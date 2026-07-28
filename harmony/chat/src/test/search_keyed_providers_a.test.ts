// search_keyed_providers_a.test.ts — D-068a 需 key 源批次 A
// Android 基准: AmberAgent/Ollama/Serper/SerpApi/Zhipu SearchService(全文)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { HttpClient, HttpRequest, HttpResponse } from '@amber/deepread-domain';
import type { SearchServiceOptions } from '../main/ets/search/search_service.ts';
import {
  makeSearchServiceOptions, initSearchSdk,
} from '../main/ets/search/search_service.ts';
import { amberAgentSearchService } from '../main/ets/search/amberagent_service.ts';
import { ollamaSearchService } from '../main/ets/search/ollama_service.ts';
import { serperSearchService } from '../main/ets/search/serper_service.ts';
import { serpApiSearchService } from '../main/ets/search/serpapi_service.ts';
import { zhipuSearchService } from '../main/ets/search/zhipu_service.ts';

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

const opts = (t: SearchServiceOptions['type'], apiKey: string = 'k'): SearchServiceOptions => {
  const o = makeSearchServiceOptions(t);
  if ('apiKey' in o) o.apiKey = apiKey;
  return o;
};

// ===== AmberAgent(AmberAgentSearchService.kt 全文) =====

test('amberagent:POST 体四键全 JsonPrimitive 字符串 + Bearer + answer/sources 映射', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{
    status: 200, headers: {},
    body: JSON.stringify({
      answer: '综合答案',
      sources: [{ name: 'n', url: 'u', snippet: 's' }],
    }),
  }], calls));
  const r = await amberAgentSearchService.search(
    { query: 'q' }, { resultSize: 10 }, opts('amber_agent'));
  const req: HttpRequest = calls[0].req;
  assert.equal(req.url, 'https://api.rikka-ai.com/v1/search');
  assert.equal(req.headers['Authorization'], 'Bearer k');
  assert.deepEqual(JSON.parse(req.body as string),
    { q: 'q', depth: 'standard', outputType: 'sourcedAnswer', includeImages: 'false' });
  assert.equal(r.answer, '综合答案');
  assert.equal(r.items[0].title, 'n');
  assert.equal(r.items[0].text, 's');
});

test('amberagent:失败带 body 文案逐字;scrape error() 逐字', async () => {
  initSearchSdk(mockHttpSeq([{ status: 429, headers: {}, body: 'rate limited' }], []));
  await assert.rejects(
    amberAgentSearchService.search({ query: 'q' }, { resultSize: 10 }, opts('amber_agent')),
    (e: Error): boolean => e.message === 'response failed #429: rate limited',
  );
  await assert.rejects(
    amberAgentSearchService.scrape({}, { resultSize: 10 }, opts('amber_agent')),
    (e: Error): boolean => e.message === 'AmberAgent does not support scraping',
  );
});

// ===== Ollama(OllamaSearchService.kt 全文) =====

test('ollama:body query+max_results coerceIn(5..10);映射 content', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{
    status: 200, headers: {},
    body: JSON.stringify({ results: [{ title: 't', url: 'u', content: 'c' }] }),
  }], calls));
  const r = await ollamaSearchService.search({ query: 'q' }, { resultSize: 1 }, opts('ollama'));
  const req: HttpRequest = calls[0].req;
  assert.equal(req.url, 'https://ollama.com/api/web_search');
  assert.deepEqual(JSON.parse(req.body as string), { query: 'q', max_results: 5 });
  assert.equal(r.items[0].text, 'c');
});

test('ollama:失败文案(无 status message → 尾空,偏差登记);scrape 逐字', async () => {
  initSearchSdk(mockHttpSeq([{ status: 403, headers: {}, body: '' }], []));
  await assert.rejects(
    ollamaSearchService.search({ query: 'q' }, { resultSize: 10 }, opts('ollama')),
    (e: Error): boolean => e.message === 'Ollama search failed with code 403: ',
  );
  await assert.rejects(
    ollamaSearchService.scrape({}, { resultSize: 10 }, opts('ollama')),
    (e: Error): boolean => e.message === 'Scraping is not supported for Ollama',
  );
});

// ===== Serper(SerperSearchService.kt 全文) =====

test('serper:无 key require 逐字;topic=news → news endpoint;映射 news?:organic', async () => {
  initSearchSdk(mockHttpSeq([], []));
  await assert.rejects(
    serperSearchService.search({ query: 'q' }, { resultSize: 10 }, opts('serper', ' ')),
    (e: Error): boolean => e.message === 'Serper API key is required',
  );
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{
    status: 200, headers: {},
    body: JSON.stringify({
      organic: [{ title: 'o', link: 'lu', snippet: null, date: '2026-01-01' }],
    }),
  }], calls));
  const r = await serperSearchService.search(
    { query: 'q', topic: 'news' }, { resultSize: 99 }, opts('serper'));
  const req: HttpRequest = calls[0].req;
  assert.equal(req.url, 'https://google.serper.dev/news');
  assert.equal(req.headers['X-API-KEY'], 'k');
  assert.deepEqual(JSON.parse(req.body as string), { q: 'q', num: 20 }); // coerceIn(1,20)
  assert.equal(r.items[0].title, 'o');
  assert.equal(r.items[0].url, 'lu');
  assert.equal(r.items[0].text, ''); // snippet.orEmpty()
  assert.equal(r.items[0].publishedAt, '2026-01-01');
});

test('serper:非 news topic → search endpoint;失败文案;scrape 逐字', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{ status: 500, headers: {}, body: '' }], calls));
  await assert.rejects(
    serperSearchService.search({ query: 'q', topic: 'general' }, { resultSize: 10 }, opts('serper')),
    (e: Error): boolean => e.message === 'Serper request failed #500',
  );
  assert.equal(calls[0].req.url, 'https://google.serper.dev/search');
  await assert.rejects(
    serperSearchService.scrape({}, { resultSize: 10 }, opts('serper')),
    (e: Error): boolean => e.message === 'Scraping is not supported for Serper',
  );
});

// ===== SerpAPI(SerpApiSearchService.kt 全文) =====

test('serpapi:GET query 链(engine/q/api_key/num/tbm=nws 仅 news);映射', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{
    status: 200, headers: {},
    body: JSON.stringify({
      news_results: [{ title: 'n', link: 'l', snippet: 's', date: 'd' }],
    }),
  }], calls));
  const r = await serpApiSearchService.search(
    { query: 'q q', topic: 'news' }, { resultSize: 10 }, opts('serpapi'));
  const url: string = calls[0].req.url;
  assert.ok(url.startsWith('https://serpapi.com/search.json?'));
  assert.ok(url.includes('engine=google'));
  assert.ok(url.includes('q=q%20q'));
  assert.ok(url.includes('api_key=k'));
  assert.ok(url.includes('num=10'));
  assert.ok(url.includes('tbm=nws'));
  assert.equal(r.items[0].url, 'l');
  assert.equal(r.items[0].publishedAt, 'd');
});

test('serpapi:无 key require 逐字;非 news 无 tbm;失败文案;scrape 逐字', async () => {
  initSearchSdk(mockHttpSeq([], []));
  await assert.rejects(
    serpApiSearchService.search({ query: 'q' }, { resultSize: 10 }, opts('serpapi', '')),
    (e: Error): boolean => e.message === 'SerpAPI key is required',
  );
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{ status: 401, headers: {}, body: '' }], calls));
  await assert.rejects(
    serpApiSearchService.search({ query: 'q' }, { resultSize: 10 }, opts('serpapi')),
    (e: Error): boolean => e.message === 'SerpAPI request failed #401',
  );
  assert.ok(!calls[0].req.url.includes('tbm'));
  await assert.rejects(
    serpApiSearchService.scrape({}, { resultSize: 10 }, opts('serpapi')),
    (e: Error): boolean => e.message === 'Scraping is not supported for SerpAPI',
  );
});

// ===== Zhipu(ZhipuSearchService.kt 全文) =====

test('zhipu:body 三键(search_std/count 数字)+ search_result 映射', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{
    status: 200, headers: {},
    body: JSON.stringify({
      search_result: [{ content: 'c', icon: null, link: 'l', media: null, refer: null, title: 't' }],
    }),
  }], calls));
  const r = await zhipuSearchService.search({ query: 'q' }, { resultSize: 7 }, opts('zhipu'));
  const req: HttpRequest = calls[0].req;
  assert.equal(req.url, 'https://open.bigmodel.cn/api/paas/v4/web_search');
  assert.equal(req.headers['Authorization'], 'Bearer k');
  assert.deepEqual(JSON.parse(req.body as string),
    { search_query: 'q', search_engine: 'search_std', count: 7 });
  assert.equal(r.items[0].title, 't');
  assert.equal(r.items[0].url, 'l');
  assert.equal(r.items[0].text, 'c');
});

test('zhipu:失败/解码失败/空 body 三文案;scrape 逐字', async () => {
  initSearchSdk(mockHttpSeq([{ status: 500, headers: {}, body: 'x' }], []));
  await assert.rejects(
    zhipuSearchService.search({ query: 'q' }, { resultSize: 10 }, opts('zhipu')),
    (e: Error): boolean => e.message === 'Zhipu response failed #500',
  );
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: '{bad' }], []));
  await assert.rejects(
    zhipuSearchService.search({ query: 'q' }, { resultSize: 10 }, opts('zhipu')),
    (e: Error): boolean => e.message.startsWith('Failed to decode Zhipu response (4 chars): '),
  );
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: '' }], []));
  await assert.rejects(
    zhipuSearchService.search({ query: 'q' }, { resultSize: 10 }, opts('zhipu')),
    (e: Error): boolean => e.message === 'Failed to get response body',
  );
  await assert.rejects(
    zhipuSearchService.scrape({}, { resultSize: 10 }, opts('zhipu')),
    (e: Error): boolean => e.message === 'Scraping is not supported for Zhipu',
  );
});
