// deepread-domain 公共入口 barrel — 供 entry 模块 ArkTS import
// 汇总 domain/agent/research 各层对外 API。
//
// 注意:ArkTS 编译器对 barrel 的要求 = 各源文件本身可过 ArkTS 严格模式。
// 纯逻辑层已按 interface+factory、无 any、spread 不可变约定编写(对照设计 §0.4)。

// ===== Domain =====
// ArkTS 安全:topic_id.ts 提供 createTopicIdWithRuntime(注入 runtime)+ normalizeUrlArkTs。
// deriveTopicId 在 src/test/topic_id_node.ts(仅 node 测试,import 'crypto'),ArkTS 不引用。
export { createTopicIdWithRuntime, normalizeTitle, normalizeUrlArkTs } from './domain/topic_id.ts';
export type { TopicIdRuntime } from './domain/topic_id.ts';
export type { DeepReadOutput, TimelineEvent, CorePoint, ReadingLink, Perspective, Quote, DeepAnalysis, DeepReadImageAsset, DiagramNode, DiagramEdge, DeepReadDiagram, DeepReadSectionState, DeepReadImpact, DeepReadUncertainty } from './domain/models.ts';
export { makeEmptyDeepReadOutput } from './domain/models.ts';
export type { DeepReadTemplateSnapshot } from './domain/models.ts';
export * from './domain/discovery.ts';
export * from './domain/discovery_input.ts';
export { observeDeepReadLibrary, queryDeepReadLibraryRows } from './domain/library.ts';
export type { DeepReadLibrarySnapshot, DeepReadLibraryRow } from './domain/library.ts';
export type { DeepReadGenerationStage, DeepReadGenerationPhase, DeepReadSectionStatus } from './domain/enums.ts';
export { STAGE_ORDER, STAGE_LABELS, IMAGE_CONFIDENCE, WRITER_TOOL_NAMES } from './domain/enums.ts';
export { statusOf, sectionsReady, isComplete, firstFailureMessage, firstFailedStage, displayHeroImageUrl, displayHeroCaption, hasReadableArticle, hasDisplayableDeepReadOutput, normalizedDeepReadUncertainties } from './domain/helpers.ts';
export type { ModelConfig, ModelRegistry, ModelConfigInit, ModelRegistryOptions } from './domain/model_config.ts';
export { makeModelConfig, newModelId, createStorageModelRegistry, MODEL_CONFIGS_STORAGE_KEY } from './domain/model_config.ts';

// ===== Platform 契约(供 entry 注入真实实现) =====
export type { HttpClient, HttpRequest, HttpResponse } from './platform/http.ts';
export { httpResponseStatusFromHeaders, isEventStreamResponse } from './platform/http_response_status.ts';
export type { AbortSignalLike, AbortControllerLike } from './platform/runtime_api.ts';
export type { Database } from './platform/database.ts';
export type { Storage } from './platform/storage.ts';
export type { SearchProvider, SearchProviderRegistry, SearchHit } from './platform/search.ts';
export type { AiClient, Tool, GenerateTextParams } from './platform/ai_client.ts';
export type { DeepReadRepository, DeepReadCacheEntry } from './platform/repository.ts';
export { deepReadProgressSnapshot, isCacheEntryExpired } from './platform/deep_read_progress.ts';
export type { DeepReadProgressSnapshot } from './platform/deep_read_progress.ts';
export type { NovelRunKind } from './novel/prompt_catalog.ts';
export { novelPromptTemplate, stripPolishSentinel, hasPolishSentinel, POLISH_COMPLETION_SENTINEL } from './novel/prompt_catalog.ts';
export type { Observable } from './platform/observable.ts';
export { pollingObservable } from './platform/observable.ts';
export { DEEP_READ_SCHEMA_SQL } from './platform/database.ts';
export type { Notifier, NotificationKind, NotificationRequest } from './platform/notifier.ts';
export type { FileStore } from './platform/files.ts';
export { joinPath, createMemoryFileStore } from './platform/files.ts';
export { parseOpenAiStreamEvent, createOpenAiTextStream } from './platform/openai_stream.ts';
export type { OpenAiStreamDelta, OpenAiTextStreamSink } from './platform/openai_stream.ts';
export type { DecodeChunk } from './platform/sse_assembler.ts';
export { SseAssembler, createSseStreamHandler } from './platform/sse_assembler.ts';
export type { SseEvent } from './platform/sse_assembler.ts';

