import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { fallbackPlan, parsePlanJson, normalizePlan, generateArticlePlan } from '../main/ets/research/article_plan.ts';
import type { DeepReadEvidencePack } from '../main/ets/research/evidence_pack.ts';
import { cardsFor } from '../main/ets/research/evidence_pack.ts';
import type { DeepReadSource } from '../main/ets/research/source_prefetcher.ts';
import type { AiClient } from '../main/ets/platform/ai_client.ts';
import type { UIMessage } from '../main/ets/agent/message.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
import { STAGE_EVIDENCE_MAX } from '../main/ets/domain/enums.ts';

// ===== fixtures =====

const makeSource = (id: string): DeepReadSource => ({
  sourceId: id,
  url: `https://example.com/${id}`,
  title: `Title ${id}`,
  source: 'tavily',
  evidenceText: '证据正文内容'.repeat(50),
  credibility: 'medium',
  freshness: 'unknown',
  publishedAt: null,
  imageCandidates: [],
});

// 8 sources, distributed: OVERVIEW s1..s6, NARRATIVE s7..s8 (after buildEvidencePack)
const buildPack = (count: number): DeepReadEvidencePack => {
  // 直接构造 pack(避免依赖 buildEvidencePack 的具体分桶)
  const sources: DeepReadSource[] = [];
  for (let i = 1; i <= count; i++) sources.push(makeSource(`s${i}`));
  // 手动分桶便于确定性测试
  const cardsByStage = {
    OVERVIEW: sources.slice(0, 6).map(s => ({
      sourceId: s.sourceId, url: s.url, title: s.title, source: s.source,
      credibility: s.credibility, freshness: s.freshness, publishedAt: s.publishedAt,
      evidenceExcerpt: s.evidenceText.slice(0, 2000), imageCandidates: [],
    })),
    NARRATIVE: sources.slice(6, 12).map(s => ({
      sourceId: s.sourceId, url: s.url, title: s.title, source: s.source,
      credibility: s.credibility, freshness: s.freshness, publishedAt: s.publishedAt,
      evidenceExcerpt: s.evidenceText.slice(0, 2000), imageCandidates: [],
    })),
    ANALYSIS: sources.slice(12, 18).map(s => ({
      sourceId: s.sourceId, url: s.url, title: s.title, source: s.source,
      credibility: s.credibility, freshness: s.freshness, publishedAt: s.publishedAt,
      evidenceExcerpt: s.evidenceText.slice(0, 2000), imageCandidates: [],
    })),
    EXTENDED_READING: sources.slice(18, 24).map(s => ({
      sourceId: s.sourceId, url: s.url, title: s.title, source: s.source,
      credibility: s.credibility, freshness: s.freshness, publishedAt: s.publishedAt,
      evidenceExcerpt: s.evidenceText.slice(0, 2000), imageCandidates: [],
    })),
  };
  return { allSources: sources, cardsByStage, requiredSourceIds: sources.slice(0, 8).map(s => s.sourceId) };
};

// mock AiClient 返回固定 assistant text
const mockAi = (responseText: string): AiClient => ({
  generateText: async (): Promise<UIMessage[]> => {
    return [makeAssistantMessage(responseText)];
  },
});

const throwingAi = (): AiClient => ({
  generateText: async (): Promise<UIMessage[]> => { throw new Error('LLM unavailable'); },
});

test('fallbackPlan: stageSourceIds start from pack.cardsByStage and fill from global pool', () => {
  const pack = buildPack(8);  // OVERVIEW has s1..s6, NARRATIVE s7..s8, others empty
  const plan = fallbackPlan('t', pack);
  assert.deepEqual(plan.stageSourceIds.overview, ['s1', 's2', 's3', 's4', 's5', 's6']);
  // 本段 bucket 优先(s7,s8),不足时从全局池补齐
  assert.deepEqual(plan.stageSourceIds.narrative.slice(0, 2), ['s7', 's8']);
  assert.ok(plan.stageSourceIds.narrative.length > 2, 'narrative bucket topped up from global pool');
});

