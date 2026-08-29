// SectionWriterTools — 照搬 Android DeepReadSectionWriterTools.kt
//
// 7 个 tool:deep_read_write_{overview,narrative,analysis,extended_reading,visuals,diagram} + deep_read_finish
// 所有 tool 共享 update(transform) 原语:writeMutex 串行(ArkTS 单线程 async,用闭包状态)
//
// ArkTS 约定:factory function createSectionWriterTools,闭包持有 DeepReadOutput 状态,
// 不用 class mutation(对照 observable.ts / source_prefetch.ts)。不可变更新用 spread。
//
// JSON 解析边界:tool input 是模型返回的 JSON 字符串,这里允许 Record<string, unknown>
// 解析(对照 json_schema_validator.ts 的解析边界模式)。

import type { UIMessagePartText } from './message.ts';
import { makeSuccessOutput } from './tool_execution.ts';
import type { ToolDefinition } from './tool_execution.ts';
import type { DeepReadOutput } from '../domain/models.ts';
import { makeEmptyDeepReadOutput } from '../domain/models.ts';
import type { ScoredImageCandidate } from '../research/image_scorer.ts';
import {
  statusOf, withSectionStatus, withSectionQuality, sectionsReady,
} from '../domain/helpers.ts';
import type { DeepReadGenerationStage } from '../domain/enums.ts';
import {
  WRITER_TOOL_NAMES, VISUALS_TOOL_NAME, DIAGRAM_TOOL_NAME, FINISH_TOOL_NAME,
  IMAGE_CONFIDENCE, STAGE_ORDER,
  OVERVIEW_SUMMARY_MIN_CHARS, OVERVIEW_SUMMARY_STORAGE_MAX_CHARS,
  MAX_DIAGRAM_NODES, MAX_LINEAR_DIAGRAM_EDGES, MAX_RELATION_DIAGRAM_EDGES,
  DIAGRAM_TITLE_MAX_CHARS, DIAGRAM_NODE_LABEL_MAX_CHARS, DIAGRAM_NODE_NOTE_MAX_CHARS,
  DIAGRAM_NODE_GROUP_MAX_CHARS, DIAGRAM_EDGE_LABEL_MAX_CHARS,
  TIMELINE_MAX, CORE_POINTS_MAX, PERSPECTIVES_MAX, QUOTES_MAX, IMAGE_ASSETS_MAX,
  READING_LINKS_REF_MAX, EXTENDED_READING_MAX, KEY_ENTITIES_MAX,
  TOPIC_TYPE_MAX, ENTITY_MAX_CHARS, READING_LINK_TITLE_MAX, READING_LINK_SOURCE_MAX,
  TIMELINE_EVENT_MAX, CORE_POINT_MAX, CORE_SUPPORTING_MAX,
  PERSPECTIVE_VIEWPOINT_MAX, PERSPECTIVE_HOLDER_MAX, QUOTE_TEXT_MAX, QUOTE_ATTR_MAX,
  ANALYSIS_DISPUTE_MAX, ANALYSIS_IMPLICATIONS_MAX, HERO_CAPTION_MAX, HERO_REASON_MAX,
} from '../domain/enums.ts';
import type {
  TimelineEvent, CorePoint, ReadingLink, Perspective, Quote, DeepAnalysis,
  DeepReadImageAsset, DiagramNode, DiagramEdge, DeepReadDiagram,
} from '../domain/models.ts';

// ===== 文本工具(照搬 Android cleanText / safeTake) =====

// safeTake — 照搬 Android:不切断 UTF-16 代理对(emoji 安全)
export const safeTake = (s: string, n: number): string => {
  if (n <= 0) return '';
  if (s.length <= n) return s;
  const cut = s.charCodeAt(n - 1) >= 0xD800 && s.charCodeAt(n - 1) <= 0xDBFF ? n - 1 : n;
  return s.substring(0, cut);
};

// cleanText — 照搬 Android:\s+ → 单空格 + trim + safeTake
export const cleanText = (s: string, max: number): string =>
  safeTake(s.replace(/\s+/g, ' ').trim(), max);

const isHttpUrl = (url: string): boolean =>
  url.startsWith('http://') || url.startsWith('https://');

// ===== JSON 解析边界(对照 json_schema_validator.ts) =====

const asObject = (input: string): Record<string, unknown> => {
  try {
    const parsed = JSON.parse(input);
    if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>;
    }
  } catch { /* 空 input → 空对象 */ }
  return {};
};

const objString = (o: Record<string, unknown>, name: string): string | null => {
  const v = o[name];
  if (typeof v !== 'string') return null;
  const trimmed = v.trim();
  return trimmed.length > 0 ? trimmed : null;
};

const objBool = (o: Record<string, unknown>, name: string): boolean | null => {
  const v = o[name];
  if (typeof v === 'boolean') return v;
  if (typeof v === 'string') {
    if (v === 'true') return true;
    if (v === 'false') return false;
  }
  return null;
};

const objArray = (o: Record<string, unknown>, name: string): unknown[] => {
  const v = o[name];
  return Array.isArray(v) ? v : [];
};

const objObjectList = (o: Record<string, unknown>, name: string): Record<string, unknown>[] => {
  return objArray(o, name).filter((x): x is Record<string, unknown> =>
    x !== null && typeof x === 'object' && !Array.isArray(x));
};

// stringList 从 array of string 提取(照搬 Android jsonPrimitive.contentOrNull)
const stringListFrom = (o: Record<string, unknown>, name: string, max: number): string[] => {
  const arr = objArray(o, name);
  const out: string[] = [];
  for (const el of arr) {
    if (typeof el === 'string') {
      const c = cleanText(el, max);
      if (c.length > 0) out.push(c);
    }
  }
  return out;
};

