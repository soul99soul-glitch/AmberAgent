import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';
import { createSourcePrefetcher, buildDeepReadQueries } from '../main/ets/research/source_prefetcher.ts';
import type { HttpClient } from '../main/ets/platform/http.ts';
import type { SearchProviderRegistry, SearchHit } from '../main/ets/platform/search.ts';
import type { AbortSignalLike } from '../main/ets/platform/runtime_api.ts';
import { buildPrompt } from '../main/ets/agent/supervisor_loop.ts';
import { fallbackPlan } from '../main/ets/research/article_plan.ts';
import { buildEvidencePack, cardsFor } from '../main/ets/research/evidence_pack.ts';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';
import { createSectionWriterTools } from '../main/ets/agent/section_writer_tools.ts';
import type { RunManagerDeps } from '../main/ets/agent/run_manager.ts';
import { run } from '../main/ets/agent/run_manager.ts';
import type { DeepReadOutput } from '../main/ets/domain/models.ts';
import { WRITER_TOOL_NAMES } from '../main/ets/domain/enums.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
import { createScheduler } from '../main/ets/agent/scheduler.ts';

const body = '<html><body><p>这是一篇包含真实研究资料的来源文章，其中描述了研究背景以及已确认的进展供后续分析使用。</p></body></html>';
const longBody = '<html><body><p>' + Array.from({ length: 16 }, (_, i) => `第${i}段真实资料详细解释了事件发生的前因后果、最新进展以及各方立场，供后续研究交叉核验。`).join('</p><p>') + '</p></body></html>';
const httpFor = (calls: string[], html = body): HttpClient => ({
  fetch: async request => { calls.push(request.url); return { status: 200, headers: {}, body: html }; },
  fetchStream: async () => { throw new Error('not used'); },
});

test('P2: every seed starts while search is pending, precedes search and is fetched once', async () => {
  let finishSearch: (value: SearchHit[]) => void = () => {};
  const pending = new Promise<SearchHit[]>(resolve => { finishSearch = resolve; });
  const registry: SearchProviderRegistry = { enabled: () => [{ name: 'search', search: () => pending }], fallback: () => [] };
  const calls: string[] = [];
  const seeds = ['https://seed.example.com/one', 'https://seed.example.com/two'];
  const collecting = createSourcePrefetcher(httpFor(calls, longBody), registry).collect('topic', '话题', seeds[0], true, undefined, [...seeds, seeds[0]]);
  await new Promise(resolve => setImmediate(resolve));
  const beforeSearch = calls.slice();
  finishSearch([{ title: 'same', url: seeds[0], source: 'search', snippet: '' }, { title: 'new', url: 'https://result.example.com/article', source: 'search', snippet: '' }]);
  const sources = await collecting;
  assert.deepEqual(beforeSearch, seeds, 'seed requests do not wait for providers');
  assert.deepEqual(sources.slice(0, 2).map(source => source.url), seeds);
  assert.equal(calls.filter(url => url === seeds[0]).length, 1);
});

test('P2: changing the complete seed list invalidates prefetch cache', async () => {
  const calls: string[] = [];
  const registry: SearchProviderRegistry = { enabled: () => [], fallback: () => [] };
  const prefetcher = createSourcePrefetcher(httpFor(calls), registry);
  await prefetcher.collect('t', '话题', null, false, undefined, ['https://seed.example.com/one']);
  const sources = await prefetcher.collect('t', '话题', null, false, undefined, ['https://seed.example.com/one', 'https://seed.example.com/two']);
  assert.equal(sources.length, 2);
  assert.ok(calls.includes('https://seed.example.com/two'));
});

