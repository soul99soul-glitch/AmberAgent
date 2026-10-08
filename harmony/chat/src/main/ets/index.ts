// @amber/chat-domain — Chat 核心领域层(纯 ArkTS,零 SDK 依赖)
// 基准:ai/ui/Message.kt, ai/core/{MessageRole,Usage}.kt, core/model/Conversation.kt

export type { JsonValue, JsonObject } from './chat/json.ts';
export { newId, nowIso } from './chat/ids.ts';
export type { ImageExportPage } from './chat/image_export_pages.ts';
export { imageExportPages } from './chat/image_export_pages.ts';

export type { TokenUsage } from './chat/usage.ts';
export { mergeUsage } from './chat/usage.ts';
export type { ContextTokenBatchCounter } from './chat/native_context_tokens.ts';
export { nativeTokenizerForModel, countNativeContextFootprint } from './chat/native_context_tokens.ts';

export type {
  MessageRole, StreamTransportState, ToolApprovalState,
  UIMessagePart, UIMessagePartText, UIMessagePartImage, UIMessagePartVideo,
  UIMessagePartAudio, UIMessagePartDocument, UIMessagePartMiniApp,
  UIMessagePartReasoning, UIMessagePartTool,
  UIMessageUrlContextAnnotation, UIMessageGoogleSearchSuggestionsAnnotation,
  UIMessageAnnotation, UIMessage, UIMessageOpts, UIMessageChoice, MessageChunk,
} from './chat/message.ts';
export {
  makeUIMessage, makeSystemMessage, makeUserMessage, makeAssistantMessage, finishAssistantMessage,
  canResumeToolExecution, isToolExecuted, isToolPending, canToolResumeExecution,
  toolInputAsJson, toText, reasoningPartText, summaryAsText, getTools, isValidToUpload,
  hasBase64Part, isEmptyInputMessage, isEmptyUIMessage,
} from './chat/message.ts';

export type { MessageNode, Conversation } from './chat/conversation.ts';
export {
  DEFAULT_ASSISTANT_ID, makeMessageNode, toMessageNode, nodeCurrentMessage, nodeRole,
  makeConversation, patchConversation, currentMessages, getMessageNodeByMessageId, updateCurrentMessages,
  limitContext, finishReasoning, finishPendingTools, collectFileUrls,
} from './chat/conversation.ts';

export {
  STREAM_TOOL_INDEX_METADATA_KEY, STREAM_TOOL_ARGS_REPLACE_METADATA_KEY,
  REASONING_CONTENT_PRESENT_METADATA_KEY,
  streamToolIndex, withStreamToolIndex, withStreamArgsReplace, isStreamArgsReplace,
  withoutStreamArgsReplace, findToolMergeTarget, mergeTool, hasExplicitReasoningContentField,
} from './chat/tool_merge.ts';

export {
  MessageStreamAccumulator, appendChunkToMessage, handleMessageChunk, coalesceStreamParts,
} from './chat/stream_accumulator.ts';

export { normalizeOpenAIStreamDataLines } from './chat/openai_normalize.ts';
export {
  parseChatCompletionMessage, parseOpenAiAnnotations, parseOpenAiTokenUsage,
  parseOpenAiErrorDetail, parseOpenAiStreamEventData, OpenAiStreamError,
} from './chat/openai_parse.ts';
export type { OpenAiStreamEventResult } from './chat/openai_parse.ts';


export type {
  TransformerClock, TransformerContext,
  MessageTransformer, OutputMessageTransformer, TailSafeOutputMessageTransformer,
} from './chat/transformer_pipeline.ts';
export {
  defaultTransformerClock, clockOf, validateTransformerInvariants,
  applyInputTransformers, applyVisualTransformers,
  applyVisualTransformersStreamingTail, applyOnGenerationFinish,
} from './chat/transformer_pipeline.ts';

export type { TemplateVars } from './chat/transformers.ts';
export {
  applyTemplate, createTemplateTransformer,
  thinkTagTransformer, createThinkTagTransformer, regexOutputTransformer, replaceRegexes,
} from './chat/transformers.ts';

export type {
  ChatStreamProvider, ConversationStore, MemoryConversationStore, ChatTurnDeps, StreamOpts,
} from './chat/chat_turn.ts';
export { createMemoryConversationStore, runChatTurn, runAppendUserMessage } from './chat/chat_turn.ts';
export type {
  InteractiveTurnSnapshot, InteractiveTurnHooks, InteractiveTurnRunResult,
  InteractiveTurnOperation,
} from './chat/interactive_turn_runtime.ts';
export { runInteractiveTurn } from './chat/interactive_turn_runtime.ts';
export type {
  NovelInteractiveRuntimeConfig, NovelInteractiveAdapterDeps,
  NovelResponsesStreamOptions,
} from './chat/novel_interactive_adapter.ts';
export { createNovelInteractiveAdapter } from './chat/novel_interactive_adapter.ts';
export type { NovelWorkspaceToolPort } from './chat/novel_workspace_tools.ts';
export { createNovelWorkspaceTools, novelAuditToolResult } from './chat/novel_workspace_tools.ts';
export { selectMessageBranch, runRegenerateAt } from './chat/regenerate.ts';
export { deleteMessage } from './chat/delete_message.ts';
export { editMessage } from './chat/edit_message.ts';
export { forkConversation } from './chat/fork.ts';
export type { ForkDeps } from './chat/fork.ts';
export { DEFAULT_TITLE_PROMPT, buildTitlePrompt, runAutoTitle } from './chat/auto_title.ts';
export { DEFAULT_SUGGESTION_PROMPT, buildSuggestionPrompt, runSuggestion } from './chat/suggestion.ts';
export type { SuggestionDeps } from './chat/suggestion.ts';
export type { AutoTitleDeps } from './chat/auto_title.ts';
export type { RegenerateDeps } from './chat/regenerate.ts';

// ===== 生成重试(D-050,GenerationRetry.kt 全文) =====
export type {
  GenerationRetrySetting, GenerationFailureCategory, GenerationFailureClassification,
  GenerationRetryDecision,
} from './chat/generation_retry.ts';
export {
  RETRY_STATUS_TEMPLATE, makeGenerationRetrySetting,
  classifyGenerationFailure, decideGenerationRetry, delayForAttempt,
} from './chat/generation_retry.ts';

// ===== 发送排队(D-048,PendingUserMessage.kt / ConversationSession.kt:110-232) =====
export type {
  PendingUserMessageMode, PendingUserMessage, PendingUserMessageOpts,
  EnqueueResult, DequeueNextResult, DequeueManyResult, ChangedList, DispatchPreparation,
} from './chat/pending_queue.ts';
export {
  MAX_PENDING_USER_MESSAGES,
  makePendingUserMessage, isCollectablePending, pendingAsFollowup,
  pendingPreviewText, buildCollectedPendingUserMessage,
  enqueuePendingUserMessage, dequeueNextPendingUserMessage,
  dequeueSteerPendingUserMessages, dequeueLeadingCollectableMessages,
  cancelPendingUserMessage, movePendingUserMessage, convertSteerToFollowup,
  preparePendingMessageForDispatch,
} from './chat/pending_queue.ts';

// ===== 队列持久化(D-052,PendingMessageStore.kt 全文) =====
export type { PendingQueueAuditEvent } from './chat/pending_queue_store.ts';
export {
  pendingQueueKey, pendingQueueAuditKey,
  serializePendingUserMessage, serializePendingQueue, parsePendingQueue,
  persistPendingQueue, persistPendingQueueWithEvent, loadPendingQueue, clearPendingQueueStorage,
  buildAuditEventLine, recordPendingQueueEvent,
} from './chat/pending_queue_store.ts';

// ===== reasoning 卡片显示(D-053,ChatMessageReasoning.kt 全文) =====
export type { ReasoningCardState } from './chat/reasoning_display.ts';
export {
  REASONING_PREVIEW_CHAR_LIMIT, REASONING_EXPANDED_STREAM_CHAR_LIMIT,
  REASONING_EXPANDED_FINAL_CHAR_LIMIT,
  reasoningCardStateExpanded, onReasoningExpandedChange,
  reasoningDisplayLimit, toDisplayReasoningText, isReasoningTailTrimmed,
  reasoningDurationMs, formatThoughtSeconds, reasoningBudgetLabel,
  extractThinkingTitle, resolveOnStreamStart, resolveOnStreamEnd,
} from './chat/reasoning_display.ts';

export { createReasoningLevelMetadataTransformer } from './chat/reasoning_level_metadata.ts';

// ===== 上下文压缩纯逻辑(D-054,Planner/Estimator/Payload/Models 全文) =====
export type {
  ConversationCompact, CompactPolicy, CompactPlan, CompactSummaryPayload, CompactResult,
} from './chat/context_compact.ts';
export { formatNumberInt, formatContextTokens } from './chat/format_number.ts';
export {
  COMPACT_SCHEMA_VERSION, makeCompactPolicy, compactPlanSourceMessageCount,
  weightedTokenChars, partEstimatedChars, partInputFootprintChars,
  estimateTokens, estimateContextWindow, estimateMessagesFootprint,
  takeMiddle, toolResultSummarize, partSummaryLine, buildCompressionInput,
  cleanRaw, cleanMarkdown, cleanHumanText, ensureTerminalPeriod, looksLikeJsonFragment,
  parseCompactSummary, compactTimelineSummary, compactSearchableText,
  compactInjectionText, compactInjectionTextParts,
  validCompletedCompacts, selectCompactsForInjection,
  compactSentenceCount, isHighQualityPayload,
  normalizeCompactModelOutput, compactFallbackPayload, remapCoveredCompactIds,
  planCompaction, planForceCompaction, prepareMessagesWithCompacts,
  fitMessagesToTokenBudget, estimateConversationInputTokens,
  MAX_COMPACT_TIMELINE_SUMMARY_CHARS, MAX_COMPACT_HANDOFF_CHARS,
  DEFAULT_COMPACT_CONTEXT_WINDOW_TOKENS,
} from './chat/context_compact.ts';

// ===== 上下文压缩引擎执行(D-055,ConversationContextEngine/PreparedContextEditor) =====
export type {
  CompressionPromptInput, ContextPreparationStepTrace, ContextPreparationTrace,
  PreparedContextEditResult, CompactStore, MemoryCompactStore,
  CompactEngineDeps, PreparedContext, PrepareContextDeps,
} from './chat/context_engine.ts';
export {
  DEFAULT_COMPRESS_PROMPT, applyPlaceholders, buildCompressionPrompt,
  COMPACT_RETRY_SUFFIX, emptyContextPreparationTrace, editPreparedContext,
  createMemoryCompactStore, invalidateCompacts, copyValidCompactsToConversation,
  compactConversation, ContextCompactionFailedError,
  conversationWithMessagesAsNodes,
  withEffectiveMessages, prepareContext, effectiveContextNextAction,
} from './chat/context_engine.ts';

