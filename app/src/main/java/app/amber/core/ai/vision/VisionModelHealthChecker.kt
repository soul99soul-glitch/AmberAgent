package app.amber.core.ai.vision

import android.content.Context
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Modality
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import kotlinx.coroutines.CancellationException
import app.amber.agent.R
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

/**
 * Resolved once at a production boundary so the health checker never owns a
 * global locale or Context. Tests can use [english] without Android resources.
 */
data class VisionModelHealthStrings(
    val checking: String,
    val available: String,
    val notConfigured: String,
    val unsupported: String,
    val providerMissing: String,
    val failed: (String) -> String,
    val unknownError: String,
) {
    companion object {
        fun english(): VisionModelHealthStrings = VisionModelHealthStrings(
            checking = "Checking",
            available = "Available",
            notConfigured = "Not configured",
            unsupported = "Image input unsupported",
            providerMissing = "Provider unavailable",
            failed = { detail -> "Unavailable: $detail" },
            unknownError = "Health check failed",
        )

        fun from(context: Context): VisionModelHealthStrings = VisionModelHealthStrings(
            checking = context.getString(R.string.vision_model_health_checking),
            available = context.getString(R.string.vision_model_health_available),
            notConfigured = context.getString(R.string.vision_model_health_not_configured),
            unsupported = context.getString(R.string.vision_model_health_unsupported),
            providerMissing = context.getString(R.string.vision_model_health_provider_missing),
            failed = { detail -> context.getString(R.string.vision_model_health_failed, detail) },
            unknownError = context.getString(R.string.vision_model_health_unknown_error),
        )
    }
}

object VisionModelHealthChecker {
    fun checking(strings: VisionModelHealthStrings = VisionModelHealthStrings.english()): VisionModelHealth =
        VisionModelHealth(VisionModelHealthKind.CHECKING, strings.checking)

    suspend fun probe(
        settings: Settings,
        providerCatalog: ProviderCatalog,
        strings: VisionModelHealthStrings = VisionModelHealthStrings.english(),
    ): VisionModelHealth {
        val model = settings.findModelById(settings.ocrModelId)
            ?: return VisionModelHealth(VisionModelHealthKind.NOT_CONFIGURED, strings.notConfigured)
        if (Modality.IMAGE !in model.inputModalities) {
            return VisionModelHealth(VisionModelHealthKind.UNSUPPORTED, strings.unsupported)
        }
        val providerSetting = model.findProvider(settings.providers)
            ?: return VisionModelHealth(VisionModelHealthKind.PROVIDER_MISSING, strings.providerMissing)
        val provider = providerCatalog.text(providerSetting)
        return runCatching {
            provider.complete(
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
            onSuccess = { VisionModelHealth(VisionModelHealthKind.AVAILABLE, strings.available) },
            onFailure = {
                if (it is CancellationException) throw it
                val detail = it.message?.takeIf { message -> message.isNotBlank() } ?: strings.unknownError
                VisionModelHealth(VisionModelHealthKind.FAILED, strings.failed(detail))
            },
        )
    }
}