// ===== Agent / Research 入口 =====
export { createSectionWriterTools } from './agent/section_writer_tools.ts';
export type { SectionWriterTools } from './agent/section_writer_tools.ts';
export { run, runSection, createRunContext, generateStages } from './agent/run_manager.ts';
export type { RunManagerDeps, RunResult } from './agent/run_manager.ts';
export { runAgentLoop } from './agent/agent_loop.ts';
export type { AgentLoopDeps, AgentLoopResult, StopReason } from './agent/agent_loop.ts';
export type { ToolDefinition, ApprovalMatrix } from './agent/tool_execution.ts';
export type { JsonValue, JsonObject } from './agent/json.ts';
export type { TokenUsage } from './agent/usage.ts';
export type {
  MessageRole, StreamTransportState, ToolApprovalState,
  UIMessagePart, UIMessagePartText, UIMessagePartImage, UIMessagePartVideo,
  UIMessagePartAudio, UIMessagePartDocument, UIMessagePartMiniApp,
  UIMessagePartReasoning, UIMessagePartTool,
  UIMessageUrlContextAnnotation, UIMessageGoogleSearchSuggestionsAnnotation,
  UIMessageAnnotation, UIMessage, UIMessageOpts, UIMessageChoice, MessageChunk,
} from './agent/message.ts';
export {
  makeUIMessage, makeSystemMessage, makeUserMessage, makeAssistantMessage, finishAssistantMessage,
  canResumeToolExecution, isToolExecuted, isToolPending, canToolResumeExecution,
  isToolAwaitingExecution, toolInputAsJson, toText, reasoningPartText, summaryAsText,
  getTools, latestAssistantText, isValidToUpload, hasBase64Part,
  isEmptyInputMessage, isEmptyUIMessage,
} from './agent/message.ts';
export type {
  LegacyToolApprovalState, LegacyUIMessagePartText, LegacyUIMessagePartImage,
  LegacyUIMessagePartReasoning, LegacyUIMessagePartTool, LegacyUIMessagePart, LegacyUIMessage,
} from './agent/legacy_message.ts';
export { migrateLegacyUIMessage } from './agent/legacy_message.ts';
export { createScheduler, isInterruptedPhase } from './agent/scheduler.ts';
export type { DeepReadScheduler, SchedulerRunOptions, DeepReadActiveRun } from './agent/scheduler.ts';
export { createSourcePrefetcher } from './research/source_prefetcher.ts';
export type { SourcePrefetcher, DeepReadSource } from './research/source_prefetcher.ts';
export { buildEvidencePack, cardsFor } from './research/evidence_pack.ts';
export type { DeepReadEvidencePack, DeepReadArticlePlan } from './research/evidence_pack.ts';
export { generateArticlePlan, fallbackPlan } from './research/article_plan.ts';
export { createTavilyProvider, createFallbackProviders } from './research/search_provider.ts';

