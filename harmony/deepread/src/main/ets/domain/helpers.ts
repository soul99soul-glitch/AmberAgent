// Deep Read 纯函数辅助 — HarmonyOS port of Android DeepReadModels.kt helpers
import { DeepReadOutput, DeepReadSectionState } from './models.ts';
import { STAGE_ORDER, DeepReadGenerationStage, DeepReadSectionStatus } from './enums.ts';

export const statusOf = (o: DeepReadOutput, stage: DeepReadGenerationStage): DeepReadSectionStatus => {
  return o.sectionStates[stage]?.status ?? 'PENDING';
};

export const sectionsReady = (o: DeepReadOutput): boolean => {
  // 严格:4 个 stage 都必须存在且 READY (Android DeepReadModels.kt:109-110)
  return STAGE_ORDER.every(s => o.sectionStates[s]?.status === 'READY');
};

export const hasAnyReadySection = (o: DeepReadOutput): boolean => {
  return STAGE_ORDER.some(s => o.sectionStates[s]?.status === 'READY');
};

export const isComplete = (o: DeepReadOutput): boolean => {
  // Android isComplete = generationComplete && isVerifiedComplete()
  // isVerifiedComplete = sectionsReady() (verificationState 不参与,design 0.1)
  return o.generationComplete && sectionsReady(o);
};

export const firstFailureMessage = (o: DeepReadOutput): string | null => {
  // Android sectionFailureMessage:声明顺序第一个 FAILED
  for (const s of STAGE_ORDER) {
    const state = o.sectionStates[s];
    if (state?.status === 'FAILED') return state.errorMessage;
  }
  return null;
};

export const withSectionStatus = (
  o: DeepReadOutput,
  stage: DeepReadGenerationStage,
  status: DeepReadSectionStatus,
  errorMessage: string | null = null,
): DeepReadOutput => {
  const newState: DeepReadSectionState = { status, errorMessage };
  return { ...o, sectionStates: { ...o.sectionStates, [stage]: newState } };
};

export const withSectionQuality = (
  o: DeepReadOutput,
  stage: DeepReadGenerationStage,
  quality: string,
): DeepReadOutput => {
  return { ...o, sectionQualities: { ...o.sectionQualities, [stage]: quality } };
};

export const withPhase = (
  o: DeepReadOutput,
  phase: DeepReadOutput['generationPhase'],
  extra: Partial<DeepReadOutput> = {},
): DeepReadOutput => {
  return { ...o, generationPhase: phase, ...extra };
};

export const withInferredSectionStates = (o: DeepReadOutput): DeepReadOutput => {
  // Android DeepReadModels.kt:115-141
  // sectionStates 已填充 → no-op (同引用早返回)
  if (Object.keys(o.sectionStates).length > 0) return o;

  const legacyComplete = o.generationComplete;

  // Android 用 isNotBlank() — 任意非空白内容即视为存在,而非长度阈值
  const overviewReady = o.summary.trim().length > 0;
  const narrativeReady =
    (o.timeline?.filter(e => e.event.trim().length > 0).length ?? 0) >= 1 ||
    (o.corePoints?.filter(p => p.point.trim().length > 0).length ?? 0) >= 1;
  const analysisReady =
    (o.analysis.coreDispute?.trim().length ?? 0) > 0 ||
    (o.analysis.implications?.trim().length ?? 0) > 0 ||
    o.analysis.perspectives.some(p => p.viewpoint.trim().length > 0) ||
    o.analysis.quotes.some(q => q.text.trim().length > 0);
  const readingReady = o.extendedReading.length > 0 || o.references.length > 0;

  const stageReady: Record<DeepReadGenerationStage, boolean> = {
    OVERVIEW: overviewReady,
    NARRATIVE: narrativeReady,
    ANALYSIS: analysisReady,
    EXTENDED_READING: readingReady,
  };
  const states: Record<string, DeepReadSectionState> = {};
  for (const s of STAGE_ORDER) {
    if (legacyComplete || stageReady[s]) {
      states[s] = { status: 'READY', errorMessage: null };
    }
  }

  const merged: DeepReadOutput = { ...o, sectionStates: states };
  const recomputedComplete = legacyComplete && sectionsReady(merged);
  return { ...merged, generationComplete: recomputedComplete };
};
