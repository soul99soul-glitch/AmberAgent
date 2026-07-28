// search_keyed_providers_b.test.ts — D-068b 需 key 源批次 B
// Android 基准: Brave/Metaso/LinkUp/Perplexity/Exa SearchService(全文)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { HttpClient, HttpRequest, HttpResponse } from '@amber/deepread-domain';
import type { SearchServiceOptions } from '../main/ets/search/search_service.ts';
import {
  makeSearchServiceOptions, initSearchSdk, createDefaultKeyRoulette,
} from '../main/ets/search/search_service.ts';
import { braveSearchService } from '../main/ets/search/brave_service.ts';
import { metasoSearchService } from '../main/ets/search/metaso_service.ts';
import { linkUpService } from '../main/ets/search/linkup_service.ts';
import { perplexitySearchService } from '../main/ets/search/perplexity_service.ts';
import { exaSearchService } from '../main/ets/search/exa_service.ts';

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

// ===== Brave(BraveSearchService.kt 全文) =====

test('brave:GET q form 编码 + count;X-Subscription-Token;thumbnail original 优先', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{
    status: 200, headers: {},
    body: JSON.stringify({
      web: {
        results: [
          { type: 'search', title: 't1', url: 'u1', description: 'd1',
            thumbnail: { src: 'https://imgs.search.brave.com/x', original: 'https://cdn.real.com/img.jpg' } },
          { type: 'search', title: 't2', url: 'u2', description: null,
            thumbnail: { src: 'https://imgs.search.brave.com/y' } },
          { type: 'search', title: 't3', url: 'u3' },
        ],
      },
    }),
  }], calls));
  const r = await braveSearchService.search({ query: 'a b' }, { resultSize: 3 }, opts('brave'));
  const req: HttpRequest = calls[0].req;
  assert.equal(req.url, 'https://api.search.brave.com/res/v1/web/search?q=a+b&count=3');
  assert.equal(req.headers['X-Subscription-Token'], 'k');
  assert.equal(req.headers['Accept'], 'application/json');
  assert.equal(r.items.length, 3);
  assert.deepEqual(r.items[0].images, ['https://cdn.real.com/img.jpg']); // original 优先
  assert.deepEqual(r.items[1].images, ['https://imgs.search.brave.com/y']); // src 兜底
  assert.equal(r.items[1].text, ''); // description ?: ""
  assert.deepEqual(r.items[2].images, []);
});

test('brave:失败文案(message 尾空登记);scrape 逐字', async () => {
  initSearchSdk(mockHttpSeq([{ status: 403, headers: {}, body: '' }], []));
  await assert.rejects(
    braveSearchService.search({ query: 'q' }, { resultSize: 10 }, opts('brave')),
    (e: Error): boolean => e.message === 'Brave search failed with code 403: ',
  );
  await assert.rejects(
    braveSearchService.scrape({}, { resultSize: 10 }, opts('brave')),
    (e: Error): boolean => e.message === 'Scraping is not supported for Brave',
  );
});

// ===== Metaso(MetasoSearchService.kt 全文) =====

test('metaso:body 四键(scope webpage/includeSummary false 布尔)+ webpages 映射', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{
    status: 200, headers: {},
    body: JSON.stringify({
      credits: 1, searchParameters: { q: 'q', scope: 'webpage', size: 5 },
      webpages: [{ title: 't', link: 'l', score: '1', snippet: null, summary: null, position: 1, date: 'd' }],
    }),
  }], calls));
  const r = await metasoSearchService.search({ query: 'q' }, { resultSize: 5 }, opts('metaso'));
  const req: HttpRequest = calls[0].req;
  assert.equal(req.url, 'https://metaso.cn/api/v1/search');
  assert.equal(req.headers['Authorization'], 'Bearer k');
  assert.deepEqual(JSON.parse(req.body as string),
    { q: 'q', scope: 'webpage', size: 5, includeSummary: false });
  assert.equal(r.items[0].url, 'l');
  assert.equal(r.items[0].text, ''); // snippet ?: ""
});

