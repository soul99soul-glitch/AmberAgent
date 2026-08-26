package app.amber.core.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.MessageRole
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.core.Tool
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.providers.GoogleProvider
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.providers.openai.supportsResponsesResume
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.canResumeToolExecution
import app.amber.ai.ui.finishPendingTools
import app.amber.ai.ui.finishReasoning
import app.amber.ai.ui.isEmptyInputMessage
import app.amber.common.android.Logging
import app.amber.agent.AppScope
import app.amber.agent.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import app.amber.agent.BuildConfig
import app.amber.agent.R
import app.amber.agent.RouteActivity
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.Generator
import app.amber.core.ai.mcp.McpManager
import app.amber.core.ai.mcp.createMcpTools
import app.amber.core.ai.tools.LocalTools
import app.amber.core.ai.tools.buildMemoryTools
import app.amber.core.ai.tools.createSoulTools
import app.amber.core.ai.tools.createMcpManagementTools
import app.amber.core.ai.tools.createProviderConfigTools
import app.amber.core.ai.tools.createSearchTools
import app.amber.core.ai.tools.createSkillTools
import app.amber.core.ai.tools.createThemePackTools
import app.amber.core.ai.tools.TOOL_THEME_PACK_IMPORT
import app.amber.core.ai.tools.TOOL_THEME_PACK_STATUS
import app.amber.core.files.SkillManager
import app.amber.core.ai.transformers.Base64ImageToLocalFileTransformer
import app.amber.core.ai.transformers.DocumentAsPromptTransformer
import app.amber.core.ai.transformers.MiniAppOutputTransformer
import app.amber.core.ai.transformers.MiniAppPromptTransformer
import app.amber.core.ai.transformers.OcrTransformer
import app.amber.core.ai.transformers.PlaceholderTransformer
import app.amber.core.ai.transformers.PromptInjectionTransformer
import app.amber.core.ai.transformers.RegexOutputTransformer
import app.amber.core.ai.transformers.TemplateTransformer
import app.amber.core.ai.transformers.ThinkTagTransformer
import app.amber.core.ai.transformers.TimeReminderTransformer
import app.amber.feature.runtime.AgentLiveStatusNotifier
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.runtime.AgentToolDispatcher
import app.amber.feature.runtime.ApprovalHistoryEntry
import app.amber.feature.runtime.CapabilityPermissionStore
import app.amber.feature.runtime.argsDigest
import app.amber.feature.modelcouncil.ModelCouncilManager
import app.amber.feature.history.SessionAccessGrantStore
import app.amber.feature.task.AgentTaskScheduler
import app.amber.feature.task.AgentTaskRetryPolicy
import app.amber.feature.task.AgentTaskSnapshot
import app.amber.feature.task.AgentTaskStatus
import app.amber.feature.tools.AgentTaskTools
import app.amber.feature.tools.ConversationContextTools
import app.amber.feature.tools.ConversationHistoryTools
import app.amber.feature.tools.ModelCouncilTools
import app.amber.feature.tools.SubAgentTools
import app.amber.feature.tools.ToolProfileFilter
import app.amber.feature.tools.ToolRegistry
import app.amber.feature.tools.capabilityForTool
import app.amber.feature.tools.createToolSearchTool
import app.amber.feature.subagent.SubAgentManager
import app.amber.feature.workspace.WorkspaceManager
import app.amber.core.automation.ScreenCaptureManager
import app.amber.core.context.ActiveCompactBoundary
import app.amber.core.context.CompactLifecycleState
import app.amber.core.context.ConversationContextEngine
import app.amber.core.settings.toCompactPolicy
import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import app.amber.core.settings.MAX_AGENT_TOOL_LOOP_STEPS
import app.amber.core.settings.MIN_AGENT_TOOL_LOOP_STEPS
import app.amber.core.settings.Settings
import app.amber.core.settings.AMBER_AGENT_LOCAL_TOOLS
import app.amber.core.settings.AMBER_AGENT_TOOL_PROFILE
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.findProvider
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.settings.resolveTaskChatModel
import app.amber.feature.ui.theme.ThemePackageManager
import app.amber.core.files.FilesManager
import app.amber.core.memory.extraction.MemoryExtractor
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.model.toMessageNode
import app.amber.core.repository.ConversationRepository
import app.amber.core.repository.MemoryRepository
import app.amber.core.utils.applyPlaceholders
import app.amber.core.utils.ChatSendTransitionTracker
import app.amber.core.utils.sendNotification
import app.amber.feature.runtime.NotificationApprovalCheck
import app.amber.feature.runtime.NotificationApprovalTokenRegistry
import app.amber.feature.runtime.OutcomeUnknownPrompt
import app.amber.feature.runtime.PauseReason
import app.amber.feature.runtime.RunOwnershipRegistry
import app.amber.feature.runtime.RunRecoveryService
import app.amber.feature.runtime.RunTerminalState
import app.amber.feature.runtime.RunTerminalStore
import app.amber.feature.runtime.StoredResponseGateway
import app.amber.feature.runtime.StoredResponseStopCancel
import app.amber.feature.runtime.ToolEffectLedger
import app.amber.feature.runtime.ToolEffectStatus
import app.amber.feature.runtime.terminalForFlowEnd
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

/** Stable IDs keep concurrent conversations/runs from replacing each other's notifications. */
internal fun generationDoneNotificationId(conversationId: Uuid, runId: String?): Int =
    stableGenerationNotificationId("completed", conversationId, runId, offset = 30_000)

internal fun generationNotificationPendingIntentRequestCode(
    conversationId: Uuid,
    runId: String?,
): Int = stableGenerationNotificationId("pending-intent", conversationId, runId, offset = 40_000)

private fun stableGenerationNotificationId(
    kind: String,
    conversationId: Uuid,
    runId: String?,
    offset: Int,
): Int {
    val hash = "$kind|$conversationId|${runId.orEmpty()}".hashCode() and Int.MAX_VALUE
    return offset + (hash % 1_000_000)
}

private const val GENERATION_CHECKPOINT_INTERVAL_MS = 10_000L
private const val INITIAL_TIMELINE_NODE_COUNT = 80
private const val TIMELINE_PREFETCH_BATCH_SIZE = 40
private const val ASK_USER_TOOL_NAME = "ask_user"
private const val WEBVIEW_SEARCH_OPEN_TOOL_NAME = "webview_search_open"

private val TOOL_APPROVAL_CONTINUATION_WORDS = setOf(
    "继续",
    "继续吧",
    "可以继续",
    "执行",
    "执行吧",
    "确认",
    "同意",
    "批准",
    "ok",
    "yes",
    "y",
    "continue",
    "goahead",
    "approve",
    "approved",
)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * P8-07: 错误按会话过滤。带 conversationId 的错误只在对应会话展示；
 * conversationId 为 null 的是全局错误（如全局 Provider 配置错误），
 * 走全局 banner，不伪装成某个会话的消息错误。
 */
internal fun List<ChatError>.errorsForConversation(conversationId: Uuid): List<ChatError> =
    filter { it.conversationId == conversationId }

internal fun List<ChatError>.globalErrors(): List<ChatError> =
    filter { it.conversationId == null }

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        MiniAppPromptTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        MiniAppOutputTransformer,
        RegexOutputTransformer,
    )
}

private sealed interface PendingMessageStoreOp {
    data class Persist(
        val conversationId: Uuid,
        val messages: List<PendingUserMessage>,
        val revision: Long,
    ) : PendingMessageStoreOp

