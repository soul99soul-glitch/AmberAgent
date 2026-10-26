// SectionWriterTools — 照搬 Android DeepReadSectionWriterTools.kt
//
// 7 个 tool:deep_read_write_{overview,narrative,analysis,extended_reading,visuals,diagram} + deep_read_finish
// 所有 tool 共享 update(transform) 原语:同步完成读取、变换和替换;execute 虽为 async,更新过程没有 await
//
// ArkTS 约定:factory function createSectionWriterTools,闭包持有 DeepReadOutput 状态,
// 不用 class mutation(对照 observable.ts / source_prefetch.ts)。不可变更新用 spread。
//
// JSON 解析边界:tool input 是模型返回的 JSON 字符串,这里允许 Record<string, unknown>
// 解析(对照 json_schema_validator.ts 的解析边界模式)。

import { parseImpacts, parseUncertainties } from './hierarchy_fields.ts';
import type { UIMessagePartText } from './message.ts';
import { makeSuccessOutput } from './tool_execution.ts';
import type { ToolDefinition } from './tool_execution.ts';
import type { DeepReadOutput } from '../domain/models.ts';
import { makeEmptyDeepReadOutput } from '../domain/models.ts';
import type { ScoredImageCandidate } from '../research/image_scorer.ts';
import { statusOf, withSectionStatus, withSectionQuality, sectionsReady } from '../domain/helpers.ts';
import type { DeepReadGenerationStage } from '../domain/enums.ts';
import {
  WRITER_TOOL_NAMES, VISUALS_TOOL_NAME, DIAGRAM_TOOL_NAME, FINISH_TOOL_NAME, IMAGE_CONFIDENCE,
  STAGE_ORDER, OVERVIEW_SUMMARY_MIN_CHARS, OVERVIEW_SUMMARY_STORAGE_MAX_CHARS, TIMELINE_MAX,
  CORE_POINTS_MAX, IMAGE_ASSETS_MAX, READING_LINKS_REF_MAX, EXTENDED_READING_MAX, KEY_ENTITIES_MAX,
  TOPIC_TYPE_MAX, ENTITY_MAX_CHARS, ANALYSIS_DISPUTE_MAX, ANALYSIS_IMPLICATIONS_MAX, HERO_CAPTION_MAX,
  HERO_REASON_MAX,
} from '../domain/enums.ts';
import type { ReadingLink, DeepAnalysis, DeepReadImageAsset } from '../domain/models.ts';
import {
  safeTake, cleanText, asObject, objString, objObjectList, stringListFrom, urlString,
  parseReadingLinks, mergeStrings, mergeReadingLinks, hasOverviewContent, hasNarrativeContent,
  hasAnalysisContent, hasExtendedReadingContent, statusReadyFor, parseTimeline, parseCorePoints,
  parsePerspectives, parseQuotes, makeWriterOutput,
} from './section_writer_codec.ts';
import { buildFallbackBody, toFallbackCorePoints, sourcesToFallbackTimeline } from './section_writer_fallback.ts';
import {
  mergeImageAssets, parseImageAssets, parseDiagram, diagramDropReason, buildDiagramDropReasons,
  candidateForUrl, isHeroCandidate, withCandidateEvidence, selectionReasonFor, buildVisualDiagnostics,
} from './section_writer_visuals.ts';

export { safeTake, cleanText, mergeStrings, mergeReadingLinks } from './section_writer_codec.ts';

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
  writeSources: (sources: ReadingLink[]) => DeepReadOutput;
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
          bottomLine: objString(obj, 'bottom_line') !== null ? cleanText(objString(obj, 'bottom_line')!, 120) : current.bottomLine,
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
      const corePoints = parseCorePoints(obj, output.sources?.length ?? 0).slice(0, CORE_POINTS_MAX);
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
        const perspectives = parsePerspectives(obj, current.sources?.length ?? 0);
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
          impacts: Array.isArray(obj['impacts']) ? parseImpacts(obj) : current.impacts,
          watch: Array.isArray(obj['watch']) ? stringListFrom(obj, 'watch', 160).slice(0, 3) : current.watch,
          uncertainties: Array.isArray(obj['uncertainties']) ? parseUncertainties(obj) : current.uncertainties,
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
          .map(a => withCandidateEvidence(a, params.imageCandidates, params.topicTitle))
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
      return [makeWriterOutput('missing_required_content', { section: 'extended_reading', required: 'links, validated hero or diagram' })];
    },
  });

  // ===== visuals tool(P1-1 hero gate) =====
  const visualsTool = (): ToolDefinition => ({
    name: VISUALS_TOOL_NAME,
    description: 'Internal Deep Read visual selector. Select hero/inline images only from the pre-fetched candidate pool; never submit arbitrary URLs.',
    schema: { type: 'object', properties: {
      hero_image_url: { type: 'string' }, hero_caption: { type: 'string' }, hero_reason: { type: 'string' },
      image_assets: { type: 'array', items: { type: 'object', required: ['url'], properties: {
        url: { type: 'string' }, caption: { type: 'string' }, source: { type: 'string' },
        quality_hint: { type: 'string' }, selection_reason: { type: 'string' },
      } } },
    } },
    allowsAutoApproval: true,
    isHighRisk: false,
    execute: async (input: string): Promise<UIMessagePartText[]> => {
      const obj = asObject(input);
      const heroUrlRaw = urlString(obj, 'hero_image_url');
      const heroUrl = heroUrlRaw !== null && isHeroCandidate(params.imageCandidates, heroUrlRaw) ? heroUrlRaw : null;
      const incomingAssets = parseImageAssets(obj)
        .map(a => withCandidateEvidence(a, params.imageCandidates, params.topicTitle))
        .filter((a): a is DeepReadImageAsset => a !== null);
      const heroCandidate = heroUrl !== null ? candidateForUrl(params.imageCandidates, heroUrl) : null;
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
    schema: { type: 'object', required: ['type', 'title', 'nodes'], properties: {
      type: { type: 'string', enum: ['causal_chain', 'process_flow', 'stakeholder_map', 'system_structure', 'comparison_matrix'] },
      title: { type: 'string' }, reason: { type: 'string' }, caption: { type: 'string' },
      nodes: { type: 'array', items: { type: 'object', required: ['id', 'label'], properties: {
        id: { type: 'string' }, label: { type: 'string' }, note: { type: 'string' }, group: { type: 'string' },
      } } },
      edges: { type: 'array', items: { type: 'object', required: ['from', 'to'], properties: {
        from: { type: 'string' }, to: { type: 'string' }, label: { type: 'string' },
      } } },
    } },
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

  // Numbered references are a local projection of the same verified source order
  // used in stage prompts. No model output may fabricate or reorder this list.
  const writeSources = (sources: ReadingLink[]): DeepReadOutput => update(current => {
    if (sources.length === 0) return withSectionStatus(current, 'EXTENDED_READING', 'FAILED', '没有可用于引用的已读取来源。');
    markRequiredWrite();
    return withSectionQuality(withSectionStatus({ ...current, sources }, 'EXTENDED_READING', 'READY'), 'EXTENDED_READING', 'STANDARD');
  });

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
    writeSources,
    markRequiredWrite,
    writeFallbackSection,
    executeFinish,
    setCurrentForTest: (o: DeepReadOutput) => { output = o; },
  };
};
