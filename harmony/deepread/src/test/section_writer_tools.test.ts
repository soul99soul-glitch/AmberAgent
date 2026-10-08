import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createSectionWriterTools, safeTake, mergeReadingLinks } from '../main/ets/agent/section_writer_tools.ts';
import type { ScoredImageCandidate } from '../main/ets/research/image_scorer.ts';
import type { ReadingLink, DeepReadOutput } from '../main/ets/domain/models.ts';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';
import { statusOf } from '../main/ets/domain/helpers.ts';

// 解析 tool output JSON
const parseOutput = (parts: { text: string }[]): Record<string, unknown> =>
  JSON.parse(parts[0].text);

// 造 hero 候选图
const heroCandidate = (url: string): ScoredImageCandidate => ({
  url, width: 800, height: 600, altText: 'a meaningful alt description', sourceUrl: 'src',
  confidence: 'hero', score: 75, riskFlags: [], selectionReason: 'hero match',
});
const rejectCandidate = (url: string): ScoredImageCandidate => ({
  url, width: 50, height: 50, altText: '', sourceUrl: 'src',
  confidence: 'reject', score: 0, riskFlags: ['too_small'], selectionReason: 'rejected',
});

const topicTitle = '量子计算重大突破';

// ===== cleanText / safeTake =====

test('safeTake: does not split UTF-16 surrogate pair (emoji)', () => {
  // 😀 = U+1F600 = surrogate pair (2 chars)
  const s = 'abc😀def';
  // take 4 would land on high surrogate index 3 → safeTake backs off to 3
  assert.equal(safeTake(s, 4), 'abc');
  assert.equal(safeTake(s, 5), 'abc😀');
});

// ===== mergeReadingLinks =====

const link = (url: string): ReadingLink => ({ title: 't', url, source: null, publishedAt: null });

test('mergeReadingLinks: dedup by url (trim trailing slash), http only, take limit', () => {
  const existing = [link('https://a.com/1'), link('https://b.com/2/')];
  const incoming = [link('https://a.com/1'), link('https://b.com/2'), link('ftp://x.com/3'), link('https://c.com/3')];
  const result = mergeReadingLinks(existing, incoming, 5);
  assert.equal(result.length, 3);
  assert.ok(result.some(l => l.url === 'https://a.com/1'));
  assert.ok(result.some(l => l.url.startsWith('https://b.com/2')));
  assert.ok(result.some(l => l.url === 'https://c.com/3'));
  assert.ok(!result.some(l => l.url.startsWith('ftp://')));
});

test('tools(stages) filters writer tools but keeps visuals/diagram/finish', () => {
  const w = createSectionWriterTools({ topicId: 't1', topicTitle, imageCandidates: [] });
  const names = w.tools(new Set(['OVERVIEW'])).map(t => t.name);
  assert.ok(names.includes('deep_read_write_overview'));
  assert.ok(!names.includes('deep_read_write_narrative'));
  // always-on tools
  assert.ok(names.includes('deep_read_write_visuals'));
  assert.ok(names.includes('deep_read_write_diagram'));
  assert.ok(names.includes('deep_read_finish'));
});

test('overview: too-short summary → missing_required_content, not READY', async () => {
  const w = createSectionWriterTools({ topicId: 't1', topicTitle, imageCandidates: [] });
  const tool = w.tools(new Set(['OVERVIEW'])).find(t => t.name === 'deep_read_write_overview')!;
  const out = await tool.execute(JSON.stringify({ summary: '太短' }));
  const parsed = parseOutput(out);
  assert.equal(parsed.status, 'missing_required_content');
  assert.equal(parsed.required, 'summary too short: 2/24');
  assert.notEqual(statusOf(w.current(), 'OVERVIEW'), 'READY');
  assert.equal(w.requiredWriteCount, 0);
});