// ===== Model Council(模型议会) =====
export type {
  ModelCouncilMode, ModelCouncilRunStatus, ReasoningLevel, CouncilToolMode, CouncilSeatRunKey,
  CouncilSource, CouncilApprovalRequest, CouncilToolVerdict, CouncilToolSnapshot,
  ModelCouncilSeat, ModelCouncilSeatInit, ModelCouncilTaskSpec, ModelCouncilTurn,
  ModelCouncilResult, ModelCouncilRun, ModelCouncilRuntimeSetting, ModelCouncilRuntimeSettingInit,
  ModelCouncilRolePreset,
} from './council/models.ts';
export {
  DEFAULT_MAX_SEATS, DEFAULT_DEFAULT_ROUNDS, DEFAULT_MAX_ROUNDS, DEFAULT_SEAT_TIMEOUT_MS,
  DEFAULT_TOTAL_TIMEOUT_MS, DEFAULT_OUTPUT_BUDGET_CHARS, DEFAULT_WAIT_TIMEOUT_MS,
  PROVIDER_PARALLELISM, SYNTHESIZER_SEAT_KEY, DEFAULT_OUTPUT_FORMAT,
  isRunningStatus, makeSeat, newSeatId, makeRuntimeSetting, makeEmptyResult, normalizeCouncilToolMode,
  CORE_SEATS, LENS_PRESETS, ROLE_PRESETS, isCoreRole, findRolePreset,
} from './council/models.ts';
export type { CouncilGenerateRequest, ModelCouncilTextResult, ModelCouncilTextRunner } from './council/runner.ts';
export { Semaphore, withPermit } from './council/semaphore.ts';
export {
  seatSystemPrompt, openingPrompt, responsePrompt, finalPositionPrompt,
  synthesisPrompt, summaryBlock, truncate, SYNTHESIZER_SYSTEM_PROMPT,
} from './council/prompts.ts';
export type { CouncilTaskInput, CouncilDynamicSeatSelection } from './council/validator.ts';
export { parseTask, validateSeats, resolveSynthesisModelId, buildDefaultSeats, resolveCouncilDynamicSeats } from './council/validator.ts';
export type { ModelCouncilDeps } from './council/manager.ts';
export { ModelCouncilManager, createModelCouncilManager } from './council/manager.ts';
export { readCouncilArchive } from './council/archive.ts';

// ===== Novel Creation(小说创作) =====
export type {
  NovelMessageRole, NovelChatMode, NovelGenerationGranularity, NovelMaterialKind,
  NovelSuggestionStatus, NovelSettingProposalStatus, NovelCollectionTarget, NovelMessage, NovelChapter, NovelMaterial,
  NovelMaterialSuggestion, NovelProject, NovelProjectInit, NovelMessageInit, NovelChapterInit,
  NovelMaterialInit, NovelSuggestionInit, NovelSettingProposal, NovelSettingProposalInit,
  NovelChapterVersion, NovelChapterVersionKind, NovelBranch, NovelModelTarget, NovelModelPolicy,
  NovelSettingFilePath, NovelForeshadowStatus, NovelForeshadow, NovelConfirmedDecision,
  NovelBranchSettings, NovelCandidateProvenance } from './novel/models.ts';
