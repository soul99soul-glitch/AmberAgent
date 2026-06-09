package app.amber.core.agent.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext

interface RunScope {
    val runId: AgentRunId
    val parentRunId: AgentRunId?
    val conversationId: ConversationId?
    val messageNodeId: MessageNodeId?
    val coroutineContext: CoroutineContext

    val events: AgentEventWriter
    val tools: ToolSession
    val llm: LlmSession
    val tracing: TraceRecorder
    val permission: PermissionBroker
    val messagePipeline: MessagePipeline

    suspend fun <T> child(
        descriptor: AgentDescriptor,
        block: suspend RunScope.() -> T,
    ): T

    suspend fun handoff(
        target: AgentDescriptorId,
        payload: HandoffPayload,
    ): Nothing

    fun ensureActive()
}

data class HandoffPayload(
    val reason: String,
    val context: Map<String, String> = emptyMap(),
)
