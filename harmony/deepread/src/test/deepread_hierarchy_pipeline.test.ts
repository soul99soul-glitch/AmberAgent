import { test } from 'node:test';
import assert from 'node:assert/strict';
import { run, runSection, createRunContext } from '../main/ets/agent/run_manager.ts';
import type { RunManagerDeps } from '../main/ets/agent/run_manager.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';
import type { DeepReadOutput } from '../main/ets/domain/models.ts';
import { createSectionWriterTools } from '../main/ets/agent/section_writer_tools.ts';
import { writeStructuredStage } from '../main/ets/agent/structured_stage.ts';
import { isComplete, hasDisplayableDeepReadOutput, normalizedDeepReadUncertainties } from '../main/ets/domain/helpers.ts';
import type { GenerateTextParams } from '../main/ets/platform/ai_client.ts';
const overview = { topic_type: 'product', bottom_line: '产品发布带来新的核查需求。', summary: '实际来源记载产品发布、使用方式和用户反馈，需要将公开事实与尚未得到验证的效果区分开来。' };
const narrative = { timeline: [{ date: '10月初', event: '产品公布后开始接受公开反馈，消费者提出进一步核查的要求。', is_highlight: true, why: '用户反馈改变了评估重点。' }], core_points: [{ point: '实际使用效果仍有待核查。', supporting: '来源记录了不同用户的公开反馈，不能将发布时的主张直接视为效果已得到验证。', sources: [2, 1, 2, 0, -1, 1.5, 99, '2'] }] };
const analysis = { analysis: { core_dispute: '产品主张是否已经在真实使用场景得到足够支持？', perspectives: [{ holder: '消费者', interest: '可靠的实际效果', viewpoint: '消费者需要更多核查来判断产品是否适合自己的使用方式。', quote: '请先核查。', quote_by: '用户，使用者', sources: [2] }] }, impacts: [{ target: '用户', horizon: 'short', effect: '需要重新评估产品选择。' }, { target: '坏记录', horizon: 'invalid', effect: '丢弃' }], watch: ['关注公开评测是否互相印证。'], uncertainties: ['旧稿未确认事项', { claim: '实际效果尚未确认。', status: 'single_source' }, { bad: true }] };
const promptOf = (request: GenerateTextParams): string => request.messages[0].parts.map(part => part.type === 'text' ? part.text : '').join('');
const fixture = () => {
  let saved: DeepReadOutput | null = null; const requests: GenerateTextParams[] = [];
  const aiClient = { generateText: async (request: GenerateTextParams) => {
    requests.push(request); const prompt = promptOf(request);
    const body = prompt.includes('目标段落：概览') ? overview : prompt.includes('目标段落：时间轴叙事') ? narrative : prompt.includes('目标段落：深度分析') ? analysis : { overview_angle: '实际来源分析' };
    return request.messages.concat(makeAssistantMessage(JSON.stringify(body)));
  } };
  const deps: RunManagerDeps = { model: 'text-only', writerMode: 'structured', playbookMarkdown: '', nowIso: () => '2026-10-05', aiClient,
    prefetcher: { collect: async () => [{ sourceId: 'web', url: 'https://source.example/report', title: '公开来源', source: '网站', evidenceText: '公开来源正文。'.repeat(40), credibility: 'high', freshness: 'unknown', publishedAt: null, imageCandidates: [] }], cacheSize: () => 0 },
    collectRun: (messages, _label, signal, tools) => aiClient.generateText({ model: 'text-only', messages, signal, tools: tools?.map(tool => ({ ...tool, schema: tool.schema ?? {} })) }),
    repository: { get: () => saved, save: (_id, _title, output) => { saved = structuredClone(output); }, clear: () => { saved = null; } },
  };
  const manual = { id: 'manual', kind: 'text' as const, title: '用户原文', url: null, content: '产品发布后需要核查实际效果。'.repeat(40), status: 'ready' as const, error: null, truncated: false, note: null };
  saved = { ...makeEmptyDeepReadOutput(), inputText: manual.content, inputUrlsText: '', inputSources: [manual] };
  return { deps, requests, saved: () => saved!, set: (output: DeepReadOutput) => { saved = output; } };
};
test('magazine generation uses plan plus three JSON calls and locally persists numbered sources', async () => {
  const f = fixture(); const result = await run(f.deps, 'article', '产品');
  assert.equal(isComplete(result.output), true); assert.equal(f.requests.length, 4);
  assert.equal(result.output.bottomLine, overview.bottom_line); assert.equal(result.output.timeline?.[0].why, narrative.timeline[0].why);
  assert.deepEqual(result.output.corePoints?.[0].sources, [2, 1]);
  assert.equal(result.output.analysis.perspectives[0].interest, '可靠的实际效果');
  assert.equal(result.output.analysis.perspectives[0].quoteBy, '用户，使用者');
  assert.deepEqual(result.output.impacts, [{ target: '用户', horizon: 'short', effect: '需要重新评估产品选择。' }]);
  assert.deepEqual(result.output.watch, analysis.watch);
  assert.deepEqual(result.output.uncertainties, ['旧稿未确认事项', { claim: '实际效果尚未确认。', status: 'single_source' }]);
  assert.deepEqual(result.output.sources?.map(source => source.url), ['', 'https://source.example/report']);
  assert.match(promptOf(f.requests[2]), /\[1\].*用户原文/); assert.match(promptOf(f.requests[2]), /\[2\].*公开来源/);
  assert.equal(result.output.sectionStates.EXTENDED_READING.status, 'READY');
  assert.deepEqual(f.saved().sources, result.output.sources);
});
test('legacy source-stage retry rebuilds saved usable sources locally without any provider call', async () => {
  const f = fixture(); const result = await runSection(f.deps, 'article', '产品', 'EXTENDED_READING');
  assert.equal(result.ok, true); assert.equal(f.requests.length, 0);
  assert.equal(result.output.sources?.[0].title, '用户原文');
  assert.deepEqual(result.output.summary, f.saved().summary);
});
test('new optional sections are displayable and analysis parsing does not erase old legacy fields', async () => {
  assert.equal(hasDisplayableDeepReadOutput({ ...makeEmptyDeepReadOutput(), bottomLine: '一句话结论' }), true);
  assert.equal(hasDisplayableDeepReadOutput({ ...makeEmptyDeepReadOutput(), impacts: [{ target: '用户', horizon: 'long', effect: '长期影响' }] }), true);
  const initial = { ...makeEmptyDeepReadOutput(), analysis: { coreDispute: null, perspectives: [], implications: '旧稿影响', quotes: [{ text: '旧稿引语', attribution: '旧来源' }] } };
  const writer = createSectionWriterTools({ topicId: 'article', topicTitle: '产品', imageCandidates: [], initialOutput: initial });
  assert.equal(await writeStructuredStage(writer, 'ANALYSIS', JSON.stringify(analysis)), true);
  assert.equal(writer.current().analysis.implications, '旧稿影响'); assert.equal(writer.current().analysis.quotes[0].text, '旧稿引语');
});

