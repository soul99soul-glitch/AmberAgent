// search_orchestrator.test.ts — D-069 SearchOrchestrator 忠实移植
// Android 基准: app/.../core/ai/tools/SearchOrchestrator.kt(全文 848 行)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';
import type { SearchResult, SearchServiceOptions } from '../main/ets/search/search_service.ts';
import { makeSearchServiceOptions } from '../main/ets/search/search_service.ts';
import type { SearchSettings } from '../main/ets/search/search_aggregator.ts';
import type {
  OrchestratorSearchExecutor, SourceSearchRequest,
} from '../main/ets/search/search_orchestrator.ts';
import {
  buildQueryVariants, buildSources, orchestratorCanonicalizeUrl,
  searchOrchestratorExplain, searchOrchestratorSearch, searchOrchestratorStatus,
} from '../main/ets/search/search_orchestrator.ts';

const DEPS = { today: (): string => '2026-07-28' };

const makeSettings = (
  services: SearchServiceOptions[] = [],
  enabledIds: string[] = [],
  overrides: Partial<SearchSettings> = {},
): SearchSettings => ({
  enableWebSearch: true,
  searchCommonOptions: { resultSize: 10 },
  searchServices: services,
  searchEnabledServiceIds: enabledIds,
  searchBuiltinJinaEnabled: true,
  searchBuiltinDuckDuckGoEnabled: true,
  searchBuiltinBingEnabled: true,
  searchBuiltinWikipediaEnabled: true,
  searchBuiltinHackerNewsEnabled: true,
  searchGoogleWebViewFallbackEnabled: false,
  ...overrides,
});

const svc = (type: SearchServiceOptions['type'], id: string): SearchServiceOptions => {
  const o = makeSearchServiceOptions(type);
  o.id = id;
  return o;
};

const item = (title: string, url: string, text: string, publishedAt: string | null = null, images: string[] = []) =>
  ({ title, url, text, publishedAt, images });

const okResult = (items: ReturnType<typeof item>[]): SearchResult => ({ answer: null, items });

// ===== buildQueryVariants(:330-368) =====

test('orchestrator:buildQueryVariants — quick 1/standard 3/deep 4 + news/market/technical/deep site 变体', () => {
  // quick → 仅原 query
  assert.deepEqual(buildQueryVariants('q', 'general', 'any', null, 'quick', DEPS), ['q']);
  // standard + news → 时间变体(today 钉住)
  assert.deepEqual(
    buildQueryVariants('q', 'news', 'week', null, 'standard', DEPS),
    ['q', 'q news this week 2026-07-28'],
  );
  // market topic → 市场变体;standard limit 3
  assert.deepEqual(
    buildQueryVariants('q', 'market', 'any', null, 'standard', DEPS),
    ['q', 'q market share shipment sales Counterpoint Canalys IDC'],
  );
  // technical
  assert.deepEqual(
    buildQueryVariants('q', 'technical', 'any', null, 'standard', DEPS),
    ['q', 'q documentation GitHub issue release'],
  );
  // deep → 4:原 query + news/time + site 变体
  const deep = buildQueryVariants('q', 'news', 'day', null, 'deep', DEPS);
  assert.deepEqual(deep, [
    'q', 'q news today 2026-07-28',
    'q site:reuters.com OR site:apnews.com OR site:bloomberg.com OR site:canalys.com OR site:idc.com',
  ]);
  // looksLikeMarketQuery:中文'销量'触发市场变体(topic general 也算)(:350-352,370-375)
  const market = buildQueryVariants('手机销量', 'general', 'any', null, 'standard', DEPS);
  assert.deepEqual(market, ['手机销量', '手机销量 market share shipment sales Counterpoint Canalys IDC']);
  // recency_days 时间文本仅在 topic==news || timeRange!=any 时落出(:347)
  assert.deepEqual(
    buildQueryVariants('q', 'general', 'any', 7, 'standard', DEPS),
    ['q'],
  );
  assert.deepEqual(
    buildQueryVariants('q', 'news', 'any', 7, 'standard', DEPS),
    ['q', 'q news last 7 days'],
  );
});

