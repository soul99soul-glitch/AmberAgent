package shared

import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.CustomBody
import app.amber.ai.provider.CustomHeader
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
            mode = "resume_response",
            responseId = "resp_123",
            responseSequenceNumber = 7L,
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
        assertEquals("resume_response", decoded.mode)
        assertEquals("resp_123", decoded.responseId)
        assertEquals(7L, decoded.responseSequenceNumber)
    }

    @Test
    fun encodePersistsFullToolNamesForBackgroundBridgeRebuild() {
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            apiKey = "secret",
            models = listOf(Model(modelId = "gpt-test", displayName = "GPT Test")),
        )
        val fullToolNames = listOf("tool_search", "search_web", "wm_type", "ask_user")

        val json = IosChatBackgroundPayloadJsonBridge.encode(
            runId = "run-full-tools",
            startedAt = 3L,
            inputDigest = "digest",
            conversationId = Uuid.random(),
            providerSetting = provider,
            params = TextGenerationParams(model = provider.models.first()),
            uploadMessages = emptyList(),
            displayMessages = emptyList(),
            fullToolNames = fullToolNames,
        )

        val decoded = IosChatBackgroundPayloadJsonBridge.decode(json)
        assertEquals(fullToolNames, decoded.fullToolNames)
    }

    @Test
    fun decodePayloadWithoutFullToolNamesFallsBackToEmpty() {
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            apiKey = "secret",
            models = listOf(Model(modelId = "gpt-test", displayName = "GPT Test")),
        )

        // Legacy payloads (pre-fullToolNames) must still decode: the field
        // defaults to empty and the background job falls back to the
        // handoff.params.tools path. Encode normally, then actually strip the
        // key — encodeDefaults=true would otherwise write "fullToolNames":[]
        // explicitly, which is not what a legacy payload looks like.
        val jsonWithKey = IosChatBackgroundPayloadJsonBridge.encode(
            runId = "run-legacy",
            startedAt = 4L,
            inputDigest = "digest",
            conversationId = Uuid.random(),
            providerSetting = provider,
            params = TextGenerationParams(model = provider.models.first()),
            uploadMessages = emptyList(),
            displayMessages = emptyList(),
        )
        val json = JsonObject(Json.parseToJsonElement(jsonWithKey).jsonObject - "fullToolNames").toString()

        val decoded = IosChatBackgroundPayloadJsonBridge.decode(json)
        assertEquals(emptyList(), decoded.fullToolNames)
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