test('narrative: event missing → timeline dropped', async () => {
  const w = createSectionWriterTools({ topicId: 't1', topicTitle, imageCandidates: [] });
  const tool = w.tools(new Set(['NARRATIVE'])).find(t => t.name === 'deep_read_write_narrative')!;
  const out = await tool.execute(JSON.stringify({
    timeline: [{ date: '2026', /* no event */ }],
    core_points: [{ point: '核心观点一足够长了满足条件', supporting: '支撑细节描述内容足够长以满足阈值要求。' }],
  }));
  const parsed = parseOutput(out);
  // core_points 救活了 narrative
  assert.equal(parsed.status, 'ok');
  assert.ok((parsed.accepted as Record<string, number>).timeline === 0);
});

test('analysis: all empty → missing_required_content', async () => {
  const w = createSectionWriterTools({ topicId: 't1', topicTitle, imageCandidates: [] });
  const tool = w.tools(new Set(['ANALYSIS'])).find(t => t.name === 'deep_read_write_analysis')!;
  const out = await tool.execute(JSON.stringify({}));
  assert.equal(parseOutput(out).status, 'missing_required_content');
});

// ===== extended_reading tool =====

test('extended_reading: valid links → READY', async () => {
  const w = createSectionWriterTools({ topicId: 't1', topicTitle, imageCandidates: [] });
  const tool = w.tools(new Set(['EXTENDED_READING'])).find(t => t.name === 'deep_read_write_extended_reading')!;
  const out = await tool.execute(JSON.stringify({
    links: [{ title: '相关阅读', url: 'https://ext.example.com/1' }],
  }));
  assert.equal(parseOutput(out).status, 'ok');
  assert.equal(statusOf(w.current(), 'EXTENDED_READING'), 'READY');
  assert.equal(w.current().extendedReading.length, 1);
});

test('extended_reading: no http links → missing', async () => {
  const w = createSectionWriterTools({ topicId: 't1', topicTitle, imageCandidates: [] });
  const tool = w.tools(new Set(['EXTENDED_READING'])).find(t => t.name === 'deep_read_write_extended_reading')!;
  const out = await tool.execute(JSON.stringify({ links: [{ title: 'x', url: 'ftp://bad' }] }));
  assert.equal(parseOutput(out).status, 'missing_required_content');
});

test('visuals: hero_url NOT in candidate pool → hero fields dropped (P1-1 gate)', async () => {
  const w = createSectionWriterTools({ topicId: 't1', topicTitle, imageCandidates: [] });
  const tool = w.tools(null).find(t => t.name === 'deep_read_write_visuals')!;
  await tool.execute(JSON.stringify({
    hero_image_url: 'https://evil.example.com/logo.png',
    hero_caption: '不该被接受',
  }));
  const cur = w.current();
  assert.equal(cur.heroImageUrl, null, 'arbitrary URL rejected');
  assert.equal(cur.heroCaption, null);
  assert.equal(cur.heroImageConfidence, null);
});

test('visuals: hero_url in pool but confidence=reject → dropped', async () => {
  const w = createSectionWriterTools({
    topicId: 't1', topicTitle,
    imageCandidates: [rejectCandidate('https://img.example.com/bad.jpg')],
  });
  const tool = w.tools(null).find(t => t.name === 'deep_read_write_visuals')!;
  await tool.execute(JSON.stringify({ hero_image_url: 'https://img.example.com/bad.jpg' }));
  assert.equal(w.current().heroImageUrl, null);
});

test('visuals: image_assets must be in candidate pool (withCandidateEvidence)', async () => {
  const w = createSectionWriterTools({
    topicId: 't1', topicTitle,
    imageCandidates: [heroCandidate('https://img.example.com/pool.jpg')],
  });
  const tool = w.tools(null).find(t => t.name === 'deep_read_write_visuals')!;
  await tool.execute(JSON.stringify({
    image_assets: [
      { url: 'https://img.example.com/pool.jpg', caption: '在池里' },
      { url: 'https://img.example.com/not-in-pool.jpg', caption: '不在池里' },
    ],
  }));
  const urls = w.current().imageAssets.map(a => a.url);
  assert.ok(urls.includes('https://img.example.com/pool.jpg'));
  assert.ok(!urls.includes('https://img.example.com/not-in-pool.jpg'));
});