// ===== buildSources(:238-283) =====

test('orchestrator:buildSources — 内置五件优先级序/标志门/applicableFor/选择器/configured Bing 抑制', () => {
  const all = makeSettings();
  // 无关 query → wikipedia/hackernews 不适用;余三件按 priority 90/200/210
  assert.deepEqual(
    buildSources(all, [], 'random query', 'general').map((s) => s.id),
    ['jina_builtin', 'duckduckgo_builtin', 'bing_builtin'],
  );
  // 百科 query → wikipedia 加入(230 垫底)
  assert.deepEqual(
    buildSources(all, [], 'what is rust', 'general').map((s) => s.id),
    ['jina_builtin', 'duckduckgo_builtin', 'bing_builtin', 'wikipedia_builtin'],
  );
  // 技术 query → hackernews 加入
  assert.deepEqual(
    buildSources(all, [], 'github mcp', 'general').map((s) => s.id),
    ['jina_builtin', 'duckduckgo_builtin', 'bing_builtin', 'hackernews_builtin'],
  );
  // technical topic → hackernews 恒适用
  assert.ok(buildSources(all, [], 'whatever', 'technical').some((s) => s.id === 'hackernews_builtin'));
  // 标志关闭
  const none = makeSettings([], [], {
    searchBuiltinJinaEnabled: false,
    searchBuiltinDuckDuckGoEnabled: false,
    searchBuiltinBingEnabled: false,
    searchBuiltinWikipediaEnabled: false,
    searchBuiltinHackerNewsEnabled: false,
  });
  assert.deepEqual(buildSources(none, [], 'what is x', 'general'), []);
  // 选择器:name contains('jina' 命中 'Jina Search'?allowed 用 name='Jina' 判等/包含)
  assert.deepEqual(
    buildSources(all, ['jina'], 'random', 'general').map((s) => s.id),
    ['jina_builtin'],
  );
  // configured bing_local 抑制内置 bing(:262,270)
  const bing = svc('bing_local', 'cfg-bing');
  const withBing = makeSettings([bing], ['cfg-bing']);
  const ids = buildSources(withBing, [], 'random', 'general').map((s) => s.id);
  assert.ok(ids.includes('cfg-bing'));
  assert.ok(!ids.includes('bing_builtin'));
  // configured priority = index(0)→ 排最前
  assert.equal(ids[0], 'cfg-bing');
});

// ===== canonicalizeUrl(:519-542) =====

test('orchestrator:canonicalizeUrl — yclid 额外过滤', () => {
  assert.equal(
    orchestratorCanonicalizeUrl('https://example.com/p?yclid=1&utm_a=2&b=3'),
    'https://example.com/p?b=3',
  );
});

// ===== 端到端 search(:43-171) =====

test('orchestrator:search — 无源错误对象逐字', async () => {
  const none = makeSettings([], [], {
    searchBuiltinJinaEnabled: false,
    searchBuiltinDuckDuckGoEnabled: false,
    searchBuiltinBingEnabled: false,
    searchBuiltinWikipediaEnabled: false,
    searchBuiltinHackerNewsEnabled: false,
  });
  const r = await searchOrchestratorSearch(none, { query: 'q' }, undefined, DEPS);
  assert.deepEqual(r, {
    status: 'error',
    query: 'q',
    error: 'No search sources are enabled. Enable built-in free sources or at least one configured search service.',
    items: [],
    sources: [],
  });
});

