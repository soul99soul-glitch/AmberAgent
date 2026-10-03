import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';
import * as chat from '../main/ets/index.ts';
import * as deepread from '@amber/deepread-domain';
import { defaultSearchPrefs } from '../main/ets/search/search_prefs.ts';
import { initSearchSdk, makeSearchServiceOptions } from '../main/ets/search/search_service.ts';
import { createDeepReadSearchProviders } from '../main/ets/search/deepread_search_registry.ts';
import type { HttpClient, HttpRequest, SearchHit, SearchProvider, SearchProviderRegistry, Storage } from '@amber/deepread-domain';
const hit = (url: string): SearchHit => ({ url, title: url, snippet: '正文', source: 'free' });
const free = (hits: SearchHit[]): SearchProvider => ({ name: 'free', search: async () => hits });
const fixture = () => {
  const settings = defaultSearchPrefs(); const requests: HttpRequest[] = [];
  const first = makeSearchServiceOptions('serper'); const second = makeSearchServiceOptions('tavily');
  if (first.type !== 'serper' || second.type !== 'tavily') throw new Error('fixture types');
  first.id = 'serper-selected'; first.apiKey = 'serper-key'; second.id = 'tavily-other'; second.apiKey = 'tavily-key';
  settings.searchServices = [first, second]; settings.searchEnabledServiceIds = [first.id, second.id];
  settings.searchServiceSelected = 0;
  const http: HttpClient = { fetch: async request => { requests.push(request); return { status: 200, headers: {},
    body: request.url.includes('serper') ? JSON.stringify({ organic: [{ title: 'selected', link: 'https://selected.test', snippet: 's' }] })
      : JSON.stringify({ results: [{ title: 'other', url: 'https://other.test', content: 's' }] }) }; },
    fetchStream: async () => { throw new Error('stream forbidden'); } };
  initSearchSdk(http);
  return { settings, requests, http };
};
test('actual configured HTTP sends only the selected enabled service and all supplied research angles', async () => {
  const f = fixture(); let freeCalls = 0; let googleCalls = 0;
  const providers = createDeepReadSearchProviders(f.settings, { ensureSdk: () => {},
    freeProviders: [{ name: 'free', search: async () => { freeCalls++; return []; } }],
    searchGoogle: async () => { googleCalls++; return []; } });
  await Promise.all(providers.map(provider => provider.search(['原主题', '官方声明'])));
  assert.equal(f.requests.length, 2); assert.equal(f.requests.every(request => request.url.includes('serper')), true);
  assert.deepEqual(f.requests.map(request => JSON.parse(request.body!).q), ['原主题', '官方声明']);
  assert.equal(freeCalls, 0); assert.equal(googleCalls, 0);
});
test('snapshot selection changes the actual selected HTTP; disabled or missing preferred ID picks one enabled fallback', async () => {
  for (const preferred of ['tavily-other', 'removed', 'serper-selected']) {
    const f = fixture(); if (preferred === 'serper-selected') f.settings.searchEnabledServiceIds = ['tavily-other'];
    const providers = createDeepReadSearchProviders(f.settings, { ensureSdk: () => {}, freeProviders: [] }, preferred);
    await Promise.all(providers.map(provider => provider.search(['q'])));
    assert.equal(f.requests.length, 1); assert.match(f.requests[0].url, preferred === 'removed' ? /serper/ : /tavily/);
  }
});
test('free weak results invoke actual Google port only when enabled, and retain ordinary results when native search fails', async () => {
  for (const enabled of [false, true]) {
    const settings = defaultSearchPrefs(); settings.searchGoogleWebViewFallbackEnabled = enabled;
    let calls = 0;
    const providers = createDeepReadSearchProviders(settings, { ensureSdk: () => {}, freeProviders: [free([hit('https://free.test')])],
      searchGoogle: async (query, size) => { calls++; assert.equal(query, 'q'); assert.equal(size, settings.searchCommonOptions.resultSize);
        return [hit('https://google.test')]; } });
    const hits = (await Promise.all(providers.map(provider => provider.search(['q'])))).flat();
    assert.equal(calls, enabled ? 1 : 0); assert.equal(hits.length, enabled ? 2 : 1);
  }
  const settings = defaultSearchPrefs(); let calls = 0;
  const providers = createDeepReadSearchProviders(settings, { ensureSdk: () => {}, freeProviders: [free([hit('https://free.test')])],
    searchGoogle: async () => { calls++; throw new Error('native blocked'); } });
  assert.equal((await providers[0].search(['q'])).length, 1); assert.equal(calls, 1);
});
test('strong results and already cancelled owner never invoke Google or more HTTP', async () => {
  const settings = defaultSearchPrefs(); let googleCalls = 0; let freeCalls = 0;
  const providers = createDeepReadSearchProviders(settings, { ensureSdk: () => {},
    freeProviders: [{ name: 'free', search: async () => { freeCalls++; return [1, 2, 3].map(index => hit(`https://${index}.test`)); } }],
    searchGoogle: async () => { googleCalls++; return []; } });
  await providers[0].search(['q']); assert.equal(googleCalls, 0);
  const controller = new AbortController(); controller.abort();
  await providers[0].search(['q'], controller.signal); assert.equal(freeCalls, 1); assert.equal(googleCalls, 0);
});
test('Google owns only its exact request signal, aborts that request and releases its subscription', async () => {
  const settings = defaultSearchPrefs(); const controller = new AbortController(); const unrelated = new AbortController();
  let nativeCalls = 0; let cancelled = 0; let released = 0;
  const providers = createDeepReadSearchProviders(settings, { ensureSdk: () => {}, freeProviders: [free([])],
    searchGoogle: async (_query, _size, signal) => new Promise((_resolve, reject) => {
      nativeCalls++; assert.equal(signal, controller.signal);
      const abort = () => { signal?.removeEventListener?.('abort', abort); released++; cancelled++; reject(new Error('native cancelled')); };
      signal?.addEventListener?.('abort', abort);
    }) });
  const result = providers[0].search(['q', 'later'], controller.signal);
  await new Promise(resolve => setImmediate(resolve)); unrelated.abort(); assert.equal(cancelled, 0);
  controller.abort(); await result;
  assert.equal(nativeCalls, 1); assert.equal(cancelled, 1); assert.equal(released, 1);
});

