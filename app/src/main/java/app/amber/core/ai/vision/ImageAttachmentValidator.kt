package app.amber.core.ai.vision

import android.content.Context
import app.amber.ai.provider.Modality
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.util.ImageEncodingException
import app.amber.ai.util.encodeBase64
import app.amber.agent.R
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.settings.findProvider
import app.amber.core.settings.getCurrentChatModel

private const val MAX_IMAGES_PER_MESSAGE = 4

enum class ImageAttachmentStatusKind {
    CHECKING,
    READY,
    FALLBACK,
    BLOCKED,
}

data class ImageAttachmentStatus(
    val kind: ImageAttachmentStatusKind,
    val message: String,
) {
    val blocksSend: Boolean get() = kind == ImageAttachmentStatusKind.BLOCKED
}

/**
 * Text resolved at the UI/runtime boundary. Keeping the resolved values in a
 * small immutable object makes the validator deterministic in JVM tests while
 * allowing Chat and Council to use their app-localized Context.
 */
data class ImageAttachmentStrings(
    val checking: String,
    val ready: String,
    val fallback: String,
    val tooManyImages: (Int) -> String,
    val selectModel: String,
    val visionModelMissing: String,
    val visionModelUnsupported: String,
    val visionProviderMissing: String,
    val fileMissing: String,
    val sourceUnsupported: String,
    val heicRequiresAndroid9: String,
    val avifRequiresAndroid12: String,
    val decodeFailed: String,
    val mimeUnsupported: String,
    val unreadable: (String) -> String,
    val unknownError: String,
    val health: VisionModelHealthStrings,
) {
    companion object {
        fun english(): ImageAttachmentStrings = ImageAttachmentStrings(
            checking = "Checking image",
            ready = "The current model can read this image",
            fallback = "The vision model will read this image first",
            tooManyImages = { count -> "At most $count images can be sent at once" },
            selectModel = "Select a model first",
            visionModelMissing = "Configure a vision recognition model first",
            visionModelUnsupported = "The vision recognition model does not support image input",
            visionProviderMissing = "The vision recognition model's provider is unavailable",
            fileMissing = "The image file is missing or was deleted",
            sourceUnsupported = "This image source is not supported",
            heicRequiresAndroid9 = "HEIC images require Android 9 or later",
            avifRequiresAndroid12 = "AVIF images require Android 12 or later",
            decodeFailed = "The image format could not be decoded (unsupported format or corrupt file)",
            mimeUnsupported = "This image format is not supported (JPEG/PNG/WebP/GIF/HEIC/AVIF supported)",
            unreadable = { detail -> "The image cannot be read: $detail" },
            unknownError = "Unknown image error",
            health = VisionModelHealthStrings.english(),
        )

        fun from(context: Context): ImageAttachmentStrings = ImageAttachmentStrings(
            checking = context.getString(R.string.image_attachment_status_checking),
            ready = context.getString(R.string.image_attachment_status_ready),
            fallback = context.getString(R.string.image_attachment_status_fallback),
            tooManyImages = { count -> context.getString(R.string.image_attachment_error_too_many, count) },
            selectModel = context.getString(R.string.image_attachment_error_select_model),
            visionModelMissing = context.getString(R.string.image_attachment_error_vision_model_missing),
            visionModelUnsupported = context.getString(R.string.image_attachment_error_vision_model_unsupported),
            visionProviderMissing = context.getString(R.string.image_attachment_error_vision_provider_missing),
            fileMissing = context.getString(R.string.image_attachment_error_file_missing),
            sourceUnsupported = context.getString(R.string.image_attachment_error_source_unsupported),
            heicRequiresAndroid9 = context.getString(R.string.image_attachment_error_heic_requires_android_9),
            avifRequiresAndroid12 = context.getString(R.string.image_attachment_error_avif_requires_android_12),
            decodeFailed = context.getString(R.string.image_attachment_error_decode_failed),
            mimeUnsupported = context.getString(R.string.image_attachment_error_mime_unsupported),
            unreadable = { detail -> context.getString(R.string.image_attachment_error_unreadable, detail) },
            unknownError = context.getString(R.string.image_attachment_error_unknown),
            health = VisionModelHealthStrings.from(context),
        )
    }
}