test('P2: one collect uses a search snapshot and respects its Jina reader switch', async () => {
  const calls: string[] = [];
  let enabled = false;
  let reads = 0;
  const registry: SearchProviderRegistry = {
    enabled: () => [], fallback: () => [],
    snapshot: async () => { reads++; return { providers: [], readerEnabled: enabled, cacheKey: String(enabled) }; },
  };
  const http: HttpClient = { fetch: async request => {
    calls.push(request.url);
    return { status: 200, headers: {}, body: request.url.startsWith('https://r.jina.ai/') ? 'Markdown Content:\n' + body : '' };
  }, fetchStream: async () => { throw new Error('not used'); } };
  const prefetcher = createSourcePrefetcher(http, registry);
  await prefetcher.collect('t', '话题', 'https://seed.example.com/one', false);
  assert.ok(calls.every(url => !url.startsWith('https://r.jina.ai/')));
  enabled = true;
  const sources = await prefetcher.collect('t', '话题', 'https://seed.example.com/one', false);
  assert.equal(sources.length, 1);
  assert.equal(reads, 2);
  assert.ok(calls.some(url => url.startsWith('https://r.jina.ai/')));
});

interface RegistrySearchResult { items: { title: string; url: string; text: string }[]; }
interface RegistryCallContext { signal?: AbortSignalLike; }
const registryHarness = (search?: (service: string, query: string, context?: RegistryCallContext) => Promise<RegistrySearchResult>) => {
  let settings = {
    searchCommonOptions: { resultSize: 6 }, searchServiceSelected: 0,
    searchServices: [{ type: 'tavily', id: 'configured-one', apiKey: 'test-key' }], searchEnabledServiceIds: ['configured-one'],
    searchBuiltinDuckDuckGoEnabled: true, searchBuiltinBingEnabled: true, searchBuiltinWikipediaEnabled: true,
    searchBuiltinHackerNewsEnabled: true, searchBuiltinJinaEnabled: true,
  };
  const calls: { service: string; query: string; signal?: AbortSignalLike }[] = [];
  let reads = 0;
  const chat = {
    FREE_ENGINE_NAMES: { duckduckgo: 'DDG', brave: 'Brave', bing: 'Bing', quark: 'Quark', so360: '360', wikipedia: 'Wiki', hackernews: 'HN' },
    SEARCH_SERVICE_TYPES: { tavily: 'Tavily' },
    enabledServices: (value: typeof settings) => value.searchServices.filter(service => value.searchEnabledServiceIds.includes(service.id)),
    freeEngineCoolingDown: () => false, looksTechnicalQuery: () => true,
    getSearchService: (options: { id: string }) => ({ search: async (input: { query: string }, _common: object, _options: object, context?: RegistryCallContext) => {
      calls.push({ service: options.id, query: input.query, signal: context?.signal }); return search ? search(options.id, input.query, context) : { items: [] };
    } }),
    runFreeEngine: async (id: string, query: string, _common: object, context?: RegistryCallContext) => {
      calls.push({ service: id, query, signal: context?.signal }); return search ? search(id, query, context) : { items: [] };
    },
  };
  const helperExports: Record<string, unknown> = {};
  const helperSource = fs.readFileSync(new URL('../../../chat/src/main/ets/search/deepread_search_registry.ts', import.meta.url), 'utf8');
  vm.runInNewContext(ts.transpileModule(helperSource, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText, {
    exports: helperExports, require: (name: string) => {
      if (['./search_aggregator.ts', './service_registry.ts', './search_service.ts'].includes(name)) return chat;
      throw new Error('unexpected helper import ' + name);
    }, setTimeout, clearTimeout, Promise, Date, JSON, Error,
  });
  const exports: Record<string, unknown> = {};
  const code = ts.transpileModule(fs.readFileSync(new URL('../../../entry/src/main/ets/platform_impl/SearchRegistry.ets', import.meta.url), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  vm.runInNewContext(code, { exports, require: (name: string) => {
    if (name === '@amber/chat-domain') return { ...chat, ...helperExports };
    if (name === '@kit.PerformanceAnalysisKit') return { hilog: { warn: () => {}, info: () => {} } };
    if (name === '@amber/deepread-domain') return { createTavilyProvider: () => { throw new Error('legacy independent key activated'); } };
    if (name === './NewsNowProvider.ets') return { createNewsNowProvider: () => { throw new Error('NewsNow research requested'); } };
    throw new Error('unexpected import ' + name);
  }, setTimeout, clearTimeout, Promise, Date, JSON, Error });
  const create = exports.createTavilyAndFallbackRegistry as (http: HttpClient, storage: object, load: () => Promise<object>, ensure: () => void) => SearchProviderRegistry;
  const registry = create(httpFor([]), { get: async () => 'legacy-key' }, async () => { reads++; return settings; }, () => {});
  return { registry, calls, reads: () => reads, set: (value: typeof settings) => { settings = value; }, settings: () => settings };
};

test('P2: registry first collect uses one preferred configured source, all query angles and no NewsNow or legacy keys', async () => {
  const h = registryHarness();
  const queries = buildDeepReadQueries('量子计算', 'https://seed.example.com');
  assert.ok(queries.every(query => !query.startsWith('https://')));
  assert.ok(queries.some(query => query.includes('官方')));
  assert.ok(queries.some(query => query.includes('图片')));
  const snapshot = await h.registry.snapshot!();
  await Promise.all(snapshot.providers.map(provider => provider.search(queries)));
  assert.equal(h.reads(), 1);
  assert.equal(h.calls.filter(call => call.service === 'configured-one').length, queries.length);
  assert.equal(h.calls.filter(call => call.service !== 'configured-one').length, 0, 'a configured selection does not also issue free searches');
});

test('P2: registry captures one immutable run config; next collect observes disabled sources', async () => {
  const h = registryHarness();
  const first = await h.registry.snapshot!();
  h.set({ ...h.settings(), searchEnabledServiceIds: [], searchBuiltinDuckDuckGoEnabled: false, searchBuiltinBingEnabled: false,
    searchBuiltinWikipediaEnabled: false, searchBuiltinHackerNewsEnabled: false, searchBuiltinJinaEnabled: false });
  await Promise.all(first.providers.map(provider => provider.search(['话题'])));
  assert.ok(h.calls.some(call => call.service === 'configured-one'));
  h.calls.length = 0;
  const second = await h.registry.snapshot!();
  await Promise.all(second.providers.map(provider => provider.search(['话题'])));
  assert.equal(h.calls.length, 0);
  assert.equal(second.readerEnabled, false);
  assert.notEqual(second.cacheKey, first.cacheKey);
});

for (const profile of ['configured', 'free']) {
  test(`P2: ${profile} registry forwards the owner signal to its actual SDK searches`, async () => {
    const controller = new AbortController();
    const h = registryHarness(async (_service, _query, context) => new Promise<RegistrySearchResult>((_resolve, reject) => {
      context?.signal?.addEventListener?.('abort', () => { const error = new Error('aborted'); error.name = 'AbortError'; reject(error); });
    }));
    if (profile === 'free') h.set({ ...h.settings(), searchEnabledServiceIds: [] });
    const collecting = createSourcePrefetcher(httpFor([]), h.registry).collect('cancel-search', '量子计算', null, true, controller.signal);
    await new Promise(resolve => setImmediate(resolve));
    const selected = h.calls.find(call => call.service === (profile === 'configured' ? 'configured-one' : 'duckduckgo'));
    assert.equal(selected?.signal, controller.signal);
    if (profile === 'configured') assert.equal(h.calls.some(call => call.service === 'duckduckgo'), false);
    else assert.equal(h.calls.some(call => call.service === 'configured-one'), false);
    controller.abort();
    assert.deepEqual(await collecting, []);
  });
}

test('P2: stage prompt only asks for tools actually exposed by the writer', () => {
  const pack = buildEvidencePack([]);
  const plan = fallbackPlan('话题', pack);
  const writer = createSectionWriterTools({ topicId: 't', topicTitle: '话题', imageCandidates: [] });
  const tools = writer.tools(new Set(['OVERVIEW'])).map(tool => tool.name);
  const prompt = buildPrompt({ topicTitle: '话题', stage: 'OVERVIEW', existingOutput: makeEmptyDeepReadOutput(),
    seedUrl: 'https://seed.example.com', scrapeWebAvailable: true, evidencePack: pack, articlePlan: plan,
    stageEvidence: cardsFor(pack, 'OVERVIEW', plan), stageTimeoutMs: 90_000, playbookMarkdown: '', todayIso: '2026-09-30' });
  assert.ok(!tools.includes('search_web') && !tools.includes('scrape_web'));
  assert.ok(!prompt.includes('search_web') && !prompt.includes('scrape_web'));
  assert.ok(prompt.includes('不确定'));
});

test('P2: saved multi-source input is reused by a forced run without route arguments', async () => {
  const seeds = ['https://seed.example.com/one', 'https://seed.example.com/two'];
  const seenSeeds: string[][] = [];
  let saved: DeepReadOutput | null = null;
  const getSaved = (): DeepReadOutput | null => saved;
  const deps: RunManagerDeps = {
    model: 'test', playbookMarkdown: '', nowIso: () => '2026-09-30',
    aiClient: { generateText: async () => [] },
    prefetcher: { cacheSize: () => 0, collect: async (_id, title, _primary, _force, _signal, urls) => {
      seenSeeds.push(urls ?? []);
      return (urls ?? []).map((url, index) => ({ sourceId: 'seed-' + index, url, title, source: 'seed',
        evidenceText: '可信研究资料描述了话题的具体背景、最新进展与可核查事实。', credibility: 'medium', freshness: 'unknown', publishedAt: null, imageCandidates: [] }));
    } },
    repository: { get: () => saved, save: async (_id, _title, output) => { saved = output; }, clear: () => { saved = null; } },
    collectRun: async (messages, _label, _signal, tools) => {
      const writer = tools?.find(tool => Object.values(WRITER_TOOL_NAMES).includes(tool.name));
      assert.ok(writer);
      const payloads = {
        [WRITER_TOOL_NAMES.OVERVIEW]: { summary: '这份概览基于真实资料解释了话题最新进展及其值得关注的背景，内容足够完整。' },
        [WRITER_TOOL_NAMES.NARRATIVE]: { timeline: [{ date: '2026-09', event: '来源报道了相关研究进展，并提供关键背景与后续观察事项。' }] },
        [WRITER_TOOL_NAMES.ANALYSIS]: { core_dispute: '争议围绕实际应用价值与相关证据的可信程度展开。' },
        [WRITER_TOOL_NAMES.EXTENDED_READING]: { links: [{ title: '来源资料', url: seeds[0] }] },
      };
      await writer.execute(JSON.stringify(payloads[writer.name]));
      return [...messages, makeAssistantMessage('已写入')];
    },
  };
  const scheduler = createScheduler({ runManager: deps, createAbortController: () => new AbortController(),
    observeOutput: () => ({ subscribe: () => () => {}, getCurrent: () => saved ?? undefined }), isInterruptedPhase: () => false });
  assert.equal((await scheduler.run('t', '话题', { seedUrl: seeds[0], seedUrls: [...seeds, seeds[0]] })).ok, true);
  assert.deepEqual(getSaved()?.inputSourceUrls, seeds);
  assert.equal((await scheduler.run('t', '话题', { force: true })).ok, true);
  assert.deepEqual(seenSeeds, [seeds, seeds]);
  assert.deepEqual(getSaved()?.inputSourceUrls, seeds);
});

test('P2: collect feeds real metadata, lazy and srcset candidates through scoring', async () => {
  const calls: string[] = [];
  const html = longBody.replace('<body>', '<head><meta property="og:image" content="/hero.jpg"><meta property="og:image:width" content="1200"><meta property="og:image:height" content="630"><meta property="og:image:alt" content="量子计算研究现场图片"><meta name="twitter:image" content="/hero.jpg"></head><body>')
    + '<img src="/placeholder.gif" data-src="images/lazy.jpg" alt="真实现场插图"><img width="1200" height="630" srcset="/small.jpg 320w, /large.jpg 1280w" alt="量子计算研究现场图片">';
  const registry: SearchProviderRegistry = { enabled: () => [], fallback: () => [] };
  const sources = await createSourcePrefetcher(httpFor(calls, html), registry).collect('t', '量子计算', 'https://seed.example.com/news/article', true);
  const candidates = sources[0].imageCandidates;
  assert.equal(candidates.filter(candidate => candidate.url.endsWith('/hero.jpg')).length, 1);
  assert.equal(candidates.find(candidate => candidate.url.endsWith('/hero.jpg'))?.confidence, 'hero');
  assert.equal(candidates.find(candidate => candidate.url.endsWith('/news/images/lazy.jpg'))?.confidence, 'inline');
  assert.equal(candidates.find(candidate => candidate.url.endsWith('/large.jpg'))?.confidence, 'hero');
});

test('P2: a hanging provider leaves time to fetch a fast provider result', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 1_000 });
  const calls: string[] = [];
  const url = 'https://fast.example.com/article';
  const registry: SearchProviderRegistry = { enabled: () => [
    { name: 'slow', search: async () => new Promise<SearchHit[]>(() => {}) },
    { name: 'fast', search: async () => [{ title: 'Fast', url, source: 'fast', snippet: '' }] },
  ], fallback: () => [] };
  const collecting = createSourcePrefetcher(httpFor(calls, longBody), registry).collect('t', '话题', null, true);
  await new Promise(resolve => setImmediate(resolve));
  t.mock.timers.tick(10_000);
  await new Promise(resolve => setImmediate(resolve));
  t.mock.timers.tick(5_000);
  const sources = await collecting;
  assert.equal(sources.length, 1, 'the fast hit is fetched before the 15s wall deadline');
  assert.equal(sources[0].url, url);
});

