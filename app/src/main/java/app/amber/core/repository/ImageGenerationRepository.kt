package app.amber.core.repository

import android.util.Log
import kotlinx.coroutines.flow.first
import app.amber.ai.provider.ImageGenerationMode
import app.amber.ai.provider.ImageGenerationParams
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.ui.ImageAspectRatio
import app.amber.feature.prompts.AgentPromptConfigRepository
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.findModelById
import app.amber.core.settings.findProvider
import app.amber.core.settings.getCurrentImageGenerationModel
import app.amber.core.files.FilesManager
import java.io.File
import kotlin.uuid.Uuid

/** Chat-owned image generation entry point. */
class ImageGenerationRepository(
    private val settingsStore: SettingsAggregator,
    private val providerCatalog: ProviderCatalog,
    private val filesManager: FilesManager,
    private val promptConfigRepository: AgentPromptConfigRepository,
) {
    /**
     * Generate images for the chat tool. Persists to the conversation's
     * private dir; does NOT touch the gallery DB.
     *
     * P6-02: pass [mode] = EDIT with a [sourceImageUrl] to request an edit of
     * an existing image (see [validateEditSource] for the controlled-reference
     * gate). The default CREATE path is unchanged.
     */
    suspend fun generateForConversation(
        modelId: Uuid,
        prompt: String,
        aspectRatio: ImageAspectRatio,
        numOfImages: Int,
        conversationId: Uuid,
        mode: ImageGenerationMode = ImageGenerationMode.CREATE,
        sourceImageUrl: String? = null,
    ): Result<List<GeneratedImageFile>> = runCatching {
        val invocation = invoke(modelId, prompt, aspectRatio, numOfImages, conversationId, mode, sourceImageUrl)
        val dir = filesManager.getChatImagesDir(conversationId)
        invocation.results.mapIndexed { index, item ->
            val timestamp = System.currentTimeMillis()
            val filename = "${timestamp}_$index.png"
            val file = File(dir, filename)
            filesManager.createImageFileFromBase64(item.data, file.absolutePath)
            GeneratedImageFile(
                file = file,
                relativePath = "chat_images/${conversationId}/${file.name}",
                modelDisplayName = invocation.modelDisplayName,
                mimeType = item.mimeType,
            )
        }
    }

    /**
     * P6-02: whether the current image-generation model's provider declares
     * image-edit support. Drives the tool schema (edit params only exposed to
     * the model when true) and the carousel entry gate. Reads the same
     * model resolution the tool uses at execute time.
     */
    fun currentModelSupportsImageEdit(): Boolean {
        val settings = settingsStore.settingsFlow.value
        val model = settings.getCurrentImageGenerationModel() ?: return false
        val provider = model.findProvider(settings.providers) ?: return false
        return providerCatalog.image(provider).supportsImageEdit(provider)
    }

    private suspend fun invoke(
        modelId: Uuid,
        prompt: String,
        aspectRatio: ImageAspectRatio,
        numOfImages: Int,
        conversationId: Uuid? = null,
        mode: ImageGenerationMode = ImageGenerationMode.CREATE,
        sourceImageUrl: String? = null,
        customWidth: Int? = null,
        customHeight: Int? = null,
    ): Invocation {
        require(prompt.isNotBlank()) { "Prompt must not be blank" }
        require(numOfImages in 1..4) { "numOfImages must be between 1 and 4 (got $numOfImages)" }
        if (mode == ImageGenerationMode.EDIT) {
            requireNotNull(conversationId) { "mode=edit requires a conversation scope" }
        }

        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(modelId)
            ?: error("Image generation model not found (id=$modelId)")
        val provider = model.findProvider(settings.providers)
            ?: error("Provider not found for model ${model.displayName}")

        val resolvedSource = if (mode == ImageGenerationMode.EDIT) {
            validateEditSource(provider, sourceImageUrl, conversationId!!)
        } else {
            null
        }

        val effectivePrompt = promptConfigRepository.effectiveImagePrompt(prompt)

        val params = ImageGenerationParams(
            model = model,
            prompt = effectivePrompt,
            numOfImages = numOfImages,
            aspectRatio = aspectRatio,
            customWidth = customWidth,
            customHeight = customHeight,
            mode = mode,
            sourceImageUrl = resolvedSource,
            customHeaders = model.customHeaders,
            customBody = model.customBodies,
        )

        Log.i(TAG, "generateImage model=${model.displayName} n=$numOfImages aspect=$aspectRatio mode=$mode")
        val result = providerCatalog.image(provider).generateImage(provider, params)
        return Invocation(
            results = result.items,
            modelDisplayName = model.displayName,
        )
    }

    /**
     * P6-02 edit gate — validates the controlled source reference before any
     * provider call. Fails loudly (distinguishable messages) instead of
     * pretending support:
     *  - capability: provider must declare image edit support.
     *  - reference: `file://` URL resolving inside THIS conversation's
     *    chat_images dir — neither the model nor the UI can reference
     *    arbitrary files.
     *  - size / format: the provider contract ceiling + allowlist.
     */
    private suspend fun validateEditSource(
        provider: ProviderSetting,
        sourceImageUrl: String?,
        conversationId: Uuid,
    ): String {
        if (!providerCatalog.image(provider).supportsImageEdit(provider)) {
            error("The current image generation model does not support editing images")
        }
        require(!sourceImageUrl.isNullOrBlank()) { "mode=edit requires a source image URL" }
        val file = File(sourceImageUrl.removePrefix("file://"))
        require(file.exists()) { "Source image not found — it may have been deleted" }
        val conversationDir = filesManager.getChatImagesDir(conversationId).canonicalFile
        require(file.canonicalFile.path.startsWith(conversationDir.path + File.separator)) {
            "Source image is outside this conversation's images"
        }
        require(sourceImageMimeType(file.name) != null) {
            "Unsupported source image format: ${file.name} (png/jpg/jpeg/webp only)"
        }
        require(file.length() <= IMAGE_EDIT_MAX_SOURCE_BYTES) {
            "Source image is too large " +
                "(max ${IMAGE_EDIT_MAX_SOURCE_BYTES / (1024 * 1024)}MB)"
        }
        return "file://${file.absolutePath}"
    }

    private data class Invocation(
        val results: List<app.amber.ai.ui.ImageGenerationItem>,
        val modelDisplayName: String,
    )

    companion object {
        private const val TAG = "ImageGenerationRepository"
        private const val IMAGE_EDIT_MAX_SOURCE_BYTES = 5L * 1024L * 1024L

        private fun sourceImageMimeType(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> null
        }
    }
}

/** Result handle for a single generated image written to disk. */
data class GeneratedImageFile(
    val file: File,
    /** Path relative to `filesDir`, e.g. `"images/1234_foo_0.png"`. */
    val relativePath: String,
    val modelDisplayName: String,
    val mimeType: String,
)
