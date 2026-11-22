package app.amber.feature.macgateway

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * Contract rules from `cloud-dialogue-api.md`:
 * - `requestId`: `[A-Za-z0-9._:-]`, length `1..128`
 * - `text` (for `messages` / `inputs`): non-blank, length `1..24000`
 */
object CloudDialogueContract {
    const val VERSION_HEADER = "X-Amber-Dialogue-Version"
    const val VERSION = "1"
    const val CONVERSATION_HEADER = "X-Amber-Conversation-Id"
    private val REQUEST_ID_REGEX = Regex("^[A-Za-z0-9._:-]{1,128}$")
    const val MAX_TEXT_LENGTH = 24_000
    const val MAX_CONVERSATION_ID_LENGTH = 128

    fun isValidRequestId(requestId: String): Boolean = REQUEST_ID_REGEX.matches(requestId)

    fun isValidText(text: String): Boolean = text.isNotBlank() && text.length <= MAX_TEXT_LENGTH

    fun normalizeConversationId(conversationId: String?): String? =
        conversationId?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_CONVERSATION_ID_LENGTH }

    fun requireValidRequestId(requestId: String) {
        if (!isValidRequestId(requestId)) {
            throw MacGatewayException.Server("invalid_request")
        }
    }

    fun requireValidText(text: String) {
        if (!isValidText(text)) {
            throw MacGatewayException.Server("invalid_request")
        }
    }
}

/**
 * Receipt returned by `POST /v2/dialogue/messages`, `POST /v2/work-items/{id}/inputs`,
 * `POST /v2/work-items/{id}/stop`, and `GET /v2/dialogue/receipts/{requestId}`.
 */
@Serializable
data class CloudDialogueReceipt(
    val eventId: String,
    val requestId: String,
    val topicId: String,
    val workItemIds: List<String> = emptyList(),
    /** `"received"`, `"consumed"`, or `"cancelled"`. */
    val state: String,
    val replayed: Boolean = false,
    val soulRevision: Long? = null,
    val conversationId: String? = null,
    val causedByInputId: String? = null,
    val causedByRequestId: String? = null,
    val parentRunId: String? = null,
    val executorKind: String? = null,
    val executorId: String? = null,
    val epoch: Long? = null,
    val observedSeq: Long? = null,
    val ledgerId: String? = null,
    val outcomeUnknown: Boolean = false,
    val schemaVersion: Int? = null,
)

/**
 * Incremental timeline item returned by `GET /v2/dialogue/events?after={seq}&limit={limit}`.
 *
 * Unknown `kind` values are safely skipped while advancing the monotonic cursor so future
 * Contract v1 extensions never stall polling.
 */
@Serializable
data class CloudDialogueEvent(
    val seq: Long = 0L,
    val id: String,
    val kind: String,
    val role: String = "system",
    val text: String = "",
    val topicId: String = "",
    val workItemId: String? = null,
    val outcome: String? = null,
    val conversationId: String? = null,
    val causedByInputId: String? = null,
    val causedByRequestId: String? = null,
    val parentRunId: String? = null,
    val executorKind: String? = null,
    val executorId: String? = null,
    val epoch: Long? = null,
    val observedSeq: Long? = null,
    val ledgerId: String? = null,
    val outcomeUnknown: Boolean = false,
    val schemaVersion: Int? = null,
    val riskLevel: String? = null,
    val targetDevice: String? = null,
    val targetSummary: String? = null,
    val diffPreview: String? = null,
    val requiresLocalApproval: Boolean? = null,
    val sourceRefs: List<String>? = null,
    val sourcePaths: List<String>? = null,
)

@Serializable
data class CloudDialogueEventsPage(
    val items: List<CloudDialogueEvent> = emptyList(),
    val nextCursor: Long = 0L,
    /** Optional top-level monotonic revision for cross-platform Agent Soul (`C-SOUL-V1`). */
    val soulRevision: Long? = null,
    val schemaVersion: Int? = null,
)

@Serializable
enum class CloudSubmissionKind {
    MESSAGE,
    FOLLOWUP,
    STOP,
}

/**
 * Distinct disposition states for submitted cloud inputs (`received`, `consumed`, `cancelled`).
 * Followups only become `CONSUMED` after the Worker's model request consumes them; if the task is
 * stopped first, unconsumed followups become `CANCELLED` and must never be shown as executed.
 */
@Serializable
enum class CloudInputDisposition(val wireValue: String) {
    RECEIVED("received"),
    CONSUMED("consumed"),
    CANCELLED("cancelled"),
    ;

    companion object {
        fun fromWire(value: String): CloudInputDisposition = when (value.trim().lowercase()) {
            "consumed" -> CONSUMED
            "cancelled" -> CANCELLED
            else -> RECEIVED
        }
    }
}