for (const provider of ['configured', 'free']) {
  test(`P2: actual ${provider} registry preserves its first hit when the next query hangs`, async t => {
    t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 1_000 });
    const url = 'https://fast.example.com/first-hit';
    const h = registryHarness(async (_service, query) => query === '量子计算'
      ? { items: [{ title: '成功来源', url, text: '' }] }
      : new Promise<RegistrySearchResult>(() => {}));
    h.set({ ...h.settings(), searchEnabledServiceIds: provider === 'configured' ? ['configured-one'] : [],
      searchBuiltinDuckDuckGoEnabled: provider === 'free', searchBuiltinBingEnabled: false,
      searchBuiltinWikipediaEnabled: false, searchBuiltinHackerNewsEnabled: false, searchBuiltinJinaEnabled: false });
    const calls: string[] = [];
    const collecting = createSourcePrefetcher(httpFor(calls, longBody), h.registry).collect('t', '量子计算', null, true);
    await new Promise(resolve => setImmediate(resolve));
    t.mock.timers.tick(9_000);
    await new Promise(resolve => setImmediate(resolve));
    t.mock.timers.tick(1_000);
    await new Promise(resolve => setImmediate(resolve));
    t.mock.timers.tick(5_000);
    const sources = await collecting;
    assert.ok(h.calls.some(call => call.query === '量子计算 最新进展'));
    assert.deepEqual(sources.map(source => source.url), [url]);
    assert.ok(calls.includes(url), 'the successful first hit reaches actual page scraping');
  });
}