export {
  NOVEL_SCHEMA_VERSION, MAX_PROJECT_NAME_CHARS, MAX_TITLE_CHARS, MAX_CHAPTER_CHARS,
  MAX_MATERIAL_CHARS, MATERIAL_KIND_LABELS, novelId, makeNovelProject, makeNovelMessage,
  makeNovelChapter, makeNovelMaterial, makeNovelSuggestion, makeNovelSettingProposal,
  globalNovelModelTarget, defaultNovelModelPolicy, emptyNovelBranchSettings,
} from './novel/models.ts';
export type { NovelErrorCode } from './novel/error.ts';
export {
  NovelError, isNovelError, invalidInput, notFound, projectBusy, alreadyCollected,
  providerError, outputTooLarge, invalidModelOutput,
} from './novel/error.ts';
export type { NovelMaterialAdoption, NovelMaterialAdoptionResult } from './novel/material_adoption.ts';
export {
  buildSuggestionAdoption, buildSettingProposalAdoption, materialAdoptionTargetDigest,
  materialSuggestionChapterDigest, applyMaterialAdoption,
} from './novel/material_adoption.ts';
export {
  createProject, renameProject, setProjectModelPolicy, setProjectBranchSettings,
  appendMessage, saveChapter, deleteChapter,
  upsertMaterial, deleteMaterial, collect, replacePendingSuggestions, resolveSuggestion,
  resolveSettingProposal,
} from './novel/mutations.ts';
export type { NovelMutation } from './novel/mutations.ts';
export { novelMessageUi, novelMessageText } from './novel/transcript.ts';
export type {
  NovelDiscussionArchive, NovelDiscussionArchiveDraft, NovelDiscussionArchiveConfirmation,
} from './novel/discussion_archive.ts';
export { eligibleNovelDiscussion } from './novel/discussion_archive.ts';
export type { NovelGhostwriteStartPreview } from './novel/ghostwrite_start_preview.ts';
export type { NovelGhostwriteReport, NovelGhostwriteReportChapter } from './novel/job_report.ts';
export { formatNovelQuickStartOverview } from './novel/quick_start_proposals.ts';
export { systemPrompt, novelComposerRunKind, MAX_USER_CHARS, MAX_OUTPUT_CHARS, MAX_SYSTEM_CHARS } from './novel/context_builder.ts';
export type {
  NovelToolContinuationVerdict, NovelModelOperation, NovelModelRequest,
  NovelContextSection, NovelRequestContext,
  NovelModelEvent, NovelModelStream, NovelModelRunning,
  NovelRuntimeSnapshot, NovelResponsesResumeCursor, NovelPreparedRuntime,
} from './novel/model_running.ts';
export {
  SUGGESTION_SYSTEM_PROMPT, buildSuggestionUserPrompt, mapSuggestionKind, parseSuggestions,
  collectModelText, analyzeChapterSuggestions, MAX_SUGGESTIONS,
} from './novel/suggestion_engine.ts';
export {
  parseAuditReport, buildAuditUserPrompt, runContinuityAudit, verifyAuditCanonicalReferences,
  CONTINUITY_AUDIT_SYSTEM_PROMPT, AUDIT_MAX_ISSUES, NovelAuditController,
} from './novel/continuity_audit.ts';
export type {
  NovelAuditIssue, NovelAuditReport, NovelAuditSeverity, NovelAuditProgress, NovelAuditBlock,
  NovelAuditParsedReference, NovelAuditReference,
} from './novel/continuity_audit.ts';
export {
  parseSettingProposals, isSettingProposalEnvelope, SETTING_PROPOSAL_MARKER, MAX_PROPOSALS,
} from './novel/setting_proposal_parser.ts';
export type {
  NovelProjectRepository, NovelWorkspaceMutationKind, NovelWorkspaceCas, NovelWorkspaceStatus, NovelWorkspaceSnapshot,
} from './novel/repository.ts';
export { createFileNovelRepository, migrateNovelProject } from './novel/repository.ts';
export type { NovelWorkspaceManifest } from './novel/workspace_contract.ts';
export { novelChapterOrdinal, nextNovelChapterOrdinal } from './novel/models.ts';
export type {
  NovelProjectFailure, NovelProjectInventory, NovelProjectRecoveryPreview,
  NovelNativeRestorePreview, NovelNativeBackupSnapshot,
} from './novel/workspace_storage.ts';
export type {
  NovelNativeBackupMetadata, NovelNativeBackupManifest, NovelNativeBackupEntry, NovelNativeBackupImport,
} from './novel/native_backup.ts';
export {
  NOVEL_NATIVE_BACKUP_MANIFEST_PATH, NOVEL_NATIVE_BACKUP_FORMAT, NOVEL_NATIVE_BACKUP_VERSION,
  exportNovelNativeBackup, importNovelNativeBackup,
} from './novel/native_backup.ts';
export type { NovelWorkspaceBranchImport, NovelWorkspaceImportPlan } from './novel/workspace_interop.ts';
export { buildNovelWorkspaceImportPlan, buildNovelWorkspacePublicFiles } from './novel/workspace_interop.ts';
export {
  NOVEL_WORKSPACE_FORMAT, NOVEL_WORKSPACE_VERSION, parseNovelWorkspaceManifest,
  serializeNovelWorkspaceManifest, validateNovelWorkspacePath, chapterFileName,
} from './novel/workspace_contract.ts';
export type {
  NovelWorkspaceArchiveEntry, NovelWorkspaceArchiveFile, NovelWorkspaceArchiveCodec,
  NovelWorkspaceExchangeLimits, NovelWorkspaceValidatedImport,
} from './novel/workspace_exchange.ts';
export {
  NOVEL_WORKSPACE_MANIFEST_PATH, DEFAULT_NOVEL_WORKSPACE_EXCHANGE_LIMITS,
  decodeNovelWorkspaceUtf8, importNovelWorkspaceArchive, exportNovelWorkspaceArchive,
} from './novel/workspace_exchange.ts';
export type {
  NovelBookChapterSnapshot, NovelBookExportInput, NovelBookZipCompression,
  NovelBookZipEntry, NovelBookEpubMetadata, NovelBookEpub, NovelBookExport,
  NovelBookZipEncoder,
} from './novel/workspace_book_export.ts';
export { buildNovelBookExport, encodeNovelBookEpub } from './novel/workspace_book_export.ts';
export type {
  WorkspaceCas, WorkspaceCommit, WorkspaceBranchMetadata, WorkspaceProposalStatus,
  WorkspaceProposalPatchOperation, WorkspaceProposalPatch, DurableWorkspaceProposal,
  WorkspaceUndoSnapshot, WorkspacePlotState, WorkspaceProposalResolution,
  WorkspaceReceiptReplay,
} from './novel/workspace_history.ts';
export {
  WORKSPACE_HISTORY_VERSION, validateWorkspaceCas, parseWorkspaceCas,
  assertWorkspaceCasMatch, validateWorkspaceCommit, parseWorkspaceCommit,
  classifyReceiptReplay, validateWorkspaceReceiptLedger, validateWorkspaceBranchMetadata,
  parseWorkspaceBranchMetadata, validateWorkspaceProposalPatch,
  validateDurableWorkspaceProposal, parseDurableWorkspaceProposal,
  resolveDurableWorkspaceProposal, validateWorkspaceUndoSnapshot,
  parseWorkspaceUndoSnapshot, unresolvedFromOrdinal, isWorkspacePlotStale,
  refreshWorkspacePlotState, syncWorkspacePlot,
} from './novel/workspace_history.ts';
export type {
  GhostwriteStage, GhostwriteFindingKind, GhostwriteFinding, FrozenPlan,
  GhostwriteCandidate, GhostwriteReview, GhostwriteStateDelta, GhostwriteClaim,
  GhostwriteClaimRef, GhostwriteProgress, DurableGhostwriteJob,
  FreezeGhostwritePlanInput, MakeGhostwriteCandidateInput, MakeGhostwriteJobInput,
} from './novel/ghostwrite.ts';
export {
  MIN_GHOSTWRITE_CHAPTER_COUNT, MAX_GHOSTWRITE_CHAPTER_COUNT,
  DEFAULT_GHOSTWRITE_CHAPTER_COUNT, MAX_GHOSTWRITE_REWRITE_COUNT,
  MIN_GHOSTWRITE_CHAPTER_ORDINAL, MAX_GHOSTWRITE_CHAPTER_ORDINAL,
  defaultGhostwriteDigest, freezeGhostwritePlan, makeGhostwriteCandidate,
  validateGhostwriteReview, makeGhostwriteJob, validateDurableGhostwriteJob,
  ghostwriteReceipt, canTransitionGhostwrite, transitionGhostwriteJob,
  withGhostwriteCandidate, applyGhostwriteReview, claimGhostwriteJob,
  assertGhostwriteClaim, pauseGhostwriteJob, resumeGhostwriteJob,
  projectGhostwriteProgress,
} from './novel/ghostwrite.ts';
export type {
  PolishStage, PolishContextSnapshotKind, PolishContextOptions, PolishContextSnapshotItem,
  PolishWarningKind, PolishWarning, PolishChapterTarget, PolishCandidate, PolishReview,
  PolishProgress, DurablePolishJob,
} from './novel/polish.ts';
export {
  MAX_POLISH_CONTEXT_ITEMS, MAX_POLISH_CONTEXT_ITEM_CHARS, MAX_POLISH_CONTEXT_TOTAL_CHARS,
  makePolishChapterTarget, makePolishContextSnapshotItem, makePolishCandidate,
  validatePolishReview, makePolishJob, validateDurablePolishJob, currentPolishTarget,
  polishReceipt, transitionPolishJob, withPolishCandidate, applyPolishReview,
  claimPolishJob, assertPolishClaim, pausePolishJob, yieldPolishJobToSystem,
  resumePolishJob, failPolishJob, retryPolishJob, cancelPolishJob,
  commitPolishChapter, projectPolishProgress,
} from './novel/polish.ts';
export type {
  NovelRunEvent, NovelRun, NovelActiveRun, NovelCollectionResult,
  NovelSuggestionRefreshResult, NovelCreationDeps,
  NovelPolishEffects,
  NovelConsistencyIssue, NovelConsistencyReport, NovelWorkspaceListResult,
  NovelWorkspaceReadResult, NovelWorkspaceGrepMatch, NovelWorkspaceGrepResult,
} from './novel/creation.ts';
export { NovelCreation, createNovelCreation } from './novel/creation.ts';

