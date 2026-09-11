package app.amber.ai.provider.providers.grok

import app.amber.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrokOAuthTest {
    @Test fun statusDistinguishesMissingAndRefreshableExpiredToken() {
        assertEquals(GrokAuthStatusCode.NOT_SIGNED_IN, GrokAuthStatus.from(null, 100L).code)
        val expired = GrokAuthStatus.from(GrokOAuthTokens("access", "refresh", 100L), 100L)
        assertEquals(GrokAuthStatusCode.TOKEN_EXPIRED, expired.code)
        assertTrue(expired.usable)
    }

    @Test fun parserAcceptsOpenAiDataModelsAndSkipsMalformedEntries() {
        val models = parseGrokModels("""{"data":[{"id":" grok-4.5 ","name":"Grok 4.5"},{"id":""},{"name":"missing-id"}]}""")
        assertEquals(1, models.size)
        assertEquals("grok-4.5", models.single().modelId)
        assertEquals("Grok 4.5", models.single().displayName)
    }

    @Test fun parserAcceptsModelsEnvelopeAndFallbackIsNonEmpty() {
        assertEquals("grok-4.6", parseGrokModels("""{"models":[{"model":"grok-4.6"}]}""").single().modelId)
        assertTrue(defaultGrokOAuthModels().isNotEmpty())
    }
}
