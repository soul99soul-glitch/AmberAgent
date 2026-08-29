package app.amber.feature.chat.api

import app.amber.core.agent.runtime.AgentInput
import app.amber.core.agent.runtime.AssistantId
import app.amber.core.agent.runtime.ConversationId
import app.amber.core.agent.runtime.MessageNodeId
import app.amber.core.agent.runtime.ModelSelectionPolicy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatTurnInput(
    @SerialName("conversation_id") val conversationId: ConversationId,
    @SerialName("message_node_id") val messageNodeId: MessageNodeId,
    @SerialName("assistant_id") val assistantId: AssistantId,
    @SerialName("user_message_text") val userMessageText: String,
    @SerialName("regenerate_of") val regenerateOf: String? = null,
    /**
     * Variant-regenerate window: when both bounds are set the turn generates
     * from `messages[messageRangeStart until messageRangeEndExclusive]`
     * instead of the whole conversation (legacy messageRange parity).
     */
    @SerialName("message_range_start") val messageRangeStart: Int? = null,
    @SerialName("message_range_end") val messageRangeEndExclusive: Int? = null,
    @SerialName("tool_choice") val toolChoice: ToolChoicePolicy = ToolChoicePolicy.AUTO,
    @SerialName("model_override") val modelOverride: ModelSelectionPolicy? = null,
    @SerialName("max_tool_iterations") val maxToolIterations: Int = 256,
) : AgentInput

@Serializable
enum class ToolChoicePolicy {
    AUTO,
    NONE,
    REQUIRED,
}

@Serializable
data class RetryPolicy(
    val maxRetries: Int = 3,
    val backoffMs: Long = 1000,
)
