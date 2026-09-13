// Isolated probe: real deepread domain logic, no network/model/filesystem.
// Hypothesis: buildEvidencePack slices sources contiguously per stage, so later
// stages (ANALYSIS / EXTENDED_READING) starve when MAX_SOURCES=12.
import { buildEvidencePack, cardsFor } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/src/main/ets/research/evidence_pack.ts';
import { fallbackPlan } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/src/main/ets/research/article_plan.ts';
import { STAGE_ORDER, STAGE_EVIDENCE_MAX, MAX_SOURCES } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/src/main/ets/domain/enums.ts';
import type { DeepReadSource } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/src/main/ets/research/source_prefetcher.ts';

const mk = (i: number): DeepReadSource => ({
  sourceId: `src-${i}`, url: `https://example.com/${i}`, title: `t${i}`, source: 'mock',
  evidenceText: `evidence body ${i}`, credibility: 'high', freshness: 'unknown',
  publishedAt: null, imageCandidates: [],
});

console.log('STAGE_EVIDENCE_MAX=', STAGE_EVIDENCE_MAX, 'MAX_SOURCES=', MAX_SOURCES);
for (const n of [1, 4, 6, 12, 18, 24]) {
  const sources = Array.from({ length: n }, (_, k) => mk(k + 1));
  const pack = buildEvidencePack(sources);
  const plan = fallbackPlan('topic', pack);
  const perStage = STAGE_ORDER.map(s => `${s}=${pack.cardsByStage[s].length}`);
  const perCardsFor = STAGE_ORDER.map(s => `${s}=${cardsFor(pack, s, plan).length}`);
  console.log(`n=${n} bucket[${perStage.join(' ')}] cardsFor[${perCardsFor.join(' ')}]`);
}
