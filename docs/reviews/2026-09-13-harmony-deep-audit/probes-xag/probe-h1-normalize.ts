// Independent H1 counter-check: real evidence_pack + article_plan.
// Question: is "ANALYSIS/EXTENDED_READING evidence = 0 when n<=12" really the NORMAL
// case, or only the fallbackPlan branch? Test normalizePlan on parsed plans.
import { buildEvidencePack, cardsFor } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/src/main/ets/research/evidence_pack.ts';
import { fallbackPlan, normalizePlan } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/src/main/ets/research/article_plan.ts';
import { STAGE_ORDER } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/src/main/ets/domain/enums.ts';
import type { DeepReadSource } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/src/main/ets/research/source_prefetcher.ts';

const mk = (i: number): DeepReadSource => ({
  sourceId: `s${i}`, url: `https://example.com/${i}`, title: `t${i}`, source: 'mock',
  evidenceText: `evidence body ${i}`.repeat(20), credibility: 'medium', freshness: 'unknown',
  publishedAt: null, imageCandidates: [],
});
const show = (name: string, n: number, plan: any) => {
  const pack = buildEvidencePack(Array.from({ length: n }, (_, k) => mk(k + 1)));
  const per = STAGE_ORDER.map(s => `${s}=${cardsFor(pack, s, plan).length}`);
  console.log(`${name} n=${n} cardsFor[${per.join(' ')}]`);
};
for (const n of [6, 12]) {
  const sources = Array.from({ length: n }, (_, k) => mk(k + 1));
  const pack = buildEvidencePack(sources);
  show('fallbackPlan      ', n, fallbackPlan('topic', pack));
  // parsed plan with NO stage_source_ids (LLM forgot the field)
  show('normalize(empty)  ', n, normalizePlan({}, 'topic', pack));
  // parsed plan that only fills overview (realistic partial LLM output)
  show('normalize(ovOnly) ', n, normalizePlan({ stage_source_ids: { overview: ['s1','s2'] } }, 'topic', pack));
  // parsed plan with all four stages explicit, analysis uses mid ids
  show('normalize(all4)   ', n, normalizePlan({ stage_source_ids: {
    overview: ['s1','s2'], narrative: ['s3','s4'], analysis: ['s5','s6'], extended_reading: ['s7','s8'],
  } }, 'topic', pack));
}