@Serializable
data class CloudDialogueSubmission(
    val requestId: String,
    val eventId: String? = null,
    val topicId: String? = null,
    val conversationId: String,
    val kind: CloudSubmissionKind,
    val text: String,
    val targetWorkItemId: String? = null,
    val workItemIds: List<String> = emptyList(),
    val state: CloudInputDisposition = CloudInputDisposition.RECEIVED,
    val replayed: Boolean = false,
    val localEchoed: Boolean = false,
    val receiptSyncedAfterConsumed: Boolean = false,
    val stopTooLate: Boolean = false,
    val statusNote: String? = null,
    val outcomeUnknown: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val error: String? = null,
)

/**
 * Per-task session state for a background cloud workItem.
 *
 * `progressText` is updated in-place by `process_event` and `input_consumed` so UI renders a
 * single dynamic status line per task rather than accumulating per-tool cards.
 */
@Serializable
data class CloudTaskSession(
    val workItemId: String,
    val topicId: String,
    val conversationId: String,
    val title: String,
    val progressText: String? = null,
    val processEventCount: Int = 0,
    val active: Boolean = true,
    /** `"completed"`, `"blocked"`, `"failed"`, or `"cancelled"` when `active == false`. */
    val outcome: String? = null,
    val resultText: String? = null,
    val outcomeUnknown: Boolean = false,
    val outcomeUnknownAbandoned: Boolean = false,
    val retryRequestId: String? = null,
    val stopTooLateNotice: String? = null,
    val sourceRefs: List<String> = emptyList(),
    val sourcePaths: List<String> = emptyList(),
    val parentRunId: String? = null,
    val executorKind: String? = null,
    val executorId: String? = null,
    val epoch: Long? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
)

@Serializable
data class CloudDialoguePersistedState(
    val gatewayId: String = "",
    val deviceId: String = "",
    val cursor: Long = 0L,
    val seenEventIds: List<String> = emptyList(),
    val localEchoedEventIds: List<String> = emptyList(),
    val topicToConversation: Map<String, String> = emptyMap(),
    val tasks: List<CloudTaskSession> = emptyList(),
    val submissions: List<CloudDialogueSubmission> = emptyList(),
)

data class CloudDialogueUiState(
    val enabled: Boolean = false,
    val activeConversationId: String? = null,
    val cursor: Long = 0L,
    val selectedFollowupWorkItemId: String? = null,
    val tasks: List<CloudTaskSession> = emptyList(),
    val submissions: List<CloudDialogueSubmission> = emptyList(),
    val lastError: String? = null,
) {
    fun tasksForConversation(conversationId: String): List<CloudTaskSession> =
        tasks.filter { it.conversationId == conversationId }

    fun activeTasksForConversation(conversationId: String): List<CloudTaskSession> =
        tasks.filter { it.conversationId == conversationId && it.active }

    fun submissionsForConversation(conversationId: String): List<CloudDialogueSubmission> =
        submissions.filter { it.conversationId == conversationId }

    fun outcomeUnknownTasksForConversation(conversationId: String): List<CloudTaskSession> =
        tasks.filter { it.conversationId == conversationId && it.outcomeUnknown && !it.outcomeUnknownAbandoned }

    fun resolvedFollowupTarget(conversationId: String): CloudTaskSession? {
        val targetId = selectedFollowupWorkItemId ?: return null
        return activeTasksForConversation(conversationId).firstOrNull { it.workItemId == targetId }
    }
}

/**
 * Coordinates Cloud Dialogue v1 requests (`messages`, `inputs`, `stop`, `receipts`, `events`),
 * monotonic `seq` cursor persistence, stable `event.id` deduplication, in-place `process_event`
 * single-line progress updates, and routing of `assistant_reply` / `task_result` to the timeline.
 */
