package app.amber.core.ai

import app.amber.core.ai.transformers.InputMessageTransformer
import app.amber.core.ai.transformers.OutputMessageTransformer
import app.amber.core.model.Assistant
import app.amber.core.model.AssistantMemory
import app.amber.core.model.Conversation
import app.amber.core.settings.Settings
import app.amber.feature.runtime.ToolInvocationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import kotlin.uuid.Uuid

/**
 * Generation interface lifted out of `class GenerationHandler` in `:app` for
 * cascade decoupling (Phase D cascade T4.2). Consumers (subagent, board,
 * chat impl, DeepRead) now depend on this api module instead of the heavy
 * concrete class.
 *
 * The `:app` side declares `class GenerationHandler(...) : Generator` and
 * supplies the actual implementation (~1200 LOC with Memory / ProviderManager
 * / ConversationRepository deps). This api module owns nothing but the
 * type signatures + the streaming wire-format data classes.
 */
interface Generator {

    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        autoApproveTools: Boolean = false,
        autoApproveHighRiskTools: Boolean = false,
        autoApprovedToolNames: Set<String> = emptySet(),
        invocationContext: ToolInvocationContext = ToolInvocationContext.Normal,
        conversation: Conversation? = null,
        consumeSteerMessages: suspend () -> List<UIMessage> = { emptyList() },
        runId: String? = null,
        onTerminal: (suspend (GenerationTerminal) -> Unit)? = null,
        /**
         * P6-01: non-null enables server-side stored OpenAI Responses
         * streaming for this call (store=true + cursor persistence + reconnect
         * + recovery). Only the main chat path sets it, under the
         * openai_responses_resume capability flag + user toggle + strict
         * official-endpoint match; other callers keep the default.
         */
        responsesResume: app.amber.ai.provider.ResponsesResumeRequest? = null,
    ): Flow<GenerationChunk>

}

/**
 * Typed end-of-flow signal reported by [Generator.generateText] via
 * [Generator.generateText.onTerminal] — P1-03 (Typed Run Terminal).
 *
 * The flow only reports the outcomes that must be persisted *from inside* the
 * flow body; COMPLETED / CANCELLED / FAILED are decided by the caller after
 * the flow ends and the conversation is persisted (COMPLETED must never be
 * published before assistant result + messages + terminal are durable).
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