test('source-only cold continuation has no planning request and completed legacy drafts stay untouched', async () => {
  const f = fixture(); const complete = (await run(f.deps, 'article', '产品')).output;
  f.requests.splice(0);
  const partial = { ...complete, generationComplete: false, sectionStates: { ...complete.sectionStates, EXTENDED_READING: { status: 'FAILED' as const, errorMessage: '旧来源阶段中断' } } };
  f.set(partial); const repaired = await run(f.deps, 'article', '产品');
  assert.equal(isComplete(repaired.output), true); assert.equal(f.requests.length, 0);
  assert.deepEqual(repaired.output.timeline, complete.timeline);
  const legacy = { ...complete, bottomLine: undefined, impacts: undefined, sources: undefined };
  f.set(legacy); const opened = await run(f.deps, 'article', '产品');
  assert.deepEqual(opened.output, legacy); assert.equal(f.requests.length, 0);
});
test('empty saved sources fail honestly and local retry propagates storage errors', async () => {
  const f = fixture(); f.set(makeEmptyDeepReadOutput());
  const result = await runSection(f.deps, 'article', '产品', 'EXTENDED_READING');
  assert.equal(result.ok, false); assert.equal(result.output.sectionStates.EXTENDED_READING.status, 'FAILED');
  assert.equal(f.requests.length, 0);
  const other = fixture(); other.deps.repository!.save = async () => { throw new Error('storage rejected'); };
  await assert.rejects(runSection(other.deps, 'article', '产品', 'EXTENDED_READING'), /storage rejected/);
  assert.equal(other.requests.length, 0);
});
test('uncertainty helper normalizes legacy strings while keeping labels and omitting invalid claims', () => {
  const output = { ...makeEmptyDeepReadOutput(), uncertainties: ['旧说法', { claim: '待确认', status: 'pending_official' }, { claim: 12, status: 'conflicting' }, null] } as unknown as DeepReadOutput;
  assert.deepEqual(normalizedDeepReadUncertainties(output), [{ claim: '旧说法', status: '' }, { claim: '待确认', status: 'pending_official' }]);
});
test('overview hero and nonlinear narrative diagram use the existing verified visual validators', async () => {
  const writer = createSectionWriterTools({ topicId: 'article', topicTitle: '产品', imageCandidates: [] });
  await writeStructuredStage(writer, 'OVERVIEW', JSON.stringify({ ...overview, hero_image_url: 'https://invented.example/image.png' }));
  assert.equal(writer.current().heroImageUrl, null);
  const diagram = { type: 'causal_chain', title: '线性关系', nodes: [{ id: 'a', label: 'A' }, { id: 'b', label: 'B' }] };
  await writeStructuredStage(writer, 'NARRATIVE', JSON.stringify({ ...narrative, diagram }));
  assert.equal(writer.current().diagram, null);
  await writeStructuredStage(writer, 'NARRATIVE', JSON.stringify({ ...narrative, diagram: { ...diagram, type: 'stakeholder_map' } }));
  assert.equal(writer.current().diagram?.type, 'stakeholder_map');
});

