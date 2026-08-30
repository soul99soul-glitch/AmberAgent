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
export type { DeepReadOutput, TimelineEvent, CorePoint, ReadingLink, Perspective, Quote, DeepAnalysis, DeepReadImageAsset, DiagramNode, DiagramEdge, DeepReadDiagram, DeepReadSectionState } from './domain/models.ts';
export { makeEmptyDeepReadOutput } from './domain/models.ts';
export type { DeepReadGenerationStage, DeepReadGenerationPhase, DeepReadSectionStatus } from './domain/enums.ts';
export { STAGE_ORDER, STAGE_LABELS, IMAGE_CONFIDENCE, WRITER_TOOL_NAMES } from './domain/enums.ts';
export { statusOf, sectionsReady, isComplete, firstFailureMessage, firstFailedStage, displayHeroImageUrl, displayHeroCaption, hasReadableArticle } from './domain/helpers.ts';
export type { ModelConfig, ModelRegistry, ModelConfigInit, ModelRegistryOptions } from './domain/model_config.ts';
export { makeModelConfig, newModelId, createStorageModelRegistry, MODEL_CONFIGS_STORAGE_KEY } from './domain/model_config.ts';

// ===== Platform 契约(供 entry 注入真实实现) =====
export type { HttpClient, HttpRequest, HttpResponse } from './platform/http.ts';
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
export type { UIMessage, UIMessagePart, UIMessagePartText, UIMessagePartTool, MessageRole, ToolApprovalState } from './agent/message.ts';
export { makeUIMessage, makeUserMessage, makeAssistantMessage, makeSystemMessage, getTools, isToolExecuted, latestAssistantText } from './agent/message.ts';
export { createScheduler, isInterruptedPhase } from './agent/scheduler.ts';
export type { DeepReadScheduler, SchedulerRunOptions } from './agent/scheduler.ts';
export { createSourcePrefetcher } from './research/source_prefetcher.ts';
export type { SourcePrefetcher, DeepReadSource } from './research/source_prefetcher.ts';
export { buildEvidencePack, cardsFor } from './research/evidence_pack.ts';
export type { DeepReadEvidencePack, DeepReadArticlePlan } from './research/evidence_pack.ts';
export { generateArticlePlan, fallbackPlan } from './research/article_plan.ts';
export { createTavilyProvider, createFallbackProviders } from './research/search_provider.ts';

// ===== Model Council(模型议会) =====
export type {
  ModelCouncilMode, ModelCouncilRunStatus, ReasoningLevel,
  ModelCouncilSeat, ModelCouncilSeatInit, ModelCouncilTaskSpec, ModelCouncilTurn,
  ModelCouncilResult, ModelCouncilRun, ModelCouncilRuntimeSetting, ModelCouncilRuntimeSettingInit,
  ModelCouncilRolePreset,
} from './council/models.ts';
export {
  DEFAULT_MAX_SEATS, DEFAULT_DEFAULT_ROUNDS, DEFAULT_MAX_ROUNDS, DEFAULT_SEAT_TIMEOUT_MS,
  DEFAULT_TOTAL_TIMEOUT_MS, DEFAULT_OUTPUT_BUDGET_CHARS, DEFAULT_WAIT_TIMEOUT_MS,
  PROVIDER_PARALLELISM, SYNTHESIZER_SEAT_KEY, DEFAULT_OUTPUT_FORMAT,
  isRunningStatus, makeSeat, newSeatId, makeRuntimeSetting, makeEmptyResult,
  CORE_SEATS, LENS_PRESETS, ROLE_PRESETS, isCoreRole, findRolePreset,
} from './council/models.ts';
export type { CouncilGenerateRequest, ModelCouncilTextResult, ModelCouncilTextRunner } from './council/runner.ts';
export { Semaphore, withPermit } from './council/semaphore.ts';
export {
  seatSystemPrompt, openingPrompt, responsePrompt, finalPositionPrompt,
  synthesisPrompt, summaryBlock, truncate, SYNTHESIZER_SYSTEM_PROMPT,
} from './council/prompts.ts';
export type { CouncilTaskInput } from './council/validator.ts';
export { parseTask, validateSeats, resolveSynthesisModelId, buildDefaultSeats } from './council/validator.ts';
export type { ModelCouncilDeps } from './council/manager.ts';
export { ModelCouncilManager, createModelCouncilManager } from './council/manager.ts';

// ===== Novel Creation(小说创作) =====
export type {
  NovelMessageRole, NovelChatMode, NovelGenerationGranularity, NovelMaterialKind,
  NovelSuggestionStatus, NovelCollectionTarget, NovelMessage, NovelChapter, NovelMaterial,
  NovelMaterialSuggestion, NovelProject, NovelProjectInit, NovelMessageInit, NovelChapterInit,
  NovelMaterialInit, NovelSuggestionInit,
} from './novel/models.ts';
export {
  NOVEL_SCHEMA_VERSION, MAX_PROJECT_NAME_CHARS, MAX_TITLE_CHARS, MAX_CHAPTER_CHARS,
  MAX_MATERIAL_CHARS, MATERIAL_KIND_LABELS, novelId, makeNovelProject, makeNovelMessage,
  makeNovelChapter, makeNovelMaterial, makeNovelSuggestion,
} from './novel/models.ts';
export type { NovelErrorCode } from './novel/error.ts';
export {
  NovelError, isNovelError, invalidInput, notFound, projectBusy, alreadyCollected,
  providerError, outputTooLarge,
} from './novel/error.ts';
export {
  createProject, renameProject, setProjectModel, appendMessage, saveChapter, deleteChapter,
  upsertMaterial, deleteMaterial, collect, replacePendingSuggestions, resolveSuggestion,
} from './novel/mutations.ts';
export type { NovelMutation } from './novel/mutations.ts';
export { systemPrompt, MAX_USER_CHARS, MAX_OUTPUT_CHARS, MAX_SYSTEM_CHARS } from './novel/context_builder.ts';
export type { NovelModelRequest, NovelModelEvent, NovelModelStream, NovelModelRunning } from './novel/model_running.ts';
export {
  SUGGESTION_SYSTEM_PROMPT, buildSuggestionUserPrompt, mapSuggestionKind, parseSuggestions,
  collectModelText, analyzeChapterSuggestions, MAX_SUGGESTIONS,
} from './novel/suggestion_engine.ts';
export type { NovelProjectRepository } from './novel/repository.ts';
export { createFileNovelRepository } from './novel/repository.ts';
export type {
  NovelRunEvent, NovelRun, NovelCollectionResult, NovelSuggestionRefreshResult, NovelCreationDeps,
} from './novel/creation.ts';
export { NovelCreation, createNovelCreation } from './novel/creation.ts';

// D-127:Deep Read Playbook 仓库(DeepReadPlaybookRepository.kt 全文;
//   assets → loadDefaultMarkdown 注入,File.mtime → FileStore.mtime)
export { DeepReadPlaybookRepository } from './agent/playbook.ts';
export type {
  DeepReadPlaybookSnapshot, DeepReadPlaybookResult, DeepReadPlaybookDeps,
} from './agent/playbook.ts';