test('metaso:失败/解码失败/空 body 三文案;scrape 逐字', async () => {
  initSearchSdk(mockHttpSeq([{ status: 402, headers: {}, body: 'x' }], []));
  await assert.rejects(
    metasoSearchService.search({ query: 'q' }, { resultSize: 10 }, opts('metaso')),
    (e: Error): boolean => e.message === 'Metaso search request failed with code 402',
  );
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: '{bad' }], []));
  await assert.rejects(
    metasoSearchService.search({ query: 'q' }, { resultSize: 10 }, opts('metaso')),
    (e: Error): boolean => e.message.startsWith('Failed to decode Metaso response (4 chars): '),
  );
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: '' }], []));
  await assert.rejects(
    metasoSearchService.search({ query: 'q' }, { resultSize: 10 }, opts('metaso')),
    (e: Error): boolean => e.message === 'Failed to get response body',
  );
  await assert.rejects(
    metasoSearchService.scrape({}, { resultSize: 10 }, opts('metaso')),
    (e: Error): boolean => e.message === 'Scraping is not supported for Metaso',
  );
});

// ===== LinkUp(LinkUpService.kt 全文) =====

test('linkup:keyRoulette 选 key + search 体四键;answer/sources 映射', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(
    mockHttpSeq([{
      status: 200, headers: {},
      body: JSON.stringify({ answer: 'A', sources: [{ name: 'n', url: 'u', snippet: 's' }] }),
    }], calls),
    createDefaultKeyRoulette((): number => 0.99), // 多 key 取末位
  );
  const o = opts('linkup');
  if ('apiKey' in o) o.apiKey = 'k1, k2';
  const r = await linkUpService.search({ query: 'q' }, { resultSize: 10 }, o);
  const req: HttpRequest = calls[0].req;
  assert.equal(req.url, 'https://api.linkup.so/v1/search');
  assert.equal(req.headers['Authorization'], 'Bearer k2'); // roulette 末位
  assert.deepEqual(JSON.parse(req.body as string),
    { q: 'q', depth: 'standard', outputType: 'sourcedAnswer', includeImages: 'false' });
  assert.equal(r.answer, 'A');
  assert.equal(r.items[0].title, 'n');
});

test('linkup:scrape /v1/fetch 四布尔键 + markdown 映射;失败带 body;url 缺失逐字', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: JSON.stringify({ markdown: '# md' }) }], calls));
  const r = await linkUpService.scrape({ url: 'https://x.y/' }, { resultSize: 10 }, opts('linkup'));
  const req: HttpRequest = calls[0].req;
  assert.equal(req.url, 'https://api.linkup.so/v1/fetch');
  assert.deepEqual(JSON.parse(req.body as string),
    { url: 'https://x.y/', includeRawHtml: false, renderJs: false, extractImages: false });
  assert.equal(r.urls[0].url, 'https://x.y/');
  assert.equal(r.urls[0].content, '# md');
  assert.equal(r.urls[0].metadata, null);
  initSearchSdk(mockHttpSeq([{ status: 500, headers: {}, body: 'oops' }], []));
  await assert.rejects(
    linkUpService.scrape({ url: 'u' }, { resultSize: 10 }, opts('linkup')),
    (e: Error): boolean => e.message === 'response failed #500: oops',
  );
  await assert.rejects(
    linkUpService.scrape({}, { resultSize: 10 }, opts('linkup')),
    (e: Error): boolean => e.message === 'url is required',
  );
});

// ===== Perplexity(PerplexitySearchService.kt 全文) =====

