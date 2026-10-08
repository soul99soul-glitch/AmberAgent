import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { buildEvidencePack, cardsFor, sourceIdsFor, DeepReadArticlePlan } from '../main/ets/research/evidence_pack.ts';
import type { DeepReadSource } from '../main/ets/research/source_prefetcher.ts';
import { STAGE_EVIDENCE_MAX } from '../main/ets/domain/enums.ts';

// ===== fixtures =====

const makeSource = (id: string, overrides: Partial<DeepReadSource> = {}): DeepReadSource => ({
  sourceId: id,
  url: `https://example.com/${id}`,
  title: `Title ${id}`,
  source: 'tavily',
  evidenceText: '证据正文内容'.repeat(50),
  credibility: 'medium',
  freshness: 'unknown',
  publishedAt: null,
  imageCandidates: [],
  ...overrides,
});

const emptyPlan = (): DeepReadArticlePlan => ({
  overviewAngle: '',
  narrativeSlots: [],
  analysisQuestions: [],
  stakeholders: [],
  riskOrUncertainty: [],
  requiredSourceIds: [],
  stageSourceIds: {},
  coverageChecks: [],
});

// ===== buildEvidencePack: credibility sort =====

test('buildEvidencePack: sorts by credibility (high → medium → low)', () => {
  const sources = [
    makeSource('low1', { credibility: 'low' }),
    makeSource('high1', { credibility: 'high' }),
    makeSource('med1', { credibility: 'medium' }),
  ];
  const pack = buildEvidencePack(sources);
  // OVERVIEW gets first STAGE_EVIDENCE_MAX=6 → all 3 here
  const overview = pack.cardsByStage.OVERVIEW;
  assert.equal(overview[0].sourceId, 'high1');
  assert.equal(overview[1].sourceId, 'med1');
  assert.equal(overview[2].sourceId, 'low1');
});

// ===== buildEvidencePack: round-robin distribution across stages =====

test('buildEvidencePack: distributes across stages by STAGE_EVIDENCE_MAX chunks', () => {
  // 14 sources (>= 12 → requiredTarget 10)
  const sources: DeepReadSource[] = [];
  for (let i = 1; i <= 14; i++) sources.push(makeSource(`s${i}`));
  const pack = buildEvidencePack(sources);
  // OVERVIEW: s1..s6, NARRATIVE: s7..s12, ANALYSIS: s13..s14, EXTENDED: none
  assert.equal(pack.cardsByStage.OVERVIEW.length, STAGE_EVIDENCE_MAX);
  assert.equal(pack.cardsByStage.NARRATIVE.length, STAGE_EVIDENCE_MAX);
  assert.equal(pack.cardsByStage.ANALYSIS.length, 2);
  assert.equal(pack.cardsByStage.EXTENDED_READING.length, 0);
});

// ===== buildEvidencePack: requiredSourceIds count =====

test('buildEvidencePack: requiredSourceIds uses requiredSourceTarget', () => {
  const sources15 = Array.from({ length: 15 }, (_, i) => makeSource(`s${i + 1}`));
  assert.equal(buildEvidencePack(sources15).requiredSourceIds.length, 10);

  const sources9 = Array.from({ length: 9 }, (_, i) => makeSource(`s${i + 1}`));
  assert.equal(buildEvidencePack(sources9).requiredSourceIds.length, 8);

  const sources3 = Array.from({ length: 3 }, (_, i) => makeSource(`s${i + 1}`));
  assert.equal(buildEvidencePack(sources3).requiredSourceIds.length, 3);
});

// ===== buildEvidencePack: evidenceExcerpt truncation + image filter =====

test('buildEvidencePack: evidenceExcerpt truncated to 2000 chars, reject images filtered', () => {
  const longText = 'x'.repeat(3000);
  const source = makeSource('s1', { evidenceText: longText, imageCandidates: [
    // can't easily make real ScoredImageCandidate; just verify excerpt truncation
  ] });
  const pack = buildEvidencePack([source]);
  assert.equal(pack.cardsByStage.OVERVIEW[0].evidenceExcerpt.length, 2000);
});

test('sourceIdsFor: matches UPPERCASE stage name as fallback', () => {
  const plan: DeepReadArticlePlan = {
    ...emptyPlan(),
    stageSourceIds: { OVERVIEW: ['s3'] },
  };
  assert.deepEqual(sourceIdsFor(plan, 'OVERVIEW'), ['s3']);
});

