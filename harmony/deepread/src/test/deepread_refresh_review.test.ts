import test from 'node:test';
import assert from 'node:assert/strict';
import { createRunContext, run, runSection } from '../main/ets/agent/run_manager.ts';
import type { RunManagerDeps } from '../main/ets/agent/run_manager.ts';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';
import { makeInputSource } from '../main/ets/domain/input_sources.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
import type { DeepReadSource } from '../main/ets/research/source_prefetcher.ts';

const fresh: DeepReadSource = { sourceId: 'fresh', title: '最新网页', url: 'https://example.com/report',
  source: 'seed', evidenceText: '新的网页正文已经发生更新。'.repeat(30), credibility: 'high',
  freshness: 'fresh', publishedAt: '2026-10-03', imageCandidates: [] };

const fixture = () => {
  let saved = makeEmptyDeepReadOutput();
  const prompts: string[] = [];
  const deps: RunManagerDeps = { writerMode: 'structured', model: 'plain', playbookMarkdown: '',
    nowIso: () => '2026-10-03', prefetcher: { collect: async () => [fresh], cacheSize: () => 0 },
    aiClient: { generateText: async () => [] }, collectRun: async messages => {
      prompts.push(messages[0].parts.map(part => part.type === 'text' ? part.text : '').join(''));
      return [...messages, makeAssistantMessage(JSON.stringify({ extended_reading: [{ title: fresh.title, url: fresh.url }] }))];
    }, repository: { get: () => saved, save: (_id, _title, output) => { saved = structuredClone(output); }, clear: () => {} } };
  return { deps, prompts, saved: () => saved };
};

for (const sectionOnly of [false, true]) test(`legacy partial ${sectionOnly ? 'section retry' : 'continuation'} completes once and preserves inferred ready chapters`, async () => {
  const f = fixture(); const old = f.saved();
  old.summary = '旧概览已经保存，必须保留。';
  old.timeline = [{ date: '昨天', event: '旧时间线已保存。', isHighlight: false, imageUrl: null, imageCaption: null }];
  old.analysis.coreDispute = '旧分析已经保存。';
  old.inputSources = [makeInputSource('web', fresh.title, '曾经保存过的真实网页正文。'.repeat(30), fresh.url)];
  old.templateSnapshot = { id: 'none', name: '默认排版', kind: 'native', html: null, capturedAt: 1 };
  const result = sectionOnly ? await runSection(f.deps, 'legacy', '旧版部分稿', 'EXTENDED_READING')
    : await run(f.deps, 'legacy', '旧版部分稿');
  assert.equal(result.ok, true);
  assert.equal(result.output.generationComplete, true);
  assert.equal(result.output.generationPhase, 'COMPLETE');
  assert.equal(f.prompts.length, 0);
  assert.equal(result.output.summary, old.summary);
  assert.deepEqual(result.output.timeline, old.timeline);
  assert.deepEqual(result.output.analysis, old.analysis);
  assert.deepEqual(result.output.templateSnapshot, old.templateSnapshot);
  for (const stage of ['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING']) {
    assert.equal(f.saved().sectionStates[stage].status, 'READY');
  }
});

test('force recollection feeds successful new web bodies to the writer while preserving local input bytes and source IDs', async () => {
  const f = fixture(); const old = f.saved();
  old.inputText = '原文\r\n 不应改写'; old.inputSourceUrls = [fresh.url];
  const localText = makeInputSource('text', '本地文本', '原始上下文', fresh.url);
  const localFile = makeInputSource('file', '原始文件', '文件原字节', fresh.url);
  const oldWeb = makeInputSource('web', '旧网页', '旧网页正文', fresh.url);
  old.inputSources = [localText, localFile, oldWeb];
  const result = await createRunContext(f.deps, 'force', '强制更新', fresh.url, true);
  assert.equal(result.ok, true); if (!result.ok) return;
  const sources = result.context.writer.current().inputSources!;
  assert.deepEqual(sources.find(source => source.id === localText.id), localText);
  assert.deepEqual(sources.find(source => source.id === localFile.id), localFile);
  const refreshed = sources.find(source => source.id === oldWeb.id)!;
  assert.equal(refreshed.content, fresh.evidenceText);
  assert.equal(refreshed.researchSource?.sourceId, fresh.sourceId);
  assert.equal(refreshed.researchSource?.publishedAt, fresh.publishedAt);
  assert.equal(result.context.evidencePack.allSources.find(source => source.url === fresh.url && source.source === fresh.source)?.evidenceText, fresh.evidenceText);
  assert.equal(result.context.writer.current().inputText, old.inputText);
  assert.equal(oldWeb.content, '旧网页正文', 'the committed input remains untouched until a new article is saved');
});

test('force recollection failure retains the previously verified web body', async () => {
  const f = fixture(); const old = f.saved(); old.inputText = ''; old.inputSourceUrls = [fresh.url];
  const original = makeInputSource('web', '旧网页', '已核查的旧正文', fresh.url); old.inputSources = [original];
  f.deps.prefetcher.collect = async (_id, _title, _seed, _force, _signal, _urls, onCollected) => {
    onCollected?.([], [{ url: fresh.url, title: fresh.title, error: 'HTTP 500' }]); return [];
  };
  const result = await createRunContext(f.deps, 'force', '强制更新', fresh.url, true);
  assert.equal(result.ok, true); if (!result.ok) return;
  assert.deepEqual(result.context.writer.current().inputSources?.[0], original);
  assert.equal(result.context.evidencePack.allSources[0].evidenceText, original.content);
});

test('a legacy partial with no retained source cannot claim source completion or discard ready chapters', async () => {
  const f = fixture(); const old = f.saved();
  old.summary = '旧概览应当保留。'; old.analysis.coreDispute = '旧分析应当保留。';
  old.timeline = [{ date: '昨天', event: '旧时间线应当保留。', isHighlight: false, imageUrl: null, imageCaption: null }];
  const result = await run(f.deps, 'legacy', '无保留来源的旧版稿');
  assert.equal(result.ok, false); assert.equal(result.output.generationComplete, false);
  assert.equal(result.output.summary, old.summary); assert.deepEqual(result.output.timeline, old.timeline);
  assert.equal(result.output.sectionStates.OVERVIEW.status, 'READY');
  assert.equal(result.output.sectionStates.NARRATIVE.status, 'READY');
  assert.equal(result.output.sectionStates.ANALYSIS.status, 'READY');
  assert.equal(result.output.sectionStates.EXTENDED_READING.status, 'FAILED');
  assert.equal(f.prompts.length, 0);
});