    data class Event(
        val conversationId: Uuid,
        val event: String,
        val messageId: String?,
        val count: Int?,
        val detail: String?,
    ) : PendingMessageStoreOp
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsAggregator,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generator: Generator,
    private val templateTransformer: TemplateTransformer,
    private val providerCatalog: ProviderCatalog,
    private val googleProvider: GoogleProvider,
    private val json: Json,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val activityStore: AgentToolActivityStore,
    private val liveStatusNotifier: AgentLiveStatusNotifier,
    private val screenCaptureManager: ScreenCaptureManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceManager: WorkspaceManager,
    private val contextEngine: ConversationContextEngine,
    private val subAgentManager: SubAgentManager,
    private val modelCouncilManager: ModelCouncilManager,
    private val agentTaskScheduler: AgentTaskScheduler,
    private val sessionAccessGrantStore: SessionAccessGrantStore,
    private val memoryExtractor: MemoryExtractor,
    private val pendingMessageStore: PendingMessageStore,
    private val userInputPreprocessor: UserInputPreprocessor,
    private val agentRunner: app.amber.core.agent.runtime.AgentRunner? = null,
    private val agentEventStore: app.amber.core.agent.runtime.AgentEventStore? = null,
    private val capabilityFlags: CapabilityFlags? = null,
    private val toolEffectLedger: ToolEffectLedger? = null,
    private val runTerminalStore: RunTerminalStore? = null,
    private val runRecovery: RunRecoveryService? = null,
    private val runOwnershipRegistry: RunOwnershipRegistry? = null,
    // P8-10: one-time approval tokens for notification approve/deny/reply
    // actions — nullable so legacy construction sites stay untouched.
    private val notificationApprovalTokens: NotificationApprovalTokenRegistry? = null,
    private val capabilityPermissionStore: CapabilityPermissionStore? = null,
    // P4-01: declarative recipe execution — step-level dispatch reuses the
    // same AgentToolDispatcher chain as normal tool calls.
    private val toolDispatcher: AgentToolDispatcher? = null,
    private val recipeRegistry: app.amber.feature.recipe.RecipeRegistry? = null,
    // P4-03: persistent JS cells — the js_cell_* tools are only added to the
    // round catalog when the js_cell_runtime capability flag is on.
    private val jsCellRuntime: app.amber.feature.jscell.JsCellRuntime? = null,
    // P6-01: server-side stored OpenAI Responses resume — cursor store,
    // provider resolver and the Stop-path server cancel. All nullable so the
    // legacy path (and existing constructor call sites) stays untouched when
    // the capability is off.
    private val responsesResumeStore: app.amber.ai.provider.ResponseResumeStore? = null,
    private val storedResponseGateway: StoredResponseGateway? = null,
    private val storedResponseStopCancel: StoredResponseStopCancel? = null,
    // Provider 配置工具（provider_config_* / settings_set_model_slot）需要
    // SecretStore 判断 has_api_key（不解密真值）。nullable 兼容旧构造点。
    private val secretStore: app.amber.core.settings.secret.SecretStore? = null,
    // Android 主题包工具只在前台 Chat 注册；SubAgent / 后台 debug catalog 不可达。
    private val themePackageManager: ThemePackageManager? = null,
) : ConversationAccess {
    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)
    private val trustedRunToolNames = ConcurrentHashMap<Uuid, Set<String>>()
    private val generationCheckpointAt = ConcurrentHashMap<Uuid, Long>()
    private val timelineLoadMutexes = ConcurrentHashMap<Uuid, Mutex>()
    private val conversationInitMutexes = ConcurrentHashMap<Uuid, Mutex>()
    /** P6-01: runs whose terminal publish must stay WAITING_EXTERNAL because the server cancel could not be confirmed. */
    private val pendingServerCancelFailures = ConcurrentHashMap.newKeySet<String>()
    /** 已删除会话的 tombstone：阻止 checkpoint / saveConversation 等后台写者把会话重新插入。 */
    private val deletedConversationIds = ConcurrentHashMap.newKeySet<Uuid>()
    private val pendingMessageStoreOps = Channel<PendingMessageStoreOp>(Channel.UNLIMITED)
    private val pendingMessagePersistRevisions = ConcurrentHashMap<Uuid, AtomicLong>()
    private val pendingMessagePersistLocks = ConcurrentHashMap<Uuid, Mutex>()

    private val aiAuxiliaryGenerator = AiAuxiliaryGenerator(
        context = context,
        settingsStore = settingsStore,
        providerCatalog = providerCatalog,
        conversationRepo = conversationRepo,
        conversationAccess = this,
    )

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    override fun addError(error: Throwable, conversationId: Uuid?, title: String?) {
        if (error is CancellationException) return
        _errors.update { it + ChatError(title = title, error = error, conversationId = conversationId) }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>(extraBufferCapacity = 16)
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    // P1-02: OutcomeUnknown prompts per conversation (tool effect outcome lost
    // after an interruption; the user must confirm retry or abandon).
    private val _outcomeUnknown = MutableStateFlow<Map<String, List<OutcomeUnknownPrompt>>>(emptyMap())
    val outcomeUnknownFlow: StateFlow<Map<String, List<OutcomeUnknownPrompt>>> = _outcomeUnknown.asStateFlow()

    /**
     * Durable runtime path (P1-02 + P1-03): active only when both feature
     * flags are on. Off keeps the exact legacy behavior; the decision is made
     * once per run and never switches mid-run.
     */
    private suspend fun useDurableRuntime(): Boolean =
        capabilityFlags != null &&
            toolEffectLedger != null &&
            runTerminalStore != null &&
            runRecovery != null &&
            capabilityFlags.isEnabled(Capability.DurableToolEffects) &&
            capabilityFlags.isEnabled(Capability.TypedRunTerminal)

    /**
     * P6-01: run states that re-attach to a server-side stored response when
     * generation is (re)started for the conversation — paused mid-stream
     * (network lost, reconnect failed, cancel unconfirmed).
     */
    private val RESPONSES_RESUME_STATES =
        setOf(RunTerminalState.RESUMABLE, RunTerminalState.WAITING_EXTERNAL)

    /**
     * P6-01: the Stop path touches the server only when the capability is
     * fully on (capability flag + durable runtime + resume store wiring).
     */
    private suspend fun durableRuntimeForStop(): Boolean =
        useDurableRuntime() &&
            capabilityFlags?.isEnabled(app.amber.core.settings.Capability.OpenAIResponsesResume) == true &&
            responsesResumeStore != null &&
            storedResponseGateway != null

    /**
     * P6-01 MAJOR: the user switch ([ProviderSetting.OpenAI.enableResponsesResume])
     * is part of the Stop-path gate too — off means the pre-P6-01 behavior
     * (local cancel only, no server call, no WAITING_EXTERNAL). The switch,
     * the persisted cursor and the strict provider match are all resolved in
     * one place by [StoredResponseGateway.resolve] — the same session the
     * recovery worker uses. No cursor means nothing was stored server-side —
     * nothing to cancel.
     */
    private suspend fun storedResponseToggleOnForRun(runId: String): Boolean {
        val gateway = storedResponseGateway ?: return false
        return gateway.resolve(runId)?.api != null
    }

    // 前台状态管理
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _isForeground.value = true
            Lifecycle.Event.ON_STOP -> _isForeground.value = false
            else -> {}
        }
    }
    private val lifecycleObserverRegistration: Job

    init {
        lifecycleObserverRegistration = appScope.launch(Dispatchers.Main.immediate) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        }
        appScope.launch(Dispatchers.IO) {
            for (op in pendingMessageStoreOps) {
                when (op) {
                    is PendingMessageStoreOp.Persist -> {
                        pendingMessagePersistLock(op.conversationId).withLock {
                            if (op.revision == pendingMessagePersistRevision(op.conversationId).get()) {
                                pendingMessageStore.persistBlocking(
                                    conversationId = op.conversationId,
                                    messages = op.messages,
                                )
                            }
                        }
                    }

                    is PendingMessageStoreOp.Event -> pendingMessageStore.recordEvent(
                        conversationId = op.conversationId,
                        event = op.event,
                        messageId = op.messageId,
                        count = op.count,
                        detail = op.detail,
                    )
                }
            }
        }
    }

    fun cleanup() = runCatching {
        lifecycleObserverRegistration.cancel()
        appScope.launch(Dispatchers.Main.immediate) {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        }
        pendingMessageStoreOps.close()
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
        pendingMessagePersistRevisions.clear()
        pendingMessagePersistLocks.clear()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession =
        sessions.computeIfAbsent(conversationId) { id ->
            val created = ConversationSession(
                id = id,
                initial = Conversation.ofId(id = id, assistantId = AMBER_AGENT_ID),
                initialPendingMessages = pendingMessageStore.load(id),
                scope = appScope,
                onIdle = ::removeSession,
                onPendingMessagesChanged = ::persistPendingMessagesAsync,
            )
            _sessionsVersion.update { version -> version + 1 }
            Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            created
        }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (!sessions.remove(conversationId, session)) return

        timelineLoadMutexes.remove(conversationId)
        conversationInitMutexes.remove(conversationId)
        pendingMessagePersistRevisions.remove(conversationId)
        pendingMessagePersistLocks.remove(conversationId)
        session.cleanup()
        _sessionsVersion.update { version -> version + 1 }
        Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
    }

    private fun persistPendingMessagesAsync(
        conversationId: Uuid,
        messages: List<PendingUserMessage>,
    ) {
        val revision = pendingMessagePersistRevision(conversationId).incrementAndGet()
        val result = pendingMessageStoreOps.trySend(
            PendingMessageStoreOp.Persist(
                conversationId = conversationId,
                messages = messages,
                revision = revision,
            )
        )
        if (result.isFailure) {
            result.exceptionOrNull()?.let { error ->
                Log.w(TAG, "Failed to enqueue pending message persist for $conversationId", error)
            } ?: Log.w(TAG, "Failed to enqueue pending message persist for $conversationId")
        }
    }

    private fun recordPendingMessageEvent(
        conversationId: Uuid,
        event: String,
        messageId: String? = null,
        count: Int? = null,
        detail: String? = null,
    ) {
        val result = pendingMessageStoreOps.trySend(
            PendingMessageStoreOp.Event(
                conversationId = conversationId,
                event = event,
                messageId = messageId,
                count = count,
                detail = detail,
            )
        )
        if (result.isFailure) {
            result.exceptionOrNull()?.let { error ->
                Log.w(TAG, "Failed to enqueue pending message event for $conversationId", error)
            } ?: Log.w(TAG, "Failed to enqueue pending message event for $conversationId")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) =
        getOrCreateSession(conversationId).acquire()

    fun removeConversationReference(conversationId: Uuid) =
        sessions[conversationId]?.release()

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        val session = getOrCreateSession(conversationId)
        session.acquire()
        try {
            block()
        } finally {
            session.release()
        }
    }

    // ---- 对话状态访问 ----

    override fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    override fun getConversationFlowOrNull(conversationId: Uuid): StateFlow<Conversation>? {
        return sessions[conversationId]?.state
    }

    fun getTimelineLoadStateFlow(conversationId: Uuid): StateFlow<ConversationTimelineLoadState> {
        return getOrCreateSession(conversationId).timelineLoadState
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        return getOrCreateSession(conversationId).processingStatus
    }

    fun getPendingUserMessagesFlow(conversationId: Uuid): StateFlow<List<PendingUserMessage>> {
        return getOrCreateSession(conversationId).pendingUserMessages
    }

    /**
     * Flow of "is this conversation currently being auto-compacted". Drives the
     * Codex-style shimmer divider above the input bar — the user reported that
     * compaction events were happening invisibly and they had no signal whether
     * a long stall was the model thinking, the network hung, or a compaction
     * silently running. This proxies the underlying ConversationContextEngine
     * flow so the VM doesn't need to take a direct dependency on the engine.
     */
    fun getIsCompactingFlow(conversationId: Uuid): Flow<Boolean> {
        return getCompactLifecycleStateFlow(conversationId).map { it.isActive }
    }

    /**
     * Live-streaming summary text for this conversation while compaction is
     * running. Empty string when not compacting or compaction just finished.
     * 1.9.6 feature — drives the rolling-text display under the
     * "———正在压缩上下文———" shimmer divider.
     */
    fun getStreamingSummaryFlow(conversationId: Uuid): Flow<String> {
        return getCompactLifecycleStateFlow(conversationId).map { it.streamingSummary }
    }

    fun getActiveCompactBoundaryFlow(conversationId: Uuid): Flow<ActiveCompactBoundary?> {
        return getCompactLifecycleStateFlow(conversationId).map { state ->
            if (state.hasBoundary && state.isActive) {
                ActiveCompactBoundary(
                    sourceStartIndex = state.sourceStartIndex,
                    sourceEndIndex = state.sourceEndIndex,
                    sourceMessageIds = state.sourceMessageIds,
                )
            } else {
                null
            }
        }
    }

    fun getCompactLifecycleStateFlow(conversationId: Uuid): Flow<CompactLifecycleState> {
        val key = conversationId.toString()
        return contextEngine.compactLifecycleStates.map { it[key] ?: CompactLifecycleState.idle() }
    }

    // UI pending-message mutations stay non-blocking: ConversationSession.onPendingMessagesChanged
    // feeds the single async persistence channel. A process kill in the tiny gap before that
    // worker flushes may revive the old pending list; generation and dequeue paths still use
    // durable persistence when they need it.
    fun cancelPendingUserMessage(conversationId: Uuid, messageId: String) {
        val session = getOrCreateSession(conversationId)
        if (session.cancelPendingUserMessage(messageId)) {
            recordPendingMessageEvent(conversationId, event = "cancel", messageId = messageId)
        }
    }

    fun clearPendingUserMessages(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        val count = session.pendingUserMessages.value.size
        if (count > 0) {
            session.clearPendingUserMessages()
            recordPendingMessageEvent(conversationId, event = "clear", count = count)
        }
    }

    fun movePendingUserMessage(conversationId: Uuid, messageId: String, offset: Int) {
        val session = getOrCreateSession(conversationId)
        if (session.movePendingUserMessage(messageId, offset)) {
            recordPendingMessageEvent(
                conversationId = conversationId,
                event = "move",
                messageId = messageId,
                detail = offset.toString(),
            )
        }
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> =
        _sessionsVersion.flatMapLatest {
            val snapshot = sessions.values.toList()
            if (snapshot.isEmpty()) return@flatMapLatest flowOf(emptyMap())

            combine(
                snapshot.map { session ->
                    session.generationJob.map { job -> session.id to job }
                }
            ) { entries ->
                buildMap {
                    entries.forEach { (id, job) ->
                        if (job != null) put(id, job)
                    }
                }
            }
        }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId) // 确保 session 存在
        // 按 conversationId single-flight：新会话首发时 UI init 与发送路径可能并发进入，
        // 较晚返回的空状态会覆盖已写入的首条消息。
        val mutex = conversationInitMutexes.computeIfAbsent(conversationId) { Mutex() }
        mutex.withLock {
            if (session.timelineLoadState.value.initialized) {
                return@withLock
            }

            val window = conversationRepo.getConversationTailById(conversationId, INITIAL_TIMELINE_NODE_COUNT)
            if (window != null) {
                updateConversation(conversationId, window.conversation)
                session.setTimelineLoadState(
                    ConversationTimelineLoadState(
                        initialized = true,
                        totalNodeCount = window.totalNodeCount,
                        loadedNodeCount = window.conversation.messageNodes.size,
                        oldestLoadedIndex = window.oldestLoadedIndex,
                        isFullyLoaded = window.oldestLoadedIndex == 0,
                        prefetchingOlder = false,
                    )
                )
            } else {
                // 新建对话, 并添加预设消息
                val currentSettings = settingsStore.settingsFlow.filterNot { it.init }.first()
                val newConversation = Conversation.ofId(
                    id = conversationId,
                    assistantId = AMBER_AGENT_ID,
                    newConversation = true
                ).updateCurrentMessages(currentSettings.presetMessages)
                updateConversation(conversationId, newConversation)
                session.setTimelineLoadState(
                    ConversationTimelineLoadState(
                        initialized = true,
                        totalNodeCount = newConversation.messageNodes.size,
                        loadedNodeCount = newConversation.messageNodes.size,
                        oldestLoadedIndex = 0,
                        isFullyLoaded = true,
                        prefetchingOlder = false,
                    )
                )
            }
        }
        launchPendingMessageLoopIfNeeded(conversationId, session)
    }

    private fun launchPendingMessageLoopIfNeeded(
        conversationId: Uuid,
        session: ConversationSession,
    ) {
        if (!session.isGenerating && session.pendingUserMessages.value.isNotEmpty()) {
            launchPendingMessageLoop(conversationId)
        }
    }

    private suspend fun ensureFullConversationLoaded(conversationId: Uuid): Conversation {
        val session = getOrCreateSession(conversationId)
        val loadState = session.timelineLoadState.value
        if (!loadState.initialized) {
            val fullConversation = conversationRepo.getConversationById(conversationId)
            if (fullConversation != null) {
                updateConversation(conversationId, fullConversation)
                session.setTimelineLoadState(
                    ConversationTimelineLoadState(
                        initialized = true,
                        totalNodeCount = fullConversation.messageNodes.size,
                        loadedNodeCount = fullConversation.messageNodes.size,
                        oldestLoadedIndex = 0,
                        isFullyLoaded = true,
                        prefetchingOlder = false,
                    )
                )
                return fullConversation
            }
            initializeConversation(conversationId)
            return session.state.value
        }
        if (loadState.isFullyLoaded && loadState.oldestLoadedIndex == 0) {
            return session.state.value
        }

        while (!session.timelineLoadState.value.isFullyLoaded || session.timelineLoadState.value.oldestLoadedIndex > 0) {
            val loaded = loadOlderTimelineBatch(conversationId, TIMELINE_PREFETCH_BATCH_SIZE)
            if (!loaded) break
        }
        return session.state.value
    }

    suspend fun ensureConversationTimelineLoaded(conversationId: Uuid): Conversation {
        return ensureFullConversationLoaded(conversationId)
    }

    suspend fun loadOlderTimelinePage(conversationId: Uuid): Boolean {
        return loadOlderTimelineBatch(conversationId, TIMELINE_PREFETCH_BATCH_SIZE)
    }

    private suspend fun loadOlderTimelineBatch(conversationId: Uuid, batchSize: Int): Boolean {
        val mutex = timelineLoadMutexes.computeIfAbsent(conversationId) { Mutex() }
        return mutex.withLock {
            val session = sessions[conversationId] ?: return@withLock false
            val loadState = session.timelineLoadState.value
            if (!loadState.initialized) {
                session.setTimelineLoadState(loadState.copy(prefetchingOlder = false))
                return@withLock false
            }
            if (loadState.oldestLoadedIndex <= 0) {
                session.setTimelineLoadState(loadState.copy(prefetchingOlder = false, isFullyLoaded = true))
                return@withLock false
            }

            val nextOffset = (loadState.oldestLoadedIndex - batchSize).coerceAtLeast(0)
            val nextLimit = loadState.oldestLoadedIndex - nextOffset
            session.setTimelineLoadState(loadState.copy(prefetchingOlder = true))

            val olderNodes = conversationRepo.getConversationNodePage(
                conversationId = conversationId,
                offset = nextOffset,
                limit = nextLimit,
            )
            var mergedNodeCount = session.state.value.messageNodes.size
            session.state.update { latestConversation ->
                val existingNodeIds = latestConversation.messageNodes.mapTo(mutableSetOf()) { it.id }
                val mergedNodes = olderNodes.filterNot { it.id in existingNodeIds } + latestConversation.messageNodes
                mergedNodeCount = mergedNodes.size
                latestConversation.copy(messageNodes = mergedNodes)
            }

            val isFullyLoaded = nextOffset == 0 || olderNodes.isEmpty()
            session.setTimelineLoadState(
                loadState.copy(
                    initialized = true,
                    loadedNodeCount = mergedNodeCount,
                    oldestLoadedIndex = nextOffset,
                    isFullyLoaded = isFullyLoaded,
                    prefetchingOlder = false,
                )
            )
            !isFullyLoaded
        }
    }

    // ---- 发送消息 ----

    fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        queueMode: PendingUserMessageMode = PendingUserMessageMode.FOLLOWUP,
    ): Boolean {
        if (content.isEmptyInputMessage()) return false

        val session = getOrCreateSession(conversationId)
        val processedContent = userInputPreprocessor.process(content)
        val pendingMessage = PendingUserMessage(
            id = Uuid.random().toString(),
            parts = processedContent,
            answer = answer,
            mode = if (session.isGenerating) queueMode else PendingUserMessageMode.FOLLOWUP,
        )

        if (session.isGenerating) {
            val accepted = session.enqueuePendingUserMessage(pendingMessage)
            if (!accepted) {
                addError(
                    IllegalStateException("消息队列已满，请先等待或取消一些排队消息。"),
                    conversationId = conversationId,
                    title = "消息未加入队列"
                )
                return false
            } else {
                persistPendingMessagesDurably(conversationId, session.pendingUserMessages.value)
                recordPendingMessageEvent(
                    conversationId = conversationId,
                    event = "enqueue",
                    messageId = pendingMessage.id,
                    detail = pendingMessage.mode.name.lowercase(),
                )
            }
            return true
        }

        if (useKernelPath && agentRunner != null) {
            launchViaKernel(conversationId, pendingMessage)
        } else {
            launchPendingMessageLoop(conversationId, pendingMessage)
        }
        return true
    }

    var useKernelPath = false

    private val activeKernelRuns =
        MutableStateFlow<Map<Uuid, app.amber.core.agent.runtime.AgentRunId>>(emptyMap())

    /** Latest active kernel-path run for a conversation, or null if none. */
    fun getActiveKernelRunFlow(conversationId: Uuid): StateFlow<app.amber.core.agent.runtime.AgentRunId?> =
        activeKernelRuns
            .map { it[conversationId] }
            .stateIn(appScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)

    /** Exposes the AgentRunner for UI ViewModels to call observe() directly. */
    fun kernelRunner(): app.amber.core.agent.runtime.AgentRunner? = agentRunner

    private fun launchViaKernel(conversationId: Uuid, message: PendingUserMessage) {
        val runner = agentRunner ?: return launchPendingMessageLoop(conversationId, message)
        val session = getOrCreateSession(conversationId)
        val job = appScope.launch {
            try {
                val dispatchMessage = session.preparePendingMessageForDispatch(conversationId, message)
                recordPendingMessageEvent(
                    conversationId = conversationId,
                    event = "dequeue",
                    messageId = dispatchMessage.id,
                    detail = dispatchMessage.mode.name.lowercase(),
                )
                if (resolveIdleToolBlockerBeforeDispatch(conversationId, dispatchMessage)) {
                    _generationDoneFlow.emit(conversationId)
                    return@launch
                }

                val userNode = appendUserMessage(conversationId, dispatchMessage)
                if (!dispatchMessage.answer) {
                    // 仅追加、未触发生成：不能 emit generationDoneFlow。
                    return@launch
                }

                val input = app.amber.feature.chat.api.ChatTurnInput(
                    conversationId = app.amber.core.agent.runtime.ConversationId(conversationId.toString()),
                    messageNodeId = app.amber.core.agent.runtime.MessageNodeId(userNode.id.toString()),
                    assistantId = app.amber.core.agent.runtime.AssistantId("default"),
                    userMessageText = dispatchMessage.previewText(maxChars = 4000),
                )
                val handle = runner.launch(
                    app.amber.feature.chat.api.ChatTurnDescriptor.ID,
                    input,
                ).getOrElse { e ->
                    addError(e, conversationId, title = "Kernel dispatch failed")
                    handleMessageComplete(conversationId)
                    return@launch
                }
                activeKernelRuns.update { it + (conversationId to handle.runId) }
                runner.observe(handle.runId).first { snapshot ->
                    snapshot.status !in setOf(
                        app.amber.core.agent.runtime.AgentRunStatus.RUNNING,
                        app.amber.core.agent.runtime.AgentRunStatus.AWAITING_PERMISSION,
                    )
                }
                _generationDoneFlow.emit(conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addError(e, conversationId, title = "Kernel dispatch failed")
            } finally {
                activeKernelRuns.update { it - conversationId }
            }
        }
        session.setJob(job)
    }

    private fun launchPendingMessageLoop(
        conversationId: Uuid,
        firstMessage: PendingUserMessage? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        if (session.isGenerating) {
            firstMessage?.let { message ->
                if (!session.enqueuePendingUserMessage(message)) {
                    addError(
                        IllegalStateException("消息队列已满，请先等待或取消一些排队消息。"),
                        conversationId = conversationId,
                        title = "消息未加入队列"
                    )
                } else {
                    persistPendingMessagesDurably(conversationId, session.pendingUserMessages.value)
                    recordPendingMessageEvent(
                        conversationId = conversationId,
                        event = "enqueue",
                        messageId = message.id,
                        detail = message.mode.name.lowercase(),
                    )
                }
            }
            return
        }

        val job = appScope.launch {
            var nextMessage = firstMessage ?: session.dequeueNextPendingUserMessageDurably(conversationId)
            while (nextMessage != null) {
                try {
                    val dispatchMessage = session.preparePendingMessageForDispatch(conversationId, nextMessage)
                    recordPendingMessageEvent(
                        conversationId = conversationId,
                        event = "dequeue",
                        messageId = dispatchMessage.id,
                        detail = dispatchMessage.mode.name.lowercase(),
                    )
                    if (resolveIdleToolBlockerBeforeDispatch(conversationId, dispatchMessage)) {
                        _generationDoneFlow.emit(conversationId)
                        val conversation = getConversationFlow(conversationId).value
                        if (conversation.hasPendingOrUnexecutedTools()) {
                            break
                        }
                        nextMessage = session.dequeueNextPendingUserMessageDurably(conversationId)
                        continue
                    }
                    appendUserMessage(conversationId, dispatchMessage)
                    if (dispatchMessage.answer) {
                        handleMessageComplete(conversationId)
                    }
                    _generationDoneFlow.emit(conversationId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                    addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
                }

                val conversation = getConversationFlow(conversationId).value
                if (conversation.hasPendingOrUnexecutedTools()) {
                    break
                }
                nextMessage = session.dequeueNextPendingUserMessageDurably(conversationId)
            }
        }
        session.setJob(job)
    }

    private suspend fun resolveIdleToolBlockerBeforeDispatch(
        conversationId: Uuid,
        message: PendingUserMessage,
    ): Boolean {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return false
        val lastMessage = lastNode.currentMessage
        val blockingTools = lastMessage.getTools().filter { !it.isExecuted }
        if (blockingTools.isEmpty()) return false

        val userAnswer = message.previewText(maxChars = 4_000)
        val explicitApproval = message.isToolApprovalContinuation()
        val hasAskUserAnswer = userAnswer.isNotBlank() &&
            blockingTools.any { it.isPending && it.toolName == ASK_USER_TOOL_NAME }
        val shouldResume = explicitApproval || hasAskUserAnswer

        var changed = false
        val approvedContinuations = mutableListOf<Triple<String, String, String>>() // toolCallId, toolName, input
        val updatedMessage = lastMessage.copy(
            parts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool && !part.isExecuted) {
                    when {
                        part.toolName == ASK_USER_TOOL_NAME && hasAskUserAnswer -> {
                            changed = true
                            part.copy(approvalState = ToolApprovalState.Answered(userAnswer))
                        }

                        explicitApproval && part.isPending -> {
                            changed = true
                            val resumeInput = recipeResumeInputForApproval(part)
                            approvedContinuations += Triple(part.toolCallId, part.toolName, resumeInput)
                            part.copy(input = resumeInput, approvalState = ToolApprovalState.Approved)
                        }

                        explicitApproval -> {
                            changed = true
                            skipStaleToolForContinuation(part)
                        }

                        hasAskUserAnswer -> {
                            part
                        }

                        else -> {
                            changed = true
                            cancelToolForNewUserMessage(part)
                        }
                    }
                } else {
                    part
                }
            }
        )
        if (!changed || updatedMessage == lastMessage) return false

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { nodeMessage ->
                    if (nodeMessage.id == lastMessage.id) updatedMessage else nodeMessage
                }
            ),
            updateAt = Instant.now(),
        )
        saveConversation(conversationId, updatedConversation)
        approvedContinuations.forEach { (callId, toolName, input) ->
            recordCapabilityApproval(callId, toolName, input, approved = true, source = "continuation")
        }
        recordPendingMessageEvent(
            conversationId = conversationId,
            event = if (shouldResume) "pending_tool_resume" else "pending_tool_cancel",
            messageId = message.id,
            count = blockingTools.size,
            detail = blockingTools.joinToString(separator = ",") { it.toolName },
        )

        if (shouldResume) {
            handleMessageComplete(conversationId)
            return true
        }
        return false
    }

    private fun PendingUserMessage.isToolApprovalContinuation(): Boolean {
        if (parts.any { it !is UIMessagePart.Text }) return false
        val raw = previewText(maxChars = 80).trim().lowercase(Locale.ROOT)
        if (raw.isBlank()) return false
        val compact = raw.replace(Regex("""[\s\p{Punct}，。！？、；：「」『』（）【】《》]+"""), "")
        return compact in TOOL_APPROVAL_CONTINUATION_WORDS ||
            compact.startsWith("继续") ||
            compact.startsWith("可以继续")
    }

    private fun cancelToolForNewUserMessage(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"A new user message arrived before this pending tool was approved, so AmberAgent cancelled the stale tool state and continued the conversation."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Cancelled because a new user message arrived before approval")
        )
    }

    private fun skipStaleToolForContinuation(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            approvalState = ToolApprovalState.Denied("Skipped stale tool after user asked to continue")
        )
    }

    /** Bind a nested recipe resume to the one checkpoint the user approved. */
    private fun recipeResumeInputForApproval(tool: UIMessagePart.Tool): String = runCatching {
        if (!tool.toolName.startsWith("recipe_")) return@runCatching tool.input
        val input = json.parseToJsonElement(tool.input).jsonObject
        if (!input.containsKey(app.amber.feature.recipe.RECIPE_CHECKPOINT_INPUT_KEY)) {
            return@runCatching tool.input
        }
        buildJsonObject {
            input.forEach { (key, value) -> put(key, value) }
            put(app.amber.feature.recipe.RECIPE_RESUME_APPROVED_INPUT_KEY, true)
        }.toString()
    }.getOrDefault(tool.input)

    private suspend fun appendUserMessage(
        conversationId: Uuid,
        message: PendingUserMessage,
    ): MessageNode {
        val session = getOrCreateSession(conversationId)
        if (!session.timelineLoadState.value.initialized) {
            initializeConversation(conversationId)
        }
        val currentConversation = session.state.value
        val userNode = UIMessage(
            role = MessageRole.USER,
            parts = message.parts,
        ).toMessageNode()
        ChatSendTransitionTracker.markSentUserMessage(
            conversationId = conversationId.toString(),
            messageId = userNode.currentMessage.id.toString(),
        )
        val newConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes + userNode,
        )
        updateConversation(conversationId, newConversation)
        persistConversationWindow(conversationId, newConversation, indexFts = true)
        return userNode
    }

    private suspend fun drainPendingUserMessagesInline(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        var nextMessage = session.dequeueNextPendingUserMessageDurably(conversationId)
        while (nextMessage != null) {
            val dispatchMessage = session.preparePendingMessageForDispatch(conversationId, nextMessage)
            recordPendingMessageEvent(
                conversationId = conversationId,
                event = "dequeue",
                messageId = dispatchMessage.id,
                detail = dispatchMessage.mode.name.lowercase(),
            )
            appendUserMessage(conversationId, dispatchMessage)
            if (dispatchMessage.answer) {
                handleMessageComplete(conversationId)
                _generationDoneFlow.emit(conversationId)
            }

            val conversation = getConversationFlow(conversationId).value
            if (conversation.hasPendingOrUnexecutedTools()) {
                break
            }
            nextMessage = session.dequeueNextPendingUserMessageDurably(conversationId)
        }
    }

    private fun persistPendingMessagesDurably(
        conversationId: Uuid,
        messages: List<PendingUserMessage>,
    ) {
        // revision 必须与快照在同一时刻（调用点）捕获：协程内写前校验，
        // 过期快照不得覆盖 channel 消费者已落盘的更新状态（否则已取消的
        // 排队消息会在重启后复活并被自动发送）。
        val revision = pendingMessagePersistRevision(conversationId).incrementAndGet()
        appScope.launch(pendingMessagePersistDispatcher) {
            pendingMessagePersistLock(conversationId).withLock {
                if (revision == pendingMessagePersistRevision(conversationId).get()) {
                    pendingMessageStore.persistBlocking(conversationId, messages)
                }
            }
        }
    }

    private val pendingMessagePersistDispatcher =
        Dispatchers.IO.limitedParallelism(1)

    private fun pendingMessagePersistRevision(conversationId: Uuid): AtomicLong =
        pendingMessagePersistRevisions.computeIfAbsent(conversationId) { AtomicLong(0L) }

    private fun pendingMessagePersistLock(conversationId: Uuid): Mutex =
        pendingMessagePersistLocks.computeIfAbsent(conversationId) { Mutex() }

    private fun persistCurrentPendingMessagesDurably(
        conversationId: Uuid,
        session: ConversationSession,
    ) {
        persistPendingMessagesDurably(conversationId, session.pendingUserMessages.value)
    }

    /**
     * 挂起直到落盘完成。dispatch 前的出队路径必须用它而不是异步版：
     * 否则进程在"出队后、写盘前"死亡时磁盘仍含已派发消息，重启后重复发送。
     */
    private suspend fun persistCurrentPendingMessagesNow(
        conversationId: Uuid,
        session: ConversationSession,
    ) {
        val messages = session.pendingUserMessages.value
        val revision = pendingMessagePersistRevision(conversationId).incrementAndGet()
        withContext(pendingMessagePersistDispatcher) {
            pendingMessagePersistLock(conversationId).withLock {
                if (revision == pendingMessagePersistRevision(conversationId).get()) {
                    pendingMessageStore.persistBlocking(conversationId, messages)
                }
            }
        }
    }

    private suspend fun ConversationSession.dequeueNextPendingUserMessageDurably(
        conversationId: Uuid,
    ): PendingUserMessage? {
        val message = dequeueNextPendingUserMessage()
        if (message != null) {
            persistCurrentPendingMessagesNow(conversationId, this)
        }
        return message
    }

    private suspend fun ConversationSession.preparePendingMessageForDispatch(
        conversationId: Uuid,
        message: PendingUserMessage,
    ): PendingUserMessage {
        return when {
            message.isCollectable -> {
                val collected = dequeueLeadingCollectableMessages()
                if (collected.isNotEmpty()) {
                    persistCurrentPendingMessagesNow(conversationId, this)
                }
                buildCollectedPendingUserMessage(listOf(message) + collected)
            }

            message.mode == PendingUserMessageMode.STEER -> message.asFollowup()
            else -> message
        }
    }


    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val session = getOrCreateSession(conversationId)
        val oldJob = session.getJob()
        oldJob?.cancel()

        session.setJob(appScope.launch {
            // Wait for the cancelled generation's onCompletion to finish writing,
            // so it doesn't race with our state mutations below.
            oldJob?.let { runCatching { it.join() } }
            try {
                val conversation = ensureFullConversationLoaded(conversationId)

                when {
                    message.role == MessageRole.USER -> {
                        // 如果是用户消息，则截止到当前消息（按 id 查找，避免值相等在并发写后 miss）
                        val node = conversation.getMessageNodeByMessageId(message.id)
                        if (node == null) {
                            addError(
                                IllegalStateException("Message node not found for regenerate: ${message.id}"),
                                conversationId,
                                title = context.getString(R.string.error_title_regenerate_message),
                            )
                            return@launch
                        }
                        val nodeIndex = conversation.messageNodes.indexOf(node) + 1
                        contextEngine.invalidateCompacts(conversationId, "message_regenerated")
                        saveConversation(
                            conversationId,
                            conversation.copy(messageNodes = conversation.messageNodes.take(nodeIndex)),
                        )
                        handleMessageComplete(conversationId)
                    }

                    regenerateAssistantMsg -> {
                        val nodeIndex = conversation.messageNodes.indexOfFirst { node ->
                            node.messages.any { it.id == message.id }
                        }
                        if (nodeIndex < 0) {
                            addError(
                                IllegalStateException("Message node not found for regenerate: ${message.id}"),
                                conversationId,
                                title = context.getString(R.string.error_title_regenerate_message),
                            )
                            return@launch
                        }
                        contextEngine.invalidateCompacts(conversationId, "message_regenerated")
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    }

                    else -> saveConversation(conversationId, conversation)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        })
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        session.setJob(appScope.launch {
            try {
                applyToolApprovalDecision(conversationId, toolCallId, approved, reason, answer)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        })
    }

    /**
     * P8-10/P8-11 — notification approve/deny/ask_user-reply with one-time
     * token validation (parity plan §P8-10/§P8-11 L1). The token was issued by
     * the notification builder bound to runId + conversationId + toolCallId +
     * args digest; consuming it is single-use, so replaying the same action
     * (double tap, approve then deny, stale notification of an ended run) is
     * rejected and nothing is executed. Returns true only when the decision
     * was applied to the tool call the user actually saw.
     */
    suspend fun handleNotificationApproval(
        conversationId: Uuid,
        runId: String?,
        toolCallId: String,
        approved: Boolean,
        reason: String,
        answer: String?,
        token: String,
    ): Boolean {
        val registry = notificationApprovalTokens ?: return false
        val binding = registry.consume(token) ?: return false
        val conversation = ensureFullConversationLoaded(conversationId)
        val toolPart = conversation.messageNodes
            .asSequence()
            .flatMap { node -> node.messages.asSequence() }
            .flatMap { message -> message.parts.asSequence() }
            .filterIsInstance<UIMessagePart.Tool>()
            .firstOrNull { it.toolCallId == toolCallId }
        if (!NotificationApprovalCheck.isValid(
                binding = binding,
                intentRunId = runId,
                conversationId = conversationId.toString(),
                toolCallId = toolCallId,
                currentArgsDigest = toolPart?.let { argsDigest(it.input) },
                toolStillPending = toolPart?.isPending == true,
            )
        ) {
            Log.w(
                TAG,
                "handleNotificationApproval: rejected token conversation=$conversationId run=$runId toolCall=$toolCallId"
            )
            return false
        }
        // Mirrors the in-app path: cancel the paused generation job, apply the
        // decision (approve/deny/answer), and resume the same run.
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()
        try {
            applyToolApprovalDecision(conversationId, toolCallId, approved, reason, answer)
        } catch (e: Exception) {
            addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            return false
        }
        return true
    }

    /**
     * Shared core of the in-app and notification approval paths: mark the
     * tool call's approval state, record the capability approval audit bound
     * to the args digest, and resume generation when nothing is left pending.
     */
    private suspend fun applyToolApprovalDecision(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String,
        answer: String?,
    ) {
        val conversation = ensureFullConversationLoaded(conversationId)
        val approvedToolName = conversation.findToolName(toolCallId)
        val newApprovalState = when {
            answer != null -> ToolApprovalState.Answered(answer)
            approved -> ToolApprovalState.Approved
            else -> ToolApprovalState.Denied(reason)
        }
        if (approved && approvedToolName in screenSessionTrustTools()) {
            trustedRunToolNames[conversationId] = screenSessionTrustTools()
        }

        // Update the tool approval state
        val approvedInputs = mutableListOf<Pair<String, String>>() // toolCallId to approved input
        val updatedNodes = conversation.messageNodes.map { node ->
            val messages = node.messages.map { message ->
                val parts = message.parts.map { part ->
                    if (part !is UIMessagePart.Tool || part.toolCallId != toolCallId) {
                        part
                    } else {
                        // A recipe's nested step uses the same top-level pending tool as its
                        // durable approval checkpoint. Grant only that pending nested step.
                        val resumeInput = if (approved && answer == null) {
                            recipeResumeInputForApproval(part)
                        } else {
                            part.input
                        }
                        approvedInputs += part.toolCallId to resumeInput
                        part.copy(input = resumeInput, approvalState = newApprovalState)
                    }
                }
                message.copy(parts = parts)
            }
            node.copy(messages = messages)
        }
        val updatedConversation = conversation.copy(messageNodes = updatedNodes)
        saveConversation(conversationId, updatedConversation)
        approvedInputs.forEach { (callId, input) ->
            recordCapabilityApproval(callId, approvedToolName, input, approved, source = "user")
        }

        // Check if there are still pending tools
        val hasPendingTools = updatedNodes.any { node -> node.currentMessage.getTools().any { it.isPending } }

        // Only continue generation when all pending tools are handled
        if (!hasPendingTools) {
            handleMessageComplete(conversationId)
            if (!getConversationFlow(conversationId).value.hasPendingOrUnexecutedTools()) {
                drainPendingUserMessagesInline(conversationId)
            }
        }

        _generationDoneFlow.emit(conversationId)
    }

    fun approvePendingAutoApprovableTools(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        if (session.isGenerating) return

        val job = appScope.launch {
            try {
                val conversation = ensureFullConversationLoaded(conversationId)
                var changed = false
                val approvedAll = mutableListOf<Triple<String, String, String>>() // toolCallId, toolName, input

                val updatedNodes = conversation.messageNodes.map { node ->
                    val messages = node.messages.map { message ->
                        val parts = message.parts.map { part ->
                            if (part !is UIMessagePart.Tool || !part.isPending || part.toolName == "ask_user") {
                                part
                            } else {
                                changed = true
                                val resumeInput = recipeResumeInputForApproval(part)
                                approvedAll += Triple(part.toolCallId, part.toolName, resumeInput)
                                part.copy(input = resumeInput, approvalState = ToolApprovalState.Approved)
                            }
                        }
                        message.copy(parts = parts)
                    }
                    node.copy(messages = messages)
                }

                if (!changed) return@launch

                saveConversation(
                    conversationId = conversationId,
                    conversation = conversation.copy(messageNodes = updatedNodes)
                )
                approvedAll.forEach { (callId, toolName, input) ->
                    recordCapabilityApproval(callId, toolName, input, approved = true, source = "approve_all")
                }

                val hasPendingTools = updatedNodes.any { node -> node.currentMessage.getTools().any { it.isPending } }

                if (!hasPendingTools) {
                    handleMessageComplete(conversationId)
                    if (!getConversationFlow(conversationId).value.hasPendingOrUnexecutedTools()) {
                        drainPendingUserMessagesInline(conversationId)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job)
    }

    // ---- P2-01 capability approval audit ----

    /**
     * Records an approval/denial bound to the args digest the user saw
     * (parity plan §P2-01 "history"). The dispatcher later validates that the
     * call about to execute still carries the same digest — 同一审批不能用于参数
     * 已经变化的调用. Only active when the capability_permissions flag is on.
     * Sensitive parameters are never stored — only the SHA-256 digest and the
     * ledger effect reference.
     */
    private suspend fun recordCapabilityApproval(
        toolCallId: String,
        toolName: String?,
        input: String,
        approved: Boolean,
        source: String,
    ) {
        if (toolName == null) return
        val store = capabilityPermissionStore ?: return
        if (capabilityFlags?.isEnabled(Capability.CapabilityPermissions) != true) return
        val effect = runCatching { toolEffectLedger?.getByToolCallId(toolCallId) }.getOrNull()
        val digest = effect?.argsDigest ?: runCatching { argsDigest(input) }.getOrNull() ?: return
        val capability = capabilityForTool(toolName)
        val entry = if (approved) {
            ApprovalHistoryEntry.approved(
                capability = capability,
                toolName = toolName,
                runId = effect?.runId,
                toolCallId = toolCallId,
                effectId = effect?.effectId,
                argsDigest = digest,
                source = source,
            )
        } else {
            ApprovalHistoryEntry.denied(
                capability = capability,
                toolName = toolName,
                runId = effect?.runId,
                toolCallId = toolCallId,
                effectId = effect?.effectId,
                argsDigest = digest,
                source = source,
            )
        }
        runCatching { store.recordApproval(entry) }
    }

    // ---- OutcomeUnknown reconcile (P1-02) ----

    /**
     * Re-reads OUTCOME_UNKNOWN effects from the ledger and refreshes the
     * per-conversation prompt map. Called after recovery, terminal publish
     * and reconcile — the UI collects [outcomeUnknownFlow].
     */
    suspend fun refreshOutcomeUnknown() {
        if (!useDurableRuntime()) {
            _outcomeUnknown.value = emptyMap()
            return
        }
        val prompts = mutableListOf<OutcomeUnknownPrompt>()
        runCatching {
            for (effect in toolEffectLedger!!.listOutcomeUnknown()) {
                val run = effect.runId?.let { runTerminalStore!!.get(it) } ?: continue
                prompts += OutcomeUnknownPrompt(
                    effectId = effect.effectId,
                    runId = run.runId,
                    conversationId = run.conversationId,
                    toolCallId = effect.toolCallId,
                    toolName = effect.toolName,
                    resultSummary = effect.resultSummary,
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "refreshOutcomeUnknown failed", error)
        }
        _outcomeUnknown.value = prompts.groupBy { it.conversationId }
    }

    /**
     * User decision on an OUTCOME_UNKNOWN tool effect (P1-02 #5).
     *
     * retry=true re-executes the tool (the effect becomes RECONCILED and the
     * tool part is reset to resumable); retry=false writes a structured
     * rejection the model can read and continues the run. Either way the
     * same conversation run resumes.
     */
    suspend fun reconcileOutcomeUnknown(conversationId: Uuid, effectId: String, retry: Boolean) {
        if (!useDurableRuntime()) return
        val effect = toolEffectLedger!!.get(effectId) ?: return
        val run = effect.runId?.let { runTerminalStore!!.get(it) } ?: return
        if (run.conversationId != conversationId.toString()) return

        val abandonedOutput = listOf(
            UIMessagePart.Text(
                json.encodeToString(
                    buildJsonObject {
                        put("status", "abandoned")
                        put(
                            "error",
                            "Tool execution outcome was unknown after an interruption; the user chose to abandon this call."
                        )
                        put("effect_id", effectId)
                    }
                )
            )
        )
        toolEffectLedger!!.reconcile(
            effectId = effectId,
            retry = retry,
            abandonOutput = if (retry) emptyList() else abandonedOutput,
        )

        // Reset the tool part in the conversation: retry → resumable
        // (Approved, no output); abandon → structured rejection the model
        // can read in the next round.
        val conversation = ensureFullConversationLoaded(conversationId)
        var changed = false
        val updatedNodes = conversation.messageNodes.map { node ->
            node.copy(
                messages = node.messages.map { message ->
                    message.copy(
                        parts = message.parts.map { part ->
                            if (part !is UIMessagePart.Tool || part.toolCallId != effect.toolCallId) {
                                return@map part
                            }
                            changed = true
                            if (retry) {
                                part.copy(
                                    approvalState = ToolApprovalState.Approved,
                                    output = emptyList(),
                                )
                            } else {
                                part.copy(
                                    output = abandonedOutput,
                                    approvalState = ToolApprovalState.Denied(
                                        "Tool execution outcome was unknown after an interruption; " +
                                            "the user chose to abandon this call."
                                    ),
                                )
                            }
                        }
                    )
                }
            )
        }
        if (changed) {
            saveConversation(conversationId, conversation.copy(messageNodes = updatedNodes))
        }
        // P2-01: retry is an explicit user decision on this exact effect —
        // record it so the dispatcher's approval-digest check lets the same
        // args execute again (same runId + toolCallId + digest).
        if (retry) {
            recordCapabilityApproval(
                toolCallId = effect.toolCallId,
                toolName = effect.toolName,
                input = "",
                approved = true,
                source = "outcome_retry",
            )
        }
        refreshOutcomeUnknown()
        // Resume the same conversation: retry re-executes the tool; abandon
        // lets the model see the structured rejection and continue.
        handleMessageComplete(conversationId)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.getCurrentChatModel() ?: return

        val senderName = model.displayName
        // Hoisted above runCatching so onFailure can still finalize the run
        // when generateText throws before the flow's onCompletion exists.
        var streamRecorder: app.amber.feature.chat.impl.ChatStreamCheckpointRecorder? = null
        val durablePath = useDurableRuntime()
        // P1-03: WAITING_USER runs survive restart — resume the same runId
        // instead of minting a new one, so approval continues the same run.
        val existingRun = if (durablePath) {
            runTerminalStore!!.activeForConversation(conversationId.toString())
        } else {
            null
        }
        val runId = if (durablePath) {
            val id = existingRun?.let { app.amber.core.agent.runtime.AgentRunId(it.runId) }
                ?: app.amber.core.agent.runtime.AgentRunId.new()
            runTerminalStore!!.begin(id.value, conversationId.toString(), AMBER_AGENT_ID.toString())
            id
        } else {
            null
        }
        var reportedTerminal: app.amber.core.ai.GenerationTerminal? = null
        runCatching {
            val initialConversation = loadFullConversationForGeneration(conversationId)

            // reset suggestions
            updateConversation(
                conversationId,
                getConversationFlow(conversationId).value.copy(chatSuggestions = emptyList()),
                checkDeletedFiles = false,
            )

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (settings.enableWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            val conversation = sanitizeInvalidMessages(initialConversation)
            if (conversation != initialConversation) {
                conversationRepo.updateConversation(conversation)
                replaceSessionWithFullConversation(conversationId, conversation)
            }

            // start generating
            val session = getOrCreateSession(conversationId)
            // P1-05: register the run's generation job as the owner of
            // (assistantId, conversationId, runId). Cancelling the job also
            // cancels the provider transport collected inside it.
            if (durablePath && runId != null) {
                session.getJob()?.let { job ->
                    runOwnershipRegistry?.register(
                        assistantId = AMBER_AGENT_ID.toString(),
                        conversationId = conversationId.toString(),
                        runId = runId.value,
                        job = job,
                    )
                }
            }
            updateAgentLiveStatus(
                conversationId = conversationId,
                messages = conversation.currentMessages,
                senderName = senderName,
                settings = settings,
                runId = runId?.value,
            )
            startGenerationKeepAlive(conversationId, senderName, settings)
            val generationTaskId = startGenerationTask(
                conversationId = conversationId,
                senderName = senderName,
                modelName = model.displayName,
                settings = settings,
            )
            // P4-01: per-round recipe execution context — the installed recipe
            // snapshot plus the same permission/ledger knobs the round uses.
            // Built once per round; mid-round imports do not change it.
            val recipeContext = if (
                toolDispatcher != null &&
                recipeRegistry != null &&
                capabilityFlags?.isEnabled(app.amber.core.settings.Capability.RecipeRuntime) == true
            ) {
                val recipeCapabilityState = if (
                    capabilityFlags.isEnabled(app.amber.core.settings.Capability.CapabilityPermissions) == true &&
                    capabilityPermissionStore != null
                ) {
                    capabilityPermissionStore.state()
                } else {
                    null
                }
                app.amber.feature.recipe.RecipeRunContext(
                    installed = recipeRegistry.installed(),
                    dispatcher = toolDispatcher,
                    runId = runId?.value,
                    conversationId = conversationId?.toString(),
                    ledger = if (durablePath) toolEffectLedger else null,
                    autoApproveTools = settings.agentRuntime.autoApproveAllToolCalls ||
                        conversation.autoApproveToolCalls,
                    autoApproveHighRiskTools = settings.agentRuntime.autoApproveHighRiskToolCalls,
                    autoApprovedToolNames = trustedRunToolNames[conversationId].orEmpty(),
                    capabilityPermissions = recipeCapabilityState,
                    approvalHistory = capabilityPermissionStore?.takeIf { recipeCapabilityState != null },
                    permissionContext = app.amber.feature.runtime.CapabilityPermissionContext(
                        assistantId = AMBER_AGENT_ID.toString(),
                        conversationId = conversationId.toString(),
                        sessionId = runId?.value,
                    ),
                    installedProvider = { recipeRegistry.installedSnapshot() },
                )
            } else {
                null
            }
            val runTools = createRunTools(
                settings,
                conversationId,
                runId?.value,
                casAuditEnabled = capabilityFlags?.isEnabled(
                    app.amber.core.settings.Capability.CapabilityPermissions
                ) == true,
                recipeContext = recipeContext,
                threadGraphEnabled = capabilityFlags?.isEnabled(
                    app.amber.core.settings.Capability.ThreadGraphV2
                ) == true,
                jsCellEnabled = capabilityFlags?.isEnabled(
                    app.amber.core.settings.Capability.JSCellRuntime
                ) == true,
            )
            // Stream checkpoints (coalesced to 1s / 512 chars) record run +
            // tool state in the runtime event store so a process death
            // mid-stream is recoverable by ChatEventProjector.replayUnfinished.
            // The 10s conversation snapshot below still owns the content.
            streamRecorder = agentEventStore?.let { store ->
                app.amber.feature.chat.impl.ChatStreamCheckpointRecorder(
                    eventStore = store,
                    conversationId = conversationId.toString(),
                    runId = runId ?: app.amber.core.agent.runtime.AgentRunId.new(),
                    json = json,
                )
            }
            streamRecorder?.onRunStarted()
            // P6-01: resume a stored server-side response instead of POSTing a
            // new one. Two entry points:
            //  - RESUMABLE / WAITING_EXTERNAL runs re-attach to the SAME
            //    stored response (same runId — the server still has the input
            //    and the persisted cursor);
            //  - fresh generations also opt in (store=true + write-ahead
            //    cursor on the new runId) so a disconnect / process death
            //    mid-stream can re-attach — the activation path that makes
            //    the cursor exist in the first place.
            // Every gate must pass: durable runtime, the capability flag, the
            // user switch (enableResponsesResume) and the strict provider
            // match (official endpoint + API key + Responses API).
            val resumeProvider = model.findProvider(settings.providers) as? ProviderSetting.OpenAI
            val responsesResume = if (
                durablePath &&
                runId != null &&
                resumeProvider != null &&
                resumeProvider.enableResponsesResume &&
                resumeProvider.supportsResponsesResume() &&
                capabilityFlags?.isEnabled(app.amber.core.settings.Capability.OpenAIResponsesResume) == true &&
                responsesResumeStore != null &&
                (
                    // continuation: same runId, re-attach to the stored response
                    (existingRun != null &&
                        existingRun.runId == runId.value &&
                        existingRun.state in RESPONSES_RESUME_STATES &&
                        conversation.currentMessages.lastOrNull()?.role == MessageRole.ASSISTANT) ||
                        // fresh generation: new runId, write-ahead cursor
                        existingRun == null
                )
            ) {
                app.amber.ai.provider.ResponsesResumeRequest(
                    runId = runId.value,
                    store = responsesResumeStore,
                )
            } else {
                null
            }
            generator.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = conversation.currentMessages.let {
                    if (messageRange != null) {
                        val end = (messageRange.endInclusive + 1).coerceAtMost(it.size)
                        val start = messageRange.start.coerceAtMost(end)
                        it.subList(start, end)
                    } else {
                        it
                    }
                },
                memories = if (settings.agentRuntime.enableCoreMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    emptyList()
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                },
                outputTransformers = outputTransformers,
                autoApproveTools = settings.agentRuntime.autoApproveAllToolCalls ||
                    conversation.autoApproveToolCalls,
                autoApproveHighRiskTools = settings.agentRuntime.autoApproveHighRiskToolCalls,
                autoApprovedToolNames = trustedRunToolNames[conversationId].orEmpty(),
                maxSteps = settings.agentRuntime.maxToolLoopSteps.coerceIn(
                    MIN_AGENT_TOOL_LOOP_STEPS,
                    MAX_AGENT_TOOL_LOOP_STEPS,
                ),
                tools = runTools,
                conversation = conversation,
                consumeSteerMessages = {
                    val session = getOrCreateSession(conversationId)
                    val consumed = session.dequeueSteerPendingUserMessages()
                    if (consumed.isNotEmpty()) {
                        persistCurrentPendingMessagesDurably(conversationId, session)
                        recordPendingMessageEvent(
                            conversationId = conversationId,
                            event = "steer_consumed",
                            count = consumed.size,
                        )
                    }
                    consumed
                        .map { queued ->
                            UIMessage(
                                role = MessageRole.USER,
                                parts = queued.parts,
                            )
                        }
                },
                runId = runId?.value,
                onTerminal = if (durablePath) {
                    { terminal ->
                        reportedTerminal = terminal
                        runCatching {
                            when (terminal) {
                                app.amber.core.ai.GenerationTerminal.WaitingUser -> {
                                    // WAITING_USER is a pause — the foreground
                                    // service stays alive and the runId is
                                    // kept for resume after approval.
                                    runTerminalStore!!.pause(
                                        runId!!.value,
                                        RunTerminalState.WAITING_USER,
                                        PauseReason.TOOL_APPROVAL,
                                    )
                                    refreshOutcomeUnknown()
                                }

                                app.amber.core.ai.GenerationTerminal.StepLimit -> {
                                    // STEP_LIMIT is terminal and never maps to COMPLETED.
                                    runTerminalStore!!.finish(
                                        runId!!.value,
                                        RunTerminalState.STEP_LIMIT,
                                        PauseReason.STEP_LIMIT_EXHAUSTED,
                                    )
                                    refreshOutcomeUnknown()
                                }
                            }
                        }.onFailure { error ->
                            Log.w(TAG, "onTerminal persist failed for $conversationId", error)
                        }
                    }
                } else {
                    null
                },
                responsesResume = responsesResume,
            ).onCompletion { cause ->
                streamRecorder?.onRunFinished(cause)
                // P1-05: the run's handles are done — release ownership. A
                // stale notification carrying this runId will no longer cancel
                // anything (a newer run registers under its own runId).
                if (runId != null) {
                    runOwnershipRegistry?.unregister(runId.value)
                }
                // P8-11: a flow that paused for user input (WAITING_USER) keeps
                // the live notification with approve/deny/reply/stop actions —
                // driven by the persisted pause state (P1-03), so the user can
                // decide from the notification. Terminal outcomes dismiss it.
                val waitingForApproval = getConversationFlow(conversationId).value.currentMessages
                    .any { message ->
                        message.parts.any { it is UIMessagePart.Tool && it.isPending }
                    }
                if (waitingForApproval && reportedTerminal is app.amber.core.ai.GenerationTerminal.WaitingUser) {
                    updateAgentLiveStatus(
                        conversationId = conversationId,
                        messages = getConversationFlow(conversationId).value.currentMessages,
                        senderName = senderName,
                        settings = settings,
                        runId = runId?.value,
                    )
                } else {
                    cancelLiveUpdateNotification(conversationId)
                }
                // P1-03: foreground-service lifecycle follows the persisted
                // terminal state, not the coroutine's exit. WAITING_USER is a
                // pause — the keep-alive stays until the user decides.
                val terminalPublish = if (durablePath && runId != null) {
                    val (state, reason) = terminalForFlowEnd(cause, reportedTerminal)
                    // P6-01: when the user stopped but the server cancel could
                    // not be confirmed, the run outcome is undecidable — keep
                    // WAITING_EXTERNAL (never pretend CANCELLED) and leave the
                    // resume cursor so recovery can settle it server-side.
                    val serverCancelPending = pendingServerCancelFailures.remove(runId.value) == true
                    runCatching {
                        when {
                            serverCancelPending -> {
                                runTerminalStore!!.pause(
                                    runId.value,
                                    RunTerminalState.WAITING_EXTERNAL,
                                    PauseReason.USER_STOP,
                                )
                            }

                            state == RunTerminalState.WAITING_USER -> {
                                runTerminalStore!!.pause(runId.value, state, reason)
                            }

                            else -> {
                                runTerminalStore!!.finish(runId.value, state, reason)
                            }
                        }
                    }
                    if (state == RunTerminalState.CANCELLED || state == RunTerminalState.FAILED || serverCancelPending) {
                        // A stop/failure may leave a STARTED non-idempotent
                        // effect behind — reconcile it so the user decides.
                        runCatching { runRecovery!!.reconcileStartedEffects(runId.value) }
                        runCatching { refreshOutcomeUnknown() }
                    }
                    // P4-02: cascade cancellation — a cancelled parent run also
                    // cancels its child threads (replaces the pre-Phase-4
                    // detach policy; gated by the thread_graph_v2 flag inside).
                    if (state == RunTerminalState.CANCELLED) {
                        runCatching {
                            subAgentManager.cancelByRootRun(runId.value, conversationId.toString())
                        }
                    }
                    if (serverCancelPending) RunTerminalState.WAITING_EXTERNAL else state
                } else {
                    null
                }
                val keepAlive = if (terminalPublish == null) {
                    true // legacy path: keep-alive follows queued continuations below
                } else {
                    // durable path: keep-alive follows the persisted state
                    runCatching { runTerminalStore?.get(runId!!.value)?.state?.isTerminal != true }
                        .getOrDefault(true)
                }
                if (keepAlive && !hasQueuedContinuation(conversationId)) {
                    stopGenerationKeepAlive(conversationId)
                }

                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation, checkDeletedFiles = false)
                checkpointConversation(conversationId, updatedConversation, force = true)
                generationCheckpointAt.remove(conversationId)
                finishGenerationTask(generationTaskId, cause)
                cleanupRunResourcesIfDone(conversationId, updatedConversation)

                // Show notification only when the run truly completed
                // (STEP_LIMIT / WAITING_USER are never "done").
                val completed = terminalPublish == RunTerminalState.COMPLETED ||
                    (terminalPublish == null && cause == null)
                if (
                    completed &&
                    !isForeground.value &&
                    settings.displaySetting.enableNotificationOnMessageGeneration
                ) {
                    sendGenerationDoneNotification(conversationId, senderName, runId?.value)
                }
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val patchMessages = chunk.update.streamingTailMessageId?.let { tailId ->
                            chunk.messages.filter { message -> message.id == tailId }
                        } ?: chunk.messages
                        val sourceStartIndex = messageRange?.let { range ->
                            if (chunk.update.isStreamingTail) {
                                chunk.messages.indexOfFirst { message ->
                                    message.id == chunk.update.streamingTailMessageId
                                }.takeIf { it >= 0 }?.let { tailOffset -> range.start + tailOffset }
                            } else {
                                range.start
                            }
                        }
                        val updatedConversation = getConversationFlow(conversationId).value
                            .mergeGeneratedMessagesIntoWindow(
                                generatedMessages = patchMessages,
                                sourceStartIndex = sourceStartIndex,
                            )
                        updateConversation(conversationId, updatedConversation, checkDeletedFiles = false)
                        checkpointConversation(conversationId, updatedConversation)
                        // M1: the executed tool results are durable in the
                        // conversation now — the ledger replay payload is no
                        // longer read, so drop it (bounded retention).
                        clearPersistedToolPayloads(durablePath, patchMessages)
                        streamRecorder?.onChunk(
                            messages = chunk.messages,
                            streamingTailMessageId = chunk.update.streamingTailMessageId,
                        )

                        updateAgentLiveStatus(
                            conversationId = conversationId,
                            messages = chunk.messages,
                            senderName = senderName,
                            settings = settings,
                            runId = runId?.value,
                        )
                    }
                }
            }
        }.onFailure {
            // No-op when onCompletion already finalized; covers failures
            // thrown before the generation flow was even constructed.
            streamRecorder?.onRunFinished(it)
            trustedRunToolNames.remove(conversationId)
            if (durablePath && runId != null) {
                val state = if (it is CancellationException) {
                    RunTerminalState.CANCELLED
                } else {
                    RunTerminalState.FAILED
                }
                runCatching {
                    runTerminalStore!!.finish(
                        runId.value,
                        state,
                        if (it is CancellationException) PauseReason.USER_STOP else null,
                    )
                    runRecovery!!.reconcileStartedEffects(runId.value)
                    refreshOutcomeUnknown()
                }
                // P4-02: cascade cancellation (thread_graph_v2 gated inside).
                if (state == RunTerminalState.CANCELLED) {
                    runCatching {
                        subAgentManager.cancelByRootRun(runId.value, conversationId.toString())
                    }
                }
            }
            if (!hasQueuedContinuation(conversationId)) {
                stopGenerationKeepAlive(conversationId)
            }
            val latestConversation = getConversationFlow(conversationId).value
            checkpointConversation(conversationId, latestConversation, force = true)
            generationCheckpointAt.remove(conversationId)
            screenCaptureManager.releaseSession()
            if (it is CancellationException || !settings.agentRuntime.enableLiveStatusNotification) {
                cancelLiveUpdateNotification(conversationId)
            } else {
                liveStatusNotifier.notifyFailure(
                    conversationId = conversationId,
                    senderName = senderName,
                    error = it,
                    launchIntent = getPendingIntent(context, conversationId, runId?.value),
                )
            }

            it.printStackTrace()
            // Surface compaction failures with a targeted title + actionable hint instead
            // of the generic "message generation failed" toast. Previously this was the
            // root cause of the silent-stall bug: GLM 5.1 at near-context-limit triggered
            // forceRatio compact → fell back to the same slow model → compact timed out →
            // exception got wrapped as a generic generation error → 5-second ErrorCard →
            // user never saw it. Now it points users at the 压缩模型 setting where the
            // real fix lives.
            val (errorTitle, surfacedError) = when (it) {
                is app.amber.core.context.ContextCompactionFailedException -> {
                    val hint = context.getString(
                        R.string.error_auto_compact_failed_hint,
                        it.phase,
                        it.compactionReason,
                    )
                    context.getString(R.string.error_title_compress_conversation) to RuntimeException(hint, it)
                }
                // P1-04: the final token fit could not satisfy the hard budget
                // even after trimming — the request was never sent. Surface a
                // clear title; the exception message carries the token math.
                is app.amber.core.context.ContextTooLargeException -> "上下文超出模型上限" to it
                else -> context.getString(R.string.error_title_generation) to it
            }
            addError(surfacedError, conversationId, title = errorTitle)
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            persistConversationWindow(conversationId, finalConversation, indexFts = true)
            cleanupRunResourcesIfDone(conversationId, finalConversation)

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
            if (!finalConversation.hasPendingOrUnexecutedTools()) {
                appScope.launch(Dispatchers.IO) {
                    memoryExtractor.extractAfterConversation(
                        loadFullConversationForGeneration(conversationId)
                    )
                }
            }
        }
    }

    /**
     * M1: after executed tool results are durably persisted into the
     * conversation, clear their ledger replay payload — it is no longer read
     * once the result landed, and full tool output must not accumulate in the
     * ledger as a plaintext sink.
     */
    private suspend fun clearPersistedToolPayloads(durablePath: Boolean, messages: List<UIMessage>) {
        if (!durablePath) return
        val ledger = toolEffectLedger ?: return
        for (message in messages) {
            for (part in message.parts) {
                if (part !is UIMessagePart.Tool || !part.isExecuted) continue
                runCatching {
                    ledger.getByToolCallId(part.toolCallId)?.let { effect ->
                        ledger.markResultPersisted(effect.effectId)
                    }
                }.onFailure { error ->
                    Log.w(TAG, "clearPersistedToolPayloads failed for ${part.toolCallId}", error)
                }
            }
        }
    }

    private fun hasQueuedContinuation(conversationId: Uuid): Boolean {
        return sessions[conversationId]?.pendingUserMessages?.value?.isNotEmpty() == true
    }    private fun cleanupRunResourcesIfDone(conversationId: Uuid, conversation: Conversation) {
        if (conversation.hasPendingOrUnexecutedTools()) return
        trustedRunToolNames.remove(conversationId)
        screenCaptureManager.releaseSession()
    }

    private fun screenSessionTrustTools(): Set<String> = setOf(
        "screen_read_ui",
        "screen_click",
        "screen_long_click",
        "screen_swipe",
        "screen_input_text",
        "screen_back",
        "screen_home",
        "screen_open_app",
        "screen_screenshot",
    )

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        updateConversation(conversationId, sanitizeInvalidMessages(conversation))
    }

    private suspend fun loadFullConversationForGeneration(conversationId: Uuid): Conversation {
        val windowConversation = getConversationFlow(conversationId).value
        val loadState = getOrCreateSession(conversationId).timelineLoadState.value
        if (loadState.initialized && loadState.isFullyLoaded && loadState.oldestLoadedIndex == 0) {
            return windowConversation
        }
        val fullConversation = conversationRepo.getConversationById(conversationId) ?: return windowConversation
        return mergeConversationWindowIntoFull(
            fullConversation = fullConversation,
            windowConversation = windowConversation,
        )
    }

    private fun replaceSessionWithFullConversation(
        conversationId: Uuid,
        conversation: Conversation,
    ) {
        val session = getOrCreateSession(conversationId)
        updateConversation(conversationId, conversation, checkDeletedFiles = false)
        session.setTimelineLoadState(
            ConversationTimelineLoadState(
                initialized = true,
                totalNodeCount = conversation.messageNodes.size,
                loadedNodeCount = conversation.messageNodes.size,
                oldestLoadedIndex = 0,
                isFullyLoaded = true,
                prefetchingOlder = false,
            )
        )
    }

    private fun sanitizeInvalidMessages(conversation: Conversation): Conversation {
        val validNodes = conversation.messageNodes.mapNotNull { node ->
            val currentTools = node.currentMessage.getTools()
            val unresolved = currentTools.filterNot { it.isExecuted }
            val candidate = if (unresolved.isEmpty() || unresolved.any {
                    it.approvalState.canResumeToolExecution()
                }
            ) {
                node
            } else {
                node.copy(
                    messages = node.messages.filterNot { it.id == node.currentMessage.id },
                    selectIndex = (node.selectIndex - 1).coerceAtLeast(0),
                )
            }

            if (candidate.messages.isEmpty()) return@mapNotNull null
            if (candidate.selectIndex in candidate.messages.indices) {
                candidate
            } else {
                candidate.copy(selectIndex = 0)
            }
        }
        return conversation.copy(messageNodes = validNodes)
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        val cancellation = UIMessagePart.Text(
            """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
        )
        return tool.copy(
            output = listOf(cancellation),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user"),
        )
    }

    // ---- 生成标题 / 建议（delegated to AiAuxiliaryGenerator）----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false,
    ) = aiAuxiliaryGenerator.generateTitle(conversationId, conversation, force)

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) =
        aiAuxiliaryGenerator.generateSuggestion(conversationId, conversation)

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = runCatching {
        val fullConversation = if (conversation.id == conversationId) {
            ensureFullConversationLoaded(conversationId)
        } else {
            conversation
        }
        val settings = settingsStore.settingsFlow.first()
        val compressionModel = settings.resolveTaskChatModel(settings.compressModelId)
        val result = contextEngine.compactConversation(
            conversation = fullConversation,
            settings = settings,
            policy = settings.agentRuntime.contextCompaction.toCompactPolicy().copy(
                enabled = true,
                keepRecentTurns = (keepRecentMessages / 2).coerceAtLeast(1),
                maxSummaryTokens = targetTokens,
            ),
            model = compressionModel,
            reason = "manual_compact_dialog",
            additionalPrompt = additionalPrompt,
            force = true,
        )
        if (result.status != "completed") {
            val reason = result.error ?: result.status
            if (reason == "not_enough_history" || reason == "not_enough_new_history") {
                throw IllegalStateException(context.getString(R.string.chat_page_compress_recent_content_too_large))
            }
            throw IllegalStateException(reason)
        }
    }

    // ---- 通知 ----

    private fun sendGenerationDoneNotification(
        conversationId: Uuid,
        senderName: String,
        runId: String? = null,
    ) {
        // 先取消 Live Update 通知
        cancelLiveUpdateNotification(conversationId)

        val conversation = getConversationFlow(conversationId).value
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = generationDoneNotificationId(conversationId, runId)
        ) {
            title = senderName
            content = conversation.currentMessages.lastOrNull()?.toText()?.take(50)?.trim() ?: ""
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId, runId)
        }
    }

    private fun updateAgentLiveStatus(
        conversationId: Uuid,
        messages: List<UIMessage>,
        senderName: String,
        settings: app.amber.core.settings.Settings,
        runId: String? = null,
    ) {
        if (!settings.agentRuntime.enableLiveStatusNotification) return
        liveStatusNotifier.notifyRunning(
            conversationId = conversationId,
            senderName = senderName,
            messages = messages,
            activity = activityStore.sandboxActivity.value
                ?.takeIf { it.conversationId == conversationId.toString() },
            hideSensitive = settings.agentRuntime.hideSensitiveLiveStatus,
            launchIntent = getPendingIntent(context, conversationId, runId),
            runId = runId,
        )
    }

    private fun cancelLiveUpdateNotification(conversationId: Uuid) {
        liveStatusNotifier.cancel(conversationId)
    }

    private suspend fun startGenerationTask(
        conversationId: Uuid,
        senderName: String,
        modelName: String,
        settings: Settings,
    ): String {
        val taskId = generationTaskId(conversationId)
        val now = System.currentTimeMillis()
        runCatching {
            agentTaskScheduler.start(
                snapshot = AgentTaskSnapshot(
                    taskId = taskId,
                    type = "generation",
                    title = context.getString(R.string.generation_task_title),
                    spec = buildJsonObject {
                        put("sender", senderName)
                        put("model", modelName)
                        put("auto_retry", settings.agentRuntime.generationRetry.enabled)
                        put("max_retries", settings.agentRuntime.generationRetry.maxRetries)
                    },
                    sourceConversationId = conversationId.toString(),
                    sourceToolName = "chat_generation",
                    status = AgentTaskStatus.RUNNING,
                    createdAtMs = now,
                    updatedAtMs = now,
                    lastHeartbeatMs = now,
                    cancelCapability = true,
                    retryPolicy = AgentTaskRetryPolicy(
                        retryable = true,
                        requiresApproval = false,
                        maxRetries = settings.agentRuntime.generationRetry.maxRetries,
                        reason = "Temporary network or provider failures retry automatically during the live generation.",
                    ),
                ),
                cancel = {
                    stopGeneration(conversationId)
                    true
                },
            )
        }.onFailure { error ->
            Log.w(TAG, "startGenerationTask failed for $conversationId", error)
        }
        return taskId
    }

    private suspend fun finishGenerationTask(taskId: String, cause: Throwable?) {
        runCatching {
            when {
                cause == null -> agentTaskScheduler.complete(taskId, summary = "Generation completed.")
                cause is CancellationException -> agentTaskScheduler.fail(
                    taskId = taskId,
                    message = "Generation cancelled by user.",
                    code = "cancelled",
                )

                else -> agentTaskScheduler.fail(
                    taskId = taskId,
                    message = cause.message ?: cause::class.java.simpleName,
                    code = "generation_failed",
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "finishGenerationTask failed for $taskId", error)
        }
    }

    private fun generationTaskId(conversationId: Uuid): String =
        "generation-$conversationId"

    private fun startGenerationKeepAlive(
        conversationId: Uuid,
        senderName: String,
        settings: Settings,
    ) {
        if (!settings.agentRuntime.keepGenerationAliveInBackground) return
        AgentGenerationForegroundService.start(
            context = context,
            conversationId = conversationId.toString(),
            title = senderName.ifBlank { context.getString(R.string.app_name) },
            content = context.getString(R.string.generation_keepalive_content),
        )
    }

    private fun stopGenerationKeepAlive(conversationId: Uuid) {
        AgentGenerationForegroundService.stop(context, conversationId.toString())
    }

    private suspend fun checkpointConversation(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        val last = generationCheckpointAt[conversationId] ?: 0L
        if (!force && now - last < GENERATION_CHECKPOINT_INTERVAL_MS) return
        generationCheckpointAt[conversationId] = now
        val startedAt = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        runCatching {
            persistConversationWindow(
                conversationId = conversationId,
                conversation = conversation,
                indexFts = force,
            )
            if (BuildConfig.DEBUG) {
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
                Log.d(
                    "AmberChatPerf",
                    "checkpointConversation force=$force nodes=${conversation.messageNodes.size} " +
                        "elapsedMs=${String.format(Locale.US, "%.2f", elapsedMs)}",
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "checkpointConversation failed for $conversationId", error)
        }
    }

    private suspend fun persistConversationWindow(
        conversationId: Uuid,
        conversation: Conversation,
        indexFts: Boolean,
    ) {
        if (conversationId in deletedConversationIds) return
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return
        }
        if (!exists) {
            conversationRepo.insertConversation(conversation)
            return
        }
        val loadState = getOrCreateSession(conversationId).timelineLoadState.value
        if (!loadState.initialized) {
            saveConversation(conversationId, conversation)
            return
        }
        conversationRepo.upsertConversationWindow(
            conversation = conversation,
            firstNodeIndex = loadState.oldestLoadedIndex,
            indexFts = indexFts,
        )
    }

    /**
     * P1-05: notification deep link carries runId + conversationId + focus so
     * the receiver can validate ownership and freshness before acting. The
     * stop action additionally carries runId (see AgentNotificationActionReceiver).
     */
    private fun getPendingIntent(
        context: Context,
        conversationId: Uuid,
        runId: String? = null,
    ): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
            putExtra("focus", "conversation")
            if (runId != null) {
                putExtra("runId", runId)
            }
        }
        return PendingIntent.getActivity(
            context,
            generationNotificationPendingIntentRequestCode(conversationId, runId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ---- 对话状态更新 ----

    override fun updateConversation(
        conversationId: Uuid,
        conversation: Conversation,
        checkDeletedFiles: Boolean,
    ) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        if (checkDeletedFiles) {
            checkFilesDelete(conversation, session.state.value)
        }
        session.state.value = conversation
        val loadState = session.timelineLoadState.value
        if (loadState.initialized) {
            val loadedNodeCount = conversation.messageNodes.size
            val totalNodeCount = if (loadState.isFullyLoaded) {
                loadedNodeCount
            } else {
                maxOf(loadState.totalNodeCount, loadState.oldestLoadedIndex + loadedNodeCount)
            }
            session.setTimelineLoadState(
                loadState.copy(
                    loadedNodeCount = loadedNodeCount,
                    totalNodeCount = totalNodeCount,
                    isFullyLoaded = loadState.isFullyLoaded && loadState.oldestLoadedIndex == 0,
                )
            )
        }
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val retainedFiles = newConversation.files.toHashSet()
        val removedFiles = oldConversation.files.filterNot(retainedFiles::contains)
        if (removedFiles.isEmpty()) return

        filesManager.deleteChatFiles(removedFiles)
        Log.w(TAG, "checkFilesDelete: $removedFiles")
    }

    override suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversationId in deletedConversationIds) return
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val loadState = getOrCreateSession(conversationId).timelineLoadState.value
        val updatedConversation = if (exists && (!loadState.initialized || !loadState.isFullyLoaded || loadState.oldestLoadedIndex > 0)) {
            mergeConversationWindowIntoFull(
                fullConversation = ensureFullConversationLoaded(conversationId),
                windowConversation = conversation,
            )
        } else {
            conversation.copy()
        }
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
    }

    private fun mergeConversationWindowIntoFull(
        fullConversation: Conversation,
        windowConversation: Conversation,
    ): Conversation {
        val windowNodesById = windowConversation.messageNodes.associateBy { it.id }
        val mergedNodes = fullConversation.messageNodes
            .map { node -> windowNodesById[node.id] ?: node }
            .toMutableList()
        val fullNodeIds = fullConversation.messageNodes.mapTo(mutableSetOf()) { it.id }
        windowConversation.messageNodes
            .filterNot { it.id in fullNodeIds }
            .forEach { mergedNodes.add(it) }

        return fullConversation.copy(
            assistantId = windowConversation.assistantId,
            title = windowConversation.title,
            chatSuggestions = windowConversation.chatSuggestions,
            isPinned = windowConversation.isPinned,
            autoApproveToolCalls = windowConversation.autoApproveToolCalls,
            updateAt = windowConversation.updateAt,
            messageNodes = mergedNodes,
        )
    }

    private fun Conversation.mergeGeneratedMessagesIntoWindow(
        generatedMessages: List<UIMessage>,
        sourceStartIndex: Int? = null,
    ): Conversation {
        if (generatedMessages.isEmpty()) return this
        if (sourceStartIndex != null) {
            return mergeGeneratedMessagesByIndex(
                generatedMessages = generatedMessages,
                sourceStartIndex = sourceStartIndex,
            )
        }
        val generatedById = generatedMessages.associateBy { it.id }
        var changed = false
        val updatedNodes = messageNodes.map { node ->
            val selected = node.currentMessage
            val replacement = generatedById[selected.id] ?: return@map node
            if (replacement === selected || replacement == selected) {
                node
            } else {
                changed = true
                node.copy(
                    messages = node.messages.map { message ->
                        if (message.id == selected.id) replacement else message
                    }
                )
            }
        }
        val existingCurrentIds = updatedNodes.mapTo(mutableSetOf()) { it.currentMessage.id }
        val lastWindowMessageId = updatedNodes.lastOrNull()?.currentMessage?.id
        val appendStart = lastWindowMessageId
            ?.let { id -> generatedMessages.indexOfLast { it.id == id }.takeIf { it >= 0 }?.plus(1) }
            ?: 0
        val appendedNodes = generatedMessages
            .drop(appendStart)
            .filterNot { it.id in existingCurrentIds }
            .map { message ->
                changed = true
                message.toMessageNode()
            }
        return if (!changed) {
            this
        } else {
            copy(messageNodes = updatedNodes + appendedNodes)
        }
    }

    private fun Conversation.mergeGeneratedMessagesByIndex(
        generatedMessages: List<UIMessage>,
        sourceStartIndex: Int,
    ): Conversation {
        if (generatedMessages.isEmpty()) return this
        val updatedNodes = messageNodes.toMutableList()
        var changed = false
        generatedMessages.forEachIndexed { offset, message ->
            val nodeIndex = sourceStartIndex + offset
            val existingNode = updatedNodes.getOrNull(nodeIndex)
            if (existingNode == null) {
                updatedNodes.add(message.toMessageNode())
                changed = true
                return@forEachIndexed
            }
            val existingMessageIndex = existingNode.messages.indexOfFirst { it.id == message.id }
            if (
                existingMessageIndex >= 0 &&
                existingNode.messages[existingMessageIndex] === message &&
                existingNode.selectIndex == existingMessageIndex
            ) {
                return@forEachIndexed
            }
            val nextMessages = existingNode.messages.toMutableList()
            val nextSelectedIndex = if (existingMessageIndex >= 0) {
                nextMessages[existingMessageIndex] = message
                existingMessageIndex
            } else {
                nextMessages.add(message)
                nextMessages.lastIndex
            }
            val nextNode = existingNode.copy(
                messages = nextMessages,
                selectIndex = nextSelectedIndex,
            )
            if (nextNode != existingNode) {
                updatedNodes[nodeIndex] = nextNode
                changed = true
            }
        }
        return if (changed) copy(messageNodes = updatedNodes) else this
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>,
        regenerate: Boolean = false,
    ) {
        if (parts.isEmptyInputMessage()) return
        val processedParts = userInputPreprocessor.process(parts)

        val session = getOrCreateSession(conversationId)
        // P8-01: 生成中编辑冲突——明确提示并拒绝，不打断当前生成，也不做任何写操作。
        val conflictReason = blockedReason(session.isGenerating, "请先停止生成再编辑消息")
        if (conflictReason != null) {
            addError(
                IllegalStateException(conflictReason),
                conversationId = conversationId,
                title = context.getString(R.string.error_title_operation),
            )
            return
        }

        val currentConversation = ensureFullConversationLoaded(conversationId)
        val updatedConversation = currentConversation.withEditedUserVariant(messageId, processedParts)
        if (updatedConversation === currentConversation) return

        contextEngine.invalidateCompacts(conversationId, "message_edited")
        saveConversation(conversationId, updatedConversation)

        // P8-01: 「保存并重新生成」——从新选中的 user variant 生成 assistant 分支。
        // 生成绑定新 variant：handleMessageComplete 使用 conversation.currentMessages，
        // 其 selectIndex 已指向新 variant。以会话 Job 运行，Stop 可取消、可防重复。
        if (regenerate) {
            val job = appScope.launch {
                try {
                    handleMessageComplete(conversationId)
                    _generationDoneFlow.emit(conversationId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    addError(
                        e,
                        conversationId,
                        title = context.getString(R.string.error_title_regenerate_message),
                    )
                }
            }
            session.setJob(job)
        }
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = ensureFullConversationLoaded(conversationId)
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }.takeIf { it >= 0 } ?: throw NoSuchElementException("Message not found")

        val sourceNodes = currentConversation.messageNodes.take(targetNodeIndex + 1)
        val copiedNodes = buildList(sourceNodes.size) {
            sourceNodes.forEach { sourceNode ->
                val copiedMessages = sourceNode.messages.map { sourceMessage ->
                    sourceMessage.copy(
                        parts = sourceMessage.parts.map { part -> part.copyWithForkedFileUrl() },
                    )
                }
                add(
                    sourceNode.copy(
                        id = Uuid.random(),
                        messages = copiedMessages,
                    )
                )
            }
        }

        val forked = Conversation(
            id = Uuid.random(),
            assistantId = currentConversation.assistantId,
            messageNodes = copiedNodes,
        )

        saveConversation(forked.id, forked)
        contextEngine.copyValidCompactsToConversation(
            sourceConversationId = conversationId,
            targetConversation = forked,
        )
        return forked
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val session = getOrCreateSession(conversationId)
        // Minor-1: 生成中切换 user variant 与生成写竞争（saveConversation 可能
        // 覆盖流式写入的下游分支）。与 editMessage 的权威守卫一致：明确提示并
        // 拒绝，不打断当前生成，也不做任何写操作。
        val conflictReason = blockedReason(session.isGenerating, "请先停止生成再切换消息分支")
        if (conflictReason != null) {
            addError(
                IllegalStateException(conflictReason),
                conversationId = conversationId,
                title = context.getString(R.string.error_title_operation),
            )
            return
        }

        val currentConversation = ensureFullConversationLoaded(conversationId)
        // P8-02: 切换 variant 后，下游可见分支同步切换（截断到被切换节点，
        // 与新 variant 上下文保持一致）。
        val updatedConversation = currentConversation.withSelectedVariant(nodeId, selectIndex)
        if (updatedConversation === currentConversation) return

        contextEngine.invalidateCompacts(conversationId, "message_branch_changed")
        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = ensureFullConversationLoaded(conversationId)
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NoSuchElementException("Message not found")
            }
            return
        }

        contextEngine.invalidateCompacts(conversationId, "message_deleted")
        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) = deleteMessage(conversationId, message.id, failIfMissing = false)

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val nodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { message -> message.id == messageId }
        }.takeIf { it >= 0 } ?: return null
        val targetNode = conversation.messageNodes[nodeIndex]
        val remainingMessages = targetNode.messages.filterNot { message -> message.id == messageId }
        val nextNodes = conversation.messageNodes.toMutableList()

        if (remainingMessages.isEmpty()) {
            nextNodes.removeAt(nodeIndex)
        } else {
            nextNodes[nodeIndex] = targetNode.copy(
                messages = remainingMessages,
                selectIndex = targetNode.selectIndex.coerceAtMost(remainingMessages.lastIndex),
            )
        }
        return conversation.copy(messageNodes = nextNodes)
    }

    private suspend fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        val sourceUrl = when (this) {
            is UIMessagePart.Image -> url
            is UIMessagePart.Document -> url
            is UIMessagePart.Video -> url
            is UIMessagePart.Audio -> url
            else -> return this
        }
        if (!sourceUrl.startsWith("file:")) return this
        val copiedUrl = filesManager.createChatFilesByContents(listOf(sourceUrl.toUri()))
            .firstOrNull()
            ?.toString()
            ?: return this

        return when (this) {
            is UIMessagePart.Image -> copy(url = copiedUrl)
            is UIMessagePart.Document -> copy(url = copiedUrl)
            is UIMessagePart.Video -> copy(url = copiedUrl)
            is UIMessagePart.Audio -> copy(url = copiedUrl)
        }
    }

    internal fun createDebugRunTools(settings: Settings): List<Tool> =
        createRunTools(settings, null, casAuditEnabled = false)

    private fun createRunTools(
        settings: Settings,
        conversationId: Uuid?,
        runId: String? = null,
        casAuditEnabled: Boolean = false,
        recipeContext: app.amber.feature.recipe.RecipeRunContext? = null,
        // P4-02: thread_graph_v2 gate, computed at the (suspend) call site.
        threadGraphEnabled: Boolean = false,
        // P4-03: js_cell_runtime gate, computed at the (suspend) call site.
        jsCellEnabled: Boolean = false,
    ): List<Tool> {
        // P2-04/P2-06/P2-07 audit sink: approval-history entries are recorded
        // only when the capability_permissions flag is on (same gate as the
        // P2-01 approval records). The flows still work flag-off — only the
        // audit trail is skipped (rollback rules §17.2 keep old entries).
        val casLedger = capabilityPermissionStore?.takeIf { casAuditEnabled }
            ?.let { app.amber.feature.runtime.CapabilityBackedCasLedger(it) }
        val soulTransaction = app.amber.feature.prompts.SoulImportTransaction(
            settingsStore = settingsStore,
            ledger = casLedger,
            previousStore = app.amber.feature.prompts.SoulPreviousStore(context),
        )
        val assistantLocalTools = localTools.getTools(AMBER_AGENT_LOCAL_TOOLS, conversationId)
        val themePackTools = themePackageManager?.let(::createThemePackTools).orEmpty()
        val mcpManagementTools = if (conversationId != null) {
            createMcpManagementTools(
                settingsStore = settingsStore,
                mcpManager = mcpManager,
                skillManager = skillManager,
                approvalLedger = capabilityPermissionStore
                    ?.let { app.amber.feature.runtime.CapabilityBackedCasLedger(it) },
            )
        } else {
            emptyList()
        }
        val includeWebViewFallbackGuidance = ToolProfileFilter
            .filter(assistantLocalTools, AMBER_AGENT_TOOL_PROFILE)
            .tools
            .any { it.name == WEBVIEW_SEARCH_OPEN_TOOL_NAME }
        val rawTools = buildList {
            if (settings.enableWebSearch) {
                addAll(
                    createSearchTools(
                        settings = settings,
                        includeWebViewFallbackGuidance = includeWebViewFallbackGuidance,
                    )
                )
            }
            addAll(assistantLocalTools)
            // Status is read-only and may be used for diagnosis; keep the
            // mutating import tool out of the raw/SubAgent catalog below.
            addAll(themePackTools.filter { it.name == TOOL_THEME_PACK_STATUS })
            addAll(
                createSkillTools(
                    enabledSkills = settings.enabledSkills,
                    allSkills = skillManager.listSkills(),
                    skillManager = skillManager,
                    settingsStore = settingsStore,
                    workspaceManager = workspaceManager,
                    casLedger = casLedger,
                    runId = runId,
                )
            )
            addAll(
                createMcpTools(
                    refs = mcpManager.getAllAvailableToolRefs(),
                    call = { ref, input ->
                        val toolCallId = activityStore.startTool(
                            toolName = "mcp__${ref.serverName}__${ref.toolName}",
                            title = "调用 MCP 工具",
                            inputPreview = input.toString(),
                            runtime = "MCP",
                        )
                        try {
                            val result = mcpManager.callToolByRef(ref, input)
                            activityStore.complete(toolCallId, result.toolOutputPreview())
                            result
                        } catch (error: Throwable) {
                            activityStore.fail(toolCallId, error)
                            throw error
                        }
                    },
                )
            )
            addAll(createMemoryTools(settings, runId, casAuditEnabled))
            addAll(
                createSoulTools(
                    workspaceManager = workspaceManager,
                    transaction = soulTransaction,
                    runId = runId,
                )
            )
            if (conversationId != null) {
                addAll(
                    ConversationContextTools(
                        contextEngine = contextEngine,
                        conversationProvider = { getConversationFlow(conversationId).value },
                        settingsProvider = { settingsStore.settingsFlow.first() },
                        modelProvider = { settingsStore.settingsFlow.first().getCurrentChatModel() },
                    ).tools()
                )
                addAll(
                    ConversationHistoryTools(
                        conversationRepo = conversationRepo,
                        grantStore = sessionAccessGrantStore,
                    ).tools()
                )
                addAll(createConversationQueueTools(conversationId))
            }
            // P4-03: persistent JS cells (default OFF; first-version scope is
            // debug/advanced users). The tools are only exposed when the
            // js_cell_runtime capability flag is on; all sandbox limits,
            // whitelist enforcement and persistence live in JsCellRuntime.
            if (jsCellEnabled && jsCellRuntime != null) {
                addAll(app.amber.feature.jscell.createJsCellTools(jsCellRuntime, runId))
            }
            addAll(AgentTaskTools(agentTaskScheduler).tools())
        }
        val profileFilter = ToolProfileFilter.filter(rawTools, AMBER_AGENT_TOOL_PROFILE)
        val profiledRawTools = profileFilter.tools
        val baseRegistry = ToolRegistry.from(profiledRawTools)
        val baseTools = baseRegistry.tools() +
            localTools.registryIntrospectionTools(baseRegistry)
        val subAgentRawTools = if (conversationId != null && settings.agentRuntime.subAgent.enabled) {
            profiledRawTools + SubAgentTools(
                subAgentManager = subAgentManager,
                parentConversationId = conversationId,
                parentRunId = runId,
                parentToolsProvider = { baseTools },
                // P4-02: thread_graph_v2 gate — off keeps the legacy tool set
                // (no subagent_followup / send_message / interrupt).
                threadGraphEnabled = threadGraphEnabled,
            ).tools()
        } else {
            profiledRawTools
        }
        val augmentedRawTools = if (settings.agentRuntime.modelCouncil.enabled) {
            subAgentRawTools + ModelCouncilTools(
                manager = modelCouncilManager,
                workspaceManager = workspaceManager,
            ).tools()
        } else {
            subAgentRawTools
        }
        val finalRawTools = ToolProfileFilter.filter(augmentedRawTools, AMBER_AGENT_TOOL_PROFILE).tools
        val registry = ToolRegistry.from(finalRawTools)
        // P4-01: dynamically registered declarative recipes. The installed
        // snapshot comes from the round context; recipe run tools capture
        // their definition at creation, so mid-round updates never change the
        // current round. Without the recipe_runtime flag the context is null
        // and no recipe tools / import entries exist (existing behavior).
        val recipeToolsProvider: () -> List<Tool> = if (recipeContext != null && recipeRegistry != null) {
            {
                app.amber.feature.recipe.RecipeToolFactory(recipeRegistry, json).createTools(
                    context = recipeContext,
                    casLedger = casLedger,
                    primitivesProvider = { registry },
                )
            }
        } else {
            { emptyList() }
        }
        val recipeTools = recipeToolsProvider()
        val finalRegistry = if (recipeTools.isEmpty()) registry else ToolRegistry.from(finalRawTools + recipeTools)
        // Provider 配置工具（provider_config_status / apply / refresh_models /
        // settings_set_model_slot）：仅前台 Chat 注册 —— conversationId != null
        // （createDebugRunTools 走 conversationId=null，smoke receiver 等后台路径
        // 拿不到）；且刻意加在 finalRegistry 之后、不进入 profiledRawTools，
        // 因此 SubAgent 的 parentTools（来自 profiledRawTools）与 allowlist
        // 校验都不可达这四把工具。但受限 toolProfile（MINIMAL / WEB_READ 等）
        // 同样会过滤这四把工具 —— provider_config_apply 等写工具不得绕过
        // ToolProfileFilter 出现在受限 profile 的会话里。
        val providerConfigTools = if (conversationId != null && secretStore != null) {
            createProviderConfigTools(
                settingsStore = settingsStore,
                secretStore = secretStore,
                providerCatalog = providerCatalog,
                googleProvider = googleProvider,
            )
        } else {
            emptyList()
        }
        val profiledProviderConfigTools = ToolProfileFilter
            .filter(providerConfigTools, AMBER_AGENT_TOOL_PROFILE)
            .tools
        val effectiveRegistry = if (profiledProviderConfigTools.isEmpty()) {
            finalRegistry
        } else {
            ToolRegistry.from(finalRawTools + recipeTools + profiledProviderConfigTools)
        }
        // Keep theme tools at the same foreground-only boundary as provider
        // config tools: they must not enter profiledRawTools or SubAgent tools.
        val profiledThemePackTools = if (conversationId != null) {
            ToolProfileFilter
                .filter(themePackTools.filter { it.name == TOOL_THEME_PACK_IMPORT }, AMBER_AGENT_TOOL_PROFILE)
                .tools
        } else {
            emptyList()
        }
        val profiledMcpManagementTools = ToolProfileFilter
            .filter(mcpManagementTools, AMBER_AGENT_TOOL_PROFILE)
            .tools
        val foregroundOnlyTools = profiledProviderConfigTools +
            profiledThemePackTools +
            profiledMcpManagementTools
        val foregroundRegistry = if (foregroundOnlyTools.isEmpty()) {
            effectiveRegistry
        } else {
            ToolRegistry.from(
                finalRawTools + recipeTools + foregroundOnlyTools
            )
        }
        val toolSearch = createToolSearchTool(
            foregroundRegistry,
            profile = AMBER_AGENT_TOOL_PROFILE,
            registryProvider = {
                ToolRegistry.from(
                    finalRawTools +
                        recipeToolsProvider() +
                        profiledProviderConfigTools +
                        profiledThemePackTools +
                        profiledMcpManagementTools
                )
            },
        ).copy(dynamicToolsProvider = recipeContext?.let { recipeToolsProvider })
        val tools = foregroundRegistry.tools() +
            toolSearch +
            localTools.registryIntrospectionTools(foregroundRegistry)
        return tools.scopedToConversation(conversationId)
    }

    private fun List<Tool>.scopedToConversation(conversationId: Uuid?): List<Tool> {
        val scopeId = conversationId?.toString() ?: return this
        return map { tool ->
            tool.copy(
                execute = { input ->
                    activityStore.withConversation(scopeId) {
                        tool.execute(input)
                    }
                }
            )
        }
    }

    private fun createMemoryTools(settings: Settings, runId: String? = null, casAuditEnabled: Boolean = false): List<Tool> {
        if (
            !settings.agentRuntime.enableCoreMemory &&
            !settings.agentRuntime.enableShortTermMemory &&
            !settings.agentRuntime.enableLongTermMemory
        ) {
            return emptyList()
        }
        val ledger = capabilityPermissionStore?.takeIf { casAuditEnabled }
            ?.let { app.amber.feature.runtime.CapabilityBackedCasLedger(it) }
        return buildMemoryTools(
            json = json,
            onList = { scope ->
                when (scope) {
                    "core" -> memoryRepository.getGlobalMemories()
                    "short_term" -> memoryRepository.getShortTermMemories()
                    "long_term" -> memoryRepository.getLongTermMemories()
                    else -> emptyList()
                }
            },
            onCreation = { request ->
                val finalContent = if (request.source.isNullOrBlank()) {
                    request.content
                } else {
                    "${request.content}\nSource: ${request.source}"
                }
                memoryRepository.addMemory(
                    scope = request.scope,
                    kind = request.kind,
                    content = finalContent,
                    assistantId = memoryBucket(request.scope.wireName),
                    sourceConversationId = request.sourceConversationId,
                    sourceMessageIds = request.sourceMessageIds,
                    expiresAt = request.expiresAt,
                    confidence = request.confidence,
                    sourceRunId = request.sourceRunId,
                    sourceTrigger = request.sourceTrigger,
                ).let {
                    app.amber.core.model.AssistantMemory(
                        id = it.id,
                        content = it.content,
                        scope = it.scope,
                        kind = it.kind,
                        expiresAt = it.expiresAt,
                        confidence = it.confidence,
                        pinned = it.pinned,
                        archived = it.archived,
                        revision = it.revision,
                        sourceRunId = it.sourceRunId,
                        sourceTrigger = it.sourceTrigger,
                    )
                }
            },
            // P2-06 CAS: the tool layer rejects a missing revision
            // (revision_required) before this callback; the SQL CAS is the
            // authoritative guard at the repository boundary.
            onUpdateCas = { id, content, expectedRevision ->
                val revision = requireNotNull(expectedRevision) {
                    "Memory update requires a revision; call memory_list first (CAS: the update " +
                        "must be bound to the version the user saw)"
                }
                memoryRepository.updateContentCas(
                    id = id,
                    content = content,
                    expectedRevision = revision,
                    sourceRunId = runId,
                    sourceTrigger = MemoryRepository.TRIGGER_TOOL,
                )
            },
            onDeleteCas = { id, expectedRevision ->
                val revision = requireNotNull(expectedRevision) {
                    "Memory delete requires the revision from memory_list (CAS: the delete " +
                        "must be bound to the version the user saw)"
                }
                memoryRepository.deleteMemoryCas(id, revision)
            },
            onAudit = { entry ->
                ledger?.recordApproval(entry)
            },
            runIdProvider = { runId },
        )
    }

    private fun memoryBucket(scope: String): String = when (scope) {
        "core" -> MemoryRepository.GLOBAL_MEMORY_ID
        "short_term" -> MemoryRepository.SHORT_TERM_MEMORY_ID
        "long_term" -> MemoryRepository.LONG_TERM_MEMORY_ID
        else -> MemoryRepository.LONG_TERM_MEMORY_ID
    }

    /**
     * 统一删除入口：tombstone → 取消并等待生成任务 → 清队列 → repository delete。
     * 直接走 repository 删除时，生成中的流式 checkpoint / saveConversation 会在删除后
     * 把会话重新插入（复活），且复活时会话引用的附件可能已被清理。
     */
    suspend fun deleteConversation(conversation: Conversation, deferCleanup: Boolean = false) {
        val conversationId = conversation.id
        deletedConversationIds.add(conversationId)
        sessions[conversationId]?.let { session ->
            session.getJob()?.let { job ->
                job.cancel()
                runCatching { job.join() }
            }
            if (session.pendingUserMessages.value.isNotEmpty()) {
                session.clearPendingUserMessages()
            }
        }
        stopGenerationKeepAlive(conversationId)
        cancelLiveUpdateNotification(conversationId)
        try {
            conversationRepo.deleteConversation(conversation, deferCleanup = deferCleanup)
        } catch (t: Throwable) {
            deletedConversationIds.remove(conversationId)
            throw t
        }
    }

    /** 删除被撤销（如 History 的 Undo）后解除 tombstone，恢复该会话的持久化通道。 */
    fun markConversationRestored(conversationId: Uuid) {
        deletedConversationIds.remove(conversationId)
    }

    /**
     * Clear every Amber conversation through the same tombstone and cancellation path
     * as single-item deletion so an active checkpoint cannot recreate deleted history.
     */
    suspend fun deleteAllConversations() {
        val conversations = conversationRepo.getConversations().first()
        val tombstoned = mutableListOf<Uuid>()
        try {
            conversations.forEach { conversation ->
                deletedConversationIds.add(conversation.id)
                tombstoned.add(conversation.id)
                sessions[conversation.id]?.let { session ->
                    session.getJob()?.let { job ->
                        job.cancel()
                        runCatching { job.join() }
                    }
                    if (session.pendingUserMessages.value.isNotEmpty()) {
                        session.clearPendingUserMessages()
                    }
                }
                stopGenerationKeepAlive(conversation.id)
                cancelLiveUpdateNotification(conversation.id)
            }
            conversationRepo.deleteAllConversations()
        } catch (t: Throwable) {
            tombstoned.forEach(deletedConversationIds::remove)
            throw t
        }
    }

    // 停止当前会话生成任务（不清理会话缓存）。
    //
    // P1-05: cancellation is ownership-scoped. Notification stops carry a
    // runId and cancel ONLY the run registered under (assistantId,
    // conversationId, runId) — a stale or mismatched runId cancels nothing.
    // UI stops (no runId) cancel the conversation-scoped generation job. The
    // previous global terminalRuntime.cancelRunningJobs() call is gone: it
    // killed terminal jobs of OTHER conversations.
    //
    // P1-06: Stop means "enter idle". The pending queue is preserved — queued
    // text/attachments are NOT lost, STEER messages are NOT downgraded to
    // FOLLOWUP, and no pending loop is auto-started. The user resumes
    // explicitly via resumePendingQueue() or edits the queued content in the
    // composer via takePendingMessageForInput().
    suspend fun stopGeneration(conversationId: Uuid, runId: String? = null) {
        // P6-01: when the run has a stored server-side response, cancel it
        // server-side FIRST and await a decidable outcome — before cancelling
        // the local job, so onCompletion sees the decision. A cancel that
        // cannot be confirmed keeps the run in WAITING_EXTERNAL (never
        // pretend cancelled); recovery settles it later.
        var serverCancelUnconfirmed = false
        val stopCancel = storedResponseStopCancel
        if (
            runId != null &&
            durableRuntimeForStop() &&
            storedResponseToggleOnForRun(runId) &&
            stopCancel != null
        ) {
            runCatching { stopCancel.cancelStored(runId) }
                .onSuccess { decidable ->
                    if (!decidable) serverCancelUnconfirmed = true
                }
                .onFailure { error ->
                    Log.w(TAG, "stopGeneration: server cancel failed for run $runId", error)
                    serverCancelUnconfirmed = true
                }
        }
        if (serverCancelUnconfirmed) {
            pendingServerCancelFailures.add(runId)
        }
        val cancelledByOwner = if (runId != null) {
            runOwnershipRegistry?.cancel(runId = runId, conversationId = conversationId.toString()) == true
        } else {
            val job = sessions[conversationId]?.getJob()
            if (job != null) {
                job.cancel()
                runCatching { job.join() }
                true
            } else {
                false
            }
        }
        // WAITING_USER has no active generation Job by design: onCompletion
        // releases the in-memory owner while the persisted terminal keeps the
        // approval resumable. Notification Stop therefore falls back to the
        // same run-scoped durable owner, with conversation + state checked
        // atomically so a stale or cross-conversation runId cannot stop work.
        val cancelledPersistedWaitingRun = runId != null &&
            !cancelledByOwner &&
            runTerminalStore?.cancelWaitingUser(
                runId = runId,
                conversationId = conversationId.toString(),
            ) == true
        val cancelled = cancelledByOwner || cancelledPersistedWaitingRun
        if (!cancelled) {
            if (serverCancelUnconfirmed) {
                // Nothing local to cancel — the flag has no consumer; drop it.
                pendingServerCancelFailures.remove(runId)
            }
            Log.i(TAG, "stopGeneration: nothing to cancel conversation=$conversationId runId=$runId")
            return
        }
        if (cancelledPersistedWaitingRun) {
            // There is no flow completion left to stop the foreground
            // keep-alive after a persisted WAITING_USER pause is cancelled.
            stopGenerationKeepAlive(conversationId)
        }
        cancelLiveUpdateNotification(conversationId)
        trustedRunToolNames.remove(conversationId)
        screenCaptureManager.releaseSession()

        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val cancelledAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val updatedMessage = lastMessage
            .finishPendingTools(::cancelToolByUser)
            .let { message ->
                if (message.role == MessageRole.ASSISTANT) {
                    message.copy(finishedAt = message.finishedAt ?: cancelledAt)
                } else {
                    message
                }
            }
            .finishReasoning()
        if (updatedMessage == lastMessage) return

        val updatedTail = lastNode.copy(
            messages = lastNode.messages.map { message ->
                if (message.id == lastMessage.id) updatedMessage else message
            },
        )
        val nodes = currentConversation.messageNodes.toMutableList()
        nodes[nodes.lastIndex] = updatedTail
        saveConversation(conversationId, currentConversation.copy(messageNodes = nodes))
    }

    /**
     * P1-06: explicitly resume the preserved pending queue after a Stop.
     * Drains queued messages (including any STEER entries, which are sent as
     * ordinary follow-up turns) and starts generation for each.
     */
    fun resumePendingQueue(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        if (session.pendingUserMessages.value.isEmpty()) return
        launchPendingMessageLoop(conversationId)
    }

    /**
     * P1-06: "移回输入框" — take the first queued message out of the queue
     * (persisting the removal) and return its parts so the UI can place them
     * back into the composer for editing. Returns null when the queue is
     * empty.
     */
    suspend fun takePendingMessageForInput(conversationId: Uuid): List<UIMessagePart>? {
        val session = getOrCreateSession(conversationId)
        val message = session.dequeueNextPendingUserMessageDurably(conversationId) ?: return null
        recordPendingMessageEvent(
            conversationId = conversationId,
            event = "move_to_input",
            messageId = message.id,
        )
        return message.parts
    }

    private fun createConversationQueueTools(conversationId: Uuid): List<Tool> = listOf(
        Tool(
            name = "conversation_queue_status",
            description = "Read queued user messages for the current conversation. This is read-only and never exposes messages from other conversations.",
            parameters = {
                InputSchema.Obj(properties = buildJsonObject {})
            },
            execute = {
                val queued = getOrCreateSession(conversationId).pendingUserMessages.value
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("status", "ok")
                            put("count", queued.size)
                            put("messages", buildJsonArray {
                                queued.forEachIndexed { index, message ->
                                    add(
                                        buildJsonObject {
                                            put("index", index)
                                            put("id", message.id)
                                            put("mode", message.mode.name.lowercase())
                                            put("answer", message.answer)
                                            put("created_at_ms", message.createdAtMs)
                                            put("preview", message.previewText())
                                        }
                                    )
                                }
                            })
                        }.toString()
                    )
                )
            }
        ),
        Tool(
            name = "conversation_queue_cancel",
            description = "Cancel one queued user message by id, or clear the current conversation queue. Requires approval because it changes user-entered pending messages.",
            needsApproval = true,
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("message_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Queued message id to cancel. Omit when clear_all=true.")
                        })
                        put("clear_all", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Clear every queued message in the current conversation.")
                        })
                    }
                )
            },
            execute = { input ->
                val session = getOrCreateSession(conversationId)
                val clearAll = input.jsonObject["clear_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
                val messageId = input.jsonObject["message_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val changed = if (clearAll) {
                    val hadMessages = session.pendingUserMessages.value.isNotEmpty()
                    clearPendingUserMessages(conversationId)
                    hadMessages
                } else {
                    require(messageId.isNotBlank()) { "message_id is required unless clear_all=true" }
                    val hadMessage = session.pendingUserMessages.value.any { it.id == messageId }
                    cancelPendingUserMessage(conversationId, messageId)
                    hadMessage
                }
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("status", if (changed) "cancelled" else "not_found")
                            put("remaining", session.pendingUserMessages.value.size)
                        }.toString()
                    )
                )
            }
        )
    )
}

private fun Conversation.findToolName(toolCallId: String): String? =
    messageNodes.asSequence()
        .flatMap { it.messages.asSequence() }
        .flatMap { it.parts.asSequence() }
        .filterIsInstance<UIMessagePart.Tool>()
        .firstOrNull { it.toolCallId == toolCallId }
        ?.toolName

private fun Conversation.hasPendingOrUnexecutedTools(): Boolean =
    currentMessages.lastOrNull()
        ?.getTools()
        ?.any { !it.isExecuted || it.isPending } == true

private fun List<UIMessagePart>.toolOutputPreview(): String =
    joinToString("\n") { part ->
        when (part) {
            is UIMessagePart.Text -> part.text
            else -> part.toString()
        }
    }.takeLast(1_600)

/**
 * 生成中的冲突策略 —— 编辑消息（P8-01）与切换 user variant（Minor-1）共用：
 * 生成中拒绝（明确提示，不打断当前生成，不做任何写操作）。返回 null 表示允许，
 * 否则返回给用户的提示文案。提取为顶层纯函数便于单元测试（ChatService
 * editMessage/selectMessageNode 与 ChatVM 预检共用）。
 */
internal fun blockedReason(isGenerating: Boolean, message: String): String? =
    if (isGenerating) message else null
