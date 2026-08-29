package app.amber.feature.subagent

import app.amber.core.agent.runtime.AgentCapability
import app.amber.core.agent.runtime.AgentDescriptor
import app.amber.core.agent.runtime.AgentDescriptorId
import app.amber.core.agent.runtime.AgentInput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kernel descriptor for one sub-agent generation turn. A sub-agent *thread*
 * is the durable, resumable entity owned by SubAgentManager +
 * ThreadGraphManager; every generation turn of that thread is one runner
 * [SubAgentTurnInput] with a fresh run id, so Run Protocol write-once
 * terminal transitions are never violated by followup turns.
 */
object SubAgentTurnDescriptor {
    val ID = AgentDescriptorId("sub_agent_turn")

    val value = AgentDescriptor(
        id = ID,
        version = "1.0.0",
        displayName = "Sub Agent Turn",
        capabilities = setOf(AgentCapability.SUB_AGENT, AgentCapability.TOOL_USE),
    )
}

/**
 * Serializable identity of one sub-agent generation turn. The runtime
 * objects a turn needs (settings snapshot, resolved tools with lambdas,
 * live text flows, terminal callback, steer drain) are not serializable and
 * travel through the process-local `SubAgentTurnPayloads` mailbox keyed by
 * the runner run id.
 */
@Serializable
data class SubAgentTurnInput(
    @SerialName("thread_id") val threadId: String,
    @SerialName("parent_conversation_id") val parentConversationId: String,
    @SerialName("parent_run_id") val parentRunId: String? = null,
    @SerialName("definition_id") val definitionId: String,
    val objective: String,
    val followup: Boolean = false,
) : AgentInput