test('orchestrator:search — ok 形状/perCallSize coerce/variant cap10/sources 状态', async () => {
  const settings = makeSettings(); // 五内置;query 无关 → 3 源
  const captured: SourceSearchRequest[] = [];
  const sizes: number[] = [];
  const exec: OrchestratorSearchExecutor = (req, common) => {
    captured.push(req);
    sizes.push(common.resultSize);
    return Promise.resolve(okResult([
      item(`${req.source.name} t`, `https://a.com/${req.sourceIndex}`, 'text'),
    ]));
  };
  const r = await searchOrchestratorSearch(settings, { query: 'q', max_results: 40 }, exec, DEPS);
  assert.equal(r['status'], 'ok');
  // perCallSize = max(4, floor(42/3)+3=17) = 17
  assert.ok(sizes.every((s) => s === 17));
  // variants(standard, any, general) = ['q'] → 每源 1 调用
  assert.equal(captured.length, 3);
  const sources = r['sources'] as JsonObject[];
  assert.deepEqual(sources.map((s) => s['status']), ['ok', 'ok', 'ok']);
  assert.deepEqual(sources.map((s) => s['variant_count']), [1, 1, 1]);
  assert.deepEqual(sources.map((s) => s['builtin']), [true, true, true]);
  // query_variants 落出
  assert.deepEqual(r['query_variants'], ['q']);
  // cap10:deep + news → variants 3;5 源全启用(技术 query 使 HN 适用,百科 query 使 wiki 适用)
  const captured2: SourceSearchRequest[] = [];
  const exec2: OrchestratorSearchExecutor = (req) => {
    captured2.push(req);
    return Promise.resolve(okResult([item('t', `https://b.com/${captured2.length}`, 'x')]));
  };
  const r2 = await searchOrchestratorSearch(
    settings,
    { query: 'what is github mcp', topic: 'news', depth: 'deep', max_results: 40 },
    exec2, DEPS,
  );
  // cap10:deep + news → variants 3;topic news 排除 wikipedia → 4 源
  // 4(v0) + 4(v1) = 8 < 10 → v2 再发 2 件截断(MAX_SOURCE_VARIANT_CALLS)
  assert.equal(captured2.length, 10);
  const sources2 = r2['sources'] as JsonObject[];
  assert.deepEqual(sources2.map((s) => s['variant_count']), [3, 3, 2, 2]);
});

test('orchestrator:search — partial/empty 状态机 + sanitizeError + message', async () => {
  const settings = makeSettings([], [], {
    searchBuiltinJinaEnabled: false,
    searchBuiltinWikipediaEnabled: false,
    searchBuiltinHackerNewsEnabled: false,
  }); // ddg + bing
  // ddg 失败,bing 成功 → partial
  const exec: OrchestratorSearchExecutor = (req) => {
    if (req.source.id === 'duckduckgo_builtin') {
      return Promise.reject(new Error('java.io.IOException: boom'));
    }
    return Promise.resolve(okResult([item('t', 'https://a.com/x', 'x')]));
  };
  const r = await searchOrchestratorSearch(settings, { query: 'q' }, exec, DEPS);
  assert.equal(r['status'], 'partial');
  const sources = r['sources'] as JsonObject[];
  const ddg = sources.find((s) => s['service_id'] === 'duckduckgo_builtin') as JsonObject;
  assert.equal(ddg['status'], 'error');
  // sanitizeError:java.io.IOException → error(:587-593)
  assert.equal(ddg['error'], 'error: boom');
  assert.ok(!('message' in r)); // merged 非空无 message
  // 全失败 → empty + message(无 webview)
  const execFail: OrchestratorSearchExecutor = () => Promise.reject(new Error('x'));
  const r2 = await searchOrchestratorSearch(settings, { query: 'q' }, execFail, DEPS);
  assert.equal(r2['status'], 'empty');
  assert.equal(
    r2['message'],
    'No source produced parseable results. Enable Google WebView fallback or another search service.',
  );
});

