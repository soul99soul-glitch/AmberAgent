package me.rerere.rikkahub.data.ai.vision

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider

private const val TINY_PNG =
    "data:image/png;base64," +
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="

enum class VisionModelHealthKind {
    CHECKING,
    AVAILABLE,
    NOT_CONFIGURED,
    UNSUPPORTED,
    PROVIDER_MISSING,
    FAILED,
}

data class VisionModelHealth(
    val kind: VisionModelHealthKind,
    val label: String,
) {
    val isAvailable: Boolean get() = kind == VisionModelHealthKind.AVAILABLE
}

object VisionModelHealthChecker {
    fun checking(): VisionModelHealth =
        VisionModelHealth(VisionModelHealthKind.CHECKING, "检测中")

    suspend fun probe(
        settings: Settings,
        providerManager: ProviderManager,
    ): VisionModelHealth {
        val model = settings.findModelById(settings.ocrModelId)
            ?: return VisionModelHealth(VisionModelHealthKind.NOT_CONFIGURED, "未配置")
        if (Modality.IMAGE !in model.inputModalities) {
            return VisionModelHealth(VisionModelHealthKind.UNSUPPORTED, "不支持图片")
        }
        val providerSetting = model.findProvider(settings.providers)
            ?: return VisionModelHealth(VisionModelHealthKind.PROVIDER_MISSING, "提供商不可用")
        val provider = providerManager.getProviderByType(providerSetting)
        return runCatching {
            provider.generateText(
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage.system("Reply with OK if you can receive this test image."),
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(
                            UIMessagePart.Text("Vision probe. Reply only OK."),
                            UIMessagePart.Image(TINY_PNG),
                        )
                    )
                ),
                params = TextGenerationParams(model = model),
            )
        }.fold(
            onSuccess = { VisionModelHealth(VisionModelHealthKind.AVAILABLE, "可用") },
            onFailure = {
                VisionModelHealth(VisionModelHealthKind.FAILED, "不可用：${it.message ?: "检测失败"}")
            },
        )
    }
}
