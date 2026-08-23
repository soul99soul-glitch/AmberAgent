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

    suspend fun getBalance(providerSetting: T): String {
        return "TODO"
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

    /**
     * P6-02: whether this provider can edit an existing image (`mode = EDIT`
     * in [ImageGenerationParams]). Declared per real API capability — the
     * app layer must not show an edit entry when this returns false (plan
     * red line: no fake support copy).
     */
    fun supportsImageEdit(providerSetting: T): Boolean = false
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
    /**
     * P6-01: non-null enables server-side store + resumable streaming for
     * this call (OpenAI Responses only, strict official-endpoint match).
     * Non-serializable by design — it carries a live store handle and is
     * only set by the app layer for the current process.
     */
    @kotlinx.serialization.Transient
    val responsesResume: ResponsesResumeRequest? = null,
)

@Serializable
enum class ImageGenerationMode {
    CREATE,
    EDIT,
}

@Serializable
data class ImageGenerationParams(
    val model: Model,
    val prompt: String,
    val numOfImages: Int = 1,
    val aspectRatio: ImageAspectRatio = ImageAspectRatio.SQUARE,
    /**
     * Custom pixel size for the generated image. When both are non-null,
     * OpenAI-compatible providers send `"{customWidth}x{customHeight}"` instead
     * of the [aspectRatio] preset mapping; other providers ignore these and
     * keep their own mapping. UI-only entry (the `generate_image` tool always
     * leaves them null). Defaults keep serialized data compatible with the
     * pre-custom-size format.
     */
    val customWidth: Int? = null,
    val customHeight: Int? = null,
    /**
     * P6-02: CREATE (default) keeps the historic behavior; EDIT references an
     * existing image via [sourceImageUrl] and requests a modification.
     */
    val mode: ImageGenerationMode = ImageGenerationMode.CREATE,
    /**
     * P6-02: local file URL (`file://…`) of the source image to modify.
     * Only meaningful when [mode] == EDIT; must be a controlled reference
     * (validated by the app layer before it reaches a provider).
     */
    val sourceImageUrl: String? = null,
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
