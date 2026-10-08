// search_tools.test.ts — D-070 SearchTools 工具组
// Android 基准: app/.../core/ai/tools/SearchTools.kt(全文 256 行)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { HttpClient, HttpRequest, HttpResponse } from '@amber/deepread-domain';
import type { JsonObject } from '../main/ets/chat/json.ts';
import type { SearchServiceOptions } from '../main/ets/search/search_service.ts';
import { initSearchSdk, makeSearchServiceOptions } from '../main/ets/search/search_service.ts';
import type { SearchSettings } from '../main/ets/search/search_aggregator.ts';
import { createSearchTools } from '../main/ets/search/search_tools.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';

const DEPS = { today: (): string => '2026-07-28' };

const mockHttpSeq = (responses: HttpResponse[], captured: HttpRequest[]): HttpClient => ({
  fetch: (req: HttpRequest): Promise<HttpResponse> => {
    captured.push(req);
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

const makeSettings = (
  services: SearchServiceOptions[] = [],
  enabledIds: string[] = [],
  overrides: Partial<SearchSettings> = {},
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
  searchGoogleWebViewFallbackEnabled: true,
  ...overrides,
});

const svc = (type: SearchServiceOptions['type'], id: string): SearchServiceOptions => {
  const o = makeSearchServiceOptions(type);
  o.id = id;
  return o;
};

const toolByName = (tools: AgentTool[], name: string): AgentTool => {
  const t = tools.find((x) => x.name === name);
  assert.ok(t !== undefined, `tool ${name} missing`);
  return t as AgentTool;
};

test('tools:四件注册序(jina 内置 → scrape 存在);scrape 门(无 scrape 源且 jina 关 → 三件)', () => {
  const tools = createSearchTools(makeSettings(), DEPS);
  assert.deepEqual(
    tools.map((t) => t.name),
    ['search_web', 'search_sources_status', 'search_strategy_explain', 'scrape_web'],
  );
  // jina 关 + 无 enabled scrape 服务 → 无 scrape_web(:108-110)
  const noScrape = createSearchTools(makeSettings([], [], {
    searchBuiltinJinaEnabled: false,
  }), DEPS);
  assert.deepEqual(
    noScrape.map((t) => t.name),
    ['search_web', 'search_sources_status', 'search_strategy_explain'],
  );
  // 有 scrape 能力 enabled 服务(tavily)→ 恢复四件
  const tavily = svc('tavily', 'id-t');
  const withScrape = createSearchTools(makeSettings([tavily], ['id-t'], {
    searchBuiltinJinaEnabled: false,
  }), DEPS);
  assert.equal(withScrape.length, 4);
});

test('tools:scrape_web — 无候选 + jina 关 → 逐字 error;默认解析 jina 内置并抓取', async () => {
  const noCandidate = createSearchTools(makeSettings([], [], {
    searchBuiltinJinaEnabled: false,
    searchBuiltinDuckDuckGoEnabled: false,
    searchBuiltinBingEnabled: false,
    searchBuiltinWikipediaEnabled: false,
    searchBuiltinHackerNewsEnabled: false,
  }), DEPS);
  assert.equal(noCandidate.some((t) => t.name === 'scrape_web'), false); // 门未开
  // 有 scrape 服务但全部不匹配 → error 逐字:用 enabled bing(无 scrape)+ jina 开但请求 bing
  const bing = svc('bing_local', 'id-b');
  const withBing = createSearchTools(makeSettings([bing], ['id-b']), DEPS);
  await assert.rejects(
    toolByName(withBing, 'scrape_web').execute({ url: 'https://a.com', service: 'bing' }),
    (e: Error): boolean =>
      e.message === 'No enabled search service supports scraping. Enable Jina Reader or another scraping-capable search service in settings.',
  );
  // 默认解析:jina 内置 → r.jina.ai 抓取(:124-129 特判 JinaOptions 路径)
  const calls: HttpRequest[] = [];
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: JSON.stringify({ data: { url: 'https://a.com/x', content: '# page', title: 'T' } }) }], calls));
  const jinaDefault = createSearchTools(makeSettings(), DEPS);
  const parts = await toolByName(jinaDefault, 'scrape_web').execute({ url: 'https://a.com/x' });
  assert.ok(calls[0].url.startsWith('https://r.jina.ai/'));
  const payload = JSON.parse((parts[0] as { text: string }).text) as JsonObject;
  assert.ok(Array.isArray(payload['urls']));
});

test('tools:scrape_web — searchServiceSelected 索引命中 enabled scrape 服务', async () => {
  const tavily = svc('tavily', 'id-t');
  const calls: HttpRequest[] = [];
  initSearchSdk(mockHttpSeq([{
    status: 200, headers: {},
    body: JSON.stringify({ results: [{ url: 'https://a.com', raw_content: 'raw' }] }),
  }], calls));
  const tools = createSearchTools(makeSettings([tavily], ['id-t'], {
    searchServiceSelected: 0,
    searchBuiltinJinaEnabled: false,
  }), DEPS);
  const parts = await toolByName(tools, 'scrape_web').execute({ url: 'https://a.com' });
  assert.equal(calls[0].url, 'https://api.tavily.com/extract');
  const payload = JSON.parse((parts[0] as { text: string }).text) as JsonObject;
  const urls = payload['urls'] as JsonObject[];
  assert.equal(urls[0]['content'], 'raw');
  // service 选择器 'jina' 命中内置回退(:250-253)
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: JSON.stringify({ data: { url: 'https://a.com', content: 'md' } }) }], calls));
  const tools2 = createSearchTools(makeSettings([tavily], ['id-t']), DEPS);
  await toolByName(tools2, 'scrape_web').execute({ url: 'https://a.com', service: 'jina reader' });
  assert.ok(calls[1].url.startsWith('https://r.jina.ai/'));
});
