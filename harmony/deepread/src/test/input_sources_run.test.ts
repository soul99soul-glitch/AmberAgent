import test from 'node:test';
import assert from 'node:assert/strict';
import { createRunContext, run } from '../main/ets/agent/run_manager.ts';
import type { RunManagerDeps } from '../main/ets/agent/run_manager.ts';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';
import type { DeepReadOutput } from '../main/ets/domain/models.ts';
import { makeInputSource, sourceInputs } from '../main/ets/domain/input_sources.ts';
import type { DeepReadSource } from '../main/ets/research/source_prefetcher.ts';

const web = (index: number): DeepReadSource => ({ sourceId: `web-${index}`, url: `https://example.com/${index}`,
  title: `Article ${index}`, source: 'search', evidenceText: 'verified body', credibility: 'medium', freshness: 'unknown', publishedAt: null, imageCandidates: [] });

const memoryDeps = (initial: DeepReadOutput): { deps: RunManagerDeps; current: () => DeepReadOutput } => {
  let stored = initial;
  const deps: RunManagerDeps = {
    prefetcher: { collect: async () => [], cacheSize: () => 0 }, aiClient: { generateText: async () => [] },
    collectRun: async messages => messages, model: 'mock', playbookMarkdown: '', nowIso: () => '2026-10-03',
    repository: { get: () => stored, save: async (_id, _title, output) => { stored = JSON.parse(JSON.stringify(output)); }, clear: () => {} },
  };
  return { deps, current: () => stored };
};

test('standalone sources persist all files and raw pasted text but the shared pipeline consumes ten', async () => {
  const initial = makeEmptyDeepReadOutput();
  initial.inputText = ' x\r\n' + '中'.repeat(40010);
  initial.inputSources = [makeInputSource('text', 'paste', initial.inputText),
    ...Array.from({ length: 12 }, (_, index) => makeInputSource('file', `file-${index}.txt`, `file body ${index}`))];
  const { deps, current } = memoryDeps(initial);
  const result = await createRunContext(deps, 't1', 'topic', null, false);
  assert.equal(result.ok, true);
  if (!result.ok) return;
  assert.equal(result.context.evidencePack.allSources.length, 10);
  assert.equal(current().inputSources?.length, 13);
  assert.equal(current().inputText, initial.inputText);
  assert.equal(result.context.writer.current().inputSources?.length, 13);
});

test('captured full collection survives consumption cap and records search and source failures', async () => {
  const initial = makeEmptyDeepReadOutput();
  initial.inputSources = sourceInputs('', 'https://example.com/fail');
  initial.inputSourceUrls = ['https://example.com/fail'];
  const { deps, current } = memoryDeps(initial);
  deps.prefetcher = {
    cacheSize: () => 0,
    collect: async (_id, _title, _seed, _force, _signal, _urls, onCollected) => {
      const all = Array.from({ length: 15 }, (_, index) => web(index));
      onCollected?.(all, [{ url: 'https://example.com/fail', title: 'failed page', error: 'HTTP 403' },
        { url: '', title: '搜索：provider', error: 'missing API key' }]);
      return all.slice(0, 12);
    },
  };
  const result = await createRunContext(deps, 't1', 'topic', null, false);
  assert.equal(result.ok, true);
  assert.equal(current().inputSources?.length, 17);
  assert.equal(current().inputSources?.[0].error, 'HTTP 403');
  assert.equal(current().inputSources?.[16].error, 'missing API key');
  if (result.ok) assert.equal(result.context.evidencePack.allSources.length, 10);
});

test('no-source failed run retains user URL text and failed collection instead of dropping inputs', async () => {
  const initial = makeEmptyDeepReadOutput();
  initial.inputUrlsText = 'https://example.com/fail\ninvalid';
  initial.inputSourceUrls = ['https://example.com/fail'];
  initial.inputSources = sourceInputs('', initial.inputUrlsText);
  const { deps, current } = memoryDeps(initial);
  const result = await run(deps, 't1', 'topic');
  assert.equal(result.ok, false);
  assert.equal(current().inputUrlsText, initial.inputUrlsText);
  assert.equal(current().inputSources?.length, 2);
  assert.equal(current().inputSources?.[0].status, 'failed');
  assert.equal(current().sectionStates.OVERVIEW.status, 'FAILED');
});

