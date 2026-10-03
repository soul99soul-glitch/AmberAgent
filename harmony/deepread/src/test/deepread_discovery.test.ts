import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { DeepReadOutput } from '../main/ets/domain/models.ts';
import { DEEPREAD_DISCOVERY_SOURCES, DEEPREAD_DISCOVERY_CATEGORIES,
    parseHackerNewsHotItem, parseHuggingFaceHotItems, parseGithubTrendingHotItems } from '../main/ets/domain/discovery.ts';
import { discoveryHotspotInputs, discoveryArticleParams } from '../main/ets/domain/discovery_input.ts';
import { generationSources } from '../main/ets/domain/input_sources.ts';

test('grouped catalog includes all nine discovery additions without losing existing source identities', () => {
    const ids = DEEPREAD_DISCOVERY_SOURCES.map(source => source.id);
    for (const id of ['hacker_news', 'huggingface_papers', 'github_trending_ai', 'ithome', 'sspai', 'juejin', 'coolapk', 'xueqiu-hotstock', 'hupu-zhugandaoretie', 'arxiv_ai', 'infoq_ai', 'github-trending-today']) assert.ok(ids.includes(id));
    assert.equal(new Set(ids).size, ids.length);
    assert.equal(ids.length, 20);
    assert.deepEqual(DEEPREAD_DISCOVERY_CATEGORIES, ['AI · 英文源', '社交热搜', '科技数码', '财经', '体育']);
});

test('real HN HF GitHub response shapes preserve absent URLs and summaries while rejecting deleted or malformed entries', () => {
    const noUrl = parseHackerNewsHotItem(JSON.stringify({ title: 'Ask HN: testing', score: 4 }), 3)!;
    assert.equal(noUrl.url, ''); assert.equal(noUrl.rank, 3);
    assert.equal(parseHackerNewsHotItem('{"title":"deleted","deleted":true}', 1), null);
    assert.equal(parseHackerNewsHotItem('{"title":"bad url","url":"javascript:alert(1)"}', 2)!.url, '');
    const papers = parseHuggingFaceHotItems(JSON.stringify([{ paper: { title: 'Research', id: '2610.00001', summary: 'Summary' }, upvotes: 2 }, { paper: { title: 'No ID' } }, { paper: {} }]));
    assert.equal(papers.length, 2); assert.equal(papers[0].summary, 'Summary'); assert.equal(papers[1].url, '');
    assert.throws(() => parseHuggingFaceHotItems('{}'), /格式/);
    const github = parseGithubTrendingHotItems('<article><h2><a class="x" href="/org/repo">org/repo</a></h2><p>A &amp; B</p></article><article><h2><a href="/login">Login</a></h2></article>');
    assert.deepEqual(github, [{ rank: 1, title: 'org/repo', url: 'https://github.com/org/repo', heat: '', summary: 'A & B' }]);
});

test('no URL hotspot persists readable title source rank summary and enters real generation evidence without a fake URL', () => {
    const inputs = discoveryHotspotInputs([{ title: 'Ask HN: careful evaluation', source: 'Hacker News', rank: 2, url: null, summary: '讨论评估的方法' }]);
    const params = discoveryArticleParams('评估方法', inputs, []);
    assert.match(params.topicId, /^discovery-/);
    assert.equal(params.sourceUrl, undefined);
    const saved = JSON.parse(params.inputSourcesJson);
    assert.equal(saved[0].url, null); assert.equal(saved[0].status, 'ready');
    assert.match(saved[0].content, /Hacker News/); assert.match(saved[0].content, /排名：2/);
    assert.match(saved[0].content, /讨论评估的方法/);
    const evidence = generationSources(saved)[0];
    assert.equal(evidence.source, 'Hacker News'); assert.equal(evidence.url, '');
    assert.match(evidence.evidenceText, /Ask HN/);
    const nextRank = discoveryHotspotInputs([{ title: 'Ask HN: careful evaluation', source: 'Hacker News', rank: 1, url: null }]);
    assert.equal(discoveryArticleParams('评估方法', nextRank, []).topicId, params.topicId, 'rank refresh does not create a new topic identity');
});

