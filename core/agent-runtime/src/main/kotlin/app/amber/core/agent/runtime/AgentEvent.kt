package app.amber.core.agent.runtime

sealed interface AgentEventPayload {
    interface Final : AgentEventPayload
    interface Transient : AgentEventPayload
}

interface AgentEventWriter {
    fun emit(transient: AgentEventPayload.Transient)
    suspend fun commit(final: AgentEventPayload.Final)
    suspend fun flush()
    suspend fun commitError(throwable: Throwable, recoverable: Boolean)
}
