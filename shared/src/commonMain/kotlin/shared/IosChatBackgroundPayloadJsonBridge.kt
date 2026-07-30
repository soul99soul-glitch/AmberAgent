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
            params = params.withoutSecrets(),
            uploadMessages = uploadMessages,
            displayMessages = displayMessages,
            mode = mode,
        )
    )

    // @Throws 必须声明：Kotlin/Native 不会把未声明的异常桥接成 Swift NSError，
    // 否则损坏的 handoff payload 会让冷启动恢复 SIGABRT 而非回退 nil（见 loadHandoff 的 do/catch）。
    @Throws(Throwable::class)
    fun decode(json: String): IosChatBackgroundPayload = JsonInstant.decodeFromString(json)

    private fun TextGenerationParams.withoutSecrets(): TextGenerationParams = copy(
        model = model.copy(
            customHeaders = emptyList(),
            customBodies = emptyList(),
            providerOverwrite = null,
        ),
        customHeaders = emptyList(),
        customBody = emptyList(),
    )
}