// ===== 工具执行核心(D-056,Tool/ToolRegistry 策略/PermissionDecisionResolver/
//   AgentToolDispatcher/ToolInvocationHooks/ToolFailure/AgentLoopBudgetPrompt 全文) =====
export type { InputSchemaObj, AgentTool, AgentToolOpts, McpToolIdentity } from './chat/tool.ts';
export {
  makeInputSchemaObj, makeAgentTool, toolParametersToJson, toChatToolDefinition,
} from './chat/tool.ts';
export type { ToolRisk, ToolInvocationPolicy } from './chat/tool_policy.ts';
export {
  toolCategory, toolMutatesState, toolRiskProfile, toolAlwaysAsk,
  requiresFailClosedAutoApproval, toolConcurrencySafe, toolSensitiveRead,
  toolForegroundPackageRequirement, toolSpeculativeBlockReason,
  toolOutputBudgetChars, toolInvocationPolicy, toolInvocationPolicyFromText,
  isPrivateNetworkTarget, inputStringValue, inputBooleanValue,
  containsExternalCliCouncilSeat, allowsExternalCliCouncil,
  enforceOutputBudget, truncatedEnvelope, withDisplayTitleHint,
  DEFAULT_TOOL_OUTPUT_BUDGET_CHARS, MODEL_COUNCIL_TOOL_OUTPUT_BUDGET_CHARS,
  SUB_AGENT_TOOL_OUTPUT_BUDGET_CHARS, EXTERNAL_CLI_COUNCIL_RUNNER_TYPES,
} from './chat/tool_policy.ts';
export type {
  ToolInvocationContext, PermissionDecisionAction, PermissionDecision,
  PermissionDecisionTrace, PermissionResolverOpts,
} from './chat/tool_permission.ts';
export {
  PermissionDecisionResolver, permissionDecisionTraceToJson,
  approvalStateSimpleName, toolHasSessionGrant, policyRequiresSubAgentApproval,
  HISTORY_READ_TOOLS_AUTO_APPROVED_FOR_SUBAGENT, ASK_USER_TOOL_NAME,
} from './chat/tool_permission.ts';
export type {
  ToolInvocationRequest, ToolInvocationResult, ToolInvocationHook,
  AgentToolDispatcherDeps, AutoApprovalReview,
} from './chat/tool_dispatcher.ts';
export {
  AgentToolDispatcher, ToolInvocationBarrierError, defaultToolInvocationHooks,
  createToolTraceHook, createToolArgumentValidationHook, createToolFailureNormalizeHook,
  sanitizedToolFailureMessage, isRecoverableToolFailure,
  toAgentToolFailurePayload, toAgentToolFailureJson,
  withPermissionTrace, withHookMetadata, TOOL_DISPLAY_METADATA_KEYS,
} from './chat/tool_dispatcher.ts';
export type { AgentLoopBudgetStage } from './chat/agent_loop_budget.ts';
export {
  agentLoopBudgetStage, buildAgentLoopBudgetPrompt, agentLoopShouldHideTools,
} from './chat/agent_loop_budget.ts';

// ===== D-057:agentic 工具循环 + 审批 + 首个内置工具 =====
export type { ToolLoopOptions } from './chat/tool_loop.ts';
export {
  MIN_AGENT_TOOL_LOOP_STEPS, MAX_AGENT_TOOL_LOOP_STEPS, DEFAULT_AGENT_MAX_TOOL_LOOP_STEPS,
  runAgenticToolLoop, runChatTurnWithTools, runToolLoopContinuation, runRegenerateAtWithTools,
} from './chat/tool_loop.ts';
export type { RegenerateSeed } from './chat/regenerate.ts';
export { prepareRegenerateSeed } from './chat/regenerate.ts';
export type { ToolApprovalVerdict, ToolApprovalPartLocator, IdleToolBlockerResult } from './chat/tool_approval.ts';
export {
  TOOL_APPROVAL_CONTINUATION_WORDS, isToolApprovalContinuation,
  findToolNameInConversation, conversationHasPendingOrUnexecutedTools,
  resolveApprovalState, applyToolApprovalToConversation, conversationHasPendingTools,
  cancelToolForNewUserMessage, skipStaleToolForContinuation, resolveIdleToolBlocker,
} from './chat/tool_approval.ts';
export { createTimeTool } from './chat/builtin_time_tool.ts';
export { createHealthSummaryTool } from './chat/builtin_local_tools.ts';
export type { HealthReadPort } from './chat/builtin_local_tools.ts';

// ===== D-058:内置工具首批 + ToolRegistry 包装层 =====
export type { ToolMetadata, ToolRegistry } from './chat/tool_registry.ts';
export { toToolMetadata, createToolRegistry } from './chat/tool_registry.ts';
export { createAskUserTool } from './chat/builtin_ask_user_tool.ts';

// ===== D-105:ask_user 问题解析/应答载荷(ChatMessageAskUserStep.kt) =====
export {
  parseAskUserQuestions, buildAskUserAnswerPayload,
  parseAskedAnswers, askAnswerDisplayText,
  isAskUserSubmittable, hasAnyAskAnswer,
} from './chat/ask_user_questions.ts';
export type { AskUserQuestion, AskedAnswers } from './chat/ask_user_questions.ts';
export type { ConversationContextToolsDeps } from './chat/builtin_conversation_tools.ts';
export { createConversationContextTools } from './chat/builtin_conversation_tools.ts';
export type { ConversationHistoryToolsDeps } from './chat/builtin_session_tools.ts';
export { createConversationHistoryTools } from './chat/builtin_session_tools.ts';

// ===== D-059:conversation_queue_* 工具对 =====
export type { ConversationQueueToolsDeps } from './chat/builtin_queue_tools.ts';
export { createConversationQueueTools } from './chat/builtin_queue_tools.ts';

// ===== D-060:自省/发现工具组(tools_list/tool_policy_explain/tool_search) =====
export {
  isResidentTool, createToolsListTool, createToolPolicyExplainTool,
  createToolSearchTool, createRegistryIntrospectionTools,
  TOOL_SEARCH_TOOL_NAME, TOOL_SEARCH_AUTO_THRESHOLD, TOOL_SEARCH_DEFAULT_LIMIT,
} from './chat/builtin_introspection_tools.ts';

// ===== D-061:本地工具三件(run_plan_update/clipboard_tool/deep_read_open) =====
export type {
  ClipboardPort, DeepReadOpenEvent, DeepReadOpenBus, DeepReadOpenToolDeps,
} from './chat/builtin_local_tools.ts';
export {
  createRunPlanUpdateTool, createClipboardTool, createDeepReadOpenTool,
  createDeepReadOpenEvent, DEEP_READ_TITLE_MAX_CHARS,
  parseDeepReadSlashCommand, DEEP_READ_ROUTE_TAG, DEEP_READ_FORCE_FLAGS,
} from './chat/builtin_local_tools.ts';

// ===== D-127:Deep Read Playbook 工具四件(LocalTools.kt:187 无条件) =====
export type {
  DeepReadPlaybookSnapshot, DeepReadPlaybookResult, DeepReadPlaybookPort,
} from './chat/builtin_deepread_playbook_tools.ts';
export { createDeepReadPlaybookTools } from './chat/builtin_deepread_playbook_tools.ts';

// ===== D-062:ToolExposureState 懒暴露 =====
export type { ToolExposureState } from './chat/tool_exposure.ts';
export { createToolExposureState } from './chat/tool_exposure.ts';

// ===== D-063:SpeculativeToolRunner 推测执行 =====
export type {
  SpeculativeToolStatus, SpeculativeToolState,
  SpeculativeToolRunnerDeps, SpeculativeToolRunner,
} from './chat/speculative_tool_runner.ts';
export { createSpeculativeToolRunner } from './chat/speculative_tool_runner.ts';
export type { SessionAccessGrant, GrantValidation } from './chat/session_grant_store.ts';
export {
  SessionAccessGrantStore,
  GRANT_DEFAULT_TTL_MS, GRANT_MAX_GRANT_CHARS, GRANT_MAX_SESSIONS,
} from './chat/session_grant_store.ts';
export type { ContextSearchResult } from './chat/context_engine.ts';
export {
  previewAround, searchConversationContext, expandConversationContext,
  conversationContextStatus,
} from './chat/context_engine.ts';

export {
  serializeUIMessage, parseUIMessage, serializePart, parsePart,
  serializeMessageList, parseMessageList,
} from './chat/serialize.ts';

export {
  CHAT_SCHEMA_SQL, conversationToRow, rowToConversation,
  messageNodeToRow, rowToMessageNode, createMemoryConversationRepository,
  messageNodeRowPlainText, snippetAround, searchMessageHitsIn,
} from './chat/persistence.ts';
export type {
  ConversationRow, MessageNodeRow, ConversationSummary, ConversationRepository,
  ConversationWindow, MessageSearchHit,
} from './chat/persistence.ts';

export {
  makeChatModel, makeProviderSettingOpenAI, makeTextGenerationParams,
  reasoningLevelIsEnabled, reasoningLevelEffort, reasoningLevelBudgetTokens,
} from './chat/provider_model.ts';
export type {
  ModelAbility, Modality, ChatModel, ReasoningLevel,
  OpenAIAuthMode, OpenAIBrand, ProviderSettingOpenAI,
  ChatToolDefinition, CustomBody, TextGenerationParams,
} from './chat/provider_model.ts';

export {
  hostOf, isMiMoProvider, shouldForceReasoningContentForToolCalls, isModelAllowTemperature,
  groupPartsByToolBoundary, buildMessages, mergeCustomBody, buildChatCompletionRequest,
} from './chat/openai_request.ts';
export type {
  ImageEncoder, PartGroup, BuildMessagesOpts, BuildChatCompletionRequestInput,
} from './chat/openai_request.ts';

export { createOpenAIChatApi, asChatStreamProvider, openAIAuthHeaders } from './chat/openai_chat_api.ts';
export { createChatDeepReadAiClient } from './chat/deepread_ai_client.ts';
export type { ChatDeepReadAiClientOptions } from './chat/deepread_ai_client.ts';
export { prepareProviderRuntime, prepareProviderAuth } from './chat/provider_runtime.ts';
export type {
  ProviderRuntimeSnapshot, ProviderRuntimeOptions, ProviderOAuthSnapshot, ProviderAuthOptions, ProviderAuthSnapshot,
} from './chat/provider_runtime.ts';
export type { OpenAIChatApiDeps, OpenAIChatApi, CallOpts } from './chat/openai_chat_api.ts';

// OpenAI Responses API + Grok + Google OAuth 契约 (harmony-agent-core-spiral Phase 1)
export {
  buildResponsesRequestBody, buildResponsesInput, supportsResponsesResume,
  resolveResponseProviderCapabilities, openAIResponsesReasoningEffort,
} from './chat/openai_responses_request.ts';
export type {
  ResponseProviderCapabilities, ResponseCursor, ResponseResumeStore, ResponsesResumeRequest,
  BuildResponsesRequestOpts,
} from './chat/openai_responses_request.ts';
export {
  parseResponsesStreamEvent, parseResponsesOutput, ResponseStreamReconciler,
  OPENAI_TOOL_CALL_ID_METADATA_KEY,
} from './chat/openai_responses_parse.ts';
export { createKvResponseResumeStore, responsesResumeKey } from './chat/openai_responses_resume_store.ts';
export {
  createOpenAIResponsesApi, asResponsesChatStreamProvider,
  responsesEndpointUrl, isOfficialOpenAIHost,
} from './chat/openai_responses_api.ts';
export type { OpenAIResponsesApi, OpenAIResponsesApiDeps } from './chat/openai_responses_api.ts';
export {
  grokAuthStatusFrom, grokNeedsRefresh, resolveGrokBearer, buildGrokCliChatRequest,
  GrokAuthError,
  GROK_OAUTH_AUTHORIZATION_ENDPOINT, GROK_OAUTH_TOKEN_ENDPOINT, GROK_CLI_PROXY_BASE_URL,
  GROK_OAUTH_REDIRECT_URI, GROK_OAUTH_SCOPE, GROK_REFRESH_SKEW_MS,
} from './chat/grok_oauth.ts';
export type { GrokOAuthTokens, GrokAuthStatus, GrokAuthStatusCode, GrokTokenStore } from './chat/grok_oauth.ts';
export {
  classifyGoogleAuthForm, GoogleAuthNotImplementedError, isGoogleAuthNotImplementedError,
  throwGoogleAuthNotImplemented,
} from './chat/google_oauth_contracts.ts';
export type { GoogleAuthNotImplementedKind, GoogleProviderAuthForm } from './chat/google_oauth_contracts.ts';

// Kernel façade (Phase 3)
export {
  createChatKernelHost, isKernelTerminalPhase,
} from './chat/chat_kernel_host.ts';
export type {
  KernelPhase, KernelPhaseContext, KernelTerminalCause, ChatKernelHost, ChatKernelHostDeps,
  ApprovalRequest,
} from './chat/chat_kernel_host.ts';

