package app.amber.feature.chat.impl

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.core.agent.runtime.AgentEventRecord
import app.amber.core.agent.runtime.AgentEventStore
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunRecord
import app.amber.feature.chat.api.ChatEventPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid

/**
 * Bridges the legacy ChatService generation loop into the runtime event
 * store: one synthetic agent run per generation, plus coalesced
 * [ChatEventPayload.StreamCheckpoint] events while the stream is live.
 *
 * If the process dies mid-stream the run stays `running`, so the next
 * launch's replayUnfinished() can mark it interrupted and project the
 * checkpoint back into the conversation. Every graceful finish (complete /
 * cancel / fail) finalizes the run and prunes its checkpoint events — the
 * conversation force-checkpoint already owns the durable content there.
 *
 * All methods swallow store failures: checkpointing must never break or
 * slow down generation.
 */
class ChatStreamCheckpointRecorder(
    private val eventStore: AgentEventStore,
    private val conversationId: String,
    val runId: AgentRunId = AgentRunId.new(),
    private val json: Json = Json,
    private val coalescer: StreamCheckpointCoalescer = StreamCheckpointCoalescer(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val seq = AtomicLong(0L)
    private val finished = AtomicBoolean(false)
    @Volatile
    private var startedAt = 0L

    suspend fun onRunStarted() {
        startedAt = now()
        runCatching {
            eventStore.appendRun(runRecord(status = "running", finishedAt = null, reason = null))
        }
    }

    suspend fun onChunk(messages: List<UIMessage>, streamingTailMessageId: Uuid?) {
        val tail = streamingTailMessageId?.let { id -> messages.lastOrNull { it.id == id } }
            ?: messages.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?: return
        val partsHash = tail.streamPartsHash()
        val charCount = tail.streamContentLength()
        if (!coalescer.offer(now(), tail.id.toString(), partsHash, charCount)) return

        val seqNum = seq.incrementAndGet()
        val payload = ChatEventPayload.StreamCheckpoint(
            conversationId = conversationId,
            messageId = tail.id.toString(),
            partsHash = partsHash,
            toolStates = tail.toolStateSnapshots(),
            lastEventId = if (seqNum > 1) eventId(seqNum - 1) else "",
            charCount = charCount,
        )
        runCatching {
            eventStore.appendEvent(
                AgentEventRecord(
                    eventId = eventId(seqNum),
                    runId = runId.value,
                    parentRunId = null,
                    seq = seqNum,
                    type = ChatEventPayload.StreamCheckpoint.TYPE,
                    payloadType = ChatEventPayload.StreamCheckpoint::class.qualifiedName
                        ?: ChatEventPayload.StreamCheckpoint.TYPE,
                    payload = json.encodeToString(
                        ChatEventPayload.StreamCheckpoint.serializer(),
                        payload,
                    ),
                    payloadSchemaVersion = 1,
                    agentDescriptorId = DESCRIPTOR_ID,
                    agentVersion = AGENT_VERSION,
                    isFinal = true,
                    ts = now(),
                )
            )
        }
    }

    /**
     * Finalize the run. Idempotent — only the first call wins, so the
     * onCompletion / onFailure double-invocation in ChatService is safe.
     * Runs under NonCancellable: a cancelled generation must still be able
     * to finalize its run record.
     */
    suspend fun onRunFinished(cause: Throwable?) {
        if (!finished.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            runCatching {
                when {
                    cause == null ->
                        eventStore.appendRun(runRecord("completed", finishedAt = now(), reason = null))
                    cause is CancellationException ->
                        eventStore.markInterrupted(runId, "cancelled")
                    else ->
                        eventStore.appendRun(
                            runRecord("failed", finishedAt = now(), reason = cause.message?.take(500))
                        )
                }
            }
            // Graceful finishes don't need recovery checkpoints any more —
            // ChatService force-persisted the conversation on this path.
            runCatching {
                eventStore.deleteEventsByType(runId, ChatEventPayload.StreamCheckpoint.TYPE)
            }
        }
    }

    private fun eventId(seqNum: Long): String = "${runId.value}_$seqNum"

    private fun runRecord(status: String, finishedAt: Long?, reason: String?) = AgentRunRecord(
        runId = runId.value,
        parentRunId = null,
        agentDescriptorId = DESCRIPTOR_ID,
        agentVersion = AGENT_VERSION,
        conversationId = conversationId,
        messageNodeId = null,
        producesMessageId = null,
        assistantId = null,
        status = status,
        inputDigest = "",
        inputSnapshotRef = null,
        inputSchemaVersion = 1,
        startedAt = startedAt,
        finishedAt = finishedAt,
        interruptedReason = reason,
    )

    companion object {
        const val DESCRIPTOR_ID = "chat_turn"
        const val AGENT_VERSION = "legacy-stream"
    }
}