// D-127:Deep Read Playbook 仓库(DeepReadPlaybookRepository.kt 全文;
//   assets → loadDefaultMarkdown 注入,File.mtime → FileStore.mtime)
export { DeepReadPlaybookRepository } from './agent/playbook.ts';
export type {
  DeepReadPlaybookSnapshot, DeepReadPlaybookResult, DeepReadPlaybookDeps,
} from './agent/playbook.ts';

export { deepReadToMarkdown, deepReadToText } from './domain/export.ts';
export {
  CLAUDE_REDACTED_THINKING_METADATA_KEY, CLAUDE_THINKING_BLOCK_INDEX_METADATA_KEY,
  hasProtocolReasoningContent, reasoningBlocksCanMerge, mergeReasoningMetadata,
} from './agent/reasoning_metadata.ts';

export type { DeepReadInputSource, DeepReadInputKind, DeepReadInputStatus, DeepReadCollectionIssue } from './domain/input_sources.ts';
export { makeInputSource, sourceInputs, mergeCollectedSources, generationSources, inputSourceLabel, extractDocxSourceText, decodeInputSourceText, MAX_INPUT_SOURCE_CHARS, MAX_GENERATION_SOURCES } from './domain/input_sources.ts';

export { deepReadLibraryStatus, queryDeepReadLibrary } from './domain/library.ts';
export type { DeepReadLibraryStatus, DeepReadLibraryFilter } from './domain/library.ts';

