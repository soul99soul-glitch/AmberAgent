// Public memory capability exports; implementations own their state.


// ===== D-085a:记忆子系统读路径纯模块 =====
// memory_models(MemoryModels.kt)/memory_time_anchor(MemoryTimeAnchorParser.kt)/
// memory_prompt_builder(MemoryPromptBuilder.kt)/memory_recall(MemoryRecallStore.kt
// 的 rank/score/tokenize 纯函数)。Store 编排与接线留 D-085b。
export {
  DEFAULT_MEMORY_RECALL_SETTING,
  memoryCandidateStatusFromWireName, memoryEventTypeFromWireName,
  memoryKindFromWireName, memoryScopeFromWireName,
  makeMemoryCandidate, makeMemoryEvent, makeMemoryRecord,
} from '../chat/memory_models.ts';
export type {
  MemoryCandidate, MemoryCandidateStatus, MemoryEvent, MemoryEventType,
  MemoryKind, MemoryRecallSetting, MemoryRecord, MemoryScope,
} from '../chat/memory_models.ts';
export {
  applyMemoryTopicSuggestions, buildMemoryTopicPrompt,
  decodeMemoryTopicSuggestions, isMemoryTopicSource, pruneMemoryTopics,
} from '../chat/memory_topics.ts';
export type {
  MemoryTopicSuggestion, MemoryTopicApplyResult, MemoryTopicPruneResult,
} from '../chat/memory_topics.ts';
export { renderMemoryDocuments } from '../chat/memory_documents.ts';
export type {
  MemoryDocumentProjection, MemoryDerivedDocument,
} from '../chat/memory_documents.ts';
export {
  classifyMemoryFreshness, deriveMemoryExpiresAt,
} from '../chat/memory_time_anchor.ts';
export type { MemoryFreshness } from '../chat/memory_time_anchor.ts';
export {
  buildMemoryContext, isSensitiveMemoryContent, SENSITIVE_MEMORY_TERMS,
} from '../chat/memory_prompt_builder.ts';
export {
  memoryRecallScoreToDebugText, rankMemoryRecords, scoreMemoryRecord,
  tokenizeMemoryQuery, USER_ALWAYS_ELIGIBLE_CONFIDENCE,
} from '../chat/memory_recall.ts';
export type {
  MemoryRecallFreshness, MemoryRecallScore, MemoryRecallSelection,
} from '../chat/memory_recall.ts';

// ===== D-085b:记忆召回编排(MemoryRecallStore.kt:14-53)+ 动态块通道 =====
export {
  buildMemoryRecallPrompt, recallMemoryRecords, recallMemorySelections,
} from '../chat/memory_recall_store.ts';
export type {
  MemoryReadRepository, MemoryRecallRuntimeGate,
} from '../chat/memory_recall_store.ts';
export {
  filterActiveMemoryRecords, touchMemoryRecords,
} from '../chat/memory_recall_store.ts';

// ===== D-085c:memory_* 工具(MemoryTools.kt 全文)+ 写路径纯函数 =====
export {
  createMemoryTools, makeAssistantMemory,
} from '../chat/builtin_memory_tools.ts';
export type {
  AssistantMemory, MemoryToolDeps, MemoryToolWriteRequest,
} from '../chat/builtin_memory_tools.ts';
export {
  appendMemoryRecord, deleteMemoryRecord, memoriesOfAssistant,
  memoryBucketForScope, memoryKindForBucketAdd, memoryRecordToAssistantMemory,
  memoryScopeForBucket, nextMemoryId, updateMemoryRecordContent,
  MEMORY_BUCKET_GLOBAL, MEMORY_BUCKET_LONG_TERM, MEMORY_BUCKET_SHORT_TERM,
} from '../chat/memory_write.ts';
export type { MemoryAddParams, MemoryWriteResult } from '../chat/memory_write.ts';

// ===== D-085d:记忆抽取(MemoryExtractionPrompt/MemoryExtractor) =====
export { buildMemoryExtractionPrompt } from '../chat/memory_extraction_prompt.ts';
export {
  autoWriteEventMessage, isDurableAutoWriteCandidate, parseMemoryCandidates,
  resetMemoryExtractionDebounce, resolveCandidateExpiresAt, runMemoryExtraction,
  shouldAutoWriteCandidate,
  DURABLE_AUTO_WRITE_CONFIDENCE, SHORT_TERM_PROJECT_AUTO_WRITE_CONFIDENCE,
} from '../chat/memory_extractor.ts';
export type {
  MemoryExtractionDeps, MemoryWorkerGate, MemoryWorkerModelResolution,
  ParsedMemoryCandidate,
} from '../chat/memory_extractor.ts';
export { upsertMemoryRecord } from '../chat/memory_write.ts';

// Memory optimization: temporal history, reinforcement and derived profiles.
export { restoreMemoryRecord } from '../chat/memory_restore.ts';
export { reinforceMemoryRecords } from '../chat/memory_recall_store.ts';
export { isMemoryActive, canPromoteMemory, shouldArchiveIdleMemory, memoryLocalDate, memoryDateLabel } from '../chat/memory_lifecycle.ts';
export { applyMemoryExtractionActions, collectMemoryExtractionSources, memoryExpiryOn,
  normalizeMemoryRelativeDates, isGroundedMemoryRewrite } from '../chat/memory_extraction_actions.ts';
export type { MemoryExtractionAction, MemoryExtractionApplyResult, MemoryExtractionSource } from '../chat/memory_extraction_actions.ts';

export { memoryProfileSources, buildMemoryProfile, coveredMemoryProfileRecords,
  isMemoryProfileCurrent, selectMemoryProfile, MINIMUM_MEMORY_PROFILE_SOURCES, MAX_MEMORY_PROFILE_ITEMS } from '../chat/memory_profile.ts';
export type { MemoryProfile, MemoryProfileItem, MemoryProfileSelection, MemoryProfileSourceSnapshot } from '../chat/memory_profile.ts';
export { nearDuplicateCandidates, planConfirmedMemoryMerges, remapMemoryProfileItems,
  buildMemoryProfilePassPrompt, parseMemoryProfilePassOutput } from '../chat/memory_semantic_dedup.ts';
export type { MemoryNearDuplicateCandidate, MemorySemanticMerge, MemoryProfilePassOutput } from '../chat/memory_semantic_dedup.ts';

export { createMemoryCitationTransformer, readMemoryCitationIds, memoryCitationIds, stripMemoryCitations } from '../chat/memory_citations.ts';
export { invalidateMemoryTopicsForSource } from '../chat/memory_topics.ts';

export { parseStoredMemoryProfile } from '../chat/memory_profile.ts';

export { extractionMessagesContaminated } from '../chat/memory_extractor.ts';
