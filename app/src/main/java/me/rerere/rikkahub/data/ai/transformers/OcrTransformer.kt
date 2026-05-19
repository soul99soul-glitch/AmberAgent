package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.cache.LruCache
import me.rerere.common.cache.SingleFileCacheStore
import me.rerere.rikkahub.data.ai.prompts.resolveVisionRecognitionPrompt
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.time.Duration.Companion.days

private const val TAG = "VisionTransformer"

class VisualRecognitionException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

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
                ctx.processingStatus.value = "正在识别图片..."
                messages.map { message ->
                    message.copy(
                        parts = message.parts.map { part ->
                            when {
                                part is UIMessagePart.Image && part.url.isNotBlank() -> {
                                    UIMessagePart.Text(performImageRecognition(part))
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
        promptOverride: String? = null,
        useCache: Boolean = true,
    ): String {
        val settings = get<SettingsAggregator>().settingsFlow.value
        val model = settings.findModelById(settings.ocrModelId)
            ?: throw VisualRecognitionException("请先配置视觉识别模型")
        if (Modality.IMAGE !in model.inputModalities) {
            throw VisualRecognitionException("视觉识别模型不支持图片输入")
        }
        val providerSetting = model.findProvider(settings.providers)
            ?: throw VisualRecognitionException("视觉识别模型的提供商不可用")
        val prompt = promptOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: resolveVisionRecognitionPrompt(settings.ocrPrompt)
        val cacheKey = "${part.url}|${model.id}|${prompt.hashCode()}"

        if (useCache) {
            cache.get(cacheKey)?.let { cachedResult ->
                Log.i(TAG, "performImageToText: Using cached result")
                return cachedResult
            }
        }

        val provider = get<ProviderManager>().getProviderByType(providerSetting)
        val result = runCatching {
            provider.generateText(
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
            throw VisualRecognitionException("视觉识别模型调用失败：${it.message}", it)
        }
        val content = result.choices[0].message?.toText()?.trim().orEmpty()
        if (content.isBlank()) {
            throw VisualRecognitionException("视觉识别模型没有返回可用内容")
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