export { defaultNovelModelDefaults, resolveNovelDefaultTarget, novelStorySeedText, projectWithNovelStorySeed } from './novel/standalone_defaults.ts';
export type { NovelModelDefaults, NovelModelRole, NovelStorySeed } from './novel/standalone_defaults.ts';

export { findNovelChapterMatches, replaceNovelChapterMatch } from './novel/chapter_find_replace.ts';

export { copyNovelWorkspaceImportPlan } from './novel/import_conflicts.ts';
export type { NovelImportResolution } from './novel/import_conflicts.ts';
export type { NovelProjectCreationMode } from './novel/models.ts';

export type { NovelMaterialFields, NovelMaterialInjectionMode } from './novel/models.ts';
export { normalizeNovelMaterialFields, novelMaterialAdoptionFields } from './novel/material_fields.ts';
export type { NormalizedNovelMaterialFields } from './novel/material_fields.ts';
export { classifyNovelMaterials, novelMaterialRelevanceScore } from './novel/material_injection.ts';
export type { NovelInjectionOverrides, NovelMaterialInjectionDecision, NovelMaterialInjectionReason } from './novel/material_injection.ts';
export type { NovelContextPreviewReceipt, NovelContextPreviewSection } from './novel/model_running.ts';
export type { NovelContextPreviewResult } from './novel/creation.ts';
export { canCloneCollectedMessage } from './novel/collected_candidates.ts';
export type { NovelOrdinaryRun, NovelOrdinaryRunView, NovelOrdinaryRunStatus, NovelOrdinaryRequest } from './novel/ordinary_run.ts';
export { ordinaryRunHistoryCount, ordinaryRunMessages } from './novel/ordinary_run.ts';
export { effectiveNovelMaterials, materialOrigin } from './novel/material_inheritance.ts';
export type { NovelMaterialOrigin } from './novel/material_inheritance.ts';

