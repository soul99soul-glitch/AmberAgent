package app.amber.ai.provider.providers.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravityOAuthTest {
    private val now = 1_000_000L

    @Test fun `missing and blank tokens are not usable`() {
        assertEquals(AntigravityAuthStatusCode.NOT_SIGNED_IN, AntigravityAuthStatus.from(null, now).code)
        assertFalse(AntigravityAuthStatus.from(AntigravityOAuthTokens(" ", "r", now + 1), now).usable)
    }

    @Test fun `expired token with refresh credential is refreshable`() {
        val status = AntigravityAuthStatus.from(AntigravityOAuthTokens("a", "r", now, projectId = "p", onboardedTier = "FREE"), now)
        assertEquals(AntigravityAuthStatusCode.TOKEN_EXPIRED, status.code)
        assertTrue(status.usable)
    }

    @Test fun `ready token is usable`() {
        val status = AntigravityAuthStatus.from(AntigravityOAuthTokens("a", "r", now + 1000, projectId = "p", onboardedTier = "FREE"), now)
        assertEquals(AntigravityAuthStatusCode.READY, status.code)
        assertTrue(status.usable)
    }

    @Test fun `redirect keeps the dedicated callback path`() {
        assertTrue(ANTIGRAVITY_OAUTH_REDIRECT_URI.endsWith("/oauth-callback"))
    }

    @Test fun `model envelope filters malformed and unsupported entries with fallback`() {
        val raw = """{"models":{"gemini-3-flash-high":{},"gemini-3-flash-low":{},"gemini-2.5-pro":{},"image-gen":{},"":{}}}"""
        val models = parseAntigravityModels(raw)
        assertEquals(listOf("gemini-3-flash"), models.map { it.modelId })
        assertTrue(parseAntigravityModels("{}").isEmpty())
        assertTrue(defaultAntigravityModels().isNotEmpty())
    }
}
