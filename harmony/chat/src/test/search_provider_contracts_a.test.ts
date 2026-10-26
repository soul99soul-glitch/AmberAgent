import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { HttpClient, HttpRequest } from '@amber/deepread-domain';
import type { JsonObject } from '../main/ets/chat/json.ts';
import type { SearchService, SearchServiceOptions } from '../main/ets/search/search_service.ts';
import { initSearchSdk, makeSearchServiceOptions } from '../main/ets/search/search_service.ts';
import { defaultSearchPrefs } from '../main/ets/search/search_prefs.ts';
import { createDeepReadSearchProviders } from '../main/ets/search/deepread_search_registry.ts';
import { searchOrchestratorSearch } from '../main/ets/search/search_orchestrator.ts';
import { tavilySearchService } from '../main/ets/search/tavily_service.ts';
import { exaSearchService } from '../main/ets/search/exa_service.ts';
import { zhipuSearchService } from '../main/ets/search/zhipu_service.ts';

const captureHttp = (payload: JsonObject, requests: HttpRequest[]): HttpClient => ({
  fetch: async (request) => {
    requests.push(request);
    return { status: 200, headers: {}, body: JSON.stringify(payload) };
  },
  fetchStream: async () => { throw new Error('not used'); },
});

const keyedOptions = (type: SearchServiceOptions['type']): SearchServiceOptions => {
  const options = makeSearchServiceOptions(type);
  if ('apiKey' in options) options.apiKey = 'contract-test-key';
  return options;
};

test('DeepRead direct Tavily and Brave requests respect web result limits and retain smaller counts', async () => {
  for (const type of ['tavily', 'brave'] as const) {
    for (const [configuredSize, expectedSize] of [[50, 20], [7, 7]]) {
      const requests: HttpRequest[] = [];
      initSearchSdk(captureHttp({ results: [], web: { results: [] } }, requests));
      const options = keyedOptions(type);
      const settings = defaultSearchPrefs();
      settings.searchServices = [options];
      settings.searchEnabledServiceIds = [options.id];
      settings.searchCommonOptions.resultSize = configuredSize;
      const provider = createDeepReadSearchProviders(settings, { freeProviders: [], ensureSdk: () => {} })[0];
      await provider.search(['current news']);
      assert.equal(requests.length, 1);
      const request = requests[0];
      const sentCount = type === 'tavily'
        ? (JSON.parse(request.body as string) as JsonObject)['max_results']
        : Number(new URL(request.url).searchParams.get('count'));
      assert.equal(sentCount, expectedSize, `${type}: configured ${configuredSize}`);
    }
  }
});

test('Tavily, Exa, and Zhipu preserve supplied publication dates and leave missing dates unknown', async () => {
  const providers: { type: SearchServiceOptions['type']; service: SearchService; field: string; date: string }[] = [
    { type: 'tavily', service: tavilySearchService, field: 'published_date', date: '2026-10-24' },
    { type: 'exa', service: exaSearchService, field: 'publishedDate', date: '2026-10-24T00:00:00Z' },
    { type: 'zhipu', service: zhipuSearchService, field: 'publish_date', date: '2026-10-24' },
  ];
  for (const { type, service, field, date } of providers) {
    const dated: JsonObject = { title: 'Dated article', url: 'https://example.com/dated',
      link: 'https://example.com/dated', content: 'Article text', text: 'Article text', [field]: date };
    const undated: JsonObject = { title: 'Undated article', url: 'https://example.com/undated',
      link: 'https://example.com/undated', content: 'Other text', text: 'Other text' };
    const payload: JsonObject = type === 'zhipu'
      ? { search_result: [dated, undated] } : { results: [dated, undated] };
    initSearchSdk(captureHttp(payload, []));
    const result = await service.search({ query: 'news', topic: 'news' }, { resultSize: 10 }, keyedOptions(type));
    assert.deepEqual(result.items.map(item => item.publishedAt), [date, null], type);
  }
});

test('Zhipu limits enhanced queries at its adapter without losing the original query prefix', async () => {
  const requests: HttpRequest[] = [];
  initSearchSdk(captureHttp({ search_result: [] }, requests));
  const options = keyedOptions('zhipu');
  const settings = defaultSearchPrefs();
  settings.searchServices = [options];
  settings.searchEnabledServiceIds = [options.id];
  const query = 'Find latest semiconductor export restrictions and their impact';
  await searchOrchestratorSearch(settings, { query, topic: 'news', services: [options.id] }, undefined,
    { today: () => '2026-10-25' });
  assert.ok(requests.length > 0);
  for (const request of requests) {
    const sentQuery = (JSON.parse(request.body as string) as JsonObject)['search_query'] as string;
    assert.ok(sentQuery.startsWith(query));
    assert.ok(Array.from(sentQuery).length <= 70);
  }

  const unicodeQuery = '🔎'.repeat(71);
  await zhipuSearchService.search({ query: unicodeQuery }, { resultSize: 10 }, options);
  const sentQuery = (JSON.parse(requests[requests.length - 1].body as string) as JsonObject)['search_query'];
  assert.equal(sentQuery, '🔎'.repeat(70));
});