// 会话导出 + Cron 执行器 (completion spiral Phase 2-3)
export {
  exportConversationMarkdown, exportConversationJson, exportConversationJsonString,
  messageImageExportText, messageArchiveMarkdown, messageArchiveSource,
  exportFileNameFor, maskSensitiveJsonObject, maskSensitiveJsonText,
} from './chat/conversation_export.ts';
export type { ConversationExportOptions, ConversationExportJson, ChatMessageArchiveSource } from './chat/conversation_export.ts';
export { parseGenerativeWidgets, widgetDocument, widgetActionPrompt, sanitizeWidgetHtml } from './chat/generative_widget.ts';
export type { GenerativeWidget, GenerativeWidgetAction, GenerativeWidgetSegment } from './chat/generative_widget.ts';
export {
  runCronTaskOnce, fireDueCronTasks, isCronTaskDue,
} from './chat/agent_cron_run_executor.ts';
export type { CronNotifierPort, CronRunExecutorDeps, CronRunResult } from './chat/agent_cron_run_executor.ts';
export {
  evaluateWebMountWait, parseWaitProbeJson,
} from './chat/webmount_wait.ts';
export type { WebMountWaitConditions, WebMountWaitProbe, WebMountWaitResult } from './chat/webmount_wait.ts';
export {
  createSyncBackup, restoreSyncBackup, inspectSyncArchive, buildSyncPayload, decodeArchive,
  conversationFromSyncDto, settingsFromSyncPayload,
  SYNC_ARCHIVE_VERSION, MANIFEST_ENTRY, PAYLOAD_ENTRY,
} from './chat/sync_snapshot.ts';
export { buildSyncSettingsBlob, applySyncRestoreSettings } from './chat/settings_backup.ts';
export type {
  SyncManifest, SyncCryptoPort, SyncPayload, SyncBackupInput, SyncInspectPreview, SyncRestoreResult,
  SyncConversationDto, SyncRestoreSettings,
} from './chat/sync_snapshot.ts';
export {
  createWebDavClient, webDavBuildUrl, webDavBackupFileName,
} from './chat/sync_webdav.ts';
export type { WebDavConfig, WebDavClient, WebDavRemoteFile, WebDavRequest, WebDavResponse, WebDavTransport } from './chat/sync_webdav.ts';
export {
  createWebMountPkce, buildFeishuAuthorizationUrl, buildFeishuTokenBody,
  parseFeishuTokenResponse, webMountTokenUsable, defaultFeishuRedirectUri,
  feishuTokenEndpoint, feishuAuthorizationEndpoint,
} from './chat/webmount_oauth.ts';
export type {
  WebMountCryptoPort, WebMountPkce, WebMountOAuthToken, WebMountOAuthTokenStore,
} from './chat/webmount_oauth.ts';
export { assertWebMountRequestActive, fetchWebMountRequest } from './chat/webmount_request.ts';
export type { WebMountEnabledCheck } from './chat/webmount_request.ts';
export {
  validateWebMountOAuthApplication, canonicalWebMountOAuthBinding,
  buildWebMountAuthorizationUrl, buildWebMountTokenRequest, parseWebMountTokenResponse,
  webMountOAuthAllowsApiOrigin,
} from './chat/webmount/oauth.ts';
export type {
  WebMountPkceChallenge, WebMountTokenGrant, WebMountTokenRequestSpec,
} from './chat/webmount/oauth.ts';
export {
  parseWebMountHar, webMountHarTemplates, webMountReplayAllowed,
  WEBMOUNT_HAR_MAX_BYTES, WEBMOUNT_HAR_MAX_ENTRIES,
} from './chat/webmount/har.ts';
export {
  WEBMOUNT_READONLY_SITE_PROFILES, webMountSiteProfile, webMountSiteMatchesUrl,
  buildWebMountSiteAdapterScript,
  WEBMOUNT_SITE_ADAPTER_DEFAULT_LIMIT, WEBMOUNT_SITE_ADAPTER_MAX_LIMIT,
} from './chat/webmount/profiles.ts';
export type { WebMountSiteProfile, WebMountSiteField } from './chat/webmount/profiles.ts';
export {
  webMountOriginOf, webMountRedactedDisplayUrl, isWebMountAbsoluteHttpUrl,
  isWebMountMutatingReplayUrl,
} from './chat/webmount/url.ts';
export {
  createWebMountGoalTool, createWebMountGoalAdapter, webMountGoalPendingPart,
  recoverInterruptedWebMountGoals, buildWebMountGoalCandidates,
  webMountGoalRequestHash, webMountGoalRequestHashJson,
} from './chat/webmount/goal.ts';
export type { WebMountGoalCandidate, WebMountGoalHostPorts } from './chat/webmount/goal.ts';
export type {
  WebMountDocumentToken, WebMountElement, WebMountObservation,
  WebMountOAuthApplication, WebMountOAuthApplicationDraft, WebMountOAuthState, WebMountOAuthStatus,
  WebMountHarArchive, WebMountReplayTemplate, WebMountNetworkSummary,
  WebMountSiteAdapterResult, WebMountGoalRequest, WebMountGoalDecision, WebMountGoalCheckpoint,
} from './chat/webmount/models.ts';
export { FeishuDocsClient, FEISHU_APP_ID_KEY, FEISHU_APP_SECRET_KEY } from './chat/feishu_docs_client.ts';
export type { FeishuDocsTokenStore, FeishuDocsClientDependencies } from './chat/feishu_docs_client.ts';
export {
  mergeContinueCandidates, continueStatusText, STATUS_RANK, SOURCE_LABEL,
} from './chat/continue_candidate.ts';
export type { ContinueCandidate, ContinueRoute, ContinueSourceKind, ContinueStatus } from './chat/continue_candidate.ts';
export { buildHealthSummary, healthSummaryToolJson } from './chat/health_summary.ts';
export type { HealthMetricRecord, HealthDailySummary, HealthWeeklySummary, HealthSummary, HealthRecordType } from './chat/health_summary.ts';

// Claude(D-044):请求构建 + 解析 + API 组装
// (SYSTEM_PROMPT_CACHE_* 常量由 context_assembly 导出,claude_request 复用)
export {
  buildClaudeMessageRequest, buildClaudeMessages, insertMessagesCacheControl,
  toClaudeContentBlock, toClaudeToolUseBlock, toClaudeToolResultBlock,
} from './chat/claude_request.ts';
export type {
  ClaudeEncodedImage, ClaudeImageEncoder, BuildClaudeMessageRequestInput,
} from './chat/claude_request.ts';
export {
  parseClaudeMessage, parseClaudeTokenUsage, parseClaudeResponseBody,
  parseClaudeStreamEvent,
} from './chat/claude_parse.ts';
export type { ClaudeStreamEventResult } from './chat/claude_parse.ts';
export {
  createClaudeChatApi, asClaudeChatStreamProvider, ANTHROPIC_VERSION,
} from './chat/claude_chat_api.ts';
export type { ClaudeChatApiDeps } from './chat/claude_chat_api.ts';

// Google(D-045):请求构建 + 解析 + API 组装
export {
  buildGoogleCompletionRequestBody, buildGoogleContents, toGooglePart,
  toGoogleFunctionCallPart, toGoogleFunctionResponsePart,
  removeJsonElements, isGemini3Series, isGemini25Pro, commonRoleToGoogleRole,
} from './chat/google_request.ts';
export type {
  GoogleImageEncoder, GoogleEncodedImage, BuildGoogleCompletionRequestInput,
} from './chat/google_request.ts';
export {
  parseGoogleMessagePart, parseGoogleMessage, parseGoogleUsageMeta,
  parseGoogleResponseBody, parseGoogleStreamEventData,
  createGoogleStreamToolIdAllocator, googleRoleToCommonRole,
  parseSearchGroundingMetadata,
} from './chat/google_parse.ts';
export type { GoogleToolIdAllocator } from './chat/google_parse.ts';
export {
  createGoogleChatApi, asGoogleChatStreamProvider,
} from './chat/google_chat_api.ts';
export type { GoogleChatApiDeps } from './chat/google_chat_api.ts';

export {
  makeAssistant, patchAssistant, makeAssistantRegex, reasoningLevelForModel,
  withReasoningLevelForModel,
} from './chat/assistant.ts';
export type {
  Assistant, AssistantRegex, AssistantAffectScope, Avatar,
  LocalToolOption, MainAgentToolProfile, CustomHeader,
} from './chat/assistant.ts';

// M2.1:模型感知推理档位段集(ModelList.kt:1097-1133)
export { reasoningLevelsForModel } from './chat/reasoning_levels.ts';
export type { ReasoningLevelOption } from './chat/reasoning_levels.ts';

// M2.3:代码块语法高亮(HighlightCodeBlock.kt)
export { highlightCode, tokenizeCode, HIGHLIGHT_COLORS } from './chat/code_highlight.ts';
export type { HighlightToken, HighlightTokenType } from './chat/code_highlight.ts';

// G1-S4:@mention 角色逻辑(MentionRoles.kt + ChatInputComposers.kt mention 段)
export {
  buildMentionRoleItems, filterMentionRoleItems,
  detectMentionContext, replaceMention,
} from './chat/mention_roles.ts';
export type { MentionRoleItem, MentionRoleKind, MentionContext } from './chat/mention_roles.ts';

// G2-S1:流式文本显示 pacing/buffer/safe-slice 纯逻辑(StreamingDisplay.kt + StreamingCharReveal.kt)
export {
  StreamingCharRevealClock,
  streamingDisplayTargetSpeed, streamingDisplayMaxCharsPerEmit,
  streamingDisplayBacklogCatchUpEnd, safeStreamingDisplayEnd,
  safeStreamingTerminalEnd, streamingImmediateDisplayText,
  streamingTailActiveWhen,
} from './chat/streaming_display.ts';

// Phase 3:流式逐字 reveal 状态驱动(可注入时间,对齐 rememberStreamingDisplayText)
export {
  StreamingDisplayState,
  STREAM_DISPLAY_SPEED_ALPHA,
  STREAM_DISPLAY_MIN_EMIT_INTERVAL_MS,
  STREAM_DISPLAY_MIN_FRAME_DELTA_MS,
  STREAM_DISPLAY_MAX_FRAME_DELTA_MS,
  STREAM_DISPLAY_INITIAL_FRAME_DELTA_SECONDS,
} from './chat/streaming_display.ts';
export type {
  StreamingDisplaySliceProxy,
} from './chat/streaming_display.ts';

// Phase 3:Markdown 增量解析缓存(精确 + 前缀/增量,代码围栏/表格边界安全回退)
export {
  MarkdownCache,
  markdownCacheKey,
  markdownPrefixReuseSafe,
  MARKDOWN_CACHE_MAX_ENTRIES,
  MARKDOWN_CACHE_MAX_CHARS,
} from './chat/markdown_cache.ts';
export type {
  MarkdownCacheEntry, MarkdownCacheResult, MarkdownCacheStats,
} from './chat/markdown_cache.ts';

// G2-S3:模型推理能力推断(ChatInputUsage.kt:509-660)
export {
  reasoningFamilyOf, reasoningOptionsForModel, coerceToReasoningOptions,
  providerRoutingKey,
} from './chat/reasoning_family.ts';
export type { ReasoningFamily, ReasoningOption } from './chat/reasoning_family.ts';

// G3-S1:显示偏好设置(PreferencesStore.kt:315-355)
export {
  makeDisplaySetting, loadDisplaySetting, saveDisplaySetting, DISPLAY_SETTING_KEY,
} from './chat/display_setting.ts';
export type { DisplaySetting } from './chat/display_setting.ts';

export {
  serializeAssistant, parseAssistant, serializeAssistantList, parseAssistantList,
} from './chat/assistant_serialize.ts';

export {
  createMemoryKeyValueStore, saveAssistants, loadAssistants, ASSISTANTS_KEY,
  saveProviders, loadProviders, PROVIDERS_KEY,
  saveMcpServers, loadMcpServers, MCP_SERVERS_KEY,
} from './chat/kv_store.ts';
export type { KeyValueStore, MemoryKeyValueStore } from './chat/kv_store.ts';

// D-116:MCP 配置模型/线格式/导入解析器(McpConfig.kt+McpStatus.kt+McpImportParser.kt)
export {
  makeMcpCommonOptions, makeMcpTool, makeMcpSseServer, makeMcpStreamableHttpServer,
  cloneMcpServerConfig,
  MCP_STATUS_IDLE, MCP_STATUS_CONNECTING, MCP_STATUS_CONNECTED,
  mcpStatusReconnecting, mcpStatusError,
} from './chat/mcp_config.ts';
export type {
  McpCommonOptions, McpTool, McpServerConfig, McpSseServerConfig,
  McpStreamableHttpServerConfig, McpServerKind, McpStatus,
} from './chat/mcp_config.ts';
export {
  serializeMcpServerConfigList, parseMcpServerConfigList,
} from './chat/mcp_config_serialize.ts';
export { parseMcpServersFromJson } from './chat/mcp_import.ts';

