import test from 'node:test';
import assert from 'node:assert/strict';
import { createDeepReadSearchProviders } from '../main/ets/search/deepread_search_registry.ts';
import { defaultSearchPrefs } from '../main/ets/search/search_prefs.ts';
import { createSourcePrefetcher } from '../../../deepread/src/main/ets/research/source_prefetcher.ts';
import type { SearchHit } from '@amber/deepread-domain';

const hits: SearchHit[] = ['one', 'two'].map(id => ({ title: '可核查来源', url: `https://example.com/${id}`,
  snippet: '摘要', source: 'free' }));
const flush = (): Promise<void> => new Promise(resolve => setImmediate(resolve));

test('remaining aggregate budget returns successful free hits before the prefetch deadline and skips later Google queries', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 1000 });
  let googleCalls = 0; let stopped = 0; const fetched: string[] = [];
  const providers = createDeepReadSearchProviders(defaultSearchPrefs(), { ensureSdk: () => {},
    freeProviders: [{ name: 'free', search: async () => { await new Promise(resolve => setTimeout(resolve, 8000)); return hits; } }],
    searchGoogle: async (_query, _size, signal) => new Promise((_resolve, reject) => {
      googleCalls++;
      const stop = (): void => { signal?.removeEventListener?.('abort', stop); stopped++; reject(new Error('Google cancelled')); };
      signal?.addEventListener?.('abort', stop);
    }) });
  let completed = false;
  const task = createSourcePrefetcher({ fetch: async request => { fetched.push(request.url); return { status: 200, headers: {},
    body: `<article><p>${'真实网页正文包含核查后的背景、时间与观点。'.repeat(40)}</p></article>` }; },
  fetchStream: async () => { throw new Error('unused'); } }, { enabled: () => providers, fallback: () => [],
    snapshot: async () => ({ providers, readerEnabled: false, cacheKey: '' }) }, () => new AbortController())
    .collect('topic', '话题', null, true).then(sources => { completed = true; return sources; });
  await flush(); t.mock.timers.tick(8000); await flush();
  assert.equal(googleCalls, 1);
  t.mock.timers.tick(1000); await flush(); const completedBeforeOuterDeadline = completed;
  // Settle the original buggy outer deadline too, so RED does not leave a pending collection.
  t.mock.timers.tick(1000); await flush(); const sources = await task;
  assert.equal(completedBeforeOuterDeadline, true);
  assert.deepEqual(sources.map(source => source.url), hits.map(hit => hit.url));
  assert.deepEqual(fetched, hits.map(hit => hit.url));
  assert.equal(googleCalls, 1);
  assert.equal(stopped, 1, 'prefetch finalization still aborts the request owned by its child signal');
});

test('free collection consuming the entire soft budget returns its hits without beginning Google', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 1000 });
  let googleCalls = 0;
  const provider = createDeepReadSearchProviders(defaultSearchPrefs(), { ensureSdk: () => {},
    freeProviders: [{ name: 'free', search: async () => { await new Promise(resolve => setTimeout(resolve, 9000)); return hits; } }],
    searchGoogle: async () => { googleCalls++; return []; } })[0];
  const task = provider.search(['first', 'later']); await flush(); t.mock.timers.tick(9000); await flush();
  assert.deepEqual(await task, hits);
  assert.equal(googleCalls, 0);
});
