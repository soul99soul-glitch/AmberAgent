package app.amber.core.ai.vision

import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Modality
import app.amber.ai.provider.ProviderManager
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.settings.findProvider

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
