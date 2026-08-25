// search_aggregator.test.ts — D-069 SearchAggregator 忠实移植
// Android 基准: app/.../core/ai/tools/SearchAggregator.kt(全文 384 行)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonObject } from '../main/ets/chat/json.ts';
import type {
  SearchResult, SearchServiceOptions,
} from '../main/ets/search/search_service.ts';
import { makeSearchServiceOptions } from '../main/ets/search/search_service.ts';
import type { SearchExecutor, SearchSettings } from '../main/ets/search/search_aggregator.ts';
import {
  buildServiceParams, canonicalizeUrl, enabledServices, normalizedTitle,
  searchAggregatorSearch,
} from '../main/ets/search/search_aggregator.ts';

const DEPS = { today: (): string => '2026-07-28' };

const makeSettings = (
  services: SearchServiceOptions[] = [],
  enabledIds: string[] = [],
): SearchSettings => ({
  enableWebSearch: true,
  searchCommonOptions: { resultSize: 10 },
  searchServices: services,
  searchServiceSelected: 0,
  searchEnabledServiceIds: enabledIds,
  searchBuiltinJinaEnabled: true,
  searchBuiltinDuckDuckGoEnabled: true,
  searchBuiltinBingEnabled: true,
  searchBuiltinWikipediaEnabled: true,
  searchBuiltinHackerNewsEnabled: true,
  searchGoogleWebViewFallbackEnabled: false,
});

const svc = (type: SearchServiceOptions['type'], id: string): SearchServiceOptions => {
  const o = makeSearchServiceOptions(type);
  o.id = id;
  return o;
};

const item = (title: string, url: string, text: string, images: string[] = []) =>
  ({ title, url, text, publishedAt: null, images });

const okResult = (items: ReturnType<typeof item>[]): SearchResult => ({ answer: null, items });

test('aggregator:query 必填 / q 兜底 / 无启用源错误对象逐字', async () => {
  await assert.rejects(
    searchAggregatorSearch(makeSettings(), {}, async () => okResult([])),
    (e: Error): boolean => e.message === 'query is required',
  );
  // 无启用服务(:49-56)
  const r = await searchAggregatorSearch(makeSettings(), { q: 'x' }, async () => okResult([]));
  assert.deepEqual(r, {
    status: 'error',
    error: 'No enabled search services are available. Enable at least one search service in Search Service settings.',
    items: [],
    sources: [],
  });
});

test('aggregator:topic/time_range 校验与默认;max_results coerce;perServiceSize 公式', async () => {
  const s1 = svc('bing_local', 'id-bing');
  const s2 = svc('tavily', 'id-tavily');
  const settings = makeSettings([s1, s2], ['id-bing', 'id-tavily']);
  const captured: { size: number; params: JsonObject }[] = [];
  const exec: SearchExecutor = (o, p, c) => {
    captured.push({ size: c.resultSize, params: p });
    return Promise.resolve(okResult([item('t', 'https://a.com/x', 'x')]));
  };
  // topic bogus → general;time_range 无效 → any;max_results 99 → coerce 30
  const r = await searchAggregatorSearch(
    settings,
    { query: 'q', topic: 'bogus', time_range: 'bogus', max_results: 99 },
    exec, DEPS,
  );
  assert.equal(r['topic'], 'general');
  assert.equal(r['time_range'], 'any');
  // perServiceSize = floor((30+1)/2)+3 = 18(:58)
  assert.deepEqual(captured.map((c) => c.size), [18, 18]);
  // max_results 缺省走 settings.resultSize;news → week;recency_days coerce
  captured.length = 0;
  const r2 = await searchAggregatorSearch(
    settings, { query: 'q', topic: 'news', recency_days: 999 }, exec, DEPS,
  );
  assert.equal(r2['time_range'], 'week');
  assert.equal(r2['recency_days'], 366);
  // floor((10+1)/2)+3 = 8
  assert.deepEqual(captured.map((c) => c.size), [8, 8]);
});

