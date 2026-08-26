package app.amber.ai.provider

import app.amber.ai.core.ReasoningLevel
import app.amber.ai.core.Tool
import app.amber.ai.ui.ImageAspectRatio
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

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
    @Transient
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
    val customWidth: Int? = null,
    val customHeight: Int? = null,
    val mode: ImageGenerationMode = ImageGenerationMode.CREATE,
    val sourceImageUrl: String? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class CustomHeader(
    val name: String,
    val value: String,
)

@Serializable
data class CustomBody(
    val key: String,
    val value: JsonElement,
)
