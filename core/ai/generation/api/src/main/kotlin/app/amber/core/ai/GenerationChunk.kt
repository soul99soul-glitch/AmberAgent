package app.amber.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import app.amber.ai.ui.UIMessage
import kotlin.uuid.Uuid

/**
 * Typed end-of-flow signal reported by [RunKernel.run] via
 * [GenerationRunSession.onTerminal].
 *
 * The flow only reports the outcomes that must be persisted *from inside* the
 * flow body; COMPLETED / CANCELLED / FAILED are decided by the caller after
 * the flow ends and the conversation is persisted (COMPLETED must never be
 * published before the generated result + messages + terminal are durable).
 */
sealed interface GenerationTerminal {
    /**
     * The tool loop stopped to wait for the user (approval or ask_user).
     * WAITING_USER is a pause — not a completion and not a failure.
     */
    data object WaitingUser : GenerationTerminal

    /**
     * The tool loop exhausted maxSteps without producing a final answer.
     * STEP_LIMIT must never be mapped to COMPLETED.
     */
    data object StepLimit : GenerationTerminal
}

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>,
        val update: GenerationUpdate = GenerationUpdate.full(messages),
    ) : GenerationChunk
}

data class GenerationUpdate(
    val messages: List<UIMessage>,
    val streamingTailMessageId: Uuid?,
) {
    val isStreamingTail: Boolean get() = streamingTailMessageId != null

    fun withMessages(messages: List<UIMessage>): GenerationUpdate =
        copy(messages = messages)

    companion object {
        fun full(messages: List<UIMessage>): GenerationUpdate =
            GenerationUpdate(messages = messages, streamingTailMessageId = null)

        fun streamingTail(messages: List<UIMessage>): GenerationUpdate {
            val tailId = messages
                .lastOrNull { it.role == app.amber.ai.core.MessageRole.ASSISTANT }
                ?.id
            return GenerationUpdate(
                messages = messages,
                streamingTailMessageId = tailId,
            )
        }
    }
}