const urlString = (o: Record<string, unknown>, name: string): string | null => {
  const s = objString(o, name);
  return s !== null && isHttpUrl(s) ? s : null;
};

// ===== ReadingLink 解析(照搬 Android readingLinks) =====

const parseReadingLinks = (o: Record<string, unknown>, name: string): ReadingLink[] => {
  return objObjectList(o, name).map(obj => {
    const url = urlString(obj, 'url');
    if (url === null) return null;
    const title = objString(obj, 'title');
    const finalTitle = title !== null
      ? cleanText(title, READING_LINK_TITLE_MAX)
      : url.split('://')[1]?.split('/')[0] ?? url;
    const source = objString(obj, 'source');
    return {
      title: finalTitle,
      url,
      source: source !== null ? cleanText(source, READING_LINK_SOURCE_MAX) : null,
      publishedAt: null,
    } as ReadingLink;
  }).filter((x): x is ReadingLink => x !== null);
};

// ===== merge 辅助(照搬 Android mergeStrings/mergeReadingLinks/mergeImageAssets) =====

export const mergeStrings = (existing: string[], incoming: string[], limit: number): string[] => {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const raw of [...existing, ...incoming]) {
    const c = cleanText(raw, ENTITY_MAX_CHARS);
    if (c.length === 0) continue;
    const key = c.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(c);
    if (out.length >= limit) break;
  }
  return out;
};

const urlKey = (url: string): string => url.trim().replace(/\/+$/, '');

export const mergeReadingLinks = (existing: ReadingLink[], incoming: ReadingLink[], limit: number): ReadingLink[] => {
  const seen = new Set<string>();
  const out: ReadingLink[] = [];
  for (const link of [...existing, ...incoming]) {
    if (!isHttpUrl(link.url)) continue;
    const key = urlKey(link.url);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(link);
    if (out.length >= limit) break;
  }
  return out;
};

const mergeImageAssets = (existing: DeepReadImageAsset[], incoming: DeepReadImageAsset[], limit: number): DeepReadImageAsset[] => {
  const seen = new Set<string>();
  const out: DeepReadImageAsset[] = [];
  for (const asset of [...existing, ...incoming]) {
    if (!isHttpUrl(asset.url)) continue;
    const key = urlKey(asset.url);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(asset);
    if (out.length >= limit) break;
  }
  return out;
};

// ===== content 检查(照搬 Android hasOverviewContent 等) =====

const hasOverviewContent = (o: DeepReadOutput): boolean =>
  o.summary.trim().length >= OVERVIEW_SUMMARY_MIN_CHARS;

const hasNarrativeContent = (o: DeepReadOutput): boolean => {
  const timeline = o.timeline ?? [];
  if (timeline.some(e => e.event.trim().length >= 20)) return true;
  const corePoints = o.corePoints ?? [];
  return corePoints.some(p => p.point.trim().length >= 8 || (p.supporting?.trim().length ?? 0) >= 20);
};

const hasAnalysisContent = (o: DeepReadOutput): boolean => {
  if ((o.analysis.coreDispute?.trim().length ?? 0) >= 20) return true;
  if ((o.analysis.implications?.trim().length ?? 0) >= 20) return true;
  if (o.analysis.perspectives.some(p => p.viewpoint.trim().length >= 20)) return true;
  return o.analysis.quotes.some(q => q.text.trim().length >= 8);
};

const hasExtendedReadingContent = (o: DeepReadOutput): boolean =>
  o.extendedReading.some(l => isHttpUrl(l.url));

const statusReadyFor = (o: DeepReadOutput, stage: DeepReadGenerationStage): boolean => {
  switch (stage) {
    case 'OVERVIEW': return hasOverviewContent(o);
    case 'NARRATIVE': return hasNarrativeContent(o);
    case 'ANALYSIS': return hasAnalysisContent(o);
    case 'EXTENDED_READING': return hasExtendedReadingContent(o);
  }
};

// ===== writer 状态接口 =====

export interface WriterState {
  current: () => DeepReadOutput;
  writeCount: number;
  requiredWriteCount: number;
}

export interface SectionWriterTools extends WriterState {
  tools: (stages?: Set<DeepReadGenerationStage> | null) => ToolDefinition[];
  markPhase: (phase: DeepReadOutput['generationPhase']) => DeepReadOutput;
  markRunning: (stages: DeepReadGenerationStage[]) => DeepReadOutput;
  markFailed: (stage: DeepReadGenerationStage, message: string) => DeepReadOutput;
  markVisibleWrite: () => void;
  markRequiredWrite: () => void;
  writeFallbackSection: (
    stage: DeepReadGenerationStage,
    assistantText: string,
    sources: ReadingLink[],
    allowReadyRewrite: boolean,
  ) => DeepReadOutput;
  executeFinish: () => DeepReadOutput;
  /** 注入测试用:直接替换 current(不走 repository) */
  setCurrentForTest: (o: DeepReadOutput) => void;
}

// ===== factory(闭包捕获状态,ArkTS 友好) =====

export interface CreateSectionWriterToolsParams {
  topicId: string;
  topicTitle: string;
  imageCandidates: ScoredImageCandidate[];
  initialOutput?: DeepReadOutput;
}

