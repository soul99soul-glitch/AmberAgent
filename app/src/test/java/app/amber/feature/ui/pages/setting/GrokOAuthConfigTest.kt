package app.amber.feature.ui.pages.setting

import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.providers.grok.GROK_CLI_PROXY_BASE_URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GrokOAuthConfigTest {
    @Test
    fun customGrokApiKeyExitRestoresOriginalEndpointAndRetainsKey() {
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "My xAI",
            baseUrl = GROK_CLI_PROXY_BASE_URL,
            apiKey = "configured-xai-key",
            authMode = OpenAIAuthMode.GROK_OAUTH,
            useResponseApi = false,
        )

        val restored = provider.switchOpenAIAuthMode(OpenAIAuthMode.API_KEY, "https://api.x.ai/v1")

        assertEquals(OpenAIAuthMode.API_KEY, restored.authMode)
        assertEquals("https://api.x.ai/v1", restored.baseUrl)
        assertEquals(provider.apiKey, restored.apiKey)
        assertEquals(provider.id, restored.id)
        assertTrue(restored.useResponseApi)
        assertTrue(restored.isGrokProvider())
    }

    @Test
    fun grokApiKeyExitUsesSavedCustomXaiGateway() {
        val provider = ProviderSetting.OpenAI(
            baseUrl = GROK_CLI_PROXY_BASE_URL,
            authMode = OpenAIAuthMode.GROK_OAUTH,
        )

        val restored = provider.switchOpenAIAuthMode(OpenAIAuthMode.API_KEY, "https://gateway.example/xai/v1")

        assertEquals("https://gateway.example/xai/v1", restored.baseUrl)
    }

    @Test
    fun legacyGrokWithoutBackupFallsBackToXaiInsteadOfGenericOpenAi() {
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            baseUrl = GROK_CLI_PROXY_BASE_URL,
            authMode = OpenAIAuthMode.GROK_OAUTH,
        )

        val restored = provider.switchOpenAIAuthMode(OpenAIAuthMode.API_KEY)

        assertEquals("https://api.x.ai/v1", restored.baseUrl)
        assertTrue(restored.isGrokProvider())
    }

    @Test
    fun enteringGrokLeavesOriginalEndpointUntilLoginAndPinsChatProtocol() {
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://api.x.ai/v1",
            apiKey = "configured-xai-key",
            chatCompletionsPath = "/custom",
            useResponseApi = true,
        )

        val oauth = provider.switchOpenAIAuthMode(OpenAIAuthMode.GROK_OAUTH)

        assertEquals(provider.baseUrl, oauth.baseUrl)
        assertEquals(provider.apiKey, oauth.apiKey)
        assertEquals(OpenAIAuthMode.GROK_OAUTH, oauth.authMode)
        assertEquals("/chat/completions", oauth.chatCompletionsPath)
        assertEquals(false, oauth.useResponseApi)
    }
}
