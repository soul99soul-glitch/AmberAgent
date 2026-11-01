package app.amber.core.service

import android.content.Context
import android.util.Log
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.finishPendingTools
import app.amber.ai.ui.finishReasoning
import app.amber.ai.ui.isEmptyInputMessage
import app.amber.core.agent.runtime.AgentEventStore
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunner
import app.amber.core.agent.runtime.AssistantId
import app.amber.core.agent.runtime.ConversationId
import app.amber.core.agent.runtime.MessageNodeId
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.model.toMessageNode
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.utils.withSettledCredentialArgumentsRedacted
import app.amber.feature.chat.api.ChatTurnDescriptor
import app.amber.feature.chat.api.ChatTurnInput
import app.amber.feature.chat.impl.InterruptedRunProjection
import app.amber.feature.macgateway.CloudDialogueSubmission
import app.amber.feature.macgateway.CloudDialogueUiState
import app.amber.feature.macgateway.CloudInputDisposition
import app.amber.feature.macgateway.CloudSubmissionKind
import app.amber.feature.macgateway.CloudTaskSession
import app.amber.feature.runtime.PauseReason
import app.amber.feature.runtime.RunOwnershipRegistry
import app.amber.feature.runtime.RunTerminalState
import app.amber.feature.runtime.RunTerminalStore
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val TAG = "LocalAsyncTaskCoord"
const val DEFAULT_MAX_CONCURRENT_LOCAL_TASKS = 3

enum class LocalTaskStatus(val wireValue: String) {
    QUEUED("queued"),
    RUNNING("running"),
    PAUSED("paused"),
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    FAILED("failed"),
    INTERRUPTED("interrupted"),
    ;

    val isActiveOrQueued: Boolean
        get() = this == QUEUED || this == RUNNING
}

@Serializable
internal data class PersistedLocalTaskRecord(
    val taskId: String,
    val conversationId: String,
    val anchorUserNodeId: String,
    val title: String,
    val status: String,
    val runId: String? = null,
)

internal data class LocalTaskFollowup(
    val submissionId: String,
    val conversationId: Uuid,
    val taskId: String,
    val message: UIMessage,
    val text: String,
    @Volatile var state: CloudInputDisposition = CloudInputDisposition.RECEIVED,
)

internal class ManagedLocalTask(
    val taskId: String,
    val conversationId: Uuid,
    val anchorUserNode: MessageNode,
    val title: String,
    val createdOrder: Long,
) {
    val anchorUserNodeId: Uuid = anchorUserNode.id
    val processingStatus: MutableStateFlow<String?> = MutableStateFlow(null)
    val userNodePersisted: CompletableDeferred<Unit> = CompletableDeferred()
    val settled: CompletableDeferred<Unit> = CompletableDeferred()
    val followups: CopyOnWriteArrayList<LocalTaskFollowup> = CopyOnWriteArrayList()
    val streamedMessageIds: MutableSet<Uuid> = ConcurrentHashMap.newKeySet()

    @Volatile var runId: String? = null
    @Volatile var status: LocalTaskStatus = LocalTaskStatus.QUEUED
    @Volatile var progressText: String? = null
    @Volatile var cancelledByUser: Boolean = false
    @Volatile var executionJob: Job? = null
}

/**
 * Task-level asynchronous ReAct execution coordinator for local conversations.
 *
 * - Keeps the composer available while tasks run; each new normal message starts an
 *   independent task with its own anchor node (`anchorUserNodeId`) and `AgentRunId`.
 * - Followups are only routed to an active/queued task when explicitly selected by the
 *   user, and are consumed at that task's next reasoning boundary (`RECEIVED` -> `CONSUMED`).
 * - Stopping a task cancels only that task (`CANCELLED` for unconsumed followups), and its
 *   streamed/final messages stay anchored at its own timeline position.
 * - Enforces a bounded local concurrency cap (`maxConcurrentTasks`), queuing excess tasks
 *   with visible queued status until a slot opens.
 */