test('R24: fallbackPlan gives every stage evidence from the global pool', () => {
  // 规划降级且来源集中在首段(buildPack 连续分桶的典型形态):后段不得为 0 证据
  const pack = buildPack(6);  // only OVERVIEW bucket non-empty
  const plan = fallbackPlan('t', pack);
  for (const key of ['overview', 'narrative', 'analysis', 'extended_reading']) {
    assert.ok(plan.stageSourceIds[key].length > 0, `${key} must not be starved`);
    // 复用真实 cardsFor 路径确认能取到卡片
    const cards = cardsFor(pack, key.toUpperCase() as never, plan);
    assert.ok(cards.length > 0, `${key} cardsFor must yield evidence`);
  }
});

test('parsePlanJson: fenced code block', () => {
  const text = 'Here is the plan:\n```json\n{"overview_angle": "y"}\n```\nDone.';
  const r = parsePlanJson(text);
  assert.ok(r !== null);
  assert.equal(r?.overview_angle, 'y');
});

test('parsePlanJson: balanced braces extraction (string-aware)', () => {
  // 大括号内有 } 字符串 → 不应提前闭合
  const text = 'prefix {"overview_angle": "has } brace", "narrative_slots": []} suffix';
  const r = parsePlanJson(text);
  assert.ok(r !== null);
  assert.equal(r?.overview_angle, 'has } brace');
});

test('parsePlanJson: escaped quote in string does not break balance', () => {
  const text = '{"overview_angle": "say \\"hello\\" now"}';
  const r = parsePlanJson(text);
  assert.ok(r !== null);
  assert.equal(r?.overview_angle, 'say "hello" now');
});

test('parsePlanJson: invalid text → null', () => {
  assert.equal(parsePlanJson('not json at all'), null);
  assert.equal(parsePlanJson(''), null);
});

test('parsePlanJson: non-object JSON (array) → null', () => {
  assert.equal(parsePlanJson('["a","b"]'), null);
  assert.equal(parsePlanJson('"string"'), null);
});

test('parsePlanJson: multiple objects, takes first parseable', () => {
  const text = 'noise {"valid": true} more {"other": false}';
  const r = parsePlanJson(text);
  assert.ok(r !== null);
  assert.equal(r?.valid, true);
});

// ===== normalizePlan =====

test('normalizePlan: filters hallucinated sourceIds not in pack (Android fidelity)', () => {
  const pack = buildPack(8);  // known ids: s1..s8
  const parsed = {
    overview_angle: 'angle',
    stage_source_ids: {
      overview: ['s1', 'FAKE_ID', 's2'],  // FAKE not in pack
      narrative: ['s7'],
    },
    required_source_ids: ['s1', 'NONEXISTENT', 's3'],
  };
  const plan = normalizePlan(parsed, 't', pack);
  assert.ok(!plan.stageSourceIds.overview.includes('FAKE_ID'));
  assert.ok(!plan.requiredSourceIds.includes('NONEXISTENT'));
  assert.ok(plan.stageSourceIds.overview.includes('s1'));
});

test('normalizePlan: caps stageSourceIds at STAGE_EVIDENCE_MAX', () => {
  const pack = buildPack(24);  // all 24 ids known
  const parsed = {
    stage_source_ids: {
      overview: ['s1','s2','s3','s4','s5','s6','s7','s8','s9','s10'],
    },
  };
  const plan = normalizePlan(parsed, 't', pack);
  assert.ok(plan.stageSourceIds.overview.length <= STAGE_EVIDENCE_MAX);
});

test('normalizePlan: accepts camelCase field names too', () => {
  const pack = buildPack(8);
  const parsed = {
    overviewAngle: 'camel angle',
    narrativeSlots: ['slot1'],
    stageSourceIds: { overview: ['s1'] },
  };
  const plan = normalizePlan(parsed, 't', pack);
  assert.equal(plan.overviewAngle, 'camel angle');
  assert.deepEqual(plan.narrativeSlots, ['slot1']);
});

