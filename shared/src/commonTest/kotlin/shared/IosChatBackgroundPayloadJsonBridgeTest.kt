package shared

import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class IosChatBackgroundPayloadJsonBridgeTest {
    @Test
    fun encodePersistsProviderIdWithoutProviderSecrets() {
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            apiKey = "sk-persisted-secret",
            models = listOf(Model(modelId = "gpt-test", displayName = "GPT Test")),
        )
        val params = TextGenerationParams(
            model = Model(
                modelId = "gpt-test",
                displayName = "GPT Test",
                providerOverwrite = ProviderSetting.Claude(apiKey = "claude-overwrite-secret"),
            ),
        )

        val json = IosChatBackgroundPayloadJsonBridge.encode(
            runId = "run-1",
            startedAt = 1L,
            inputDigest = "digest",
            conversationId = Uuid.random(),
            providerSetting = provider,
            params = params,
            uploadMessages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi")))),
            displayMessages = emptyList(),
        )

        assertFalse(json.contains("sk-persisted-secret"))
        assertFalse(json.contains("claude-overwrite-secret"))
        assertEquals(provider.id.toString(), IosChatBackgroundPayloadJsonBridge.decode(json).providerId)
    }
}
