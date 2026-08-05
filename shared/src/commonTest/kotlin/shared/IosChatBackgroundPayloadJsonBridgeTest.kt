package shared

import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.CustomBody
import app.amber.ai.provider.CustomHeader
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
                customHeaders = listOf(CustomHeader("X-Model-Token", "model-header-secret")),
                customBodies = listOf(CustomBody("model_token", JsonPrimitive("model-body-secret"))),
                providerOverwrite = ProviderSetting.Claude(apiKey = "claude-overwrite-secret"),
            ),
            customHeaders = listOf(CustomHeader("Authorization", "Bearer params-header-secret")),
            customBody = listOf(CustomBody("access_token", JsonPrimitive("params-body-secret"))),
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
        assertFalse(json.contains("model-header-secret"))
        assertFalse(json.contains("model-body-secret"))
        assertFalse(json.contains("params-header-secret"))
        assertFalse(json.contains("params-body-secret"))
        val decoded = IosChatBackgroundPayloadJsonBridge.decode(json)
        assertEquals(provider.id.toString(), decoded.providerId)
        assertEquals(emptyList(), decoded.params.model.customHeaders)
        assertEquals(emptyList(), decoded.params.model.customBodies)
        assertEquals(emptyList(), decoded.params.customHeaders)
        assertEquals(emptyList(), decoded.params.customBody)
        assertFalse(decoded.generativeUiRequired)
        assertFalse(decoded.generativeUiFallbackAttempted)
    }

    @Test
    fun encodePersistsGenerativeUiFallbackContract() {
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            apiKey = "secret",
            models = listOf(Model(modelId = "gpt-test", displayName = "GPT Test")),
        )

        val json = IosChatBackgroundPayloadJsonBridge.encode(
            runId = "run-widget",
            startedAt = 2L,
            inputDigest = "digest",
            conversationId = Uuid.random(),
            providerSetting = provider,
            params = TextGenerationParams(model = provider.models.first()),
            uploadMessages = emptyList(),
            displayMessages = emptyList(),
            generativeUiRequired = true,
            generativeUiExpectSlides = true,
            generativeUiExpectFullHtmlDeck = true,
            generativeUiFallbackAttempted = true,
        )

        val decoded = IosChatBackgroundPayloadJsonBridge.decode(json)
        assertTrue(decoded.generativeUiRequired)
        assertTrue(decoded.generativeUiExpectSlides)
        assertTrue(decoded.generativeUiExpectFullHtmlDeck)
        assertTrue(decoded.generativeUiFallbackAttempted)
    }
}