test('orchestrator:search — merge 评分公式/freshness/dup+12/publishedAt 合并/排序', async () => {
  const settings = makeSettings([], [], {
    searchBuiltinJinaEnabled: false,
    searchBuiltinWikipediaEnabled: false,
    searchBuiltinHackerNewsEnabled: false,
  }); // ddg(200) + bing(210) → sourceIndex 0/1
  const exec: OrchestratorSearchExecutor = (req) => {
    if (req.sourceIndex === 0) {
      return Promise.resolve(okResult([
        // 命中 query token 'rust'(len>=2)→ +3
        item('Rust guide', 'https://a.com/p?utm_x=1', 'rust text', '2026-07-01'),
        item('Other', 'https://c.com/z', 'plain'),
      ]));
    }
    return Promise.resolve(okResult([
      // canonical 同(utm 剥离,www)→ dup;publishedAt 已有 → 不覆盖
      item('Rust guide', 'https://www.a.com/p', 'longer rust text here', '2026-07-02'),
    ]));
  };
  // depth quick → 单变体(否则多变体会产生额外 dup 计数)
  const r = await searchOrchestratorSearch(
    settings, { query: 'rust', topic: 'news', time_range: 'week', depth: 'quick' }, exec, DEPS,
  );
  const items = r['items'] as JsonObject[];
  assert.equal(items.length, 2);
  const dup = items.find((i) => i['duplicate_count'] === 2) as JsonObject;
  // score = (60-0-0-0) + hit3 + fresh12 = 75;dup +12 → 87
  assert.equal(dup['rank_score'], 87);
  assert.equal(dup['freshness_score'], 12);
  assert.equal(dup['published_at'], '2026-07-01'); // 已有不覆盖
  assert.equal(dup['published_at_unknown'], false);
  assert.equal(dup['text'], 'longer rust text here'); // 长文本胜
  assert.equal(dup['verified_by_scrape'], false);
  // other:60 - rank1 - 1 = 59;无 fresh(topic news 但 publishedAt null → 0)
  const other = items.find((i) => i['duplicate_count'] === 1) as JsonObject;
  assert.equal(other['rank_score'], 59);
  assert.equal(other['published_at_unknown'], true);
  // 排序:87 > 59
  assert.equal(items[0]['duplicate_count'], 2);
});

test('orchestrator:search — webview 兜底条件 + 建议 URL 形式编码 + empty message 变体', async () => {
  const settings = makeSettings([], [], {
    searchBuiltinJinaEnabled: false,
    searchBuiltinWikipediaEnabled: false,
    searchBuiltinHackerNewsEnabled: false,
    searchGoogleWebViewFallbackEnabled: true,
  });
  const execFail: OrchestratorSearchExecutor = () => Promise.reject(new Error('x'));
  // allow_webview true → 兜底 + empty message 变体
  const r = await searchOrchestratorSearch(
    settings, { query: 'a b', allow_webview: true }, execFail, DEPS,
  );
  const fb = r['webview_fallback'] as JsonObject;
  assert.equal(fb['status'], 'available');
  const sug = fb['suggestions'] as JsonObject[];
  assert.equal(sug[0]['url'], 'https://www.google.com/search?q=a+b');
  assert.equal(sug[1]['url'], 'https://duckduckgo.com/?q=a+b');
  assert.equal(sug[2]['url'], 'https://www.bing.com/search?q=a+b');
  assert.equal(
    r['message'],
    'No ordinary source produced results. Use webview_search_open with the suggested fallback URL, or enable more search services.',
  );
  // 'TRUE' 严格解析失败 → false;但 depth deep → 仍兜底
  const r2 = await searchOrchestratorSearch(
    settings, { query: 'a b', allow_webview: 'TRUE', depth: 'deep' }, execFail, DEPS,
  );
  assert.ok('webview_fallback' in r2);
  // 全不触发 → 无兜底
  const settingsOff = makeSettings([], [], {
    searchBuiltinJinaEnabled: false,
    searchBuiltinWikipediaEnabled: false,
    searchBuiltinHackerNewsEnabled: false,
    searchGoogleWebViewFallbackEnabled: false,
  });
  const r3 = await searchOrchestratorSearch(settingsOff, { query: 'q' }, execFail, DEPS);
  assert.ok(!('webview_fallback' in r3));
});

