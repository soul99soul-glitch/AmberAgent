import { test } from 'node:test';
import assert from 'node:assert/strict';
import { run } from '../main/ets/agent/run_manager.ts';
import type { RunManagerDeps } from '../main/ets/agent/run_manager.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';
import type { DeepReadOutput } from '../main/ets/domain/models.ts';
import { isComplete, statusOf } from '../main/ets/domain/helpers.ts';
import type { GenerateTextParams } from '../main/ets/platform/ai_client.ts';
const overview = { topic_type: 'product', summary: '两个真实来源描述了产品的发布、使用方式与影响；目前仍需进一步核对性能和市场反馈。', key_entities: ['产品'] };
const narrative = { timeline: [{ date: '今天', event: '来源记录产品公布之后开始接受用户反馈的经过。' }], core_points: [{ point: '功能与实际使用之间仍需要更多验证。' }], summary: '不应覆盖前段' };
const analysis = { analysis: { core_dispute: '各方核心分歧在于这些功能是否足以解决真实使用场景的长期问题。', perspectives: [], implications: '需要继续核对不同用户的反馈和来源材料。', quotes: [] } };
const extended = { extended_reading: [{ title: '已提供的来源', url: 'https://source.example/report', source: '来源' }], references: [] };
const requestPrompt = (request: GenerateTextParams): string => request.messages[0].parts.map(part => part.type === 'text' ? part.text : '').join('');
const normalReply = (request: GenerateTextParams): string => {
  const prompt = requestPrompt(request);
  return JSON.stringify(prompt.includes('目标段落：概览') ? overview : prompt.includes('目标段落：时间轴叙事') ? narrative
    : prompt.includes('目标段落：深度分析') ? analysis : prompt.includes('目标段落：扩展阅读') ? extended : { overview_angle: '实际来源分析' });
};
const fixture = (reply = normalReply) => {
  let saved: DeepReadOutput | null = null; const requests: GenerateTextParams[] = [];
  const aiClient = { generateText: async (request: GenerateTextParams) => {
    requests.push(request); assert.equal(request.tools?.length ?? 0, 0, 'text-only model receives no tools');
    return request.messages.concat(makeAssistantMessage(reply(request)));
  } };
  const deps: RunManagerDeps = { model: 'text-only', writerMode: 'structured', playbookMarkdown: '', nowIso: () => '2026-10-03', aiClient,
    prefetcher: { collect: async () => [{ sourceId: 'source', url: 'https://source.example/report', title: '已读取的真实来源', source: '用户', evidenceText: '来源描述产品发布与反馈。'.repeat(30), credibility: 'medium', freshness: 'unknown', publishedAt: null, imageCandidates: [] }], cacheSize: () => 0 },
    collectRun: (messages, _label, signal, tools) => aiClient.generateText({ model: 'text-only', messages, signal,
      tools: tools?.map(tool => ({ ...tool, schema: tool.schema ?? {} })) }),
    repository: { get: () => saved, save: (_id, _title, output) => { saved = structuredClone(output); }, clear: () => { saved = null; } },
  };
  return { deps, requests, saved: () => saved!, set: (output: DeepReadOutput) => { saved = output; } };
};

test('text-only model completes plan plus three JSON stages and local sources, preserving earlier structured content', async () => {
  const f = fixture(); const result = await run(f.deps, 'article', '来源产品');
  assert.equal(result.ok, true); assert.equal(isComplete(result.output), true); assert.equal(f.requests.length, 4);
  assert.equal(result.output.summary, overview.summary); assert.equal(result.output.timeline?.length, 1);
  assert.equal(result.output.analysis.coreDispute, analysis.analysis.core_dispute); assert.equal(result.output.sources?.[0].url, extended.extended_reading[0].url);
  assert.match(requestPrompt(f.requests[3]), /"timeline"/); assert.match(requestPrompt(f.requests[3]), /来源记录产品公布/);
  assert.equal(isComplete(f.saved()), true);
});

test('bad stage JSON receives one correction instead of treating free text as a complete section', async () => {
  let bad = 0; const f = fixture(request => {
    if (requestPrompt(request).includes('目标段落：深度分析')) { bad++; return bad === 1 ? '自由文本不应直接标成分析完成' : JSON.stringify(analysis); }
    return normalReply(request);
  });
  const result = await run(f.deps, 'article', '来源产品');
  assert.equal(isComplete(result.output), true); assert.equal(bad, 2); assert.equal(f.requests.length, 5);
  assert.equal(result.output.sectionQualities.ANALYSIS, 'STANDARD');
});

test('failed structured replacement retains the complete old article', async () => {
  const first = fixture(); await run(first.deps, 'article', '来源产品'); const old = first.saved();
  const replacement = fixture(() => '坏 JSON'); replacement.set(old);
  const result = await run(replacement.deps, 'article', '来源产品', { force: true });
  assert.equal(result.ok, false); assert.deepEqual(result.output, old); assert.deepEqual(replacement.saved(), old);
});

