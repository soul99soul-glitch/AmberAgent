package app.amber.ai.provider

import app.amber.ai.ui.ImageGenerationResult
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import kotlinx.coroutines.flow.Flow

/** Stateless text capability implemented by one configured model provider. */
interface TextModelGateway<T : ProviderSetting> {
    suspend fun listModels(providerSetting: T): List<Model>

    suspend fun getBalance(providerSetting: T): String = "TODO"

    suspend fun complete(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk

    suspend fun stream(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk>
}

/** Image capability kept separate so text-only consumers cannot invoke it. */
interface ImageModelGateway<T : ProviderSetting> {
    suspend fun generateImage(
        providerSetting: T,
        params: ImageGenerationParams,
    ): ImageGenerationResult

    fun supportsImageEdit(providerSetting: T): Boolean = false
}