// D-117:MCP JSON-RPC client 核(kotlin-sdk 0.8.4 Protocol/Client 使用面子集)
export {
  McpClient, McpError, McpTimeoutError,
  JSONRPC_VERSION, MCP_LATEST_PROTOCOL_VERSION, MCP_SUPPORTED_PROTOCOL_VERSIONS,
  MCP_DEFAULT_REQUEST_TIMEOUT_MS,
  MCP_ERROR_CONNECTION_CLOSED, MCP_ERROR_REQUEST_TIMEOUT, MCP_ERROR_METHOD_NOT_FOUND,
} from './chat/mcp_protocol.ts';
export type {
  McpTransport, McpClientInfo, McpContentBlock, McpCallToolResult, McpServerTool,
} from './chat/mcp_protocol.ts';

// D-118:MCP 两 transport(SseClientTransport/StreamableHttpClientTransport 全文)
export {
  McpSseClientTransport, McpStreamableHttpTransport,
  StreamableHttpError, McpSseOpenError,
} from './chat/mcp_transports.ts';
export type {
  McpHttpPort, McpHttpResponse, McpSseEvent, McpSseStream,
  McpPostStreamResponse, McpSseLineStream,
} from './chat/mcp_transports.ts';

// D-119:McpManager 全文(生命周期/sync 合并/退避重连/callTool×2)
export {
  McpManager, mcpCalculateBackoffDelay, base64Decode,
  MCP_MAX_RECONNECT_ATTEMPTS, MCP_BASE_RECONNECT_DELAY_MS, MCP_MAX_RECONNECT_DELAY_MS,
} from './chat/mcp_manager.ts';
export type {
  McpSettingsPort, McpFilesPort, McpManagerDeps, McpMessagePart,
} from './chat/mcp_manager.ts';

// D-120:MCP 工具桥接(管理三件 + createRunTools mcp__ 组)
export {
  createMcpManagementTools, createMcpServerTools,
  mcpArgumentsObject, mcpStatusToString, mcpImportKey, toolOutputPreview,
} from './chat/mcp_tools.ts';
export type {
  McpManagementToolsDeps, McpServerToolsDeps, McpToolActivityPort, McpSkillManagerPort,
} from './chat/mcp_tools.ts';

// D-123:skills 子系统域层(SkillManager.kt/SkillPaths.kt/SkillsTools.kt 全文;
//   mcp_import_from_skill 并入 mcp_tools.ts)
export {
  SkillManager, makeSkillMetadata, skillParseFrontmatter, skillEnsureDescription,
  skillResolveDescription, skillIsPlaceholderDescription, skillExtractBody,
  resolveSkillDirPath, resolveSkillFilePath, isLikelyTextSkillFile,
  canonicalSkillFileName, buildSkillMobileRuntimePrompt, createSkillTools,
  collectSkillFilesFromDirectory, collectWorkspaceSkillFiles, unzipSkillFiles,
  MAX_SKILL_FILE_BYTES,
} from './chat/skills.ts';
export type {
  SkillMetadata, SkillScanIssue, SkillFilePort, SkillDirEntry, SkillManagerDeps,
  SkillWorkspacePort, SkillWorkspaceEntry, CreateSkillToolsDeps, BuiltinSkillAssetPort,
} from './chat/skills.ts';

// D-124:workspace 子系统域层(WorkspaceManager.kt/WorkspacePaths.kt/
//   WorkspaceTools.kt/ToolJson.kt 全文;SAF → POSIX 镜像,
//   configured 恒 true 登记偏差)
export {
  PosixWorkspaceManager, normalizeWorkspacePath, joinWorkspacePath,
  textMimeTypeForPath, toolInputString, toolInputRequiredString, toolInputBoolean,
  toolInputInt, normalizeFileReadMaxChars, buildFileReadJson,
  workspaceActivityInputPreview, createWorkspaceTools,
  FILE_READ_DEFAULT_MAX_CHARS, FILE_READ_HARD_MAX_CHARS,
} from './chat/workspace.ts';
export type {
  WorkspaceEntry, WorkspaceEditResult, WorkspaceSearchResult,
  WorkspaceFsEntry, WorkspaceFsPort, PosixWorkspaceManagerDeps,
} from './chat/workspace.ts';

// D-125:workspace artifact 十一件(WorkspaceArtifactTools.kt 全文;
//   pdf 两件平台阻断显式抛错登记;ExternalFileTools 留 P1)
export {
  createWorkspaceArtifactTools, requireHttpUrl, fileNameFromUrl, safeFileName,
  safeBaseName, mimeFromName, gunzipSplit, safeArchiveTarget, archiveTypeOf,
  crc32, buildZipBytes, dosTimeDate, stripXml, parseXlsxText, artifactScaleDims,
  OFFICE_UNSUPPORTED, PDF_BLOCKED_MESSAGE,
  MAX_HTTP_BODY_BYTES, MAX_DOWNLOAD_BYTES, MAX_ARCHIVE_BYTES,
  MAX_ARCHIVE_ENTRY_BYTES, MAX_IMAGE_BYTES, MAX_WORKSPACE_TEMP_FILE_BYTES,
} from './chat/workspace_artifacts.ts';
export type {
  ArtifactHttpRequest, ArtifactHttpResponse, ArtifactHttpPort,
  ArtifactImageInfo, ArtifactImagePort, DeflateRawPort,
  WorkspaceArtifactToolsDeps, ArchiveType, ZipWriteEntry, ArtifactScaleResult,
} from './chat/workspace_artifacts.ts';

export {
  makeProviderModel, makeBalanceOption,
  makeProviderSettingOpenAIVariant, makeProviderSettingGoogle, makeProviderSettingClaude,
  openAIBrandAvailableAuthModes, openAIAuthModeFixedBaseUrl, googleAuthModeFixedBaseUrl,
  hasUsableAuth, copyProviderSettingWithModels, findProviderForModel,
} from './chat/provider_settings.ts';
export type {
  ProviderModel, BalanceOption, ProviderSetting, ProviderSettingOpenAIVariant,
  ProviderSettingGoogle, ProviderSettingClaude, ModelType, ModalityFull, BuiltInTools,
  GoogleAuthMode,
} from './chat/provider_settings.ts';

export {
  serializeProviderSetting, parseProviderSetting,
  serializeProviderSettingList, parseProviderSettingList,
} from './chat/provider_settings_serialize.ts';
export { providerFromImportJson } from './chat/provider_import.ts';
export { providerBalanceRequestIdentity } from './chat/provider_balance.ts';

// D-133:TopModelMenu 顶部模型快速切换菜单的数据装配纯逻辑
//   (Android feature/ui/components/ai/TopModelMenu.kt)
export { buildTopModelMenuGroups } from './chat/top_model_menu.ts';
export type { TopModelMenuModel, TopModelMenuGroup } from './chat/top_model_menu.ts';

export {
  SYSTEM_PROMPT_CACHE_CONTROL_METADATA, SYSTEM_PROMPT_CACHE_DISABLED, SYSTEM_PROMPT_CACHE_EPHEMERAL,
  buildStaticSystemPromptParts, buildToolSystemPrompt, assembleInternalMessages,
  defaultReasoningLevelForModel, resolveSessionDefaults, mergeCustomParams,
  toChatModel, seedConversationWithPresets,
} from './chat/context_assembly.ts';
export type {
  AssembleInternalInput, SessionGroupDefault, ResolvedSessionDefaults, MergedCustomParams,
} from './chat/context_assembly.ts';

// ===== D-065:搜索 SDK 核心(SearchService.kt + KeyRoulette.kt) =====
export type {
  SearchCommonOptions, SearchResult, SearchResultItem,
  ScrapedResult, ScrapedResultUrl, ScrapedResultMetadata,
  SearchServiceOptions, SearchServiceOptionsType,
  BingLocalOptions, ZhipuOptions, TavilyOptions, ExaOptions, SearXNGOptions,
  LinkUpOptions, BraveOptions, SerperOptions, SerpApiOptions, MetasoOptions,
  OllamaOptions, PerplexityOptions, FirecrawlOptions, JinaOptions, BochaOptions,
  AmberAgentSearchOptions, GrokOptions,
  SearchService, SearchSdkRuntime, KeyRoulette, LruKeyRouletteStore,
} from './search/search_service.ts';
export {
  DEFAULT_SEARCH_COMMON_OPTIONS, SEARCH_SERVICE_TYPES, GROK_DEFAULT_SYSTEM_PROMPT,
  DEFAULT_SEARCH_SERVICE_OPTIONS,
  makeSearchServiceOptions, searchServiceOptionsToJson, searchServiceOptionsFromJson,
  searchResultToJson, scrapedResultToJson,
  initSearchSdk, searchSdkRuntime,
  createDefaultKeyRoulette, createLruKeyRoulette,
} from './search/search_service.ts';

// ===== D-066:免费/内置搜索源(JSON 协议三件 + URL 编解码) =====
export { javaUrlEncodeForm, javaUrlDecodeForm, buildQuery } from './search/url_codec.ts';
export { wikipediaSearch } from './search/wikipedia_service.ts';
export { hackerNewsSearch } from './search/hackernews_service.ts';
export { jinaSearchService } from './search/jina_service.ts';

// ===== D-067:mini-DOM + HTML 抓取源(Bing/DuckDuckGo) =====
export type { HtmlElement, HtmlDocument } from './search/html_dom.ts';
export {
  parseHtml, select, selectFirst, elementText, attr, hasClass,
  parentsOf, parentOf, nextElementSiblingOf,
} from './search/html_dom.ts';
export { bingSearchService } from './search/bing_service.ts';
export { duckDuckGoSearch } from './search/duckduckgo_service.ts';

// ===== D-068a:需 key 搜索源批次 A(AmberAgent/Ollama/Serper/SerpAPI/智谱) =====
export { amberAgentSearchService } from './search/amberagent_service.ts';
export { ollamaSearchService } from './search/ollama_service.ts';
export { serperSearchService } from './search/serper_service.ts';
export { serpApiSearchService } from './search/serpapi_service.ts';
export { zhipuSearchService } from './search/zhipu_service.ts';

// ===== D-068b:需 key 搜索源批次 B(Brave/秘塔/LinkUp/Perplexity/Exa) =====
export { braveSearchService } from './search/brave_service.ts';
export { metasoSearchService } from './search/metaso_service.ts';
export { linkUpService } from './search/linkup_service.ts';
export { perplexitySearchService } from './search/perplexity_service.ts';
export { exaSearchService } from './search/exa_service.ts';

// ===== D-068c:需 key 搜索源批次 C(Tavily/博查/SearXNG/Grok/Firecrawl)+ getService 分派 =====
export { tavilySearchService } from './search/tavily_service.ts';
export { bochaSearchService } from './search/bocha_service.ts';
export { searXNGService } from './search/searxng_service.ts';
export { grokSearchService } from './search/grok_service.ts';
export { firecrawlSearchService } from './search/firecrawl_service.ts';
export { getSearchService } from './search/service_registry.ts';

// ===== D-069:搜索聚合器 + 编排器(变体查询/内置源/webview 兜底) =====
export type { SearchSettings, SearchExecutor, SearchSourceResult, AggregatorDeps } from './search/search_aggregator.ts';
export {
  enabledServices, buildServiceParams, canonicalizeUrl, normalizedTitle, searchAggregatorSearch,
} from './search/search_aggregator.ts';
export type {
  OrchestratorSource, SourceSearchRequest, SourceSearchResult,
  OrchestratorSearchExecutor, OrchestratorDeps,
} from './search/search_orchestrator.ts';
export {
  buildSources, buildQueryVariants, orchestratorCanonicalizeUrl,
  searchOrchestratorSearch, searchOrchestratorStatus, searchOrchestratorExplain,
} from './search/search_orchestrator.ts';
// 免费网页引擎(DDG/Brave/Bing/360/夸克 + Wikipedia/HN)+ 验证码熔断;深度阅读预抓复用
export type { FreeEngineId } from './search/free_web_engines.ts';
export {
  FREE_ENGINE_NAMES, FREE_WEB_ENGINE_IDS, runFreeEngine, looksTechnicalQuery, freeEngineCoolingDown,
} from './search/free_web_engines.ts';