export const createSectionWriterTools = (
  params: CreateSectionWriterToolsParams,
): SectionWriterTools => {
  let output: DeepReadOutput = params.initialOutput ?? makeEmptyDeepReadOutput();
  let writeCount = 0;
  let requiredWriteCount = 0;

  // update(transform) — 照搬 Android update():取 current → transform → 存(纯 node 无 repository,直接赋值)
  const update = (transform: (current: DeepReadOutput) => DeepReadOutput): DeepReadOutput => {
    const current = output;
    const next = transform(current);
    output = next;
    return next;
  };

  const markRequiredWrite = (): void => {
    writeCount += 1;
    requiredWriteCount += 1;
  };

  const markVisibleWrite = (): void => {
    writeCount += 1;
  };

  const candidateForUrl = (url: string): ScoredImageCandidate | null =>
    params.imageCandidates.find(c => c.url === url) ?? null;

  const isHeroCandidate = (url: string): boolean =>
    candidateForUrl(url)?.confidence === IMAGE_CONFIDENCE.HERO;

  // withCandidateEvidence — 照搬 Android:asset 必须在候选池且非 reject
  const withCandidateEvidence = (asset: DeepReadImageAsset): DeepReadImageAsset | null => {
    const candidate = candidateForUrl(asset.url);
    if (candidate === null) return null;
    if (candidate.confidence === IMAGE_CONFIDENCE.REJECT) return null;
    return {
      ...asset,
      source: asset.source ?? null,
      confidence: candidate.confidence,
      score: candidate.score,
      qualityHint: asset.qualityHint ?? candidate.confidence,
      selectionReason: asset.selectionReason ?? selectionReasonFor(candidate, params.topicTitle),
    };
  };

  // ===== overview tool =====
  const overviewTool = (): ToolDefinition => ({
    name: WRITER_TOOL_NAMES.OVERVIEW,
    description: 'Internal Deep Read writer. Write the source-backed overview section. UI only renders content written through this tool.',
    schema: { type: 'object', properties: { summary: { type: 'string' }, references: { type: 'array' } } },
    allowsAutoApproval: true,
    isHighRisk: false,
    execute: async (input: string): Promise<UIMessagePartText[]> => {
      const obj = asObject(input);
      const summaryRaw = objString(obj, 'summary');
      const summary = summaryRaw !== null ? cleanText(summaryRaw, OVERVIEW_SUMMARY_STORAGE_MAX_CHARS) : null;
      const references = parseReadingLinks(obj, 'references');
      const updated = update(current => {
        const topicTypeRaw = objString(obj, 'topic_type');
        const next: DeepReadOutput = {
          ...current,
          topicType: topicTypeRaw !== null ? cleanText(topicTypeRaw, TOPIC_TYPE_MAX) : current.topicType,
          summary: summary ?? current.summary,
          keyEntities: mergeStrings(current.keyEntities, stringListFrom(obj, 'key_entities', ENTITY_MAX_CHARS), KEY_ENTITIES_MAX),
          references: mergeReadingLinks(current.references, references, READING_LINKS_REF_MAX),
        };
        // 主字段不足:合法的 keyEntities/references 仍保留(不整回 current 丢弃),
        // 只是不标记 READY/必需写
        if (!hasOverviewContent(next)) return next;
        markRequiredWrite();
        return withSectionQuality(
          withSectionStatus(next, 'OVERVIEW', 'READY'),
          'OVERVIEW', 'STANDARD',
        );
      });
      if (statusOf(updated, 'OVERVIEW') === 'READY') {
        return [makeSuccessOutput({
          section: 'overview',
          generation_complete: updated.generationComplete && sectionsReady(updated),
          accepted: {
            summary_chars: updated.summary.trim().length,
            key_entities: updated.keyEntities.length,
            references: updated.references.length,
          },
        })];
      }
      const summaryChars = summary?.trim().length ?? 0;
      const required = summary === null || summary.trim().length === 0
        ? 'summary missing'
        : `summary too short: ${summaryChars}/${OVERVIEW_SUMMARY_MIN_CHARS}`;
      return [makeWriterOutput('missing_required_content', {
        section: 'overview',
        required,
        dropped: { summary: 1 },
        drop_reasons: [required],
      })];
    },
  });

  // ===== narrative tool =====
  const narrativeTool = (): ToolDefinition => ({
    name: WRITER_TOOL_NAMES.NARRATIVE,
    description: 'Internal Deep Read writer. Write timeline/story narrative data.',
    schema: { type: 'object', properties: { timeline: { type: 'array' } } },
    allowsAutoApproval: true,
    isHighRisk: false,
    execute: async (input: string): Promise<UIMessagePartText[]> => {
      const obj = asObject(input);
      const timeline = parseTimeline(obj).slice(0, TIMELINE_MAX);
      const corePoints = parseCorePoints(obj).slice(0, CORE_POINTS_MAX);
      const references = parseReadingLinks(obj, 'references');
      const rawTimelineCount = objObjectList(obj, 'timeline').length;
      const rawCorePointCount = objObjectList(obj, 'core_points').length;
      const updated = update(current => {
        const next: DeepReadOutput = {
          ...current,
          timeline: timeline.length > 0 ? timeline : current.timeline,
          corePoints: corePoints.length > 0 ? corePoints : current.corePoints,
          references: mergeReadingLinks(current.references, references, READING_LINKS_REF_MAX),
        };
        // 主字段不足:合法 timeline/references 仍保留,只不标 READY
        if (!hasNarrativeContent(next)) return next;
        markRequiredWrite();
        return withSectionQuality(
          withSectionStatus(next, 'NARRATIVE', 'READY'),
          'NARRATIVE', 'STANDARD',
        );
      });
      const droppedTimeline = Math.max(0, rawTimelineCount - timeline.length);
      const droppedCorePoints = Math.max(0, rawCorePointCount - corePoints.length);
      const dropReasons: string[] = [];
      if (rawTimelineCount > timeline.length) dropReasons.push(`timeline dropped: missing event or truncated limit=${TIMELINE_MAX}`);
      if (rawCorePointCount > corePoints.length) dropReasons.push(`core_points dropped: missing point or truncated limit=${CORE_POINTS_MAX}`);
      const feedback = {
        accepted: { timeline: timeline.length, core_points: corePoints.length, references: references.length },
        dropped: { ...(droppedTimeline > 0 ? { timeline: droppedTimeline } : {}), ...(droppedCorePoints > 0 ? { core_points: droppedCorePoints } : {}) },
        drop_reasons: dropReasons,
      };
      if (statusOf(updated, 'NARRATIVE') === 'READY') {
        return [makeSuccessOutput({ section: 'narrative', generation_complete: updated.generationComplete && sectionsReady(updated), ...feedback })];
      }
      return [makeWriterOutput('missing_required_content', { section: 'narrative', required: 'timeline or core_points', ...feedback })];
    },
  });

  // ===== analysis tool =====
  const analysisTool = (): ToolDefinition => ({
    name: WRITER_TOOL_NAMES.ANALYSIS,
    description: 'Internal Deep Read writer. Write the deep analysis section.',
    schema: { type: 'object', properties: { core_dispute: { type: 'string' }, perspectives: { type: 'array' }, implications: { type: 'string' }, quotes: { type: 'array' } } },
    allowsAutoApproval: true,
    isHighRisk: false,
    execute: async (input: string): Promise<UIMessagePartText[]> => {
      const obj = asObject(input);
      const updated = update(current => {
        const coreDisputeRaw = objString(obj, 'core_dispute');
        const implicationsRaw = objString(obj, 'implications');
        const perspectives = parsePerspectives(obj);
        const quotes = parseQuotes(obj);
        const nextAnalysis: DeepAnalysis = {
          coreDispute: coreDisputeRaw !== null ? cleanText(coreDisputeRaw, ANALYSIS_DISPUTE_MAX) : current.analysis.coreDispute,
          perspectives: perspectives.length > 0 ? perspectives : current.analysis.perspectives,
          implications: implicationsRaw !== null ? cleanText(implicationsRaw, ANALYSIS_IMPLICATIONS_MAX) : current.analysis.implications,
          quotes: quotes.length > 0 ? quotes : current.analysis.quotes,
        };
        const next: DeepReadOutput = {
          ...current,
          analysis: nextAnalysis,
          references: mergeReadingLinks(current.references, parseReadingLinks(obj, 'references'), READING_LINKS_REF_MAX),
        };
        // 主字段不足:合法 references 仍保留,只不标 READY
        if (!hasAnalysisContent(next)) return next;
        markRequiredWrite();
        return withSectionQuality(
          withSectionStatus(next, 'ANALYSIS', 'READY'),
          'ANALYSIS', 'STANDARD',
        );
      });
      if (statusOf(updated, 'ANALYSIS') === 'READY') {
        return [makeSuccessOutput({ section: 'analysis', generation_complete: updated.generationComplete && sectionsReady(updated) })];
      }
      return [makeWriterOutput('missing_required_content', { section: 'analysis', required: 'core_dispute, perspectives, implications, or quotes' })];
    },
  });

  // ===== extended_reading tool =====
  const extendedReadingTool = (): ToolDefinition => ({
    name: WRITER_TOOL_NAMES.EXTENDED_READING,
    description: 'Internal Deep Read writer. Write source-backed extended reading links and optional real image assets.',
    schema: { type: 'object', properties: { links: { type: 'array' } } },
    allowsAutoApproval: true,
    isHighRisk: false,
    execute: async (input: string): Promise<UIMessagePartText[]> => {
      const obj = asObject(input);
      const updated = update(current => {
        let links = parseReadingLinks(obj, 'links');
        if (links.length === 0) links = parseReadingLinks(obj, 'extended_reading');
        const incomingAssets = parseImageAssets(obj)
          .map(a => withCandidateEvidence(a))
          .filter((a): a is DeepReadImageAsset => a !== null);
        const next: DeepReadOutput = {
          ...current,
          extendedReading: mergeReadingLinks(current.extendedReading, links, EXTENDED_READING_MAX),
          references: mergeReadingLinks(current.references, links, READING_LINKS_REF_MAX),
          imageAssets: mergeImageAssets(current.imageAssets, incomingAssets, IMAGE_ASSETS_MAX),
        };
        if (!hasExtendedReadingContent(next)) return current;
        markRequiredWrite();
        return withSectionQuality(
          withSectionStatus(next, 'EXTENDED_READING', 'READY'),
          'EXTENDED_READING', 'STANDARD',
        );
      });
      if (statusOf(updated, 'EXTENDED_READING') === 'READY') {
        return [makeSuccessOutput({ section: 'extended_reading', generation_complete: updated.generationComplete && sectionsReady(updated) })];
      }
      return [makeWriterOutput('missing_required_content', { section: 'extended_reading', required: 'links' })];
    },
  });

  // ===== visuals tool(P1-1 hero gate) =====
  const visualsTool = (): ToolDefinition => ({
    name: VISUALS_TOOL_NAME,
    description: 'Internal Deep Read visual selector. Select hero/inline images only from the pre-fetched candidate pool; never submit arbitrary URLs.',
    schema: { type: 'object', properties: { hero: { type: 'object' }, inline: { type: 'array' } } },
    allowsAutoApproval: true,
    isHighRisk: false,
    execute: async (input: string): Promise<UIMessagePartText[]> => {
      const obj = asObject(input);
      const heroUrlRaw = urlString(obj, 'hero_image_url');
      const heroUrl = heroUrlRaw !== null && isHeroCandidate(heroUrlRaw) ? heroUrlRaw : null;
      const incomingAssets = parseImageAssets(obj)
        .map(a => withCandidateEvidence(a))
        .filter((a): a is DeepReadImageAsset => a !== null);
      const heroCandidate = heroUrl !== null ? candidateForUrl(heroUrl) : null;
      const heroReasonRaw = objString(obj, 'hero_reason');
      const heroReason = heroReasonRaw !== null
        ? cleanText(heroReasonRaw, HERO_REASON_MAX)
        : (heroCandidate !== null ? selectionReasonFor(heroCandidate, params.topicTitle) : null);
      const heroCaptionRaw = objString(obj, 'hero_caption');
      const heroAsset: DeepReadImageAsset | null = heroCandidate !== null ? {
        url: heroCandidate.url,
        caption: heroCaptionRaw !== null ? cleanText(heroCaptionRaw, HERO_CAPTION_MAX) : null,
        confidence: heroCandidate.confidence,
        score: heroCandidate.score,
        source: null,
        qualityHint: heroCandidate.confidence,
        selectionReason: heroReason,
        relatedEntities: [],
        relatedTimelineIndex: null,
      } : null;

      const updated = update(current => {
        const heroCaptionClean = heroCaptionRaw !== null ? cleanText(heroCaptionRaw, HERO_CAPTION_MAX) : null;
        const next: DeepReadOutput = {
          ...current,
          heroImageUrl: heroUrl ?? current.heroImageUrl,
          heroCaption: heroUrl !== null && heroCaptionClean !== null ? heroCaptionClean : current.heroCaption,
          heroImageConfidence: heroUrl !== null ? IMAGE_CONFIDENCE.HERO : current.heroImageConfidence,
          imageAssets: mergeImageAssets(current.imageAssets, [...(heroAsset !== null ? [heroAsset] : []), ...incomingAssets], IMAGE_ASSETS_MAX),
          visualDiagnostics: buildVisualDiagnostics(current.visualDiagnostics, heroCandidate, heroReason, incomingAssets, params.imageCandidates),
        };
        if (heroUrl === null && incomingAssets.length === 0) return current;
        markVisibleWrite();
        return next;
      });
      return [makeSuccessOutput({ section: 'visuals', generation_complete: updated.generationComplete && sectionsReady(updated) })];
    },
  });

  // ===== diagram tool =====
  const diagramTool = (): ToolDefinition => ({
    name: DIAGRAM_TOOL_NAME,
    description: 'Internal Deep Read diagram writer. Submit only a compact structured diagram spec; raw SVG/HTML/JS forbidden. Use 3-6 short nodes.',
    schema: { type: 'object', properties: { title: { type: 'string' }, nodes: { type: 'array' }, edges: { type: 'array' }, caption: { type: 'string' } } },
    allowsAutoApproval: true,
    isHighRisk: false,
    execute: async (input: string): Promise<UIMessagePartText[]> => {
      const obj = asObject(input);
      const diagram = parseDiagram(obj);
      const rawNodeCount = objObjectList(obj, 'nodes').length;
      const rawEdgeCount = objObjectList(obj, 'edges').length;
      const updated = update(current => {
        if (diagram === null) return current;
        markVisibleWrite();
        return { ...current, diagram };
      });
      if (diagram !== null) {
        return [makeSuccessOutput({
          section: 'diagram',
          generation_complete: updated.generationComplete && sectionsReady(updated),
          accepted: { nodes: diagram.nodes.length, edges: diagram.edges.length },
          dropped: {
            nodes: Math.max(0, rawNodeCount - diagram.nodes.length),
            edges: Math.max(0, rawEdgeCount - diagram.edges.length),
          },
          drop_reasons: buildDiagramDropReasons(rawNodeCount, diagram.nodes.length, rawEdgeCount, diagram.edges.length),
        })];
      }
      return [makeWriterOutput('missing_required_content', { section: 'diagram', required: diagramDropReason(obj), dropped: {}, drop_reasons: [] })];
    },
  });

  // ===== finish tool(三门闩锁) =====
  const finishTool = (): ToolDefinition => ({
    name: FINISH_TOOL_NAME,
    description: 'Internal Deep Read writer. Call after every section writer reports ready. Returns missing sections if any remain.',
    schema: { type: 'object', properties: {} },
    allowsAutoApproval: true,
    isHighRisk: false,
    execute: async (): Promise<UIMessagePartText[]> => {
      const result = executeFinishInternal();
      const missing = STAGE_ORDER.filter(s => statusOf(result, s) !== 'READY');
      return [makeWriterOutput(missing.length === 0 ? 'complete' : 'missing_sections', {
        missing: missing.map(s => s.toLowerCase()),
      })];
    },
  });

  const executeFinishInternal = (): DeepReadOutput => update(current => ({
    ...current,
    // 全段 READY → 经 VERIFYING(补漏)落 COMPLETE,与 Android 补漏环节对齐
    generationPhase: sectionsReady(current)
      ? (current.generationPhase === 'VERIFYING' ? 'COMPLETE' : 'VERIFYING')
      : current.generationPhase,
    generationComplete: sectionsReady(current) && current.generationPhase === 'VERIFYING',
  }));

  // ===== markPhase / markRunning / markFailed =====
  const markPhase = (phase: DeepReadOutput['generationPhase']): DeepReadOutput =>
    update(current => ({ ...current, generationPhase: phase }));

  const markRunning = (stages: DeepReadGenerationStage[]): DeepReadOutput =>
    update(current => {
      let next: DeepReadOutput = { ...current, generationPhase: 'WRITING' };
      for (const stage of stages) {
        if (statusOf(next, stage) !== 'READY') {
          next = withSectionStatus(next, stage, 'RUNNING');
        }
      }
      return next;
    });

  const markFailed = (stage: DeepReadGenerationStage, message: string): DeepReadOutput =>
    update(current => {
      if (statusOf(current, stage) === 'READY') return current;
      return withSectionStatus(current, stage, 'FAILED', safeTake(message, 220));
    });

  // ===== writeFallbackSection(照搬 Android :94-141) =====
  const writeFallbackSection = (
    stage: DeepReadGenerationStage,
    assistantText: string,
    sources: ReadingLink[],
    allowReadyRewrite: boolean,
  ): DeepReadOutput => update(current => {
    if (!allowReadyRewrite && statusOf(current, stage) === 'READY') return current;
    const fallbackText = buildFallbackBody(stage, assistantText, sources, params.topicTitle);
    const next: DeepReadOutput = (() => {
      switch (stage) {
        case 'OVERVIEW': {
          const cleaned = cleanText(fallbackText, OVERVIEW_SUMMARY_STORAGE_MAX_CHARS);
          return {
            ...current,
            summary: cleaned.length > 0 ? cleaned : current.summary,
            references: mergeReadingLinks(current.references, sources, READING_LINKS_REF_MAX),
          };
        }
        case 'NARRATIVE': {
          const timeline = sourcesToFallbackTimeline(sources);
          const corePoints = toFallbackCorePoints(fallbackText);
          return {
            ...current,
            timeline: timeline.length > 0 ? timeline : current.timeline,
            corePoints: corePoints.length > 0 ? corePoints : current.corePoints,
            references: mergeReadingLinks(current.references, sources, READING_LINKS_REF_MAX),
          };
        }
        case 'ANALYSIS': {
          const implications = cleanText(fallbackText, ANALYSIS_IMPLICATIONS_MAX);
          return {
            ...current,
            analysis: {
              ...current.analysis,
              implications: implications.length > 0 ? implications : current.analysis.implications,
            },
            references: mergeReadingLinks(current.references, sources, READING_LINKS_REF_MAX),
          };
        }
        case 'EXTENDED_READING': {
          return {
            ...current,
            extendedReading: mergeReadingLinks(current.extendedReading, sources, EXTENDED_READING_MAX),
            references: mergeReadingLinks(current.references, sources, READING_LINKS_REF_MAX),
          };
        }
      }
    })();
    if (statusReadyFor(next, stage)) {
      markRequiredWrite();
      return withSectionQuality(withSectionStatus(next, stage, 'READY'), stage, 'BASIC');
    }
    return current;
  });

  // finishIfPossible 路径:run() 尾段直落 COMPLETE(VERIFYING 过渡仅由模型 finish 工具驱动;
  // runManager 尾段无第二轮 finish 调用,直接 COMPLETE 防"卡在补漏")
  const executeFinish = (): DeepReadOutput => {
    const after = executeFinishInternal();
    if (after.generationPhase === 'VERIFYING') {
      return update(current => ({
        ...current,
        generationPhase: 'COMPLETE',
        generationComplete: sectionsReady(current),
      }));
    }
    return after;
  };

  // tools(stages?) — 照搬 Android tools():null → 全部,否则按 stage 过滤 writer tool
  const tools = (stages: Set<DeepReadGenerationStage> | null = null): ToolDefinition[] => {
    const out: ToolDefinition[] = [];
    if (stages === null || stages.has('OVERVIEW')) out.push(overviewTool());
    if (stages === null || stages.has('NARRATIVE')) out.push(narrativeTool());
    if (stages === null || stages.has('ANALYSIS')) out.push(analysisTool());
    if (stages === null || stages.has('EXTENDED_READING')) out.push(extendedReadingTool());
    out.push(visualsTool());
    out.push(diagramTool());
    out.push(finishTool());
    return out;
  };

  return {
    current: () => output,
    get writeCount() { return writeCount; },
    get requiredWriteCount() { return requiredWriteCount; },
    tools,
    markPhase,
    markRunning,
    markFailed,
    markVisibleWrite,
    markRequiredWrite,
    writeFallbackSection,
    executeFinish,
    setCurrentForTest: (o: DeepReadOutput) => { output = o; },
  };
};

