package app.amber.core.agent.runtime

import kotlinx.coroutines.flow.Flow

interface MessagePipeline {
    suspend fun transformInput(
        messages: List<Any>,
        ctx: PipelineCtx,
    ): List<Any>

    fun transformOutput(
        streaming: Flow<TurnEvent>,
        ctx: PipelineCtx,
    ): Flow<TurnEvent>
}

data class PipelineCtx(
    val assistantId: AssistantId,
    val conversationId: ConversationId,
    val variables: Map<String, String>,
)