// ===== D-070:搜索工具组 + 搜索偏好持久化 + entry 接线面 =====
export {
  SEARCH_PREFS_KEYS, defaultSearchPrefs, loadSearchPrefs, saveSearchPrefs, updateSearchPrefs,
  initializeDeepReadSearchPrefs,
} from './search/search_prefs.ts';
export { createDeepReadSearchProviders } from './search/deepread_search_registry.ts';
export type { DeepReadSearchGoogle, DeepReadSearchProviderDependencies } from './search/deepread_search_registry.ts';
export { createSearchTools } from './search/search_tools.ts';

// ===== D-071a:视觉兜底(分类器/缓存/OcrTransformer/生成接线) =====
export {
  ImageEncodingError, VisualRecognitionError, hasImageParts,
  shouldFallbackToVisionRecognition, resolveVisionRecognitionPrompt,
  DEFAULT_VISION_RECOGNITION_PROMPT, DEFAULT_OCR_PROMPT, javaStringHashCode,
  runWithVisionFallback, VISION_FALLBACK_STATUS,
} from './chat/vision_fallback.ts';
export type { VisionFallbackHook } from './chat/vision_fallback.ts';
export { createVisionCache } from './chat/vision_cache.ts';
export type { VisionCache, VisionCacheFileStore } from './chat/vision_cache.ts';
export { createOcrTransformer, performImageRecognition } from './chat/ocr_transformer.ts';
export type { OcrTransformerDeps } from './chat/ocr_transformer.ts';

// ===== D-112:文档解析(DocxParser.kt 全文;XmlPull/ZIP 端口) =====
export { parseDocxDocumentXml, parseDocxFromZip } from './chat/docx_parser.ts';
export type {
  XmlPullEvent, XmlPullFactory, XmlPullPort, ZipEntryTextProvider,
} from './chat/xml_pull.ts';

// ===== D-113:ZIP 读取(中央目录序)+ PptxParser 全文 =====
export {
  extractZipEntryData, readZipEntries, readZipEntryBytes, readZipEntryText,
} from './chat/zip_archive.ts';
export type { InflateRawPort, ZipEntryRecord } from './chat/zip_archive.ts';
export { parsePptxFromZip, parsePptxNotesXml, parsePptxSlideXml } from './chat/pptx_parser.ts';

// ===== D-114:EpubParser 全文 =====
export {
  parseEpubContainerXml, parseEpubFromZip, parseEpubOpfXml, parseEpubXhtml,
} from './chat/epub_parser.ts';

// ===== D-111:视觉健康探测 + 图片附件发送守卫 =====
//   (VisionModelHealthChecker.kt / ImageAttachmentValidator.kt 全文)
export {
  checkingVisionModelHealth, isVisionHealthAvailable, probeVisionModelHealth,
  VISION_PROBE_SYSTEM_PROMPT, VISION_PROBE_TINY_PNG, VISION_PROBE_USER_TEXT,
} from './chat/vision_health.ts';
export type { VisionModelHealth, VisionModelHealthKind, VisionProbeDeps } from './chat/vision_health.ts';
export {
  checkingImageAttachment, firstImageBlockingIssue, firstImageBlockingIssueForSend,
  imageAttachmentBlocksSend, inspectImageAttachment, MAX_IMAGES_PER_MESSAGE,
  readableImageError,
} from './chat/image_attachment_guard.ts';
export type {
  ImageAttachmentStatus, ImageAttachmentStatusKind, ImageEncodeCheck, ImageGuardSettings,
} from './chat/image_attachment_guard.ts';

// ===== D-072:时间提醒注入(TimeReminderTransformer.kt 全文) =====
export {
  TIME_GAP_THRESHOLD_SECONDS, applyTimeReminder, createTimeReminderTransformer,
  formatGap, javaLocalDateTimeString, dayOfWeekFullName,
  DAY_OF_WEEK_FULL_EN, DAY_OF_WEEK_FULL_ZH,
} from './chat/time_reminder_transformer.ts';
export type {
  TimeReminderDeps, TimeReminderFormatters,
} from './chat/time_reminder_transformer.ts';

// ===== D-073:占位符替换(PlaceholderTransformer.kt 全文) =====
export {
  PLACEHOLDER_KEYS, createPlaceholderTransformer, replaceAllIgnoreCase,
  replacePlaceholders, resolvePlaceholderValue,
} from './chat/placeholder_transformer.ts';
export type { PlaceholderValues } from './chat/placeholder_transformer.ts';

// ===== D-074:文档内联 prompt(DocumentAsPromptTransformer.kt 全文) =====
export {
  MAX_INLINE_FILE_BYTES, MAX_INLINE_TEXT_CHARS, buildDocumentPrompt,
  createDocumentTransformer, isLikelyTextFile, readDocumentContent,
} from './chat/document_transformer.ts';
export type {
  DocumentFileHandle, DocumentReaderDeps,
} from './chat/document_transformer.ts';

// ===== D-077b:Recent Chats 动态块(GenerationPrompts.kt:219-241) =====
export {
  RECENT_CHATS_PROMPT_LIMIT, buildRecentChatsPrompt, prettyPrintRecentChatsJson,
} from './chat/recent_chats_prompt.ts';
export type { RecentChatItem } from './chat/recent_chats_prompt.ts';

// ===== D-076:base64 图片落盘(Base64ImageToLocalFileTransformer.kt + FilesManager.kt:183-265) =====
export {
  FILE_FOLDER_UPLOAD, MAX_CHAT_ATTACHMENT_BYTES,
  convertBase64ImagePartToLocalFile, createBase64ImageToLocalFileTransformer,
  decodeBase64, extractBase64Payload, isDataImageUrl,
} from './chat/base64_image_transformer.ts';
export type { Base64ImageDeps } from './chat/base64_image_transformer.ts';

// ===== D-079a:图片压缩纯函数(FileEncoder.kt:14-30,53-66,236-303) =====
// PixelMap 解码/EXIF 归一化在 entry(ImageKit),HAR 只承载无平台依赖的部分。
export {
  AVIF_BRANDS, COMPRESS_JPEG_QUALITY, COMPRESS_MAX_DIMENSION, MAX_PICKER_IMAGE_BYTES,
  assertPickerImageSize, HEIF_BRANDS,
  SUPPORTED_IMAGE_TYPES,
  EXIF_ORIENTATION_FLIP_HORIZONTAL, EXIF_ORIENTATION_FLIP_VERTICAL,
  EXIF_ORIENTATION_NORMAL, EXIF_ORIENTATION_ROTATE_180, EXIF_ORIENTATION_ROTATE_270,
  EXIF_ORIENTATION_ROTATE_90, EXIF_ORIENTATION_TRANSPOSE, EXIF_ORIENTATION_TRANSVERSE,
  EXIF_ORIENTATION_UNDEFINED,
  calculateInSampleSize, guessImageMimeType, mapExifOrientationToTransform,
} from './chat/image_compress.ts';
export type { ExifTransformType } from './chat/image_compress.ts';

// ===== D-082:工具画像过滤(ToolProfileFilter.kt 全文 + ToolRegistry.kt:424-457) =====
export {
  PROFILE_CODING_CATEGORIES, PROFILE_MINIMAL_TOOLS, PROFILE_MOBILE_CONTROL_CATEGORIES,
  PROFILE_WEB_READ_TOOLS, PROFILE_WORKSPACE_READ_TOOLS,
  filterToolProfile,
} from './chat/tool_profile_filter.ts';
export type { ToolProfileFilterResult } from './chat/tool_profile_filter.ts';

// ===== D-085a:记忆子系统读路径纯模块 =====
// memory_models(MemoryModels.kt)/memory_time_anchor(MemoryTimeAnchorParser.kt)/
// memory_prompt_builder(MemoryPromptBuilder.kt)/memory_recall(MemoryRecallStore.kt
// 的 rank/score/tokenize 纯函数)。Store 编排与接线留 D-085b。
export {
  DEFAULT_MEMORY_RECALL_SETTING,
  memoryCandidateStatusFromWireName, memoryEventTypeFromWireName,
  memoryKindFromWireName, memoryScopeFromWireName,
  makeMemoryCandidate, makeMemoryEvent, makeMemoryRecord,
} from './chat/memory_models.ts';
export type {
  MemoryCandidate, MemoryCandidateStatus, MemoryEvent, MemoryEventType,
  MemoryKind, MemoryRecallSetting, MemoryRecord, MemoryScope,
} from './chat/memory_models.ts';
export {
  applyMemoryTopicSuggestions, buildMemoryTopicPrompt,
  decodeMemoryTopicSuggestions, isMemoryTopicSource, pruneMemoryTopics,
} from './chat/memory_topics.ts';
export type {
  MemoryTopicSuggestion, MemoryTopicApplyResult, MemoryTopicPruneResult,
} from './chat/memory_topics.ts';
export { renderMemoryDocuments } from './chat/memory_documents.ts';
export type {
  MemoryDocumentProjection, MemoryDerivedDocument,
} from './chat/memory_documents.ts';
export {
  classifyMemoryFreshness, deriveMemoryExpiresAt,
} from './chat/memory_time_anchor.ts';
export type { MemoryFreshness } from './chat/memory_time_anchor.ts';
export {
  buildMemoryContext, isSensitiveMemoryContent, SENSITIVE_MEMORY_TERMS,
} from './chat/memory_prompt_builder.ts';
export {
  memoryRecallScoreToDebugText, rankMemoryRecords, scoreMemoryRecord,
  tokenizeMemoryQuery, USER_ALWAYS_ELIGIBLE_CONFIDENCE,
} from './chat/memory_recall.ts';
export type {
  MemoryRecallFreshness, MemoryRecallScore, MemoryRecallSelection,
} from './chat/memory_recall.ts';

// ===== D-085b:记忆召回编排(MemoryRecallStore.kt:14-53)+ 动态块通道 =====
export {
  buildMemoryRecallPrompt, recallMemoryRecords, recallMemorySelections,
} from './chat/memory_recall_store.ts';
export type {
  MemoryReadRepository, MemoryRecallRuntimeGate,
} from './chat/memory_recall_store.ts';
export {
  filterActiveMemoryRecords, touchMemoryRecords,
} from './chat/memory_recall_store.ts';

// ===== D-085c:memory_* 工具(MemoryTools.kt 全文)+ 写路径纯函数 =====
export {
  createMemoryTools, makeAssistantMemory,
} from './chat/builtin_memory_tools.ts';
export type {
  AssistantMemory, MemoryToolDeps, MemoryToolWriteRequest,
} from './chat/builtin_memory_tools.ts';
export {
  appendMemoryRecord, deleteMemoryRecord, memoriesOfAssistant,
  memoryBucketForScope, memoryKindForBucketAdd, memoryRecordToAssistantMemory,
  memoryScopeForBucket, nextMemoryId, updateMemoryRecordContent,
  MEMORY_BUCKET_GLOBAL, MEMORY_BUCKET_LONG_TERM, MEMORY_BUCKET_SHORT_TERM,
} from './chat/memory_write.ts';
export type { MemoryAddParams, MemoryWriteResult } from './chat/memory_write.ts';

// ===== D-085d:记忆抽取(MemoryExtractionPrompt/CandidateFilter/MemoryExtractor) =====
export { buildMemoryExtractionPrompt } from './chat/memory_extraction_prompt.ts';
export { filterMemoryCandidates } from './chat/memory_candidate_filter.ts';
export type { MemoryFilterResult } from './chat/memory_candidate_filter.ts';
export {
  autoWriteEventMessage, isDurableAutoWriteCandidate, parseMemoryCandidates,
  resetMemoryExtractionDebounce, resolveCandidateExpiresAt, runMemoryExtraction,
  shouldAutoWriteCandidate,
  DURABLE_AUTO_WRITE_CONFIDENCE, SHORT_TERM_PROJECT_AUTO_WRITE_CONFIDENCE,
} from './chat/memory_extractor.ts';
export type {
  MemoryExtractionDeps, MemoryWorkerGate, MemoryWorkerModelResolution,
  ParsedMemoryCandidate,
} from './chat/memory_extractor.ts';