// ===== 子解析器(照搬 Android timeline/corePoints/perspectives/quotes/imageAssets/diagram) =====

const parseTimeline = (o: Record<string, unknown>): TimelineEvent[] =>
  objObjectList(o, 'timeline').map(obj => {
    const event = objString(obj, 'event');
    if (event === null) return null;
    const date = objString(obj, 'date');
    return {
      date: date !== null ? cleanText(date, 80) : '',
      event: cleanText(event, TIMELINE_EVENT_MAX),
      isHighlight: objBool(obj, 'is_highlight') ?? false,
      imageUrl: urlString(obj, 'image_url'),
      imageCaption: (() => { const c = objString(obj, 'image_caption'); return c !== null ? cleanText(c, HERO_CAPTION_MAX) : null; })(),
    } as TimelineEvent;
  }).filter((x): x is TimelineEvent => x !== null);

const parseCorePoints = (o: Record<string, unknown>): CorePoint[] =>
  objObjectList(o, 'core_points').map(obj => {
    const point = objString(obj, 'point');
    if (point === null) return null;
    const supporting = objString(obj, 'supporting');
    return {
      point: cleanText(point, CORE_POINT_MAX),
      supporting: supporting !== null ? cleanText(supporting, CORE_SUPPORTING_MAX) : null,
      imageUrl: urlString(obj, 'image_url'),
      imageCaption: (() => { const c = objString(obj, 'image_caption'); return c !== null ? cleanText(c, HERO_CAPTION_MAX) : null; })(),
    } as CorePoint;
  }).filter((x): x is CorePoint => x !== null);

