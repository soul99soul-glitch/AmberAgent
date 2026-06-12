package app.amber.feature.chat.api

import app.amber.core.agent.runtime.AgentArtifact
import app.amber.core.agent.runtime.MessageNodeId
import app.amber.core.agent.runtime.ToolId
import app.amber.core.agent.runtime.ToolVersion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatTurnArtifact(
    @SerialName("assistant_message_id") val assistantMessageId: String,
    @SerialName("produced_in_node") val producedInNode: MessageNodeId,
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("tool_calls_count") val toolCallsCount: Int = 0,
) : AgentArtifact
