// @amber/chat-domain — Chat 核心领域层(纯 ArkTS,零 SDK 依赖)
// 基准:ai/ui/Message.kt, ai/core/{MessageRole,Usage}.kt, core/model/Conversation.kt

export type { JsonValue, JsonObject } from './chat/json.ts';
export { newId, nowIso } from './chat/ids.ts';

export type { TokenUsage } from './chat/usage.ts';
export { mergeUsage } from './chat/usage.ts';

export type {
  MessageRole, ToolApprovalState,
  UIMessagePart, UIMessagePartText, UIMessagePartImage, UIMessagePartVideo,
  UIMessagePartAudio, UIMessagePartDocument, UIMessagePartMiniApp,
  UIMessagePartReasoning, UIMessagePartTool,
  UIMessageAnnotation, UIMessage, UIMessageOpts, UIMessageChoice, MessageChunk,
} from './chat/message.ts';
export {
  makeUIMessage, makeSystemMessage, makeUserMessage, makeAssistantMessage,
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
  thinkTagTransformer, regexOutputTransformer, replaceRegexes,
} from './chat/transformers.ts';

export type {
  ChatStreamProvider, ConversationStore, MemoryConversationStore, ChatTurnDeps, StreamOpts,
} from './chat/chat_turn.ts';
export { createMemoryConversationStore, runChatTurn, runAppendUserMessage } from './chat/chat_turn.ts';
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
  persistPendingQueue, loadPendingQueue,
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

// ===== 上下文压缩纯逻辑(D-054,Planner/Estimator/Payload/Models 全文) =====
export type {
  ConversationCompact, CompactPolicy, CompactPlan, CompactSummaryPayload, CompactResult,
} from './chat/context_compact.ts';
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
export type { InputSchemaObj, AgentTool, AgentToolOpts } from './chat/tool.ts';
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
  AgentToolDispatcherDeps,
} from './chat/tool_dispatcher.ts';
export {
  AgentToolDispatcher, defaultToolInvocationHooks,
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
export type { ToolApprovalVerdict, IdleToolBlockerResult } from './chat/tool_approval.ts';
export {
  TOOL_APPROVAL_CONTINUATION_WORDS, isToolApprovalContinuation,
  findToolNameInConversation, conversationHasPendingOrUnexecutedTools,
  resolveApprovalState, applyToolApprovalToConversation, conversationHasPendingTools,
  cancelToolForNewUserMessage, skipStaleToolForContinuation, resolveIdleToolBlocker,
} from './chat/tool_approval.ts';
export { createTimeTool } from './chat/builtin_time_tool.ts';

// ===== D-058:内置工具首批 + ToolRegistry 包装层 =====
export type { ToolMetadata, ToolRegistry } from './chat/tool_registry.ts';
export { toToolMetadata, createToolRegistry } from './chat/tool_registry.ts';
export { createAskUserTool } from './chat/builtin_ask_user_tool.ts';
export type { ConversationContextToolsDeps } from './chat/builtin_conversation_tools.ts';
export { createConversationContextTools } from './chat/builtin_conversation_tools.ts';
export type { ConversationHistoryToolsDeps } from './chat/builtin_session_tools.ts';
export { createConversationHistoryTools } from './chat/builtin_session_tools.ts';

// ===== D-059:conversation_queue_* 工具对 =====
export type { ConversationQueueToolsDeps } from './chat/builtin_queue_tools.ts';
export { createConversationQueueTools } from './chat/builtin_queue_tools.ts';
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
} from './chat/persistence.ts';
export type {
  ConversationRow, MessageNodeRow, ConversationSummary, ConversationRepository,
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
export type { OpenAIChatApiDeps, OpenAIChatApi, CallOpts } from './chat/openai_chat_api.ts';

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
} from './chat/assistant.ts';
export type {
  Assistant, AssistantRegex, AssistantAffectScope, Avatar,
  LocalToolOption, MainAgentToolProfile, CustomHeader,
} from './chat/assistant.ts';

export {
  serializeAssistant, parseAssistant, serializeAssistantList, parseAssistantList,
} from './chat/assistant_serialize.ts';

export {
  createMemoryKeyValueStore, saveAssistants, loadAssistants, ASSISTANTS_KEY,
  saveProviders, loadProviders, PROVIDERS_KEY,
} from './chat/kv_store.ts';
export type { KeyValueStore, MemoryKeyValueStore } from './chat/kv_store.ts';

export {
  makeProviderModel, makeBalanceOption,
  makeProviderSettingOpenAIVariant, makeProviderSettingGoogle, makeProviderSettingClaude,
  openAIBrandAvailableAuthModes, openAIAuthModeFixedBaseUrl, googleAuthModeFixedBaseUrl,
  hasUsableAuth,
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

export {
  SYSTEM_PROMPT_CACHE_CONTROL_METADATA, SYSTEM_PROMPT_CACHE_DISABLED, SYSTEM_PROMPT_CACHE_EPHEMERAL,
  buildStaticSystemPromptParts, assembleInternalMessages,
  resolveSessionDefaults, mergeCustomParams, toChatModel, seedConversationWithPresets,
} from './chat/context_assembly.ts';
export type {
  AssembleInternalInput, SessionGroupDefault, ResolvedSessionDefaults, MergedCustomParams,
} from './chat/context_assembly.ts';
