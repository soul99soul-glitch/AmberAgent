// Deep Read output data model — HarmonyOS port of Android DeepReadModels.kt
// ArkTS: interface + factory functions, no runtime mutation

export interface TimelineEvent {
  date: string;
  event: string;
  isHighlight: boolean;
  imageUrl: string | null;
  imageCaption: string | null;
}

export interface CorePoint {
  point: string;
  supporting: string | null;
  imageUrl: string | null;
  imageCaption: string | null;
}

export interface ReadingLink {
  title: string;
  url: string;
  source: string | null;
  publishedAt: string | null;
}

export interface Perspective {
  holder: string | null;
  viewpoint: string;
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

export interface DeepReadImageAsset {
  url: string;
  caption: string | null;
  confidence: string;
  score: number;
  source: string | null;
  qualityHint: string | null;
  selectionReason: string | null;
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
  status: string;
  errorMessage: string | null;
}

export interface DeepReadOutput {
  topicType: string;
  generationComplete: boolean;
  generationPhase: string;
  summary: string;
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