test('continuation retains article-owned numbering when inputs reorder and a new source is appended', async () => {
  const f = fixture(); const complete = (await run(f.deps, 'article', '产品')).output;
  const inputs = complete.inputSources!;
  const reordered = { ...complete, generationComplete: false, inputSources: inputs.slice().reverse(),
    sectionStates: { ...complete.sectionStates, ANALYSIS: { status: 'FAILED' as const, errorMessage: '补分析' } } };
  f.set(reordered); f.requests.splice(0);
  const result = await run(f.deps, 'article', '产品');
  assert.deepEqual(result.output.sources, complete.sources);
  const prompt = promptOf(f.requests[1]);
  assert.match(prompt, /\[1\].*用户原文/); assert.match(prompt, /\[2\].*公开来源/);
  const local = await runSection(f.deps, 'article', '产品', 'EXTENDED_READING');
  assert.deepEqual(local.output.sources, complete.sources);
  const extra = { ...inputs[0], id: 'extra', title: '新增用户文件', kind: 'file' as const };
  f.set({ ...result.output, inputSources: [extra, ...inputs.slice().reverse()] });
  const appended = await runSection(f.deps, 'article', '产品', 'EXTENDED_READING');
  assert.deepEqual(appended.output.sources?.slice(0, 2), complete.sources);
  assert.equal(appended.output.sources?.[2].title, extra.title);
});

test('two same-title text inputs retain distinct stable source identities and reference slots', async () => {
  const f = fixture(); const initial = f.saved(); const first = initial.inputSources![0];
  const second = { ...first, id: 'other-manual', content: '第二份不同的真实正文。'.repeat(40) };
  f.set({ ...initial, inputSources: [first, second] });
  f.deps.prefetcher.collect = async () => [];
  const result = await run(f.deps, 'article', '产品');
  assert.deepEqual(result.output.sources?.map(source => source.sourceId), ['input:manual', 'input:other-manual']);
  const prompt = promptOf(f.requests[2]);
  assert.match(prompt, /\[1\] input:manual/); assert.match(prompt, /\[2\] input:other-manual/);
  f.set({ ...result.output, inputSources: [second, first] });
  const local = await runSection(f.deps, 'article', '产品', 'EXTENDED_READING');
  assert.deepEqual(local.output.sources, result.output.sources);
});

test('cold prefetch counter collision cannot alias distinct websites or overwrite an old source number', async () => {
  const f = fixture(); const old = { sourceId: 'src-1', title: '原报道', url: 'https://old.example/report', source: '原网站', evidenceText: '已保存原报道正文。'.repeat(40), credibility: 'high' as const, freshness: 'unknown' as const, publishedAt: null, imageCandidates: [] };
  const newer = { ...old, url: 'https://new.example/report', title: '新报道', source: '新网站' };
  f.set({ ...makeEmptyDeepReadOutput(), sources: [{ sourceId: old.sourceId, title: old.title, url: old.url, source: old.source, publishedAt: null }], inputSources: [{ id: 'persisted-old-input', kind: 'search', title: old.title, url: old.url, content: old.evidenceText, status: 'ready', error: null, truncated: false, note: null, researchSource: { ...old, evidenceText: '' } }] });
  f.deps.prefetcher.collect = async () => [newer];
  const result = await createRunContext(f.deps, 'article', '主题', null, false);
  assert.equal(result.ok, true); if (!result.ok) return;
  const context = result.context;
  assert.deepEqual(context.writer.current().sources?.map(source => source.url), [old.url, newer.url]);
  assert.equal(new Set(context.evidencePack.allSources.map(source => source.sourceId)).size, 2);
  const mapping = context.evidencePack.allSources.map(source => context.evidencePack.sourceNumbers![source.sourceId]);
  assert.deepEqual(mapping, [1, 2]);
});
