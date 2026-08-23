package app.amber.ai.provider.providers.openai

import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderSetting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * P6-01 — strict capability gating (plan §P6-01 严格适用范围 / 不适用):
 * only the official https://api.openai.com + API key + Responses API
 * qualifies; custom endpoints, Codex OAuth, coding plans and third-party
 * OpenAI-compatible hosts never do (the settings toggle is driven by this).
 */
class ResponseResumeCapabilityTest {

    private fun setting(
        baseUrl: String = "https://api.openai.com/v1",
        authMode: OpenAIAuthMode = OpenAIAuthMode.API_KEY,
        useResponseApi: Boolean = true,
    ) = ProviderSetting.OpenAI(
        id = Uuid.random(),
        baseUrl = baseUrl,
        useResponseApi = useResponseApi,
        authMode = authMode,
        name = "OpenAI",
    )

    @Test
    fun officialEndpointApiKeyResponsesApiQualifies() {
        assertTrue(setting().supportsResponsesResume())
        // Trailing slash variant still matches the official host.
        assertTrue(setting(baseUrl = "https://api.openai.com/").supportsResponsesResume())
    }

    @Test
    fun customOpenAiCompatibleEndpointsNeverQualify() {
        assertFalse(setting(baseUrl = "https://ark.cn-beijing.volces.com/api/v3").supportsResponsesResume())
        assertFalse(setting(baseUrl = "https://my-proxy.example.com/v1").supportsResponsesResume())
        assertFalse(setting(baseUrl = "https://api.deepseek.com/v1").supportsResponsesResume())
        assertFalse(setting(baseUrl = "https://open.bigmodel.cn/api/paas/v4").supportsResponsesResume())
        assertFalse(setting(baseUrl = "https://api.moonshot.cn/v1").supportsResponsesResume())
    }

    @Test
    fun codexOauthAndCodingPlansNeverQualify() {
        assertFalse(setting(authMode = OpenAIAuthMode.CODEX_OAUTH).supportsResponsesResume())
        assertFalse(setting(authMode = OpenAIAuthMode.ZHIPU_CODING_PLAN).supportsResponsesResume())
        assertFalse(setting(authMode = OpenAIAuthMode.KIMI_CODING_PLAN).supportsResponsesResume())
        assertFalse(setting(authMode = OpenAIAuthMode.MIMO_CODING_PLAN).supportsResponsesResume())
        assertFalse(setting(authMode = OpenAIAuthMode.MINIMAX_TOKEN_PLAN).supportsResponsesResume())
    }

    @Test
    fun chatCompletionsOnlyNeverQualifies() {
        assertFalse(setting(useResponseApi = false).supportsResponsesResume())
    }

    @Test
    fun hostCapabilityMapDeclaresStoredResponsesOnlyForOfficialHost() {
        assertTrue(resolveResponseProviderCapabilities("api.openai.com").supportsStoredResponses)
        assertFalse(resolveResponseProviderCapabilities("ark.cn-beijing.volces.com").supportsStoredResponses)
        assertFalse(resolveResponseProviderCapabilities("chatgpt.com").supportsStoredResponses)
        assertFalse(resolveResponseProviderCapabilities("my-proxy.example.com").supportsStoredResponses)
    }
}