test('visuals: no hero no assets → no write (writeCount unchanged)', async () => {
  const w = createSectionWriterTools({ topicId: 't1', topicTitle, imageCandidates: [] });
  const tool = w.tools(null).find(t => t.name === 'deep_read_write_visuals')!;
  await tool.execute(JSON.stringify({ hero_image_url: 'https://not-in-pool.jpg' }));
  assert.equal(w.writeCount, 0);
});

test('diagram: invalid type → missing', async () => {
  const w = createSectionWriterTools({ topicId: 't1', topicTitle, imageCandidates: [] });
  const tool = w.tools(null).find(t => t.name === 'deep_read_write_diagram')!;
  const out = await tool.execute(JSON.stringify({
    type: 'bogus', title: 'x',
    nodes: [{ id: 'n1', label: 'a' }, { id: 'n2', label: 'b' }],
  }));
  const parsed = parseOutput(out);
  assert.equal(parsed.status, 'missing_required_content');
  assert.equal(parsed.required, 'invalid type');
});

test('diagram: <2 nodes → missing (nodes < 2)', async () => {
  const w = createSectionWriterTools({ topicId: 't1', topicTitle, imageCandidates: [] });
  const tool = w.tools(null).find(t => t.name === 'deep_read_write_diagram')!;
  const out = await tool.execute(JSON.stringify({
    type: 'process_flow', title: 'x',
    nodes: [{ id: 'n1', label: 'a' }],
  }));
  assert.equal(parseOutput(out).required, 'nodes < 2');
});

test('diagram: duplicate node id deduped', async () => {
  const w = createSectionWriterTools({ topicId: 't1', topicTitle, imageCandidates: [] });
  const tool = w.tools(null).find(t => t.name === 'deep_read_write_diagram')!;
  await tool.execute(JSON.stringify({
    type: 'stakeholder_map', title: 'x',
    nodes: [
      { id: 'n1', label: 'a' },
      { id: 'n1', label: 'dup' },
      { id: 'n2', label: 'b' },
    ],
  }));
  assert.equal(w.current().diagram?.nodes.length, 2);
});

test('diagram: edge to unknown node / self edge dropped', async () => {
  const w = createSectionWriterTools({ topicId: 't1', topicTitle, imageCandidates: [] });
  const tool = w.tools(null).find(t => t.name === 'deep_read_write_diagram')!;
  await tool.execute(JSON.stringify({
    type: 'system_structure', title: 'x',
    nodes: [{ id: 'n1', label: 'a' }, { id: 'n2', label: 'b' }],
    edges: [
      { from: 'n1', to: 'n1' },          // self
      { from: 'n1', to: 'unknown' },      // unknown
      { from: 'n1', to: 'n2' },           // valid
    ],
  }));
  assert.equal(w.current().diagram?.edges.length, 1);
});

// ===== finish tool (三门闩锁) =====

test('finish: not all sections ready → generationComplete=false, missing list returned', async () => {
  const w = createSectionWriterTools({ topicId: 't1', topicTitle, imageCandidates: [] });
  const tool = w.tools(null).find(t => t.name === 'deep_read_finish')!;
  const out = await tool.execute('{}');
  const parsed = parseOutput(out);
  assert.equal(parsed.status, 'missing_sections');
  assert.deepEqual(parsed.missing, ['overview', 'narrative', 'analysis', 'extended_reading']);
  assert.equal(w.current().generationComplete, false);
});