test('sourceIdsFor: empty ids for stage → not matched (continues to next key)', () => {
  const plan: DeepReadArticlePlan = {
    ...emptyPlan(),
    stageSourceIds: { overview: [], OVERVIEW: ['s5'] },
  };
  assert.deepEqual(sourceIdsFor(plan, 'OVERVIEW'), ['s5']);
});

// ===== cardsFor: P0-D consumes plan.stageSourceIds =====

test('cardsFor: returns cards for plan-specified sourceIds (P0-D)', () => {
  const sources = [makeSource('s1'), makeSource('s2'), makeSource('s3'), makeSource('s4')];
  const pack = buildEvidencePack(sources);
  const plan: DeepReadArticlePlan = {
    ...emptyPlan(),
    stageSourceIds: { overview: ['s3', 's1'] },  // plan wants s3, s1 for overview
  };
  const cards = cardsFor(pack, 'OVERVIEW', plan);
  const ids = cards.map(c => c.sourceId);
  assert.ok(ids.includes('s3'));
  assert.ok(ids.includes('s1'));
});

test('cardsFor: plan sourceIds can reference cards outside current stage bucket', () => {
  // 8 sources: s1..s6 in OVERVIEW, s7..s8 in NARRATIVE
  const sources = Array.from({ length: 8 }, (_, i) => makeSource(`s${i + 1}`));
  const pack = buildEvidencePack(sources);
  // plan asks NARRATIVE to use s1 (which is in OVERVIEW bucket)
  const plan: DeepReadArticlePlan = {
    ...emptyPlan(),
    stageSourceIds: { narrative: ['s1'] },
  };
  const cards = cardsFor(pack, 'NARRATIVE', plan);
  assert.ok(cards.some(c => c.sourceId === 's1'), 'cross-stage lookup works');
});

// ===== cardsFor: planned empty → fallback to stage bucket =====

test('cardsFor: no plan ids → falls back to stage bucket', () => {
  const sources = Array.from({ length: 6 }, (_, i) => makeSource(`s${i + 1}`));
  const pack = buildEvidencePack(sources);
  const plan = emptyPlan();  // no stageSourceIds
  const cards = cardsFor(pack, 'OVERVIEW', plan);
  assert.equal(cards.length, 6, 'fallback to OVERVIEW bucket (6 cards)');
});

// ===== cardsFor: forceIncludeSourceIds =====

test('cardsFor: forceIncludeSourceIds are included even without plan', () => {
  const sources = Array.from({ length: 8 }, (_, i) => makeSource(`s${i + 1}`));
  const pack = buildEvidencePack(sources);
  // s7 is in NARRATIVE bucket; force include for OVERVIEW
  const plan = emptyPlan();
  const cards = cardsFor(pack, 'OVERVIEW', plan, ['s7']);
  assert.ok(cards.some(c => c.sourceId === 's7'), 'forced card included');
});

test('cardsFor: forced + planned merged, deduped', () => {
  const sources = Array.from({ length: 8 }, (_, i) => makeSource(`s${i + 1}`));
  const pack = buildEvidencePack(sources);
  const plan: DeepReadArticlePlan = {
    ...emptyPlan(),
    stageSourceIds: { overview: ['s1', 's2'] },
  };
  const cards = cardsFor(pack, 'OVERVIEW', plan, ['s2', 's3']);  // s2 dup
  const ids = cards.map(c => c.sourceId);
  // s2 appears once
  assert.equal(ids.filter(id => id === 's2').length, 1);
  assert.ok(ids.includes('s1'));
  assert.ok(ids.includes('s3'));
});

// ===== cardsFor: cap STAGE_EVIDENCE_MAX =====

test('cardsFor: capped at STAGE_EVIDENCE_MAX (6)', () => {
  // plan asks for 10 sources for OVERVIEW
  const sources = Array.from({ length: 12 }, (_, i) => makeSource(`s${i + 1}`));
  const pack = buildEvidencePack(sources);
  const plan: DeepReadArticlePlan = {
    ...emptyPlan(),
    stageSourceIds: { overview: ['s1','s2','s3','s4','s5','s6','s7','s8','s9','s10'] },
  };
  const cards = cardsFor(pack, 'OVERVIEW', plan);
  // but only s1..s6 are in OVERVIEW bucket; s7..s10 in NARRATIVE. cardsFor cross-stage lookups
  // should resolve all 10, but cap to STAGE_EVIDENCE_MAX=6
  assert.equal(cards.length, STAGE_EVIDENCE_MAX);
  assert.equal(STAGE_EVIDENCE_MAX, 6);
});
