package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.ImageAspectRatio
import app.amber.feature.prompts.AgentPromptConfigRepository
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.files.FilesManager
import java.io.File
import kotlin.uuid.Uuid

/**
 * Shared image-generation entry point used by both [me.rerere.rikkahub.ui.pages.imggen.ImgGenVM]
 * (the standalone "create images" page) and the `generate_image` chat tool
 * (inline image generation triggered by the main chat model).
 *
 * Two destinations are supported:
 *  - [generateToGallery]: writes results to the global images dir
 *    (`filesDir/images/`) — the historic gallery used by [ImgGenVM].
 *    Caller is responsible for inserting [me.rerere.rikkahub.data.db.entity.GenMediaEntity]
 *    rows after success (kept out of this repo to preserve the existing
 *    ImgGenVM ordering: insert *after* the file write succeeds).
 *  - [generateForConversation]: writes results to a per-conversation dir
 *    (`filesDir/chat_images/{conversationId}/`) and does NOT touch the
 *    gallery DB. Chat-inline generated images live with their conversation
 *    and disappear on conversation deletion; users save to MediaStore on
 *    demand via the long-press menu in the timeline.
 */
class ImageGenerationRepository(
    private val settingsStore: SettingsAggregator,
    private val providerManager: ProviderManager,
    private val filesManager: FilesManager,
    private val promptConfigRepository: AgentPromptConfigRepository,
) {
    /**
     * Generate images and persist each to the **global gallery dir**.
     * Used by ImgGenVM. Returns the saved [File]s in result order.
     */
    suspend fun generateToGallery(
        modelId: Uuid,
        prompt: String,
        aspectRatio: ImageAspectRatio,
        numOfImages: Int,
    ): Result<List<GeneratedImageFile>> = runCatching {
        val invocation = invoke(modelId, prompt, aspectRatio, numOfImages)
        invocation.results.mapIndexed { index, item ->
            val timestamp = System.currentTimeMillis()
            // Match the historic filename convention from ImgGenVM so existing
            // gallery entries and new ones look the same on disk.
            val sanitizedModel = invocation.modelDisplayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val filename = "${timestamp}_${sanitizedModel}_$index.png"
            val file = File(filesManager.getImagesDir(), filename)
            filesManager.createImageFileFromBase64(item.data, file.absolutePath)
            GeneratedImageFile(
                file = file,
                relativePath = "images/${file.name}",
                modelDisplayName = invocation.modelDisplayName,
                mimeType = item.mimeType,
            )
        }
    }

    /**
     * Generate images for the chat tool. Persists to the conversation's
     * private dir; does NOT touch the gallery DB.
     */
    suspend fun generateForConversation(
        modelId: Uuid,
        prompt: String,
        aspectRatio: ImageAspectRatio,
        numOfImages: Int,
        conversationId: Uuid,
    ): Result<List<GeneratedImageFile>> = runCatching {
        val invocation = invoke(modelId, prompt, aspectRatio, numOfImages)
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

    private suspend fun invoke(
        modelId: Uuid,
        prompt: String,
        aspectRatio: ImageAspectRatio,
        numOfImages: Int,
    ): Invocation {
        require(prompt.isNotBlank()) { "Prompt must not be blank" }
        require(numOfImages in 1..4) { "numOfImages must be between 1 and 4 (got $numOfImages)" }

        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(modelId)
            ?: error("Image generation model not found (id=$modelId)")
        val provider = model.findProvider(settings.providers)
            ?: error("Provider not found for model ${model.displayName}")

        val effectivePrompt = promptConfigRepository.effectiveImagePrompt(prompt)

        val params = ImageGenerationParams(
            model = model,
            prompt = effectivePrompt,
            numOfImages = numOfImages,
            aspectRatio = aspectRatio,
            customHeaders = model.customHeaders,
            customBody = model.customBodies,
        )

        Log.i(TAG, "generateImage model=${model.displayName} n=$numOfImages aspect=$aspectRatio")
        val result = providerManager.getProviderByType(provider).generateImage(provider, params)
        return Invocation(
            results = result.items,
            modelDisplayName = model.displayName,
        )
    }

    private data class Invocation(
        val results: List<me.rerere.ai.ui.ImageGenerationItem>,
        val modelDisplayName: String,
    )

    companion object {
        private const val TAG = "ImageGenerationRepository"
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
