import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';
import { createSourcePrefetcher } from '../main/ets/research/source_prefetcher.ts';
import type { AbortControllerLike, AbortSignalLike } from '../main/ets/platform/runtime_api.ts';
import type { HttpClient } from '../main/ets/platform/http.ts';
import type { SearchProviderRegistry } from '../main/ets/platform/search.ts';

const loadEntry = (filename: string, imports: Record<string, object> = {}): Record<string, unknown> => {
  const path = new URL('../../../entry/src/main/ets/platform_impl/' + filename, import.meta.url);
  const exports: Record<string, unknown> = {};
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(path, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, {
    exports, require: (name: string): object => {
      if (imports[name] !== undefined) return imports[name];
      throw new Error('unexpected runtime import ' + name);
    }, Date, setTimeout, clearTimeout, Promise, Error, ArrayBuffer, Uint8Array,
  }, { filename: path.pathname });
  return exports;
};

const entryControllerFactory = (): (() => AbortControllerLike) =>
  loadEntry('EntryAbortController.ets').createEntryAbortController as () => AbortControllerLike;
const listenerCount = (signal: AbortSignalLike): number =>
  (signal as unknown as { listeners: Array<() => void> }).listeners.length;

const noSearch: SearchProviderRegistry = { enabled: () => [], fallback: () => [],
  snapshot: async () => ({ providers: [], readerEnabled: false, cacheKey: '' }) };
const body = '<html><body><p>' + '实际来源详细说明研究背景、进展和各方立场。'.repeat(22) + '</p></body></html>';
interface RequestRecord { url: string; destroyed: number; }

const actualHttpFixture = (fastUrls: string[] = []): { http: HttpClient; requests: RequestRecord[] } => {
  const requests: RequestRecord[] = [];
  const networkHttp = {
    RequestMethod: { GET: 'GET', POST: 'POST', PUT: 'PUT', PATCH: 'PATCH', DELETE: 'DELETE' },
    HttpDataType: { STRING: 'STRING', ARRAY_BUFFER: 'ARRAY_BUFFER' },
    createHttp: (): object => {
      const record: RequestRecord = { url: '', destroyed: 0 };
      requests.push(record);
      return {
        request: (url: string): Promise<object> => {
          record.url = url;
          return fastUrls.includes(url) ? Promise.resolve({ responseCode: 200, header: {}, result: body })
            : new Promise<object>(() => {});
        },
        destroy: (): void => { record.destroyed++; },
      };
    },
  };
  const exports = loadEntry('RcpHttpClient.ets', {
    '@kit.NetworkKit': { http: networkHttp }, '@kit.ArkTS': { util: {} },
    '@kit.PerformanceAnalysisKit': { hilog: { info: (): void => {}, warn: (): void => {}, error: (): void => {} } },
    '@amber/deepread-domain': {},
  });
  return { http: (exports.createRcpHttpClient as () => HttpClient)(), requests };
};

test('actual Entry signal keeps its receiver and a 200 source survives a signalled collect', async () => {
  const createController = entryControllerFactory();
  const parent = createController();
  const url = 'https://seed.example.com/article';
  const fixture = actualHttpFixture([url]);
  const sources = await createSourcePrefetcher(fixture.http, noSearch, createController)
    .collect('entry-signal', '实际资料', url, true, parent.signal);
  assert.equal(sources.length, 1);
  assert.equal(sources[0].url, url);
  assert.equal(parent.signal.aborted, false);
  assert.equal(listenerCount(parent.signal), 0);
  assert.equal(fixture.requests[0].destroyed, 1);
});

test('15s budget destroys actual pending HTTP but keeps completed sources and the parent usable', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 1_000 });
  const createController = entryControllerFactory();
  const parent = createController();
  const fast = 'https://seed.example.com/fast';
  const slow = 'https://seed.example.com/slow';
  const fixture = actualHttpFixture([fast]);
  const collecting = createSourcePrefetcher(fixture.http, noSearch, createController)
    .collect('wall-budget', '实际资料', fast, true, parent.signal, [slow]);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(fixture.requests.find(request => request.url === slow)?.destroyed, 0);
  t.mock.timers.tick(15_000);
  const sources = await collecting;
  assert.deepEqual(sources.map(source => source.url), [fast]);
  assert.equal(fixture.requests.find(request => request.url === slow)?.destroyed, 1);
  assert.equal(parent.signal.aborted, false, 'research time budget must not cancel later LLM stages');
  assert.equal(listenerCount(parent.signal), 0);
  const later = await fixture.http.fetch({ url: fast, method: 'POST', headers: {}, body: 'later LLM request' }, { signal: parent.signal });
  assert.equal(later.status, 200, 'later work can still use the parent signal after the source deadline');
});

test('collection finalization destroys timed-out provider and scrape requests using the child signal', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 1_000 });
  const createController = entryControllerFactory();
  const parent = createController();
  const fast = 'https://seed.example.com/fast';
  const providerUrl = 'https://search.example.com/hangs';
  const scrapeUrl = 'https://article.example.com/hangs';
  const fixture = actualHttpFixture([fast]);
  const registry: SearchProviderRegistry = { enabled: () => [
    { name: 'slow', search: async (_queries: string[], signal?: AbortSignalLike) => {
      await fixture.http.fetch({ url: providerUrl, method: 'GET', headers: {} }, { signal });
      return [];
    } },
    { name: 'fast', search: async () => [{ title: '正文', url: scrapeUrl, source: 'fast', snippet: '' }] },
  ], fallback: () => [] };
  const collecting = createSourcePrefetcher(fixture.http, registry, createController)
    .collect('provider-cleanup', '实际资料', fast, true, parent.signal);
  await new Promise(resolve => setImmediate(resolve));
  t.mock.timers.tick(10_000);
  await new Promise(resolve => setImmediate(resolve));
  assert.ok(fixture.requests.some(request => request.url === scrapeUrl));
  t.mock.timers.tick(5_000);
  const sources = await collecting;
  assert.deepEqual(sources.map(source => source.url), [fast]);
  for (const request of fixture.requests) assert.equal(request.destroyed, 1, request.url);
  assert.equal(parent.signal.aborted, false);
  assert.equal(listenerCount(parent.signal), 0);
});

test('a user abort forwards to the child, destroys actual pending HTTP and clears the parent listener', async () => {
  const createController = entryControllerFactory();
  const parent = createController();
  const fixture = actualHttpFixture();
  const collecting = createSourcePrefetcher(fixture.http, noSearch, createController)
    .collect('user-abort', '实际资料', 'https://seed.example.com/slow', true, parent.signal);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(listenerCount(parent.signal), 1);
  parent.abort();
  assert.deepEqual(await collecting, []);
  assert.equal(fixture.requests[0].destroyed, 1);
  assert.equal(parent.signal.aborted, true);
  assert.equal(listenerCount(parent.signal), 0);
});
