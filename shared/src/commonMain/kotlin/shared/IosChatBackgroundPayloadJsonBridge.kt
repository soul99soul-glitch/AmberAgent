package shared

import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.core.agent.utils.JsonInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Serializable
data class IosChatBackgroundPayload(
    val runId: String,
    val startedAt: Long,
    val inputDigest: String,
    val conversationId: Uuid,
    val providerId: String,
    val params: TextGenerationParams,
    val uploadMessages: List<UIMessage>,
    val displayMessages: List<UIMessage>,
    val mode: String = "continue_model",
)

/** Swift-facing bridge for persisted iOS chat background generation payloads. */
@OptIn(ExperimentalUuidApi::class)
object IosChatBackgroundPayloadJsonBridge {
    fun encode(
        runId: String,
        startedAt: Long,
        inputDigest: String,
        conversationId: Uuid,
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        uploadMessages: List<UIMessage>,
        displayMessages: List<UIMessage>,
        mode: String = "continue_model",
    ): String = JsonInstant.encodeToString(
        IosChatBackgroundPayload(
            runId = runId,
            startedAt = startedAt,
            inputDigest = inputDigest,
            conversationId = conversationId,
            providerId = providerSetting.id.toString(),
            params = params.withoutProviderOverwrite(),
            uploadMessages = uploadMessages,
            displayMessages = displayMessages,
            mode = mode,
        )
    )

    fun decode(json: String): IosChatBackgroundPayload = JsonInstant.decodeFromString(json)

    private fun TextGenerationParams.withoutProviderOverwrite(): TextGenerationParams {
        return if (model.providerOverwrite == null) this else copy(model = model.copy(providerOverwrite = null))
    }
}
