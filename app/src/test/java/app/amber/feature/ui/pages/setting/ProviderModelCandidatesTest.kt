package app.amber.feature.ui.pages.setting

import app.amber.ai.provider.GoogleAuthMode
import app.amber.ai.provider.Model
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ProviderModelCandidatesTest {
    @Test
    fun `login keeps existing enabled models while candidates carry the fetched catalog`() {
        val enabled = model("gpt-4o")
        val fetched = listOf(model("gpt-5"), model("gpt-5-mini"))
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            authMode = OpenAIAuthMode.CODEX_OAUTH,
            models = listOf(enabled),
        )
        val readyProvider = provider.copy(
            baseUrl = "https://chatgpt.com/backend-api/codex",
            useResponseApi = true,
        )
        val candidates = ProviderModelCandidates(
            requestKey = readyProvider.modelListRequestKey(),
            models = fetched,
        )

        assertEquals(listOf(enabled), provider.codexOAuthLoginSelection(fetched))
        assertEquals(fetched, candidates.models)
        assertTrue(candidates.requestKey == readyProvider.modelListRequestKey())
    }

    @Test
    fun `login seeds only the first fetched model when no model is enabled`() {
        val fetched = listOf(model("gpt-5"), model("gpt-5-mini"))
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            authMode = OpenAIAuthMode.CODEX_OAUTH,
        )

        assertEquals(listOf(fetched.first()), provider.codexOAuthLoginSelection(fetched))
        assertEquals(emptyList<Model>(), provider.codexOAuthLoginSelection(emptyList()))
    }

    @Test
    fun `candidates from old credentials are not reused after provider changes`() {
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            authMode = OpenAIAuthMode.CODEX_OAUTH,
            apiKey = "old",
        )
        val candidates = ProviderModelCandidates(
            requestKey = provider.modelListRequestKey(),
            models = listOf(model("gpt-5")),
        )
        val changedProvider = provider.copy(apiKey = "new")

        assertFalse(candidates.requestKey == changedProvider.modelListRequestKey())
    }

    @Test
    fun `late codex result is rejected after oauth generation changes`() {
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            authMode = OpenAIAuthMode.CODEX_OAUTH,
        )
        val requestKey = provider.modelListRequestKey()

        assertTrue(codexOAuthResultIsCurrent(3, 3, requestKey, provider))
        assertFalse(codexOAuthResultIsCurrent(3, 4, requestKey, provider))
    }

    @Test
    fun `gemini result is rejected when provider configuration changed`() {
        val provider = ProviderSetting.Google(
            id = Uuid.random(),
            authMode = GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH,
        )
        val requestKey = provider.modelListRequestKey()

        assertTrue(geminiOAuthResultIsCurrent(requestKey, provider))
        assertFalse(
            geminiOAuthResultIsCurrent(
                requestKey,
                provider.copy(baseUrl = "https://example.test"),
            )
        )
    }

    private fun model(modelId: String) = Model(
        id = Uuid.random(),
        modelId = modelId,
        displayName = modelId,
    )
}
