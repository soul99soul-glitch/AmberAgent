package app.amber.core.agent.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

interface ModelRouter {
    fun session(policy: ModelSelectionPolicy): LlmSession
}

@Serializable
data class ModelSelectionPolicy(
    val preferredModelId: String? = null,
    val fallbackModelId: String? = null,
    val maxTokenBudget: Int? = null,
)

interface LlmSession {
    val descriptor: ModelDescriptor
    suspend fun countTokens(parts: List<TokenizableSegment>): TokenBudget
    suspend fun streamTurn(turn: ChatTurn): Flow<TurnEvent>
}

data class ModelDescriptor(
    val modelId: String,
    val providerId: String,
    val displayName: String,
)

data class TokenizableSegment(
    val tokenizerId: String,
    val text: String,
)

data class TokenBudget(
    val inputTokens: Int,
    val maxOutputTokens: Int,
    val remainingTokens: Int,
)

data class ChatTurn(
    val messages: List<Any>,
    val tools: List<ToolDescriptor>,
    val systemPrompt: String?,
)

sealed interface TurnEvent {
    data class Delta(val content: String) : TurnEvent
    data class ToolCall(val id: String, val name: String, val args: String) : TurnEvent
    data class Done(val finishReason: String) : TurnEvent
    data class Error(val message: String) : TurnEvent
}
