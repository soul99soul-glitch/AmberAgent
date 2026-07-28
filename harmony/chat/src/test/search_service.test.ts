// search_service.test.ts — D-065 搜索 SDK 核心
// Android 基准: search/.../SearchService.kt(全文 314 行)+ ai/util/KeyRoulette.kt(全文)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonObject } from '../main/ets/chat/json.ts';
import type { SearchServiceOptions } from '../main/ets/search/search_service.ts';
import {
  SEARCH_SERVICE_TYPES, DEFAULT_SEARCH_SERVICE_OPTIONS,
  makeSearchServiceOptions, searchServiceOptionsToJson, searchServiceOptionsFromJson,
  searchResultToJson, scrapedResultToJson,
  createDefaultKeyRoulette, createLruKeyRoulette,
} from '../main/ets/search/search_service.ts';

const UUID_RE: RegExp = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

// ===== SearchServiceOptions 工厂默认值(:161-292) =====

test('工厂默认值:17 类逐字(bing_local 无字段;各类默认)', () => {
  const bing = makeSearchServiceOptions('bing_local');
  assert.equal(bing.type, 'bing_local');
  assert.match(bing.id, UUID_RE);
  const tavily = makeSearchServiceOptions('tavily');
  assert.equal(tavily.type, 'tavily');
  if (tavily.type === 'tavily') {
    assert.equal(tavily.apiKey, '');
    assert.equal(tavily.depth, 'advanced');
  }
  const linkup = makeSearchServiceOptions('linkup');
  if (linkup.type === 'linkup') assert.equal(linkup.depth, 'standard');
  const jina = makeSearchServiceOptions('jina');
  if (jina.type === 'jina') {
    assert.equal(jina.searchUrl, 'https://s.jina.ai/');
    assert.equal(jina.scrapeUrl, 'https://r.jina.ai/');
  }
  const bocha = makeSearchServiceOptions('bocha');
  if (bocha.type === 'bocha') assert.equal(bocha.summary, true);
  const perplexity = makeSearchServiceOptions('perplexity');
  if (perplexity.type === 'perplexity') {
    assert.equal(perplexity.maxTokens, null);
    assert.equal(perplexity.maxTokensPerPage, null);
  }
  const grok = makeSearchServiceOptions('grok');
  if (grok.type === 'grok') {
    assert.equal(grok.model, 'grok-4-1-fast-non-reasoning');
    assert.equal(grok.customUrl, 'https://api.x.ai/v1/responses');
    assert.equal(grok.systemPrompt,
      'You are a helpful search assistant. Search the web to find accurate and up-to-date information for the user\'s query. Provide a comprehensive answer with citations.');
  }
  const searxng = makeSearchServiceOptions('searxng');
  if (searxng.type === 'searxng') {
    assert.equal(searxng.url, '');
    assert.equal(searxng.engines, '');
    assert.equal(searxng.language, '');
    assert.equal(searxng.username, '');
    assert.equal(searxng.password, '');
  }
});

test('TYPES 显示名映射:17 项 + 插入序(:140-158)', () => {
  assert.deepEqual(Object.keys(SEARCH_SERVICE_TYPES), [
    'bing_local', 'amber_agent', 'zhipu', 'tavily', 'exa', 'searxng', 'linkup',
    'brave', 'serper', 'serpapi', 'metaso', 'ollama', 'perplexity', 'firecrawl',
    'jina', 'bocha', 'grok',
  ]);
  assert.equal(SEARCH_SERVICE_TYPES['bing_local'], 'Bing HTML 兜底');
  assert.equal(SEARCH_SERVICE_TYPES['zhipu'], '智谱');
  assert.equal(SEARCH_SERVICE_TYPES['metaso'], '秘塔');
  assert.equal(SEARCH_SERVICE_TYPES['bocha'], '博查');
  assert.equal(SEARCH_SERVICE_TYPES['serpapi'], 'SerpAPI');
  assert.equal(SEARCH_SERVICE_TYPES['amber_agent'], 'AmberAgent');
});

test('DEFAULT = BingLocalOptions(:138)', () => {
  assert.equal(DEFAULT_SEARCH_SERVICE_OPTIONS.type, 'bing_local');
});

// ===== 序列化(kotlinx:classDiscriminator 'type' + explicitNulls=false + ignoreUnknownKeys) =====

test('toJson:type 判别字段居首 + 声明序 + null 省略', () => {
  const o = makeSearchServiceOptions('perplexity');
  const j: JsonObject = searchServiceOptionsToJson(o);
  assert.deepEqual(Object.keys(j), ['type', 'id', 'apiKey']);
  assert.equal(j['type'], 'perplexity');
  const g = makeSearchServiceOptions('grok');
  const gj: JsonObject = searchServiceOptionsToJson(g);
  assert.deepEqual(Object.keys(gj),
    ['type', 'id', 'apiKey', 'model', 'customUrl', 'systemPrompt']);
});

test('fromJson 回读:未知键忽略 + 缺字段回默认 + type 路由', () => {
  const j: JsonObject = {
    type: 'tavily', id: 'fixed-id', apiKey: 'k1', unknownField: 42,
  };
  const o: SearchServiceOptions = searchServiceOptionsFromJson(j);
  assert.equal(o.type, 'tavily');
  assert.equal(o.id, 'fixed-id');
  if (o.type === 'tavily') {
    assert.equal(o.apiKey, 'k1');
    assert.equal(o.depth, 'advanced'); // 缺字段 → 默认
  }
});

