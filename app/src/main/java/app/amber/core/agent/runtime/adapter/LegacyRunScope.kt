package app.amber.core.agent.runtime.adapter

import app.amber.core.agent.runtime.AgentDescriptor
import app.amber.core.agent.runtime.AgentDescriptorId
import app.amber.core.agent.runtime.AgentEventPayload
import app.amber.core.agent.runtime.AgentEventWriter
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AssistantId
import app.amber.core.agent.runtime.ChatTurn
import app.amber.core.agent.runtime.ConversationId
import app.amber.core.agent.runtime.HandoffPayload
import app.amber.core.agent.runtime.LlmSession
import app.amber.core.agent.runtime.MessageNodeId
import app.amber.core.agent.runtime.MessagePipeline
import app.amber.core.agent.runtime.ModelDescriptor
import app.amber.core.agent.runtime.PermissionBroker
import app.amber.core.agent.runtime.PermissionDecision
import app.amber.core.agent.runtime.PermissionIntent
import app.amber.core.agent.runtime.PipelineCtx
import app.amber.core.agent.runtime.RunScope
import app.amber.core.agent.runtime.SpanAttrs
import app.amber.core.agent.runtime.SpanKind
import app.amber.core.agent.runtime.SpanScope
import app.amber.core.agent.runtime.TokenBudget
import app.amber.core.agent.runtime.TokenizableSegment
import app.amber.core.agent.runtime.ToolCallResult
import app.amber.core.agent.runtime.ToolDescriptor
import app.amber.core.agent.runtime.ToolId
import app.amber.core.agent.runtime.ToolSession
import app.amber.core.agent.runtime.TraceRecorder
import app.amber.core.agent.runtime.TurnEvent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.coroutines.CoroutineContext

class LegacyRunScope(
    override val runId: AgentRunId = AgentRunId.new(),
    override val parentRunId: AgentRunId? = null,
    override val conversationId: ConversationId? = null,
    override val messageNodeId: MessageNodeId? = null,
) : RunScope {

    override val coroutineContext: CoroutineContext
        get() = kotlin.coroutines.EmptyCoroutineContext

    override val events: AgentEventWriter = NoOpEventWriter
    override val tools: ToolSession = NoOpToolSession
    override val llm: LlmSession = NoOpLlmSession
    override val tracing: TraceRecorder = NoOpTraceRecorder
    override val permission: PermissionBroker = AutoApprovePermissionBroker
    override val messagePipeline: MessagePipeline = PassthroughPipeline

    override suspend fun <T> child(
        descriptor: AgentDescriptor,
        block: suspend RunScope.() -> T,
    ): T = block(LegacyRunScope(
        runId = AgentRunId.new(),
        parentRunId = runId,
        conversationId = conversationId,
        messageNodeId = messageNodeId,
    ))

    override suspend fun handoff(target: AgentDescriptorId, payload: HandoffPayload): Nothing {
        error("Handoff not supported in LegacyRunScope")
    }

    override fun ensureActive() {
        // No-op in legacy mode; callers still use coroutine cancellation directly
    }
}

private object NoOpEventWriter : AgentEventWriter {
    override fun emit(transient: AgentEventPayload.Transient) {}
    override suspend fun commit(final: AgentEventPayload.Final) {}
    override suspend fun flush() {}
    override suspend fun commitError(throwable: Throwable, recoverable: Boolean) {}
}

private object NoOpToolSession : ToolSession {
    override fun listAvailable(): List<ToolDescriptor> = emptyList()
    override suspend fun invoke(toolId: ToolId, args: JsonElement): ToolCallResult =
        ToolCallResult(output = JsonNull, isError = true)
}

private object NoOpLlmSession : LlmSession {
    override val descriptor = ModelDescriptor("legacy", "legacy", "Legacy")
    override suspend fun countTokens(parts: List<TokenizableSegment>) =
        TokenBudget(0, 0, 0)
    override suspend fun streamTurn(turn: ChatTurn): Flow<TurnEvent> = emptyFlow()
}

private object NoOpTraceRecorder : TraceRecorder {
    override suspend fun <T> span(
        name: String,
        kind: SpanKind,
        attributes: SpanAttrs,
        block: suspend SpanScope.() -> T,
    ): T = block(NoOpSpanScope)
}

private object NoOpSpanScope : SpanScope {
    override val spanId: String = "noop"
    override fun setAttribute(key: String, value: String) {}
    override fun setError(throwable: Throwable) {}
}

private object AutoApprovePermissionBroker : PermissionBroker {
    override suspend fun request(intent: PermissionIntent): PermissionDecision =
        PermissionDecision.Allow
}

private object PassthroughPipeline : MessagePipeline {
    override suspend fun transformInput(messages: List<Any>, ctx: PipelineCtx) = messages
    override fun transformOutput(streaming: Flow<TurnEvent>, ctx: PipelineCtx) = streaming
}
