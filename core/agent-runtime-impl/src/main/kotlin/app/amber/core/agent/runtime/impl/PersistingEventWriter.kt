package app.amber.core.agent.runtime.impl

import android.util.Log
import app.amber.core.agent.runtime.AgentEventPayload
import app.amber.core.agent.runtime.AgentEventPayloadCodec
import app.amber.core.agent.runtime.AgentEventRecord
import app.amber.core.agent.runtime.AgentEventStore
import app.amber.core.agent.runtime.AgentEventWriter
import app.amber.core.agent.runtime.AgentRunId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Store-backed [AgentEventWriter] for non-chat runs (Step 3): every committed
 * Final payload with a registered [AgentEventPayloadCodec] is persisted to the
 * event store with a DB-allocated seq. Chat keeps its own projecting writer;
 * this one is the generic path DeepRead / SubAgent / Novel scopes get, and the
 * fallback chat delegates its non-chat finals (tool lifecycle) to.
 *
 * Fail-safe by contract: a writer observes a run and must never break it, so
 * serialization/store failures are logged and dropped. Unregistered payload
 * types are dropped with a warning — registering a codec is the opt-in that
 * makes a payload type durable.
 */
class PersistingEventWriter(
    private val runId: AgentRunId,
    private val parentRunId: AgentRunId?,
    private val agentDescriptorId: String,
    private val store: AgentEventStore,
    private val json: Json,
    private val codecs: Map<String, AgentEventPayloadCodec<*>>,
) : AgentEventWriter {

    override fun emit(transient: AgentEventPayload.Transient) {
        // No transient bus outside chat; transients are render hints only.
    }

    override suspend fun commit(final: AgentEventPayload.Final) {
        val payloadType = final::class.qualifiedName
        val codec = payloadType?.let(codecs::get)
        if (codec == null) {
            Log.w(TAG, "Dropping unregistered Final event ${final::class.simpleName} for run ${runId.value}")
            return
        }
        try {
            @Suppress("UNCHECKED_CAST")
            val payload = json.encodeToString(
                codec.serializer as KSerializer<AgentEventPayload.Final>,
                final,
            )
            store.appendEventAllocatingSeq(
                AgentEventRecord(
                    // Unique-per-process eventId: the seq is DB-allocated, so
                    // a process restart + same-runId resume can never recycle
                    // an id and hit the idempotent-append IGNORE.
                    eventId = "${runId.value}_${UUID.randomUUID()}",
                    runId = runId.value,
                    parentRunId = parentRunId?.value,
                    seq = 0L,
                    type = codec.type,
                    payloadType = payloadType,
                    payload = payload,
                    payloadSchemaVersion = SCHEMA_VERSION,
                    agentDescriptorId = agentDescriptorId,
                    agentVersion = AGENT_VERSION,
                    isFinal = true,
                    ts = System.currentTimeMillis(),
                ),
            )
        } catch (error: CancellationException) {
            // Fail-safe covers store/serialization failures, never cancellation
            // — swallowing it would hide the cancel from the caller and log a
            // misleading persistence failure.
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Failed to persist ${codec.type} for run ${runId.value}", error)
        }
    }

    override suspend fun flush() {
        // No buffering; commits are synchronous against the store.
    }

    override suspend fun commitError(throwable: Throwable, recoverable: Boolean) {
        Log.e(TAG, "Run ${runId.value} error (recoverable=$recoverable)", throwable)
    }

    private companion object {
        const val TAG = "PersistingEventWriter"
        const val SCHEMA_VERSION = 1
        const val AGENT_VERSION = "1.0.0"
    }
}