const parsePerspectives = (o: Record<string, unknown>): Perspective[] =>
  objObjectList(o, 'perspectives').map(obj => {
    const viewpoint = objString(obj, 'viewpoint');
    if (viewpoint === null) return null;
    const holder = objString(obj, 'holder');
    return {
      viewpoint: cleanText(viewpoint, PERSPECTIVE_VIEWPOINT_MAX),
      holder: holder !== null ? cleanText(holder, PERSPECTIVE_HOLDER_MAX) : null,
    } as Perspective;
  }).filter((x): x is Perspective => x !== null).slice(0, PERSPECTIVES_MAX);

const parseQuotes = (o: Record<string, unknown>): Quote[] =>
  objObjectList(o, 'quotes').map(obj => {
    const text = objString(obj, 'text');
    if (text === null) return null;
    const attribution = objString(obj, 'attribution');
    return {
      text: cleanText(text, QUOTE_TEXT_MAX),
      attribution: attribution !== null ? cleanText(attribution, QUOTE_ATTR_MAX) : null,
    } as Quote;
  }).filter((x): x is Quote => x !== null).slice(0, QUOTES_MAX);

const parseImageAssets = (o: Record<string, unknown>): DeepReadImageAsset[] =>
  objObjectList(o, 'image_assets').map(obj => {
    const url = urlString(obj, 'url');
    if (url === null) return null;
    const caption = objString(obj, 'caption');
    const source = objString(obj, 'source');
    const qualityHint = objString(obj, 'quality_hint');
    const selectionReason = objString(obj, 'selection_reason');
    return {
      url,
      caption: caption !== null ? cleanText(caption, HERO_CAPTION_MAX) : null,
      source: source !== null ? cleanText(source, READING_LINK_SOURCE_MAX) : null,
      confidence: IMAGE_CONFIDENCE.INLINE,
      score: null,
      qualityHint: qualityHint !== null ? cleanText(qualityHint, 60) : null,
      selectionReason: selectionReason !== null ? cleanText(selectionReason, HERO_REASON_MAX) : null,
      relatedEntities: [],
      relatedTimelineIndex: null,
    } as DeepReadImageAsset;
  }).filter((x): x is DeepReadImageAsset => x !== null).slice(0, IMAGE_ASSETS_MAX);

