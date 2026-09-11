package app.amber.core.ai.transformers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Modality
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.R
import app.amber.common.cache.LruCache
import app.amber.common.cache.SingleFileCacheStore
import app.amber.core.ai.prompts.resolveVisionRecognitionPrompt
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.settings.findProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.time.Duration.Companion.days

private const val TAG = "VisionTransformer"

class VisualRecognitionException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Resolved per call so processing/errors follow the app's current locale. */
data class OcrStrings(
    val processing: String,
    val visionFallback: String,
    val modelMissing: String,
    val modelUnsupported: String,
    val providerMissing: String,
    val providerCallFailed: (String) -> String,
    val emptyResult: String,
    val unknownError: String,
) {
    companion object {
        fun english(): OcrStrings = OcrStrings(
            processing = "Reading image...",
            visionFallback = "Switching to the vision model to read the image...",
            modelMissing = "Configure a vision recognition model first",
            modelUnsupported = "The vision recognition model does not support image input",
            providerMissing = "The vision recognition model's provider is unavailable",
            providerCallFailed = { detail -> "Vision recognition model call failed: $detail" },
            emptyResult = "The vision recognition model returned no usable content",
            unknownError = "Vision recognition failed",
        )

        fun from(context: Context): OcrStrings = OcrStrings(
            processing = context.getString(R.string.ocr_processing_status),
            visionFallback = context.getString(R.string.ocr_vision_fallback_status),
            modelMissing = context.getString(R.string.ocr_error_model_missing),
            modelUnsupported = context.getString(R.string.ocr_error_model_unsupported),
            providerMissing = context.getString(R.string.ocr_error_provider_missing),
            providerCallFailed = { detail -> context.getString(R.string.ocr_error_provider_call_failed, detail) },
            emptyResult = context.getString(R.string.ocr_error_empty_result),
            unknownError = context.getString(R.string.ocr_error_unknown),
        )
    }
}

object OcrTransformer : InputMessageTransformer, KoinComponent {
    private val cache by lazy {
        val context = get<Context>()
        val json = Json { allowStructuredMapKeys = true }
        val store = SingleFileCacheStore(
            file = File(context.cacheDir, "vision_cache.json"),
            keySerializer = String.serializer(),
            valueSerializer = String.serializer(),
            json = json
        )
        LruCache(
            capacity = 64,
            store = store,
            deleteOnEvict = true,
            preloadFromStore = true,
            expireAfterWriteMillis = 3.days.inWholeMilliseconds,
        )
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (ctx.model.inputModalities.contains(Modality.IMAGE) && !ctx.forceImageToText) {
            return messages
        }

        val hasImages = messages.any { message ->
            message.parts.any { it is UIMessagePart.Image && it.url.isNotBlank() }
        }
        if (!hasImages) return messages

        return withContext(Dispatchers.IO) {
            try {
                val strings = OcrStrings.from(ctx.context)
                ctx.processingStatus.value = strings.processing
                messages.map { message ->
                    message.copy(
                        parts = message.parts.map { part ->
                            when {
                                part is UIMessagePart.Image && part.url.isNotBlank() -> {
                                    UIMessagePart.Text(
                                        performImageRecognition(
                                            part = part,
                                            settings = ctx.settings,
                                            strings = strings,
                                        )
                                    )
                                }

                                else -> part
                            }
                        }
                    )
                }
            } finally {
                ctx.processingStatus.value = null
            }
        }
    }

    suspend fun performImageRecognition(
        part: UIMessagePart.Image,
        settings: Settings,
        promptOverride: String? = null,
        useCache: Boolean = true,
        strings: OcrStrings = OcrStrings.english(),
    ): String {
        val model = settings.findModelById(settings.ocrModelId)
            ?: throw VisualRecognitionException(strings.modelMissing)
        if (Modality.IMAGE !in model.inputModalities) {
            throw VisualRecognitionException(strings.modelUnsupported)
        }
        val providerSetting = model.findProvider(settings.providers)
            ?: throw VisualRecognitionException(strings.providerMissing)
        val prompt = promptOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: resolveVisionRecognitionPrompt(settings.ocrPrompt)
        val cacheKey = "${part.url}|${model.id}|${prompt.hashCode()}"

        if (useCache) {
            cache.get(cacheKey)?.let { cachedResult ->
                Log.i(TAG, "performImageToText: Using cached result")
                return cachedResult
            }
        }

        val provider = get<ProviderCatalog>().text(providerSetting)
        val result = runCatching {
            provider.complete(
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage.system(prompt),
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Image(part.url))
                    )
                ),
                params = TextGenerationParams(
                    model = model,
                ),
            )
        }.getOrElse {
            if (it is CancellationException) throw it
            val detail = it.message?.takeIf { message -> message.isNotBlank() } ?: strings.unknownError
            throw VisualRecognitionException(strings.providerCallFailed(detail), it)
        }
        // choices 可能为空（内容过滤等），直接 [0] 会 IOOBE
        val content = result.choices.firstOrNull()?.message?.toText()?.trim().orEmpty()
        if (content.isBlank()) {
            throw VisualRecognitionException(strings.emptyResult)
        }
        Log.i(TAG, "performImageToText: $content")
        val visionResult = """
            <image_context>
            $content
            </image_context>
            * The image_context tag contains visual recognition results for an image uploaded by the user, not the user's prompt.
        """.trimIndent()

        if (useCache) {
            cache.put(cacheKey, visionResult)
        }
        return visionResult
    }
}
