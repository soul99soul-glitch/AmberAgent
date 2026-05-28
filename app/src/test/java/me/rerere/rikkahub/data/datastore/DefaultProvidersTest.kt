package app.amber.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProvidersTest {
    @Test
    fun `default providers are curated and deletable`() {
        assertEquals(
            listOf("OpenAI", "Gemini", "DeepSeek", "OpenRouter", "月之暗面", "xAI"),
            DEFAULT_PROVIDERS.map { it.name },
        )
        assertTrue(DEFAULT_PROVIDERS.all { !it.builtIn })
        assertFalse(DEFAULT_PROVIDERS.any { it.name == "AmberAgent" })
        assertFalse(DEFAULT_PROVIDERS.any { it.name == "AiHubMix" })
        assertFalse(DEFAULT_PROVIDERS.any { it.name == "Vercel AI Gateway" })
    }

    @Test
    fun `removed default providers include legacy AmberAgent`() {
        assertTrue(
            REMOVED_DEFAULT_PROVIDER_IDS.any {
                it.toString() == "a8d2d463-e8c0-41f2-b89e-f5eb8e716cce"
            },
        )
    }

    @Test
    fun `default tts providers do not include AiHubMix`() {
        val settings = Settings()

        assertFalse(settings.ttsProviders.any { it.name == "AiHubMix" })
        assertTrue(settings.ttsProviders.any { it.id == DEFAULT_SYSTEM_TTS_ID })
        assertTrue(settings.ttsProviders.any { it.id == settings.selectedTTSProviderId })
    }
}