// ===== D-085e:记忆导出导入(FrontmatterCodec + ImportExportManager) =====
export {
  decodeMemoryFrontmatter, encodeMemoryFrontmatter,
} from './chat/memory_frontmatter.ts';
export type { MemoryFrontmatterCodecDeps } from './chat/memory_frontmatter.ts';
export {
  exportMemoriesTo, importMemoriesFrom, memoryEventToJsonText,
  memoryExportFileName, resolveMemoryExportRoot,
} from './chat/memory_import_export.ts';
export type {
  MemoryExportFs, MemoryExportResult, MemoryImportExportDeps, MemoryImportResult,
} from './chat/memory_import_export.ts';
export { upsertMemoryRecord } from './chat/memory_write.ts';

// ===== D-085f:dream 子系统核心(Gate/Prompt/Planner/Applier) =====
export { buildMemoryDreamPrompt } from './chat/memory_dream_prompt.ts';
export {
  buildAgentSoulPrompt, DEFAULT_AGENT_SOUL_MARKDOWN,
} from './chat/agent_soul_prompt.ts';
export { memorySummaryGroups } from './chat/memory_summary.ts';
export type { MemorySummaryGroups } from './chat/memory_summary.ts';
export {
  dreamPlanOnlyApplicable, isAnyDreamEnabled, isDreamMaintenanceEnabled,
  isDreamModelEnabled, makeMemoryDreamPlan, memoryDreamPlanHasChanges,
  memoryDreamPlanSummaryText, mergeDreamPlanWith, parseDreamModelPlanJson,
  planDreamMaintenance, runMemoryDreamApply, runMemoryDreamPlan,
} from './chat/memory_dream.ts';
export type {
  MemoryDaydreamModelResolution, MemoryDreamApplierDeps, MemoryDreamPlan,
  MemoryDreamPlannerDeps, MemoryDreamWorkerGate, MemoryMergeSuggestion,
  MemorySupersedeSuggestion,
} from './chat/memory_dream.ts';

// ===== D-085g:dream plan 持久化 + 运行编排(Coordinator/Scheduler/Worker) =====
export {
  createMemoryDreamPlanStore, decodeDreamPlanJson, encodeDreamPlanJson,
  memoryDreamPlanSourceFromWireName, memoryDreamPlanStatusFromWireName,
  persistedDreamPlanSummary,
} from './chat/memory_dream_plan_store.ts';
export type {
  MemoryDreamPlanDaoPort, MemoryDreamPlanEntity, MemoryDreamPlanSource,
  MemoryDreamPlanStatus, MemoryDreamPlanStore, PersistedMemoryDreamPlan,
} from './chat/memory_dream_plan_store.ts';
export {
  computeDelayUntilNextNightWindowMs, dreamSyncShouldSchedule, localDayStart,
  runMemoryDreamRun, runMemoryDreamWorkerOnce,
  DREAM_EXTRA_OPEN_AGENT_MEMORY, DREAM_NOTIFICATION_ID, DREAM_NOTIFY_FAILED_BIGTEXT_TAKE,
  DREAM_NOTIFY_FAILED_TEXT_TAKE, DREAM_NOTIFY_FAILED_TITLE, DREAM_NOTIFY_PENDING_TITLE,
  DREAM_NOTIFY_RUNNING_TEXT, DREAM_NOTIFY_RUNNING_TITLE,
  MEMORY_DREAM_MANUAL_RUN_NAME, MEMORY_DREAM_WORK_NAME,
} from './chat/memory_dream_run.ts';
export type {
  MemoryDreamReviewNotifier, MemoryDreamRunCoordinatorDeps, MemoryDreamRunGate,
  MemoryDreamRunOutcome, MemoryDreamWorkerRunDeps,
} from './chat/memory_dream_run.ts';

// ===== D-088:Memory 页 dream/候选操作编排(SettingAgentMemoryVM 操作段) =====
export {
  acceptMemoryCandidate, applyMemoryDreamPlanOp, dismissMemoryDreamPlanOp,
  ignoreLowConfidenceMemoryCandidates, ignoreMemoryCandidate,
  memoryDreamApplyFailureMessage, memoryDreamPlanFailureMessage,
  memoryDreamReviewSummaryText, memoryLowConfidenceIgnoredMessage,
  planMemoryDreamOp,
  LOW_CONFIDENCE_CANDIDATE_THRESHOLD,
  MEMORY_DREAM_APPLIED_MESSAGE, MEMORY_DREAM_APPLY_EMPTY_MESSAGE,
  MEMORY_DREAM_PLAN_EMPTY_MESSAGE, MEMORY_DREAM_PLAN_REPLACED_MESSAGE,
  MEMORY_DREAM_PLAN_SAVED_MESSAGE, MEMORY_NO_LOW_CONFIDENCE_MESSAGE,
} from './chat/memory_dream_ops.ts';
export type {
  MemoryCandidateOpsDeps, MemoryDreamApplyOpDeps, MemoryDreamApplyOpResult,
  MemoryDreamPlanOpDeps, MemoryDreamPlanOpResult, MemoryIgnoreLowConfidenceResult,
} from './chat/memory_dream_ops.ts';

// ===== D-086:FilesRepository 跟踪(managed_files) =====
export {
  createFilesRepository, deleteChatFiles, makeManagedFileEntity, trackUploadFile,
  conversationFileUris, removedFileUris,
  FILE_FOLDERS_CHAT_IMAGES, FILE_FOLDERS_IMAGES, FILE_FOLDERS_SKILLS, FILE_FOLDERS_UPLOAD,
} from './chat/managed_files.ts';
export type {
  DeleteChatFilesDeps, FilesRepository, ManagedFileDaoPort, ManagedFileEntity,
} from './chat/managed_files.ts';

// ===== D-087:任务模型解析链(worker/daydream/title/suggestion/compress) =====
export {
  decodeTaskModelReference, encodeTaskModelReference, taskModelReferenceId,
  taskModelReferenceFromId, findTaskProviderModel,
} from './chat/task_model_reference.ts';
export type {
  TaskModelPair, TaskModelAutoReference, TaskModelLegacyReference,
  TaskModelFixedReference, TaskModelReference, TaskProviderModel,
} from './chat/task_model_reference.ts';
export {
  daydreamModelCandidates, memoryWorkerModelCandidates, pickDaydreamModelId,
  pickMemoryWorkerModelId, DEFAULT_AUTO_MODEL_ID, DEFAULT_TASK_MODEL_WORKER_GATE,
  daydreamModelReferences, memoryWorkerModelReferences,
} from './chat/task_model.ts';
export type { TaskModelWorkerGate } from './chat/task_model.ts';

// D-122:AgentToolActivityStore + 沙盒活动时间线(AgentToolActivityStore.kt/
//   AgentRuntimeModels.kt/ChatPage.kt:1166-1470;ToolFailure.kt 复用
//   tool_dispatcher D-056 既有移植,不重复导出)
export {
  AgentToolActivityStore, makeSandboxActivityUiState, compactSandboxText,
  currentRunMessages, isSandboxActivityTool, sandboxActivityTools, toolOutputText,
  toolOutputJson, toolActivityStatus, indicatesFailure, toolActivityOutputTail,
  toolSandboxTitle, toolSandboxInputPreview, toolDefaultRuntime,
  toolDefaultWorkspace, deriveSandboxActivities, mergeSandboxTimeline,
  idleSandboxActivity, withStepProgress, isActiveOperation,
  ACTIVITY_MAX_INPUT_PREVIEW_CHARS, ACTIVITY_MAX_OUTPUT_TAIL_CHARS,
  MAX_SANDBOX_TIMELINE_ITEMS, MAX_SANDBOX_OUTPUT_TAIL_CHARS,
  MAX_SANDBOX_JSON_PARSE_CHARS,
} from './chat/tool_activity.ts';
export type {
  ToolActivityStatus, SandboxActivityUiState, SandboxActivityUiStateOpts,
} from './chat/tool_activity.ts';

// ===== D-128:agent_prompt_config(subagent 数据面 + 提示词配置仓库 + 工具) =====
export type {
  SubAgentMode, SubAgentOverride, SubAgentDefinition, SubAgentDefinitionInit,
  SubAgentRuntimeSetting, PromptConfigFilePort, CouncilSettingStrategy,
  ImagePromptInjectionConfig, PromptConfigWriteResult, AgentPromptSettingsPort,
  AgentPromptConfigToolDeps,
} from './chat/agent_prompt_config.ts';
export {
  AgentPromptConfigRepository, createAgentPromptConfigTool,
  makeSubAgentOverride, makeSubAgentDefinition, makeSubAgentRuntimeSetting,
  makeImagePromptInjectionConfig, subAgentApplyOverride, subAgentFindDefinition,
  subAgentExtractMentions, SUB_AGENT_BUILT_INS, SUB_AGENT_BUILT_IN_IDS,
  DEFAULT_SUB_AGENT_MAX_CONCURRENT_RUNS, DEFAULT_SUB_AGENT_TIMEOUT_MS,
  DEFAULT_SUB_AGENT_MAX_TURNS, DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS,
  DEFAULT_IMAGE_PROMPT_INJECTION, DEFAULT_IMAGE_NEGATIVE_PROMPT_INJECTION,
  DEFAULT_CONTEXT_COMPACTION_HANDOFF_PROMPT,
} from './chat/agent_prompt_config.ts';

// ===== D-132a Task 1:subagent 运行时模型与线格式 =====
export type {
  SubAgentToolProfile, SubAgentTaskSpec, SubAgentTaskSpecInit,
  SubAgentRunStatus, SubAgentResult, SubAgentResultInit,
  SubAgentRun, SubAgentRunInit, SubAgentValidationResult,
  SubAgentActivityPresentation,
} from './chat/subagent_models.ts';
export {
  EXTENDED_SUB_AGENT_TIMEOUT_MS, EXTENDED_SUB_AGENT_OUTPUT_BUDGET_CHARS,
  makeSubAgentTaskSpec, subAgentRunStatusRunning,
  makeSubAgentResult,
  makeSubAgentRun, makeSubAgentValidationResult,
  subAgentActivityPresentation,
} from './chat/subagent_models.ts';

// ===== D-132a Task 2:subagent 稳定名称与验证器 =====
export {
  DEFAULT_DYNAMIC_READ_ONLY_TOOLS, subAgentParseTask,
  subAgentResolveDefinition, subAgentValidateToolAllowlist,
} from './chat/subagent_validator.ts';

// ===== D-132a Task 3:subagent 结构化报告捕获 =====
export {
  SUBAGENT_REPORT_TOOL_NAME, SubAgentReportCapture,
} from './chat/subagent_report_tool.ts';

// ===== D-132a Task 4:subagent child-only 工具作用域 =====
export { scopedSubAgentTools } from './chat/subagent_tool_scope.ts';

// ===== D-132a Task 5:subagent JSONL 转录端口/manager 写入 =====
export type {
  SubAgentTranscriptPort, SubAgentTranscriptTail,
} from './chat/subagent_transcript.ts';
export {
  appendSubAgentTranscriptEvent, readSubAgentDisplayTextFromTranscript,
} from './chat/subagent_transcript.ts';
export { subAgentNameFromTools, subAgentObjectiveFromTools, subAgentWorkSummary, subAgentStatusFromTools, subAgentStatusLabel,
  subAgentFinalTextFromTools, readSubAgentHistoricalText } from './chat/subagent_display.ts';

// ===== D-132b Task 6:isolated child runner =====
export type {
  SubAgentAssistantResolution, SubAgentGenerationRequest, SubAgentGenerationPort,
  ChatStreamSubAgentGenerationPortDeps, SubAgentRunner,
} from './chat/subagent_runner.ts';
export {
  ChatStreamSubAgentGenerationPort, GenerationSubAgentRunner,
} from './chat/subagent_runner.ts';

// ===== D-132b Task 7:SubAgent Manager =====
export type { SubAgentManagerDeps } from './chat/subagent_manager.ts';
export { SubAgentManager } from './chat/subagent_manager.ts';

// ===== D-132c Task 8:SubAgentTools 五件公共工具 =====
export type { SubAgentToolsDeps } from './chat/subagent_tools.ts';
export { SubAgentTools, buildSubAgentMentionOverrideDirective } from './chat/subagent_tools.ts';