test('aggregator:buildServiceParams — tavily 原生 topic;非原生 enhance;firecrawl news sources', () => {
  // tavily 原生(:155-165):query 不增强,落 topic 键
  assert.deepEqual(
    buildServiceParams('q', 'news', 'week', null, svc('tavily', 'x'), DEPS),
    { query: 'q', topic: 'news' },
  );
  // 非原生:enhanceQuery 拼 news + 时间文本(:262-282)
  assert.deepEqual(
    buildServiceParams('q', 'news', 'week', null, svc('bing_local', 'x'), DEPS),
    { query: 'q news this week 2026-07-28' },
  );
  // recency_days 优先于 timeRange
  assert.deepEqual(
    buildServiceParams('q', 'general', 'any', 7, svc('bing_local', 'x'), DEPS),
    { query: 'q last 7 days' },
  );
  // firecrawl + news → sources 键(:166-168)
  assert.deepEqual(
    buildServiceParams('q', 'news', 'any', null, svc('firecrawl', 'x'), DEPS),
    { query: 'q news', sources: ['news', 'web'] },
  );
});

test('aggregator:enabledServices — id 过滤 + 选择器(id ==/前缀/name ==)', () => {
  const a = svc('tavily', 'aaa-111');
  const b = svc('exa', 'bbb-222');
  const c = svc('grok', 'ccc-333');
  const settings = makeSettings([a, b, c], ['aaa-111', 'bbb-222']); // grok 未启用
  assert.deepEqual(enabledServices(settings), [a, b]);
  // name ==
  assert.deepEqual(enabledServices(settings, ['tavily']), [a]);
  // id 前缀
  assert.deepEqual(enabledServices(settings, ['bbb']), [b]);
  // id 全等
  assert.deepEqual(enabledServices(settings, ['aaa-111']), [a]);
  // 未启用不可选
  assert.deepEqual(enabledServices(settings, ['grok']), []);
  // 全 blank 选择器 → 全部启用(:137-138)
  assert.deepEqual(enabledServices(settings, [' ', '']), [a, b]);
});

test('aggregator:canonicalizeUrl — www/utm/fbclid 剥离,query 排序,尾斜杠,失败回退', () => {
  assert.equal(
    canonicalizeUrl('HTTPS://WWW.Example.COM/path/?utm_source=x&b=2&a=1'),
    'https://example.com/path?a=1&b=2',
  );
  assert.equal(canonicalizeUrl('https://example.com/'), 'https://example.com/');
  assert.equal(canonicalizeUrl('https://example.com/p/?fbclid=z'), 'https://example.com/p');
  // 含空格 → Java URI 抛异常 → trim+lowercase 回退
  assert.equal(canonicalizeUrl('  Not A URL  '), 'not a url');
  // normalizedTitle:标点+空白移除,take(80)
  assert.equal(normalizedTitle('Hello, World! 这是一个标题'), 'helloworld这是一个标题');
});

test('aggregator:merge — canonical 去重/dup+8/长文本胜/图片合并 cap5;排序 score 降序', async () => {
  const s1 = svc('bing_local', 'id-1');
  const s2 = svc('exa', 'id-2');
  const settings = makeSettings([s1, s2], ['id-1', 'id-2']);
  const exec: SearchExecutor = (o) => {
    if (o.id === 'id-1') {
      return Promise.resolve(okResult([
        item('Same Title', 'https://a.com/p?utm_source=x', 'short', ['i1']),
        item('Other', 'https://b.com/q', 'b text'),
      ]));
    }
    return Promise.resolve(okResult([
      // 同 canonical(utm 剥离)→ 去重
      item('Same Title', 'https://www.a.com/p?fbclid=y', 'a much longer text wins', ['i1', 'i2']),
    ]));
  };
  const r = await searchAggregatorSearch(settings, { query: 'q' }, exec, DEPS);
  const items = r['items'] as JsonObject[];
  assert.equal(items.length, 2);
  // 去重项:duplicate_count 2,长文本胜,source_services 双件
  const dup = items.find((i) => i['duplicate_count'] === 2) as JsonObject;
  assert.equal(dup['text'], 'a much longer text wins');
  assert.deepEqual(dup['source_services'], ['Bing HTML 兜底', 'Exa']);
  assert.deepEqual(dup['images'], ['i1', 'i2']);
  // score:s1 首条 = (2-0)*10 - 0 = 20;去重 +8 → 28 > other((2-0)*10-1=19)→ dup 第一
  assert.equal(items[0]['duplicate_count'], 2);
  assert.equal(dup['rank_score'], 28);
});

