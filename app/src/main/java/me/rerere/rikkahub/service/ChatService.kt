package me.rerere.rikkahub.service

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
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.ai.tools.createMcpManagementTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.MiniAppOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.MiniAppPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.SearchImageInjectorTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.agent.AgentLiveStatusNotifier
import me.rerere.rikkahub.data.agent.AgentToolActivityStore
import me.rerere.rikkahub.data.agent.modelcouncil.ModelCouncilManager
import me.rerere.rikkahub.data.agent.history.SessionAccessGrantStore
import me.rerere.rikkahub.data.agent.task.AgentTaskScheduler
import me.rerere.rikkahub.data.agent.task.AgentTaskRetryPolicy
import me.rerere.rikkahub.data.agent.task.AgentTaskSnapshot
import me.rerere.rikkahub.data.agent.task.AgentTaskStatus
import me.rerere.rikkahub.data.agent.terminal.TerminalRuntime
import me.rerere.rikkahub.data.agent.tools.AgentTaskTools
import me.rerere.rikkahub.data.agent.tools.ConversationContextTools
import me.rerere.rikkahub.data.agent.tools.ConversationHistoryTools
import me.rerere.rikkahub.data.agent.tools.ModelCouncilTools
import me.rerere.rikkahub.data.agent.tools.SubAgentTools
import me.rerere.rikkahub.data.agent.tools.ToolProfileFilter
import me.rerere.rikkahub.data.agent.tools.ToolRegistry
import me.rerere.rikkahub.data.agent.tools.createToolSearchTool
import me.rerere.rikkahub.data.agent.subagent.SubAgentManager
import me.rerere.rikkahub.data.agent.workspace.WorkspaceManager
import me.rerere.rikkahub.data.automation.ScreenCaptureManager
import me.rerere.rikkahub.data.context.ActiveCompactBoundary
import me.rerere.rikkahub.data.context.CompactLifecycleState
import me.rerere.rikkahub.data.context.ConversationContextEngine
import me.rerere.rikkahub.data.datastore.toCompactPolicy
import me.rerere.rikkahub.data.datastore.MAX_AGENT_TOOL_LOOP_STEPS
import me.rerere.rikkahub.data.datastore.MIN_AGENT_TOOL_LOOP_STEPS
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.resolveTaskChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.memory.extraction.MemoryExtractor
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.ChatSendTransitionTracker
import me.rerere.rikkahub.utils.sendNotification
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "ChatService"
private const val GENERATION_CHECKPOINT_INTERVAL_MS = 10_000L
private const val INITIAL_TIMELINE_NODE_COUNT = 80
private const val TIMELINE_PREFETCH_BATCH_SIZE = 40
private const val ASK_USER_TOOL_NAME = "ask_user"

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
        SearchImageInjectorTransformer,
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
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val activityStore: AgentToolActivityStore,
    private val liveStatusNotifier: AgentLiveStatusNotifier,
    private val terminalRuntime: TerminalRuntime,
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
) : ConversationAccess {
    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)
    private val trustedRunToolNames = ConcurrentHashMap<Uuid, Set<String>>()
    private val generationCheckpointAt = ConcurrentHashMap<Uuid, Long>()
    private val timelineLoadMutexes = ConcurrentHashMap<Uuid, Mutex>()
    private val pendingMessageStoreOps = Channel<PendingMessageStoreOp>(Channel.UNLIMITED)
    private val pendingMessagePersistRevisions = ConcurrentHashMap<Uuid, AtomicLong>()
    private val pendingMessagePersistLocks = ConcurrentHashMap<Uuid, Mutex>()

    private val translationHandler = ChatTranslationHandler(
        context = context,
        appScope = appScope,
        settingsStore = settingsStore,
        generationHandler = generationHandler,
        conversationAccess = this,
    )

    private val aiAuxiliaryGenerator = AiAuxiliaryGenerator(
        context = context,
        settingsStore = settingsStore,
        providerManager = providerManager,
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
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

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

    init {
        // 添加生命周期观察者
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
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
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        pendingMessageStoreOps.close()
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
        pendingMessagePersistRevisions.clear()
        pendingMessagePersistLocks.clear()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                initialPendingMessages = pendingMessageStore.load(id),
                scope = appScope,
                onIdle = { removeSession(it) },
                onPendingMessagesChanged = { sessionId, messages ->
                    persistPendingMessagesAsync(sessionId, messages)
                }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            timelineLoadMutexes.remove(conversationId)
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
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

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
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
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
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

    fun cancelPendingUserMessage(conversationId: Uuid, messageId: String) {
        val session = getOrCreateSession(conversationId)
        if (session.cancelPendingUserMessage(messageId)) {
            persistCurrentPendingMessagesDurably(conversationId, session)
            recordPendingMessageEvent(conversationId, event = "cancel", messageId = messageId)
        }
    }

    fun clearPendingUserMessages(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        val count = session.pendingUserMessages.value.size
        if (count > 0) {
            session.clearPendingUserMessages()
            persistCurrentPendingMessagesDurably(conversationId, session)
            recordPendingMessageEvent(conversationId, event = "clear", count = count)
        }
    }

    fun movePendingUserMessage(conversationId: Uuid, messageId: String, offset: Int) {
        val session = getOrCreateSession(conversationId)
        if (session.movePendingUserMessage(messageId, offset)) {
            persistCurrentPendingMessagesDurably(conversationId, session)
            recordPendingMessageEvent(
                conversationId = conversationId,
                event = "move",
                messageId = messageId,
                detail = offset.toString(),
            )
        }
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId) // 确保 session 存在
        if (session.timelineLoadState.value.initialized) {
            launchPendingMessageLoopIfNeeded(conversationId, session)
            return
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
            settingsStore.updateAssistant(window.conversation.assistantId)
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlow.filterNot { it.init }.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
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

        launchPendingMessageLoop(conversationId, pendingMessage)
        return true
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
                            part.copy(approvalState = ToolApprovalState.Approved)
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

    private suspend fun appendUserMessage(
        conversationId: Uuid,
        message: PendingUserMessage,
    ) {
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
            }
            _generationDoneFlow.emit(conversationId)

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
        runBlocking(Dispatchers.IO) {
            pendingMessagePersistLock(conversationId).withLock {
                pendingMessagePersistRevision(conversationId).incrementAndGet()
                pendingMessageStore.persistBlocking(conversationId, messages)
            }
        }
    }

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

    private fun ConversationSession.dequeueNextPendingUserMessageDurably(
        conversationId: Uuid,
    ): PendingUserMessage? {
        val message = dequeueNextPendingUserMessage()
        if (message != null) {
            persistCurrentPendingMessagesDurably(conversationId, this)
        }
        return message
    }

    private fun ConversationSession.preparePendingMessageForDispatch(
        conversationId: Uuid,
        message: PendingUserMessage,
    ): PendingUserMessage {
        return when {
            message.isCollectable -> {
                val collected = dequeueLeadingCollectableMessages()
                if (collected.isNotEmpty()) {
                    persistCurrentPendingMessagesDurably(conversationId, this)
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
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val conversation = ensureFullConversationLoaded(conversationId)

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    contextEngine.invalidateCompacts(conversationId, "message_regenerated")
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        contextEngine.invalidateCompacts(conversationId, "message_regenerated")
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
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

        val job = appScope.launch {
            try {
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
                val updatedNodes = conversation.messageNodes.map { node ->
                    node.copy(
                        messages = node.messages.map { msg ->
                            msg.copy(
                                parts = msg.parts.map { part ->
                                    when {
                                        part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                            part.copy(approvalState = newApprovalState)
                                        }

                                        else -> part
                                    }
                                }
                            )
                        }
                    )
                }
                val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                saveConversation(conversationId, updatedConversation)

                // Check if there are still pending tools
                val hasPendingTools = updatedNodes.any { node ->
                    node.currentMessage.parts.any { part ->
                        part is UIMessagePart.Tool && part.isPending
                    }
                }

                // Only continue generation when all pending tools are handled
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

    fun approvePendingAutoApprovableTools(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        if (session.isGenerating) return

        val job = appScope.launch {
            try {
                val conversation = ensureFullConversationLoaded(conversationId)
                var changed = false

                val updatedNodes = conversation.messageNodes.map { node ->
                    node.copy(
                        messages = node.messages.map { msg ->
                            msg.copy(
                                parts = msg.parts.map { part ->
                                    when {
                                        part is UIMessagePart.Tool &&
                                            part.isPending &&
                                            part.toolName != "ask_user" -> {
                                            changed = true
                                            part.copy(approvalState = ToolApprovalState.Approved)
                                        }

                                        else -> part
                                    }
                                }
                            )
                        }
                    )
                }

                if (!changed) return@launch

                saveConversation(
                    conversationId = conversationId,
                    conversation = conversation.copy(messageNodes = updatedNodes)
                )

                val hasPendingTools = updatedNodes.any { node ->
                    node.currentMessage.parts.any { part ->
                        part is UIMessagePart.Tool && part.isPending
                    }
                }

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

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.getCurrentChatModel() ?: return

        val assistant = settings.getCurrentAssistant()
        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }
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
            updateAgentLiveStatus(
                conversationId = conversationId,
                messages = conversation.currentMessages,
                senderName = senderName,
                settings = settings,
            )
            startGenerationKeepAlive(conversationId, senderName, settings)
            val generationTaskId = startGenerationTask(
                conversationId = conversationId,
                senderName = senderName,
                modelName = model.displayName,
                settings = settings,
            )
            val runTools = createRunTools(settings, conversationId)
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = conversation.currentMessages.let {
                    if (messageRange != null) {
                        it.subList(messageRange.start, messageRange.endInclusive + 1)
                    } else {
                        it
                    }
                },
                assistant = settings.getCurrentAssistant(),
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
            ).onCompletion { cause ->
                cancelLiveUpdateNotification(conversationId)
                if (!hasQueuedContinuation(conversationId)) {
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

                // Show notification if app is not in foreground
                if (
                    cause == null &&
                    !isForeground.value &&
                    settings.displaySetting.enableNotificationOnMessageGeneration
                ) {
                    sendGenerationDoneNotification(conversationId, senderName)
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

                        updateAgentLiveStatus(
                            conversationId = conversationId,
                            messages = chunk.messages,
                            senderName = senderName,
                            settings = settings,
                        )
                    }
                }
            }
        }.onFailure {
            trustedRunToolNames.remove(conversationId)
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
                    launchIntent = getPendingIntent(context, conversationId),
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
                is me.rerere.rikkahub.data.context.ContextCompactionFailedException -> {
                    val hint = context.getString(
                        R.string.error_auto_compact_failed_hint,
                        it.phase,
                        it.compactionReason,
                    )
                    context.getString(R.string.error_title_compress_conversation) to RuntimeException(hint, it)
                }
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

    private fun hasQueuedContinuation(conversationId: Uuid): Boolean {
        return sessions[conversationId]?.pendingUserMessages?.value?.isNotEmpty() == true
    }

    private fun cleanupRunResourcesIfDone(conversationId: Uuid, conversation: Conversation) {
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
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        return conversation.copy(messageNodes = messagesNodes)
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
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

    private fun sendGenerationDoneNotification(conversationId: Uuid, senderName: String) {
        // 先取消 Live Update 通知
        cancelLiveUpdateNotification(conversationId)

        val conversation = getConversationFlow(conversationId).value
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 1
        ) {
            title = senderName
            content = conversation.currentMessages.lastOrNull()?.toText()?.take(50)?.trim() ?: ""
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun updateAgentLiveStatus(
        conversationId: Uuid,
        messages: List<UIMessage>,
        senderName: String,
        settings: me.rerere.rikkahub.data.datastore.Settings,
    ) {
        if (!settings.agentRuntime.enableLiveStatusNotification) return
        liveStatusNotifier.notifyRunning(
            conversationId = conversationId,
            senderName = senderName,
            messages = messages,
            activity = activityStore.sandboxActivity.value
                ?.takeIf { it.conversationId == conversationId.toString() },
            hideSensitive = settings.agentRuntime.hideSensitiveLiveStatus,
            launchIntent = getPendingIntent(context, conversationId),
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

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
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
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    override suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
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

    // ---- 翻译消息（delegated to ChatTranslationHandler）----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale,
    ) = translationHandler.translateMessage(conversationId, message, targetLanguage)

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return
        val processedParts = userInputPreprocessor.process(parts)

        val currentConversation = ensureFullConversationLoaded(conversationId)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        contextEngine.invalidateCompacts(conversationId, "message_edited")
        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = ensureFullConversationLoaded(conversationId)
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversation = Conversation(
            id = Uuid.random(),
            assistantId = currentConversation.assistantId,
            messageNodes = copiedNodes,
        )

        saveConversation(forkConversation.id, forkConversation)
        contextEngine.copyValidCompactsToConversation(
            sourceConversationId = conversationId,
            targetConversation = forkConversation,
        )
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = ensureFullConversationLoaded(conversationId)
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        contextEngine.invalidateCompacts(conversationId, "message_branch_changed")
        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
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
                throw NotFoundException("Message not found")
            }
            return
        }

        contextEngine.invalidateCompacts(conversationId, "message_deleted")
        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) =
        translationHandler.clearTranslationField(conversationId, messageId)

    internal fun createDebugRunTools(settings: Settings): List<Tool> = createRunTools(settings, null)

    private fun createRunTools(settings: Settings, conversationId: Uuid?): List<Tool> {
        val rawTools = buildList {
            if (settings.enableWebSearch) {
                addAll(createSearchTools(settings))
            }
            val assistant = settings.getCurrentAssistant()
            addAll(localTools.getTools(assistant.localTools, conversationId))
            addAll(
                createSkillTools(
                    enabledSkills = assistant.enabledSkills,
                    allSkills = skillManager.listSkills(),
                    skillManager = skillManager,
                    settingsStore = settingsStore,
                    workspaceManager = workspaceManager,
                )
            )
            addAll(
                createMcpManagementTools(
                    settingsStore = settingsStore,
                    mcpManager = mcpManager,
                    skillManager = skillManager,
                )
            )
            mcpManager.getAllAvailableTools().forEach { tool ->
                add(
                    Tool(
                        name = "mcp__" + tool.name,
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = tool.needsApproval,
                        execute = { input ->
                            val toolCallId = activityStore.startTool(
                                toolName = "mcp__${tool.name}",
                                title = "调用 MCP 工具",
                                inputPreview = input.toString(),
                                runtime = "MCP",
                            )
                            try {
                                val result = mcpManager.callTool(tool.name, input.jsonObject)
                                activityStore.complete(toolCallId, result.toolOutputPreview())
                                result
                            } catch (error: Throwable) {
                                activityStore.fail(toolCallId, error)
                                throw error
                            }
                        },
                    )
                )
            }
            addAll(createMemoryTools(settings))
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
                        currentConversationProvider = { getConversationFlow(conversationId).value },
                        grantStore = sessionAccessGrantStore,
                    ).tools()
                )
                addAll(createConversationQueueTools(conversationId))
            }
            addAll(AgentTaskTools(agentTaskScheduler).tools())
        }
        val assistant = settings.getCurrentAssistant()
        val profileFilter = ToolProfileFilter.filter(rawTools, assistant.toolProfile)
        val profiledRawTools = profileFilter.tools
        val baseRegistry = ToolRegistry.from(profiledRawTools)
        val baseTools = baseRegistry.tools() +
            localTools.registryIntrospectionTools(baseRegistry)
        val subAgentRawTools = if (conversationId != null && settings.agentRuntime.subAgent.enabled) {
            profiledRawTools + SubAgentTools(
                subAgentManager = subAgentManager,
                parentConversationId = conversationId,
                parentToolsProvider = { baseTools },
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
        val finalRawTools = ToolProfileFilter.filter(augmentedRawTools, assistant.toolProfile).tools
        val registry = ToolRegistry.from(finalRawTools)
        val tools = registry.tools() +
            createToolSearchTool(registry, profile = assistant.toolProfile) +
            localTools.registryIntrospectionTools(registry)
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

    private fun createMemoryTools(settings: Settings): List<Tool> {
        if (
            !settings.agentRuntime.enableCoreMemory &&
            !settings.agentRuntime.enableShortTermMemory &&
            !settings.agentRuntime.enableLongTermMemory
        ) {
            return emptyList()
        }
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
                ).let {
                    me.rerere.rikkahub.data.model.AssistantMemory(
                        id = it.id,
                        content = it.content,
                        scope = it.scope,
                        kind = it.kind,
                        expiresAt = it.expiresAt,
                        confidence = it.confidence,
                        pinned = it.pinned,
                        archived = it.archived,
                    )
                }
            },
            onUpdate = { id, content ->
                memoryRepository.updateContent(id, content)
            },
            onDelete = { id ->
                memoryRepository.deleteMemory(id)
            }
        )
    }

    private fun memoryBucket(scope: String): String = when (scope) {
        "core" -> MemoryRepository.GLOBAL_MEMORY_ID
        "short_term" -> MemoryRepository.SHORT_TERM_MEMORY_ID
        "long_term" -> MemoryRepository.LONG_TERM_MEMORY_ID
        else -> MemoryRepository.LONG_TERM_MEMORY_ID
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val job = sessions[conversationId]?.getJob()
        if (job != null) {
            job.cancel()
            runCatching { job.join() }
        }
        terminalRuntime.cancelRunningJobs()
        cancelLiveUpdateNotification(conversationId)
        trustedRunToolNames.remove(conversationId)
        screenCaptureManager.releaseSession()
        val session = sessions[conversationId]
        if (session?.convertPendingSteerMessagesToFollowup() == true) {
            persistCurrentPendingMessagesDurably(conversationId, session)
            recordPendingMessageEvent(conversationId, event = "steer_downgrade")
        }

        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: run {
            launchPendingMessageLoop(conversationId)
            return
        }
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
        if (updatedMessage == lastMessage) {
            launchPendingMessageLoop(conversationId)
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
        launchPendingMessageLoop(conversationId)
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
