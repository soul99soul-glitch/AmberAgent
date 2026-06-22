// Deep Read 枚举与常量 — HarmonyOS port

export type DeepReadGenerationStage = 'OVERVIEW' | 'NARRATIVE' | 'ANALYSIS' | 'EXTENDED_READING';
export const STAGE_ORDER: DeepReadGenerationStage[] = ['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING'];

export const STAGE_LABELS: Record<DeepReadGenerationStage, string> = {
  OVERVIEW: '概览',
  NARRATIVE: '时间轴叙事',
  ANALYSIS: '深度分析',
  EXTENDED_READING: '扩展阅读',
};

export type DeepReadGenerationPhase = 'IDLE' | 'COLLECTING' | 'PLANNING' | 'WRITING' | 'COMPLETE';
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

export const STAGE_TIMEOUT_MS: Record<DeepReadGenerationStage, number> = {
  OVERVIEW: 90_000,
  NARRATIVE: 110_000,
  ANALYSIS: 150_000,
  EXTENDED_READING: 90_000,
};

export const STAGE_SOURCE_LIMIT: Record<DeepReadGenerationStage, number> = {
  OVERVIEW: 6,
  NARRATIVE: 9,
  ANALYSIS: 8,
  EXTENDED_READING: 12,
};

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

export const MAX_GENERATION_STEPS = 32;
export const MAX_SUPERVISOR_PASSES = 2;
export const MAX_LLM_RETRIES = 5;
export const RETRY_INITIAL_DELAY_MS = 1_000;
export const RETRY_MAX_DELAY_MS = 16_000;
export const RETRY_JITTER_RATIO = 0.15;

export const DEEP_READ_TTL_MS = 24 * 60 * 60 * 1_000;
export const DEEP_READ_HISTORY_RETENTION_MS = 7 * 24 * 60 * 60 * 1_000;

export const PREFETCH_WALL_BUDGET_MS = 36_000;
export const PREFETCH_LRU_TTL_MS = 10 * 60 * 1_000;
export const PREFETCH_LRU_MAX_ENTRIES = 16;
export const MAX_SOURCES = 12;
export const MAX_SEARCH_RESULTS = 14;
export const MIN_SOURCE_CHARS = 280;
export const MIN_SEED_SOURCE_CHARS = 15;
export const READER_NATIVE_MIN_CHARS = 18;