// ===== D-129:permissions_status 工具 + AgentPermissionBroker/Registry =====
export type {
  AgentPermissionRisk, AgentPermissionStatus, AgentSpecialAccess,
  RuntimeGrantMode, RuntimePermissionSpec, AgentPermissionCapability,
  AgentPermissionCapabilityInit, AgentPermissionPlatformPort,
} from './chat/permission_status.ts';
export {
  AgentPermissionBroker, AGENT_PERMISSION_CAPABILITIES,
  makeRuntimePermissionSpec, makeAgentPermissionCapability,
  runtimePermissionSpecApplies, capabilityCurrentRuntimePermissions,
  createPermissionsStatusTool,
} from './chat/permission_status.ts';

// ===== D-130:agent_cron 域面 + feature/task 三件套 =====
export type {
  AgentTaskStatus, AgentTaskQueueState, AgentTaskRecoveryState,
  AgentTaskRetryPolicy, AgentTaskRetryPolicyInit,
  AgentTaskOutputRef, AgentTaskOutputRefInit,
  AgentTaskSnapshot, AgentTaskSnapshotInit, AgentTaskSnapshotPatch,
  TaskRecoveryAdapter, AgentTaskFilePort, AgentTaskStoreDeps,
} from './chat/agent_task.ts';
export {
  AgentTaskRecoveryManager, AgentTaskStore,
  makeAgentTaskRetryPolicy, makeAgentTaskOutputRef, makeAgentTaskSnapshot,
  copyAgentTaskSnapshot, agentTaskSnapshotToJson, agentTaskSnapshotFromJson,
  agentTaskStatusRunning, agentTaskStatusToQueueState, agentTaskStatusToRecoveryState,
} from './chat/agent_task.ts';
export type { AgentRuntimeStatus } from './chat/agent_task_scheduler.ts';
export { AgentTaskScheduler } from './chat/agent_task_scheduler.ts';
export type { AgentTaskOutputProbe } from './chat/agent_task_tools.ts';
export { AgentTaskTools } from './chat/agent_task_tools.ts';
export type {
  CronExpression, AgentCronTaskStatus, AgentCronTask, AgentCronTaskInit,
  AgentCronPersistencePort, AgentCronSchedulerPort,
} from './chat/agent_cron.ts';
export {
  AgentCronManager, AgentCronTools,
  cronExpressionParse, cronNextRunAfter, cronIsValidZoneId, cronSystemZoneId,
  makeAgentCronTask,
} from './chat/agent_cron.ts';

// ===== 阶段 A:消息 parts 分组与折叠纯逻辑(audit 1.4, ChatMessageCot.kt 全文) =====
export type {
  ThinkingStep, MessagePartBlock, SubAgentTaskStep, CouncilTaskStep,
} from './chat/message_grouping.ts';
export { groupMessageParts } from './chat/message_grouping.ts';

// ===== 阶段 B:Markdown→HTML 转换器(audit 1.1, ArkUI RichText + HTML 路径) =====
export { markdownToHtml } from './chat/markdown_html.ts';

// ===== P0:Markdown→block AST(替代 RichText,原生 ArkUI 渲染) =====
export { parseMarkdown, parseInline, orderedListLabel } from './chat/markdown_blocks.ts';
export { markdownToPlainText, textPartCopyText, messageCopyText } from './chat/message_copy.ts';
export type {
  MarkdownBlock,
  MarkdownBlockHeading,
  MarkdownBlockParagraph,
  MarkdownBlockCode,
  MarkdownBlockList,
  MarkdownBlockBlockquote,
  MarkdownBlockTable,
  MarkdownTableAlignment,
  MarkdownBlockHr,
  InlineToken,
  InlineTextToken,
  InlineBoldToken,
  InlineItalicToken,
  InlineCodeToken,
  InlineLinkToken,
  InlineImageToken,
  InlineMathToken,
} from './chat/markdown_blocks.ts';
export { composeLatex } from './chat/math_compose.ts';

// ===== Phase C5:Jev 判断服务 =====
export {
  makeJevSettings, makeJevNewPurposeSettings, loadJevSettings, saveJevSettings,
  JEV_TYPESAFE_ENDPOINT, JEV_VERCEL_DEFAULT_BASE,
} from './chat/jev_models.ts';
export type {
  JevMode, JevApiMode, JevSettings, JevSettingsInit, JevPurpose, JevNewPurpose, JevNewPurposeSettings, JevEvaluateResult,
  JevNoulQuestion, JevChoiceQuestion, JevQuestion,
  JevNoulAnswer, JevChoiceAnswer, JevAnswer,
  JevUsage, JevEvaluation, JevCallOutcome, JevCallOk, JevCallSkipped, JevCallFailed,
} from './chat/jev_models.ts';
export {
  buildJevRequest, decodeJevResponse, buildJevNoulBatch, readJevNoulBatchProbabilities,
} from './chat/jev_client.ts';
export type { ToolSearchReranker, ScoredTool } from './chat/builtin_introspection_tools.ts';
export type { JevRequestSpec, JevNoulBatchSpec } from './chat/jev_client.ts';

// ===== Phase 1:消息流跟随底部(纯逻辑,零 SDK 依赖) =====
export type { FollowMode } from './chat/timeline_follow.ts';
export {
  FOLLOW_BOTTOM_TOLERANCE_VP,
  transitionOnUserScroll,
  transitionOnStreamUpdate,
  transitionOnGenerateEnd,
  transitionOnJumpToBottom,
  shouldScrollOnStreamUpdate,
  shouldScrollOnGenerateEnd,
  shouldScrollOnAppend,
  isCloseEnoughToBottom,
  resolveInitialRowIndex,
} from './chat/timeline_follow.ts';

// ===== Phase 5:历史分页窗口加载(纯逻辑,零 SDK 依赖) =====
export type { ConversationTimelineLoadState } from './chat/timeline_load.ts';
export type { ChatTimelineRow } from './chat/timeline_rows.ts';
export { buildChatTimelineRows } from './chat/timeline_rows.ts';
export {
  createInitialTimelineLoadState, applyTailWindow, beginLoadOlder,
  applyOlderPage, canLoadOlder, coversIndex, nextOlderPageOffset,
  mergeOlderNodes,
} from './chat/timeline_load.ts';

// ===== Phase 2:停止生成收口(ChatService.stopGeneration :2408-2455) =====
export {
  CANCEL_TOOL_BY_USER_OUTPUT, CANCEL_TOOL_BY_USER_REASON,
  cancelToolByUser, stopGenerationUpdatedMessage, applyStopGeneration,
} from './chat/stop_generation.ts';

// ===== Phase 2:流式 checkpoint 纯逻辑判定(StreamCheckpoints.kt) =====
export {
  STREAM_CHECKPOINT_INTERVAL_MS, STREAM_CHECKPOINT_MIN_GROWTH_CHARS,
  streamContentLength, streamPartsCanonical, defaultStreamPartsHash,
  streamPartsHash, shouldCheckpoint, checkpointConversationTail,
  checkpointRegenerateConversation,
} from './chat/stream_checkpoint.ts';

// ===== MiniApp 子系统纯领域逻辑(MiniApp*.kt 移植) =====
export {
  MINI_APP_MAX_HTML_BYTES, MINI_APP_VERSION_KEEP_LIMIT, MINI_APP_BOARD_SUMMARY_MAX_CHARS,
  MINI_APP_TITLE_MAX_CHARS, MINI_APP_DESCRIPTION_MAX_CHARS, MINI_APP_ICON_MAX_CHARS,
  MINI_APP_RENAME_TITLE_MAX_CHARS, MINI_APP_RENAME_DESCRIPTION_MAX_CHARS,
  MINI_APP_AUDIT_SUMMARY_MAX_CHARS, MINI_APP_CHANGE_NOTE_MAX_CHARS,
  MINI_APP_SHARED_VALUE_BYTES, MINI_APP_SHARED_NAMESPACE_BYTES,
  MINI_APP_V2_PERMISSIONS, MINI_APP_V3_PERMISSIONS, MINI_APP_V1_PERMISSIONS,
  MINI_APP_CATEGORIES, MINI_APP_PERMISSION_ALIASES,
  MiniAppValidationException, MiniAppSecurityException, MiniAppParseException,
  utf8ByteLength,
} from './chat/miniapp/miniapp_models.ts';
export type {
  MiniAppPermission, MiniAppGrantDecision, MiniAppGeneratedOutput, MiniAppCardRef,
  MiniAppBridgeRequest, MiniAppBridgeResponse, MiniAppRecord, MiniAppVersionRecord,
  MiniAppAuditEntry,
} from './chat/miniapp/miniapp_models.ts';
export {
  makeMiniAppSetting, serializeMiniAppSetting, parseMiniAppSetting,
  loadMiniAppSetting, saveMiniAppSetting, MINI_APP_SETTING_KEY,
  isPermissionGloballyEnabled,
} from './chat/miniapp/miniapp_setting.ts';
export type { MiniAppSetting } from './chat/miniapp/miniapp_setting.ts';
export { validateMiniAppHtml } from './chat/miniapp/miniapp_html_validator.ts';
export { MiniAppOutputParser } from './chat/miniapp/miniapp_output_parser.ts';
export { MiniAppSandbox } from './chat/miniapp/miniapp_sandbox.ts';
export type { MiniAppSandboxDeps } from './chat/miniapp/miniapp_sandbox.ts';
export {
  MINI_APP_SHELL_BASE_URL, injectMiniAppShell,
} from './chat/miniapp/miniapp_shell.ts';
export { BRIDGE_JS } from './chat/miniapp/miniapp_bridge_js.ts';
export { MiniAppUrlGuard, resolveRedirectUrl, isIpLiteralHost } from './chat/miniapp/miniapp_url_guard.ts';
export type { MiniAppUrlRecord, MiniAppHostResolver } from './chat/miniapp/miniapp_url_guard.ts';
export {
  MINI_APP_USER_AGENT, MINI_APP_MAX_REQUEST_BODY_BYTES, MINI_APP_MAX_RESPONSE_BYTES,
  MINI_APP_MAX_TEXT_CHARS, MINI_APP_MAX_IMAGE_BYTES, MINI_APP_MAX_REDIRECTS,
  MINI_APP_ACCEPT_HEADER, MINI_APP_IMAGE_ACCEPT_HEADER,
  isAllowedRequestHeader, buildMiniAppRequestPlan, resolveMiniAppResponseType,
} from './chat/miniapp/miniapp_network_policy.ts';
export type { MiniAppRequestPlan, MiniAppResponseType } from './chat/miniapp/miniapp_network_policy.ts';
export { MiniAppStorage } from './chat/miniapp/miniapp_storage.ts';
export type { MiniAppStorageSnapshot, MiniAppStorageOpts } from './chat/miniapp/miniapp_storage.ts';
export {
  MINI_APP_EVENT_BUS_MAX_SUBSCRIPTIONS_PER_APP,
  MINI_APP_LAUNCH_WINDOW_MS, MINI_APP_LAUNCH_MAX_IN_WINDOW,
  MINI_APP_AI_DAILY_BUDGET,
  createMiniAppEventBus, createMiniAppLaunchLimiter, createMiniAppAiBudget,
  defaultMiniAppAiDayKey,
} from './chat/miniapp/miniapp_runtime.ts';
export type {
  MiniAppEventBus, MiniAppLaunchLimiter, MiniAppAiBudget, MiniAppAiBudgetDeps,
} from './chat/miniapp/miniapp_runtime.ts';
export {
  createMemoryMiniAppRepository, miniAppToCardRef, miniAppMinimalHostContext,
} from './chat/miniapp/miniapp_repository.ts';
export type {
  MiniAppRepository, MiniAppRepositoryDeps, MemoryMiniAppRepository,
  MiniAppSharedDataRecord, MiniAppHostContext,
} from './chat/miniapp/miniapp_repository.ts';
export {
  MINI_APP_INSTRUCTION, MINI_APP_REVISION_HTML_CONTEXT_CHARS,
  isExplicitMiniAppRequest, revisionAppId, revisionVersion,
  createMiniAppPromptTransformer,
} from './chat/miniapp/miniapp_prompt_transformer.ts';
export type { MiniAppPromptTransformerDeps } from './chat/miniapp/miniapp_prompt_transformer.ts';
export {
  mightContainMiniApp, revisionChangeNote, createMiniAppOutputTransformer,
} from './chat/miniapp/miniapp_output_transformer.ts';
export type { MiniAppOutputTransformerDeps } from './chat/miniapp/miniapp_output_transformer.ts';

