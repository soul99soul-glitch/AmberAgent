// search_keyed_providers_c.test.ts — D-068c 需 key 源批次 C + getService 分派
// Android 基准: Tavily/Bocha/SearXNG/Grok/Firecrawl SearchService(全文)
//   + SearchService.kt:46-66(getService when 分派)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { HttpClient, HttpRequest, HttpResponse } from '@amber/deepread-domain';
import type { SearchServiceOptions } from '../main/ets/search/search_service.ts';
import {
  makeSearchServiceOptions, initSearchSdk, createDefaultKeyRoulette,
  SEARCH_SERVICE_TYPES,
} from '../main/ets/search/search_service.ts';
import { tavilySearchService } from '../main/ets/search/tavily_service.ts';
import { bochaSearchService } from '../main/ets/search/bocha_service.ts';
import { searXNGService } from '../main/ets/search/searxng_service.ts';
import { grokSearchService } from '../main/ets/search/grok_service.ts';
import { firecrawlSearchService } from '../main/ets/search/firecrawl_service.ts';
import { getSearchService } from '../main/ets/search/service_registry.ts';

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

// ===== Tavily(TavilySearchService.kt 全文) =====

test('tavily:topic 校验 + 体五键(search_depth 空回 advanced/include_answer advanced)+ 图片挂首条', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{
    status: 200, headers: {},
    body: JSON.stringify({
      query: 'q', answer: 'ans', images: ['i1', 'i1', 'i2'],
      results: [
        { title: 't1', url: 'u1', content: 'c1', score: 0.9 },
        { title: 't2', url: 'u2', content: 'c2', score: 0.8 },
      ],
    }),
  }], calls));
  const o = opts('tavily');
  if (o.type === 'tavily') o.depth = ''; // ifEmpty → advanced
  const r = await tavilySearchService.search({ query: 'q', topic: 'news' }, { resultSize: 10 }, o);
  assert.deepEqual(JSON.parse(calls[0].req.body as string),
    { query: 'q', max_results: 10, search_depth: 'advanced', topic: 'news', include_answer: 'advanced' });
  assert.equal(r.answer, 'ans');
  assert.deepEqual(r.items[0].images, ['i1', 'i2']);
  assert.deepEqual(r.items[1].images, []);
  await assert.rejects(
    tavilySearchService.search({ query: 'q', topic: 'bogus' }, { resultSize: 10 }, o),
    (e: Error): boolean => e.message === 'topic must be one of `general`, `news`, `finance`',
  );
});

test('tavily:scrape /extract urls 数组 + raw_content 映射;失败文案', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{
    status: 200, headers: {},
    body: JSON.stringify({ results: [{ url: 'u1', raw_content: 'raw' }] }),
  }], calls));
  const r = await tavilySearchService.scrape({ url: 'u1' }, { resultSize: 10 }, opts('tavily'));
  assert.equal(calls[0].req.url, 'https://api.tavily.com/extract');
  assert.deepEqual(JSON.parse(calls[0].req.body as string), { urls: ['u1'] });
  assert.equal(r.urls[0].content, 'raw');
  assert.equal(r.urls[0].metadata, null);
  initSearchSdk(mockHttpSeq([{ status: 500, headers: {}, body: 'x' }], []));
  await assert.rejects(
    tavilySearchService.scrape({ url: 'u' }, { resultSize: 10 }, opts('tavily')),
    (e: Error): boolean => e.message === 'response failed #500',
  );
});

// ===== Bocha(BochaSearchService.kt 全文) =====

test('bocha:体三键(summary 布尔透传)+ code!=200 API error + summary 优先 snippet', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{
    status: 200, headers: {},
    body: JSON.stringify({
      code: 200,
      data: { webPages: { value: [
        { name: 'n1', url: 'u1', snippet: 'snip', summary: 'sum' },
        { name: 'n2', url: 'u2', snippet: 'snip2', summary: null },
      ] } },
    }),
  }], calls));
  const o = opts('bocha');
  if (o.type === 'bocha') o.summary = false;
  const r = await bochaSearchService.search({ query: 'q' }, { resultSize: 10 }, o);
  assert.deepEqual(JSON.parse(calls[0].req.body as string),
    { query: 'q', summary: false, count: 10 });
  assert.equal(r.items[0].text, 'sum'); // summary ?: snippet
  assert.equal(r.items[1].text, 'snip2');
  initSearchSdk(mockHttpSeq([{
    status: 200, headers: {}, body: JSON.stringify({ code: 401, msg: 'invalid key' }),
  }], []));
  await assert.rejects(
    bochaSearchService.search({ query: 'q' }, { resultSize: 10 }, o),
    (e: Error): boolean => e.message === 'Bocha API error: invalid key',
  );
});