test('P2: a hanging seed does not delay scraping a fast search result', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 1_000 });
  const seed = 'https://slow.example.com/seed';
  const url = 'https://fast.example.com/article';
  const calls: string[] = [];
  const registry: SearchProviderRegistry = { enabled: () => [
    { name: 'fast', search: async () => [{ title: 'Fast', url, source: 'fast', snippet: '' }] },
  ], fallback: () => [] };
  const http: HttpClient = { fetch: async request => {
    calls.push(request.url);
    if (request.url === seed) return new Promise(() => {});
    return { status: 200, headers: {}, body: longBody };
  }, fetchStream: async () => { throw new Error('not used'); } };
  const collecting = createSourcePrefetcher(http, registry).collect('t', '话题', seed, true);
  await new Promise(resolve => setImmediate(resolve));
  const scrapedBeforeDeadline = calls.includes(url);
  t.mock.timers.tick(15_000);
  const sources = await collecting;
  assert.equal(scrapedBeforeDeadline, true, 'search pages start before awaiting a slow seed');
  assert.deepEqual(sources.map(source => source.url), [url]);
});

test('P2: first source failure saves an honest retryable record before the active job ends', async () => {
  const seeds = ['https://seed.example.com/one', 'https://seed.example.com/two'];
  let saved: DeepReadOutput | null = null;
  const getSaved = (): DeepReadOutput | null => saved;
  let saveStarted = false;
  let releaseSave: () => void = () => {};
  const saving = new Promise<void>(resolve => { releaseSave = resolve; });
  let planningCalls = 0;
  const seenSeeds: string[][] = [];
  const deps: RunManagerDeps = {
    model: 'test', playbookMarkdown: '', nowIso: () => '2026-09-30',
    aiClient: { generateText: async () => { planningCalls++; return []; } },
    prefetcher: { cacheSize: () => 0, collect: async (_id, _title, _primary, _force, _signal, urls) => {
      seenSeeds.push(urls ?? []); return [];
    } }, collectRun: async messages => messages,
    repository: { get: getSaved, clear: () => { saved = null; }, save: async (_id, _title, output) => {
      saveStarted = true; await saving; saved = output;
    } },
  };
  const scheduler = createScheduler({ runManager: deps, createAbortController: () => new AbortController(),
    observeOutput: () => ({ subscribe: () => () => {}, getCurrent: () => getSaved() ?? undefined }), isInterruptedPhase: () => false });
  const running = scheduler.run('first-failed', '资料不可用的话题', { seedUrl: seeds[0], seedUrls: [...seeds, seeds[0]] });
  await new Promise(resolve => setImmediate(resolve));
  const awaitingSave = saveStarted;
  const activeWhileSaving = scheduler.getActiveRuns!().length;
  releaseSave();
  const result = await running;
  assert.equal(awaitingSave, true);
  assert.equal(activeWhileSaving, 1);
  assert.equal(planningCalls, 0);
  assert.equal(result.ok, false);
  const row = getSaved();
  assert.ok(row);
  assert.equal(row.generationPhase, 'IDLE');
  assert.equal(row.generationComplete, false);
  assert.equal(row.sectionStates.OVERVIEW.status, 'FAILED');
  assert.equal(row.sectionStates.OVERVIEW.errorMessage, result.error);
  assert.equal(row.summary, '');
  assert.equal(row.references.length, 0);
  assert.equal(row.extendedReading.length, 0);
  assert.deepEqual(row.inputSourceUrls, seeds);
  assert.deepEqual(scheduler.getActiveRuns!(), []);
  assert.equal((await scheduler.run('first-failed', '资料不可用的话题')).ok, false);
  assert.deepEqual(seenSeeds, [seeds, seeds], 'history retries retain all original sources');
});