test('normalizePlan: unions requiredSourceIds with stage ids + fallback', () => {
  const pack = buildPack(8);
  const parsed = {
    required_source_ids: ['s3'],
    stage_source_ids: { overview: ['s1', 's2'] },
  };
  const plan = normalizePlan(parsed, 't', pack);
  // requiredSourceIds should include s3 (parsed) and s1, s2 (from stage), filtered to allIds
  assert.ok(plan.requiredSourceIds.includes('s3'));
});

test('normalizePlan: mergeWithFallbackIds fills to STAGE_EVIDENCE_MIN when parsed < 4', () => {
  const pack = buildPack(24);  // 24 ids available
  const parsed = {
    stage_source_ids: { overview: ['s1'] },  // only 1 parsed, MIN=4
  };
  const plan = normalizePlan(parsed, 't', pack);
  // should be padded to at least 4 (from fallback + allIds)
  assert.ok(plan.stageSourceIds.overview.length >= 4, `got ${plan.stageSourceIds.overview.length}`);
  assert.ok(plan.stageSourceIds.overview.includes('s1'));
});

// ===== generateArticlePlan =====

test('generateArticlePlan: LLM success → uses LLM plan (normalized)', async () => {
  const pack = buildPack(8);
  const ai = mockAi(JSON.stringify({
    overview_angle: 'LLM angle',
    narrative_slots: ['llm slot'],
    stage_source_ids: { overview: ['s1', 's2'] },
  }));
  const plan = await generateArticlePlan(ai, 'm', '话题', pack, '');
  assert.equal(plan.overviewAngle, 'LLM angle');
  assert.deepEqual(plan.narrativeSlots, ['llm slot']);
  assert.ok(plan.stageSourceIds.overview.includes('s1'));
});

test('generateArticlePlan: LLM throws → fallback plan', async () => {
  const pack = buildPack(8);
  const ai = throwingAi();
  const plan = await generateArticlePlan(ai, 'm', '话题', pack, '');
  // fallback overviewAngle includes title
  assert.ok(plan.overviewAngle.includes('话题'));
  assert.ok(plan.narrativeSlots.length > 0);
});

test('generateArticlePlan: LLM returns non-JSON → fallback', async () => {
  const pack = buildPack(8);
  const ai = mockAi('this is not json at all, sorry');
  const plan = await generateArticlePlan(ai, 'm', '话题', pack, '');
  assert.ok(plan.overviewAngle.includes('话题'), 'fell back to default');
});

test('generateArticlePlan: LLM returns fenced JSON → parsed', async () => {
  const pack = buildPack(8);
  const ai = mockAi('```json\n{"overview_angle": "fenced angle", "narrative_slots": ["x"]}\n```');
  const plan = await generateArticlePlan(ai, 'm', '话题', pack, '');
  assert.equal(plan.overviewAngle, 'fenced angle');
});

test('generateArticlePlan: LLM returns partial fields → normalizePlan fills defaults', async () => {
  const pack = buildPack(8);
  const ai = mockAi(JSON.stringify({ overview_angle: 'only angle' }));  // missing other fields
  const plan = await generateArticlePlan(ai, 'm', '话题', pack, '');
  assert.equal(plan.overviewAngle, 'only angle');
  assert.ok(plan.narrativeSlots.length > 0, 'defaults filled');
  assert.ok(plan.analysisQuestions.length > 0);
});

test('generateArticlePlan: LLM hallucinates ids → filtered out', async () => {
  const pack = buildPack(8);  // s1..s8
  const ai = mockAi(JSON.stringify({
    stage_source_ids: { overview: ['s1', 'HALLUCINATED'] },
    required_source_ids: ['BOGUS'],
  }));
  const plan = await generateArticlePlan(ai, 'm', '话题', pack, '');
  assert.ok(!plan.stageSourceIds.overview.includes('HALLUCINATED'));
  assert.ok(!plan.requiredSourceIds.includes('BOGUS'));
  assert.ok(plan.stageSourceIds.overview.includes('s1'));
});