const DIAGRAM_TYPES = new Set(['causal_chain', 'process_flow', 'stakeholder_map', 'system_structure', 'comparison_matrix']);

const parseDiagram = (o: Record<string, unknown>): DeepReadDiagram | null => {
  const typeRaw = objString(o, 'type');
  const type = typeRaw !== null ? typeRaw.toLowerCase() : null;
  if (type === null || !DIAGRAM_TYPES.has(type)) return null;
  const titleRaw = objString(o, 'title');
  if (titleRaw === null) return null;
  const title = cleanText(titleRaw, DIAGRAM_TITLE_MAX_CHARS);
  const seenIds = new Set<string>();
  const nodes: DiagramNode[] = [];
  for (const obj of objObjectList(o, 'nodes')) {
    const id = objString(obj, 'id');
    const label = objString(obj, 'label');
    if (id === null || label === null) continue;
    const idC = cleanText(id, 32);
    const labelC = cleanText(label, DIAGRAM_NODE_LABEL_MAX_CHARS);
    if (seenIds.has(idC)) continue;
    seenIds.add(idC);
    const note = objString(obj, 'note');
    const group = objString(obj, 'group');
    nodes.push({
      id: idC,
      label: labelC,
      note: note !== null ? cleanText(note, DIAGRAM_NODE_NOTE_MAX_CHARS) : null,
      group: group !== null ? cleanText(group, DIAGRAM_NODE_GROUP_MAX_CHARS) : null,
    });
    if (nodes.length >= MAX_DIAGRAM_NODES) break;
  }
  if (nodes.length < 2) return null;
  const nodeIds = new Set(nodes.map(n => n.id));
  const rawEdges: DiagramEdge[] = [];
  for (const obj of objObjectList(o, 'edges')) {
    const from = objString(obj, 'from');
    const to = objString(obj, 'to');
    if (from === null || to === null) continue;
    const fromC = cleanText(from, 32);
    const toC = cleanText(to, 32);
    if (!nodeIds.has(fromC) || !nodeIds.has(toC) || fromC === toC) continue;
    const label = objString(obj, 'label');
    rawEdges.push({ from: fromC, to: toC, label: label !== null ? cleanText(label, DIAGRAM_EDGE_LABEL_MAX_CHARS) : null });
  }
  const limit = type === 'process_flow' || type === 'causal_chain' ? MAX_LINEAR_DIAGRAM_EDGES : MAX_RELATION_DIAGRAM_EDGES;
  const edgeSeen = new Set<string>();
  const edges: DiagramEdge[] = [];
  for (const e of rawEdges) {
    const key = `${e.from}->${e.to}`;
    if (edgeSeen.has(key)) continue;
    edgeSeen.add(key);
    edges.push(e);
    if (edges.length >= limit) break;
  }
  const reason = objString(o, 'reason');
  const caption = objString(o, 'caption');
  return {
    type,
    title,
    reason: reason !== null ? cleanText(reason, 220) : null,
    nodes,
    edges,
    caption: caption !== null ? cleanText(caption, HERO_CAPTION_MAX) : null,
  };
};