test('orchestrator:search — merged < min(maxResults,3) 触发兜底;图片 toEmit 预算修正', async () => {
  const settings = makeSettings([], [], {
    searchBuiltinJinaEnabled: false,
    searchBuiltinBingEnabled: false,
    searchBuiltinWikipediaEnabled: false,
    searchBuiltinHackerNewsEnabled: false,
    searchGoogleWebViewFallbackEnabled: true,
  }); // 仅 ddg
  const exec: OrchestratorSearchExecutor = () => Promise.resolve(okResult([
    item('t1', 'https://a.com/1', 'x', null, ['i1', 'i2', 'i3', 'i4']),
    item('t2', 'https://a.com/2', 'y', null, ['i5', 'i6']),
  ]));
  const r = await searchOrchestratorSearch(settings, { query: 'q', max_results: 10 }, exec, DEPS);
  // merged 2 < min(10,3)=3 → 兜底
  assert.ok('webview_fallback' in r);
  const items = r['items'] as JsonObject[];
  // toEmit 修正:首条 min(4,5)=4 → 预算 1;次条 min(2,1)=1
  assert.deepEqual(items[0]['images'], ['i1', 'i2', 'i3', 'i4']);
  assert.deepEqual(items[1]['images'], ['i5']);
  assert.equal(r['total_images'], 5);
});

// ===== status(:173-199)/ explain(:201-236) =====

test('orchestrator:status — 计数 + api_key_configured 分类型(brave bool/bing+searxng null)', () => {
  const brave = svc('brave', 'id-brave');
  if (brave.type === 'brave') brave.apiKey = 'k';
  const bing = svc('bing_local', 'id-bing');
  const searx = svc('searxng', 'id-searx');
  const settings = makeSettings([brave, bing, searx], ['id-brave', 'id-bing']); // searx 未启用
  const r = searchOrchestratorStatus(settings);
  assert.equal(r['enabled'], true);
  assert.equal(r['configured_service_count'], 3);
  assert.equal(r['enabled_configured_service_count'], 2);
  assert.equal(r['builtin_duckduckgo_enabled'], true);
  assert.equal(r['google_webview_fallback_enabled'], false);
  const sources = r['sources'] as JsonObject[];
  // buildSources:configured(bing 抑制内置 bing) + 内置四件
  const braveSrc = sources.find((s) => s['id'] === 'id-brave') as JsonObject;
  assert.equal(braveSrc['api_key_configured'], true);
  assert.equal(braveSrc['builtin'], false);
  const bingSrc = sources.find((s) => s['id'] === 'id-bing') as JsonObject;
  assert.equal(bingSrc['api_key_configured'], null);
  assert.ok(sources.some((s) => s['id'] === 'jina_builtin' && s['builtin'] === true));
  assert.ok(!sources.some((s) => s['id'] === 'bing_builtin'));
  assert.ok(!sources.some((s) => s['id'] === 'id-searx')); // 未启用
});

test('orchestrator:explain — 形状/空 query 无变体/webview 可用性判定', () => {
  const settings = makeSettings([], [], { searchGoogleWebViewFallbackEnabled: true });
  const r = searchOrchestratorExplain(
    settings, { q: 'what is rust', topic: 'NEWS', depth: 'deep' }, DEPS,
  );
  assert.equal(r['query'], 'what is rust');
  assert.equal(r['topic'], 'news'); // lowercase 后校验
  assert.equal(r['time_range'], 'week');
  assert.equal(r['depth'], 'deep');
  assert.ok((r['source_count'] as number) === 3); // topic news:wikipedia/HN 均不适用
  const sources = r['sources'] as JsonObject[];
  assert.deepEqual(
    Object.keys(sources[0]).sort(),
    ['builtin', 'id', 'kind', 'name', 'reason'],
  );
  // deep + fallback enabled → true
  assert.equal(r['webview_fallback_would_be_available'], true);
  // 空 query → 空变体
  const r2 = searchOrchestratorExplain(settings, {}, DEPS);
  assert.deepEqual(r2['query_variants'], []);
  assert.equal(r2['webview_fallback_would_be_available'], false);
  // 变体落出(standard)
  const r3 = searchOrchestratorExplain(settings, { query: 'q' }, DEPS);
  assert.deepEqual(r3['query_variants'], ['q']);
});
