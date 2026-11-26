package app.amber.feature.macgateway

import app.amber.agent.data.db.entity.ProactiveCardEntity
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

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

const val PROACTIVE_MESSAGE_KIND = "proactive_message"

/**
 * Structured metadata attached to `proactive_message` dialogue events (frozen contract §3.5 / §4.2).
 * Only [signalId], [topicKey], [category], and [title] are required; all other fields have defaults.
 */
@Serializable
data class ProactiveEventMeta(
    val signalId: String,
    val topicKey: String,
    val category: String,
    val title: String,
    val urgency: String = "normal",
    val silent: Boolean = false,
    val sourceLabel: String = "",
    val actions: List<String> = listOf("act", "snooze", "mute"),
    val checkPrompt: String? = null,
    val wakeupId: String? = null,
    val originSignalId: String? = null,
)

/**
 * Tolerates malformed or partially missing `proactive` JSON objects so a single bad proactive card
 * never fails decoding of the entire `/v2/dialogue/events` page.
 */
object LenientProactiveEventMetaSerializer : KSerializer<ProactiveEventMeta?> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ProactiveEventMeta?) {
        if (encoder is JsonEncoder) {
            if (value == null) {
                encoder.encodeJsonElement(JsonNull)
            } else {
                encoder.encodeJsonElement(
                    encoder.json.encodeToJsonElement(ProactiveEventMeta.serializer(), value),
                )
            }
        } else if (value == null) {
            encoder.encodeString("")
        } else {
            ProactiveEventMeta.serializer().serialize(encoder, value)
        }
    }

    override fun deserialize(decoder: Decoder): ProactiveEventMeta? {
        if (decoder !is JsonDecoder) {
            return runCatching { ProactiveEventMeta.serializer().deserialize(decoder) }.getOrNull()
        }
        val element = decoder.decodeJsonElement()
        if (element is JsonNull || element !is JsonObject) return null
        return runCatching {
            decoder.json.decodeFromJsonElement(ProactiveEventMeta.serializer(), element)
        }.getOrNull()
    }
}

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
    @Serializable(with = LenientProactiveEventMetaSerializer::class)
    val proactive: ProactiveEventMeta? = null,
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
    private val persistScope: CoroutineScope? = null,
    private val persistCoalesceWindowMs: Long = DEFAULT_PERSIST_COALESCE_WINDOW_MS,
    private val maxSettledTasks: Int = DEFAULT_MAX_SETTLED_TASKS,
    private val maxSettledSubmissions: Int = DEFAULT_MAX_SETTLED_SUBMISSIONS,
    private val maxTopicMappings: Int = DEFAULT_MAX_TOPIC_MAPPINGS,
    private val onProactiveCard: (suspend (ProactiveCardEntity) -> Unit)? = null,
    private val connectionGenerationProvider: (() -> Long)? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val onDiagnostic: (String) -> Unit = { message ->
        runCatching { android.util.Log.w("CloudDialogue", message) }
    },
) {
    private data class PendingPersist(
        val sessionEpoch: Long,
        val revision: Long,
        val state: CloudDialoguePersistedState,
    )

    private data class PollGeneration(
        val sessionEpoch: Long,
        val externalGeneration: Long?,
        val gatewayId: String,
        val deviceId: String,
    )

    private sealed interface ApplyEventOutcome {
        data object Applied : ApplyEventOutcome
        data object AppliedProactive : ApplyEventOutcome
        data object SkippedMalformedProactive : ApplyEventOutcome
        data object StaleGeneration : ApplyEventOutcome
        data class ProactivePersistFailed(val error: Throwable) : ApplyEventOutcome
    }

    private data class PollPageResult(
        val pendingPersist: PendingPersist?,
        val immediatePersist: Boolean,
        val staleGeneration: Boolean,
        val proactivePersistFailure: Throwable?,
    )

    private val mutex = Mutex()
    private val persistWriteMutex = Mutex()
    private val coalesceLock = Any()
    private val sessionEpoch = AtomicLong(0L)
    private val latestPendingPersist = AtomicReference<PendingPersist?>(null)
    private var persistRevision: Long = 0L
    private var lastPersistedRevision: Long = 0L
    private var coalesceTimerPending: Boolean = false
    private var coalesceJob: Job? = null
    private val fallbackPersistScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    private val effectivePersistScope: CoroutineScope
        get() = persistScope ?: fallbackPersistScope

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
    private var lastMonotonicTimestampMs: Long = 0L

    private fun effectiveProactiveCardStore(): (suspend (ProactiveCardEntity) -> Unit)? =
        onProactiveCard ?: defaultProactiveCardStore.get()

    private fun effectiveConnectionGeneration(): Long? =
        (connectionGenerationProvider ?: defaultConnectionGenerationProvider.get())?.invoke()

    private fun capturePollGenerationLocked(): PollGeneration =
        PollGeneration(
            sessionEpoch = sessionEpoch.get(),
            externalGeneration = effectiveConnectionGeneration(),
            gatewayId = gatewayId,
            deviceId = deviceId,
        )

    private fun isGenerationCurrentFast(captured: PollGeneration): Boolean =
        sessionEpoch.get() == captured.sessionEpoch &&
            effectiveConnectionGeneration() == captured.externalGeneration

    private fun isGenerationCurrentLocked(captured: PollGeneration): Boolean =
        isGenerationCurrentFast(captured) &&
            gatewayId == captured.gatewayId &&
            deviceId == captured.deviceId

    private fun nextMonotonicTimestampLocked(): Long {
        val next = maxOf(nowMs(), lastMonotonicTimestampMs + 1L)
        lastMonotonicTimestampMs = next
        return next
    }

    suspend fun restore(
        enabled: Boolean,
        connection: MacGatewayConnection?,
        persisted: CloudDialoguePersistedState?,
    ) = mutex.withLock {
        sessionEpoch.incrementAndGet()
        latestPendingPersist.set(null)
        cancelCoalescedPersist()
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
        val (initialSubmission, initialPending) = mutex.withLock {
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
            val pending = capturePersistLocked()
            publishLocked()
            if (!echoed) {
                timelineSink.appendUserMessage(conversationId, text, eventId = sub.eventId)
            }
            sub to pending
        }
        commitPersist(initialPending, immediate = true)

        return try {
            val receipt = client.messages(
                requestId = initialSubmission.requestId,
                text = initialSubmission.text,
                conversationId = conversationId,
            )
            val (updated, receiptPending) = mutex.withLock {
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
                val pending = capturePersistLocked()
                publishLocked()
                next to pending
            }
            commitPersist(receiptPending, immediate = true)
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
        val (initialSubmission, initialPending) = mutex.withLock {
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
            val pending = capturePersistLocked()
            publishLocked()
            if (!echoed) {
                timelineSink.appendUserMessage(targetConv, text, eventId = sub.eventId)
            }
            sub to pending
        }
        commitPersist(initialPending, immediate = true)

        return try {
            val receipt = client.inputs(
                workItemId = workItemId,
                requestId = initialSubmission.requestId,
                text = initialSubmission.text,
                conversationId = initialSubmission.conversationId,
            )
            val (updated, receiptPending) = mutex.withLock {
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
                val pending = capturePersistLocked()
                publishLocked()
                next to pending
            }
            commitPersist(receiptPending, immediate = true)
            updated
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
        val (initialSubmission, initialPending) = mutex.withLock {
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
            val pending = capturePersistLocked()
            publishLocked()
            sub to pending
        }
        commitPersist(initialPending, immediate = true)

        return try {
            val receipt = client.stop(
                workItemId = workItemId,
                requestId = initialSubmission.requestId,
                conversationId = initialSubmission.conversationId.takeIf { it.isNotBlank() },
            )
            val (updated, receiptPending) = mutex.withLock {
                localEchoedEventIds.add(receipt.eventId)
                val next = (submissionsByRequestId[requestId] ?: initialSubmission).withReceipt(receipt)
                submissionsByRequestId[requestId] = next
                lastError = null
                val pending = capturePersistLocked()
                publishLocked()
                next to pending
            }
            commitPersist(receiptPending, immediate = true)
            updated
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
        val (retryId, prompt, retryPending) = mutex.withLock {
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
            var pending: PendingPersist? = null
            val id = task.retryRequestId ?: requestId.also {
                tasksById[workItemId] = task.copy(retryRequestId = it)
                pending = capturePersistLocked()
            }
            Triple(id, original.text, pending)
        }
        commitPersist(retryPending, immediate = true)
        val submission = sendMessage(conversationId, prompt, retryId)
        val abandonPending = mutex.withLock {
            val task = tasksById[workItemId]
            if (task?.outcomeUnknown == true && task.retryRequestId == retryId) {
                abandonOutcomeUnknownTaskLocked(task)
            } else {
                null
            }
        }
        commitPersist(abandonPending, immediate = true)
        return submission
    }

    suspend fun abandonOutcomeUnknownTask(workItemId: String) {
        val pending = mutex.withLock {
            val existing = tasksById[workItemId] ?: return@withLock null
            if (existing.outcomeUnknown) abandonOutcomeUnknownTaskLocked(existing) else null
        }
        commitPersist(pending, immediate = true)
    }

    private fun abandonOutcomeUnknownTaskLocked(existing: CloudTaskSession): PendingPersist? {
        tasksById[existing.workItemId] = existing.copy(
            active = false,
            outcomeUnknown = false,
            outcomeUnknownAbandoned = true,
            outcome = existing.outcome ?: "abandoned",
            updatedAtMs = System.currentTimeMillis(),
        )
        val pending = capturePersistLocked()
        publishLocked()
        return pending
    }

    suspend fun pollOnce(reportFailure: Boolean = false) {
        val client = clientProvider() ?: if (reportFailure) throw MacGatewayException.Unreachable() else return
        val (currentCursor, boundConversationId, pollGen) = mutex.withLock {
            Triple(cursor, activeConversationId, capturePollGenerationLocked())
        }
        try {
            val page = client.events(
                after = currentCursor,
                limit = 100,
                conversationId = boundConversationId,
            )
            if (!isGenerationCurrentFast(pollGen)) return
            val hasOnlyProactiveItems = page.items.isNotEmpty() &&
                page.items.all { it.kind == PROACTIVE_MESSAGE_KIND }
            if (!hasOnlyProactiveItems) {
                page.soulRevision?.let { rev ->
                    if (isGenerationCurrentFast(pollGen)) {
                        runCatching { onSoulRevision(rev) }
                    }
                }
            }
            val pageResult = mutex.withLock {
                if (!isGenerationCurrentLocked(pollGen)) {
                    return@withLock PollPageResult(
                        pendingPersist = null,
                        immediatePersist = false,
                        staleGeneration = true,
                        proactivePersistFailure = null,
                    )
                }
                var dirty = false
                var durableBarrier = false
                var stale = false
                var proactiveFailure: Throwable? = null
                var lastAppliedSeq = cursor
                val sortedItems = page.items.sortedBy { it.seq }
                for (event in sortedItems) {
                    if (!isGenerationCurrentLocked(pollGen)) {
                        stale = true
                        break
                    }
                    if (event.id in seenEventIds) {
                        if (event.seq > lastAppliedSeq) lastAppliedSeq = event.seq
                        continue
                    }
                    when (val outcome = applyEventLocked(event, pollGen)) {
                        ApplyEventOutcome.Applied -> {
                            seenEventIds.add(event.id)
                            if (event.seq > lastAppliedSeq) lastAppliedSeq = event.seq
                            dirty = true
                            if (isDurableBarrierEvent(event)) {
                                durableBarrier = true
                            }
                        }

                        ApplyEventOutcome.AppliedProactive -> {
                            if (!isGenerationCurrentLocked(pollGen)) {
                                stale = true
                                break
                            }
                            seenEventIds.add(event.id)
                            if (event.seq > lastAppliedSeq) lastAppliedSeq = event.seq
                            dirty = true
                            durableBarrier = true
                        }

                        ApplyEventOutcome.SkippedMalformedProactive -> {
                            seenEventIds.add(event.id)
                            if (event.seq > lastAppliedSeq) lastAppliedSeq = event.seq
                            dirty = true
                        }

                        ApplyEventOutcome.StaleGeneration -> {
                            stale = true
                            break
                        }

                        is ApplyEventOutcome.ProactivePersistFailed -> {
                            proactiveFailure = outcome.error
                            lastError = outcome.error.message ?: outcome.error.javaClass.simpleName
                            break
                        }
                    }
                }
                if (stale || !isGenerationCurrentLocked(pollGen)) {
                    return@withLock PollPageResult(
                        pendingPersist = null,
                        immediatePersist = false,
                        staleGeneration = true,
                        proactivePersistFailure = null,
                    )
                }
                if (proactiveFailure == null) {
                    val maxItemSeq = sortedItems.maxOfOrNull { it.seq } ?: 0L
                    val nextCursor = maxOf(page.nextCursor, maxItemSeq)
                    if (page.items.isNotEmpty() && nextCursor > cursor) {
                        cursor = nextCursor
                        dirty = true
                    }
                } else {
                    if (lastAppliedSeq > cursor) {
                        cursor = lastAppliedSeq
                        dirty = true
                    }
                    publishLocked()
                }
                if (dirty) {
                    val pending = capturePersistLocked()
                    publishLocked()
                    PollPageResult(
                        pendingPersist = pending,
                        immediatePersist = durableBarrier || proactiveFailure != null,
                        staleGeneration = false,
                        proactivePersistFailure = proactiveFailure,
                    )
                } else {
                    PollPageResult(
                        pendingPersist = null,
                        immediatePersist = false,
                        staleGeneration = false,
                        proactivePersistFailure = proactiveFailure,
                    )
                }
            }
            if (pageResult.staleGeneration) return
            commitPersist(pageResult.pendingPersist, immediate = pageResult.immediatePersist)
            pageResult.proactivePersistFailure?.let { failure ->
                if (reportFailure) throw failure
                return
            }

            if (!isGenerationCurrentFast(pollGen)) return
            val pendingReceiptRequests = mutex.withLock {
                if (!isGenerationCurrentLocked(pollGen)) return@withLock emptyList()
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
            var receiptsDurable = false
            for ((reqId, subConversationId) in pendingReceiptRequests) {
                if (!isGenerationCurrentFast(pollGen)) return
                val receipt = runCatching {
                    client.receipts(
                        requestId = reqId,
                        conversationId = subConversationId.takeIf { it.isNotBlank() } ?: boundConversationId,
                    )
                }.getOrNull() ?: continue
                if (!isGenerationCurrentFast(pollGen)) return
                mutex.withLock {
                    if (!isGenerationCurrentLocked(pollGen)) return@withLock
                    val existing = submissionsByRequestId[reqId] ?: return@withLock
                    val next = existing.withReceipt(receipt)
                    if (next != existing) {
                        submissionsByRequestId[reqId] = next
                        receiptsUpdated = true
                        if (next.state != CloudInputDisposition.RECEIVED || next.outcomeUnknown) {
                            receiptsDurable = true
                        }
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
                        if (tasksById != before) {
                            receiptsUpdated = true
                            receiptsDurable = true
                        }
                    }
                }
            }
            if (receiptsUpdated && isGenerationCurrentFast(pollGen)) {
                val receiptsPending = mutex.withLock {
                    if (!isGenerationCurrentLocked(pollGen)) return@withLock null
                    val pending = capturePersistLocked()
                    publishLocked()
                    pending
                }
                commitPersist(receiptsPending, immediate = receiptsDurable)
            }
        } catch (error: MacGatewayException.Unauthorized) {
            if (isGenerationCurrentFast(pollGen)) {
                onUnauthorized()
                if (reportFailure) throw error
            }
        } catch (error: MacGatewayException) {
            if (isGenerationCurrentFast(pollGen)) {
                mutex.withLock {
                    if (isGenerationCurrentLocked(pollGen)) {
                        lastError = error.message
                        publishLocked()
                    }
                }
                if (reportFailure) throw error
            }
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

    private suspend fun applyEventLocked(
        event: CloudDialogueEvent,
        pollGen: PollGeneration,
    ): ApplyEventOutcome {
        // Intercept proactive_message before any causal submission, conversation resolution,
        // topic mapping, timeline sink, receipt consumption, waiting state, or soul handling.
        if (event.kind == PROACTIVE_MESSAGE_KIND) {
            return handleProactiveEventLocked(event, pollGen)
        }

        val submission = causalSubmissionLocked(event)
        val resolvedConvId = event.conversationId?.takeIf(String::isNotBlank)
            ?: submission?.conversationId ?: resolveConversationIdLocked(event.topicId, event.workItemId)
        val now = nextMonotonicTimestampLocked()

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
                val workId = event.workItemId ?: return ApplyEventOutcome.Applied
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
                return ApplyEventOutcome.Applied
            }
        }
        if (resolvedConvId != null && event.topicId.isNotBlank()) {
            topicToConversation[event.topicId] = resolvedConvId
        }
        return ApplyEventOutcome.Applied
    }

    private suspend fun handleProactiveEventLocked(
        event: CloudDialogueEvent,
        pollGen: PollGeneration,
    ): ApplyEventOutcome {
        if (!isGenerationCurrentLocked(pollGen)) return ApplyEventOutcome.StaleGeneration
        val meta = event.proactive
        if (meta == null ||
            !ProactiveContract.isValidSignalId(meta.signalId) ||
            meta.topicKey.isBlank() ||
            meta.category.isBlank() ||
            meta.title.isBlank()
        ) {
            onDiagnostic("Skipped malformed proactive_message event seq=${event.seq} id=${event.id}")
            return ApplyEventOutcome.SkippedMalformedProactive
        }
        if (gatewayId.isBlank() || deviceId.isBlank()) {
            onDiagnostic("Skipped proactive_message without active gateway session seq=${event.seq} id=${event.id}")
            return ApplyEventOutcome.SkippedMalformedProactive
        }
        val entity = ProactiveCardEntity(
            gatewayId = gatewayId,
            deviceId = deviceId,
            eventId = event.id.ifBlank { "proactive:${meta.signalId}" },
            signalId = meta.signalId,
            seq = event.seq,
            text = event.text.take(ProactiveContract.MAX_CARD_TEXT_LENGTH),
            metaJson = MacGatewayJson.encodeToString(ProactiveEventMeta.serializer(), meta),
            receivedAt = nextMonotonicTimestampLocked(),
            notifiedAt = null,
            feedbackState = null,
        )
        val store = effectiveProactiveCardStore()
        if (store != null) {
            if (!isGenerationCurrentLocked(pollGen)) return ApplyEventOutcome.StaleGeneration
            try {
                store(entity)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onDiagnostic("Failed to persist proactive_message seq=${event.seq} id=${event.id}: ${error.javaClass.simpleName}")
                return ApplyEventOutcome.ProactivePersistFailed(error)
            }
        }
        return ApplyEventOutcome.AppliedProactive
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
        val pending = mutex.withLock {
            val existing = submissionsByRequestId[requestId]
            if (existing != null) {
                submissionsByRequestId[requestId] = existing.copy(
                    error = error.message ?: error.javaClass.simpleName,
                    updatedAtMs = System.currentTimeMillis(),
                )
            }
            lastError = error.message ?: error.javaClass.simpleName
            val snapshot = capturePersistLocked()
            publishLocked()
            snapshot
        }
        commitPersist(pending, immediate = true)
    }

    private fun isDurableBarrierEvent(event: CloudDialogueEvent): Boolean = when (event.kind) {
        "user_message",
        "followup",
        "stop_requested",
        "stop_too_late",
        "assistant_reply",
        "input_consumed",
        "input_cancelled",
        "task_result",
        PROACTIVE_MESSAGE_KIND,
        -> true
        else -> false
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
        pruneSettledHistoryLocked()
    }

    /**
     * Prunes settled history beyond the replay window while strictly preserving every
     * unsettled, unconfirmed, or `outcomeUnknown` task/submission (and any submission or
     * topic mapping referenced by a protected task).
     */
    private fun pruneSettledHistoryLocked() {
        val protectedTaskIds = HashSet<String>()
        val protectedTaskTopics = HashSet<String>()
        tasksById.values.forEach { task ->
            val isProtected = task.active ||
                (task.outcomeUnknown && !task.outcomeUnknownAbandoned) ||
                task.workItemId == selectedFollowupWorkItemId
            if (isProtected) {
                protectedTaskIds.add(task.workItemId)
                if (task.topicId.isNotBlank()) {
                    protectedTaskTopics.add(task.topicId)
                }
            }
        }

        val protectedSubmissionIds = HashSet<String>()
        submissionsByRequestId.values.forEach { sub ->
            val isProtected = sub.state == CloudInputDisposition.RECEIVED ||
                !sub.receiptSyncedAfterConsumed ||
                sub.eventId == null ||
                sub.outcomeUnknown ||
                (sub.targetWorkItemId != null && sub.targetWorkItemId in protectedTaskIds) ||
                sub.workItemIds.any { it in protectedTaskIds } ||
                (!sub.topicId.isNullOrBlank() && sub.topicId in protectedTaskTopics)
            if (isProtected) {
                protectedSubmissionIds.add(sub.requestId)
                sub.targetWorkItemId?.takeIf { it.isNotBlank() }?.let(protectedTaskIds::add)
                sub.workItemIds.filter { it.isNotBlank() }.forEach(protectedTaskIds::add)
            }
        }

        if (maxSettledTasks >= 0) {
            val settledTasks = tasksById.values.filter { it.workItemId !in protectedTaskIds }
            val excessTasks = settledTasks.size - maxSettledTasks
            if (excessTasks > 0) {
                settledTasks
                    .sortedBy { it.updatedAtMs }
                    .take(excessTasks)
                    .forEach { tasksById.remove(it.workItemId) }
            }
        }

        if (maxSettledSubmissions >= 0) {
            val settledSubmissions = submissionsByRequestId.values.filter { it.requestId !in protectedSubmissionIds }
            val excessSubmissions = settledSubmissions.size - maxSettledSubmissions
            if (excessSubmissions > 0) {
                settledSubmissions
                    .sortedBy { it.updatedAtMs }
                    .take(excessSubmissions)
                    .forEach { submissionsByRequestId.remove(it.requestId) }
            }
        }

        if (maxTopicMappings >= 0 && topicToConversation.size > maxTopicMappings) {
            val referencedTopics = HashSet<String>()
            tasksById.values.forEach { if (it.topicId.isNotBlank()) referencedTopics.add(it.topicId) }
            submissionsByRequestId.values.forEach { sub ->
                sub.topicId?.takeIf { it.isNotBlank() }?.let(referencedTopics::add)
            }
            val removable = topicToConversation.keys.filter { it !in referencedTopics }
            var toRemove = topicToConversation.size - maxTopicMappings
            for (topic in removable) {
                if (toRemove <= 0) break
                topicToConversation.remove(topic)
                toRemove--
            }
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

    private fun capturePersistLocked(): PendingPersist? {
        trimBoundedSetsLocked()
        if (gatewayId.isBlank() || deviceId.isBlank()) return null
        val snapshot = CloudDialoguePersistedState(
            gatewayId = gatewayId,
            deviceId = deviceId,
            cursor = cursor,
            seenEventIds = seenEventIds.toList(),
            localEchoedEventIds = localEchoedEventIds.toList(),
            topicToConversation = topicToConversation.toMap(),
            tasks = tasksById.values.toList(),
            submissions = submissionsByRequestId.values.toList(),
        )
        val pending = PendingPersist(
            sessionEpoch = sessionEpoch.get(),
            revision = ++persistRevision,
            state = snapshot,
        )
        latestPendingPersist.set(pending)
        return pending
    }

    private suspend fun commitPersist(pending: PendingPersist?, immediate: Boolean) {
        if (pending == null) return
        if (immediate || persistCoalesceWindowMs <= 0L) {
            cancelCoalescedPersist()
            flushPendingPersist()
        } else {
            scheduleCoalescedPersist()
        }
    }

    private fun scheduleCoalescedPersist() {
        synchronized(coalesceLock) {
            if (coalesceTimerPending) return
            coalesceTimerPending = true
            coalesceJob = effectivePersistScope.launch {
                try {
                    delay(persistCoalesceWindowMs)
                } finally {
                    synchronized(coalesceLock) {
                        coalesceTimerPending = false
                    }
                }
                runCatching { flushPendingPersist() }
            }
        }
    }

    private fun cancelCoalescedPersist() {
        synchronized(coalesceLock) {
            coalesceTimerPending = false
            coalesceJob?.cancel()
            coalesceJob = null
        }
    }

    internal suspend fun flushPendingPersist() {
        persistWriteMutex.withLock {
            val candidate = latestPendingPersist.get() ?: return@withLock
            if (candidate.sessionEpoch != sessionEpoch.get()) return@withLock
            if (candidate.revision <= lastPersistedRevision) return@withLock
            persistState(candidate.state)
            lastPersistedRevision = candidate.revision
            latestPendingPersist.compareAndSet(candidate, null)
        }
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

    companion object {
        private const val MAX_TRACKED_EVENT_IDS = 1_000
        private const val DEFAULT_PERSIST_COALESCE_WINDOW_MS = 300L
        private const val DEFAULT_MAX_SETTLED_TASKS = 100
        private const val DEFAULT_MAX_SETTLED_SUBMISSIONS = 200
        private const val DEFAULT_MAX_TOPIC_MAPPINGS = 200

        private val defaultProactiveCardStore =
            AtomicReference<(suspend (ProactiveCardEntity) -> Unit)?>(null)
        private val defaultConnectionGenerationProvider =
            AtomicReference<(() -> Long)?>(null)

        fun setDefaultProactiveCardStore(store: (suspend (ProactiveCardEntity) -> Unit)?) {
            defaultProactiveCardStore.set(store)
        }

        fun setDefaultConnectionGenerationProvider(provider: (() -> Long)?) {
            defaultConnectionGenerationProvider.set(provider)
        }

        private fun normalizedTitle(raw: String?, fallback: String): String {
            val line = raw
                ?.lineSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotEmpty() }
                ?.take(48)
            return if (!line.isNullOrBlank()) line else fallback
        }
    }
}
