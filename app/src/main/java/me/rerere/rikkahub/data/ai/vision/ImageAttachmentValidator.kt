package me.rerere.rikkahub.data.ai.vision

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.ImageEncodingException
import me.rerere.ai.util.encodeBase64
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel

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

object ImageAttachmentValidator {
    fun checking(): ImageAttachmentStatus =
        ImageAttachmentStatus(ImageAttachmentStatusKind.CHECKING, "正在检查图片")

    fun inspectImage(
        image: UIMessagePart.Image,
        settings: Settings,
    ): ImageAttachmentStatus {
        image.encodeBase64(withPrefix = false).getOrElse { error ->
            return ImageAttachmentStatus(
                kind = ImageAttachmentStatusKind.BLOCKED,
                message = readableImageError(error),
            )
        }

        val chatModel = settings.getCurrentChatModel()
        if (chatModel == null) {
            return ImageAttachmentStatus(ImageAttachmentStatusKind.BLOCKED, "请先选择模型")
        }
        if (Modality.IMAGE in chatModel.inputModalities) {
            return ImageAttachmentStatus(ImageAttachmentStatusKind.READY, "图片可由当前模型读取")
        }

        val visionModel = settings.findModelById(settings.ocrModelId)
            ?: return ImageAttachmentStatus(ImageAttachmentStatusKind.BLOCKED, "请先配置视觉识别模型")
        if (Modality.IMAGE !in visionModel.inputModalities) {
            return ImageAttachmentStatus(ImageAttachmentStatusKind.BLOCKED, "视觉识别模型不支持图片输入")
        }
        if (visionModel.findProvider(settings.providers) == null) {
            return ImageAttachmentStatus(ImageAttachmentStatusKind.BLOCKED, "视觉识别模型的提供商不可用")
        }
        return ImageAttachmentStatus(ImageAttachmentStatusKind.FALLBACK, "将先由视觉识别模型读取图片")
    }

    fun firstBlockingIssue(
        parts: List<UIMessagePart>,
        settings: Settings,
    ): ImageAttachmentStatus? {
        val images = parts.filterIsInstance<UIMessagePart.Image>()
        if (images.size > MAX_IMAGES_PER_MESSAGE) {
            return ImageAttachmentStatus(
                kind = ImageAttachmentStatusKind.BLOCKED,
                message = "一次最多发送 $MAX_IMAGES_PER_MESSAGE 张图片",
            )
        }
        return images.asSequence()
            .map { inspectImage(it, settings) }
            .firstOrNull { it.blocksSend }
    }

    suspend fun firstBlockingIssueForSend(
        parts: List<UIMessagePart>,
        settings: Settings,
        providerManager: ProviderManager,
    ): ImageAttachmentStatus? {
        firstBlockingIssue(parts, settings)?.let { return it }

        val needsVisionFallback = parts.filterIsInstance<UIMessagePart.Image>()
            .map { inspectImage(it, settings) }
            .any { it.kind == ImageAttachmentStatusKind.FALLBACK }
        if (!needsVisionFallback) return null

        val health = VisionModelHealthChecker.probe(settings, providerManager)
        return if (health.isAvailable) {
            null
        } else {
            ImageAttachmentStatus(
                kind = ImageAttachmentStatusKind.BLOCKED,
                message = health.label,
            )
        }
    }

    private fun readableImageError(error: Throwable): String {
        val cause = if (error is ImageEncodingException) error.cause ?: error else error
        val message = cause.message.orEmpty()
        return when {
            "File does not exist" in message -> "图片文件不存在或已被删除"
            "Unsupported URL format" in message -> "图片来源暂不支持"
            "HEIC format requires Android 9" in message -> "HEIC 格式需要 Android 9 或更高版本"
            "AVIF format requires Android 12" in message -> "AVIF 格式需要 Android 12 或更高版本"
            "Failed to decode image" in message -> "图片格式无法解码（可能是不支持的格式或文件损坏）"
            "Failed to guess MIME type" in message -> "图片格式暂不支持（支持 JPEG/PNG/WebP/GIF/HEIC/AVIF）"
            else -> "图片不可读取：${message.ifBlank { cause::class.simpleName ?: "未知错误" }}"
        }
    }
}
