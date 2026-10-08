// Deep Read output data model — HarmonyOS port of Android DeepReadModels.kt
// ArkTS: interface + factory functions, no runtime mutation

import type { DeepReadTemplateArticle } from './synthesis_article.ts';
import type { DeepReadInputSource } from './input_sources.ts';
import type { DeepReadSectionStatus, DeepReadGenerationPhase } from './enums.ts';

export interface TimelineEvent {
  date: string;
  event: string;
  isHighlight: boolean;
  why?: string | null;
  imageUrl: string | null;
  imageCaption: string | null;
}

export interface CorePoint {
  point: string;
  sources?: number[];
  supporting: string | null;
  imageUrl: string | null;
  imageCaption: string | null;
}

// ReadingLink: Android 只有 title/url/source; publishedAt 是鸿蒙扩展
// (prefetcher 从 OG meta 标签提取发布时间,Android 忽略此字段)
export interface ReadingLink {
  /** Stable research/input identity for numbered generation sources. */
  sourceId?: string;
  title: string;
  url: string;
  source: string | null;
  publishedAt: string | null;
}

export interface Perspective {
  holder: string | null;
  interest?: string | null;
  quote?: string | null;
  quoteBy?: string | null;
  sources?: number[];
  viewpoint: string;
}

export interface DeepReadImpact {
  target: string;
  horizon: 'short' | 'long';
  effect: string;
}

export interface DeepReadUncertainty {
  claim: string;
  status: 'single_source' | 'conflicting' | 'pending_official' | '';
}

export interface Quote {
  text: string;
  attribution: string | null;
}

export interface DeepAnalysis {
  coreDispute: string | null;
  perspectives: Perspective[];
  implications: string | null;
  quotes: Quote[];
}

// DeepReadImageAsset: 对应 Android DeepReadModels.kt:198-208
// score: Android 是 Int? = null,鸿蒙用 number | null
export interface DeepReadImageAsset {
  url: string;
  caption: string | null;
  confidence: string;
  score: number | null;
  source: string | null;
  qualityHint: string | null;
  selectionReason: string | null;
  relatedEntities: string[];
  relatedTimelineIndex: number | null;
}

export interface DiagramNode {
  id: string;
  label: string;
  note: string | null;
  group: string | null;
}

export interface DiagramEdge {
  from: string;
  to: string;
  label: string | null;
}

export interface DeepReadDiagram {
  type: string;
  title: string;
  reason: string | null;
  nodes: DiagramNode[];
  edges: DiagramEdge[];
  caption: string | null;
}

export interface HeroSelection {
  url: string;
  reason: string;
}

export interface InlineSelection {
  url: string;
  reason: string;
}

export interface RejectedImage {
  url: string;
  score: number;
  reason: string;
}

export interface DeepReadVisualDiagnostics {
  candidateCount: number;
  heroSelection: HeroSelection | null;
  inlineSelections: InlineSelection[];
  rejectedImages: RejectedImage[];
}

export interface DeepReadSectionState {
  status: DeepReadSectionStatus;
  errorMessage: string | null;
}

/** The article owns this capture; changing or deleting a template cannot restyle it. */
export interface DeepReadTemplateSnapshot {
  id: string;
  name: string;
  kind: 'native' | 'editorial' | 'custom' | 'synthesis';
  html: string | null;
  capturedAt: number;
}

export interface DeepReadOutput {
  templateArticle?: DeepReadTemplateArticle;
  templateId?: string;
  templateSnapshot?: DeepReadTemplateSnapshot;
  /** Original topic sources, stored with the draft so history can retry every source. */
  inputSourceUrls?: string[];
  inputSources?: DeepReadInputSource[];
  /** Unabridged user paste and URL input, retained separately from extraction limits. */
  inputText?: string;
  inputUrlsText?: string;
  topicType: string;
  generationComplete: boolean;
  generationPhase: DeepReadGenerationPhase;
  summary: string;
  bottomLine?: string;
  impacts?: DeepReadImpact[];
  watch?: string[];
  /** Stable 1-based generation order, including text/file sources with no URL. */
  sources?: ReadingLink[];
  uncertainties?: (string | DeepReadUncertainty)[];
  keyEntities: string[];
  timeline: TimelineEvent[] | null;
  corePoints: CorePoint[] | null;
  analysis: DeepAnalysis;
  extendedReading: ReadingLink[];
  heroImageQuery: string | null;
  heroImageUrl: string | null;
  heroCaption: string | null;
  heroImageConfidence: string | null;
  imageAssets: DeepReadImageAsset[];
  diagram: DeepReadDiagram | null;
  visualDiagnostics: DeepReadVisualDiagnostics | null;
  references: ReadingLink[];
  sectionStates: Record<string, DeepReadSectionState>;
  sectionQualities: Record<string, string>;
}

export const makeEmptyDeepReadOutput = (): DeepReadOutput => ({
  topicType: 'event',
  generationComplete: false,
  generationPhase: 'IDLE',
  summary: '',
  bottomLine: '',
  impacts: [],
  watch: [],
  sources: [],
  uncertainties: [],
  keyEntities: [],
  timeline: null,
  corePoints: null,
  analysis: { coreDispute: null, perspectives: [], implications: null, quotes: [] },
  extendedReading: [],
  heroImageQuery: null,
  heroImageUrl: null,
  heroCaption: null,
  heroImageConfidence: null,
  imageAssets: [],
  diagram: null,
  visualDiagnostics: null,
  references: [],
  sectionStates: {},
  sectionQualities: {},
});
