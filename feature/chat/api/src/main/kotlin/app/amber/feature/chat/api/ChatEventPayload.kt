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

    /**
     * Lightweight durable checkpoint of an in-flight streaming turn,
     * coalesced to at most one per second / 512 new chars. Carries no
     * message content (the conversation snapshot owns content) — only
     * enough state for crash recovery to project an interrupted run
     * back into the UI: which message was the streaming tail, a hash to
     * detect staleness of the persisted tail, and the tool states needed
     * to preserve pending approvals and block half-streamed tool calls.
     */
    @Serializable
    data class StreamCheckpoint(
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("message_id") val messageId: String,
        @SerialName("parts_hash") val partsHash: String,
        @SerialName("tool_states") val toolStates: List<ToolStateSnapshot> = emptyList(),
        @SerialName("last_event_id") val lastEventId: String = "",
        @SerialName("char_count") val charCount: Long = 0,
    ) : ChatEventPayload, AgentEventPayload.Final {
        companion object {
            /** Matches the persisted AgentEventRecord.type for this payload. */
            const val TYPE = "StreamCheckpoint"
        }
    }
}

@Serializable
data class ToolStateSnapshot(
    @SerialName("tool_call_id") val toolCallId: String,
    @SerialName("tool_name") val toolName: String,
    val state: StreamToolState,
)

@Serializable
enum class StreamToolState {
    /** Tool call arguments were still streaming — not safe to execute. */
    @SerialName("streaming")
    STREAMING,

    /** Arguments complete, awaiting user approval. */
    @SerialName("pending_approval")
    PENDING_APPROVAL,

    /** User already decided (approved/answered/denied); resumable as-is. */
    @SerialName("resumable")
    RESUMABLE,

    /** Tool already executed and produced output. */
    @SerialName("executed")
    EXECUTED,
}
