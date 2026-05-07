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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
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
import me.rerere.rikkahub.data.agent.tools.ToolRegistry
import me.rerere.rikkahub.data.agent.subagent.SubAgentManager
import me.rerere.rikkahub.data.agent.workspace.WorkspaceManager
import me.rerere.rikkahub.data.automation.ScreenCaptureManager
import me.rerere.rikkahub.data.context.ConversationContextEngine
import me.rerere.rikkahub.data.datastore.toCompactPolicy
import me.rerere.rikkahub.data.datastore.MAX_AGENT_TOOL_LOOP_STEPS
import me.rerere.rikkahub.data.datastore.MIN_AGENT_TOOL_LOOP_STEPS
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.sendNotification
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"
private const val GENERATION_CHECKPOINT_INTERVAL_MS = 10_000L

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
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
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
) {
    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)
    private val trustedRunToolNames = ConcurrentHashMap<Uuid, Set<String>>()
    private val generationCheckpointAt = ConcurrentHashMap<Uuid, Long>()

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(error: Throwable, conversationId: Uuid? = null, title: String? = null) {
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
    }

    fun cleanup() = runCatching {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
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
                initialPendingMessages = loadPendingUserMessages(id),
                scope = appScope,
                onIdle = { removeSession(it) },
                onPendingMessagesChanged = { sessionId, messages ->
                    persistPendingUserMessages(sessionId, messages)
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
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun pendingUserMessageFile(conversationId: Uuid): File {
        return File(context.filesDir, "amberagent/message-queue").resolve("$conversationId.json")
    }

    private fun loadPendingUserMessages(conversationId: Uuid): List<PendingUserMessage> {
        val file = pendingUserMessageFile(conversationId)
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<PendingUserMessage>>(file.readText())
        }.onFailure {
            Log.w(TAG, "Failed to load pending user messages for $conversationId", it)
        }.getOrDefault(emptyList())
    }

    private fun persistPendingUserMessages(conversationId: Uuid, messages: List<PendingUserMessage>) {
        runCatching {
            val file = pendingUserMessageFile(conversationId)
            if (messages.isEmpty()) {
                file.delete()
            } else {
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString(messages))
            }
        }.onFailure {
            Log.w(TAG, "Failed to persist pending user messages for $conversationId", it)
        }
    }

    private fun pendingUserMessageAuditFile(conversationId: Uuid): File {
        return File(context.filesDir, "amberagent/message-queue").resolve("$conversationId.events.jsonl")
    }

    private fun recordPendingQueueEvent(
        conversationId: Uuid,
        event: String,
        messageId: String? = null,
        count: Int? = null,
        detail: String? = null,
    ) {
        runCatching {
            val file = pendingUserMessageAuditFile(conversationId)
            file.parentFile?.mkdirs()
            file.appendText(
                buildJsonObject {
                    put("created_at_ms", System.currentTimeMillis())
                    put("conversation_id", conversationId.toString())
                    put("event", event)
                    messageId?.let { put("message_id", it) }
                    count?.let { put("count", it) }
                    detail?.let { put("detail", it) }
                }.toString() + "\n"
            )
        }.onFailure {
            Log.w(TAG, "Failed to write pending message audit for $conversationId", it)
        }
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

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
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

    fun cancelPendingUserMessage(conversationId: Uuid, messageId: String) {
        if (getOrCreateSession(conversationId).cancelPendingUserMessage(messageId)) {
            recordPendingQueueEvent(conversationId, event = "cancel", messageId = messageId)
        }
    }

    fun clearPendingUserMessages(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        val count = session.pendingUserMessages.value.size
        if (count > 0) {
            session.clearPendingUserMessages()
            recordPendingQueueEvent(conversationId, event = "clear", count = count)
        }
    }

    fun movePendingUserMessage(conversationId: Uuid, messageId: String, offset: Int) {
        if (getOrCreateSession(conversationId).movePendingUserMessage(messageId, offset)) {
            recordPendingQueueEvent(
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
        getOrCreateSession(conversationId) // 确保 session 存在
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversation(conversationId, conversation)
            settingsStore.updateAssistant(conversation.assistantId)
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    // ---- 发送消息 ----

    fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        queueMode: PendingUserMessageMode = PendingUserMessageMode.FOLLOWUP,
    ) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        val processedContent = preprocessUserInputParts(content)
        val shouldQueue = session.isGenerating || session.state.value.hasPendingOrUnexecutedTools()
        val pendingMessage = PendingUserMessage(
            id = Uuid.random().toString(),
            parts = processedContent,
            answer = answer,
            mode = if (session.isGenerating) queueMode else PendingUserMessageMode.FOLLOWUP,
        )

        if (shouldQueue) {
            val accepted = session.enqueuePendingUserMessage(pendingMessage)
            if (!accepted) {
                addError(
                    IllegalStateException("消息队列已满，请先等待或取消一些排队消息。"),
                    conversationId = conversationId,
                    title = "消息未加入队列"
                )
            } else {
                recordPendingQueueEvent(
                    conversationId = conversationId,
                    event = "enqueue",
                    messageId = pendingMessage.id,
                    detail = pendingMessage.mode.name.lowercase(),
                )
            }
            return
        }

        launchPendingMessageLoop(conversationId, pendingMessage)
    }

    private fun launchPendingMessageLoop(
        conversationId: Uuid,
        firstMessage: PendingUserMessage? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        if (session.isGenerating || session.state.value.hasPendingOrUnexecutedTools()) {
            firstMessage?.let { message ->
                if (!session.enqueuePendingUserMessage(message)) {
                    addError(
                        IllegalStateException("消息队列已满，请先等待或取消一些排队消息。"),
                        conversationId = conversationId,
                        title = "消息未加入队列"
                    )
                } else {
                    recordPendingQueueEvent(
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
            var nextMessage = firstMessage ?: session.dequeueNextPendingUserMessage()
            while (nextMessage != null) {
                try {
                    val dispatchMessage = session.preparePendingMessageForDispatch(nextMessage)
                    recordPendingQueueEvent(
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
                nextMessage = session.dequeueNextPendingUserMessage()
            }
        }
        session.setJob(job)
    }

    private suspend fun appendUserMessage(
        conversationId: Uuid,
        message: PendingUserMessage,
    ) {
        val session = getOrCreateSession(conversationId)
        val currentConversation = session.state.value
        val newConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes + UIMessage(
                role = MessageRole.USER,
                parts = message.parts,
            ).toMessageNode(),
        )
        saveConversation(conversationId, newConversation)
    }

    private suspend fun drainPendingUserMessagesInline(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        var nextMessage = session.dequeueNextPendingUserMessage()
        while (nextMessage != null) {
            val dispatchMessage = session.preparePendingMessageForDispatch(nextMessage)
            recordPendingQueueEvent(
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
            nextMessage = session.dequeueNextPendingUserMessage()
        }
    }

    private fun ConversationSession.preparePendingMessageForDispatch(
        message: PendingUserMessage,
    ): PendingUserMessage {
        return when {
            message.isCollectable -> buildCollectedPendingUserMessage(
                listOf(message) + dequeueLeadingCollectableMessages()
            )

            message.mode == PendingUserMessageMode.STEER -> message.asFollowup()
            else -> message
        }
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>): List<UIMessagePart> {
        val assistant = settingsStore.settingsFlow.value.getCurrentAssistant()
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
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
                val conversation = session.state.value

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
                val conversation = session.state.value
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
                val conversation = session.state.value
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
            val initialConversation = getConversationFlow(conversationId).value

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

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
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

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
                    val consumed = getOrCreateSession(conversationId)
                        .dequeueSteerPendingUserMessages()
                    if (consumed.isNotEmpty()) {
                        recordPendingQueueEvent(
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
                stopGenerationKeepAlive(conversationId)

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
                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateCurrentMessages(chunk.messages)
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
            stopGenerationKeepAlive(conversationId)
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
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            saveConversation(conversationId, finalConversation)
            cleanupRunResourcesIfDone(conversationId, finalConversation)

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
        }
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

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
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

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText() })
                    ),
                ),
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.OFF,
                ),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.choices[0].message?.toText()?.trim() ?: "")
                )
            }
        }.onFailure {
            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generate_title))
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.suggestionModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            sessions[conversationId]?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText() }),
                    )
                ),
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.OFF,
                ),
            )
            val suggestions =
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: sessions[conversationId]?.state?.value
                ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        if (keepRecentMessages > 0 && conversation.currentMessages.size <= keepRecentMessages) {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        }
        val result = contextEngine.compactConversation(
            conversation = conversation,
            settings = settings,
            policy = settings.agentRuntime.contextCompaction.toCompactPolicy().copy(
                enabled = true,
                keepRecentTurns = (keepRecentMessages / 2).coerceAtLeast(1),
                maxSummaryTokens = targetTokens,
            ),
            model = settings.findModelById(settings.compressModelId) ?: settings.getCurrentChatModel(),
            reason = "manual_compact_dialog",
            additionalPrompt = additionalPrompt,
            force = true,
        )
        if (result.status != "completed") {
            throw IllegalStateException(result.error ?: result.status)
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
            activity = activityStore.sandboxActivity.value,
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
        runCatching {
            val exists = conversationRepo.existsConversationById(conversation.id)
            if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) return@runCatching
            if (exists) {
                conversationRepo.updateConversation(conversation)
            } else {
                conversationRepo.insertConversation(conversation)
            }
        }.onFailure { error ->
            Log.w(TAG, "checkpointConversation failed for $conversationId", error)
        }
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

    private fun updateConversation(
        conversationId: Uuid,
        conversation: Conversation,
        checkDeletedFiles: Boolean = true,
    ) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        if (checkDeletedFiles) {
            checkFilesDelete(conversation, session.state.value)
        }
        session.state.value = conversation
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

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val updatedConversation = conversation.copy()
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return
        val processedParts = preprocessUserInputParts(parts)

        val currentConversation = getConversationFlow(conversationId).value
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
        val currentConversation = getConversationFlow(conversationId).value
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

        contextEngine.invalidateCompacts(conversationId, "conversation_forked")
        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
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
        val currentConversation = getConversationFlow(conversationId).value
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

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    internal fun createDebugRunTools(settings: Settings): List<Tool> = createRunTools(settings, null)

    private fun createRunTools(settings: Settings, conversationId: Uuid?): List<Tool> {
        val rawTools = buildList {
            if (settings.enableWebSearch) {
                addAll(createSearchTools(settings))
            }
            val assistant = settings.getCurrentAssistant()
            addAll(localTools.getTools(assistant.localTools))
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
        val baseRegistry = ToolRegistry.from(rawTools)
        val baseTools = baseRegistry.tools() +
            localTools.createToolsListTool(baseRegistry) +
            localTools.createToolPolicyExplainTool(baseRegistry)
        val subAgentRawTools = if (conversationId != null && settings.agentRuntime.subAgent.enabled) {
            rawTools + SubAgentTools(
                subAgentManager = subAgentManager,
                parentConversationId = conversationId,
                parentToolsProvider = { baseTools },
            ).tools()
        } else {
            rawTools
        }
        val finalRawTools = if (settings.agentRuntime.modelCouncil.enabled) {
            subAgentRawTools + ModelCouncilTools(
                manager = modelCouncilManager,
                workspaceManager = workspaceManager,
            ).tools()
        } else {
            subAgentRawTools
        }
        val registry = ToolRegistry.from(finalRawTools)
        return registry.tools() +
            localTools.createToolsListTool(registry) +
            localTools.createToolPolicyExplainTool(registry)
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
            onCreation = { scope, content ->
                memoryRepository.addMemory(memoryBucket(scope), content)
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
        if (sessions[conversationId]?.convertPendingSteerMessagesToFollowup() == true) {
            recordPendingQueueEvent(conversationId, event = "steer_downgrade")
        }

        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: run {
            launchPendingMessageLoop(conversationId)
            return
        }
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
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