test('finish: all ready → VERIFYING 过渡,二次 finish → COMPLETE', async () => {
  // 预置 4 sections READY
  const preset: DeepReadOutput = {
    ...makeEmptyDeepReadOutput(),
    summary: '概览内容足够长以满足最小字符阈值的要求。',
    timeline: [{ date: 'd', event: '时间线事件足够长的描述内容满足阈值', isHighlight: false, imageUrl: null, imageCaption: null }],
    corePoints: null,
    analysis: { coreDispute: '核心争议足够长的描述内容满足阈值的要求', perspectives: [], implications: null, quotes: [] },
    extendedReading: [{ title: 'x', url: 'https://ext.example.com', source: null, publishedAt: null }],
    sectionStates: {
      OVERVIEW: { status: 'READY', errorMessage: null },
      NARRATIVE: { status: 'READY', errorMessage: null },
      ANALYSIS: { status: 'READY', errorMessage: null },
      EXTENDED_READING: { status: 'READY', errorMessage: null },
    },
  };
  const w = createSectionWriterTools({ topicId: 't1', topicTitle, imageCandidates: [], initialOutput: preset });
  const tool = w.tools(null).find(t => t.name === 'deep_read_finish')!;
  const out1 = await tool.execute('{}');
  const parsed1 = parseOutput(out1);
  assert.equal(parsed1.status, 'complete');
  assert.deepEqual(parsed1.missing, []);
  // 第一次 finish:进入 VERIFYING(补漏),尚未 COMPLETE
  assert.equal(w.current().generationPhase, 'VERIFYING');
  assert.equal(w.current().generationComplete, false);
  // 第二次 finish(补漏通过):落 COMPLETE
  await tool.execute('{}');
  assert.equal(w.current().generationPhase, 'COMPLETE');
  assert.equal(w.current().generationComplete, true);
});

test('markFailed: sets FAILED with truncated message, skips READY', () => {
  const w = createSectionWriterTools({ topicId: 't1', topicTitle, imageCandidates: [] });
  const longMsg = '失败原因'.repeat(100);
  w.markFailed('OVERVIEW', longMsg);
  assert.equal(statusOf(w.current(), 'OVERVIEW'), 'FAILED');
  assert.ok((w.current().sectionStates.OVERVIEW?.errorMessage?.length ?? 999) <= 220);
});

// ===== writeFallbackSection =====

test('writeFallbackSection: OVERVIEW with useful assistant text → READY BASIC', () => {
  const w = createSectionWriterTools({ topicId: 't1', topicTitle, imageCandidates: [] });
  const result = w.writeFallbackSection(
    'OVERVIEW',
    '这是模型生成的足够长的中文概览文本内容,包含足够的汉字以通过有效性检查阈值要求。',
    [link('https://src.example.com/1')],
    false,
  );
  assert.equal(statusOf(result, 'OVERVIEW'), 'READY');
  assert.equal(result.sectionQualities.OVERVIEW, 'BASIC');
  assert.ok(result.summary.length > 0);
});

test('writeFallbackSection: skips if already READY and !allowReadyRewrite', () => {
  const preset: DeepReadOutput = { ...makeEmptyDeepReadOutput(), sectionStates: { OVERVIEW: { status: 'READY', errorMessage: null } } };
  const w = createSectionWriterTools({ topicId: 't1', topicTitle, imageCandidates: [], initialOutput: preset });
  const before = w.current();
  w.writeFallbackSection('OVERVIEW', '新文本内容', [], false);
  assert.equal(w.current(), before, 'unchanged when READY');
});

test('writeFallbackSection: low-quality assistant text → falls back to source/default text', () => {
  const w = createSectionWriterTools({ topicId: 't1', topicTitle, imageCandidates: [] });
  const result = w.writeFallbackSection('OVERVIEW', 'short', [], false);
  // 无 source,无有用文本 → 默认文本
  assert.ok(result.summary.includes(topicTitle));
});

// ===== JSON 解析容错 =====

test('tool execute: empty/invalid JSON input → treated as empty object (no throw)', async () => {
  const w = createSectionWriterTools({ topicId: 't1', topicTitle, imageCandidates: [] });
  const tool = w.tools(new Set(['OVERVIEW'])).find(t => t.name === 'deep_read_write_overview')!;
  const out = await tool.execute('not json');
  assert.equal(parseOutput(out).status, 'missing_required_content');
});
