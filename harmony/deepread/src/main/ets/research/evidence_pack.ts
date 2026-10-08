// Evidence Pack — 把 prefetch 的 sources 按 stage 分桶 + cardsFor 消费 plan.stageSourceIds
// 照搬 Android DeepReadResearchHarness.kt
//
// cardsFor(stage, plan) 是 P0-D 关键:必须消费 plan.stageSourceIds 路由 evidence。
// buildEvidencePack 的分桶逻辑是 MVP(简化版):Android 用 tag-based stageScore,
// 这里用 credibility 排序 + 按 STAGE_EVIDENCE_MAX 分片(够 spike;tag scoring 留增量)。

import type { DeepReadSource, SourceCredibility, SourceFreshness } from './source_prefetcher.ts';
import type { ScoredImageCandidate } from './image_scorer.ts';
import {
  STAGE_ORDER, STAGE_EVIDENCE_MAX,
} from '../domain/enums.ts';
import type { DeepReadGenerationStage } from '../domain/enums.ts';

export interface EvidenceCard {
  sourceId: string;
  url: string;
  title: string;
  source: string;
  credibility: SourceCredibility;
  freshness: SourceFreshness;
  publishedAt: string | null;
  evidenceExcerpt: string;   // 截断的正文片段
  imageCandidates: ScoredImageCandidate[];
}

export interface DeepReadEvidencePack {
  allSources: DeepReadSource[];
  /** Stable output.sources numbers, independent of evidence relevance ordering. */
  sourceNumbers?: Record<string, number>;
  cardsByStage: Record<DeepReadGenerationStage, EvidenceCard[]>;
  requiredSourceIds: string[];
}

export interface DeepReadArticlePlan {
  overviewAngle: string;
  narrativeSlots: string[];
  analysisQuestions: string[];
  stakeholders: string[];
  riskOrUncertainty: string[];
  requiredSourceIds: string[];
  stageSourceIds: Record<string, string[]>;
  coverageChecks: string[];
}

const emptyStageCards = (): Record<DeepReadGenerationStage, EvidenceCard[]> => ({
  OVERVIEW: [], NARRATIVE: [], ANALYSIS: [], EXTENDED_READING: [],
});

const credibilityOrder: Record<SourceCredibility, number> = { high: 0, medium: 1, low: 2 };

// 照搬 Android DeepReadResearchHarness.kt:499-504 sourceIdsFor
// keys 顺序:[stageKey, name.lowercase, name] — 取第一个非空
const STAGE_KEY: Record<DeepReadGenerationStage, string> = {
  OVERVIEW: 'overview',
  NARRATIVE: 'narrative',
  ANALYSIS: 'analysis',
  EXTENDED_READING: 'extended_reading',
};

export const sourceIdsFor = (
  plan: DeepReadArticlePlan,
  stage: DeepReadGenerationStage,
): string[] => {
  const candidates = [
    STAGE_KEY[stage],            // 'overview' / 'extended_reading'
    stage.toLowerCase(),          // 'overview' / 'extended_reading' (同 stageKey for these)
    stage,                        // 'OVERVIEW' / 'EXTENDED_READING'
  ];
  for (const key of candidates) {
    const ids = plan.stageSourceIds[key];
    if (ids && ids.length > 0) return ids;
  }
  return [];
};

// requiredSourceTarget — 照搬 Android DeepReadEvidencePack.requiredSourceTarget()
export const requiredSourceTarget = (cardCount: number): number => {
  if (cardCount >= 12) return 10;
  if (cardCount >= 8) return 8;
  return cardCount;
};

// buildEvidencePack — 本地计算,无 LLM 调用
// MVP 分桶:credibility 排序 + 按 STAGE_EVIDENCE_MAX 轮转分片到各 stage
export const buildEvidencePack = (sources: DeepReadSource[]): DeepReadEvidencePack => {
  if (sources.length === 0) {
    return { allSources: [], cardsByStage: emptyStageCards(), requiredSourceIds: [] };
  }

  // 给每个 source 截取 evidenceExcerpt + 过滤 reject 图片
  const cards: EvidenceCard[] = sources.map(s => ({
    sourceId: s.sourceId,
    url: s.url,
    title: s.title,
    source: s.source,
    credibility: s.credibility,
    freshness: s.freshness,
    publishedAt: s.publishedAt,
    evidenceExcerpt: s.evidenceText.slice(0, 2_000),
    imageCandidates: s.imageCandidates
      .filter(c => c.confidence !== 'reject')
      .slice(0, 4),
  }));

  // 按 credibility 排序(high 优先),稳定排序保留原顺序
  const sorted = [...cards].sort((a, b) => {
    return credibilityOrder[a.credibility] - credibilityOrder[b.credibility];
  });

  // 按 STAGE_EVIDENCE_MAX 轮转分片到各 stage
  const cardsByStage = emptyStageCards();
  let idx = 0;
  for (const stage of STAGE_ORDER) {
    cardsByStage[stage] = sorted.slice(idx, idx + STAGE_EVIDENCE_MAX);
    idx += STAGE_EVIDENCE_MAX;
  }

  // requiredSourceIds:top credibility 的 requiredSourceTarget() 个
  const targetCount = Math.min(requiredSourceTarget(sorted.length), sorted.length);
  const requiredSourceIds = sorted.slice(0, targetCount).map(c => c.sourceId);

  return { allSources: sources, cardsByStage, requiredSourceIds };
};

// allCards — 收集 pack 所有 stage 的 cards(供 cardsFor 跨 stage 查找)
const allCards = (pack: DeepReadEvidencePack): EvidenceCard[] => {
  const all: EvidenceCard[] = [];
  for (const stage of STAGE_ORDER) {
    all.push(...pack.cardsByStage[stage]);
  }
  return all;
};

// cardsFor — 照搬 Android DeepReadEvidencePack.cardsFor (DeepReadResearchHarness.kt:439-452)
// 逻辑:forced + planned.ifEmpty { stageCards[stage] },distinctBy(sourceId),take(STAGE_EVIDENCE_MAX)
// P0-D:消费 plan.stageSourceIds 路由 evidence
export const cardsFor = (
  pack: DeepReadEvidencePack,
  stage: DeepReadGenerationStage,
  plan: DeepReadArticlePlan,
  forceIncludeSourceIds: string[] = [],
): EvidenceCard[] => {
  // byId 跨 stage 查找(Plan 指定的 source 可能不在当前 stage bucket)
  const byId = new Map<string, EvidenceCard>();
  for (const card of allCards(pack)) byId.set(card.sourceId, card);

  const forced = forceIncludeSourceIds
    .map(id => byId.get(id))
    .filter((c): c is EvidenceCard => c !== undefined);

  const plannedIds = sourceIdsFor(plan, stage);
  const planned = plannedIds
    .map(id => byId.get(id))
    .filter((c): c is EvidenceCard => c !== undefined);

  // planned 为空 → fallback 到 stage bucket(Android ifEmpty 语义)
  const effectivePlanned = planned.length > 0 ? planned : (pack.cardsByStage[stage] ?? []);

  // forced + effectivePlanned,distinctBy(sourceId)
  const result: EvidenceCard[] = [];
  const seen = new Set<string>();
  for (const card of [...forced, ...effectivePlanned]) {
    if (seen.has(card.sourceId)) continue;
    seen.add(card.sourceId);
    result.push(card);
  }
  return result.slice(0, STAGE_EVIDENCE_MAX);
};