test('cold structured continuation only requests the failed stage and preserves ready sections', async () => {
  const first = fixture(); const complete = (await run(first.deps, 'article', '来源产品')).output;
  const partial = { ...complete, generationComplete: false, analysis: makeEmptyDeepReadOutput().analysis,
    sectionStates: { ...complete.sectionStates, ANALYSIS: { status: 'FAILED' as const, errorMessage: '中断' } } };
  const cold = fixture(); cold.set(partial);
  const result = await run(cold.deps, 'article', '来源产品');
  assert.equal(isComplete(result.output), true); assert.equal(cold.requests.length, 2);
  assert.deepEqual(result.output.timeline, partial.timeline); assert.equal(result.output.summary, partial.summary);
  assert.equal(statusOf(result.output, 'ANALYSIS'), 'READY');
});

test('manual text without external links can complete with a validated source-backed diagram', async () => {
  const f = fixture(request => requestPrompt(request).includes('目标段落：时间轴叙事')
    ? JSON.stringify({ ...narrative, diagram: { type: 'stakeholder_map', title: '产品反馈关系',
      nodes: [{ id: 'release', label: '产品方' }, { id: 'feedback', label: '用户' }, { id: 'review', label: '评测方' }], edges: [{ from: 'release', to: 'feedback' }] } })
    : normalReply(request));
  f.set({ ...makeEmptyDeepReadOutput(), inputText: '产品公布后开始收集用户反馈。'.repeat(40), inputUrlsText: '',
    inputSources: [{ id: 'manual', kind: 'text', title: '原文', url: null, content: '产品公布后开始收集用户反馈。'.repeat(40), status: 'ready', error: null, truncated: false, note: null }] });
  f.deps.prefetcher.collect = async () => [];
  const result = await run(f.deps, 'article', '原文分析');
  assert.equal(result.ok, true); assert.equal(isComplete(result.output), true);
  assert.equal(result.output.extendedReading.length, 0); assert.equal(result.output.diagram?.nodes.length, 3);
  assert.equal(result.output.inputText, f.saved().inputText);
});

test('invalid optional hero and diagram never enter an otherwise valid editorial article', async () => {
  const f = fixture(request => requestPrompt(request).includes('目标段落：概览')
    ? JSON.stringify({ ...overview, hero_image_url: 'https://invented.example/image.png' })
    : requestPrompt(request).includes('目标段落：时间轴叙事')
      ? JSON.stringify({ ...narrative, diagram: { type: 'unsupported', title: '无依据', nodes: [{ id: 'a', label: 'A' }, { id: 'b', label: 'B' }] } }) : normalReply(request));
  const result = await run(f.deps, 'article', '来源产品');
  assert.equal(isComplete(result.output), true); assert.equal(statusOf(result.output, 'EXTENDED_READING'), 'READY');
  assert.equal(result.output.heroImageUrl, null); assert.equal(result.output.diagram, null);
  assert.equal(f.requests.length, 4);
});

test('structured stages persist explicit template capture, continuation keeps it and force uses the next explicit capture', async () => {
  const original = { id: 'custom_a', name: 'A', kind: 'custom' as const, html: '<article>A</article>', capturedAt: 1 };
  const changedDefault = { id: 'editorial', name: 'B', kind: 'editorial' as const, html: null, capturedAt: 2 };
  const selected = { id: 'custom_c', name: 'C', kind: 'custom' as const, html: '<article>C</article>', capturedAt: 3 };
  const first = fixture(); first.deps.templateSnapshot = changedDefault;
  const created = await run(first.deps, 'article', '来源产品', { templateSnapshot: original });
  assert.deepEqual(created.output.templateSnapshot, original); assert.equal(first.saved().templateId, original.id);
  const cold = fixture(); cold.deps.templateSnapshot = changedDefault;
  cold.set({ ...created.output, generationComplete: false, analysis: makeEmptyDeepReadOutput().analysis,
    sectionStates: { ...created.output.sectionStates, ANALYSIS: { status: 'FAILED', errorMessage: '中断' } } });
  const continued = await run(cold.deps, 'article', '来源产品');
  assert.deepEqual(continued.output.templateSnapshot, original); assert.deepEqual(cold.saved().templateSnapshot, original);
  const replaced = await run(cold.deps, 'article', '来源产品', { force: true, templateSnapshot: selected });
  assert.equal(isComplete(replaced.output), true); assert.deepEqual(replaced.output.templateSnapshot, selected);
  const failed = fixture(() => '坏 JSON'); failed.set(replaced.output); failed.deps.templateSnapshot = changedDefault;
  const retained = await run(failed.deps, 'article', '来源产品', { force: true, templateSnapshot: original });
  assert.equal(retained.ok, false); assert.deepEqual(failed.saved().templateSnapshot, selected);
});