object ImageAttachmentValidator {
    fun checking(strings: ImageAttachmentStrings = ImageAttachmentStrings.english()): ImageAttachmentStatus =
        ImageAttachmentStatus(ImageAttachmentStatusKind.CHECKING, strings.checking)

    fun inspectImage(
        image: UIMessagePart.Image,
        settings: Settings,
        strings: ImageAttachmentStrings = ImageAttachmentStrings.english(),
    ): ImageAttachmentStatus {
        image.encodeBase64(withPrefix = false).getOrElse { error ->
            return ImageAttachmentStatus(
                kind = ImageAttachmentStatusKind.BLOCKED,
                message = readableImageError(error, strings),
            )
        }

        val chatModel = settings.getCurrentChatModel()
        if (chatModel == null) {
            return ImageAttachmentStatus(ImageAttachmentStatusKind.BLOCKED, strings.selectModel)
        }
        if (Modality.IMAGE in chatModel.inputModalities) {
            return ImageAttachmentStatus(ImageAttachmentStatusKind.READY, strings.ready)
        }

        val visionModel = settings.findModelById(settings.ocrModelId)
            ?: return ImageAttachmentStatus(ImageAttachmentStatusKind.BLOCKED, strings.visionModelMissing)
        if (Modality.IMAGE !in visionModel.inputModalities) {
            return ImageAttachmentStatus(ImageAttachmentStatusKind.BLOCKED, strings.visionModelUnsupported)
        }
        if (visionModel.findProvider(settings.providers) == null) {
            return ImageAttachmentStatus(ImageAttachmentStatusKind.BLOCKED, strings.visionProviderMissing)
        }
        return ImageAttachmentStatus(ImageAttachmentStatusKind.FALLBACK, strings.fallback)
    }

    fun firstBlockingIssue(
        parts: List<UIMessagePart>,
        settings: Settings,
        strings: ImageAttachmentStrings = ImageAttachmentStrings.english(),
    ): ImageAttachmentStatus? {
        val images = parts.filterIsInstance<UIMessagePart.Image>()
        if (images.size > MAX_IMAGES_PER_MESSAGE) {
            return ImageAttachmentStatus(
                kind = ImageAttachmentStatusKind.BLOCKED,
                message = strings.tooManyImages(MAX_IMAGES_PER_MESSAGE),
            )
        }
        return images.asSequence()
            .map { inspectImage(it, settings, strings) }
            .firstOrNull { it.blocksSend }
    }

    suspend fun firstBlockingIssueForSend(
        parts: List<UIMessagePart>,
        settings: Settings,
        providerCatalog: ProviderCatalog,
        strings: ImageAttachmentStrings = ImageAttachmentStrings.english(),
    ): ImageAttachmentStatus? {
        firstBlockingIssue(parts, settings, strings)?.let { return it }

        val needsVisionFallback = parts.filterIsInstance<UIMessagePart.Image>()
            .map { inspectImage(it, settings, strings) }
            .any { it.kind == ImageAttachmentStatusKind.FALLBACK }
        if (!needsVisionFallback) return null

        val health = VisionModelHealthChecker.probe(settings, providerCatalog, strings.health)
        return if (health.isAvailable) {
            null
        } else {
            ImageAttachmentStatus(
                kind = ImageAttachmentStatusKind.BLOCKED,
                message = health.label,
            )
        }
    }

    private fun readableImageError(error: Throwable, strings: ImageAttachmentStrings): String {
        val cause = if (error is ImageEncodingException) error.cause ?: error else error
        val message = cause.message.orEmpty()
        return when {
            "File does not exist" in message -> strings.fileMissing
            "Unsupported URL format" in message -> strings.sourceUnsupported
            "HEIC format requires Android 9" in message -> strings.heicRequiresAndroid9
            "AVIF format requires Android 12" in message -> strings.avifRequiresAndroid12
            "Failed to decode image" in message -> strings.decodeFailed
            "Failed to guess MIME type" in message -> strings.mimeUnsupported
            else -> strings.unreadable(message.ifBlank { cause::class.simpleName ?: strings.unknownError })
        }
    }
}