test('bocha:msg null → Unknown error;HTTP 失败/解码失败;scrape 逐字', async () => {
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: JSON.stringify({ code: 500 }) }], []));
  await assert.rejects(
    bochaSearchService.search({ query: 'q' }, { resultSize: 10 }, opts('bocha')),
    (e: Error): boolean => e.message === 'Bocha API error: Unknown error',
  );
  initSearchSdk(mockHttpSeq([{ status: 502, headers: {}, body: '' }], []));
  await assert.rejects(
    bochaSearchService.search({ query: 'q' }, { resultSize: 10 }, opts('bocha')),
    (e: Error): boolean => e.message === 'Bocha response failed #502',
  );
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: '{bad' }], []));
  await assert.rejects(
    bochaSearchService.search({ query: 'q' }, { resultSize: 10 }, opts('bocha')),
    (e: Error): boolean => e.message.startsWith('Failed to decode Bocha response (4 chars): '),
  );
  await assert.rejects(
    bochaSearchService.scrape({}, { resultSize: 10 }, opts('bocha')),
    (e: Error): boolean => e.message === 'Scraping is not supported for Bocha',
  );
});

// ===== SearXNG(SearXNGService.kt 全文) =====

test('searxng:URL require/engines+language 条件键/Basic auth;图片解析(http///相对)', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{
    status: 200, headers: {},
    body: JSON.stringify({
      query: 'q', number_of_results: 1,
      results: [{
        url: 'u', title: 't', content: 'c', engine: 'e', template: 't',
        thumbnail: '//cdn.x.com/t.jpg', img_src: 'img/p.png',
      }],
    }),
  }], calls));
  const o = makeSearchServiceOptions('searxng');
  if (o.type === 'searxng') {
    o.url = 'https://sx.example.com/';
    o.engines = 'google,bing';
    o.language = 'zh';
    o.username = 'user';
    o.password = 'pass';
  }
  const r = await searXNGService.search({ query: 'a b' }, { resultSize: 10 }, o);
  const req: HttpRequest = calls[0].req;
  assert.ok(req.url.startsWith('https://sx.example.com/search?q=a+b&format=json'));
  assert.ok(req.url.includes('engines=google%2Cbing'));
  assert.ok(req.url.includes('language=zh'));
  // Credentials.basic("user","pass") = Basic dXNlcjpwYXNz
  assert.equal(req.headers['Authorization'], 'Basic dXNlcjpwYXNz');
  assert.deepEqual(r.items[0].images, ['https://cdn.x.com/t.jpg', 'https://sx.example.com/img/p.png']);
});

test('searxng:空 url require 逐字;无凭据无 auth;失败/解码失败;scrape 逐字', async () => {
  const o = makeSearchServiceOptions('searxng');
  initSearchSdk(mockHttpSeq([], []));
  await assert.rejects(
    searXNGService.search({ query: 'q' }, { resultSize: 10 }, o),
    (e: Error): boolean => e.message === 'SearXNG URL cannot be empty',
  );
  if (o.type === 'searxng') o.url = 'https://sx.example.com';
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{ status: 500, headers: {}, body: '' }], calls));
  await assert.rejects(
    searXNGService.search({ query: 'q' }, { resultSize: 10 }, o),
    (e: Error): boolean => e.message === 'SearXNG request failed with status 500',
  );
  assert.equal(calls[0].req.headers['Authorization'], undefined);
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: '{bad' }], []));
  await assert.rejects(
    searXNGService.search({ query: 'q' }, { resultSize: 10 }, o),
    (e: Error): boolean => e.message.startsWith('Failed to decode SearXNG response (4 chars): '),
  );
  await assert.rejects(
    searXNGService.scrape({}, { resultSize: 10 }, o),
    (e: Error): boolean => e.message === 'Scraping is not supported for SearXNG',
  );
});

// ===== Grok(GrokSearchService.kt 全文) =====

test('grok:体 model/input 双消息/tools 双件/store false;assistant output_text + 引用映射', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{
    status: 200, headers: {},
    body: JSON.stringify({
      output: [
        { type: 'web_search_call', role: null },
        {
          type: 'message', role: 'assistant',
          content: [{
            type: 'output_text', text: '综合答案',
            annotations: [
              { type: 'url_citation', url: 'https://a.com/1' },
              { type: 'url_citation', url: 'https://a.com/1' },
              { type: 'url_citation', url: '' },
              { type: 'file_citation', url: 'https://skip.com' },
              { type: 'url_citation', url: 'https://b.com/2' },
            ],
          }],
        },
      ],
    }),
  }], calls));
  const r = await grokSearchService.search({ query: 'q' }, { resultSize: 10 }, opts('grok'));
  const body = JSON.parse(calls[0].req.body as string) as Record<string, unknown>;
  assert.equal(calls[0].req.url, 'https://api.x.ai/v1/responses');
  assert.equal(body['model'], 'grok-4-1-fast-non-reasoning');
  assert.deepEqual(body['tools'], [{ type: 'web_search' }, { type: 'x_search' }]);
  assert.equal(body['store'], false);
  const input = body['input'] as Array<Record<string, string>>;
  assert.equal(input[0]['role'], 'system');
  assert.equal(input[1]['role'], 'user');
  assert.equal(input[1]['content'], 'q');
  assert.equal(r.answer, '综合答案');
  assert.equal(r.items.length, 2); // 空 url + file_citation 过滤,distinct
  assert.equal(r.items[0].title, 'https://a.com/1'); // title = url 逐字
  assert.equal(r.items[1].url, 'https://b.com/2');
});