const actualEntryRegistry = (f: ReturnType<typeof fixture>, searchGoogle?: (query: string, size: number, signal?: deepread.AbortSignalLike) => Promise<SearchHit[]>): SearchProviderRegistry => {
  const source = new URL('../../../entry/src/main/ets/platform_impl/SearchRegistry.ets', import.meta.url);
  const exports: Record<string, unknown> = {};
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(source, 'utf8'), { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
  } }).outputText, { exports, require: (name: string) => {
    if (name === '@amber/chat-domain') return { ...chat, createDeepReadSearchProviders };
    if (name === '@amber/deepread-domain') return deepread;
    if (name === './NewsNowProvider.ets') return {};
    if (name === '@kit.PerformanceAnalysisKit') return { hilog: { warn: () => {} } };
    throw new Error('unexpected entry import ' + name);
  }, Date, setTimeout, clearTimeout, Promise, Error }, { filename: source.pathname });
  const storage: Storage = { get: async (_key, fallback) => fallback, set: async () => {}, delete: async () => {},
    getSecret: async () => null, setSecret: async () => {}, deleteSecret: async () => {}, getAllStringEntries: async () => [] };
  const create = exports.createTavilyAndFallbackRegistry as (http: HttpClient, storage: Storage,
    load: () => Promise<typeof f.settings>, ensure: () => void, options: { searchGoogle?: typeof searchGoogle }) => SearchProviderRegistry;
  return create(f.http, storage, async () => structuredClone(f.settings), () => {}, { searchGoogle });
};
test('actual Entry registry freezes selected request set per snapshot and next snapshot uses changed prefs', async () => {
  const f = fixture(); const registry = actualEntryRegistry(f);
  const old = await registry.snapshot!(); f.settings.searchServiceSelected = 1;
  await Promise.all(old.providers.map(provider => provider.search(['old q'])));
  assert.equal(f.requests.length, 1); assert.match(f.requests[0].url, /serper/);
  const next = await registry.snapshot!(); await Promise.all(next.providers.map(provider => provider.search(['new q'])));
  assert.equal(f.requests.length, 2); assert.match(f.requests[1].url, /tavily/);
  assert.notEqual(old.cacheKey, next.cacheKey);
  f.settings.searchServiceSelected = 0; f.settings.searchServices[0] = { ...f.settings.searchServices[0], apiKey: '' } as typeof f.settings.searchServices[0];
  await Promise.all((await registry.snapshot!()).providers.map(provider => provider.search(['missing key'])));
  assert.equal(f.requests.length, 3); assert.match(f.requests[2].url, /tavily/);
});
test('actual Entry Google option is called for weak free results; disabled snapshot makes zero native calls', async () => {
  const f = fixture(); f.settings.searchEnabledServiceIds = [];
  f.settings.searchBuiltinDuckDuckGoEnabled = false; f.settings.searchBuiltinBingEnabled = false;
  f.settings.searchBuiltinWikipediaEnabled = false; f.settings.searchBuiltinHackerNewsEnabled = false;
  let nativeCalls = 0;
  const registry = actualEntryRegistry(f, async () => { nativeCalls++; return [hit('https://native.test')]; });
  const disabled = { ...f.settings, searchGoogleWebViewFallbackEnabled: false };
  f.settings = disabled;
  const disabledRun = await registry.snapshot!();
  f.settings = { ...f.settings, searchGoogleWebViewFallbackEnabled: true };
  await disabledRun.providers[0].search(['q']); assert.equal(nativeCalls, 0);
  assert.equal((await (await registry.snapshot!()).providers[0].search(['q'])).length, 1);
  assert.equal(nativeCalls, 1); assert.equal(f.requests.length, 0);
});

test('selected configured service cancels only its actual HTTP request and skips later angles', async () => {
  const f = fixture(); const owner = new AbortController(); const unrelated = new AbortController();
  let calls = 0; let cancelled = 0; let released = 0;
  initSearchSdk({ fetch: async (_request, options) => new Promise((_resolve, reject) => {
    calls++; assert.equal(options?.signal, owner.signal);
    const abort = () => { options?.signal?.removeEventListener?.('abort', abort); released++; cancelled++; reject(new Error('HTTP cancelled')); };
    options?.signal?.addEventListener?.('abort', abort);
  }), fetchStream: async () => { throw new Error('stream forbidden'); } });
  const provider = createDeepReadSearchProviders(f.settings, { ensureSdk: () => {}, freeProviders: [] })[0];
  const result = provider.search(['first', 'later'], owner.signal);
  await new Promise(resolve => setImmediate(resolve)); unrelated.abort(); assert.equal(cancelled, 0);
  owner.abort(); await result;
  assert.equal(calls, 1); assert.equal(cancelled, 1); assert.equal(released, 1);
  await provider.search(['already cancelled'], owner.signal); assert.equal(calls, 1);
});