const diagramDropReason = (o: Record<string, unknown>): string => {
  const typeRaw = objString(o, 'type');
  const type = typeRaw !== null ? typeRaw.toLowerCase() : null;
  if (type === null || !DIAGRAM_TYPES.has(type)) return 'invalid type';
  const titleRaw = objString(o, 'title');
  if (titleRaw === null || cleanText(titleRaw, DIAGRAM_TITLE_MAX_CHARS).length === 0) return 'title missing';
  const acceptedNodes = objObjectList(o, 'nodes').filter(obj => objString(obj, 'id') !== null && objString(obj, 'label') !== null);
  if (acceptedNodes.length < 2) return 'nodes < 2';
  return 'type, title, nodes';
};

const buildDiagramDropReasons = (rawNodes: number, nodes: number, rawEdges: number, edges: number): string[] => {
  const out: string[] = [];
  if (rawNodes > nodes) out.push(`nodes dropped: missing id/label, duplicate id, or truncated limit=${MAX_DIAGRAM_NODES}`);
  if (rawEdges > edges) out.push('edges dropped: unknown node, self edge, duplicate edge, or truncated limit');
  return out;
};

// ===== fallback 文本构造(照搬 Android fallbackBody/cleanAssistantFallback/isUsefulFallbackText) =====

