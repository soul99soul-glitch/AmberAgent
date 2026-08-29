// Deep Read 枚举与常量 — HarmonyOS port

export type DeepReadGenerationStage = 'OVERVIEW' | 'NARRATIVE' | 'ANALYSIS' | 'EXTENDED_READING';
export const STAGE_ORDER: DeepReadGenerationStage[] = ['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING'];

export const STAGE_LABELS: Record<DeepReadGenerationStage, string> = {
  OVERVIEW: '概览',
  NARRATIVE: '时间轴叙事',
  ANALYSIS: '深度分析',
  EXTENDED_READING: '扩展阅读',
};

export type DeepReadGenerationPhase =
  | 'IDLE' | 'COLLECTING' | 'PLANNING' | 'WRITING' | 'VERIFYING' | 'COMPLETE';
export type DeepReadSectionStatus = 'PENDING' | 'RUNNING' | 'READY' | 'FAILED';
export type DeepReadSectionQuality = 'BASIC' | 'STANDARD';

export const IMAGE_CONFIDENCE = {
  HERO: 'hero',
  INLINE: 'inline',
  REJECT: 'reject',
} as const;

export const WRITER_TOOL_NAMES: Record<DeepReadGenerationStage, string> = {
  OVERVIEW: 'deep_read_write_overview',
  NARRATIVE: 'deep_read_write_narrative',
  ANALYSIS: 'deep_read_write_analysis',
  EXTENDED_READING: 'deep_read_write_extended_reading',
};

export const VISUALS_TOOL_NAME = 'deep_read_write_visuals';
export const DIAGRAM_TOOL_NAME = 'deep_read_write_diagram';
export const FINISH_TOOL_NAME = 'deep_read_finish';

// Per-stage 超时(ms) — 照搬 Android DeepReadAgentRunManager.collectRunTimeoutFor
export const STAGE_TIMEOUT_MS: Record<DeepReadGenerationStage, number> = {
  OVERVIEW: 90_000,
  NARRATIVE: 110_000,
  ANALYSIS: 150_000,
  EXTENDED_READING: 90_000,
};

// Per-stage prompt source 数量上限 — 照搬 Android DeepReadAgentRunManager.promptSourceLimit (1109-1114)
// 注意:Android 另有一张 DeepReadPrompt.sourceLimit (226-231) 是 scraper source cap(5/10/10/12),与此不同
export const STAGE_SOURCE_LIMIT: Record<DeepReadGenerationStage, number> = {
  OVERVIEW: 6,
  NARRATIVE: 9,
  ANALYSIS: 8,
  EXTENDED_READING: 12,
};

// Per-stage evidence excerpt 截断长度 — 照搬 Android DeepReadAgentRunManager.promptExcerptLimit
export const STAGE_EXCERPT_LIMIT: Record<DeepReadGenerationStage, number> = {
  OVERVIEW: 1000,
  NARRATIVE: 1400,
  ANALYSIS: 1400,
  EXTENDED_READING: 700,
};

export const PROMPT_SOURCE_LIMIT = 12;
export const PROMPT_SOURCE_EXCERPT_LIMIT = 2_000;
export const PLAYBOOK_PROMPT_LIMIT = 12_000;

export const OVERVIEW_SUMMARY_MIN_CHARS = 24;
export const OVERVIEW_SUMMARY_STORAGE_MAX_CHARS = 1_200;

export const MAX_DIAGRAM_NODES = 6;
export const MAX_LINEAR_DIAGRAM_EDGES = 5;
export const MAX_RELATION_DIAGRAM_EDGES = 6;
// 照搬 Android DeepReadSectionWriterTools.kt:24-31 图表字段上限
export const DIAGRAM_TITLE_MAX_CHARS = 64;
export const DIAGRAM_NODE_LABEL_MAX_CHARS = 34;
export const DIAGRAM_NODE_NOTE_MAX_CHARS = 96;
export const DIAGRAM_NODE_GROUP_MAX_CHARS = 40;
export const DIAGRAM_EDGE_LABEL_MAX_CHARS = 42;

// 照搬 Android DeepReadSectionWriterTools.kt 各 take() 上限
export const TIMELINE_MAX = 8;
export const CORE_POINTS_MAX = 8;
export const PERSPECTIVES_MAX = 8;
export const QUOTES_MAX = 6;
export const IMAGE_ASSETS_MAX = 8;
export const READING_LINKS_REF_MAX = 12;
export const EXTENDED_READING_MAX = 10;
export const KEY_ENTITIES_MAX = 12;
// 照搬 Android 各 cleanText() 字段截断
export const TOPIC_TYPE_MAX = 32;
export const ENTITY_MAX_CHARS = 80;
export const READING_LINK_TITLE_MAX = 160;
export const READING_LINK_SOURCE_MAX = 80;
export const TIMELINE_EVENT_MAX = 600;
export const CORE_POINT_MAX = 280;
export const CORE_SUPPORTING_MAX = 700;
export const PERSPECTIVE_VIEWPOINT_MAX = 700;
export const PERSPECTIVE_HOLDER_MAX = 120;
export const QUOTE_TEXT_MAX = 420;
export const QUOTE_ATTR_MAX = 160;
export const ANALYSIS_DISPUTE_MAX = 1_000;
export const ANALYSIS_IMPLICATIONS_MAX = 1_600;
export const HERO_CAPTION_MAX = 180;
export const HERO_REASON_MAX = 240;

export const MAX_GENERATION_STEPS = 32;
export const MAX_SUPERVISOR_PASSES = 2;
export const MAX_LLM_RETRIES = 5;
export const RETRY_INITIAL_DELAY_MS = 1_000;
export const RETRY_MAX_DELAY_MS = 16_000;
export const RETRY_JITTER_RATIO = 0.15;

export const DEEP_READ_TTL_MS = 24 * 60 * 60 * 1_000;
export const DEEP_READ_HISTORY_RETENTION_MS = 7 * 24 * 60 * 60 * 1_000;

// Prefetch — 照搬 Android DeepReadSourcePrefetcher
// PREFETCH_WALL_BUDGET_MS = Android SOURCE_COLLECTION_TIMEOUT_MS (DeepReadSourcePrefetcher.kt:688)
// PREFETCH_LRU_TTL_MS / MAX_ENTRIES = Android CACHE_TTL_MS / CACHE_MAX_ENTRIES (DeepReadSourcePrefetcher.kt:719-720)
export const PREFETCH_WALL_BUDGET_MS = 15_000;
export const PREFETCH_LRU_TTL_MS = 10 * 60 * 1_000;
export const PREFETCH_LRU_MAX_ENTRIES = 16;
// MAX_SOURCES = Android DeepReadSourcePrefetcher.MAX_SOURCES = 12 (区别于 SearchOrchestrator.MAX_SOURCES = 5)
export const MAX_SOURCES = 12;
export const MAX_SEARCH_RESULTS = 14;
export const MIN_SOURCE_CHARS = 280;
export const MIN_SEED_SOURCE_CHARS = 15;
// READER_NATIVE_MIN_CHARS: Android 用裸 >= 18 字面量 (DeepReadSourcePrefetcher.kt:657, 673)
export const READER_NATIVE_MIN_CHARS = 18;

// Evidence stage 分配 / cardsFor 上限 — 照搬 Android DeepReadResearchHarness.kt:15-17
// STAGE_EVIDENCE_MIN/MAX 限制每个 stage 的 evidence cards 数量(plan + cardsFor cap)
export const STAGE_EVIDENCE_MIN = 4;
export const STAGE_EVIDENCE_MAX = 6;
export const DEFAULT_STAGE_EVIDENCE_TARGET = 5;
