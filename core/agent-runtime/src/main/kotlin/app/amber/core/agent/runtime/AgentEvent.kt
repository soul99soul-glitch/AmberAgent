package app.amber.core.agent.runtime

import kotlinx.serialization.KSerializer

sealed interface AgentEventPayload {
    interface Final : AgentEventPayload
    interface Transient : AgentEventPayload
}

/**
 * Wire codec for one persisted [AgentEventPayload.Final] type: [type] is the
 * value written into the `agent_event.type` column (explicit and stable — not
 * necessarily the class simple name), [serializer] renders the payload column.
 * Registries key codecs by the payload class's qualified name, which is what
 * writers store in `payloadType`.
 */
class AgentEventPayloadCodec<P : AgentEventPayload.Final>(
    val type: String,
    val serializer: KSerializer<P>,
)

interface AgentEventWriter {
    fun emit(transient: AgentEventPayload.Transient)
    suspend fun commit(final: AgentEventPayload.Final)
    suspend fun flush()
    suspend fun commitError(throwable: Throwable, recoverable: Boolean)
}
