package app.amber.feature.ui.pages.chat

import app.amber.ai.provider.GoogleAuthMode
import app.amber.ai.provider.Model
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.OpenAIBrand
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.providers.grok.GrokAuthStatus
import app.amber.ai.provider.providers.grok.GrokAuthStatusCode
import app.amber.ai.provider.providers.grok.GrokOAuthTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatConfigurationIssueTest {
    private val model = Model(modelId = "test-chat")
    private val provider = ProviderSetting.OpenAI(models = listOf(model), apiKey = "test-key", brand = OpenAIBrand.OPENAI)

    @Test fun missingProviderAndModelHaveDifferentRepairActions() {
        assertEquals(ChatConfigurationIssue.MissingProvider, chatConfigurationIssue(null, emptyList()))
        assertEquals(ChatConfigurationIssue.MissingModel, chatConfigurationIssue(null, listOf(provider)))
    }

    @Test fun validLocalHttpProviderRemainsUsable() {
        assertNull(chatConfigurationIssue(model, listOf(provider.copy(baseUrl = "http://localhost:11434/v1"))))
        assertNull(chatConfigurationIssue(model, listOf(provider.copy(baseUrl = "http://localhost:11434/v1", apiKey = "", brand = OpenAIBrand.GENERIC))))
    }

    @Test fun configurationErrorsAreSpecific() {
        assertEquals(ChatConfigurationIssue.DisabledProvider, chatConfigurationIssue(model, listOf(provider.copy(enabled = false))))
        assertEquals(ChatConfigurationIssue.InvalidAddress, chatConfigurationIssue(model, listOf(provider.copy(baseUrl = "missing-scheme"))))
        assertEquals(ChatConfigurationIssue.MissingKey, chatConfigurationIssue(model, listOf(provider.copy(apiKey = ""))))
    }

    @Test fun oauthUsesStoredSessionInsteadOfApiKey() {
        val codex = provider.copy(apiKey = "", baseUrl = "", authMode = OpenAIAuthMode.CODEX_OAUTH)
        assertNull(chatConfigurationIssue(model, listOf(codex), oauthReady = true))
        assertEquals(ChatConfigurationIssue.CodexSignIn, chatConfigurationIssue(model, listOf(codex), oauthReady = false))
        val google = ProviderSetting.Google(models = listOf(model), authMode = GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH)
        assertEquals(ChatConfigurationIssue.GeminiSignIn, chatConfigurationIssue(model, listOf(google), oauthReady = false))
        val antigravity = google.copy(authMode = GoogleAuthMode.ANTIGRAVITY_OAUTH)
        assertEquals(ChatConfigurationIssue.AntigravitySignIn, chatConfigurationIssue(model, listOf(antigravity), oauthReady = false))
        assertNull(chatConfigurationIssue(model, listOf(antigravity), oauthReady = true))
    }

    @Test fun grokOAuthRequiresGrokSignInWhenSessionIsMissing() {
        val grok = provider.copy(name = "xAI", apiKey = "", authMode = OpenAIAuthMode.GROK_OAUTH)
        assertEquals(ChatConfigurationIssue.GrokSignIn, chatConfigurationIssue(model, listOf(grok), oauthReady = false))
        assertNull(chatConfigurationIssue(model, listOf(grok), oauthReady = true))
    }

    @Test fun antigravityAuthStatusTreatsRefreshableExpiryAsUsable() {
        val status = app.amber.ai.provider.providers.google.AntigravityAuthStatus.from(
            app.amber.ai.provider.providers.google.AntigravityOAuthTokens("access", "refresh", 1000L, projectId = "p", onboardedTier = "FREE"),
            1000L,
        )
        assertTrue(status.usable)
    }

    @Test fun grokAuthStatusTreatsRefreshableExpiryAsUsable() {
        val status = GrokAuthStatus.from(
            GrokOAuthTokens("access", "refresh", 1000L),
            1000L,
        )
        assertEquals(GrokAuthStatusCode.TOKEN_EXPIRED, status.code)
        assertTrue(status.usable)
    }

    @Test fun vertexServiceAccountDoesNotRequireApiKey() {
        val google = ProviderSetting.Google(models = listOf(model), baseUrl = "", vertexAI = true, useServiceAccount = true, privateKey = "service-account")
        assertNull(chatConfigurationIssue(model, listOf(google)))
    }

    @Test fun modelOverrideUsesItsOwnConfiguration() {
        val overridden = model.copy(providerOverwrite = provider.copy(apiKey = ""))
        assertEquals(ChatConfigurationIssue.MissingKey, chatConfigurationIssue(overridden, listOf(provider.copy(models = listOf(overridden)))))
    }
}