test('P2: first failure save rejection reaches the caller and clears the active job', async () => {
  const failure = new Error('failure row write rejected');
  const deps: RunManagerDeps = {
    model: 'test', playbookMarkdown: '', nowIso: () => '2026-09-30', aiClient: { generateText: async () => [] },
    prefetcher: { cacheSize: () => 0, collect: async () => [] }, collectRun: async messages => messages,
    repository: { get: () => null, clear: () => {}, save: async () => { throw failure; } },
  };
  const scheduler = createScheduler({ runManager: deps, createAbortController: () => new AbortController(),
    observeOutput: () => ({ subscribe: () => () => {}, getCurrent: () => undefined }), isInterruptedPhase: () => false });
  await assert.rejects(scheduler.run('failed-save', '话题'), error => error === failure);
  assert.deepEqual(scheduler.getActiveRuns!(), []);
});

test('P2: source failure preserves readable old content even without READY states', async () => {
  for (const force of [false, true]) {
    const old: DeepReadOutput = { ...makeEmptyDeepReadOutput(),
      summary: '已有文章包含研究背景和明确结论，仍可继续阅读。'.repeat(5),
      timeline: [{ date: '一', event: '第一个关键节点有具体事实与足够完整的事件描述。', isHighlight: false, imageUrl: null, imageCaption: null },
        { date: '二', event: '第二个关键节点有具体事实与足够完整的事件描述。', isHighlight: false, imageUrl: null, imageCaption: null }],
      corePoints: [
        { point: '第一个可读核心观点有明确依据', supporting: '已有来源支持此观点并提供充分可核查的细节说明。'.repeat(2), imageUrl: null, imageCaption: null },
        { point: '第二个可读核心观点有明确依据', supporting: '已有来源支持此观点并提供充分可核查的细节说明。'.repeat(2), imageUrl: null, imageCaption: null },
      ],
      analysis: { coreDispute: '已有分析给出充分的事实脉络，讨论相关争议以及尚待验证的结论。'.repeat(3),
        perspectives: [], implications: null, quotes: [] },
      sectionStates: { OVERVIEW: { status: 'FAILED', errorMessage: '旧中断' } },
    };
    let saves = 0;
    const deps: RunManagerDeps = {
      model: 'test', playbookMarkdown: '', nowIso: () => '2026-09-30', aiClient: { generateText: async () => [] },
      prefetcher: { cacheSize: () => 0, collect: async () => [] }, collectRun: async messages => messages,
      repository: { get: () => old, clear: () => {}, save: async () => { saves++; } },
    };
    const result = await run(deps, 'old-readable', '话题', { force });
    assert.equal(result.ok, false);
    assert.equal(result.output, old);
    assert.equal(saves, 0);
  }
});