class LocalAsyncTaskCoordinator(
    private val context: Context,
    private val appScope: CoroutineScope,
    private val settingsStore: SettingsAggregator,
    private val conversationStore: ConversationStore,
    private val userInputPreprocessor: UserInputPreprocessor,
    private val runPresenter: ChatRunPresenter,
    private val agentRunner: AgentRunner,
    private val agentEventStore: AgentEventStore? = null,
    private val runTerminalStore: RunTerminalStore? = null,
    private val runOwnershipRegistry: RunOwnershipRegistry? = null,
    val maxConcurrentTasks: Int = DEFAULT_MAX_CONCURRENT_LOCAL_TASKS,
    private val recoveryFileOverride: File? = null,
) {
    private val slotLock = Any()
    private val tasksById = ConcurrentHashMap<String, ManagedLocalTask>()
    private val nodeIdToTaskId = ConcurrentHashMap<Uuid, String>()
    private val runIdToTaskId = ConcurrentHashMap<String, String>()
    private val runningTaskIds = LinkedHashSet<String>()
    private val waitQueue = ArrayDeque<String>()
    private val submissionsById = ConcurrentHashMap<String, CloudDialogueSubmission>()
    private val submissionOrder = CopyOnWriteArrayList<String>()
    private val selectedFollowupByConversation = ConcurrentHashMap<String, String>()
    private val appendMutexes = ConcurrentHashMap<Uuid, Mutex>()
    private val conversationMarkerJobs = ConcurrentHashMap<Uuid, kotlinx.coroutines.CompletableJob>()
    private val orderCounter = java.util.concurrent.atomic.AtomicLong(0L)

    private val _uiState = MutableStateFlow(CloudDialogueUiState())
    val uiState: StateFlow<CloudDialogueUiState> = _uiState.asStateFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val recoveryFile: File
        get() = recoveryFileOverride ?: File(context.filesDir, "local_async_tasks_recovery.json")

    fun submitMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
    ): Boolean {
        if (content.isEmptyInputMessage()) return false
        val selectedTarget = resolvedFollowupTarget(conversationId)
        if (answer && selectedTarget != null) {
            return submitFollowup(conversationId, selectedTarget.workItemId, content)
        }

        val processed = userInputPreprocessor.process(content)
        val userMessage = UIMessage(role = MessageRole.USER, parts = processed)
        val userNode = userMessage.toMessageNode()
        val title = userMessage.toText()
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.take(48)
            ?.ifBlank { "Task" }
            ?: "Task"

        val session = conversationStore.session(conversationId)
        if (session.timelineLoadState.value.initialized) {
            conversationStore.updateConversationState(conversationId) { current ->
                if (current.messageNodes.any { it.id == userNode.id }) {
                    current
                } else {
                    current.copy(
                        messageNodes = current.messageNodes + userNode,
                        updateAt = Instant.now(),
                    )
                }
            }
        }

        if (!answer) {
            appScope.launch(start = CoroutineStart.UNDISPATCHED) {
                persistUserNode(conversationId, userNode)
            }
            return true
        }

        val taskId = Uuid.random().toString()
        val task = ManagedLocalTask(
            taskId = taskId,
            conversationId = conversationId,
            anchorUserNode = userNode,
            title = title,
            createdOrder = orderCounter.incrementAndGet(),
        )
        task.streamedMessageIds.add(userMessage.id)
        task.runId = taskId
        tasksById[taskId] = task
        nodeIdToTaskId[userNode.id] = taskId
        runIdToTaskId[taskId] = taskId

        val startImmediately: Boolean
        synchronized(slotLock) {
            if (runningTaskIds.size < maxConcurrentTasks) {
                runningTaskIds.add(taskId)
                task.status = LocalTaskStatus.RUNNING
                task.progressText = "正在思考…"
                startImmediately = true
            } else {
                waitQueue.addLast(taskId)
                task.status = LocalTaskStatus.QUEUED
                task.progressText = "排队中（等待本地执行槽位）…"
                startImmediately = false
            }
        }

        val statusCollectorJob = appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            task.processingStatus.collect { statusText ->
                if (!statusText.isNullOrBlank() && task.status == LocalTaskStatus.RUNNING) {
                    task.progressText = statusText.lineSequence().firstOrNull()?.trim()?.take(72)
                    publishUiState()
                }
            }
        }

        persistRecoverySnapshot()
        publishUiState()

        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                persistUserNode(conversationId, userNode)
                task.userNodePersisted.complete(Unit)
            } catch (t: Throwable) {
                task.userNodePersisted.completeExceptionally(t)
            }
        }

        if (startImmediately) {
            launchTaskExecution(task, statusCollectorJob)
        } else {
            task.executionJob = statusCollectorJob
            refreshConversationSessionJob(conversationId)
        }
        return true
    }

    fun submitFollowup(
        conversationId: Uuid,
        targetTaskIdOrRunId: String,
        content: List<UIMessagePart>,
    ): Boolean {
        if (content.isEmptyInputMessage()) return false
        val task = findTask(targetTaskIdOrRunId)
            ?.takeIf { it.conversationId == conversationId && it.status.isActiveOrQueued }
            ?: return false

        val processed = userInputPreprocessor.process(content)
        val followupMessage = UIMessage(role = MessageRole.USER, parts = processed)
        val text = followupMessage.toText().trim()
        val submissionId = Uuid.random().toString()
        val followup = LocalTaskFollowup(
            submissionId = submissionId,
            conversationId = conversationId,
            taskId = task.taskId,
            message = followupMessage,
            text = text,
            state = CloudInputDisposition.RECEIVED,
        )
        task.followups.add(followup)
        val submission = CloudDialogueSubmission(
            requestId = submissionId,
            eventId = submissionId,
            topicId = conversationId.toString(),
            conversationId = conversationId.toString(),
            kind = CloudSubmissionKind.FOLLOWUP,
            text = text,
            targetWorkItemId = task.taskId,
            workItemIds = listOf(task.taskId),
            state = CloudInputDisposition.RECEIVED,
        )
        submissionsById[submissionId] = submission
        submissionOrder.add(0, submissionId)
        selectedFollowupByConversation.remove(conversationId.toString())
        publishUiState()
        return true
    }

    fun selectFollowupTarget(conversationId: Uuid, taskId: String?) {
        val convKey = conversationId.toString()
        if (taskId == null) {
            selectedFollowupByConversation.remove(convKey)
        } else {
            selectedFollowupByConversation[convKey] = taskId
        }
        publishUiState(activeConversationId = convKey)
    }

    fun selectFollowupTarget(taskId: String?) {
        val currentConv = _uiState.value.activeConversationId
        if (taskId == null) {
            if (currentConv != null) {
                selectedFollowupByConversation.remove(currentConv)
            } else {
                selectedFollowupByConversation.clear()
            }
        } else {
            val task = findTask(taskId)
            val convKey = task?.conversationId?.toString() ?: currentConv
            if (convKey != null) {
                selectedFollowupByConversation[convKey] = task?.taskId ?: taskId
            }
        }
        publishUiState(activeConversationId = currentConv)
    }

    fun resolvedFollowupTarget(conversationId: Uuid): CloudTaskSession? {
        val convKey = conversationId.toString()
        val selectedId = selectedFollowupByConversation[convKey] ?: return null
        val task = findTask(selectedId)
        if (task == null || task.conversationId != conversationId || !task.status.isActiveOrQueued) {
            selectedFollowupByConversation.remove(convKey)
            return null
        }
        return task.toCloudTaskSession()
    }

    internal fun bindRunToAnchorNode(anchorNodeId: Uuid?, runId: String): ManagedLocalTask? {
        val taskId = anchorNodeId?.let { nodeIdToTaskId[it] } ?: runIdToTaskId[runId] ?: return null
        val task = tasksById[taskId] ?: return null
        task.runId = runId
        runIdToTaskId[runId] = taskId
        persistRecoverySnapshot()
        return task
    }

    internal fun findTaskForRun(runId: String?, anchorNodeId: Uuid? = null): ManagedLocalTask? {
        if (runId != null) {
            runIdToTaskId[runId]?.let { tasksById[it] }?.let { return it }
            tasksById[runId]?.let { return it }
        }
        if (anchorNodeId != null) {
            nodeIdToTaskId[anchorNodeId]?.let { tasksById[it] }?.let { return it }
        }
        return null
    }

    internal fun processingStatusForRun(runId: String, anchorNodeId: Uuid?): MutableStateFlow<String?>? =
        findTaskForRun(runId, anchorNodeId)?.processingStatus

    internal fun consumeFollowupsForRun(runId: String, anchorNodeId: Uuid? = null): List<UIMessage> {
        val task = findTaskForRun(runId, anchorNodeId) ?: return emptyList()
        val consumed = mutableListOf<UIMessage>()
        for (followup in task.followups) {
            if (followup.state == CloudInputDisposition.RECEIVED) {
                followup.state = CloudInputDisposition.CONSUMED
                task.streamedMessageIds.add(followup.message.id)
                submissionsById.computeIfPresent(followup.submissionId) { _, existing ->
                    existing.copy(state = CloudInputDisposition.CONSUMED)
                }
                consumed += followup.message
            }
        }
        if (consumed.isNotEmpty()) {
            publishUiState()
        }
        return consumed
    }

    internal fun onStreamingMessages(
        runId: String,
        anchorNodeId: Uuid?,
        messages: List<UIMessage>,
    ) {
        val task = findTaskForRun(runId, anchorNodeId) ?: return
        val anchorIdx = messages.indexOfFirst { it.id == task.anchorUserNode.currentMessage.id }
        val ownedSlice = if (anchorIdx >= 0) messages.subList(anchorIdx, messages.size) else messages
        ownedSlice.forEach { task.streamedMessageIds.add(it.id) }

        val lastAssistant = ownedSlice.lastOrNull { it.role == MessageRole.ASSISTANT }
        if (lastAssistant != null) {
            val singleLine = deriveSingleLineProgress(lastAssistant)
            if (!singleLine.isNullOrBlank()) {
                task.progressText = singleLine
                publishUiState()
            }
        }
    }

    fun updateTaskProgress(taskIdOrRunId: String, progressText: String) {
        val task = findTask(taskIdOrRunId) ?: return
        val singleLine = progressText.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: return
        task.progressText = singleLine.take(72)
        publishUiState()
    }

    fun hasActiveOrQueuedTasks(conversationId: Uuid, excludingRunId: String? = null): Boolean {
        val excludedTaskId = excludingRunId?.let { runIdToTaskId[it] ?: it }
        return tasksById.values.any { task ->
            task.conversationId == conversationId &&
                task.status.isActiveOrQueued &&
                task.taskId != excludedTaskId &&
                task.runId != excludingRunId
        }
    }

    suspend fun stopTask(conversationId: Uuid, taskIdOrRunId: String? = null): Boolean = withContext(NonCancellable) {
        val target = if (taskIdOrRunId != null) {
            findTask(taskIdOrRunId)?.takeIf { it.conversationId == conversationId }
        } else {
            val selectedId = selectedFollowupByConversation[conversationId.toString()]
            val selectedTask = selectedId?.let(::findTask)
                ?.takeIf { it.conversationId == conversationId && it.status.isActiveOrQueued }
            selectedTask ?: tasksById.values
                .filter { it.conversationId == conversationId && it.status.isActiveOrQueued }
                .maxByOrNull { it.createdOrder }
        } ?: return@withContext false

        stopSpecificTask(target)
        true
    }

    suspend fun stopAllForConversation(conversationId: Uuid) = withContext(NonCancellable) {
        val active = tasksById.values
            .filter { it.conversationId == conversationId && it.status.isActiveOrQueued }
            .sortedByDescending { it.createdOrder }
        for (task in active) {
            stopSpecificTask(task)
        }
    }

    private suspend fun stopSpecificTask(task: ManagedLocalTask) {
        task.cancelledByUser = true
        val wasQueuedOnly: Boolean
        var nextToLaunch: ManagedLocalTask? = null
        synchronized(slotLock) {
            wasQueuedOnly = waitQueue.remove(task.taskId)
            if (runningTaskIds.remove(task.taskId)) {
                nextToLaunch = pollNextQueuedTaskLocked()
            }
            task.status = LocalTaskStatus.CANCELLED
            task.progressText = "已停止"
        }
        cancelUnconsumedFollowups(task)
        if (selectedFollowupByConversation[task.conversationId.toString()] == task.taskId) {
            selectedFollowupByConversation.remove(task.conversationId.toString())
        }
        persistRecoverySnapshot()
        publishUiState()

        if (wasQueuedOnly) {
            task.executionJob?.cancel()
            task.userNodePersisted.join()
            settleTaskMessagesInConversation(task, reason = "user_cancelled")
            finishConversationKeepAliveIfIdle(task.conversationId)
            refreshConversationSessionJob(task.conversationId)
            task.settled.complete(Unit)
            return
        }

        val runId = task.runId
        if (runId != null) {
            runCatching {
                runOwnershipRegistry?.cancel(
                    runId = runId,
                    conversationId = task.conversationId.toString(),
                )
            }
            runCatching { agentRunner.cancel(AgentRunId(runId)) }
            runCatching { agentRunner.awaitRunSettled(AgentRunId(runId)) }
        }
        task.executionJob?.cancel()
        runCatching { task.executionJob?.join() }
        settleTaskMessagesInConversation(task, reason = "user_cancelled")
        finishConversationKeepAliveIfIdle(task.conversationId)
        refreshConversationSessionJob(task.conversationId)
        task.settled.complete(Unit)

        nextToLaunch?.let { next ->
            launchTaskExecution(next, statusCollectorJob = next.executionJob)
        }
    }

    private fun launchTaskExecution(task: ManagedLocalTask, statusCollectorJob: Job?) {
        val job = appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var terminalStatus = LocalTaskStatus.COMPLETED
            try {
                task.userNodePersisted.await()
                if (task.cancelledByUser || task.status == LocalTaskStatus.CANCELLED) {
                    terminalStatus = LocalTaskStatus.CANCELLED
                    return@launch
                }
                val maxSteps = settingsStore.settingsFlow.value.agentRuntime.maxToolLoopSteps.coerceIn(
                    app.amber.core.settings.MIN_AGENT_TOOL_LOOP_STEPS,
                    app.amber.core.settings.MAX_AGENT_TOOL_LOOP_STEPS,
                )
                val input = ChatTurnInput(
                    conversationId = ConversationId(task.conversationId.toString()),
                    messageNodeId = MessageNodeId(task.anchorUserNodeId.toString()),
                    assistantId = AssistantId(AMBER_AGENT_ID.toString()),
                    userMessageText = task.anchorUserNode.currentMessage.toText(),
                    maxToolIterations = maxSteps,
                )
                val handle = agentRunner.launch(
                    ChatTurnDescriptor.ID,
                    input,
                    requestedRunId = AgentRunId(task.taskId),
                ).getOrThrow()
                bindRunToAnchorNode(task.anchorUserNodeId, handle.runId.value)
                val snapshots = agentRunner.observe(handle.runId)
                val endSnapshot = snapshots.first { it.status.isTerminal || it.status.isPause }
                if (endSnapshot.status.isTerminal) {
                    agentRunner.awaitRunSettled(handle.runId)
                }
                terminalStatus = when {
                    task.cancelledByUser || endSnapshot.status == app.amber.core.agent.runtime.RunStatus.CANCELLED ->
                        LocalTaskStatus.CANCELLED
                    endSnapshot.status == app.amber.core.agent.runtime.RunStatus.INTERRUPTED ->
                        LocalTaskStatus.INTERRUPTED
                    endSnapshot.status == app.amber.core.agent.runtime.RunStatus.FAILED ->
                        LocalTaskStatus.FAILED
                    endSnapshot.status.isPause ->
                        LocalTaskStatus.PAUSED
                    else ->
                        LocalTaskStatus.COMPLETED
                }
            } catch (cancelled: CancellationException) {
                terminalStatus = LocalTaskStatus.CANCELLED
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "Task ${task.taskId} failed", error)
                terminalStatus = LocalTaskStatus.FAILED
            } finally {
                withContext(NonCancellable) {
                    statusCollectorJob?.cancel()
                    onTaskExecutionFinished(task, terminalStatus)
                }
            }
        }
        task.executionJob = job
        refreshConversationSessionJob(task.conversationId)
    }

    private suspend fun onTaskExecutionFinished(
        task: ManagedLocalTask,
        finalStatus: LocalTaskStatus,
    ) {
        var nextToLaunch: ManagedLocalTask? = null
        synchronized(slotLock) {
            if (task.status != LocalTaskStatus.CANCELLED) {
                task.status = finalStatus
            }
            if (runningTaskIds.remove(task.taskId)) {
                nextToLaunch = pollNextQueuedTaskLocked()
            }
        }
        if (task.status == LocalTaskStatus.CANCELLED || task.status == LocalTaskStatus.FAILED || task.status == LocalTaskStatus.INTERRUPTED) {
            cancelUnconsumedFollowups(task)
        }
        if (selectedFollowupByConversation[task.conversationId.toString()] == task.taskId) {
            selectedFollowupByConversation.remove(task.conversationId.toString())
        }
        persistRecoverySnapshot()
        publishUiState()
        finishConversationKeepAliveIfIdle(task.conversationId)
        refreshConversationSessionJob(task.conversationId)
        task.settled.complete(Unit)

        nextToLaunch?.let { next ->
            launchTaskExecution(next, statusCollectorJob = next.executionJob)
        }
    }

    private fun pollNextQueuedTaskLocked(): ManagedLocalTask? {
        while (waitQueue.isNotEmpty() && runningTaskIds.size < maxConcurrentTasks) {
            val nextId = waitQueue.removeFirst()
            val nextTask = tasksById[nextId] ?: continue
            if (nextTask.status == LocalTaskStatus.QUEUED && !nextTask.cancelledByUser) {
                runningTaskIds.add(nextId)
                nextTask.status = LocalTaskStatus.RUNNING
                nextTask.progressText = "正在思考…"
                return nextTask
            }
        }
        return null
    }

    private fun cancelUnconsumedFollowups(task: ManagedLocalTask) {
        for (followup in task.followups) {
            if (followup.state == CloudInputDisposition.RECEIVED) {
                followup.state = CloudInputDisposition.CANCELLED
                submissionsById.computeIfPresent(followup.submissionId) { _, existing ->
                    existing.copy(state = CloudInputDisposition.CANCELLED)
                }
            }
        }
    }

    private suspend fun settleTaskMessagesInConversation(
        task: ManagedLocalTask,
        reason: String,
    ) {
        val conversationId = task.conversationId
        val cancelledAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        var updatedConversation: Conversation? = null
        conversationStore.updateConversationState(conversationId) { current ->
            val anchorIdx = current.messageNodes.indexOfFirst { it.id == task.anchorUserNodeId }
            if (anchorIdx < 0) return@updateConversationState current

            val assistantIds = task.streamedMessageIds - task.anchorUserNode.currentMessage.id
            val hasAssistantNode = current.messageNodes.any { node ->
                node.currentMessage.role == MessageRole.ASSISTANT && node.currentMessage.id in assistantIds
            }
            val nextNodes = if (hasAssistantNode) {
                current.messageNodes.map { node ->
                    val msg = node.currentMessage
                    if (msg.role != MessageRole.ASSISTANT || msg.id !in assistantIds) {
                        node
                    } else {
                        val settled = msg
                            .finishPendingTools(::cancelToolByUser)
                            .finishReasoning()
                            .let { m ->
                                val annotations = if (m.annotations.any { it is UIMessageAnnotation.GenerationInterrupted }) {
                                    m.annotations
                                } else {
                                    m.annotations + UIMessageAnnotation.GenerationInterrupted(reason)
                                }
                                m.copy(
                                    annotations = annotations,
                                    finishedAt = m.finishedAt ?: cancelledAt,
                                )
                            }
                        node.copy(
                            messages = node.messages.map { if (it.id == msg.id) settled else it },
                        )
                    }
                }
            } else {
                val interruptedMsg = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = emptyList(),
                    annotations = listOf(UIMessageAnnotation.GenerationInterrupted(reason)),
                    finishedAt = cancelledAt,
                )
                current.messageNodes.toMutableList().apply {
                    add(anchorIdx + 1, MessageNode(messages = listOf(interruptedMsg)))
                }
            }
            current.copy(
                messageNodes = nextNodes,
                updateAt = Instant.now(),
            ).also { updatedConversation = it }
        }
        updatedConversation?.let {
            conversationStore.persistConversationWindow(conversationId, it, indexFts = true)
        }
    }

    /**
     * Cold-start recovery: any task that was `QUEUED` or `RUNNING` when the app process
     * was killed by the OS is marked `INTERRUPTED` and projected at its anchor position.
     */
    suspend fun recoverInterruptedTasks() = withContext(NonCancellable) {
        val records = readRecoverySnapshot()
        if (records.isEmpty()) return@withContext
        for (record in records) {
            val conversationId = runCatching { Uuid.parse(record.conversationId) }.getOrNull() ?: continue
            val anchorNodeId = runCatching { Uuid.parse(record.anchorUserNodeId) }.getOrNull() ?: continue
            record.runId?.let { runId ->
                runCatching {
                    val existing = runTerminalStore?.get(runId)
                    if (existing != null && existing.state == RunTerminalState.RUNNING) {
                        runTerminalStore.finish(runId, RunTerminalState.INTERRUPTED, PauseReason.PROCESS_RESTART)
                    }
                }
                runCatching {
                    agentEventStore?.markInterrupted(AgentRunId(runId), reason = InterruptedRunProjection.DEFAULT_REASON)
                }
            }
            runCatching {
                conversationStore.ensureInitialized(conversationId)
                var projectedConversation: Conversation? = null
                conversationStore.updateConversationState(conversationId) { current ->
                    InterruptedRunProjection.projectAtAnchor(
                        conversation = current,
                        anchorNodeId = anchorNodeId,
                        reason = InterruptedRunProjection.DEFAULT_REASON,
                    ).also { projected ->
                        if (projected !== current) projectedConversation = projected
                    }
                }
                projectedConversation?.let {
                    conversationStore.persistConversationWindow(conversationId, it, indexFts = true)
                }
            }.onFailure { error ->
                Log.w(TAG, "recoverInterruptedTasks failed for ${record.taskId}", error)
            }
        }
        runCatching { if (recoveryFile.exists()) recoveryFile.delete() }
    }

    private suspend fun persistUserNode(conversationId: Uuid, userNode: MessageNode) {
        val mutex = appendMutexes.computeIfAbsent(conversationId) { Mutex() }
        mutex.withLock {
            conversationStore.ensureInitialized(conversationId)
            val current = conversationStore.getConversationFlow(conversationId).value
            if (current.messageNodes.any { it.id == userNode.id }) {
                conversationStore.persistConversationWindow(conversationId, current, indexFts = true)
            } else {
                conversationStore.appendUserNode(conversationId, userNode)
            }
        }
    }

    private fun finishConversationKeepAliveIfIdle(conversationId: Uuid) {
        if (!hasActiveOrQueuedTasks(conversationId)) {
            runPresenter.stopGenerationKeepAlive(conversationId)
        }
    }

    private fun refreshConversationSessionJob(conversationId: Uuid) {
        val session = conversationStore.sessionOrNull(conversationId) ?: return
        val hasActive = tasksById.values.any {
            it.conversationId == conversationId && it.status.isActiveOrQueued
        }
        if (hasActive) {
            val marker = conversationMarkerJobs.compute(conversationId) { _, existing ->
                if (existing != null && existing.isActive) existing else kotlinx.coroutines.SupervisorJob()
            }!!
            if (session.getJob() !== marker) {
                session.setJob(marker)
            }
        } else {
            val removed = conversationMarkerJobs.remove(conversationId)
            removed?.complete()
            if (session.getJob() != null) {
                session.setJob(null)
            }
        }
    }

    internal suspend fun awaitTaskSettledByTitle(title: String) {
        val task = tasksById.values
            .filter { it.title == title }
            .maxByOrNull { it.createdOrder }
            ?: return
        task.settled.await()
    }

    private fun findTask(taskIdOrRunId: String): ManagedLocalTask? =
        tasksById[taskIdOrRunId] ?: runIdToTaskId[taskIdOrRunId]?.let { tasksById[it] }

    private fun ManagedLocalTask.toCloudTaskSession(): CloudTaskSession = CloudTaskSession(
        workItemId = taskId,
        topicId = conversationId.toString(),
        conversationId = conversationId.toString(),
        title = title,
        progressText = progressText,
        active = status.isActiveOrQueued,
        outcome = status.wireValue,
    )

    private fun publishUiState(activeConversationId: String? = _uiState.value.activeConversationId) {
        val taskSessions = tasksById.values
            .sortedBy { it.createdOrder }
            .map { it.toCloudTaskSession() }
        val submissions = submissionOrder.mapNotNull { submissionsById[it] }
        val resolvedSelected = if (activeConversationId != null) {
            selectedFollowupByConversation[activeConversationId]
        } else {
            selectedFollowupByConversation.values.firstOrNull()
        }
        _uiState.value = CloudDialogueUiState(
            enabled = false,
            activeConversationId = activeConversationId,
            selectedFollowupWorkItemId = resolvedSelected,
            tasks = taskSessions,
            submissions = submissions,
        )
    }

    fun bindActiveConversation(conversationId: Uuid) {
        publishUiState(activeConversationId = conversationId.toString())
    }

    private fun persistRecoverySnapshot() {
        runCatching {
            val activeRecords = tasksById.values
                .filter { it.status.isActiveOrQueued }
                .sortedBy { it.createdOrder }
                .map { task ->
                    PersistedLocalTaskRecord(
                        taskId = task.taskId,
                        conversationId = task.conversationId.toString(),
                        anchorUserNodeId = task.anchorUserNodeId.toString(),
                        title = task.title,
                        status = task.status.wireValue,
                        runId = task.runId,
                    )
                }
            if (activeRecords.isEmpty()) {
                if (recoveryFile.exists()) recoveryFile.delete()
            } else {
                recoveryFile.parentFile?.mkdirs()
                recoveryFile.writeText(
                    json.encodeToString(ListSerializer(PersistedLocalTaskRecord.serializer()), activeRecords),
                )
            }
        }
    }

    private fun readRecoverySnapshot(): List<PersistedLocalTaskRecord> = runCatching {
        if (!recoveryFile.exists()) return emptyList()
        json.decodeFromString(ListSerializer(PersistedLocalTaskRecord.serializer()), recoveryFile.readText())
    }.getOrDefault(emptyList())

    private fun deriveSingleLineProgress(assistantMessage: UIMessage): String? {
        val unexecutedTool = assistantMessage.parts
            .filterIsInstance<UIMessagePart.Tool>()
            .lastOrNull { !it.isExecuted }
        if (unexecutedTool != null) {
            return if (unexecutedTool.isPending) {
                "等待工具审批：${unexecutedTool.toolName}"
            } else {
                "执行工具：${unexecutedTool.toolName}"
            }
        }
        val activeReasoning = assistantMessage.parts
            .filterIsInstance<UIMessagePart.Reasoning>()
            .lastOrNull { it.finishedAt == null && it.reasoning.isNotBlank() }
        if (activeReasoning != null) {
            val line = activeReasoning.reasoning.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
            if (!line.isNullOrBlank()) return line.take(72)
        }
        val textLine = assistantMessage.parts
            .filterIsInstance<UIMessagePart.Text>()
            .asReversed()
            .firstNotNullOfOrNull { part ->
                part.text.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
            }
        return textLine?.take(72)
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        val cancellation = UIMessagePart.Text(
            """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}""",
        )
        return tool.copy(
            output = listOf(cancellation),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user"),
        ).withSettledCredentialArgumentsRedacted()
    }
}