export type {
  NovelChapterContractStatus, NovelChapterContractInput, NovelChapterContract, NovelUpcomingArc,
} from './novel/chapter_contract.ts';
export {
  makeNovelChapterContract, chapterContractMarkdown, confirmedChapterPlanText,
  withNovelChapterContract, withNovelUpcomingArc, parseChapterContractProposal, collectChapterContractProposal,
} from './novel/chapter_contract.ts';

export type {
  NovelStateEvent, NovelStateDelta, NovelIdentityAction, NovelIdentityClarification,
  NovelStateChapterSource, NovelStructuredState, NovelCharacterExperience,
} from './novel/structured_state.ts';
export {
  NOVEL_STATE_PROTOCOL_VERSION, emptyNovelStructuredState, parseNovelStateDelta,
  mergeNovelStateDelta, appendNovelStateDelta, pruneNovelStructuredState, invalidateNovelStructuredState,
  projectNovelCharacterExperiences, applyNovelIdentityClarification,
} from './novel/structured_state.ts';

export type {
  NovelProjectOperationKind, NovelProjectToolInput, NovelSpecializedOperation,
  NovelChapterParagraph, NovelProjectToolResult,
} from './novel/specialized_operations.ts';
export {
  novelProjectOperationKind, novelChapterParagraphs, novelOperationSourceDigest,
  readNovelProjectOperation, applyNovelSpecializedOperation, makeNovelSpecializedReview,
  validateNovelSpecializedOperation, makeNovelProjectOperation,
} from './novel/specialized_operations.ts';
export type { WorkspaceProposalPreview, WorkspaceProposalReview } from './novel/workspace_history.ts';

export type { NovelStateSource, NovelStateTarget, NovelStateOperation } from './novel/state_rebuild.ts';
export { STATE_MAX_OUTPUT_TOKENS, STATE_SYSTEM_PROMPT, NovelStateService } from './novel/state_rebuild.ts';
export type { NovelStructuredTaskOptions } from './novel/model_running.ts';
export type { NovelStructuredStateView } from './novel/creation.ts';

export type { PolishChapterOutcome, PolishChapterResult } from './novel/polish.ts';
export { projectPolishOutcomes, recordPolishChapterResult } from './novel/polish.ts';
export type { NovelPolishStartPreview } from './novel/polish_context.ts';

export { DEEPREAD_SYNTHESIS_TEMPLATES, synthesisTemplate, parseSynthesisPick, synthesisPickPrompt, synthesisPrompt as templateSynthesisPrompt, parseSynthesisArticle, hasSynthesisBody, synthesisArticleMarkdown } from './domain/synthesis_templates.ts';
export type { DeepReadSynthesisTemplateId, DeepReadSynthesisTemplate, DeepReadTemplateArticle, DeepReadTemplateSource, DeepReadTemplateBrief, DeepReadTemplateAnswer, DeepReadTemplateCamp, DeepReadTemplateDebate, DeepReadTemplateEvent, DeepReadTemplateTurn, DeepReadTemplateTimeline, DeepReadTemplateReviewView, DeepReadTemplateReviewSplit, DeepReadTemplateReviewSpec, DeepReadTemplateReviewScore, DeepReadTemplateReview } from './domain/synthesis_templates.ts';
