package app.amber.core.service

import app.amber.core.service.tools.RunToolCatalogFactory

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.add
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.canResumeToolExecution
import app.amber.agent.R
import app.amber.core.ai.mcp.McpManager
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
import app.amber.core.ai.transformers.ThinkTagTransformer
import app.amber.core.ai.transformers.TimeReminderTransformer
import app.amber.core.context.ActiveCompactBoundary
import app.amber.core.context.CompactLifecycleState
import app.amber.core.context.ConversationContextEngine
import app.amber.core.settings.toCompactPolicy
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.resolveTaskChatModel
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.model.toMessageNode
import app.amber.feature.runtime.OutcomeUnknownPrompt
import app.amber.feature.runtime.RunTerminal
import app.amber.feature.runtime.RunTerminalState
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

/** Shared completion/failure cleanup for a generation that may already be cancelled. */
internal suspend fun finalizeChatGeneration(block: suspend () -> Unit) =
    withContext(NonCancellable) { block() }

private const val ASK_USER_TOOL_NAME = "ask_user"



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

class ChatService(
    private val context: Application,
    private val runCoordinator: ChatRunCoordinator,
    private val settingsStore: SettingsAggregator,
    private val toolCatalogFactory: RunToolCatalogFactory,
    private val runPresenter: ChatRunPresenter,
    private val approvalActions: ChatApprovalActions,
    private val hooksFactory: ChatRunHooksFactory,
    private val aiAuxiliaryGenerator: AiAuxiliaryGenerator,
    val mcpManager: McpManager,
    private val conversationEditor: ConversationEditor,
    private val contextEngine: ConversationContextEngine,
    private val conversationStore: ConversationStore,
    private val pendingMessageQueue: PendingMessageQueue,
    private val errorStore: ChatErrorStore,
    // Full restore raises an epoch and serializes durable conversation writes;
    // nullable keeps legacy construction sites and isolated tests unchanged.
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
    val localAsyncTaskCoordinator: LocalAsyncTaskCoordinator? = null,
) : ConversationAccess {
    val errors: StateFlow<List<ChatError>> = errorStore.errors

    override fun addError(error: Throwable, conversationId: Uuid?, title: String?) =
        errorStore.addError(error, conversationId, title)

    fun dismissError(id: Uuid) = errorStore.dismissError(id)
    fun clearAllErrors() = errorStore.clearAllErrors()

    val generationDoneFlow: SharedFlow<Uuid> = runCoordinator.generationDoneFlow
    val lastRunOutcomes: StateFlow<Map<Uuid, app.amber.core.agent.runtime.RunStatus>> = runCoordinator.lastRunOutcomes
    val activeConversationIds: StateFlow<Set<Uuid>> = runCoordinator.activeConversationIds

    val outcomeUnknownFlow: StateFlow<Map<String, List<OutcomeUnknownPrompt>>> = approvalActions.outcomeUnknownFlow

    val isForeground: StateFlow<Boolean> = runPresenter.isForeground

    fun cleanup() = runCatching {
        runCoordinator.cleanup()
        runPresenter.cleanup()
        conversationStore.cleanup()
        pendingMessageQueue.cleanup()
    }

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession =
        conversationStore.session(conversationId)

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) =
        getOrCreateSession(conversationId).acquire()

    fun removeConversationReference(conversationId: Uuid) =
        conversationStore.removeConversationReference(conversationId)

    private suspend fun captureRestoreEpoch(): Long? {
        val gate = restoreWriteGate ?: return null
        return coroutineContext[SyncRestoreWriteEpoch]?.value ?: gate.currentEpoch()
    }

    // ---- 对话状态访问 ----

    override fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> =
        conversationStore.getConversationFlow(conversationId)

    override fun getConversationFlowOrNull(conversationId: Uuid): StateFlow<Conversation>? =
        conversationStore.getConversationFlowOrNull(conversationId)

    fun getTimelineLoadStateFlow(conversationId: Uuid): StateFlow<ConversationTimelineLoadState> =
        conversationStore.getTimelineLoadStateFlow(conversationId)

    fun getGenerationJobStateFlow(conversationId: Uuid): StateFlow<Job?> =
        conversationStore.getGenerationJobStateFlow(conversationId)

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> =
        conversationStore.getProcessingStatusFlow(conversationId)

    fun getPendingUserMessagesFlow(conversationId: Uuid): StateFlow<List<PendingUserMessage>> =
        conversationStore.getPendingUserMessagesFlow(conversationId)

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

    // UI mutations enqueue persistence without blocking; dispatch awaits durable dequeue.
    fun cancelPendingUserMessage(conversationId: Uuid, messageId: String) {
        getOrCreateSession(conversationId)
        pendingMessageQueue.cancelPendingUserMessage(conversationId, messageId)
    }

    fun clearPendingUserMessages(conversationId: Uuid) {
        getOrCreateSession(conversationId)
        pendingMessageQueue.clearPendingUserMessages(conversationId)
    }

    fun movePendingUserMessage(conversationId: Uuid, messageId: String, offset: Int) {
        getOrCreateSession(conversationId)
        pendingMessageQueue.movePendingUserMessage(conversationId, messageId, offset)
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> = conversationStore.getConversationJobs()

    suspend fun initializeConversation(conversationId: Uuid) = runCoordinator.initializeConversation(conversationId)

    private suspend fun ensureFullConversationLoaded(conversationId: Uuid): Conversation =
        conversationStore.ensureFullConversationLoaded(conversationId)

    suspend fun ensureConversationTimelineLoaded(conversationId: Uuid): Conversation =
        conversationStore.ensureConversationTimelineLoaded(conversationId)

    suspend fun loadOlderTimelinePage(conversationId: Uuid): Boolean =
        conversationStore.loadOlderTimelinePage(conversationId)

    internal suspend fun readOlderTimelinePageForPrewarm(conversationId: Uuid): List<MessageNode> =
        conversationStore.readOlderTimelinePageForPrewarm(conversationId)

    // ---- 发送消息 ----

    fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        queueMode: PendingUserMessageMode = PendingUserMessageMode.FOLLOWUP,
    ): Boolean {
        val localAsync = localAsyncTaskCoordinator
        if (localAsync != null) {
            return localAsync.submitMessage(conversationId, content, answer)
        }
        return runCoordinator.sendMessage(conversationId, content, answer, queueMode)
    }

    internal suspend fun startTaskMessage(conversationId: Uuid, content: List<UIMessagePart>): ChatTaskRun =
        runCoordinator.startTaskMessage(conversationId, content)

    fun getActiveKernelRunFlow(conversationId: Uuid, scope: CoroutineScope): StateFlow<app.amber.core.agent.runtime.AgentRunId?> =
        runCoordinator.getActiveKernelRunFlow(conversationId, scope)

    fun kernelRunner(): app.amber.core.agent.runtime.AgentRunner? = runCoordinator.kernelRunner()

    internal suspend fun consumeSteerMessagesForRun(conversationId: Uuid): List<UIMessage> =
        runCoordinator.consumeSteerMessagesForRun(conversationId)

    internal suspend fun chatRunHooks(conversationId: Uuid): app.amber.feature.chat.impl.ChatRunHooks =
        hooksFactory.create(conversationId, runCoordinator::stopGeneration)

    fun regenerateAtMessage(conversationId: Uuid, message: UIMessage, regenerateAssistantMsg: Boolean = true) =
        runCoordinator.regenerateAtMessage(conversationId, message, regenerateAssistantMsg)

    suspend fun retryFromNotification(conversationId: Uuid, runId: String?) =
        runCoordinator.retryFromNotification(conversationId, runId)

    fun handleToolApproval(conversationId: Uuid, toolCallId: String, approved: Boolean, reason: String = "", answer: String? = null) =
        runCoordinator.handleToolApproval(conversationId, toolCallId, approved, reason, answer)

    suspend fun handleNotificationApproval(
        conversationId: Uuid,
        runId: String?,
        toolCallId: String,
        approved: Boolean,
        reason: String,
        answer: String?,
        token: String,
    ): Boolean = runCoordinator.handleNotificationApproval(conversationId, runId, toolCallId, approved, reason, answer, token)

    suspend fun resumePendingToolsWithCurrentApprovalSettings(conversationId: Uuid) =
        runCoordinator.resumePendingToolsWithCurrentApprovalSettings(conversationId)

    fun approvePendingAutoApprovableTools(conversationId: Uuid) =
        runCoordinator.approvePendingAutoApprovableTools(conversationId)

    suspend fun refreshOutcomeUnknown() = approvalActions.refreshOutcomeUnknown()

    suspend fun reconcileOutcomeUnknown(conversationId: Uuid, effectId: String, retry: Boolean) =
        runCoordinator.reconcileOutcomeUnknown(conversationId, effectId, retry)

    // ---- 生成标题 / 建议（delegated to AiAuxiliaryGenerator）----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false,
    ) = aiAuxiliaryGenerator.generateTitle(conversationId, conversation, force)

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) =
        aiAuxiliaryGenerator.generateSuggestion(conversationId, conversation)

    // ---- 会话回顾 ----

    fun observeRecap(conversationId: Uuid): StateFlow<app.amber.core.recap.RecapState> =
        hooksFactory.observeRecap(conversationId)

    suspend fun loadRecap(conversationId: Uuid) = hooksFactory.loadRecap(conversationId)

    suspend fun generateRecap(conversationId: Uuid, force: Boolean) = hooksFactory.generateRecap(conversationId, force)

    /** Full current conversation without widening the chat page's paged window. */
    suspend fun loadFullConversation(conversationId: Uuid): Conversation =
        conversationStore.loadFullConversationForGeneration(conversationId)

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

    override fun updateConversation(conversationId: Uuid, conversation: Conversation, checkDeletedFiles: Boolean) =
        conversationStore.updateConversation(conversationId, conversation, checkDeletedFiles)

    override fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) =
        conversationStore.updateConversationState(conversationId, update)

    override suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) =
        conversationStore.saveConversation(conversationId, conversation)

    suspend fun updateConversationTitle(conversationId: Uuid, title: String) =
        conversationStore.updateTitle(conversationId, title)

    suspend fun togglePinnedStatus(conversationId: Uuid) = conversationStore.togglePinnedStatus(conversationId)

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

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>,
        regenerate: Boolean = false,
    ) = runCoordinator.editMessage(conversationId, messageId, parts, regenerate)

    suspend fun forkConversationAtMessage(conversationId: Uuid, messageId: Uuid): Conversation =
        conversationEditor.forkConversationAtMessage(conversationId, messageId)

    suspend fun selectMessageNode(conversationId: Uuid, nodeId: Uuid, selectIndex: Int) =
        conversationEditor.selectMessageNode(conversationId, nodeId, selectIndex)

    suspend fun deleteMessage(conversationId: Uuid, messageId: Uuid, failIfMissing: Boolean = true) =
        conversationEditor.deleteMessage(conversationId, messageId, failIfMissing)

    suspend fun deleteMessage(conversationId: Uuid, message: UIMessage) =
        conversationEditor.deleteMessage(conversationId, message.id, failIfMissing = false)

    internal fun createDebugRunTools(settings: Settings): List<Tool> =
        toolCatalogFactory.createDebugRunTools(settings)

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
    ): List<Tool> = toolCatalogFactory.createKernelRunTools(
        settings = settings,
        conversationId = conversationId,
        runId = runId,
        conversation = conversation,
        durablePath = durablePath,
        conversationProvider = { getConversationFlow(conversationId).value },
        conversationQueueTools = createConversationQueueTools(conversationId),
        autoApprovedToolNames = approvalActions.trustedToolNames(conversationId),
        events = events,
        executionPolicy = executionPolicy,
    )

    /** Load and sanitize the full conversation once for the resolver's generation turn. */
    internal suspend fun conversationForGeneration(conversationId: Uuid): Conversation {
        try {
            return conversationStore.conversationForGeneration(conversationId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            addError(error, conversationId, title = context.getString(R.string.error_title_send_message))
            throw error
        }
    }

    /**
     * 统一删除入口：tombstone → 取消并等待生成任务 → 清队列 → repository delete。
     * 直接走 repository 删除时，生成中的流式 checkpoint / saveConversation 会在删除后
     * 把会话重新插入（复活），且复活时会话引用的附件可能已被清理。
     */
    suspend fun deleteConversation(conversation: Conversation, deferCleanup: Boolean = false) = withContext(NonCancellable) {
        val conversationId = conversation.id
        val expectedRestoreEpoch = captureRestoreEpoch()
        conversationStore.markDeleted(conversationId, expectedRestoreEpoch)
        try {
            localAsyncTaskCoordinator?.stopAllForConversation(conversationId)
            runCoordinator.stopRunForDeletion(conversationId)
            conversationStore.sessionOrNull(conversationId)?.let { session ->
                if (session.pendingUserMessages.value.isNotEmpty()) {
                    pendingMessageQueue.clearPendingUserMessages(conversationId)
                }
            }
            runPresenter.stopGenerationKeepAlive(conversationId)
            runPresenter.cancelLiveStatus(conversationId)
            conversationStore.deletePersisted(conversation, deferCleanup, expectedRestoreEpoch)
        } catch (t: Throwable) {
            // A repository cleanup can fail after its durable delete. Keep the
            // tombstone in that case so an older checkpoint cannot revive it.
            conversationStore.rollbackDeletionIfPresent(conversationId)
            throw t
        }
    }

    /** 删除被撤销（如 History 的 Undo）后解除 tombstone，恢复该会话的持久化通道。 */
    fun markConversationRestored(conversationId: Uuid) = conversationStore.markConversationRestored(conversationId)

    /** Complete a deferred History deletion with the epoch captured at delete time. */
    suspend fun purgeDeletedConversation(conversation: Conversation) = conversationStore.purgeDeletedConversation(conversation)

    /**
     * Clear every Amber conversation through the same tombstone and cancellation path
     * as single-item deletion so an active checkpoint cannot recreate deleted history.
     */
    suspend fun deleteAllConversations() = withContext(NonCancellable) {
        val expectedRestoreEpoch = captureRestoreEpoch()
        val conversations = conversationStore.listConversations()
        val tombstoned = mutableListOf<Uuid>()
        try {
            conversations.forEach { conversation ->
                conversationStore.markDeleted(conversation.id, expectedRestoreEpoch)
                tombstoned.add(conversation.id)
                localAsyncTaskCoordinator?.stopAllForConversation(conversation.id)
                runCoordinator.stopRunForDeletion(conversation.id)
                conversationStore.sessionOrNull(conversation.id)?.let { session ->
                    if (session.pendingUserMessages.value.isNotEmpty()) {
                        pendingMessageQueue.clearPendingUserMessages(conversation.id)
                    }
                }
                runPresenter.stopGenerationKeepAlive(conversation.id)
                runPresenter.cancelLiveStatus(conversation.id)
            }
            conversationStore.deleteAllPersisted(tombstoned, expectedRestoreEpoch)
        } catch (t: Throwable) {
            tombstoned.forEach { id ->
                conversationStore.rollbackDeletionIfPresent(id)
            }
            throw t
        }
    }

    suspend fun stopGeneration(conversationId: Uuid, runId: String? = null) {
        val localAsync = localAsyncTaskCoordinator
        if (localAsync != null && localAsync.stopTask(conversationId, runId)) {
            return
        }
        runCoordinator.stopGeneration(conversationId, runId)
    }

    fun resumePendingQueue(conversationId: Uuid) = runCoordinator.resumePendingQueue(conversationId)

    /**
     * P1-06: "移回输入框" — take the first queued message out of the queue
     * (persisting the removal) and return its parts so the UI can place them
     * back into the composer for editing. Returns null when the queue is
     * empty.
     */
    suspend fun takePendingMessageForInput(conversationId: Uuid): List<UIMessagePart>? {
        getOrCreateSession(conversationId)
        return pendingMessageQueue.takePendingMessageForInput(conversationId)
    }

    private fun createConversationQueueTools(conversationId: Uuid): List<Tool> =
        pendingMessageQueue.createTools(conversationId, PendingUserMessageDisplayCopy.from(context))

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