test('fromJson:未知 type → 抛错(kotlinx 未知 SerialName 语义)', () => {
  assert.throws(() => searchServiceOptionsFromJson({ type: 'nope' }));
});

test('round-trip:全部 17 类 toJson→fromJson 逐字段相等', () => {
  for (const t of Object.keys(SEARCH_SERVICE_TYPES)) {
    const o = makeSearchServiceOptions(t as SearchServiceOptions['type']);
    const back: SearchServiceOptions =
      searchServiceOptionsFromJson(searchServiceOptionsToJson(o));
    assert.deepEqual(back, o, `round-trip failed for ${t}`);
  }
});

// ===== 结果模型序列化(SearchResult/ScrapedResult,:98-131) =====

test('searchResultToJson:answer null 省略 + item 声明序 + images 缺省空数组', () => {
  const j: JsonObject = searchResultToJson({
    answer: null,
    items: [{ title: 't', url: 'u', text: 'x', publishedAt: null, images: [] }],
  });
  assert.deepEqual(Object.keys(j), ['items']);
  const items = j['items'] as JsonObject[];
  assert.deepEqual(Object.keys(items[0]), ['title', 'url', 'text', 'images']);
});

test('scrapedResultToJson:urls 声明序 + metadata null 省略/非空声明序', () => {
  const j: JsonObject = scrapedResultToJson({
    urls: [
      { url: 'u1', content: 'c1', metadata: null },
      { url: 'u2', content: 'c2', metadata: { title: 'T', description: null, language: 'zh' } },
    ],
  });
  const urls = j['urls'] as JsonObject[];
  assert.deepEqual(Object.keys(urls[0]), ['url', 'content']);
  assert.deepEqual(Object.keys(urls[1]), ['url', 'content', 'metadata']);
  const md = urls[1]['metadata'] as JsonObject;
  assert.deepEqual(Object.keys(md), ['title', 'language']);
});

// ===== KeyRoulette(KeyRoulette.kt 全文) =====

test('default roulette:split [\\s,]+ → trim → 非空白 → distinct;空 → 原串', () => {
  const r = createDefaultKeyRoulette((): number => 0.99);
  // random()=0.99 → 末位;distinct 保首次出现序
  assert.equal(r.next('k1, k2\nk3 k2'), 'k3');
  const r0 = createDefaultKeyRoulette((): number => 0);
  assert.equal(r0.next('k1, k2'), 'k1');
  assert.equal(r0.next('   '), '   '); // 空列表 → 返回原 keys
});

test('lru roulette:未使用 key 优先,否则最久未使用', () => {
  let stored: string | null = null;
  let now = 1000;
  const r = createLruKeyRoulette({
    read: (): string | null => stored,
    write: (c: string): void => { stored = c; },
    nowMs: (): number => now,
  });
  assert.equal(r.next('k1 k2', 'p'), 'k1'); // 首个未使用
  assert.equal(r.next('k1 k2', 'p'), 'k2'); // k1 已用 → k2
  assert.equal(r.next('k1 k2', 'p'), 'k1'); // 均用过 → 最久未用(k1)
});

test('lru roulette:24h 过期条目视为未使用;不在当前列表的键被过滤', () => {
  const DAY: number = 24 * 60 * 60 * 1000;
  const stored: string = JSON.stringify({ p: { k1: 0, stale: 0 } });
  const r = createLruKeyRoulette({
    read: (): string | null => stored,
    write: (): void => {},
    nowMs: (): number => DAY + 1000,
  });
  // k1 已过期 → 视为未使用;stale 不在 keyList 被过滤
  assert.equal(r.next('k1 k2', 'p'), 'k1');
});

test('lru roulette:持久化结构 Map<providerId, Map<key, ts>>;他 provider 全过期清理', () => {
  const DAY: number = 24 * 60 * 60 * 1000;
  let written: string = '';
  const stored: string = JSON.stringify({
    p: { k1: 500 },
    old: { x: 0 }, // 全过期 → 清理
  });
  const r = createLruKeyRoulette({
    read: (): string | null => stored,
    write: (c: string): void => { written = c; },
    nowMs: (): number => DAY + 500,
  });
  r.next('k1', 'p');
  const parsed = JSON.parse(written) as JsonObject;
  assert.deepEqual(Object.keys(parsed), ['p']);
  assert.equal((parsed['p'] as JsonObject)['k1'], DAY + 500);
});

test('lru roulette:load 失败 → 空缓存;空 key 列表 → 原串且不写盘', () => {
  let writes = 0;
  const r = createLruKeyRoulette({
    read: (): string | null => '{bad json',
    write: (): void => { writes++; },
    nowMs: (): number => 0,
  });
  assert.equal(r.next('k1', 'p'), 'k1'); // 坏 JSON → 空缓存 → 首个未使用
  assert.equal(writes, 1);
  assert.equal(r.next('  ', 'p'), '  '); // 空 → 原串,无读写
  assert.equal(writes, 1);
});
