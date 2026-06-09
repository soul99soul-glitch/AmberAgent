package app.amber.feature.ui.pages.imggen

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import app.amber.ai.ui.ImageAspectRatio
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.findModelById
import app.amber.agent.data.db.entity.GenMediaEntity
import app.amber.core.files.FilesManager
import app.amber.core.repository.GenMediaRepository
import app.amber.core.repository.ImageGenerationRepository
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class GeneratedImage(
    val id: Int,
    val prompt: String,
    val filePath: String,
    val timestamp: Long,
    val model: String
)

private fun GenMediaEntity.toGeneratedImage(filesManager: FilesManager): GeneratedImage {
    val imagesDir = filesManager.getImagesDir()
    val fullPath = File(imagesDir, this.path.removePrefix("images/")).absolutePath

    return GeneratedImage(
        id = this.id,
        prompt = this.prompt,
        filePath = fullPath,
        timestamp = this.createAt,
        model = this.modelId
    )
}

class ImgGenVM(
    context: Application,
    val settingsStore: SettingsAggregator,
    val genMediaRepository: GenMediaRepository,
    private val filesManager: FilesManager,
    private val imageGenerationRepository: ImageGenerationRepository,
) : AndroidViewModel(context) {
    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt

    private val _numberOfImages = MutableStateFlow(1)
    val numberOfImages: StateFlow<Int> = _numberOfImages

    private val _aspectRatio = MutableStateFlow(ImageAspectRatio.SQUARE)
    val aspectRatio: StateFlow<ImageAspectRatio> = _aspectRatio

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating
    private var cancelJob: Job? = null

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _currentGeneratedImages = MutableStateFlow<List<GeneratedImage>>(emptyList())
    val currentGeneratedImages: StateFlow<List<GeneratedImage>> = _currentGeneratedImages

    val pager = Pager(
        config = PagingConfig(pageSize = 20, enablePlaceholders = false),
        pagingSourceFactory = { genMediaRepository.getAllMedia() }
    )
    val generatedImages: Flow<PagingData<GeneratedImage>> = pager.flow
        .map { pagingData ->
            pagingData.map { entity -> entity.toGeneratedImage(filesManager) }
        }
        .cachedIn(viewModelScope)

    fun updatePrompt(prompt: String) {
        _prompt.value = prompt
    }

    fun updateNumberOfImages(count: Int) {
        _numberOfImages.value = count.coerceIn(1, 4)
    }

    fun updateAspectRatio(aspectRatio: ImageAspectRatio) {
        _aspectRatio.value = aspectRatio
    }

    fun clearError() {
        _error.value = null
    }

    fun generateImage() {
        if (prompt.value.isBlank()) return
        cancelJob?.cancel()
        cancelJob = viewModelScope.launch {
            try {
                _isGenerating.value = true
                _error.value = null
                _currentGeneratedImages.value = emptyList()

                val settings = settingsStore.settingsFlow.first()
                // Settings.imageGenerationModelId is a non-null Uuid with a
                // random default — "unset" presents as a Uuid that resolves
                // to no model. Treat that as the no-selection case.
                val model = settings.findModelById(settings.imageGenerationModelId)
                    ?: throw IllegalStateException("No image generation model selected")

                val generated = imageGenerationRepository.generateToGallery(
                    modelId = model.id,
                    prompt = _prompt.value,
                    aspectRatio = _aspectRatio.value,
                    numOfImages = _numberOfImages.value,
                ).getOrThrow()

                // Persist gallery rows AFTER files land on disk so the gallery
                // never points at half-written files (the historic invariant
                // from this VM's original code path).
                val timestamp = System.currentTimeMillis()
                generated.forEach { saved ->
                    genMediaRepository.insertMedia(
                        GenMediaEntity(
                            path = saved.relativePath,
                            modelId = saved.modelDisplayName,
                            prompt = _prompt.value,
                            createAt = timestamp,
                        )
                    )
                }

                _currentGeneratedImages.value = generated.map { saved ->
                    GeneratedImage(
                        id = 0, // Updated on next paging refresh from DB.
                        prompt = _prompt.value,
                        filePath = saved.file.absolutePath,
                        timestamp = timestamp,
                        model = saved.modelDisplayName,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                Log.e(TAG, "Failed to generate image", e)
                _error.value = e.message ?: "Unknown error occurred"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun cancelGeneration() {
        cancelJob?.cancel()
    }

    fun deleteImage(image: GeneratedImage) {
        viewModelScope.launch {
            try {
                // Delete from database first
                genMediaRepository.deleteMedia(image.id)

                // Then delete the file
                val file = File(image.filePath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete image", e)
                _error.value = "Failed to delete image"
            }
        }
    }

    companion object {
        private const val TAG = "ImgGenVM"
    }
}