test('mixed hotspot topic keeps all real links and no URL sources, excludes unsafe URLs and keeps original link identity', () => {
    const inputs = discoveryHotspotInputs([{ title: '标题一', source: 'IT之家', rank: 1, url: 'https://news.test/a' },
        { title: '标题二', source: '少数派', rank: 2, url: '' }]);
    const params = discoveryArticleParams('同一话题', inputs, ['https://news.test/a', 'https://news.test/a', 'javascript:bad()'], true);
    assert.equal(params.topicId, 'https://news.test/a');
    assert.deepEqual(JSON.parse(params.seedUrlsJson), ['https://news.test/a']);
    assert.equal(JSON.parse(params.inputSourcesJson).length, 2);
    assert.equal(params.force, 'true');
});

test('actual discovery context fetches a linked page despite ready rank metadata and keeps body plus rank in one source', async () => {
    const { createRunContext } = await import('../main/ets/agent/run_manager.ts');
    const { makeEmptyDeepReadOutput } = await import('../main/ets/domain/models.ts');
    const inputs = discoveryHotspotInputs([{ title: '真实话题', source: 'IT 之家', rank: 3, url: 'https://news.test/story', summary: '榜单摘要' }]);
    let saved: DeepReadOutput = { ...makeEmptyDeepReadOutput(), inputSources: inputs, inputText: inputs[0].content, inputSourceUrls: ['https://news.test/story'] };
    let seeds: string[] = [];
    const result = await createRunContext({ repository: { get: () => saved, save: async (_id, _title, output) => { saved = output; }, clear() {} },
        prefetcher: { cacheSize: () => 0, async collect(_id, _title, _url, _force, _signal, seedUrls) {
            seeds = seedUrls ?? [];
            return [{ sourceId: 'page', title: '真实话题', url: 'https://news.test/story', source: 'seed', evidenceText: '真正抓取到的全文材料。'.repeat(100),
                credibility: 'medium', freshness: 'recent', publishedAt: null, imageCandidates: [] }];
        } }, collectRun: async messages => messages, aiClient: { generateText: async () => [] }, model: 'm', playbookMarkdown: '', nowIso: () => '2026-10-03',
    }, 'discovery-linked', '真实话题', 'https://news.test/story', false);
    assert.deepEqual(seeds, ['https://news.test/story']);
    assert.equal(result.ok, true);
    if (!result.ok) throw new Error('context missing');
    assert.equal(result.context.evidencePack.allSources.length, 1);
    assert.match(result.context.evidencePack.allSources[0].evidenceText, /真正抓取到的全文材料/);
    assert.match(result.context.evidencePack.allSources[0].evidenceText, /排名：3/);
    assert.equal(saved.inputSources!.length, 1);
    assert.equal(saved.inputSources![0].kind, 'web');
});

test('actual no-link discovery run reads its persisted inputs with empty search results and sends readable evidence to planning', async () => {
    const { createRunContext } = await import('../main/ets/agent/run_manager.ts');
    const { makeEmptyDeepReadOutput } = await import('../main/ets/domain/models.ts');
    const inputs = discoveryHotspotInputs([{ title: 'Ask HN: evaluation', source: 'Hacker News', rank: 4, url: null }]);
    let disk = JSON.stringify({ ...makeEmptyDeepReadOutput(), inputSources: inputs, inputText: inputs[0].content, inputSourceUrls: [] });
    let requestedPlan = '';
    const result = await createRunContext({ repository: { get: () => JSON.parse(disk), save: async (_id, _title, output) => { disk = JSON.stringify(output); }, clear() {} },
        prefetcher: { cacheSize: () => 0, async collect(_id, _title, seed) { assert.equal(seed, null); return []; } },
        collectRun: async messages => messages, aiClient: { async generateText(request) { requestedPlan = JSON.stringify(request); return []; } },
        model: 'm', playbookMarkdown: '', nowIso: () => '2026-10-03',
    }, discoveryArticleParams('评估', inputs, []).topicId, '评估', null, false);
    assert.equal(result.ok, true);
    if (!result.ok) throw new Error('no URL hotspot missing');
    assert.match(requestedPlan, /Hacker News/); assert.match(requestedPlan, /排名/);
    assert.equal(result.context.evidencePack.allSources[0].url, '');
    const restored = JSON.parse(disk);
    assert.equal(restored.inputSources[0].url, null);
    assert.equal(restored.inputSources[0].researchSource.source, 'Hacker News');
});
