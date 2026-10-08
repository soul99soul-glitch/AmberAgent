import { hasSynthesisBody } from './synthesis_article.ts';
// Deep Read 纯函数辅助 — HarmonyOS port of Android DeepReadModels.kt helpers
import { DeepReadOutput, DeepReadSectionState, DeepReadUncertainty } from './models.ts';
import { STAGE_ORDER, DeepReadGenerationStage, DeepReadSectionStatus, IMAGE_CONFIDENCE } from './enums.ts';

export const statusOf = (o: DeepReadOutput, stage: DeepReadGenerationStage): DeepReadSectionStatus => {
  return o.sectionStates[stage]?.status ?? 'PENDING';
};

export const sectionsReady = (o: DeepReadOutput): boolean => {
  if (o.templateArticle !== undefined) return hasSynthesisBody(o.templateArticle);
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

// Android firstFailedStage:第一个 FAILED 段(供"仅重试这一段")
export const firstFailedStage = (o: DeepReadOutput): DeepReadGenerationStage | null => {
  for (const s of STAGE_ORDER) {
    if (o.sectionStates[s]?.status === 'FAILED') return s;
  }
  return null;
};

export const withSectionStatus = (
  o: DeepReadOutput,
  stage: DeepReadGenerationStage,
  status: DeepReadSectionStatus,
  errorMessage: string | null = null,
): DeepReadOutput => {
  // Android DeepReadModels.kt:70-79:更新 sectionStates 后重算 generationComplete
  const newState: DeepReadSectionState = { status, errorMessage };
  const merged: DeepReadOutput = { ...o, sectionStates: { ...o.sectionStates, [stage]: newState } };
  return { ...merged, generationComplete: o.generationComplete && sectionsReady(merged) };
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
  if (o.templateArticle !== undefined || Object.keys(o.sectionStates).length > 0) return o;

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
  const readingReady = (o.sources?.length ?? 0) > 0 || o.extendedReading.length > 0 || o.references.length > 0;

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

const isHttpUrl = (url: string): boolean => {
  return url.startsWith('http://') || url.startsWith('https://');
};

export const verifiedImageUrls = (o: DeepReadOutput): Set<string> => {
  // Android DeepReadModels.kt:334-339
  const result = new Set<string>();
  for (const a of o.imageAssets) {
    if (a.confidence !== IMAGE_CONFIDENCE.REJECT && isHttpUrl(a.url)) {
      result.add(a.url);
    }
  }
  return result;
};

export const displayHeroImageUrl = (o: DeepReadOutput): string | null => {
  // Android DeepReadModels.kt:341-347
  const safe = verifiedImageUrls(o);
  if (o.heroImageUrl && safe.has(o.heroImageUrl) && o.heroImageConfidence === IMAGE_CONFIDENCE.HERO) {
    return o.heroImageUrl;
  }
  for (const a of o.imageAssets) {
    if (safe.has(a.url)) return a.url;
  }
  return null;
};

export const displayHeroCaption = (o: DeepReadOutput, imageUrl: string | null = displayHeroImageUrl(o)): string | null => {
  // Android DeepReadModels.kt:349-353
  if (imageUrl === null) return null;
  if (o.heroImageUrl === imageUrl && o.heroCaption && o.heroCaption.trim().length > 0) {
    return o.heroCaption;
  }
  for (const a of o.imageAssets) {
    if (a.url === imageUrl && a.caption && a.caption.trim().length > 0) {
      return a.caption;
    }
  }
  return null;
};

// Android DeepReadModels.kt:374-387 lowInformationFallbackPhrases
const LOW_INFORMATION_FALLBACK_PHRASES: string[] = [
  '当前可抓取信息仍偏薄',
  '可用信息不足以形成稳定脉络',
  '抓取摘要不足',
  '来源提供了相关背景线索',
  '来源链接统一收在扩展阅读',
  '避免把来源列表误写成深度分析',
  '后续深读应围绕',
  '模型输出格式不稳定',
  '目前来源未覆盖',
  '更多材料',
  '链接见扩展阅读',
  '来源见扩展阅读',
];

const containsAny = (text: string, phrases: string[]): boolean => {
  return phrases.some(p => text.includes(p));
};

export const hasEnoughChinese = (text: string): boolean => {
  // Android DeepReadModels.kt:324-332
  const cjk = (text.match(/[\u4e00-\u9fff]/g) ?? []).length;
  const latin = (text.match(/[a-zA-Z]/g) ?? []).length;
  if (cjk >= 80) return true;
  if (cjk < 12 && latin > 30) return false;
  const denom = Math.max(cjk + latin, 1);  // 防 0/0=NaN(Android coerceAtLeast(1))
  return cjk / denom >= 0.35;
};

// 拼接文章全文本用于语言检查 — 对应 Android articleTextForQualityCheck()
const articleTextForQualityCheck = (o: DeepReadOutput): string => {
  const parts: string[] = [o.summary, o.bottomLine ?? ''];
  parts.push(...(o.impacts ?? []).map(impact => `${impact.target} ${impact.effect}`));
  parts.push(...(o.watch ?? []));
  parts.push(...o.keyEntities);
  if (o.timeline) parts.push(...o.timeline.map(t => t.event));
  if (o.corePoints) parts.push(...o.corePoints.map(p => `${p.point} ${p.supporting ?? ''}`));
  parts.push(o.analysis.coreDispute ?? '');
  parts.push(o.analysis.implications ?? '');
  parts.push(...o.analysis.perspectives.map(p => p.viewpoint));
  parts.push(...o.analysis.quotes.map(q => q.text));
  return parts.join(' ');
};

export const hasReadableArticle = (o: DeepReadOutput): boolean => {
  if (o.templateArticle !== undefined) return hasSynthesisBody(o.templateArticle);
  // Android DeepReadModels.kt:309-322 — 注意:Android 用 AND 逻辑(三项都满足)
  if (o.summary.trim().length < 80) return false;
  // Android :311 检查 visibleTextForLanguageCheck() 全文,不只 summary
  const visibleText = articleTextForQualityCheck(o);
  if (containsAny(visibleText, LOW_INFORMATION_FALLBACK_PHRASES)) return false;

  const hasTimeline = (o.timeline?.filter(e => e.event.trim().length >= 20).length ?? 0) >= 2;
  const hasCorePoints = (o.corePoints?.filter(p => p.point.trim().length >= 10 && (p.supporting?.trim().length ?? 0) >= 30).length ?? 0) >= 2;
  const hasAnalysis =
    (o.analysis.coreDispute?.trim().length ?? 0) >= 40 ||
    (o.analysis.implications?.trim().length ?? 0) >= 40 ||
    o.analysis.perspectives.filter(p => p.viewpoint.trim().length >= 30).length >= 2;
  return hasTimeline && hasCorePoints && hasAnalysis;
};

/** Presentation gate only: partial content stays visible without claiming completion or research quality. */
export const hasDisplayableDeepReadOutput = (o: DeepReadOutput): boolean => {
  if (o.templateArticle !== undefined && hasSynthesisBody(o.templateArticle)) return true;
  return (o.bottomLine?.trim().length ?? 0) > 0
    || (o.impacts?.some(impact => impact.effect.trim().length > 0) ?? false)
    || (o.watch?.some(item => item.trim().length > 0) ?? false)
    || normalizedDeepReadUncertainties(o).some(item => item.claim.trim().length > 0)
    || (o.sources?.some(link => link.title.trim().length > 0 || link.url.trim().length > 0) ?? false)
    || o.summary.trim().length > 0 || o.keyEntities.some(entity => entity.trim().length > 0)
    || (o.timeline?.some(event => event.event.trim().length > 0 || isHttpUrl(event.imageUrl ?? '')) ?? false)
    || (o.corePoints?.some(point => point.point.trim().length > 0 || !!point.supporting?.trim()
      || isHttpUrl(point.imageUrl ?? '')) ?? false)
    || (o.analysis.coreDispute?.trim().length ?? 0) > 0
    || (o.analysis.implications?.trim().length ?? 0) > 0
    || o.analysis.perspectives.some(perspective => perspective.viewpoint.trim().length > 0)
    || o.analysis.quotes.some(quote => quote.text.trim().length > 0)
    || o.extendedReading.some(link => link.title.trim().length > 0 || link.url.trim().length > 0)
    || o.references.some(link => link.title.trim().length > 0 || link.url.trim().length > 0)
    || displayHeroImageUrl(o) !== null
    || (o.diagram !== null && (o.diagram.title.trim().length > 0
      || (o.diagram.reason?.trim().length ?? 0) > 0
      || o.diagram.nodes.some(node => node.label.trim().length > 0 || !!node.note?.trim())
      || (o.diagram.caption?.trim().length ?? 0) > 0));
};

/** Old articles store plain strings; consumers always receive claim/status objects. */
export const normalizedDeepReadUncertainties = (o: DeepReadOutput): DeepReadUncertainty[] => {
  const result: DeepReadUncertainty[] = [];
  for (const item of o.uncertainties ?? []) {
    if (typeof item === 'string') {
      if (item.trim().length > 0) result.push({ claim: item, status: '' });
    } else if (item !== null && typeof item.claim === 'string' && item.claim.trim().length > 0) {
      const status = item.status === 'single_source' || item.status === 'conflicting' || item.status === 'pending_official' ? item.status : '';
      result.push({ claim: item.claim, status });
    }
  }
  return result;
};