test('P2: cancellation during first collection does not manufacture a failed history row', async () => {
  let releasePrefetch: () => void = () => {};
  const pending = new Promise<void>(resolve => { releasePrefetch = resolve; });
  let saves = 0;
  const deps: RunManagerDeps = {
    model: 'test', playbookMarkdown: '', nowIso: () => '2026-09-30', aiClient: { generateText: async () => [] },
    prefetcher: { cacheSize: () => 0, collect: async () => { await pending; return []; } }, collectRun: async messages => messages,
    repository: { get: () => null, clear: () => {}, save: async () => { saves++; } },
  };
  const scheduler = createScheduler({ runManager: deps, createAbortController: () => new AbortController(),
    observeOutput: () => ({ subscribe: () => () => {}, getCurrent: () => undefined }), isInterruptedPhase: () => false });
  const running = scheduler.run('cancelled-first', '话题');
  await new Promise(resolve => setImmediate(resolve));
  scheduler.abort('cancelled-first');
  releasePrefetch();
  assert.equal((await running).error, 'aborted');
  assert.equal(saves, 0);
  assert.deepEqual(scheduler.getActiveRuns!(), []);
});

test('P2: cancellation while an admitted failure save is pending still returns aborted', async () => {
  let releaseSave: () => void = () => {};
  const saving = new Promise<void>(resolve => { releaseSave = resolve; });
  let saved: DeepReadOutput | null = null;
  let saveStarted = false;
  const deps: RunManagerDeps = {
    model: 'test', playbookMarkdown: '', nowIso: () => '2026-09-30', aiClient: { generateText: async () => [] },
    prefetcher: { cacheSize: () => 0, collect: async () => [] }, collectRun: async messages => messages,
    repository: { get: () => saved, clear: () => {}, save: async (_id, _title, output) => {
      saveStarted = true; await saving; saved = output;
    } },
  };
  const scheduler = createScheduler({ runManager: deps, createAbortController: () => new AbortController(),
    observeOutput: () => ({ subscribe: () => () => {}, getCurrent: () => undefined }), isInterruptedPhase: () => false });
  const running = scheduler.run('cancelled-save', '话题');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(saveStarted, true);
  scheduler.abort('cancelled-save');
  releaseSave();
  assert.equal((await running).error, 'aborted');
  assert.deepEqual(scheduler.getActiveRuns!(), []);
});

