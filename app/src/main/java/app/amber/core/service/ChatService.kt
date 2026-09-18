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
import kotlinx.coroutines.NonCancellable
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
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
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
import app.amber.ai.provider.providers.openai.OpenAICodexAuthStore
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
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
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
import app.amber.feature.ui.subagent.AppSubAgentDisplayLocalizer
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
import app.amber.core.utils.appLocale
import app.amber.core.utils.sendNotification
import app.amber.feature.runtime.NotificationApprovalCheck
import app.amber.feature.runtime.NotificationApprovalTokenRegistry
import app.amber.feature.runtime.OutcomeUnknownPrompt
import app.amber.feature.runtime.PauseReason
import app.amber.feature.runtime.RunOwnershipRegistry
import app.amber.feature.runtime.RunRecoveryService
import app.amber.feature.runtime.RunTerminal
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

/** Shared completion/failure cleanup for a generation that may already be cancelled. */
internal suspend fun finalizeChatGeneration(block: suspend () -> Unit) =
    withContext(NonCancellable) { block() }

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
    private val coldStartRecoveryGate: app.amber.feature.runtime.ColdStartRuntimeRecoveryGate? = null,
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
    // Full restore raises an epoch and serializes durable conversation writes;
    // nullable keeps legacy construction sites and isolated tests unchanged.
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
    // Jev 语义工具发现（tool_search 的语义重排）；nullable 兼容旧构造点与测试。
    private val jevToolSemanticSearch: app.amber.core.jev.JevToolSemanticSearch? = null,
) : ConversationAccess {
    // ProviderConfigTools needs the same durable Codex OAuth store as the settings and
    // provider layers. This instance is lightweight and reads the shared encrypted store.
    private val openAICodexAuthStore = OpenAICodexAuthStore(context)
    private val grokAuthStore = app.amber.ai.provider.providers.grok.GrokAuthStore(context)
    private val antigravityAuthStore = app.amber.ai.provider.providers.google.AntigravityAuthStore(context)

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
    /** Epoch captured by a deferred History purge; prevents it deleting a later restore. */
    private val deletedConversationRestoreEpochs = ConcurrentHashMap<Uuid, Long>()
    private val pendingMessageStoreOps = Channel<PendingMessageStoreOp>(Channel.UNLIMITED)
    private val pendingMessagePersistRevisions = ConcurrentHashMap<Uuid, AtomicLong>()
    private val pendingMessagePersistLocks = ConcurrentHashMap<Uuid, Mutex>()
    private val restoreWriteGateRegistration: AutoCloseable?

    private val aiAuxiliaryGenerator = AiAuxiliaryGenerator(
        context = context,
        settingsStore = settingsStore,
        providerCatalog = providerCatalog,
        conversationRepo = conversationRepo,
        conversationAccess = this,
        restoreWriteGate = restoreWriteGate,
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
                                withPendingMessageWrite {
                                    pendingMessageStore.persistBlocking(
                                        conversationId = op.conversationId,
                                        messages = op.messages,
                                    )
                                }
                            }
                        }
                    }

                    is PendingMessageStoreOp.Event -> withPendingMessageWrite {
                        pendingMessageStore.recordEvent(
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
        restoreWriteGateRegistration = restoreWriteGate?.addRestoreLifecycleListener(
            onStarted = {
                // Stop active providers before the restore waits for the short
                // durable writer section. Their callbacks keep their old
                // coroutine epoch and are dropped at the gate.
                sessions.values.forEach { it.getJob()?.cancel() }
                activeKernelRuns.value.values.forEach { agentRunner?.cancel(it) }
                sessions.keys.forEach { conversationId ->
                    stopGenerationKeepAlive(conversationId)
                    cancelLiveUpdateNotification(conversationId)
                }
                trustedRunToolNames.clear()
                screenCaptureManager.releaseSession()
                // Invalidate queued async snapshots too; a worker already in
                // the channel must not replay the pre-restore pending queue.
                pendingMessagePersistRevisions.values.forEach { it.incrementAndGet() }
            },
            onFinished = { succeeded, dataCommitted ->
                if (succeeded || dataCommitted) {
                    // A successful or partial data commit makes every loaded
                    // session stale. Clear only memory; the imported DB is the
                    // source of truth and the next access reloads it.
                    sessions.values.forEach { it.invalidateAfterRestore() }
                    sessions.keys.forEach { conversationId ->
                        persistPendingMessagesDurably(conversationId, emptyList())
                    }
                    generationCheckpointAt.clear()
                    trustedRunToolNames.clear()
                    deletedConversationIds.clear()
                    deletedConversationRestoreEpochs.clear()
                } else {
                    // A failed restore that committed no data leaves the
                    // pre-restore database intact. Drop only the captured
                    // purge epochs: the tombstones remain, and a later
                    // History purge will capture the now-current epoch.
                    deletedConversationRestoreEpochs.clear()
                }
            },
        )
    }

    fun cleanup() = runCatching {
        restoreWriteGateRegistration?.close()
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
        expectedRestoreEpoch: Long? = null,
        block: suspend () -> Unit,
    ): Job = appScope.launch(restoreWriteContext(expectedRestoreEpoch)) {
        val session = getOrCreateSession(conversationId)
        session.acquire()
        try {
            block()
        } finally {
            session.release()
        }
    }

    private fun restoreWriteContext(expectedRestoreEpoch: Long? = restoreWriteGate?.currentEpoch()): CoroutineContext =
        expectedRestoreEpoch?.let(::SyncRestoreWriteEpoch) ?: EmptyCoroutineContext

    private suspend fun expectedRestoreEpoch(): Long? =
        coroutineContext[SyncRestoreWriteEpoch]?.value

    private suspend fun captureRestoreEpoch(): Long? {
        val gate = restoreWriteGate ?: return null
        return coroutineContext[SyncRestoreWriteEpoch]?.value ?: gate.currentEpoch()
    }

    private suspend fun <T> withRestoreEpoch(
        expectedRestoreEpoch: Long?,
        block: suspend () -> T,
    ): T {
        if (restoreWriteGate == null) return block()
        val epoch = expectedRestoreEpoch
            ?: coroutineContext[SyncRestoreWriteEpoch]?.value
            ?: restoreWriteGate.currentEpoch()
        return withContext(SyncRestoreWriteEpoch(epoch)) { block() }
    }

    /** Capture the caller's epoch before a read/transform/write operation. */
    private suspend fun <T> withCapturedRestoreWriteContext(
        block: suspend () -> T,
    ): T = withRestoreEpoch(captureRestoreEpoch(), block)

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

    fun getGenerationJobStateFlow(conversationId: Uuid): StateFlow<Job?> =
        getOrCreateSession(conversationId).generationJob

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
        launchPendingDispatchIfNeeded(conversationId, session)
    }

    private fun launchPendingDispatchIfNeeded(
        conversationId: Uuid,
        session: ConversationSession,
    ) {
        if (!session.isGenerating && session.pendingUserMessages.value.isNotEmpty()) {
            launchViaKernel(conversationId)
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

        if (session.isGenerating || session.pendingUserMessages.value.isNotEmpty()) {
            val accepted = session.enqueuePendingUserMessage(pendingMessage)
            if (!accepted) {
                addError(
                    IllegalStateException(context.getString(R.string.chat_page_queue_full_error)),
                    conversationId = conversationId,
                    title = context.getString(R.string.chat_page_queue_message_not_added)
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
            // A queue restored after process death must stay ahead of a new
            // message entered while the session is idle; otherwise the new
            // message would bypass the durable FIFO and run first.
            if (!session.isGenerating) {
                launchViaKernel(conversationId)
            }
            return true
        }

        launchViaKernel(conversationId, pendingMessage)
        return true
    }

    private val activeKernelRuns =
        MutableStateFlow<Map<Uuid, app.amber.core.agent.runtime.AgentRunId>>(emptyMap())

    /** Conversations whose run paused for the user (approval / ask_user). The
     *  kernel dispatch wait ends on pause — these are invisible to
     *  activeKernelRuns but app-external surfaces (task bubble) must keep
     *  showing them until a resume dispatch or a stop clears the entry. */
    private val pausedForUserConversations = MutableStateFlow<Set<Uuid>>(emptySet())

    /** Latest terminal RunStatus per conversation, recorded when a dispatch
     *  wait ends in a terminal state or the dispatch job is cancelled. */
    private val _lastRunOutcomes = MutableStateFlow<Map<Uuid, app.amber.core.agent.runtime.RunStatus>>(emptyMap())
    val lastRunOutcomes: StateFlow<Map<Uuid, app.amber.core.agent.runtime.RunStatus>> =
        _lastRunOutcomes.asStateFlow()

    /** Conversation ids with an active or user-paused run; drives app-external surfaces (task bubble). */
    val activeConversationIds: StateFlow<Set<Uuid>> =
        combine(activeKernelRuns, pausedForUserConversations) { runs, paused -> runs.keys + paused }
            .stateIn(appScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptySet())

    /** Latest active kernel-path run for a conversation, or null if none. */
    fun getActiveKernelRunFlow(conversationId: Uuid): StateFlow<app.amber.core.agent.runtime.AgentRunId?> =
        activeKernelRuns
            .map { it[conversationId] }
            .stateIn(appScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)

    /** Exposes the AgentRunner for UI ViewModels to call observe() directly. */
    fun kernelRunner(): app.amber.core.agent.runtime.AgentRunner? = agentRunner

    /**
     * Kernel dispatch loop for a conversation: drains the pending queue one
     * turn at a time (mirroring the legacy loop's structure), each turn
     * executed by the AgentRunner. [resumeWithoutNewMessage] starts the
     * drain with a turn over the current conversation (tool approval /
     * regenerate / outcome retry / edit-regenerate) instead of a newly
     * appended user message; [messageRange] restricts that resume turn to a
     * conversation window (variant regenerate).
     */
    /**
     * External entry into the kernel dispatcher: launches the queue-draining
     * dispatch loop as the conversation's session job. Callers that already
     * run INSIDE the session job (approval / regenerate / in-loop resume)
     * must use [runKernelDispatchLoop] inline instead — this guard would
     * deterministically swallow them, since the caller's own job is what
     * makes [ConversationSession.isGenerating] true.
     */
    private fun launchViaKernel(
        conversationId: Uuid,
        firstMessage: PendingUserMessage? = null,
        resumeWithoutNewMessage: Boolean = false,
        messageRange: ClosedRange<Int>? = null,
        expectedPausedRunId: app.amber.core.agent.runtime.AgentRunId? = null,
        expectedRestoreEpoch: Long? = restoreWriteGate?.currentEpoch(),
    ) {
        val runner = agentRunner ?: return
        val session = getOrCreateSession(conversationId)
        if (session.isGenerating) {
            // Same guard as the legacy loop: a running turn absorbs new
            // messages into the queue; external resume requests during an
            // active turn are dropped (the active turn owns the conversation).
            firstMessage?.let { message ->
                if (!session.enqueuePendingUserMessage(message)) {
                    addError(
                        IllegalStateException(context.getString(R.string.chat_page_queue_full_error)),
                        conversationId = conversationId,
                        title = context.getString(R.string.chat_page_queue_message_not_added)
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
        val job = appScope.launch(restoreWriteContext(expectedRestoreEpoch)) {
            runKernelDispatchLoop(
                conversationId = conversationId,
                session = session,
                runner = runner,
                firstMessage = firstMessage,
                resumeFirst = resumeWithoutNewMessage,
                messageRange = messageRange,
                expectedPausedRunId = expectedPausedRunId,
            )
        }
        session.setJob(job)
    }

    /**
     * The kernel dispatch loop: drains the pending queue one turn at a time
     * (mirroring the retired legacy loop's structure), each turn executed by
     * the AgentRunner. [resumeFirst] starts the drain with a turn over the
     * current conversation (tool approval / regenerate / outcome retry /
     * edit-regenerate) instead of a newly appended user message;
     * [messageRange] restricts that resume turn to a conversation window
     * (variant regenerate).
     *
     * Suspending and job-agnostic: safe to run inline on an existing session
     * job or under the [launchViaKernel] wrapper.
     */
    private suspend fun runKernelDispatchLoop(
        conversationId: Uuid,
        session: ConversationSession,
        runner: app.amber.core.agent.runtime.AgentRunner,
        firstMessage: PendingUserMessage? = null,
        resumeFirst: Boolean = false,
        messageRange: ClosedRange<Int>? = null,
        expectedPausedRunId: app.amber.core.agent.runtime.AgentRunId? = null,
    ) {
        var pendingResume = resumeFirst
        var pendingRange = messageRange
        var nextMessage = if (resumeFirst) {
            null
        } else {
            firstMessage ?: session.dequeueNextPendingUserMessageDurably(conversationId)
        }
        while (pendingResume || nextMessage != null) {
            try {
                if (pendingResume) {
                    pendingResume = false
                    val range = pendingRange
                    pendingRange = null
                    val lastNode = getConversationFlow(conversationId).value
                        .messageNodes.lastOrNull()
                    if (lastNode != null) {
                        dispatchKernelTurn(
                            conversationId = conversationId,
                            runner = runner,
                            messageNodeId = lastNode.id,
                            userMessageText = "",
                            messageRange = range,
                            expectedPausedRunId = expectedPausedRunId,
                        )
                    }
                    _generationDoneFlow.emit(conversationId)
                    // Settings-driven recovery owns only the paused run. It
                    // must never use its successful completion as a reason to
                    // drain queued user messages into a fresh run.
                    if (expectedPausedRunId != null) return
                } else {
                    val dispatchMessage =
                        session.preparePendingMessageForDispatch(conversationId, nextMessage!!)
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
                        nextMessage =
                            session.dequeueNextPendingUserMessageDurably(conversationId)
                        continue
                    }
                    val userNode = appendUserMessage(conversationId, dispatchMessage)
                    if (dispatchMessage.answer) {
                        dispatchKernelTurn(
                            conversationId = conversationId,
                            runner = runner,
                            messageNodeId = userNode.id,
                            userMessageText = dispatchMessage.previewText(
                                maxChars = 4000,
                                copy = PendingUserMessageDisplayCopy.from(context),
                            ),
                        )
                        _generationDoneFlow.emit(conversationId)
                    }
                    // 仅追加、未触发生成：不能 emit generationDoneFlow。
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                addError(
                    e,
                    conversationId,
                    title = context.getString(R.string.error_title_send_message),
                )
            }

            val conversation = getConversationFlow(conversationId).value
            if (conversation.hasPendingOrUnexecutedTools()) {
                break
            }
            nextMessage = session.dequeueNextPendingUserMessageDurably(conversationId)
        }
    }

    /**
     * One kernel-dispatched generation turn: pre-flight (sanitize, reset
     * suggestions, tool-availability warning), runner launch, then wait for
     * the run's terminal state. Resume turns pass the persisted paused runId
     * so the ledger / terminal store / event log continue the same run.
     */
    private suspend fun dispatchKernelTurn(
        conversationId: Uuid,
        runner: app.amber.core.agent.runtime.AgentRunner,
        messageNodeId: Uuid,
        userMessageText: String,
        messageRange: ClosedRange<Int>? = null,
        expectedPausedRunId: app.amber.core.agent.runtime.AgentRunId? = null,
    ) {
        try {
            coldStartRecoveryGate?.awaitReady()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            addError(error, conversationId, title = "Runtime recovery failed")
            return
        }
        val settings = settingsStore.settingsFlow.first()
        // Legacy parity: a turn with no chat model configured ends silently.
        val model = settings.getCurrentChatModel() ?: return

        val input = app.amber.feature.chat.api.ChatTurnInput(
            conversationId = app.amber.core.agent.runtime.ConversationId(conversationId.toString()),
            messageNodeId = app.amber.core.agent.runtime.MessageNodeId(messageNodeId.toString()),
            assistantId = app.amber.core.agent.runtime.AssistantId("default"),
            userMessageText = userMessageText,
            messageRangeStart = messageRange?.start,
            messageRangeEndExclusive = messageRange?.let { it.endInclusive + 1 },
        )
        // P1-03 parity with the legacy loop: a paused run (approval /
        // resumable) is resumed under the SAME runId so the ledger,
        // terminal store and event log all continue the same run. Automatic
        // approval recovery is stricter: it can only claim this conversation's
        // still-live WAITING_USER row and never falls back to a new run.
        val durable = useDurableRuntime()
        val activeTerminal = if (durable) {
            runTerminalStore?.activeForConversation(conversationId.toString())
        } else {
            null
        }
        if (
            expectedPausedRunId != null &&
            !isExpectedWaitingUserRun(activeTerminal, conversationId, expectedPausedRunId)
        ) {
            return
        }
        val resumeRunId = activeTerminal
            ?.let { app.amber.core.agent.runtime.AgentRunId(it.runId) }
        val resumeCursor = resumeRunId?.let { responsesResumeStore?.load(it.value) }
        if (resumeCursor != null && resumeCursor.providerId != model.findProvider(settings.providers)?.id?.toString()) {
            addError(
                IllegalStateException("请切回原 Provider 后继续恢复，或选择重新生成。"),
                conversationId,
                title = context.getString(R.string.error_title_regenerate_message),
            )
            return
        }
        var activeRunId: app.amber.core.agent.runtime.AgentRunId? = null
        if (expectedPausedRunId != null) {
            // Register the known run before the asynchronous AgentRunner gate.
            // Stop then reaches AgentRunner.cancel(), whose pending-cancel
            // handoff covers the gap before its job is registered.
            activeKernelRuns.update { current ->
                if (current[conversationId] == null) {
                    current + (conversationId to expectedPausedRunId)
                } else {
                    current
                }
            }
            if (activeKernelRuns.value[conversationId] != expectedPausedRunId) return
            activeRunId = expectedPausedRunId
        }
        var pauseRecorded = false
        try {
            // Stop can win while policy/model preflight is running. Recheck
            // the durable owner immediately before launch; fixed requested
            // runId is retained below so a later Stop can never mint fresh work.
            if (
                expectedPausedRunId != null &&
                !isExpectedWaitingUserRun(
                    runTerminalStore?.activeForConversation(conversationId.toString()),
                    conversationId,
                    expectedPausedRunId,
                )
            ) {
                return
            }
            prepareKernelGenerationTurn(conversationId, settings, model)
            if (
                expectedPausedRunId != null &&
                !isExpectedWaitingUserRun(
                    runTerminalStore?.activeForConversation(conversationId.toString()),
                    conversationId,
                    expectedPausedRunId,
                )
            ) {
                return
            }
            // Validate the caller's epoch and capture the runner's launch epoch
            // in one short gate section; a restore cannot slip between them.
            val preLaunchSnapshot = resumeRunId?.let { runner.observe(it).value }
            val handle = withConversationWrite {
                runner.launch(
                    app.amber.feature.chat.api.ChatTurnDescriptor.ID,
                    input,
                    requestedRunId = resumeRunId,
                )
            }.getOrElse { e ->
                addError(e, conversationId, title = "Kernel dispatch failed")
                return
            }
            if (expectedPausedRunId != null && handle.runId != expectedPausedRunId) {
                runner.cancel(handle.runId)
                return
            }
            if (activeRunId == null) {
                activeKernelRuns.update { it + (conversationId to handle.runId) }
                activeRunId = handle.runId
                // 新 run 开始即清本会话旧终态，过渡 tick 才不会把上一轮成败错标到本轮。
                _lastRunOutcomes.update { it - conversationId }
                // A resume dispatch takes the conversation back from the
                // user-paused set in the same breath it re-registers as active.
                pausedForUserConversations.update { it - conversationId }
            }
            val snapshots = runner.observe(handle.runId)
            if (expectedPausedRunId != null && preLaunchSnapshot != null) {
                // The runner replaces its immutable snapshot only after this
                // launch clears the gate. Reference identity avoids relying
                // on wall-clock timestamps when a resumed run pauses again.
                snapshots.first { snapshot ->
                    snapshot !== preLaunchSnapshot || snapshot.status.isTerminal
                }
            }
            val endedSnapshot = snapshots.first { snapshot ->
                // Keep waiting through live states; a terminal outcome OR a
                // persisted pause (approval / server-cancel pending) ends
                // this turn's wait — the paused run resumes via its own
                // entry points under the same runId.
                snapshot.status.isTerminal || snapshot.status.isPause
            }
            if (endedSnapshot.status.isPause) {
                pauseRecorded = true
                pausedForUserConversations.update { it + conversationId }
            } else {
                _lastRunOutcomes.update { it + (conversationId to endedSnapshot.status) }
            }
        } catch (cancelled: CancellationException) {
            activeRunId?.let(runner::cancel)
            _lastRunOutcomes.update { it + (conversationId to app.amber.core.agent.runtime.RunStatus.CANCELLED) }
            throw cancelled
        } finally {
            // 本 dispatch 未以 pause 退出 = 会话已不在等待用户态：无论从终态、
            // 取消还是任何 bail 路径离开，都回收 paused 条目，避免僵尸气泡。
            if (!pauseRecorded) {
                pausedForUserConversations.update { it - conversationId }
            }
            activeRunId?.let { runId ->
                activeKernelRuns.update { current ->
                    if (current[conversationId] == runId) current - conversationId else current
                }
            }
        }
    }

    /**
     * Per-turn pre-flight shared by all kernel dispatches (the retired
     * legacy loop's prologue parity): load the full conversation,
     * reset suggestions, warn when tools are unavailable for the model, and
     * sanitize invalid messages before the session resolves its inputs.
     */
    private suspend fun prepareKernelGenerationTurn(
        conversationId: Uuid,
        settings: Settings,
        model: app.amber.ai.provider.Model,
    ) {
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
        val conversation = sanitizeInvalidMessagesForGeneration(initialConversation)
        if (conversation != initialConversation) {
            withConversationWrite { conversationRepo.updateConversation(conversation) }
            replaceSessionWithFullConversation(conversationId, conversation)
        }
    }

    /**
     * Continue/resume generation on the current conversation state —
     * outcome retry/abandon, edit-and-regenerate, notification approval.
     * External entry (launches a new session job); the kernel dispatcher
     * owns the queued-message drain after the turn.
     *
     * Callers already running inside the session job MUST use
     * [continueGenerationInline] — the launcher's isGenerating guard would
     * swallow the request (the caller's own job is the active one).
     */
    private suspend fun continueGeneration(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        expectedPausedRunId: app.amber.core.agent.runtime.AgentRunId? = null,
    ) {
        launchViaKernel(
            conversationId,
            resumeWithoutNewMessage = true,
            messageRange = messageRange,
            expectedPausedRunId = expectedPausedRunId,
            expectedRestoreEpoch = captureRestoreEpoch(),
        )
    }

    /**
     * Resume generation from within an existing session-job context (in-app
     * tool approval, regenerate, in-loop blocker resume): runs the resume
     * turn plus the queued-message drain inline on the caller's job —
     * the retired legacy loop's inline handleMessageComplete parity.
     */
    private suspend fun continueGenerationInline(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
    ) {
        val runner = agentRunner ?: return
        runKernelDispatchLoop(
            conversationId = conversationId,
            session = getOrCreateSession(conversationId),
            runner = runner,
            resumeFirst = true,
            messageRange = messageRange,
        )
    }

    /**
     * Steer drain shared by the legacy loop and the kernel-dispatched turn:
     * dequeue queued STEER messages, persist the shrinkage and record the
     * consumption event.
     */
    internal suspend fun consumeSteerMessagesForRun(conversationId: Uuid): List<UIMessage> {
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
        return consumed.map { queued ->
            UIMessage(
                role = MessageRole.USER,
                parts = queued.parts,
            )
        }
    }

    /**
     * Turn hooks for a kernel-dispatched chat turn (P0 kernel convergence).
     * Always present on the chat path: the lifecycle orchestration (live
     * status notification, foreground keep-alive, generation task, title /
     * suggestion / memory side-effects, error surfacing) mirrors the legacy
     * loop regardless of the durable-runtime flags; the durable writes
     * (terminal store, event-store CAS, ledger reconcile, cascade cancel)
     * self-gate on [ChatRunHooks.durable].
     *
     * The bundle is resolved once per turn, before the runner arms the run,
     * so [RunTerminalStore.activeForConversation] still reflects the
     * pre-begin state for the resume gates below.
     */
    internal suspend fun chatRunHooks(
        conversationId: Uuid,
    ): app.amber.feature.chat.impl.ChatRunHooks {
        val generationRestoreEpoch = captureRestoreEpoch()
        val durable = useDurableRuntime()
        val existingRun = if (durable) {
            runTerminalStore?.activeForConversation(conversationId.toString())
        } else {
            null
        }
        val turnSettings = settingsStore.settingsFlow.first()
        val senderName = turnSettings.getCurrentChatModel()?.displayName
            ?: context.getString(R.string.app_name)
        // This turn's generation-task id, captured between start and finish.
        var generationTaskId: String? = null
        var reportedTerminal: app.amber.core.ai.GenerationTerminal? = null
        var responseRequest: app.amber.ai.provider.ResponsesResumeRequest? = null
        return app.amber.feature.chat.impl.ChatRunHooks(
            durable = durable,
            processingStatus = getOrCreateSession(conversationId).processingStatus,
            autoApprovedToolNames = trustedRunToolNames[conversationId].orEmpty(),
            consumeSteerMessages = { consumeSteerMessagesForRun(conversationId) },
            onRunStarted = { runId ->
                if (durable) {
                    runTerminalStore!!.begin(runId, conversationId.toString(), AMBER_AGENT_ID.toString())
                    // P1-05: the hook runs inside the runner's handler
                    // coroutine — its Job owns the provider transport
                    // collected downstream, so it is the cancellation owner
                    // for (assistantId, conversationId, runId).
                    kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let { job ->
                        runCatching {
                            runOwnershipRegistry?.register(
                                assistantId = AMBER_AGENT_ID.toString(),
                                conversationId = conversationId.toString(),
                                runId = runId,
                                job = job,
                            )
                        }
                    }
                }
                updateAgentLiveStatus(
                    conversationId = conversationId,
                    messages = getConversationFlow(conversationId).value.currentMessages,
                    senderName = senderName,
                    settings = turnSettings,
                    runId = runId.takeIf { durable },
                )
                startGenerationKeepAlive(conversationId, senderName, turnSettings)
                generationTaskId = startGenerationTask(
                    conversationId = conversationId,
                    runId = runId,
                    senderName = senderName,
                    modelName = senderName,
                    settings = turnSettings,
                )
            },
            onTerminal = { _, terminal ->
                // Final content must reach durable storage before either run
                // owner publishes completion, a limit, or an approval pause.
                reportedTerminal = terminal
            },
            onStreamingMessages = { runId, messages ->
                updateAgentLiveStatus(
                    conversationId = conversationId,
                    messages = messages,
                    senderName = senderName,
                    settings = turnSettings,
                    runId = runId.takeIf { durable },
                )
                // M1: the executed tool results are durable in the
                // conversation now — the ledger replay payload is no longer
                // read, so drop it (bounded retention).
                if (checkpointConversation(conversationId, getConversationFlow(conversationId).value)) {
                    clearPersistedToolPayloads(durable, messages)
                }
            },
            onRunFinished = { runId, cause ->
                finalizeChatGeneration {
                    runCatching { runOwnershipRegistry?.unregister(runId) }
                    if (restoreWriteGate?.isWriteAllowed(generationRestoreEpoch) == false) {
                        generationTaskId?.let {
                            finishGenerationTask(it, CancellationException("Generation interrupted by restore"), runId)
                        }
                        localTools.endWebMountRun(
                            runId = runId,
                            conversationId = conversationId.toString(),
                            reason = "generation superseded by restore",
                        )
                        return@finalizeChatGeneration
                    }
                    val currentConversation = getConversationFlow(conversationId).value
                    val updatedConversation = currentConversation.copy(
                        messageNodes = currentConversation.messageNodes.map { node ->
                            node.copy(messages = node.messages.map { it.finishReasoning() })
                        },
                        updateAt = Instant.now(),
                    )
                    updateConversation(conversationId, updatedConversation, checkDeletedFiles = false)
                    val conversationCheckpointed = checkpointConversation(
                        conversationId, updatedConversation, force = true,
                    )
                    if (restoreWriteGate?.isWriteAllowed(generationRestoreEpoch) == false) {
                        generationTaskId?.let {
                            finishGenerationTask(it, CancellationException("Generation interrupted by restore"), runId)
                        }
                        localTools.endWebMountRun(
                            runId = runId,
                            conversationId = conversationId.toString(),
                            reason = "generation superseded by restore",
                        )
                        return@finalizeChatGeneration
                    }
                    var terminalPublish: RunTerminalState? = null
                    var terminalOwnerSucceeded = !durable
                    if (durable) {
                        val (state, reason) = terminalForFlowEnd(cause, reportedTerminal)
                        val serverCancelPending = pendingServerCancelFailures.remove(runId)
                        val target = when {
                            serverCancelPending -> RunTerminalState.WAITING_EXTERNAL
                            !conversationCheckpointed && cause == null -> RunTerminalState.RESUMABLE
                            else -> state
                        }
                        val targetReason = when {
                            serverCancelPending -> PauseReason.USER_STOP
                            target == RunTerminalState.RESUMABLE -> null
                            else -> reason
                        }
                        terminalOwnerSucceeded = runCatching {
                            if (target.isTerminal) {
                                runTerminalStore!!.finish(runId, target, targetReason)
                            } else {
                                runTerminalStore!!.pause(runId, target, targetReason)
                            }
                            val persisted = runTerminalStore.get(runId)
                            terminalPublish = persisted?.state
                            // Keep the kernel's CAS state in agreement with the
                            // durable owner, including deferred checkpoints.
                            val persistedState = persisted?.state?.let {
                                app.amber.core.agent.runtime.RunStatus.valueOf(it.name)
                            }
                            val kernelTransition = if (persistedState != null) {
                                agentEventStore?.transitionRun(
                                    app.amber.core.agent.runtime.AgentRunId(runId),
                                    app.amber.core.agent.runtime.RunStatus.LIVE_STATES,
                                    persistedState,
                                )
                            } else {
                                null
                            }
                            val kernelOwnerSucceeded = agentEventStore == null ||
                                kernelTransition is app.amber.core.agent.runtime.RunTransitionResult.Applied ||
                                (kernelTransition is app.amber.core.agent.runtime.RunTransitionResult.Rejected &&
                                    kernelTransition.current == persistedState)
                            persisted?.state == target && kernelOwnerSucceeded
                        }.onFailure { error ->
                            Log.w(TAG, "terminal owner persist failed for $conversationId", error)
                        }.getOrDefault(false)
                        if (terminalOwnerSucceeded && conversationCheckpointed &&
                            !serverCancelPending &&
                            terminalPublish in setOf(RunTerminalState.COMPLETED, RunTerminalState.STEP_LIMIT)
                        ) {
                            responseRequest?.let { request ->
                                runCatching { request.store.clear(request.runId) }
                                    .onFailure { Log.w(TAG, "stored response cursor clear failed for $conversationId", it) }
                            }
                        }
                        if (state == RunTerminalState.CANCELLED || state == RunTerminalState.FAILED || serverCancelPending) {
                            runCatching { runRecovery!!.reconcileStartedEffects(runId) }
                            runCatching { refreshOutcomeUnknown() }
                        }
                        if (state == RunTerminalState.CANCELLED) {
                            runCatching { subAgentManager.cancelByRootRun(runId, conversationId.toString()) }
                        }
                    }
                    if (terminalOwnerSucceeded && terminalPublish != RunTerminalState.WAITING_USER) {
                        localTools.endWebMountRun(
                            runId = runId,
                            conversationId = conversationId.toString(),
                            reason = if (cause == null) "generation finished" else "generation failed",
                            preservePendingHandoff = terminalPublish == RunTerminalState.COMPLETED,
                        )
                    }

                    // P8-11: a paused approval run keeps the live notification
                    // (approve/deny/reply/stop actions); terminal outcomes
                    // dismiss it, failures replace it with the failure card.
                    val waitingForApproval = terminalPublish == RunTerminalState.WAITING_USER &&
                        getConversationFlow(conversationId).value.currentMessages.any { message ->
                            message.parts.any { it is UIMessagePart.Tool && it.isPending }
                        }
                    when {
                        waitingForApproval -> updateAgentLiveStatus(
                            conversationId = conversationId,
                            messages = getConversationFlow(conversationId).value.currentMessages,
                            senderName = senderName,
                            settings = turnSettings,
                            runId = runId.takeIf { durable },
                        )

                        cause == null -> cancelLiveUpdateNotification(conversationId)

                        cause is CancellationException ||
                            !turnSettings.agentRuntime.enableLiveStatusNotification ->
                            cancelLiveUpdateNotification(conversationId)

                        else -> liveStatusNotifier.notifyFailure(
                            conversationId = conversationId,
                            senderName = senderName,
                            error = cause,
                            launchIntent = getPendingIntent(context, conversationId, runId.takeIf { durable }),
                        )
                    }

                    // Keep-alive follows the persisted terminal state: pauses
                    // keep it (the user can resume from the notification);
                    // terminal states stop it unless a queued continuation
                    // starts the next turn right away.
                    val shouldStopKeepAlive = if (durable) {
                        runCatching { runTerminalStore?.get(runId)?.state?.isTerminal == true }
                            .getOrDefault(true)
                    } else {
                        true
                    }
                    if (shouldStopKeepAlive && !hasQueuedContinuation(conversationId)) {
                        stopGenerationKeepAlive(conversationId)
                    }

                    generationCheckpointAt.remove(conversationId)
                    generationTaskId?.let { finishGenerationTask(it, cause, runId) }
                    cleanupRunResourcesIfDone(conversationId, updatedConversation)

                    check(terminalOwnerSucceeded) {
                        "Chat terminal state was not durably committed for $conversationId"
                    }

                    if (cause == null) {
                        // Completion notification only when the run truly
                        // completed (STEP_LIMIT / WAITING_USER are never
                        // "done"); non-durable turns have no persisted state
                        // and complete with the flow.
                        val completed = if (durable) {
                            terminalPublish == RunTerminalState.COMPLETED && terminalOwnerSucceeded
                        } else {
                            true
                        }
                        if (
                            completed &&
                            !isForeground.value &&
                            turnSettings.displaySetting.enableNotificationOnMessageGeneration
                        ) {
                            sendGenerationDoneNotification(conversationId, senderName, runId.takeIf { durable })
                        }
                        // Success side-effects (legacy onSuccess parity):
                        // window persistence, title, suggestions, memory.
                        val finalConversation = getConversationFlow(conversationId).value
                        persistConversationWindow(conversationId, finalConversation, indexFts = true)
                        cleanupRunResourcesIfDone(conversationId, finalConversation)
                        launchWithConversationReference(conversationId, generationRestoreEpoch) {
                            generateTitle(conversationId, finalConversation)
                        }
                        launchWithConversationReference(conversationId, generationRestoreEpoch) {
                            generateSuggestion(conversationId, finalConversation)
                        }
                        if (!finalConversation.hasPendingOrUnexecutedTools()) {
                            appScope.launch(Dispatchers.IO + restoreWriteContext(generationRestoreEpoch)) {
                                memoryExtractor.extractAfterConversation(
                                    loadFullConversationForGeneration(conversationId)
                                )
                            }
                        }
                    } else {
                        trustedRunToolNames.remove(conversationId)
                        screenCaptureManager.releaseSession()
                        surfaceGenerationFailure(conversationId, cause)
                    }
                }
            },
            responsesResumeFor = resume@{ runId ->
                if (!durable) return@resume null
                val currentSettings = settingsStore.settingsFlow.first()
                val currentModel = currentSettings.getCurrentChatModel()
                val resumeProvider =
                    currentModel?.findProvider(currentSettings.providers) as? ProviderSetting.OpenAI
                val currentConversation = getConversationFlow(conversationId).value
                if (
                    resumeProvider != null &&
                    resumeProvider.enableResponsesResume &&
                    resumeProvider.supportsResponsesResume() &&
                    capabilityFlags?.isEnabled(Capability.OpenAIResponsesResume) == true &&
                    responsesResumeStore != null &&
                    (
                        // continuation: same runId, re-attach to the stored response
                        (existingRun != null &&
                            existingRun.runId == runId &&
                            existingRun.state in RESPONSES_RESUME_STATES &&
                            currentConversation.currentMessages.lastOrNull()?.role == MessageRole.ASSISTANT) ||
                            // fresh generation: new runId, write-ahead cursor
                            existingRun == null
                        )
                ) {
                    app.amber.ai.provider.ResponsesResumeRequest(
                        runId = runId,
                        store = responsesResumeStore,
                        resumeFrom = existingRun?.takeIf { it.state in RESPONSES_RESUME_STATES }
                            ?.let { responsesResumeStore.load(it.runId) },
                    ).also { responseRequest = it }
                } else {
                    null
                }
            },
        )
    }

    /**
     * Surface a generation failure with a targeted title + actionable hint
     * (compaction / context-size get dedicated titles) instead of the
     * generic generation error. Cancellation is filtered inside [addError].
     */
    private fun surfaceGenerationFailure(conversationId: Uuid, cause: Throwable) {
        if (cause is CancellationException) return
        val (errorTitle, surfacedError) = when (cause) {
            is app.amber.core.context.ContextCompactionFailedException -> {
                val hint = context.getString(
                    R.string.error_auto_compact_failed_hint,
                    cause.phase,
                    cause.compactionReason,
                )
                context.getString(R.string.error_title_compress_conversation) to RuntimeException(hint, cause)
            }

            // P1-04: the final token fit could not satisfy the hard budget
            // even after trimming — the request was never sent.
            is app.amber.core.context.ContextTooLargeException ->
                context.getString(R.string.chat_page_compress_recent_content_too_large) to cause

            else -> context.getString(R.string.error_title_generation) to cause
        }
        addError(surfacedError, conversationId, title = errorTitle)
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

        val userAnswer = message.previewText(
            maxChars = 4_000,
            copy = PendingUserMessageDisplayCopy.from(context),
        )
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
            // In-loop resume: run the turn inline on the dispatcher job —
            // the external launcher would be swallowed by its own
            // isGenerating guard here.
            continueGenerationInline(conversationId)
            return true
        }
        return false
    }

    private fun PendingUserMessage.isToolApprovalContinuation(): Boolean {
        if (parts.any { it !is UIMessagePart.Text }) return false
        val raw = previewText(
            maxChars = 80,
            copy = PendingUserMessageDisplayCopy.from(context),
        ).trim().lowercase(Locale.ROOT)
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
                    withPendingMessageWrite {
                        pendingMessageStore.persistBlocking(conversationId, messages)
                    }
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
                    val gate = restoreWriteGate
                    if (gate == null) {
                        pendingMessageStore.persistBlocking(conversationId, messages)
                    } else {
                        gate.withCurrentWriterOrCancel {
                            pendingMessageStore.persistBlocking(conversationId, messages)
                        }
                    }
                }
            }
        }
    }

    private suspend fun withPendingMessageWrite(block: suspend () -> Unit) {
        val gate = restoreWriteGate
        if (gate == null) block() else gate.withCurrentWriterOrCancel(block)
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
                buildCollectedPendingUserMessage(
                    messages = listOf(message) + collected,
                    copy = PendingUserMessageDisplayCopy.from(context),
                )
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

        session.setJob(appScope.launch(restoreWriteContext()) {
            // Wait for the cancelled generation's onCompletion to finish writing,
            // so it doesn't race with our state mutations below.
            oldJob?.let { runCatching { it.join() } }
            try {
                val conversation = ensureFullConversationLoaded(conversationId)

                // Regenerate replaces an interrupted response; it must not
                // reuse that response's runId or overwrite its resume cursor.
                if (message.role == MessageRole.USER || regenerateAssistantMsg) {
                    val previousRun = runTerminalStore?.activeForConversation(conversationId.toString())
                        ?.takeIf { it.state in RESPONSES_RESUME_STATES }
                    if (previousRun != null) {
                        check(checkNotNull(storedResponseStopCancel).cancelForRegeneration(
                            previousRun.runId, checkNotNull(runTerminalStore),
                        )) { "原响应的取消尚未确认，请稍后再重新生成。" }
                        runRecovery?.reconcileStartedEffects(previousRun.runId)
                        refreshOutcomeUnknown()
                    }
                }

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
                        continueGenerationInline(conversationId)
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
                        // The answer being regenerated is a branch point: any
                        // later messages were generated from the old answer
                        // and must not remain in the active context while the
                        // replacement is streamed. Keep the target node so
                        // the new answer can become its selected variant.
                        saveConversation(
                            conversationId,
                            conversation.copy(
                                messageNodes = conversation.messageNodes.take(nodeIndex + 1),
                            ),
                        )
                        continueGenerationInline(conversationId, messageRange = 0..<nodeIndex)
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

        session.setJob(appScope.launch(restoreWriteContext()) {
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
    ): Boolean = withCapturedRestoreWriteContext restore@{
        val registry = notificationApprovalTokens ?: return@restore false
        val binding = registry.consume(token) ?: return@restore false
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
            return@restore false
        }
        // Mirrors the in-app path: cancel the paused generation job, apply the
        // decision (approve/deny/answer), and resume the same run.
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()
        // Notification actions run in an AppScope coroutine rather than the
        // in-app session job. Register that coroutine as the new owner before
        // resuming so the UI observes the resumed generation and Stop/edit
        // guards cannot treat the conversation as idle.
        kotlinx.coroutines.currentCoroutineContext()[Job]?.let(session::setJob)
        try {
            applyToolApprovalDecision(conversationId, toolCallId, approved, reason, answer)
        } catch (e: Exception) {
            addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            return@restore false
        }
        true
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

        // Only continue generation when all pending tools are handled; the
        // resume runs inline on the caller's job (the in-app path wraps this
        // in session.setJob, so the external launcher's guard would swallow
        // it) and drains the queued messages after the turn.
        if (!hasPendingTools) {
            continueGenerationInline(conversationId)
        }

        _generationDoneFlow.emit(conversationId)
    }

    /**
     * Re-evaluate one visible conversation after its high-risk approval setting
     * changes. This is deliberately not a background sweep: it only resumes a
     * persisted WAITING_USER run under the exact same run id.
     */
    suspend fun resumePendingToolsWithCurrentApprovalSettings(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.join()
        // Respect Stop: changing a setting must not revive a cancelled run.
        if (previousJob?.isCancelled == true) return
        if (!settingsStore.settingsFlow.value.agentRuntime.autoApproveHighRiskToolCalls) return
        if (!useDurableRuntime()) return
        val waitingRun = runTerminalStore?.activeForConversation(conversationId.toString())
            ?.takeIf { it.state == RunTerminalState.WAITING_USER }
            ?: return
        val expectedRunId = app.amber.core.agent.runtime.AgentRunId(waitingRun.runId)
        if (!isExpectedWaitingUserRun(waitingRun, conversationId, expectedRunId)) return
        val conversation = ensureFullConversationLoaded(conversationId)
        val pendingTools = conversation.currentMessages.lastOrNull()?.getTools()
            ?.filter { it.isPending }
            .orEmpty()
        // ask_user remains an explicit human interaction. Do not re-arm a
        // mixed approval batch from a global setting; the kernel independently
        // rechecks the policy before any execution.
        if (
            pendingTools.isEmpty() ||
            pendingTools.any { tool ->
                tool.toolName == ASK_USER_TOOL_NAME ||
                    tool.metadata?.get("run_id")?.jsonPrimitive?.contentOrNull != waitingRun.runId
            }
        ) return
        continueGeneration(
            conversationId = conversationId,
            expectedPausedRunId = expectedRunId,
        )
    }

    fun approvePendingAutoApprovableTools(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        if (session.isGenerating) return

        val job = appScope.launch(restoreWriteContext()) {
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
                    continueGenerationInline(conversationId)
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
        withCapturedRestoreWriteContext restore@{
            if (!useDurableRuntime()) return@restore
            val effect = toolEffectLedger!!.get(effectId) ?: return@restore
            val run = effect.runId?.let { runTerminalStore!!.get(it) } ?: return@restore
            if (run.conversationId != conversationId.toString()) return@restore

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
            continueGeneration(conversationId)
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
                    // A callId can have several ledger rows (retries, older
                    // attempts); sweep them all — markResultPersisted only
                    // touches FINISHED/FAILED rows with a payload, so this
                    // can never corrupt a live PREPARED/STARTED effect.
                    ledger.listByToolCallId(part.toolCallId).forEach { effect ->
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
        runId: String,
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
                        put("run_id", runId)
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

    private suspend fun finishGenerationTask(taskId: String, cause: Throwable?, runId: String) {
        val expectedSpec = agentTaskScheduler.read(taskId)?.spec
            ?.takeIf { it["run_id"]?.jsonPrimitive?.contentOrNull == runId }
            ?: return
        runCatching {
            when {
                cause == null -> agentTaskScheduler.complete(
                    taskId, summary = "Generation completed.", expectedSpec = expectedSpec,
                )
                cause is CancellationException -> agentTaskScheduler.fail(
                    taskId = taskId,
                    message = "Generation cancelled.",
                    code = "cancelled",
                    expectedSpec = expectedSpec,
                )

                else -> agentTaskScheduler.fail(
                    taskId = taskId,
                    message = cause.message ?: cause::class.java.simpleName,
                    code = "generation_failed",
                    expectedSpec = expectedSpec,
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
    ): Boolean {
        val now = System.currentTimeMillis()
        val last = generationCheckpointAt[conversationId] ?: 0L
        if (!force && now - last < GENERATION_CHECKPOINT_INTERVAL_MS) return false
        generationCheckpointAt[conversationId] = now
        val startedAt = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        return runCatching {
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
            true
        }.onFailure { error ->
            Log.w(TAG, "checkpointConversation failed for $conversationId", error)
        }.getOrDefault(false)
    }

    private suspend fun persistConversationWindow(
        conversationId: Uuid,
        conversation: Conversation,
        indexFts: Boolean,
    ) {
        withConversationWrite {
            persistConversationWindowInternal(conversationId, conversation, indexFts)
        }
    }

    private suspend fun persistConversationWindowInternal(
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
            saveConversationInternal(conversationId, conversation)
            return
        }
        conversationRepo.upsertConversationWindow(
            conversation = conversation,
            firstNodeIndex = loadState.oldestLoadedIndex,
            indexFts = indexFts,
        )
    }

    /** Serialize the actual repository write and reject an old generation epoch. */
    private suspend fun <T> withConversationWrite(block: suspend () -> T): T {
        val gate = restoreWriteGate
        return if (gate == null) {
            block()
        } else {
            gate.withCurrentWriterOrCancel(block)
        }
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
        // This API only projects the conversation into the in-memory session.
        // Physical attachment cleanup belongs to saveConversationInternal,
        // where the suspend caller's restore epoch is available. Keeping the
        // old compatibility flag avoids breaking ConversationAccess callers,
        // while preventing a synchronous update from scheduling cleanup under
        // a newer epoch after an older generation resumes.
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

    private fun checkFilesDelete(
        newConversation: Conversation,
        oldConversation: Conversation,
        expectedRestoreEpoch: Long? = null,
    ) {
        val retainedFiles = newConversation.files.toHashSet()
        val removedFiles = oldConversation.files.filterNot(retainedFiles::contains)
        if (removedFiles.isEmpty()) return

        filesManager.deleteChatFiles(removedFiles, expectedRestoreEpoch)
        Log.w(TAG, "checkFilesDelete: $removedFiles")
    }

    override suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        withConversationWrite {
            saveConversationInternal(conversationId, conversation)
        }
    }

    /** User initiated pin changes share the same durable restore boundary as chat writes. */
    suspend fun togglePinnedStatus(conversationId: Uuid) {
        withConversationWrite {
            conversationRepo.togglePinStatus(conversationId)
        }
    }

    private suspend fun saveConversationInternal(conversationId: Uuid, conversation: Conversation) {
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
        val expectedRestoreEpoch = captureRestoreEpoch()
        val previousConversation = getConversationFlow(conversationId).value
        updateConversation(conversationId, updatedConversation, checkDeletedFiles = false)
        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
        // Removing old attachments is only safe after the new references commit.
        checkFilesDelete(updatedConversation, previousConversation, expectedRestoreEpoch)
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
        withCapturedRestoreWriteContext restore@{
            if (parts.isEmptyInputMessage()) return@restore
            val processedParts = userInputPreprocessor.process(parts)

            val session = getOrCreateSession(conversationId)
            // P8-01: 生成中编辑冲突——明确提示并拒绝，不打断当前生成，也不做任何写操作。
            val conflictReason = blockedReason(
                session.isGenerating,
                context.getString(R.string.chat_page_edit_generating),
            )
            if (conflictReason != null) {
                addError(
                    IllegalStateException(conflictReason),
                    conversationId = conversationId,
                    title = context.getString(R.string.error_title_operation),
                )
                return@restore
            }

            val currentConversation = ensureFullConversationLoaded(conversationId)
            val updatedConversation = currentConversation.withEditedUserVariant(messageId, processedParts)
            if (updatedConversation === currentConversation) return@restore

            contextEngine.invalidateCompacts(conversationId, "message_edited")
            saveConversation(conversationId, updatedConversation)

            // P8-01: 「保存并重新生成」——从新选中的 user variant 生成 assistant 分支。
            // 生成绑定新 variant：kernel dispatcher 使用 conversation.currentMessages，
            // 其 selectIndex 已指向新 variant。dispatcher 以会话 Job 运行，Stop 可取消、可防重复。
            if (regenerate) {
                continueGeneration(conversationId)
            }
        }
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation = withCapturedRestoreWriteContext {
        val currentConversation = ensureFullConversationLoaded(conversationId)
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }.takeIf { it >= 0 } ?: throw NoSuchElementException("Message not found")

        val copiedFiles = mutableListOf<android.net.Uri>()
        val forkedId = Uuid.random()
        val forked = try {
            val sourceNodes = currentConversation.messageNodes.take(targetNodeIndex + 1)
            val copiedNodes = sourceNodes.map { sourceNode ->
                sourceNode.copy(
                    id = Uuid.random(),
                    messages = sourceNode.messages.map { sourceMessage ->
                        sourceMessage.copy(
                            parts = sourceMessage.parts.map { part -> part.copyWithForkedFileUrl(copiedFiles) },
                        )
                    },
                )
            }
            Conversation(
                id = forkedId,
                assistantId = currentConversation.assistantId,
                messageNodes = copiedNodes,
            ).also { saveConversation(it.id, it) }
        } catch (error: Exception) {
            withContext(NonCancellable) {
                // A cancelled save may already have committed. Only reclaim an
                // aborted fork whose files have no persisted conversation owner.
                if (!conversationRepo.existsConversationById(forkedId)) {
                    filesManager.deleteChatFiles(copiedFiles).join()
                }
            }
            throw error
        }
        contextEngine.copyValidCompactsToConversation(
            sourceConversationId = conversationId,
            targetConversation = forked,
        )
        forked
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        withCapturedRestoreWriteContext restore@{
            val session = getOrCreateSession(conversationId)
            // Minor-1: 生成中切换 user variant 与生成写竞争（saveConversation 可能
            // 覆盖流式写入的下游分支）。与 editMessage 的权威守卫一致：明确提示并
            // 拒绝，不打断当前生成，也不做任何写操作。
            val conflictReason = blockedReason(session.isGenerating, context.getString(R.string.chat_page_edit_generating))
            if (conflictReason != null) {
                addError(
                    IllegalStateException(conflictReason),
                    conversationId = conversationId,
                    title = context.getString(R.string.error_title_operation),
                )
                return@restore
            }

            val currentConversation = ensureFullConversationLoaded(conversationId)
            // P8-02: 切换 variant 后，下游可见分支同步切换（截断到被切换节点，
            // 与新 variant 上下文保持一致）。
            val updatedConversation = currentConversation.withSelectedVariant(nodeId, selectIndex)
            if (updatedConversation === currentConversation) return@restore

            contextEngine.invalidateCompacts(conversationId, "message_branch_changed")
            saveConversation(conversationId, updatedConversation)
        }
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        withCapturedRestoreWriteContext restore@{
            val currentConversation = ensureFullConversationLoaded(conversationId)
            val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

            if (updatedConversation == null) {
                if (failIfMissing) {
                    throw NoSuchElementException("Message not found")
                }
                return@restore
            }

            contextEngine.invalidateCompacts(conversationId, "message_deleted")
            saveConversation(conversationId, updatedConversation)
            val retainedFiles = updatedConversation.files.toHashSet()
            withConversationWrite {
                filesManager.deleteChatImageFiles(
                    conversationId,
                    currentConversation.files.filterNot(retainedFiles::contains),
                )
            }
        }
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

    private suspend fun UIMessagePart.copyWithForkedFileUrl(copiedFiles: MutableList<android.net.Uri>): UIMessagePart {
        if (this is UIMessagePart.Tool) {
            return copy(output = output.map { it.copyWithForkedFileUrl(copiedFiles) })
        }
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
            ?: error("Failed to copy attachment for fork: $sourceUrl")
        copiedFiles.add(copiedUrl.toUri())

        return when (this) {
            is UIMessagePart.Image -> copy(url = copiedUrl)
            is UIMessagePart.Document -> copy(url = copiedUrl)
            is UIMessagePart.Video -> copy(url = copiedUrl)
            is UIMessagePart.Audio -> copy(url = copiedUrl)
        }
    }

    internal fun createDebugRunTools(settings: Settings): List<Tool> =
        createRunTools(settings, null, casAuditEnabled = false)

    /**
     * P4-01: per-round recipe execution context — the installed recipe
     * snapshot plus the same permission/ledger knobs the round uses. Shared
     * by the legacy loop and the kernel chat-turn session so both paths
     * expose the identical recipe tool surface.
     */
    private suspend fun buildRecipeRunContext(
        conversationId: Uuid,
        runId: String?,
        settings: Settings,
        conversation: Conversation,
        durablePath: Boolean,
        events: app.amber.core.agent.runtime.AgentEventWriter? = null,
        executionPolicy: app.amber.feature.runtime.ExecutionPolicy =
            app.amber.feature.runtime.ExecutionPolicy.permissive(),
    ): app.amber.feature.recipe.RecipeRunContext? {
        if (toolDispatcher == null || recipeRegistry == null) return null
        if (capabilityFlags?.isEnabled(app.amber.core.settings.Capability.RecipeRuntime) != true) {
            return null
        }
        val recipeCapabilityState = if (
            capabilityFlags.isEnabled(app.amber.core.settings.Capability.CapabilityPermissions) == true &&
            capabilityPermissionStore != null
        ) {
            capabilityPermissionStore.state()
        } else {
            null
        }
        return app.amber.feature.recipe.RecipeRunContext(
            installed = recipeRegistry.installed(),
            dispatcher = toolDispatcher,
            runId = runId,
            conversationId = conversationId.toString(),
            ledger = if (durablePath) toolEffectLedger else null,
            events = events.takeIf { durablePath },
            autoApproveTools = settings.agentRuntime.autoApproveAllToolCalls ||
                conversation.autoApproveToolCalls,
            autoApproveHighRiskTools = settings.agentRuntime.autoApproveHighRiskToolCalls,
            autoApprovedToolNames = trustedRunToolNames[conversationId].orEmpty(),
            capabilityPermissions = recipeCapabilityState,
            approvalHistory = capabilityPermissionStore?.takeIf { recipeCapabilityState != null },
            permissionContext = app.amber.feature.runtime.CapabilityPermissionContext(
                assistantId = AMBER_AGENT_ID.toString(),
                conversationId = conversationId.toString(),
                sessionId = runId,
            ),
            executionPolicy = executionPolicy,
            installedProvider = { recipeRegistry.installedSnapshot() },
        )
    }

    /**
     * Kernel-path tool surface for a chat turn — the same gates the legacy
     * loop applies (capability audit, recipe runtime, thread graph, JS
     * cell), keyed by the kernel runId.
     */
    internal suspend fun createKernelRunTools(
        settings: Settings,
        conversationId: Uuid,
        runId: String?,
        conversation: Conversation,
        durablePath: Boolean,
        events: app.amber.core.agent.runtime.AgentEventWriter? = null,
        executionPolicy: app.amber.feature.runtime.ExecutionPolicy =
            app.amber.feature.runtime.ExecutionPolicy.permissive(),
    ): List<Tool> = createRunTools(
        settings,
        conversationId,
        runId,
        casAuditEnabled = capabilityFlags?.isEnabled(
            app.amber.core.settings.Capability.CapabilityPermissions
        ) == true,
        recipeContext = buildRecipeRunContext(
            conversationId = conversationId,
            runId = runId,
            settings = settings,
            conversation = conversation,
            durablePath = durablePath,
            events = events,
            executionPolicy = executionPolicy,
        ),
        // P1-7: the same run policy the recipe context carries is handed to
        // the subagent tools, so children start under the parent's sandbox.
        executionPolicy = executionPolicy,
        threadGraphEnabled = capabilityFlags?.isEnabled(
            app.amber.core.settings.Capability.ThreadGraphV2
        ) == true,
        jsCellEnabled = capabilityFlags?.isEnabled(
            app.amber.core.settings.Capability.JSCellRuntime
        ) == true,
    )

    /** Full (window-merged) conversation for a generation turn. */
    internal suspend fun conversationForGeneration(conversationId: Uuid): Conversation =
        loadFullConversationForGeneration(conversationId)

    private fun createRunTools(
        settings: Settings,
        conversationId: Uuid?,
        runId: String? = null,
        casAuditEnabled: Boolean = false,
        recipeContext: app.amber.feature.recipe.RecipeRunContext? = null,
        // P1-7: the parent run's sandbox policy — handed to SubAgentTools so
        // the children a (possibly narrowed) run starts stay under its sandbox.
        executionPolicy: app.amber.feature.runtime.ExecutionPolicy =
            app.amber.feature.runtime.ExecutionPolicy.permissive(),
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
        val assistantLocalTools = localTools.getTools(
            options = AMBER_AGENT_LOCAL_TOOLS,
            conversationId = conversationId,
            runId = runId,
        )
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
                        locale = context.appLocale(),
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
                            title = context.getString(R.string.tool_activity_mcp_call),
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
                parentPolicy = executionPolicy,
                parentToolsProvider = { baseTools },
                // A subagent receives the same logical catalog, but WebMount tools must bind
                // their lease to the internally assigned child generation scope. Rebuild only
                // the local-tool entries through the existing scoped factory; all other parent
                // tool closures keep their current identity and no model-supplied identity is trusted.
                parentToolsForRun = { childScopeId ->
                    val childScopedLocalTools = localTools.getTools(
                        options = AMBER_AGENT_LOCAL_TOOLS,
                        conversationId = conversationId,
                        runId = childScopeId,
                    ).associateBy { it.name }
                    baseTools.map { childScopedLocalTools[it.name] ?: it }
                },
                onRunFinished = { childScopeId, reason, preservePendingHandoff ->
                    localTools.endWebMountRun(
                        runId = childScopeId,
                        conversationId = conversationId.toString(),
                        reason = reason,
                        preservePendingHandoff = preservePendingHandoff,
                    )
                },
                // P4-02: thread_graph_v2 gate — off keeps the legacy tool set
                // (no subagent_followup / send_message / interrupt).
                threadGraphEnabled = threadGraphEnabled,
                displayLocalizer = AppSubAgentDisplayLocalizer(context),
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
                openAICodexAuthStore = openAICodexAuthStore,
                grokAuthStore = grokAuthStore,
                antigravityAuthStore = antigravityAuthStore,
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
            semanticSearch = jevToolSemanticSearch?.forRun(runId),
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
                        sourceConversationId = it.sourceConversationId,
                        sourceMessageIds = it.sourceMessageIds,
                        supersedesIds = it.supersedesIds,
                        createdAt = it.createdAt,
                        updatedAt = it.updatedAt,
                        lastUsedAt = it.lastUsedAt,
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
            locale = context.appLocale(),
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
    suspend fun deleteConversation(conversation: Conversation, deferCleanup: Boolean = false) = withContext(NonCancellable) {
        val conversationId = conversation.id
        val expectedRestoreEpoch = captureRestoreEpoch()
        deletedConversationIds.add(conversationId)
        expectedRestoreEpoch?.let { deletedConversationRestoreEpochs[conversationId] = it }
        try {
            stopRunForDeletion(conversationId)
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
            withRestoreEpoch(expectedRestoreEpoch) {
                conversationRepo.deleteConversation(conversation, deferCleanup = deferCleanup)
            }
            if (!deferCleanup) {
                deletedConversationRestoreEpochs.remove(conversationId)
            }
        } catch (t: Throwable) {
            // A repository cleanup can fail after its durable delete. Keep the
            // tombstone in that case so an older checkpoint cannot revive it.
            if (conversationRepo.existsConversationById(conversationId)) {
                deletedConversationIds.remove(conversationId)
                deletedConversationRestoreEpochs.remove(conversationId)
            }
            throw t
        }
    }

    private suspend fun stopRunForDeletion(conversationId: Uuid) {
        val kernelRun = activeKernelRuns.value[conversationId]
        val persistedRun = runTerminalStore?.activeForConversation(conversationId.toString())
        stopGeneration(conversationId, kernelRun?.value ?: persistedRun?.runId)
        if (kernelRun != null) {
            agentRunner?.observe(kernelRun)?.first { it.status.isTerminal || it.status.isPause }
        }
        val remaining = persistedRun?.let { runTerminalStore?.get(it.runId) }
            ?.takeIf { !it.state.isTerminal && it.conversationId == conversationId.toString() } ?: return
        if (responsesResumeStore?.load(remaining.runId) != null) {
            // An unconfirmed remote cancel still needs recovery to settle its
            // real outcome, even though the local conversation is being deleted.
            runTerminalStore?.pause(remaining.runId, RunTerminalState.WAITING_EXTERNAL, PauseReason.USER_STOP)
            agentEventStore?.transitionRun(
                app.amber.core.agent.runtime.AgentRunId(remaining.runId),
                app.amber.core.agent.runtime.RunStatus.PAUSE_STATES,
                app.amber.core.agent.runtime.RunStatus.RUNNING,
                reason = "conversation_deleted_server_cancel_pending",
            )
            agentEventStore?.transitionRun(
                app.amber.core.agent.runtime.AgentRunId(remaining.runId),
                app.amber.core.agent.runtime.RunStatus.LIVE_STATES,
                app.amber.core.agent.runtime.RunStatus.WAITING_EXTERNAL,
                reason = "conversation_deleted_server_cancel_pending",
            )
        } else {
            runRecovery?.reconcileStartedEffects(remaining.runId)
            runTerminalStore?.finish(remaining.runId, RunTerminalState.CANCELLED, PauseReason.USER_STOP)
            agentEventStore?.transitionRun(
                app.amber.core.agent.runtime.AgentRunId(remaining.runId),
                app.amber.core.agent.runtime.RunStatus.LIVE_STATES,
                app.amber.core.agent.runtime.RunStatus.CANCELLED,
                reason = "conversation_deleted",
            )
        }
    }

    /** 删除被撤销（如 History 的 Undo）后解除 tombstone，恢复该会话的持久化通道。 */
    fun markConversationRestored(conversationId: Uuid) {
        deletedConversationIds.remove(conversationId)
        deletedConversationRestoreEpochs.remove(conversationId)
    }

    /** Complete a deferred History deletion with the epoch captured at delete time. */
    suspend fun purgeDeletedConversation(conversation: Conversation) {
        val invocationEpoch = captureRestoreEpoch()
        // A successful/partial restore clears the tombstone. A late snackbar
        // callback must therefore be a no-op rather than treating its missing
        // epoch as a new user write and deleting restored attachments.
        if (conversation.id !in deletedConversationIds) return
        val expectedRestoreEpoch = deletedConversationRestoreEpochs[conversation.id] ?: invocationEpoch
        withRestoreEpoch(expectedRestoreEpoch) {
            conversationRepo.cleanupDeletedConversation(
                conversation = conversation,
                expectedRestoreEpoch = expectedRestoreEpoch,
            )
        }
        if (expectedRestoreEpoch == null) {
            deletedConversationRestoreEpochs.remove(conversation.id)
        } else {
            deletedConversationRestoreEpochs.remove(conversation.id, expectedRestoreEpoch)
        }
    }

    /**
     * Clear every Amber conversation through the same tombstone and cancellation path
     * as single-item deletion so an active checkpoint cannot recreate deleted history.
     */
    suspend fun deleteAllConversations() = withContext(NonCancellable) {
        val expectedRestoreEpoch = captureRestoreEpoch()
        val conversations = conversationRepo.getConversations().first()
        val tombstoned = mutableListOf<Uuid>()
        try {
            conversations.forEach { conversation ->
                deletedConversationIds.add(conversation.id)
                expectedRestoreEpoch?.let { deletedConversationRestoreEpochs[conversation.id] = it }
                tombstoned.add(conversation.id)
                stopRunForDeletion(conversation.id)
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
            withRestoreEpoch(expectedRestoreEpoch) {
                conversationRepo.deleteAllConversations()
            }
            tombstoned.forEach(deletedConversationRestoreEpochs::remove)
        } catch (t: Throwable) {
            tombstoned.forEach { id ->
                if (conversationRepo.existsConversationById(id)) {
                    deletedConversationIds.remove(id)
                    deletedConversationRestoreEpochs.remove(id)
                }
            }
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
        val activeRun = activeKernelRuns.value[conversationId]
        if (runId != null && activeRun != null && activeRun.value != runId) return
        val sessionJob = sessions[conversationId]?.getJob()
        // A WAITING_USER pause intentionally releases both the session job
        // and the active-kernel map. For a UI Stop only, recover that one
        // same-conversation durable owner; notification Stops retain their
        // explicit runId ownership contract.
        val pausedFallbackRunId = if (runId == null && activeRun == null && sessionJob?.isActive != true) {
            runTerminalStore?.activeForConversation(conversationId.toString())
                ?.takeIf {
                    it.conversationId == conversationId.toString() &&
                        it.state == RunTerminalState.WAITING_USER &&
                        it.finishedAtMs == null
                }
                ?.runId
        } else {
            null
        }
        val targetRunId = runId ?: activeRun?.value ?: pausedFallbackRunId
        // P6-01: when the run has a stored server-side response, cancel it
        // server-side FIRST and await a decidable outcome — before cancelling
        // the local job, so onCompletion sees the decision. A cancel that
        // cannot be confirmed keeps the run in WAITING_EXTERNAL (never
        // pretend cancelled); recovery settles it later.
        var serverCancelUnconfirmed = false
        val stopCancel = storedResponseStopCancel
        val durableStopEnabled = targetRunId != null && durableRuntimeForStop()
        if (durableStopEnabled) {
            val activeRun = runTerminalStore?.activeForConversation(conversationId.toString())
            val requestedRunId = targetRunId!!
            val terminalOwnsRun = activeRun != null &&
                !activeRun.state.isTerminal &&
                activeRun.conversationId == conversationId.toString() &&
                activeRun.runId == requestedRunId
            val terminalOwnsAnotherRun = activeRun != null &&
                activeRun.runId != requestedRunId
            val kernelOwnsRun = activeKernelRuns.value[conversationId]?.value == requestedRunId
            if (terminalOwnsAnotherRun || (!terminalOwnsRun && !kernelOwnsRun)) {
                Log.w(
                    TAG,
                    "stopGeneration: refusing server cancel for unowned run " +
                        "conversation=$conversationId runId=$targetRunId",
                )
                return
            }
        }
        if (
            durableStopEnabled &&
            storedResponseToggleOnForRun(targetRunId!!) &&
            stopCancel != null
        ) {
            runCatching { stopCancel.cancelStored(targetRunId!!) }
                .onSuccess { decidable ->
                    if (!decidable) serverCancelUnconfirmed = true
                }
                .onFailure { error ->
                    Log.w(TAG, "stopGeneration: server cancel failed for run $targetRunId", error)
                    serverCancelUnconfirmed = true
                }
        }
        if (serverCancelUnconfirmed) {
            pendingServerCancelFailures.add(targetRunId!!)
        }
        val activeKernelRun = activeKernelRuns.value[conversationId]
        val cancelledByOwner = targetRunId != null &&
            runOwnershipRegistry?.cancel(
                runId = targetRunId,
                conversationId = conversationId.toString(),
            ) == true
        // UI Stop remains conversation-scoped even when it resolved the
        // persisted pause above; do not lose the session-job cancellation.
        val cancelledBySession = if (runId == null && sessionJob != null) {
            sessionJob.cancel()
            runCatching { sessionJob.join() }
            true
        } else {
            false
        }
        // Kernel-dispatched runs are owned by the AgentRunner, not the
        // session job or the ownership registry — cancel through the runner;
        // its CancellationException path settles the durable records.
        val kernelRunId = activeKernelRun
            ?.takeIf { targetRunId == null || it.value == targetRunId }
            ?: targetRunId
                ?.takeIf { candidate ->
                    runTerminalStore?.activeForConversation(conversationId.toString())?.let { terminal ->
                        terminal.runId == candidate &&
                            terminal.conversationId == conversationId.toString() &&
                            !terminal.state.isTerminal
                    } == true
                }
                ?.let { app.amber.core.agent.runtime.AgentRunId(it) }
        val cancelledKernelRun = kernelRunId?.let { candidate ->
            // AgentRunner preserves a cancel intent if its launch gate has
            // not registered the job yet, which closes Stop vs auto-resume.
            agentRunner?.cancel(candidate)
            true
        } ?: false
        // WAITING_USER has no active generation Job by design: onCompletion
        // releases the in-memory owner while the persisted terminal keeps the
        // approval resumable. Notification Stop therefore falls back to the
        // same run-scoped durable owner, with conversation + state checked
        // atomically so a stale or cross-conversation runId cannot stop work.
        val cancelledPersistedWaitingRun = targetRunId != null &&
            !cancelledByOwner &&
            runTerminalStore?.cancelWaitingUser(
                runId = targetRunId,
                conversationId = conversationId.toString(),
            ) == true
        val cancelled = cancelledByOwner || cancelledBySession || cancelledPersistedWaitingRun || cancelledKernelRun
        if (!cancelled) {
            if (serverCancelUnconfirmed) {
                // Nothing local to cancel — the flag has no consumer; drop it.
                targetRunId?.let(pendingServerCancelFailures::remove)
            }
            Log.i(TAG, "stopGeneration: nothing to cancel conversation=$conversationId runId=$targetRunId")
            return
        }
        if (cancelledPersistedWaitingRun) {
            // There is no flow completion left to stop the foreground
            // keep-alive after a persisted WAITING_USER pause is cancelled.
            pausedForUserConversations.update { it - conversationId }
            // 暂停态停止没有 dispatch 终态可等：直接写终态，任务气泡据此立即收起而非误报完成。
            _lastRunOutcomes.update { it + (conversationId to app.amber.core.agent.runtime.RunStatus.CANCELLED) }
            stopGenerationKeepAlive(conversationId)
            // No flow completion will settle the event-store row either —
            // move it to CANCELLED here or it stays a live WAITING_USER row
            // forever (replayUnfinished deliberately skips pause states).
            runCatching {
                agentEventStore?.transitionRun(
                    app.amber.core.agent.runtime.AgentRunId(targetRunId!!),
                    app.amber.core.agent.runtime.RunStatus.PAUSE_STATES,
                    app.amber.core.agent.runtime.RunStatus.CANCELLED,
                    reason = "user_stop",
                )
            }
            // A composite tool can park with its outer effect still STARTED
            // (nested approval checkpoint). Cold-start recovery skips terminal
            // rows, so classify the effect here or a non-idempotent tool's
            // unknown outcome is never surfaced (Step 3-5).
            runCatching { runRecovery?.reconcileStartedEffects(targetRunId!!) }
            runCatching { refreshOutcomeUnknown() }
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
        launchViaKernel(conversationId)
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
                                            put(
                                                "preview",
                                                message.previewText(copy = PendingUserMessageDisplayCopy.from(context)),
                                            )
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

/** Exact durable owner accepted by settings-driven approval recovery. */
internal fun isExpectedWaitingUserRun(
    terminal: RunTerminal?,
    conversationId: Uuid,
    expectedRunId: app.amber.core.agent.runtime.AgentRunId,
): Boolean =
    terminal?.runId == expectedRunId.value &&
        terminal.conversationId == conversationId.toString() &&
        terminal.state == RunTerminalState.WAITING_USER &&
        terminal.finishedAtMs == null

/** Drop only genuinely invalid unresolved tool messages before a new turn. */
internal fun sanitizeInvalidMessagesForGeneration(conversation: Conversation): Conversation {
    val validNodes = conversation.messageNodes.mapNotNull { node ->
        val currentTools = node.currentMessage.getTools()
        val unresolved = currentTools.filterNot { it.isExecuted }
        val candidate = if (unresolved.isEmpty() || unresolved.any {
                // A Pending tool is a valid, durable approval checkpoint.
                // DefaultRunKernel re-evaluates it against the current policy
                // before execution; dropping it would make approval unreachable.
                it.isPending || it.approvalState.canResumeToolExecution()
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