test('perplexity:空 key error 逐字;条件 max_tokens 键;响应级图片挂首条', async () => {
  initSearchSdk(mockHttpSeq([], []));
  await assert.rejects(
    perplexitySearchService.search({ query: 'q' }, { resultSize: 10 }, opts('perplexity', ' ')),
    (e: Error): boolean => e.message === 'Perplexity API key is required',
  );
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{
    status: 200, headers: {},
    body: JSON.stringify({
      answer: 'ans',
      results: [
        { title: null, url: 'skip' },
        { title: 't1', url: 'u1', snippet: 's1' },
        { title: 't2', url: 'u2', text: 'x2' },
      ],
      images: [{ image_url: 'i1' }, { image_url: 'i1' }, { image_url: 'i2' }, { origin_url: 'o' }],
    }),
  }], calls));
  const o = opts('perplexity');
  if (o.type === 'perplexity') {
    o.maxTokens = 100;
    o.maxTokensPerPage = 0; // ≤0 不落键
  }
  const r = await perplexitySearchService.search({ query: 'q' }, { resultSize: 10 }, o);
  const body = JSON.parse(calls[0].req.body as string) as Record<string, number | string>;
  assert.equal(body['query'], 'q');
  assert.equal(body['max_results'], 10);
  assert.equal(body['max_tokens'], 100);
  assert.equal(body['max_tokens_per_page'], undefined);
  assert.equal(r.answer, 'ans');
  assert.equal(r.items.length, 2); // title null 过滤
  assert.equal(r.items[0].text, 's1');
  assert.equal(r.items[1].text, 'x2'); // snippet ?: text
  assert.deepEqual(r.items[0].images, ['i1', 'i2']); // distinct + origin_url 不取
  assert.deepEqual(r.items[1].images, []); // 仅首条挂图
});

test('perplexity:失败带 body;scrape 逐字', async () => {
  initSearchSdk(mockHttpSeq([{ status: 400, headers: {}, body: 'bad' }], []));
  await assert.rejects(
    perplexitySearchService.search({ query: 'q' }, { resultSize: 10 }, opts('perplexity')),
    (e: Error): boolean => e.message === 'response failed #400: bad',
  );
  await assert.rejects(
    perplexitySearchService.scrape({}, { resultSize: 10 }, opts('perplexity')),
    (e: Error): boolean => e.message === 'Scraping is not supported for Perplexity',
  );
});

// ===== Exa(ExaSearchService.kt 全文) =====

test('exa:body(query/numResults/type 缺省 auto/contents.text true)+ output.content + keyRoulette', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(
    mockHttpSeq([{
      status: 200, headers: {},
      body: JSON.stringify({
        results: [{ id: '1', title: 't', url: 'u', publishedDate: null, author: null, text: null }],
        output: { content: 'deep answer' },
      }),
    }], calls),
    createDefaultKeyRoulette((): number => 0),
  );
  const o = opts('exa');
  if ('apiKey' in o) o.apiKey = 'ka kb';
  const r = await exaSearchService.search({ query: 'q' }, { resultSize: 8 }, o);
  const req: HttpRequest = calls[0].req;
  assert.equal(req.url, 'https://api.exa.ai/search');
  assert.equal(req.headers['Authorization'], 'Bearer ka'); // roulette 首位
  assert.deepEqual(JSON.parse(req.body as string),
    { query: 'q', numResults: 8, type: 'auto', contents: { text: true } });
  assert.equal(r.answer, 'deep answer');
  assert.equal(r.items[0].text, ''); // text ?: ""
});

test('exa:type 透传;失败/解码失败文案;scrape 逐字', async () => {
  const calls: CapturedCall[] = [];
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: JSON.stringify({ results: [] }) }], calls));
  await exaSearchService.search({ query: 'q', type: 'deep' }, { resultSize: 8 }, opts('exa'));
  assert.deepEqual(JSON.parse(calls[0].req.body as string),
    { query: 'q', numResults: 8, type: 'deep', contents: { text: true } });
  initSearchSdk(mockHttpSeq([{ status: 503, headers: {}, body: '' }], []));
  await assert.rejects(
    exaSearchService.search({ query: 'q' }, { resultSize: 8 }, opts('exa')),
    (e: Error): boolean => e.message === 'Exa response failed #503',
  );
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: '{bad' }], []));
  await assert.rejects(
    exaSearchService.search({ query: 'q' }, { resultSize: 8 }, opts('exa')),
    (e: Error): boolean => e.message.startsWith('Failed to decode Exa response (4 chars): '),
  );
  await assert.rejects(
    exaSearchService.scrape({}, { resultSize: 8 }, opts('exa')),
    (e: Error): boolean => e.message === 'Scraping is not supported for Exa',
  );
});