class CloudDialogueCoordinator(
    private val clientProvider: suspend () -> MacGatewayClient?,
    private val timelineSink: CloudDialogueTimelineSink,
    private val persistState: suspend (CloudDialoguePersistedState) -> Unit,
    private val onUnauthorized: suspend () -> Unit = {},
    private val onStateChanged: (CloudDialogueUiState) -> Unit = {},
    private val onSoulRevision: suspend (Long) -> Unit = {},
) {
    private val mutex = Mutex()

    private var enabled: Boolean = false
    private var gatewayId: String = ""
    private var deviceId: String = ""
    private var activeConversationId: String? = null
    private var selectedFollowupWorkItemId: String? = null
    private var cursor: Long = 0L
    private val seenEventIds = LinkedHashSet<String>()
    private val localEchoedEventIds = LinkedHashSet<String>()
    private val topicToConversation = LinkedHashMap<String, String>()
    private val tasksById = LinkedHashMap<String, CloudTaskSession>()
    private val submissionsByRequestId = LinkedHashMap<String, CloudDialogueSubmission>()
    private var lastError: String? = null

    suspend fun restore(
        enabled: Boolean,
        connection: MacGatewayConnection?,
        persisted: CloudDialoguePersistedState?,
    ) = mutex.withLock {
        this.enabled = enabled
        if (connection == null) {
            clearSessionLocked()
            publishLocked()
            return@withLock
        }
        this.gatewayId = connection.gatewayId
        this.deviceId = connection.deviceId
        if (persisted != null &&
            persisted.gatewayId == connection.gatewayId &&
            persisted.deviceId == connection.deviceId
        ) {
            cursor = persisted.cursor.coerceAtLeast(0L)
            seenEventIds.clear()
            seenEventIds.addAll(persisted.seenEventIds)
            localEchoedEventIds.clear()
            localEchoedEventIds.addAll(persisted.localEchoedEventIds)
            topicToConversation.clear()
            topicToConversation.putAll(persisted.topicToConversation)
            tasksById.clear()
            persisted.tasks.forEach { tasksById[it.workItemId] = it }
            submissionsByRequestId.clear()
            persisted.submissions.forEach { submissionsByRequestId[it.requestId] = it }
        } else {
            clearSessionLocked()
            this.gatewayId = connection.gatewayId
            this.deviceId = connection.deviceId
        }
        publishLocked()
    }

    suspend fun setEnabled(value: Boolean) = mutex.withLock {
        enabled = value
        if (!value) {
            selectedFollowupWorkItemId = null
        }
        publishLocked()
    }

    suspend fun bindActiveConversation(conversationId: String) = mutex.withLock {
        activeConversationId = conversationId
        val selected = selectedFollowupWorkItemId
        if (selected != null && tasksById[selected]?.let { it.conversationId == conversationId && it.active } != true) {
            selectedFollowupWorkItemId = null
        }
        publishLocked()
    }

    suspend fun selectFollowupTarget(workItemId: String?) = mutex.withLock {
        selectedFollowupWorkItemId = workItemId?.takeIf { id ->
            tasksById[id]?.active == true
        }
        publishLocked()
    }

    suspend fun sendMessage(
        conversationId: String,
        text: String,
        requestId: String,
    ): CloudDialogueSubmission {
        val client = clientProvider() ?: throw MacGatewayException.Unreachable()
        val now = System.currentTimeMillis()
        val initialSubmission = mutex.withLock {
            activeConversationId = conversationId
            val existing = submissionsByRequestId[requestId]
            val echoed = existing?.localEchoed == true
            val sub = (existing ?: CloudDialogueSubmission(
                requestId = requestId,
                conversationId = conversationId,
                kind = CloudSubmissionKind.MESSAGE,
                text = text,
                state = CloudInputDisposition.RECEIVED,
                createdAtMs = now,
            )).copy(
                localEchoed = true,
                updatedAtMs = now,
                error = null,
            )
            submissionsByRequestId[requestId] = sub
            persistLocked()
            publishLocked()
            if (!echoed) {
                timelineSink.appendUserMessage(conversationId, text, eventId = sub.eventId)
            }
            sub
        }

        return try {
            val receipt = client.messages(
                requestId = initialSubmission.requestId,
                text = initialSubmission.text,
                conversationId = conversationId,
            )
            val updated = mutex.withLock {
                localEchoedEventIds.add(receipt.eventId)
                val resolvedConversation = receipt.conversationId?.takeIf { it.isNotBlank() } ?: conversationId
                topicToConversation[receipt.topicId] = resolvedConversation
                val next = (submissionsByRequestId[requestId] ?: initialSubmission).withReceipt(receipt)
                submissionsByRequestId[requestId] = next
                registerWorkItemsLocked(
                    workItemIds = next.workItemIds,
                    topicId = receipt.topicId,
                    conversationId = resolvedConversation,
                    titleHint = initialSubmission.text,
                    parentRunId = receipt.parentRunId,
                    executorKind = receipt.executorKind,
                    executorId = receipt.executorId,
                    epoch = receipt.epoch,
                    outcomeUnknown = receipt.outcomeUnknown,
                )
                lastError = null
                persistLocked()
                publishLocked()
                next
            }
            updated
        } catch (error: Throwable) {
            handleRequestFailure(requestId, error)
            throw error
        }
    }

    suspend fun sendFollowup(
        conversationId: String,
        workItemId: String,
        text: String,
        requestId: String,
    ): CloudDialogueSubmission {
        val client = clientProvider() ?: throw MacGatewayException.Unreachable()
        val now = System.currentTimeMillis()
        val initialSubmission = mutex.withLock {
            activeConversationId = conversationId
            val existing = submissionsByRequestId[requestId]
            val echoed = existing?.localEchoed == true
            val task = tasksById[workItemId]
            val targetConv = task?.conversationId ?: conversationId
            val sub = (existing ?: CloudDialogueSubmission(
                requestId = requestId,
                topicId = task?.topicId,
                conversationId = targetConv,
                kind = CloudSubmissionKind.FOLLOWUP,
                text = text,
                targetWorkItemId = workItemId,
                workItemIds = listOf(workItemId),
                state = CloudInputDisposition.RECEIVED,
                createdAtMs = now,
            )).copy(
                localEchoed = true,
                updatedAtMs = now,
                error = null,
            )
            submissionsByRequestId[requestId] = sub
            if (selectedFollowupWorkItemId == workItemId) {
                selectedFollowupWorkItemId = null
            }
            persistLocked()
            publishLocked()
            if (!echoed) {
                timelineSink.appendUserMessage(targetConv, text, eventId = sub.eventId)
            }
            sub
        }

        return try {
            val receipt = client.inputs(
                workItemId = workItemId,
                requestId = initialSubmission.requestId,
                text = initialSubmission.text,
                conversationId = initialSubmission.conversationId,
            )
            mutex.withLock {
                localEchoedEventIds.add(receipt.eventId)
                val resolvedConversation = receipt.conversationId?.takeIf { it.isNotBlank() }
                    ?: initialSubmission.conversationId
                topicToConversation.putIfAbsent(receipt.topicId, resolvedConversation)
                val next = (submissionsByRequestId[requestId] ?: initialSubmission).withReceipt(receipt)
                submissionsByRequestId[requestId] = next
                registerWorkItemsLocked(
                    workItemIds = next.workItemIds,
                    topicId = receipt.topicId,
                    conversationId = resolvedConversation,
                    titleHint = tasksById[workItemId]?.title ?: initialSubmission.text,
                    parentRunId = receipt.parentRunId,
                    executorKind = receipt.executorKind,
                    executorId = receipt.executorId,
                    epoch = receipt.epoch,
                    outcomeUnknown = receipt.outcomeUnknown,
                )
                lastError = null
                persistLocked()
                publishLocked()
                next
            }
        } catch (error: Throwable) {
            handleRequestFailure(requestId, error)
            throw error
        }
    }

    suspend fun stopWorkItem(
        workItemId: String,
        requestId: String,
    ): CloudDialogueSubmission {
        val client = clientProvider() ?: throw MacGatewayException.Unreachable()
        val now = System.currentTimeMillis()
        val initialSubmission = mutex.withLock {
            val task = tasksById[workItemId]
            val targetConv = task?.conversationId ?: activeConversationId.orEmpty()
            val existing = submissionsByRequestId[requestId]
            val sub = (existing ?: CloudDialogueSubmission(
                requestId = requestId,
                topicId = task?.topicId,
                conversationId = targetConv,
                kind = CloudSubmissionKind.STOP,
                text = "",
                targetWorkItemId = workItemId,
                workItemIds = listOf(workItemId),
                state = CloudInputDisposition.RECEIVED,
                createdAtMs = now,
            )).copy(
                localEchoed = true,
                updatedAtMs = now,
                error = null,
            )
            submissionsByRequestId[requestId] = sub
            if (selectedFollowupWorkItemId == workItemId) {
                selectedFollowupWorkItemId = null
            }
            persistLocked()
            publishLocked()
            sub
        }

        return try {
            val receipt = client.stop(
                workItemId = workItemId,
                requestId = initialSubmission.requestId,
                conversationId = initialSubmission.conversationId.takeIf { it.isNotBlank() },
            )
            mutex.withLock {
                localEchoedEventIds.add(receipt.eventId)
                val next = (submissionsByRequestId[requestId] ?: initialSubmission).withReceipt(receipt)
                submissionsByRequestId[requestId] = next
                lastError = null
                persistLocked()
                publishLocked()
                next
            }
        } catch (error: Throwable) {
            handleRequestFailure(requestId, error)
            throw error
        }
    }

    // Only a task result settles the execution outcome. Input receipts acknowledge delivery.
    suspend fun recheckOutcomeUnknownTask(workItemId: String): Boolean {
        pollOnce(reportFailure = true)
        return mutex.withLock { tasksById[workItemId]?.outcomeUnknown != true }
    }

    suspend fun retryOutcomeUnknownTask(
        conversationId: String,
        workItemId: String,
        requestId: String,
    ): CloudDialogueSubmission {
        val (retryId, prompt) = mutex.withLock {
            val task = tasksById[workItemId]
            require(task != null && task.conversationId == conversationId &&
                task.outcomeUnknown && !task.outcomeUnknownAbandoned) { "任务已不再等待结果确认" }
            val messages = submissionsByRequestId.values.filter {
                it.conversationId == conversationId && it.kind == CloudSubmissionKind.MESSAGE
            }
            val original = messages.firstOrNull { workItemId in it.workItemIds }
                ?: messages.singleOrNull { it.topicId == task.topicId }
                ?: error("找不到原始任务指令，请在对话中重新发送")
            // A lost HTTP response or a second tap must replay the same server request.
            val id = task.retryRequestId ?: requestId.also {
                tasksById[workItemId] = task.copy(retryRequestId = it)
                persistLocked()
            }
            id to original.text
        }
        val submission = sendMessage(conversationId, prompt, retryId)
        mutex.withLock {
            val task = tasksById[workItemId]
            if (task?.outcomeUnknown == true && task.retryRequestId == retryId) {
                abandonOutcomeUnknownTaskLocked(task)
            }
        }
        return submission
    }

    suspend fun abandonOutcomeUnknownTask(workItemId: String) = mutex.withLock {
        val existing = tasksById[workItemId] ?: return@withLock
        if (existing.outcomeUnknown) abandonOutcomeUnknownTaskLocked(existing)
    }

    private suspend fun abandonOutcomeUnknownTaskLocked(existing: CloudTaskSession) {
        tasksById[existing.workItemId] = existing.copy(
            active = false,
            outcomeUnknown = false,
            outcomeUnknownAbandoned = true,
            outcome = existing.outcome ?: "abandoned",
            updatedAtMs = System.currentTimeMillis(),
        )
        persistLocked()
        publishLocked()
    }

    suspend fun pollOnce(reportFailure: Boolean = false) {
        val client = clientProvider() ?: if (reportFailure) throw MacGatewayException.Unreachable() else return
        val (currentCursor, boundConversationId) = mutex.withLock { cursor to activeConversationId }
        try {
            val page = client.events(
                after = currentCursor,
                limit = 100,
                conversationId = boundConversationId,
            )
            page.soulRevision?.let { rev ->
                runCatching { onSoulRevision(rev) }
            }
            mutex.withLock {
                var dirty = false
                val sortedItems = page.items.sortedBy { it.seq }
                for (event in sortedItems) {
                    if (event.id in seenEventIds) continue
                    applyEventLocked(event)
                    seenEventIds.add(event.id)
                    dirty = true
                }
                val maxItemSeq = sortedItems.maxOfOrNull { it.seq } ?: 0L
                val nextCursor = maxOf(page.nextCursor, maxItemSeq)
                if (page.items.isNotEmpty() && nextCursor > cursor) {
                    cursor = nextCursor
                    dirty = true
                }
                if (dirty) {
                    trimBoundedSetsLocked()
                    persistLocked()
                    publishLocked()
                }
            }

            val pendingReceiptRequests = mutex.withLock {
                submissionsByRequestId.values
                    .filter { sub ->
                        sub.eventId != null && (
                            sub.state == CloudInputDisposition.RECEIVED ||
                                (sub.kind == CloudSubmissionKind.MESSAGE && !sub.receiptSyncedAfterConsumed)
                            )
                    }
                    .map { it.requestId to it.conversationId }
            }
            var receiptsUpdated = false
            for ((reqId, subConversationId) in pendingReceiptRequests) {
                val receipt = runCatching {
                    client.receipts(
                        requestId = reqId,
                        conversationId = subConversationId.takeIf { it.isNotBlank() } ?: boundConversationId,
                    )
                }.getOrNull() ?: continue
                mutex.withLock {
                    val existing = submissionsByRequestId[reqId] ?: return@withLock
                    val next = existing.withReceipt(receipt)
                    if (next != existing) {
                        submissionsByRequestId[reqId] = next
                        receiptsUpdated = true
                    }
                    if (next.workItemIds.isNotEmpty()) {
                        val before = tasksById.toMap()
                        registerWorkItemsLocked(
                            workItemIds = next.workItemIds,
                            topicId = receipt.topicId,
                            conversationId = next.conversationId,
                            titleHint = existing.text,
                            parentRunId = receipt.parentRunId,
                            executorKind = receipt.executorKind,
                            executorId = receipt.executorId,
                            epoch = receipt.epoch,
                            outcomeUnknown = receipt.outcomeUnknown,
                        )
                        if (tasksById != before) receiptsUpdated = true
                    }
                }
            }
            if (receiptsUpdated) {
                mutex.withLock {
                    persistLocked()
                    publishLocked()
                }
            }
        } catch (error: MacGatewayException.Unauthorized) {
            onUnauthorized()
            if (reportFailure) throw error
        } catch (error: MacGatewayException) {
            mutex.withLock {
                lastError = error.message
                publishLocked()
            }
            if (reportFailure) throw error
        }
    }

    private fun CloudDialogueSubmission.withReceipt(receipt: CloudDialogueReceipt): CloudDialogueSubmission {
        val receivedState = CloudInputDisposition.fromWire(receipt.state)
        val next = copy(
            eventId = receipt.eventId,
            topicId = receipt.topicId,
            conversationId = receipt.conversationId?.takeIf { it.isNotBlank() } ?: conversationId,
            workItemIds = receipt.workItemIds.ifEmpty { workItemIds },
            state = if (state == CloudInputDisposition.RECEIVED) receivedState else state,
            replayed = receipt.replayed,
            receiptSyncedAfterConsumed = receiptSyncedAfterConsumed || receivedState != CloudInputDisposition.RECEIVED,
            outcomeUnknown = outcomeUnknown || receipt.outcomeUnknown,
            error = null,
        )
        return if (next == this) this else next.copy(updatedAtMs = System.currentTimeMillis())
    }

    private suspend fun applyEventLocked(event: CloudDialogueEvent) {
        val submission = causalSubmissionLocked(event)
        val resolvedConvId = event.conversationId?.takeIf(String::isNotBlank)
            ?: submission?.conversationId ?: resolveConversationIdLocked(event.topicId, event.workItemId)
        val now = System.currentTimeMillis()

        when (event.kind) {
            "user_message", "followup" -> {
                if (submission != null) {
                    if (event.topicId.isNotBlank()) topicToConversation[event.topicId] = submission.conversationId
                } else if (event.id !in localEchoedEventIds && resolvedConvId != null && event.text.isNotBlank()) {
                    localEchoedEventIds.add(event.id)
                    timelineSink.appendUserMessage(resolvedConvId, event.text, event.id)
                }
            }

            "stop_requested" -> {
                val workId = event.workItemId ?: return
                val current = tasksById[workId]
                if (current != null && current.active) {
                    tasksById[workId] = current.copy(
                        progressText = event.text.ifBlank { current.progressText },
                        parentRunId = event.parentRunId ?: current.parentRunId,
                        executorKind = event.executorKind ?: current.executorKind,
                        executorId = event.executorId ?: current.executorId,
                        epoch = event.epoch ?: current.epoch,
                        updatedAtMs = now,
                    )
                }
            }

            "stop_too_late" -> {
                val notice = event.text.ifBlank { "任务已完成，停止请求到达过晚" }
                submission?.let { sub ->
                    submissionsByRequestId[sub.requestId] = sub.copy(
                        state = CloudInputDisposition.CANCELLED,
                        stopTooLate = true,
                        statusNote = notice,
                        receiptSyncedAfterConsumed = true,
                        updatedAtMs = now,
                    )
                }
                val workId = event.workItemId
                if (!workId.isNullOrBlank()) {
                    val existing = tasksById[workId]
                    if (existing != null) {
                        tasksById[workId] = existing.copy(
                            stopTooLateNotice = notice,
                            parentRunId = event.parentRunId ?: existing.parentRunId,
                            executorKind = event.executorKind ?: existing.executorKind,
                            executorId = event.executorId ?: existing.executorId,
                            epoch = event.epoch ?: existing.epoch,
                            updatedAtMs = now,
                        )
                    }
                }
            }

            "assistant_reply" -> {
                // The daemon also emits assistant_reply for an early acknowledgement.
                if (!event.id.startsWith("ack:")) {
                    submission?.takeIf { it.state == CloudInputDisposition.RECEIVED }?.let { sub ->
                        submissionsByRequestId[sub.requestId] = sub.copy(state = CloudInputDisposition.CONSUMED, updatedAtMs = now)
                    }
                }
                if (resolvedConvId != null && event.text.isNotBlank()) {
                    timelineSink.appendAssistantReply(
                        conversationId = resolvedConvId,
                        text = event.text,
                        eventId = event.id,
                        topicId = event.topicId,
                    )
                }
            }

            "process_event" -> {
                // Contract: process_event only updates the single-line progress text in-place;
                // never emit per-tool cards or timeline messages.
                val workId = event.workItemId ?: event.topicId
                if (workId.isNotBlank()) {
                    val existing = tasksById[workId]
                    val convId = resolvedConvId ?: existing?.conversationId.orEmpty()
                    val title = existing?.title ?: titleForTopicLocked(event.topicId, workId)
                    tasksById[workId] = (existing ?: CloudTaskSession(
                        workItemId = workId,
                        topicId = event.topicId,
                        conversationId = convId,
                        title = title,
                    )).copy(
                        conversationId = convId,
                        progressText = event.text,
                        processEventCount = (existing?.processEventCount ?: 0) + 1,
                        parentRunId = event.parentRunId ?: existing?.parentRunId,
                        executorKind = event.executorKind ?: existing?.executorKind,
                        executorId = event.executorId ?: existing?.executorId,
                        epoch = event.epoch ?: existing?.epoch,
                        updatedAtMs = now,
                    )
                }
            }

            "input_consumed" -> {
                submission?.let { sub ->
                    submissionsByRequestId[sub.requestId] = sub.copy(
                        state = CloudInputDisposition.CONSUMED,
                        receiptSyncedAfterConsumed = true,
                        updatedAtMs = now,
                    )
                }
                val workId = event.workItemId
                if (!workId.isNullOrBlank()) {
                    val existing = tasksById[workId]
                    if (existing != null && existing.active) {
                        tasksById[workId] = existing.copy(
                            progressText = event.text.ifBlank { existing.progressText },
                            parentRunId = event.parentRunId ?: existing.parentRunId,
                            executorKind = event.executorKind ?: existing.executorKind,
                            executorId = event.executorId ?: existing.executorId,
                            epoch = event.epoch ?: existing.epoch,
                            updatedAtMs = now,
                        )
                    }
                }
            }

            "input_cancelled" -> {
                submission?.let { sub ->
                    submissionsByRequestId[sub.requestId] = sub.copy(
                        state = CloudInputDisposition.CANCELLED,
                        receiptSyncedAfterConsumed = true,
                        updatedAtMs = now,
                    )
                }
            }

            "task_result" -> {
                val workId = event.workItemId ?: event.topicId
                val existing = tasksById[workId]
                val convId = resolvedConvId ?: existing?.conversationId?.takeIf(String::isNotBlank)
                val title = existing?.title ?: titleForTopicLocked(event.topicId, workId)
                val outcome = event.outcome ?: if (event.outcomeUnknown) "outcome_unknown" else "completed"
                val mergedRefs = (existing?.sourceRefs.orEmpty() + event.sourceRefs.orEmpty())
                    .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                val mergedPaths = (existing?.sourcePaths.orEmpty() + event.sourcePaths.orEmpty())
                    .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (workId.isNotBlank()) {
                    tasksById[workId] = (existing ?: CloudTaskSession(
                        workItemId = workId,
                        topicId = event.topicId,
                        conversationId = convId.orEmpty(),
                        title = title,
                    )).copy(
                        conversationId = convId.orEmpty(),
                        active = false,
                        outcome = outcome,
                        resultText = event.text,
                        progressText = null,
                        outcomeUnknown = event.outcomeUnknown,
                        outcomeUnknownAbandoned = existing?.outcomeUnknownAbandoned ?: false,
                        sourceRefs = mergedRefs,
                        sourcePaths = mergedPaths,
                        parentRunId = event.parentRunId ?: existing?.parentRunId,
                        executorKind = event.executorKind ?: existing?.executorKind,
                        executorId = event.executorId ?: existing?.executorId,
                        epoch = event.epoch ?: existing?.epoch,
                        updatedAtMs = now,
                    )
                    if (selectedFollowupWorkItemId == workId) {
                        selectedFollowupWorkItemId = null
                    }
                    if (outcome == "cancelled") {
                        submissionsByRequestId.entries.toList().forEach { (reqId, sub) ->
                            if (sub.kind == CloudSubmissionKind.FOLLOWUP &&
                                sub.targetWorkItemId == workId &&
                                sub.state == CloudInputDisposition.RECEIVED
                            ) {
                                submissionsByRequestId[reqId] = sub.copy(
                                    state = CloudInputDisposition.CANCELLED,
                                    receiptSyncedAfterConsumed = true,
                                    updatedAtMs = now,
                                )
                            }
                        }
                    }
                }
                if (convId != null) {
                    timelineSink.appendTaskResult(
                        conversationId = convId,
                        workItemId = workId,
                        taskTitle = title,
                        outcome = outcome,
                        text = event.text,
                        eventId = event.id,
                        topicId = event.topicId,
                        sourceRefs = mergedRefs,
                        sourcePaths = mergedPaths,
                        outcomeUnknown = event.outcomeUnknown,
                    )
                }
            }

            else -> {
                // Unknown Contract v1 event kinds (including future extensions) are safely skipped
                // so the seen-set and cursor still advance without stalling polling.
                return
            }
        }
        if (resolvedConvId != null && event.topicId.isNotBlank()) {
            topicToConversation[event.topicId] = resolvedConvId
        }
    }

    private fun resolveConversationIdLocked(
        topicId: String,
        workItemId: String?,
    ): String? {
        if (topicId.isNotBlank()) {
            topicToConversation[topicId]?.let { return it }
            submissionsByRequestId.values.firstOrNull { it.topicId == topicId }?.conversationId?.let { return it }
        }
        if (!workItemId.isNullOrBlank()) {
            tasksById[workItemId]?.conversationId?.takeIf { it.isNotBlank() }?.let { return it }
            submissionsByRequestId.values.firstOrNull {
                it.targetWorkItemId == workItemId || workItemId in it.workItemIds
            }?.conversationId?.let { return it }
        }
        return activeConversationId
    }

    private fun titleForTopicLocked(topicId: String, fallback: String): String {
        val messageText = submissionsByRequestId.values
            .firstOrNull { it.kind == CloudSubmissionKind.MESSAGE && it.topicId == topicId }
            ?.text
        return normalizedTitle(messageText, fallback)
    }

    private fun registerWorkItemsLocked(
        workItemIds: List<String>,
        topicId: String,
        conversationId: String,
        titleHint: String,
        parentRunId: String? = null,
        executorKind: String? = null,
        executorId: String? = null,
        epoch: Long? = null,
        outcomeUnknown: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        workItemIds.forEach { workId ->
            if (workId.isBlank()) return@forEach
            val existing = tasksById[workId]
            if (existing == null) {
                tasksById[workId] = CloudTaskSession(
                    workItemId = workId,
                    topicId = topicId,
                    conversationId = conversationId,
                    title = normalizedTitle(titleHint, workId),
                    active = !outcomeUnknown,
                    outcomeUnknown = outcomeUnknown,
                    parentRunId = parentRunId,
                    executorKind = executorKind,
                    executorId = executorId,
                    epoch = epoch,
                    updatedAtMs = now,
                )
            } else {
                tasksById[workId] = existing.copy(
                    conversationId = existing.conversationId.ifBlank { conversationId },
                    parentRunId = parentRunId ?: existing.parentRunId,
                    executorKind = executorKind ?: existing.executorKind,
                    executorId = executorId ?: existing.executorId,
                    epoch = epoch ?: existing.epoch,
                    outcomeUnknown = existing.outcomeUnknown || (existing.active && outcomeUnknown),
                    active = existing.active && !outcomeUnknown,
                )
            }
        }
    }

    /**
     * Resolves the submission caused by [event] using Contract v1 causal precedence:
     * 1. `causedByInputId` matching `submission.eventId`
     * 2. `causedByRequestId` matching `submission.requestId`
     * 3. Legacy prefix fallback on `event.id` (`consumed:<id>`, `cancelled:<id>`, `stop_too_late:<id>`, `reply:<id>:...`)
     */
    private fun causalSubmissionLocked(event: CloudDialogueEvent): CloudDialogueSubmission? {
        val inputId = event.causedByInputId?.takeIf(String::isNotBlank)
        fun findInput(id: String) = submissionsByRequestId.values.firstOrNull { it.eventId == id }
        if (inputId != null) findInput(inputId)?.let { return it }

        // requestId is device-scoped on the server. A known, different input ID cannot belong
        // to this submission even if another paired device reused its request ID.
        event.causedByRequestId?.let(submissionsByRequestId::get)?.takeIf {
            (inputId == null || it.eventId == null || it.eventId == inputId) &&
                (event.conversationId.isNullOrBlank() || it.conversationId == event.conversationId)
        }?.let { return it }
        if (inputId != null || !event.causedByRequestId.isNullOrBlank()) return null

        fun afterPrefix(prefix: String) = event.id.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
        val legacyId = when (event.kind) {
            "user_message", "followup", "stop_requested" -> event.id
            "input_consumed" -> afterPrefix("consumed:")
            "input_cancelled" -> afterPrefix("cancelled:")
            "stop_too_late" -> afterPrefix("stop_too_late:")
            "assistant_reply" -> afterPrefix("reply:")?.substringBeforeLast(':')
            else -> null
        }
        return legacyId?.takeIf(String::isNotBlank)?.let(::findInput)
    }

    private suspend fun handleRequestFailure(requestId: String, error: Throwable) {
        if (error is MacGatewayException.Unauthorized) {
            onUnauthorized()
            return
        }
        mutex.withLock {
            val existing = submissionsByRequestId[requestId]
            if (existing != null) {
                submissionsByRequestId[requestId] = existing.copy(
                    error = error.message ?: error.javaClass.simpleName,
                    updatedAtMs = System.currentTimeMillis(),
                )
            }
            lastError = error.message ?: error.javaClass.simpleName
            persistLocked()
            publishLocked()
        }
    }

    private fun trimBoundedSetsLocked() {
        while (seenEventIds.size > MAX_TRACKED_EVENT_IDS) {
            val first = seenEventIds.firstOrNull() ?: break
            seenEventIds.remove(first)
        }
        while (localEchoedEventIds.size > MAX_TRACKED_EVENT_IDS) {
            val first = localEchoedEventIds.firstOrNull() ?: break
            localEchoedEventIds.remove(first)
        }
    }

    private fun clearSessionLocked() {
        gatewayId = ""
        deviceId = ""
        selectedFollowupWorkItemId = null
        cursor = 0L
        seenEventIds.clear()
        localEchoedEventIds.clear()
        topicToConversation.clear()
        tasksById.clear()
        submissionsByRequestId.clear()
        lastError = null
    }

    private suspend fun persistLocked() {
        if (gatewayId.isBlank() || deviceId.isBlank()) return
        persistState(
            CloudDialoguePersistedState(
                gatewayId = gatewayId,
                deviceId = deviceId,
                cursor = cursor,
                seenEventIds = seenEventIds.toList(),
                localEchoedEventIds = localEchoedEventIds.toList(),
                topicToConversation = topicToConversation.toMap(),
                tasks = tasksById.values.toList(),
                submissions = submissionsByRequestId.values.toList(),
            ),
        )
    }

    private fun publishLocked() {
        onStateChanged(
            CloudDialogueUiState(
                enabled = enabled,
                activeConversationId = activeConversationId,
                cursor = cursor,
                selectedFollowupWorkItemId = selectedFollowupWorkItemId,
                tasks = tasksById.values.sortedByDescending { it.updatedAtMs },
                submissions = submissionsByRequestId.values.sortedByDescending { it.createdAtMs },
                lastError = lastError,
            ),
        )
    }

    private companion object {
        const val MAX_TRACKED_EVENT_IDS = 1_000

        fun normalizedTitle(raw: String?, fallback: String): String {
            val line = raw
                ?.lineSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotEmpty() }
                ?.take(48)
            return if (!line.isNullOrBlank()) line else fallback
        }
    }
}