const fallbackTextMax = (stage: DeepReadGenerationStage): number => {
  switch (stage) {
    case 'OVERVIEW': return OVERVIEW_SUMMARY_STORAGE_MAX_CHARS;
    case 'NARRATIVE': return 1_200;
    case 'ANALYSIS': return ANALYSIS_IMPLICATIONS_MAX;
    case 'EXTENDED_READING': return 600;
  }
};

const isUsefulFallbackText = (text: string): boolean => {
  const cjk = (text.match(/[\u4e00-\u9fff]/g) ?? []).length;
  return text.length >= 24 && cjk >= 12;
};

const buildFallbackBody = (
  stage: DeepReadGenerationStage,
  assistantText: string,
  sources: ReadingLink[],
  topicTitle: string,
): string => {
  const cleaned = cleanAssistantFallbackPure(assistantText, fallbackTextMax(stage));
  if (isUsefulFallbackText(cleaned)) return cleaned;
  // 取第一个 source 的 excerpt(此处 source 是 ReadingLink,用 title 模拟)
  const firstSource = sources.find(s => s.title.trim().length > 0);
  if (firstSource !== undefined) {
    const srcText = cleanText(firstSource.title, fallbackTextMax(stage));
    if (srcText.length > 0) return srcText;
  }
  switch (stage) {
    case 'OVERVIEW': return `围绕「${topicTitle}」，当前来源已经提供了可继续阅读的基础事实，但模型未按约定写入结构化概览。`;
    case 'NARRATIVE': return `围绕「${topicTitle}」，现有来源显示事件已有多个公开节点，后续应优先沿时间线补齐关键进展。`;
    case 'ANALYSIS': return `围绕「${topicTitle}」，核心分析应聚焦已公开事实、各方立场和可能影响，避免把未证实推断写成定论。`;
    case 'EXTENDED_READING': return '';
  }
};

// 纯净版 cleanAssistantFallback(不污染 String prototype)
const cleanAssistantFallbackPure = (text: string, max: number): string => {
  const withoutFences = text.replace(/```[\s\S]*?```/g, ' ');
  const joined = withoutFences
    .split('\n')
    .map(line => line.trim().replace(/^[#\-*\s]+/, ''))
    .filter(line => {
      if (line.includes('deep_read_')) return false;
      if (line.includes('调用') && line.includes('工具')) return false;
      if (line.toLowerCase() === '好的') return false;
      return true;
    })
    .join(' ');
  return cleanText(joined, max);
};

const fallbackSentences = (text: string, limit: number): string[] =>
  text.split(/[。！？!?]\s*|\n+/)
    .map(s => cleanText(s, 220))
    .filter(s => s.length > 0)
    .slice(0, limit);

const toFallbackCorePoints = (text: string): CorePoint[] =>
  fallbackSentences(text, 4).map(sentence => ({
    point: safeTake(sentence, 42),
    supporting: sentence.length > 42 ? cleanText(sentence, 240) : null,
    imageUrl: null,
    imageCaption: null,
  }));

const sourcesToFallbackTimeline = (sources: ReadingLink[]): TimelineEvent[] =>
  sources.slice(0, 4).map((source, index) => {
    const date = source.source ?? `来源 ${index + 1}`;
    const eventText = source.title;
    return {
      date: cleanText(date, 40),
      event: cleanText(eventText, 260),
      isHighlight: false,
      imageUrl: null,
      imageCaption: null,
    } as TimelineEvent;
  }).filter(t => t.event.trim().length > 0);

// ===== visualDiagnostics 构造(照搬 Android buildVisualDiagnostics) =====
const selectionReasonFor = (c: ScoredImageCandidate, topicTitle: string): string => {
  switch (c.confidence) {
    case IMAGE_CONFIDENCE.HERO: return `候选图与「${topicTitle}」的标题实体或事件词匹配，且未命中 logo/icon 风险。`;
    case IMAGE_CONFIDENCE.INLINE: return '候选图可作为正文上下文图，但标题相关性不足以做头图。';
    default: return c.riskFlags.length > 0 ? c.riskFlags.join('、') : '图片相关性或质量不足。';
  }
};

const buildVisualDiagnostics = (
  previous: DeepReadOutput['visualDiagnostics'],
  heroCandidate: ScoredImageCandidate | null,
  heroReason: string | null,
  inlineAssets: DeepReadImageAsset[],
  imageCandidates: ScoredImageCandidate[] = [],
): DeepReadOutput['visualDiagnostics'] => {
  // candidateCount/rejectedImages 基于完整候选池(inline 子集会低报,
  // reject 候选此前恒空 — 诊断失真)
  const prevCount = previous?.candidateCount ?? 0;
  const selectedUrls = new Set<string>(inlineAssets.map(a => a.url));
  if (heroCandidate !== null) selectedUrls.add(heroCandidate.url);
  const rejected = imageCandidates
    .filter(c => !selectedUrls.has(c.url) && c.confidence === 'reject')
    .map(c => ({ url: c.url, reason: c.selectionReason, score: c.score }));
  return {
    candidateCount: Math.max(prevCount, Math.max(imageCandidates.length, inlineAssets.length)),
    heroSelection: heroCandidate !== null ? {
      url: heroCandidate.url,
      reason: heroReason ?? selectionReasonFor(heroCandidate, ''),
    } : (previous?.heroSelection ?? null),
    inlineSelections: inlineAssets.map(a => ({ url: a.url, reason: a.selectionReason ?? '' })).slice(0, 6),
    rejectedImages: rejected.length > 0 ? rejected : (previous?.rejectedImages ?? []),
  };
};

// ===== Writer output 工厂(对应 Android ok/missing JSON) =====
const makeWriterOutput = (status: string, payload: Record<string, unknown>): UIMessagePartText => ({
  kind: 'text',
  text: JSON.stringify({ status, ...payload }),
});
