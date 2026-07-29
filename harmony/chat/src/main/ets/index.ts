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
  McpManagementToolsDeps, McpServerToolsDeps, McpToolActivityPort,
} from './chat/mcp_tools.ts';

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

export {
  SYSTEM_PROMPT_CACHE_CONTROL_METADATA, SYSTEM_PROMPT_CACHE_DISABLED, SYSTEM_PROMPT_CACHE_EPHEMERAL,
  buildStaticSystemPromptParts, buildToolSystemPrompt, assembleInternalMessages,
  resolveSessionDefaults, mergeCustomParams, toChatModel, seedConversationWithPresets,
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

// ===== D-070:搜索工具组 + 搜索偏好持久化 + entry 接线面 =====
export {
  SEARCH_PREFS_KEYS, defaultSearchPrefs, loadSearchPrefs, saveSearchPrefs, updateSearchPrefs,
} from './search/search_prefs.ts';
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
export { extractZipEntryData, readZipEntries, readZipEntryText } from './chat/zip_archive.ts';
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

// ===== D-075:提示词注入(PromptInjectionTransformer.kt 全文 + Assistant.kt:140-287) =====
// (SYSTEM_PROMPT_CACHE_* 常量已在 context_assembly.ts 导出块,D-045)
export {
  applyInjections, cacheDisabledMetadata, collectInjections,
  createMergedInjectionMessages, createPromptInjectionTransformer,
  extractContextForMatching, findSafeInsertIndex, isTriggered,
  makeLorebook, makeModeInjection, makeRegexInjection, transformMessages,
} from './chat/prompt_injection_transformer.ts';
export type {
  InjectionPosition, InjectionRole, Lorebook, PromptInjection,
  PromptInjectionDeps, RegexInjection,
} from './chat/prompt_injection_transformer.ts';

// ===== D-100:注入序列化 + mode_injections/lorebooks 写路径 =====
export {
  decodeLorebooks, decodeModeInjections, encodeLorebooks, encodeModeInjections,
  lorebookFromJson, lorebookToJson, modeInjectionFromJson, modeInjectionToJson,
  regexInjectionFromJson, regexInjectionToJson,
} from './chat/injection_serialize.ts';

// ===== D-079a:图片压缩纯函数(FileEncoder.kt:14-30,53-66,236-303) =====
// PixelMap 解码/EXIF 归一化在 entry(ImageKit),HAR 只承载无平台依赖的部分。
export {
  AVIF_BRANDS, COMPRESS_JPEG_QUALITY, COMPRESS_MAX_DIMENSION, HEIF_BRANDS,
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
  daydreamModelCandidates, memoryWorkerModelCandidates, pickDaydreamModelId,
  pickMemoryWorkerModelId, DEFAULT_AUTO_MODEL_ID, DEFAULT_TASK_MODEL_WORKER_GATE,
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
