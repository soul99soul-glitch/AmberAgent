import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createSectionWriterTools } from '../main/ets/agent/section_writer_tools.ts';
import {
  asObject, parseCorePoints, parsePerspectives, hasAnalysisContent,
} from '../main/ets/agent/section_writer_codec.ts';
import {
  buildFallbackBody, toFallbackCorePoints, sourcesToFallbackTimeline,
} from '../main/ets/agent/section_writer_fallback.ts';
import {
  parseImageAssets, withCandidateEvidence, buildVisualDiagnostics,
} from '../main/ets/agent/section_writer_visuals.ts';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';
import { statusOf } from '../main/ets/domain/helpers.ts';
import type { ReadingLink } from '../main/ets/domain/models.ts';
import type { ScoredImageCandidate } from '../main/ets/research/image_scorer.ts';

const source: ReadingLink = {
  title: '来源已经确认公开事件发生，相关各方随后公布正式回应和后续计划。',
  url: 'https://example.com/source', source: '公开来源', publishedAt: null,
};
const image: ScoredImageCandidate = {
  url: 'https://example.com/image.jpg', width: 800, height: 600,
  altText: '事件现场', sourceUrl: source.url, confidence: 'hero',
  score: 80, riskFlags: [], selectionReason: '标题实体匹配',
};

test('content codec preserves verified source order and editorial readiness without mutating input', () => {
  const input = {
    core_points: [{ point: '核心观点足够长', sources: [2, 1, 2, 3, 0, '1', 1.5] }],
    perspectives: [{ viewpoint: '公开立场', sources: [2, 3, 1] }],
  };
  const before = JSON.stringify(input);
  assert.deepEqual(parseCorePoints(input, 2)[0].sources, [2, 1]);
  assert.deepEqual(parsePerspectives(input, 2)[0].sources, [2, 1]);
  assert.equal(JSON.stringify(input), before);
  for (const invalid of ['null', '[]', '"text"', '{']) assert.deepEqual(asObject(invalid), {});

  const output = makeEmptyDeepReadOutput();
  output.impacts = [{ target: '公众', horizon: 'short', effect: '这项政策将改变现有公共服务安排，并且对多方产生持续影响。' }];
  assert.equal(hasAnalysisContent(output), false, 'impacts alone do not establish an analysis position');
  output.analysis.perspectives = parsePerspectives(input, 2);
  assert.equal(hasAnalysisContent(output), true, 'a concise position plus substantive impact is accepted');
});

test('fallback strips tool chatter and code while retaining narrative source identity', () => {
  const text = '公开资料已经确认事件发生，相关各方随后公布正式回应和后续安排。';
  const body = buildFallbackBody('NARRATIVE', `好的\n调用工具处理中\ndeep_read_finish\n\`\`\`json\n{"hidden":true}\n\`\`\`\n# ${text}`, [source], '主题');
  assert.equal(body, text);
  assert.equal(toFallbackCorePoints(body)[0].point, text.slice(0, -1));
  assert.deepEqual(sourcesToFallbackTimeline([source]), [{
    date: source.source, event: source.title, isHighlight: false, imageUrl: null, imageCaption: null,
  }]);
  assert.equal(buildFallbackBody('OVERVIEW', '好的', [source], '主题'), source.title);
  assert.equal(buildFallbackBody('EXTENDED_READING', '', [], '主题'), '');
});

test('visual evidence keeps model captions and pool scores while diagnostics include unselected rejects', () => {
  const rejected: ScoredImageCandidate = { ...image, url: 'https://example.com/logo.jpg', confidence: 'reject', score: 0, riskFlags: ['logo'], selectionReason: 'logo' };
  const pool = [image, rejected];
  const before = JSON.stringify(pool);
  const parsed = parseImageAssets({ image_assets: [{ url: image.url, caption: '模型图注' }, { url: rejected.url }] });
  const accepted = withCandidateEvidence(parsed[0], pool, '主题');
  assert.ok(accepted);
  assert.equal(accepted.caption, '模型图注');
  assert.equal(accepted.score, image.score);
  assert.equal(accepted.confidence, 'hero');
  assert.equal(withCandidateEvidence(parsed[1], pool, '主题'), null);
  const diagnostics = buildVisualDiagnostics(null, image, null, [accepted], pool);
  assert.equal(diagnostics?.candidateCount, 2);
  assert.deepEqual(diagnostics?.rejectedImages, [{ url: rejected.url, reason: 'logo', score: 0 }]);
  assert.equal(JSON.stringify(pool), before);
});

test('all tool handles synchronously share one output and both counters before their promises settle', async () => {
  const writer = createSectionWriterTools({ topicId: 'topic', topicTitle: '主题', imageCandidates: [image] });
  const overview = writer.tools().find(tool => tool.name === 'deep_read_write_overview')!;
  const visuals = writer.tools().find(tool => tool.name === 'deep_read_write_visuals')!;
  const initial = writer.current();
  const overviewPending = overview.execute(JSON.stringify({ summary: source.title }));
  const visualsPending = visuals.execute(JSON.stringify({ hero_image_url: image.url }));
  writer.writeSources([source]);
  assert.equal(writer.current().summary, source.title);
  assert.equal(writer.current().heroImageUrl, image.url);
  assert.deepEqual(writer.current().sources, [source]);
  assert.equal(writer.writeCount, 3);
  assert.equal(writer.requiredWriteCount, 2);
  assert.equal(initial.summary, '', 'initial snapshot remains unchanged');
  await Promise.all([overviewPending, visualsPending]);
  assert.equal(writer.writeCount, 3);
  writer.writeFallbackSection('OVERVIEW', '另一份有效但不能覆盖的概览', [], false);
  assert.equal(writer.writeCount, 3, 'READY fallback skip does not count a write');

  const replacement = makeEmptyDeepReadOutput();
  writer.setCurrentForTest(replacement);
  assert.equal(writer.current(), replacement);
  await overview.execute(JSON.stringify({ summary: source.title }));
  assert.equal(statusOf(writer.current(), 'OVERVIEW'), 'READY', 'old tool handles use the replaced canonical output');
  assert.equal(writer.writeCount, 4);
  assert.equal(writer.requiredWriteCount, 3);
});

test('model finish retains VERIFYING transition while external finish completes in one call', async () => {
  const readyWriter = () => {
    const writer = createSectionWriterTools({ topicId: 'topic', topicTitle: '主题', imageCandidates: [] });
    writer.writeFallbackSection('OVERVIEW', '', [source], false);
    writer.writeFallbackSection('NARRATIVE', '', [source], false);
    writer.writeFallbackSection('ANALYSIS', '', [source], false);
    writer.writeSources([source]);
    return writer;
  };
  const modelWriter = readyWriter();
  const finish = modelWriter.tools().find(tool => tool.name === 'deep_read_finish')!;
  assert.equal(JSON.parse((await finish.execute('{}'))[0].text).status, 'complete');
  assert.equal(modelWriter.current().generationPhase, 'VERIFYING');
  assert.equal(modelWriter.current().generationComplete, false);
  await finish.execute('{}');
  assert.equal(modelWriter.current().generationComplete, true);

  const externalWriter = readyWriter();
  assert.equal(externalWriter.executeFinish().generationPhase, 'COMPLETE');
  assert.equal(externalWriter.current().generationComplete, true);
  assert.equal(externalWriter.writeCount, 4, 'finish does not count as content output');
  assert.equal(externalWriter.requiredWriteCount, 4);
});