test('aggregator:输出形状 — status/图片全局预算/total_images/image_instruction/sources', async () => {
  const s1 = svc('bing_local', 'id-1');
  const s2 = svc('exa', 'id-2');
  const settings = makeSettings([s1, s2], ['id-1', 'id-2']);
  const exec: SearchExecutor = (o) => {
    if (o.id === 'id-1') {
      return Promise.resolve(okResult([
        item('t1', 'https://a.com/1', 'x', ['i1', 'i2', 'i3']),
        item('t2', 'https://a.com/2', 'y', ['i4', 'i5', 'i6']),
      ]));
    }
    return Promise.reject(new Error('boom '.repeat(200))); // error take(500)
  };
  const r = await searchAggregatorSearch(settings, { query: 'q' }, exec, DEPS);
  assert.equal(r['status'], 'ok');
  assert.equal(r['service_count'], 2);
  const items = r['items'] as JsonObject[];
  // 全局预算 5:首条 3 张,次条 emitted=distinct.take(2)=2 → 预算耗尽
  assert.deepEqual(items[0]['images'], ['i1', 'i2', 'i3']);
  assert.deepEqual(items[1]['images'], ['i4', 'i5']);
  assert.equal(r['total_images'], 5);
  assert.ok(typeof r['image_instruction'] === 'string' &&
    (r['image_instruction'] as string).startsWith('搜索结果包含 5 张相关图片。'));
  // sources:exa 失败,error take(500)
  const sources = r['sources'] as JsonObject[];
  assert.equal(sources[0]['status'], 'ok');
  assert.equal(sources[0]['service'], 'Bing HTML 兜底');
  assert.equal(sources[1]['status'], 'error');
  assert.equal(sources[1]['result_count'], 0);
  assert.equal((sources[1]['error'] as string).length, 500);
  assert.equal(sources[1]['query'], 'q');
  // text take(2000)/published_at null
  assert.equal(items[0]['published_at'], null);
});

test('aggregator:services 选择器 — 字符串逗号切分(保留 blank)/数组取串', async () => {
  const a = svc('tavily', 'aaa-111');
  const settings = makeSettings([a], ['aaa-111']);
  const exec: SearchExecutor = () => Promise.resolve(okResult([item('t', 'https://a.com/x', 'x')]));
  const r = await searchAggregatorSearch(settings, { query: 'q', services: 'tavily, ' }, exec, DEPS);
  // 'tavily, ' → ['tavily',''] → 非全 blank → 过滤命中 tavily
  assert.equal(r['status'], 'ok');
  assert.equal(r['service_count'], 1);
  const r2 = await searchAggregatorSearch(settings, { query: 'q', services: ['nope'] }, exec, DEPS);
  // 选择器无命中 → candidates 空 → error 对象
  assert.equal(r2['status'], 'error');
});

test('aggregator:全失败 → status empty;max_results 串数字 intOrNull', async () => {
  const s1 = svc('bing_local', 'id-1');
  const settings = makeSettings([s1], ['id-1']);
  const exec: SearchExecutor = () => Promise.reject(new Error('x'));
  const r = await searchAggregatorSearch(settings, { query: 'q', max_results: '5' }, exec, DEPS);
  assert.equal(r['status'], 'empty');
  // 5.5 非整数 → intOrNull null → 走 settings 默认(不抛错)
  const r2 = await searchAggregatorSearch(settings, { query: 'q', max_results: 5.5 }, exec, DEPS);
  assert.equal(r2['status'], 'empty');
});

test('aggregator:merge — 同域同题不同 URL 经标题别名合并;空 URL/空标题不入聚合', async () => {
  const s1 = svc('bing_local', 'id-1');
  const s2 = svc('exa', 'id-2');
  const settings = makeSettings([s1, s2], ['id-1', 'id-2']);
  const exec: SearchExecutor = (o) => {
    if (o.id === 'id-1') {
      return Promise.resolve(okResult([
        item('Same Long Enough Title', 'https://x.test/one', 'first'),
        item('', 'https://x.test/no-title', 'dropped'),
      ]));
    }
    return Promise.resolve(okResult([
      item('Same Long Enough Title', 'https://x.test/two', 'second'),
      item('Has Title', '', 'dropped'),
    ]));
  };
  const r = await searchAggregatorSearch(settings, { query: 'q' }, exec, DEPS);
  const items = r['items'] as JsonObject[];
  assert.equal(items.length, 1, '同域同题合并 + 空 URL/空标题被过滤');
  assert.equal(items[0]['duplicate_count'], 2);
});
