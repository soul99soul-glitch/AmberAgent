package app.amber.core.service

import android.util.Log
import app.amber.ai.core.InputSchema
import app.amber.ai.core.MessageRole
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.core.sync.core.SyncRestoreWriteGate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "PendingMessageQueue"

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

class PendingMessageQueue(
    private val appScope: CoroutineScope,
    private val pendingMessageStore: PendingMessageStore,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
) {
    private val pendingMessageStoreOps = Channel<PendingMessageStoreOp>(Channel.UNLIMITED)
    private val pendingMessagePersistRevisions = ConcurrentHashMap<Uuid, AtomicLong>()
    private val pendingMessagePersistLocks = ConcurrentHashMap<Uuid, Mutex>()
    private val pendingMessagePersistDispatcher = Dispatchers.IO.limitedParallelism(1)
    private class QueueState(initial: List<PendingUserMessage>) {
        val messages = MutableStateFlow(initial)
        val flow = messages.asStateFlow()
    }
    private val states = ConcurrentHashMap<Uuid, QueueState>()
    private val restoreRegistration = restoreWriteGate?.addRestoreLifecycleListener(
        onStarted = ::invalidatePendingWrites,
        onFinished = { succeeded, dataCommitted ->
            if (succeeded || dataCommitted) {
                states.forEach { (id, state) ->
                    state.messages.value = emptyList()
                    persistPendingMessagesDurably(id, emptyList())
                }
            }
        },
    )

    init {
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
    }

    private fun queueState(conversationId: Uuid): QueueState =
        states.computeIfAbsent(conversationId) { QueueState(pendingMessageStore.load(it)) }

    private fun pendingState(conversationId: Uuid): MutableStateFlow<List<PendingUserMessage>> =
        queueState(conversationId).messages

    fun flow(conversationId: Uuid): StateFlow<List<PendingUserMessage>> = queueState(conversationId).flow

    fun forgetSession(conversationId: Uuid) {
        states.remove(conversationId)
        pendingMessagePersistRevisions.remove(conversationId)
        pendingMessagePersistLocks.remove(conversationId)
    }

    private fun invalidatePendingWrites() {
        pendingMessagePersistRevisions.values.forEach { it.incrementAndGet() }
    }

    fun cleanup() {
        restoreRegistration?.close()
        states.clear()
        pendingMessageStoreOps.close()
        pendingMessagePersistRevisions.clear()
        pendingMessagePersistLocks.clear()
    }

    private fun pendingMessagePersistRevision(conversationId: Uuid): AtomicLong =
        pendingMessagePersistRevisions.computeIfAbsent(conversationId) { AtomicLong(0L) }

    private fun pendingMessagePersistLock(conversationId: Uuid): Mutex =
        pendingMessagePersistLocks.computeIfAbsent(conversationId) { Mutex() }

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

    fun recordPendingMessageEvent(
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

    fun persistCurrentPendingMessagesDurably(
        conversationId: Uuid,
    ) {
        persistPendingMessagesDurably(conversationId, pendingState(conversationId).value)
    }

    suspend fun persistCurrentPendingMessagesNow(
        conversationId: Uuid,
    ) {
        val messages = pendingState(conversationId).value
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


    fun enqueuePendingUserMessage(conversationId: Uuid, message: PendingUserMessage): Boolean {
        val messages = pendingState(conversationId)
        messages.update { current ->
            if (message.mode == PendingUserMessageMode.STEER) {
                val steerPrefixSize = current.indexOfFirst { it.mode != PendingUserMessageMode.STEER }
                    .takeIf { it >= 0 }
                    ?: current.size
                current.take(steerPrefixSize) + message + current.drop(steerPrefixSize)
            } else {
                current + message
            }
        }
        persistPendingMessagesAsync(conversationId, messages.value)
        return true
    }

    private fun dequeueNextPendingUserMessage(conversationId: Uuid): PendingUserMessage? {
        val messages = pendingState(conversationId)
        var next: PendingUserMessage? = null
        messages.update { current ->
            next = current.firstOrNull()
            if (next == null) current else current.drop(1)
        }
        if (next != null) {
            persistPendingMessagesAsync(conversationId, messages.value)
        }
        return next
    }

    private fun dequeueSteerPendingUserMessages(conversationId: Uuid): List<PendingUserMessage> {
        val messages = pendingState(conversationId)
        var consumed = emptyList<PendingUserMessage>()
        messages.update { current ->
            consumed = current.takeWhile { it.mode == PendingUserMessageMode.STEER }
            if (consumed.isEmpty()) current else current.drop(consumed.size)
        }
        if (consumed.isNotEmpty()) {
            persistPendingMessagesAsync(conversationId, messages.value)
        }
        return consumed
    }

    private fun dequeueLeadingCollectableMessages(conversationId: Uuid): List<PendingUserMessage> {
        val messages = pendingState(conversationId)
        var consumed = emptyList<PendingUserMessage>()
        messages.update { current ->
            consumed = current.takeWhile { it.isCollectable }
            if (consumed.isEmpty()) current else current.drop(consumed.size)
        }
        if (consumed.isNotEmpty()) {
            persistPendingMessagesAsync(conversationId, messages.value)
        }
        return consumed
    }

    fun cancelPendingUserMessage(conversationId: Uuid, messageId: String): Boolean {
        val messages = pendingState(conversationId)
        var changed = false
        messages.update { current ->
            val next = current.filterNot { it.id == messageId }
            changed = next.size != current.size
            next
        }
        if (changed) {
            persistPendingMessagesAsync(conversationId, messages.value)
        }
        if (changed) recordPendingMessageEvent(conversationId, event = "cancel", messageId = messageId)
        return changed
    }

    fun movePendingUserMessage(conversationId: Uuid, messageId: String, offset: Int): Boolean {
        val messages = pendingState(conversationId)
        if (offset == 0) return false
        var changed = false
        messages.update { current ->
            val index = current.indexOfFirst { it.id == messageId }
            if (index < 0) {
                current
            } else {
                val target = (index + offset).coerceIn(0, current.lastIndex)
                if (target == index) {
                    current
                } else {
                    changed = true
                    current.toMutableList().also { list ->
                        val item = list.removeAt(index)
                        list.add(target, item)
                    }
                }
            }
        }
        if (changed) {
            persistPendingMessagesAsync(conversationId, messages.value)
        }
        if (changed) recordPendingMessageEvent(conversationId, event = "move", messageId = messageId, detail = offset.toString())
        return changed
    }

    fun clearPendingUserMessages(conversationId: Uuid) {
        val messages = pendingState(conversationId)
        val count = messages.value.size
        if (count == 0) return
        messages.value = emptyList()
        persistPendingMessagesAsync(conversationId, emptyList())
        recordPendingMessageEvent(conversationId, event = "clear", count = count)
    }

    suspend fun dequeueNextPendingUserMessageDurably(conversationId: Uuid): PendingUserMessage? {
        val message = dequeueNextPendingUserMessage(conversationId)
        if (message != null) persistCurrentPendingMessagesNow(conversationId)
        return message
    }

    suspend fun preparePendingMessageForDispatch(
        conversationId: Uuid,
        message: PendingUserMessage,
        displayCopy: PendingUserMessageDisplayCopy,
    ): PendingUserMessage = when {
        message.isCollectable -> {
            val collected = dequeueLeadingCollectableMessages(conversationId)
            if (collected.isNotEmpty()) persistCurrentPendingMessagesNow(conversationId)
            buildCollectedPendingUserMessage(listOf(message) + collected, copy = displayCopy)
        }
        message.mode == PendingUserMessageMode.STEER -> message.asFollowup()
        else -> message
    }

    fun consumeSteerMessagesForRun(conversationId: Uuid): List<UIMessage> {
        val consumed = dequeueSteerPendingUserMessages(conversationId)
        if (consumed.isNotEmpty()) {
            persistCurrentPendingMessagesDurably(conversationId)
            recordPendingMessageEvent(conversationId, event = "steer_consumed", count = consumed.size)
        }
        return consumed.map { UIMessage(role = MessageRole.USER, parts = it.parts) }
    }

    suspend fun takePendingMessageForInput(conversationId: Uuid): List<UIMessagePart>? {
        val message = dequeueNextPendingUserMessageDurably(conversationId) ?: return null
        recordPendingMessageEvent(conversationId, event = "move_to_input", messageId = message.id)
        return message.parts
    }

    fun createTools(conversationId: Uuid, displayCopy: PendingUserMessageDisplayCopy): List<Tool> = listOf(
        Tool(
            name = "conversation_queue_status",
            description = "Read queued user messages for the current conversation. This is read-only and never exposes messages from other conversations.",
            parameters = {
                InputSchema.Obj(properties = buildJsonObject {})
            },
            execute = {
                val queued = flow(conversationId).value
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
                                                message.previewText(copy = displayCopy),
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
                val clearAll = input.jsonObject["clear_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
                val messageId = input.jsonObject["message_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val changed = if (clearAll) {
                    val hadMessages = flow(conversationId).value.isNotEmpty()
                    clearPendingUserMessages(conversationId)
                    hadMessages
                } else {
                    require(messageId.isNotBlank()) { "message_id is required unless clear_all=true" }
                    val hadMessage = flow(conversationId).value.any { it.id == messageId }
                    cancelPendingUserMessage(conversationId, messageId)
                    hadMessage
                }
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("status", if (changed) "cancelled" else "not_found")
                            put("remaining", flow(conversationId).value.size)
                        }.toString()
                    )
                )
            }
        )
    )
}
