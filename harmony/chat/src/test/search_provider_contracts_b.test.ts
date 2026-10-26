import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { HttpClient, HttpRequest } from '@amber/deepread-domain';
import type { JsonObject } from '../main/ets/chat/json.ts';
import type { SearchServiceOptions } from '../main/ets/search/search_service.ts';
import { initSearchSdk, makeSearchServiceOptions } from '../main/ets/search/search_service.ts';
import { defaultSearchPrefs } from '../main/ets/search/search_prefs.ts';
import { createDeepReadSearchProviders } from '../main/ets/search/deepread_search_registry.ts';
import { createSearchTools } from '../main/ets/search/search_tools.ts';

const settingsFor = (type: 'perplexity' | 'firecrawl') => {
  const options: SearchServiceOptions = makeSearchServiceOptions(type);
  if ('apiKey' in options) options.apiKey = 'fixture-key';
  const settings = defaultSearchPrefs();
  settings.enableWebSearch = true;
  settings.searchServices = [options];
  settings.searchEnabledServiceIds = [options.id];
  settings.searchBuiltinDuckDuckGoEnabled = false;
  settings.searchBuiltinBingEnabled = false;
  settings.searchBuiltinWikipediaEnabled = false;
  settings.searchBuiltinHackerNewsEnabled = false;
  settings.searchGoogleWebViewFallbackEnabled = false;
  return settings;
};

const installHttp = (body: JsonObject, status: number = 200): HttpRequest[] => {
  const calls: HttpRequest[] = [];
  const http: HttpClient = {
    fetch: async (request) => {
      calls.push(request);
      return { status, headers: {}, body: JSON.stringify(body) };
    },
    fetchStream: async () => { throw new Error('unused'); },
  };
  initSearchSdk(http);
  return calls;
};

const searchToolResult = async (type: 'perplexity' | 'firecrawl'): Promise<JsonObject> => {
  const tool = createSearchTools(settingsFor(type)).find(candidate => candidate.name === 'search_web');
  assert.ok(tool);
  const parts = await tool.execute({ query: 'dated article', topic: 'news', depth: 'quick' });
  assert.equal(parts[0].type, 'text');
  if (parts[0].type !== 'text') throw new Error('expected search text');
  return JSON.parse(parts[0].text) as JsonObject;
};

test('DeepRead Perplexity web requests respect the provider limit for the shared maximum setting', async () => {
  const calls = installHttp({ results: [{ title: 'Article', url: 'https://example.com/article', snippet: 'Text' }] });
  const settings = settingsFor('perplexity');
  settings.searchCommonOptions.resultSize = 50;
  const providers = createDeepReadSearchProviders(settings, { freeProviders: [], ensureSdk: () => {} });
  const hits = await providers[0].search(['query']);
  assert.equal(hits.length, 1);
  assert.equal(calls.length, 1);
  assert.equal((JSON.parse(calls[0].body as string) as JsonObject)['max_results'], 20);
});

test('Perplexity and Firecrawl dates survive the actual search tool and news aggregation', async () => {
  for (const type of ['perplexity', 'firecrawl'] as const) {
    const item = { title: 'Dated article', url: 'https://example.com/dated', date: '2026-10-24' };
    installHttp(type === 'perplexity'
      ? { results: [{ ...item, snippet: 'Article contents' }] }
      : { data: { news: [{ ...item, snippet: 'Article contents' }] } });
    const result = await searchToolResult(type);
    const items = result['items'] as JsonObject[];
    assert.equal(items[0]['published_at'], '2026-10-24', type);
    assert.equal(items[0]['published_at_unknown'], false, type);
    assert.equal(items[0]['freshness_score'], 12, type);
    if (type === 'firecrawl') assert.match(items[0]['text'] as string, /2026-10-24/);
  }
});

test('Firecrawl failures preserve the HTTP status and provider explanation through the tool result', async () => {
  for (const response of [{ status: 401, error: 'Invalid API key' }, { status: 429, error: 'Rate limit exceeded' }]) {
    installHttp({ success: false, error: response.error }, response.status);
    const result = await searchToolResult('firecrawl');
    const source = (result['sources'] as JsonObject[])[0];
    assert.equal(source['status'], 'error');
    assert.match(source['error'] as string, new RegExp(`#${response.status}`));
    assert.ok((source['error'] as string).includes(response.error));
  }
});
