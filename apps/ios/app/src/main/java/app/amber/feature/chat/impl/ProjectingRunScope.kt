package app.amber.feature.chat.impl

import app.amber.core.agent.runtime.AgentDescriptor
import app.amber.core.agent.runtime.AgentDescriptorId
import app.amber.core.agent.runtime.AgentEventWriter
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.ConversationId
import app.amber.core.agent.runtime.HandoffPayload
import app.amber.core.agent.runtime.LlmSession
import app.amber.core.agent.runtime.MessageNodeId
import app.amber.core.agent.runtime.MessagePipeline
import app.amber.core.agent.runtime.PermissionBroker
import app.amber.core.agent.runtime.RunScope
import app.amber.core.agent.runtime.ToolSession
import app.amber.core.agent.runtime.TraceRecorder
import app.amber.core.agent.runtime.adapter.LegacyRunScope
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

class ProjectingRunScope(
    override val runId: AgentRunId,
    override val conversationId: ConversationId?,
    override val messageNodeId: MessageNodeId?,
    override val events: AgentEventWriter,
) : RunScope {

    private val delegate = LegacyRunScope(
        runId = runId,
        parentRunId = null,
        conversationId = conversationId,
        messageNodeId = messageNodeId,
    )

    override val parentRunId: AgentRunId? = null
    override val coroutineContext: CoroutineContext = EmptyCoroutineContext

    override val tools: ToolSession = delegate.tools
    override val llm: LlmSession = delegate.llm
    override val tracing: TraceRecorder = delegate.tracing
    override val permission: PermissionBroker = delegate.permission
    override val messagePipeline: MessagePipeline = delegate.messagePipeline

    override suspend fun <T> child(
        descriptor: AgentDescriptor,
        block: suspend RunScope.() -> T,
    ): T = delegate.child(descriptor, block)

    override suspend fun handoff(target: AgentDescriptorId, payload: HandoffPayload): Nothing =
        delegate.handoff(target, payload)

    override fun ensureActive() = delegate.ensureActive()
}
