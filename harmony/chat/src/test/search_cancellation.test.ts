import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { AbortSignalLike, HttpClient, HttpRequest, HttpResponse } from '@amber/deepread-domain';
import type { JsonObject } from '../main/ets/chat/json.ts';
import type { SearchRequestContext, SearchServiceOptionsType } from '../main/ets/search/search_service.ts';
import {
  initSearchSdk, makeSearchServiceOptions, SEARCH_SERVICE_TYPES,
} from '../main/ets/search/search_service.ts';
import { getSearchService } from '../main/ets/search/service_registry.ts';
import {
  runFreeEngine, resetFreeEngineBreakers, freeEngineCoolingDown,
} from '../main/ets/search/free_web_engines.ts';
import type { FreeEngineId } from '../main/ets/search/free_web_engines.ts';
import { createSearchTools } from '../main/ets/search/search_tools.ts';
import type { SearchSettings } from '../main/ets/search/search_aggregator.ts';
import { searchAggregatorSearch } from '../main/ets/search/search_aggregator.ts';
import { searchOrchestratorSearch } from '../main/ets/search/search_orchestrator.ts';

interface PendingRequest {
  request: HttpRequest;
  signal: AbortSignalLike | undefined;
  finish: (response: HttpResponse) => void;
}

class AbortAwareHttp implements HttpClient {
  calls: PendingRequest[] = [];

  fetch(request: HttpRequest, context?: SearchRequestContext): Promise<HttpResponse> {
    return new Promise<HttpResponse>((resolve, reject) => {
      const signal = context?.signal;
      const onAbort = (): void => {
        signal?.removeEventListener?.('abort', onAbort);
        reject(new Error('request cancelled'));
      };
      this.calls.push({
        request,
        signal,
        finish: (response: HttpResponse): void => {
          signal?.removeEventListener?.('abort', onAbort);
          resolve(response);
        },
      });
      if (signal?.aborted) onAbort();
      else signal?.addEventListener?.('abort', onAbort);
    });
  }

  fetchStream(): Promise<HttpResponse> {
    return Promise.reject(new Error('not used'));
  }
}

test('all configured and free search paths cancel the actual HTTP request with the per-call signal', async () => {
  const http = new AbortAwareHttp();
  initSearchSdk(http);
  resetFreeEngineBreakers();

  for (const type of Object.keys(SEARCH_SERVICE_TYPES) as SearchServiceOptionsType[]) {
    const options = makeSearchServiceOptions(type);
    if ('apiKey' in options) options.apiKey = 'test-key';
    if (options.type === 'searxng') options.url = 'https://search.example.com';
    const controller = new AbortController();
    const callIndex = http.calls.length;
    const run = getSearchService(options).search(
      { query: 'test' }, { resultSize: 3 }, options, { signal: controller.signal },
    );
    const rejected = assert.rejects(run, /request cancelled/);
    assert.equal(http.calls.length, callIndex + 1, `${type} must reach HTTP`);
    assert.equal(http.calls[callIndex].signal, controller.signal, type);
    controller.abort();
    await rejected;
  }

  const freeIds: FreeEngineId[] = [
    'duckduckgo', 'brave', 'bing', 'so360', 'quark', 'wikipedia', 'hackernews',
  ];
  for (const id of freeIds) {
    const controller = new AbortController();
    const callIndex = http.calls.length;
    const run = runFreeEngine(id, 'test', { resultSize: 3 }, { signal: controller.signal });
    const rejected = assert.rejects(run, /request cancelled/);
    assert.equal(http.calls.length, callIndex + 1, `${id} must reach HTTP`);
    assert.equal(http.calls[callIndex].signal, controller.signal, id);
    controller.abort();
    await rejected;
  }
});

test('configured scrape calls also cancel HTTP with their own signal', async () => {
  const http = new AbortAwareHttp();
  initSearchSdk(http);
  for (const type of ['tavily', 'jina', 'linkup', 'firecrawl'] as const) {
    const options = makeSearchServiceOptions(type);
    const controller = new AbortController();
    const callIndex = http.calls.length;
    const run = getSearchService(options).scrape(
      { url: 'https://example.com/article' }, { resultSize: 3 }, options,
      { signal: controller.signal },
    );
    const rejected = assert.rejects(run, /request cancelled/);
    assert.equal(http.calls.length, callIndex + 1);
    assert.equal(http.calls[callIndex].signal, controller.signal, type);
    controller.abort();
    await rejected;
  }
});

test('cancelling one concurrent search leaves another search and legacy calls independent', async () => {
  const http = new AbortAwareHttp();
  initSearchSdk(http);
  resetFreeEngineBreakers();
  const first = new AbortController();
  const second = new AbortController();
  const options = makeSearchServiceOptions('tavily');
  const cancelled = getSearchService(options).search(
    { query: 'first' }, { resultSize: 3 }, options, { signal: first.signal },
  );
  const rejected = assert.rejects(cancelled, /request cancelled/);
  const survivor = runFreeEngine('hackernews', 'second', { resultSize: 3 }, { signal: second.signal });
  const legacy = getSearchService(options).search({ query: 'legacy' }, { resultSize: 3 }, options);
  assert.deepEqual(http.calls.map(call => call.signal), [first.signal, second.signal, undefined]);

  first.abort();
  await rejected;
  assert.equal(second.signal.aborted, false);
  http.calls[1].finish({ status: 200, headers: {}, body: '{"hits":[]}' });
  http.calls[2].finish({ status: 200, headers: {}, body: '{"answer":"legacy","results":[]}' });
  assert.deepEqual(await survivor, { answer: null, items: [] });
  assert.deepEqual(await legacy, { answer: 'legacy', items: [] });
});

