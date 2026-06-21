package app.amber.ai.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.core.Tool
import app.amber.ai.ui.ImageAspectRatio
import app.amber.ai.ui.ImageGenerationResult
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage

// 提供商实现
// 采用无状态设计，使用时除了需要传入需要的参数外，还需要传入provider setting作为参数
interface Provider<T : ProviderSetting> {
    suspend fun listModels(providerSetting: T): List<Model>

    /**
     * 查询 provider 余额。默认实现抛 [UnsupportedOperationException]：未 override 的
     * provider（如 KMP 的 Claude/OpenAI、Google）明确不支持余额查询，而不是静默返回
     * 占位字符串。此前默认返回 "TODO"，iOS 侧若经 :shared 调用会把字面量 "TODO" 显示
     * 给用户——P3 契约陷阱。调用方应先判 [ProviderSetting.balanceOption].enabled，
     * 或 catch 本异常。
     */
    suspend fun getBalance(providerSetting: T): String {
        throw UnsupportedOperationException(
            "Provider ${this::class.simpleName} does not implement getBalance; " +
                "override it or check balanceOption.enabled before calling."
        )
    }

    suspend fun generateText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk

    suspend fun streamText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk>

    suspend fun generateEmbedding(
        providerSetting: T,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult {
        error("Embedding generation is not supported")
    }

    suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): ImageGenerationResult
}

@Serializable
data class TextGenerationParams(
    val model: Model,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val tools: List<Tool> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class ImageGenerationParams(
    val model: Model,
    val prompt: String,
    val numOfImages: Int = 1,
    val aspectRatio: ImageAspectRatio = ImageAspectRatio.SQUARE,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class EmbeddingGenerationParams(
    val model: Model,
    val input: List<String>,
    val dimensions: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class EmbeddingGenerationResult(
    val model: String,
    val embeddings: List<List<Float>>,
)

@Serializable
data class CustomHeader(
    val name: String,
    val value: String
)

@Serializable
data class CustomBody(
    val key: String,
    val value: JsonElement
)
