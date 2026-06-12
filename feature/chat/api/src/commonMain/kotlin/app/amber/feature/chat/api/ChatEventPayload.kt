package app.amber.feature.chat.api

import app.amber.core.agent.runtime.AgentEventPayload
import app.amber.core.agent.runtime.MessageNodeId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

sealed interface ChatEventPayload {

    @Serializable
    data class UserMessageAccepted(
        @SerialName("message_node_id") val messageNodeId: MessageNodeId,
        @SerialName("message_id") val messageId: String,
    ) : ChatEventPayload, AgentEventPayload.Final

    @Serializable
    data class AssistantTextDelta(
        @SerialName("message_id") val messageId: String,
        val delta: String,
    ) : ChatEventPayload, AgentEventPayload.Transient

    @Serializable
    data class ToolInvoked(
        @SerialName("tool_id") val toolId: String,
        @SerialName("tool_version") val toolVersion: String,
        @SerialName("result_preview") val resultPreview: String,
    ) : ChatEventPayload, AgentEventPayload.Final

    @Serializable
    data class AssistantMessageFinalized(
        @SerialName("message_node_id") val messageNodeId: MessageNodeId,
        @SerialName("message_id") val messageId: String,
        @SerialName("input_tokens") val inputTokens: Int,
        @SerialName("output_tokens") val outputTokens: Int,
        @SerialName("regenerate_of") val regenerateOf: String?,
    ) : ChatEventPayload, AgentEventPayload.Final
}