test('chat search and scrape entrypoints forward their own cancellation signal to HTTP', async () => {
  const http = new AbortAwareHttp();
  initSearchSdk(http);
  const service = makeSearchServiceOptions('tavily');
  if (service.type !== 'tavily') throw new Error('fixture must be Tavily');
  service.apiKey = 'test-key';
  const settings: SearchSettings = {
    enableWebSearch: true, searchCommonOptions: { resultSize: 3 }, searchServices: [service],
    searchServiceSelected: 0, searchEnabledServiceIds: [service.id],
    searchBuiltinJinaEnabled: true, searchBuiltinDuckDuckGoEnabled: false,
    searchBuiltinBingEnabled: false, searchBuiltinWikipediaEnabled: false,
    searchBuiltinHackerNewsEnabled: false, searchGoogleWebViewFallbackEnabled: false,
  };
  const tools = createSearchTools(settings);
  for (const name of ['search_web', 'scrape_web']) {
    const controller = new AbortController();
    const index = http.calls.length;
    const input: JsonObject = name === 'search_web'
      ? { query: 'cancel me', depth: 'quick' } : { url: 'https://example.com/article' };
    const run = tools.find(tool => tool.name === name)!.execute(input, controller.signal);
    const rejected = assert.rejects(run, { name: 'AbortError' });
    const actualSignals = http.calls.slice(index).map(call => call.signal);
    controller.abort();
    // Also release legacy requests in the red run; the assertions must not leak HTTP promises.
    for (const call of http.calls.slice(index)) call.finish({ status: 200, headers: {}, body: '{"results":[]}' });
    await rejected;
    assert.ok(actualSignals.length > 0);
    assert.ok(actualSignals.every(signal => signal === controller.signal), name);
  }
});

test('cancelled aggregations reject instead of reporting failed sources, and pre-cancelled calls never reach HTTP', async () => {
  const http = new AbortAwareHttp();
  initSearchSdk(http);
  const service = makeSearchServiceOptions('tavily');
  const settings: SearchSettings = {
    enableWebSearch: true, searchCommonOptions: { resultSize: 3 }, searchServices: [service],
    searchServiceSelected: 0, searchEnabledServiceIds: [service.id],
    searchBuiltinJinaEnabled: false, searchBuiltinDuckDuckGoEnabled: false,
    searchBuiltinBingEnabled: false, searchBuiltinWikipediaEnabled: false,
    searchBuiltinHackerNewsEnabled: false, searchGoogleWebViewFallbackEnabled: false,
  };
  for (const aggregate of [searchOrchestratorSearch, searchAggregatorSearch]) {
    const controller = new AbortController();
    const run = aggregate(settings, { query: 'test', depth: 'quick' }, undefined, {}, { signal: controller.signal });
    const rejected = assert.rejects(run, { name: 'AbortError' });
    controller.abort();
    await rejected;
    const count = http.calls.length;
    await assert.rejects(aggregate(settings, { query: 'never requested' }, undefined, {},
      { signal: controller.signal }), { name: 'AbortError' });
    assert.equal(http.calls.length, count);
  }
});

test('a cancelled free-engine response does not trip the breaker or cancel an independent request', async () => {
  resetFreeEngineBreakers();
  const cancelled = new AbortController();
  const surviving = new AbortController();
  const finishes: Array<(response: HttpResponse) => void> = [];
  initSearchSdk({ fetch: (_request, context) => new Promise(resolve => {
    assert.ok(context?.signal === cancelled.signal || context?.signal === surviving.signal);
    finishes.push(resolve);
  }), fetchStream: () => Promise.reject(new Error('unused')) });
  const first = runFreeEngine('brave', 'cancelled', { resultSize: 3 }, { signal: cancelled.signal });
  const rejection = assert.rejects(first, /HTTP 403/);
  const second = runFreeEngine('brave', 'surviving', { resultSize: 3 }, { signal: surviving.signal });
  cancelled.abort();
  finishes[0]({ status: 403, headers: {}, body: 'blocked' });
  finishes[1]({ status: 200, headers: {}, body: '<div data-type="web"><a href="https://example.com">'
    + '<div class="title">Survivor</div></a><div class="snippet"><div class="content">Valid result</div></div></div>' });
  await rejection;
  assert.equal((await second).items[0].url, 'https://example.com');
  assert.equal(surviving.signal.aborted, false);
  assert.equal(freeEngineCoolingDown('brave'), false);
  const third = runFreeEngine('brave', 'actual failure', { resultSize: 3 }, { signal: surviving.signal });
  const actualFailure = assert.rejects(third, /HTTP 403/);
  finishes[2]({ status: 403, headers: {}, body: 'blocked' });
  await actualFailure;
  assert.equal(freeEngineCoolingDown('brave'), true);
  resetFreeEngineBreakers();
});