test('retry keeps successfully read original webpage content when a new fetch fails', async () => {
  const initial = makeEmptyDeepReadOutput();
  initial.inputSourceUrls = ['https://example.com/1'];
  initial.inputSources = [makeInputSource('web', 'original', 'saved original body', 'https://example.com/1')];
  const { deps, current } = memoryDeps(initial);
  deps.prefetcher = { cacheSize: () => 0, collect: async (_id, _title, _seed, _force, _signal, _urls, onCollected) => {
    onCollected?.([], [{ url: 'https://example.com/1', title: 'original', error: 'HTTP 500' }]);
    return [];
  } };
  const result = await createRunContext(deps, 't1', 'topic', null, false);
  assert.equal(result.ok, true);
  assert.equal(current().inputSources?.[0].content, 'saved original body');
  if (result.ok) assert.equal(result.context.evidencePack.allSources[0].evidenceText, 'saved original body');
});

test('source collection persists an interrupted phase before network work so cold launch can continue', async () => {
  const initial = makeEmptyDeepReadOutput();
  initial.inputSources = sourceInputs('user context', '');
  const { deps, current } = memoryDeps(initial);
  const controller = new AbortController();
  deps.prefetcher = { cacheSize: () => 0, collect: async () => {
    assert.equal(current().generationPhase, 'COLLECTING');
    assert.equal(current().inputSources?.[0].content, 'user context');
    controller.abort();
    return [];
  } };
  const result = await run(deps, 't1', 'topic', { signal: controller.signal });
  assert.equal(result.error, 'aborted');
  assert.equal(current().generationPhase, 'COLLECTING');
  assert.equal(current().inputSources?.[0].content, 'user context');
});

test('forced standalone replacement keeps the exact committed article until a new complete result is saved', async () => {
  const old = makeEmptyDeepReadOutput();
  old.summary = '已提交文章';
  old.generationComplete = true;
  old.generationPhase = 'COMPLETE';
  old.sectionStates = { OVERVIEW: { status: 'READY', errorMessage: null }, NARRATIVE: { status: 'READY', errorMessage: null },
    ANALYSIS: { status: 'READY', errorMessage: null }, EXTENDED_READING: { status: 'READY', errorMessage: null } };
  old.inputText = '用户提供的原始资料';
  old.inputSources = sourceInputs(old.inputText, '');
  let saves = 0;
  const { deps } = memoryDeps(old);
  deps.repository = { get: () => old, save: () => { saves++; }, clear: () => {} };
  deps.collectRun = async () => { throw new Error('provider unavailable'); };
  const result = await run(deps, 't1', 'topic', { force: true });
  assert.equal(result.ok, false);
  assert.equal(result.output, old);
  assert.equal(saves, 0);
  assert.equal(old.inputSources[0].content, old.inputText);
});

test('individual failed search angles are retained alongside successful evidence', async () => {
  const { createSourcePrefetcher } = await import('../main/ets/research/source_prefetcher.ts');
  const { deps, current } = memoryDeps({ ...makeEmptyDeepReadOutput(), inputText: '', inputUrlsText: '', inputSources: [] });
  deps.prefetcher = createSourcePrefetcher({ fetch: async () => ({ status: 200, headers: {},
    body: `<article><p>${'可核查研究资料解释了具体背景和后续变化。'.repeat(40)}</p></article>` }),
  fetchStream: async () => { throw new Error('unused'); } }, { enabled: () => [{ name: 'query-provider', search: async (queries, _signal, onFailure) => {
    onFailure?.(queries[1], 'HTTP 429');
    return [{ title: 'good article', url: 'https://example.com/good', snippet: '', source: 'query-provider' }];
  } }], fallback: () => [] });
  const result = await createRunContext(deps, 't1', 'topic', null, false);
  assert.equal(result.ok, true);
  const failure = current().inputSources?.find(source => source.status === 'failed');
  assert.match(failure?.title ?? '', /query-provider.*topic 最新进展/);
  assert.equal(failure?.error, 'HTTP 429');
  assert.equal(current().inputSources?.filter(source => source.status === 'ready').length, 1);
});