test('P2: active job is discoverable before any draft save and vanishes at terminal', async () => {
  let releasePrefetch: () => void = () => {};
  const pending = new Promise<void>(resolve => { releasePrefetch = resolve; });
  const deps: RunManagerDeps = {
    model: 'test', playbookMarkdown: '', nowIso: () => '2026-09-30', aiClient: { generateText: async () => [] },
    prefetcher: { cacheSize: () => 0, collect: async () => { await pending; return []; } }, collectRun: async messages => messages,
  };
  const scheduler = createScheduler({ runManager: deps, createAbortController: () => new AbortController(),
    observeOutput: () => ({ subscribe: () => () => {}, getCurrent: () => undefined }), isInterruptedPhase: () => false });
  const startedAt = Date.now();
  const running = scheduler.run('first-unsaved', '尚未存稿的研究');
  await new Promise(resolve => setImmediate(resolve));
  const active = scheduler.getActiveRuns!();
  assert.equal(active.length, 1);
  assert.equal(active[0].topicId, 'first-unsaved');
  assert.equal(active[0].title, '尚未存稿的研究');
  assert.ok(active[0].startedAt >= startedAt);
  releasePrefetch();
  assert.equal((await running).ok, false);
  assert.deepEqual(scheduler.getActiveRuns!(), []);
});