test('grok:空 key error 逐字;失败带 body;scrape 逐字', async () => {
  initSearchSdk(mockHttpSeq([], []));
  await assert.rejects(
    grokSearchService.search({ query: 'q' }, { resultSize: 10 }, opts('grok', ' ')),
    (e: Error): boolean => e.message === 'Grok API key is required',
  );
  initSearchSdk(mockHttpSeq([{ status: 401, headers: {}, body: 'denied' }], []));
  await assert.rejects(
    grokSearchService.search({ query: 'q' }, { resultSize: 10 }, opts('grok')),
    (e: Error): boolean => e.message === 'response failed #401: denied',
  );
  await assert.rejects(
    grokSearchService.scrape({}, { resultSize: 10 }, opts('grok')),
    (e: Error): boolean => e.message === 'Scraping is not supported for Grok',
  );
});

// ===== Firecrawl(FirecrawlSearchService.kt 全文) =====

test('firecrawl:sources/categories asStringList(数组/逗号串)条件落键;web+news 映射', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{
    status: 200, headers: {},
    body: JSON.stringify({
      data: {
        web: [{ url: 'wu', title: 'wt', description: 'wd' }],
        news: [{ title: 'nt', url: 'nu', snippet: 'ns', date: '2026-01-01' }],
      },
    }),
  }], calls));
  const r = await firecrawlSearchService.search(
    { query: 'q', sources: ['web', 'news'], categories: 'github, research' },
    { resultSize: 10 }, opts('firecrawl'));
  assert.deepEqual(JSON.parse(calls[0].req.body as string),
    { query: 'q', limit: 10, sources: ['web', 'news'], categories: ['github', 'research'] });
  assert.equal(r.items.length, 2);
  assert.equal(r.items[0].text, 'wd');
  assert.equal(r.items[1].text, 'ns\n2026-01-01'); // trimIndent 模板
});

test('firecrawl:**bug 钉住** search 失败文案为字面量;空 data 逐字;scrape 全链', async () => {
  initSearchSdk(mockHttpSeq([{ status: 500, headers: {}, body: '' }], []));
  await assert.rejects(
    firecrawlSearchService.search({ query: 'q' }, { resultSize: 10 }, opts('firecrawl')),
    // Android "response failed #${'$'}{response.code}" — ${'$'} 渲染 $ 后其余为字面量
    (e: Error): boolean => e.message === 'response failed #{response.code}',
  );
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: JSON.stringify({}) }], []));
  await assert.rejects(
    firecrawlSearchService.search({ query: 'q' }, { resultSize: 10 }, opts('firecrawl')),
    (e: Error): boolean => e.message === 'empty response data',
  );
  // scrape:onlyMainContent 缺省 true;maxAge/parsers/formats 固定;success false → 逐字
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{
    status: 200, headers: {},
    body: JSON.stringify({ success: true, data: { markdown: '# md' } }),
  }], calls));
  const r = await firecrawlSearchService.scrape({ url: 'u' }, { resultSize: 10 }, opts('firecrawl'));
  assert.deepEqual(JSON.parse(calls[0].req.body as string),
    { url: 'u', onlyMainContent: true, maxAge: 172800000, parsers: [], formats: ['markdown'] });
  assert.equal(r.urls[0].content, '# md');
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: JSON.stringify({ success: false }) }], []));
  await assert.rejects(
    firecrawlSearchService.scrape({ url: 'u' }, { resultSize: 10 }, opts('firecrawl')),
    (e: Error): boolean => e.message === 'scrape request failed',
  );
});

// ===== getService 分派(SearchService.kt:46-66) =====

test('getSearchService:17 类全分派且 name 逐字', () => {
  const expected: Record<string, string> = {
    bing_local: 'Bing', zhipu: 'Zhipu', tavily: 'Tavily', exa: 'Exa',
    searxng: 'SearXNG', linkup: 'LinkUp', brave: 'Brave', serper: 'Serper',
    serpapi: 'SerpAPI', metaso: 'Metaso', ollama: 'Ollama',
    perplexity: 'Perplexity', firecrawl: 'Firecrawl', jina: 'Jina',
    bocha: 'Bocha', amber_agent: 'AmberAgent', grok: 'Grok',
  };
  for (const t of Object.keys(SEARCH_SERVICE_TYPES)) {
    const svc = getSearchService(makeSearchServiceOptions(t as SearchServiceOptions['type']));
    assert.equal(svc.name, expected[t], `dispatch mismatch for ${t}`);
  }
});