// E2 Remote SSH shared domain and platform contracts.
export type {
  SSHAuthMethod, SSHHandleKind, SSHReadState, SSHCloseReason,
  SSHNativeErrorCode, SSHNativeError, SSHHandle, SSHProbeOptions,
  SSHProbeResult, SSHConnectionOptions, SSHExecOptions, SSHPtyOptions,
  SSHOutputChunk, SSHReadPacket, SSHCredentialBinding, SSHCredential,
  SSHProfileDraft, SSHProfile, SSHProfileSettings, SSHTrustProbe,
  SSHTargetSnapshot, TerminalCommandRequest, TerminalSessionRequest, TerminalJobSnapshot,
  TerminalSessionSnapshot, TerminalSessionEvent,
} from './chat/terminal/models.ts';
export type { SSHTransportPort, SSHCredentialStorePort, TerminalLogPort } from './chat/terminal/ports.ts';
export { SSHProfileStore } from './chat/terminal/profile_store.ts';
export type { SSHProfileStoreDeps } from './chat/terminal/profile_store.ts';
export { encodeSSHCredential, decodeSSHCredential } from './chat/terminal/credential_codec.ts';
export { TerminalRuntime } from './chat/terminal/runtime.ts';
export type { TerminalRuntimeDeps } from './chat/terminal/runtime.ts';
export { createTerminalTools } from './chat/terminal/tools.ts';
export { createTerminalApprovalGuard } from './chat/terminal_approval.ts';
export type { TerminalApprovalGuard } from './chat/terminal_approval.ts';
export type { TerminalToolsDeps } from './chat/terminal/tools.ts';

// E3 local CPython and authenticated Mosh domain contracts.
export { SSHTargetResolver } from './chat/terminal/target_resolver.ts';
export type { PythonNativeStatus, PythonExecuteOptions, PythonNativeResult,
  PythonExecuteRequest, PythonSnapshot } from './chat/python/models.ts';
export type { PythonTransportPort } from './chat/python/ports.ts';
export { PythonRuntime } from './chat/python/runtime.ts';
export { createPythonTool } from './chat/python/tools.ts';
export type { MoshHandle, MoshOptions, MoshPacket, MoshCloseReason, MoshServerLocale,
  MoshSessionRequest, MoshSessionSnapshot, MoshSessionEvent } from './chat/mosh/models.ts';
export type { MoshTransportPort } from './chat/mosh/ports.ts';
export { MoshRuntime } from './chat/mosh/runtime.ts';
export { createMoshTools } from './chat/mosh/tools.ts';

// E4 declarative Recipes share storage, validation and per-step dispatch.
export type { RecipeInputType, RecipeStep, RecipeManifest, RecipeIssue, RecipeEnvelope,
  RecipeDescriptor, InstalledRecipe, RecipeBinding, RecipeRunCheckpoint,
  RecipeImportCheckpoint } from './chat/recipes/models.ts';
export { RECIPE_SCHEMA, RECIPE_MAX_STEPS, RECIPE_DEFAULT_TIMEOUT_SECONDS,
  RECIPE_MAX_TIMEOUT_SECONDS } from './chat/recipes/models.ts';
export type { RecipeImportPreview, RecipeStore, RecipeExecutionPort,
  RecipeLoopAdapter } from './chat/recipes/ports.ts';
export { decodeRecipe, validateRecipe, canonicalRecipeJSON, recipeEnvelope,
  parseRecipeBinding, normalizeRecipeInputs, RecipeValidationError } from './chat/recipes/validation.ts';
export { createRecipeRun, nextRecipeStep, acceptRecipeStep,
  createRecipeLoopAdapter, recoverInterruptedRecipes } from './chat/recipes/runner.ts';
export type { RecipeLoopAdapterDeps } from './chat/recipes/runner.ts';
export { createRecipeTools } from './chat/recipes/tools.ts';
export type { RecipeToolDeps } from './chat/recipes/tools.ts';

export type { PluginOutputType, PluginHttpMethod, PluginEnvelope, PluginIssue, PluginRemoteManifest,
  PluginCommandManifest, PluginToolManifest, PluginCapabilities, PluginDirectoryMetadata, PluginManifest,
  PluginFile, PluginImplementation, PluginResolvedTool, PluginDescriptor, PluginPackage, PluginValidationResult,
  PluginSource, PluginSignature, PluginTrust, PluginTrustedKey, PluginFailureKind, PluginDiagnostic, PluginHealth,
  InstalledPlugin, PluginReadResult, PluginImportPreview, PluginReceipt, PluginTestContext, PluginTestPlan,
  PluginRunCheckpoint, PluginImportCheckpoint, PluginCheckpoint, PluginJsRequest, PluginJsEvent,
  PluginHttpRequest, PluginHttpResult } from './chat/plugins/models.ts';
export { PLUGIN_SCHEMA, PLUGIN_ARCHIVE_SCHEMA, PLUGIN_MAX_FILES, PLUGIN_MAX_FILE_BYTES,
  PLUGIN_MAX_PACKAGE_BYTES, PLUGIN_MAX_ARCHIVE_BYTES, PLUGIN_PACKAGE_HASH_DOMAIN,
  PLUGIN_SIGNATURE_DOMAIN, PluginHttpError } from './chat/plugins/models.ts';
export type { PluginExecutionPort, PluginLoopAdapter, PluginStore, PluginHashPort, PluginSignaturePort,
  PluginArchivePort, PluginJsPort, PluginHttpPort, PluginWebMountPort, PluginMcpPort } from './chat/plugins/ports.ts';
export { decodePlugin, validatePlugin, canonicalPluginJSON, isCanonicalPluginPath, pluginPathCompare,
  isAllowedPluginFilePath, pluginFileText, pluginPackageHashBytes, preparePluginPackage,
  normalizePluginInputs, validatePluginOutput, PluginValidationError } from './chat/plugins/validation.ts';
export { pluginSchemaIssues, pluginInputSchemaIssues, pluginSchemaValueIssues,
  canonicalPluginValue, pluginJSONEqual } from './chat/plugins/json_schema.ts';
export { pluginNetworkURLAllowed, supportedPluginWebMountActions } from './chat/plugins/broker.ts';
export { createPluginRun, createPluginLoopAdapter, recoverInterruptedPlugins } from './chat/plugins/runner.ts';
export type { PluginLoopAdapterDeps } from './chat/plugins/runner.ts';
export { createPluginTools, getPluginSDK } from './chat/plugins/tools.ts';
export type { PluginToolDeps } from './chat/plugins/tools.ts';

// E9 separate Google authentication identities and protocol ports.
export * from './chat/google_auth.ts';

export type { JevFact, JevApprovalLocator, JevApprovalTriage, JevApprovalSubject, JevApprovalPolicy,
  JevPendingApproval, JevApprovalBatch } from './chat/jev_approval.ts';
export { resolveJevPurposeMode, jevPurposeSettings, jevPurposeConsentUnchanged,
  jevFactFromProbability, buildJevApprovalSubject, readJevApprovalTriage,
  listJevPendingApprovals, jevTaskIntent, buildJevApprovalBatch, completeJevApprovalFacts } from './chat/jev_approval.ts';

export type { JevWebCandidate, JevWebObservation, JevWebDecision } from './chat/jev_web.ts';
export { buildJevWebChoice } from './chat/jev_web.ts';

export type { JevAutoApprovalReviewDeps } from './chat/jev_auto_approval.ts';
export { buildJevAutoApprovalBatch, jevAutoApprovalRiskReasons, jevAutoApprovalReason,
  createJevAutoApprovalReview } from './chat/jev_auto_approval.ts';
export type { JevContextPolicy, JevContextPurpose, JevContextEvaluation, JevContextRuntimeDeps,
  JevPreparedToolResults, JevContextBlock } from './chat/jev_context.ts';
export { JevContextRuntime, JevContextRun, splitJevContextBlocks } from './chat/jev_context.ts';
export type { JevCompletionFacts, JevCompletionNotice, JevCompletionBatch,
  JevVerificationComposerAction } from './chat/jev_completion.ts';
export { JEV_VERIFICATION_PROMPT, jevUnverifiedChanges, jevCompletionBindingMatches,
  jevCompletionNeedsVerification, buildJevCompletionBatch,
  jevVerificationComposerAction } from './chat/jev_completion.ts';
export { ConversationRecapService, recapMessages, recapEligible, recapBranchId, recapIsStale,
  projectRecap, makeRecapInput, parseRecap, decodeStoredRecap } from './chat/conversation_recap.ts';
export type { ConversationRecap, ConversationRecapNode, RecapNodeKind, RecapInput,
  RecapState, RecapServiceDeps } from './chat/conversation_recap.ts';
export { recapCustomBody } from './chat/recap_request.ts';

export { applyUserInputRegexes } from './chat/user_input.ts';

export { createCouncilTools } from './chat/council_tools.ts';
export type { CouncilToolsDeps } from './chat/council_tools.ts';
export { councilRunFromTools } from './chat/council_display.ts';

export * from './chat/native_timeline_motion';
export { makeNovelTextGenerationParams, resolveNovelMaxOutputTokens } from './chat/novel_request_config.ts';
export { applyNovelStateReasoning } from './chat/novel_state_reasoning.ts';
export { freezeNovelRuntimeConfiguration, readNovelRuntimeConfiguration } from './chat/novel_runtime_snapshot.ts';
export type { NovelFrozenRuntimeConfiguration } from './chat/novel_runtime_snapshot.ts';
export { createProviderManagementTools } from './chat/provider_tools.ts';
export type { ProviderManagementToolsDeps } from './chat/provider_tools.ts';
export { collectConversationArtifacts, artifactSourceNodeIndex, widgetArtifactExport } from './chat/conversation_artifacts.ts';
export type {
  ConversationArtifactIndex, ConversationArtifactSource, ConversationImageArtifact,
  ConversationDocumentArtifact, ConversationMiniAppArtifact, ConversationWidgetArtifact, WidgetArtifactExport,
} from './chat/conversation_artifacts.ts';

export { createNovelProjectOperationTools, executeNovelProjectOperation } from './chat/novel_project_operations.ts';
export type { NovelProjectOperationPort } from './chat/novel_project_operations.ts';

// Memory optimization: temporal history, reinforcement and derived profiles.
export { restoreMemoryRecord } from './chat/memory_restore.ts';
export { reinforceMemoryRecords } from './chat/memory_recall_store.ts';
export { isMemoryActive, canPromoteMemory, shouldArchiveIdleMemory, memoryLocalDate, memoryDateLabel } from './chat/memory_lifecycle.ts';
export { applyMemoryExtractionActions, collectMemoryExtractionSources, memoryExpiryOn,
  normalizeMemoryRelativeDates, isGroundedMemoryRewrite } from './chat/memory_extraction_actions.ts';
export type { MemoryExtractionAction, MemoryExtractionApplyResult, MemoryExtractionSource } from './chat/memory_extraction_actions.ts';

export { memoryProfileSources, buildMemoryProfile, coveredMemoryProfileRecords,
  isMemoryProfileCurrent, selectMemoryProfile, MINIMUM_MEMORY_PROFILE_SOURCES, MAX_MEMORY_PROFILE_ITEMS } from './chat/memory_profile.ts';
export type { MemoryProfile, MemoryProfileItem, MemoryProfileSelection, MemoryProfileSourceSnapshot } from './chat/memory_profile.ts';
export { nearDuplicateCandidates, planConfirmedMemoryMerges, remapMemoryProfileItems,
  buildMemoryProfilePassPrompt, parseMemoryProfilePassOutput } from './chat/memory_semantic_dedup.ts';
export type { MemoryNearDuplicateCandidate, MemorySemanticMerge, MemoryProfilePassOutput } from './chat/memory_semantic_dedup.ts';

export { createMemoryCitationTransformer, readMemoryCitationIds, memoryCitationIds, stripMemoryCitations } from './chat/memory_citations.ts';
export { invalidateMemoryTopicsForSource } from './chat/memory_topics.ts';

export { parseStoredMemoryProfile } from './chat/memory_profile.ts';

export { extractionMessagesContaminated } from './chat/memory_extractor.ts';
