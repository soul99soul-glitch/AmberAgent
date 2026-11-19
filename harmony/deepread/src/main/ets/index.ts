// deepread-domain 公共入口 barrel — 保留共享 platform / agent_loop / message / model_config / council 原语
// 已清理 DeepRead 文章抓取/排版与 Novel 小说创作业务导出。

// ===== Domain 核心共享（ModelConfig + RDB 兼容只读模型） =====
export type {
  DeepReadOutput, TimelineEvent, CorePoint, ReadingLink, Perspective, Quote, DeepAnalysis,
  DeepReadImageAsset, DiagramNode, DiagramEdge, DeepReadDiagram, DeepReadSectionState,
  DeepReadImpact, DeepReadUncertainty, DeepReadTemplateSnapshot,
} from './domain/models.ts';
export { makeEmptyDeepReadOutput } from './domain/models.ts';
export type { DeepReadGenerationStage, DeepReadGenerationPhase, DeepReadSectionStatus } from './domain/enums.ts';
export { STAGE_ORDER } from './domain/enums.ts';
export { statusOf, isComplete, firstFailureMessage, firstFailedStage } from './domain/helpers.ts';
export type { ModelConfig, ModelRegistry, ModelConfigInit, ModelRegistryOptions } from './domain/model_config.ts';
export { makeModelConfig, newModelId, createStorageModelRegistry, MODEL_CONFIGS_STORAGE_KEY } from './domain/model_config.ts';

// ===== Platform 契约（供 entry 注入真实实现） =====
export type { HttpClient, HttpRequest, HttpResponse } from './platform/http.ts';
export { httpResponseStatusFromHeaders, isEventStreamResponse } from './platform/http_response_status.ts';
export type { AbortSignalLike, AbortControllerLike } from './platform/runtime_api.ts';
export type { Database } from './platform/database.ts';
export type { Storage } from './platform/storage.ts';
export type { SearchProvider, SearchProviderRegistry, SearchHit } from './platform/search.ts';
export type { AiClient, Tool, GenerateTextParams } from './platform/ai_client.ts';
export type { DeepReadRepository, DeepReadCacheEntry } from './platform/repository.ts';
export type { Observable } from './platform/observable.ts';
export { pollingObservable } from './platform/observable.ts';
export type { Notifier, NotificationKind, NotificationRequest } from './platform/notifier.ts';
export type { FileStore } from './platform/files.ts';
export { joinPath, createMemoryFileStore } from './platform/files.ts';
export { parseOpenAiStreamEvent, createOpenAiTextStream } from './platform/openai_stream.ts';
export type { OpenAiStreamDelta, OpenAiTextStreamSink } from './platform/openai_stream.ts';
export type { DecodeChunk } from './platform/sse_assembler.ts';
export { SseAssembler, createSseStreamHandler } from './platform/sse_assembler.ts';
export type { SseEvent } from './platform/sse_assembler.ts';

// ===== Agent 核心循环与消息原语 =====
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
export {
  CLAUDE_REDACTED_THINKING_METADATA_KEY, CLAUDE_THINKING_BLOCK_INDEX_METADATA_KEY,
  hasProtocolReasoningContent, reasoningBlocksCanMerge, mergeReasoningMetadata,
} from './agent/reasoning_metadata.ts';

// ===== Model Council（模型议会） =====
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
