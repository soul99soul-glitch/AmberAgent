package app.amber.ai.provider.providers.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleGeminiOAuthTest {
    private val now = 1_000_000L

    @Test
    fun `missing token is not usable even when OAuth mode is selected`() {
        val status = GoogleGeminiAuthStatus.from(null, now)

        assertEquals(GoogleGeminiAuthStatusCode.NOT_SIGNED_IN, status.code)
        assertFalse(status.usable)
    }

    @Test
    fun `blank access token is not usable`() {
        val status = GoogleGeminiAuthStatus.from(
            GoogleGeminiAuthTokens(
                accessToken = "   ",
                refreshToken = "refresh-token",
                expiresAtMillis = now + 60_000L,
                projectId = "ghost-project",
                onboardedTier = "FREE",
            ),
            now,
        )

        assertEquals(GoogleGeminiAuthStatusCode.TOKEN_MISSING, status.code)
        assertFalse(status.usable)
    }

    @Test
    fun `token without onboarded project can complete lazy onboarding`() {
        val status = GoogleGeminiAuthStatus.from(
            GoogleGeminiAuthTokens(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                expiresAtMillis = now + 60_000L,
                projectId = null,
                onboardedTier = null,
            ),
            now,
        )

        assertEquals(GoogleGeminiAuthStatusCode.PROJECT_MISSING, status.code)
        assertTrue(status.usable)
    }

    @Test
    fun `expired token with refresh credential keeps the OAuth session usable`() {
        val status = GoogleGeminiAuthStatus.from(
            GoogleGeminiAuthTokens(
                accessToken = "expired-token",
                refreshToken = "refresh-token",
                expiresAtMillis = now,
                projectId = "ghost-project",
                onboardedTier = "FREE",
            ),
            now,
        )

        assertEquals(GoogleGeminiAuthStatusCode.TOKEN_EXPIRED, status.code)
        assertTrue(status.usable)
    }

    @Test
    fun `expired token without refresh credential is not usable`() {
        val status = GoogleGeminiAuthStatus.from(
            GoogleGeminiAuthTokens(
                accessToken = "expired-token",
                refreshToken = null,
                expiresAtMillis = now,
                projectId = "ghost-project",
                onboardedTier = "FREE",
            ),
            now,
        )

        assertEquals(GoogleGeminiAuthStatusCode.TOKEN_EXPIRED, status.code)
        assertFalse(status.usable)
    }

    @Test
    fun `fully onboarded unexpired token is usable`() {
        val status = GoogleGeminiAuthStatus.from(
            GoogleGeminiAuthTokens(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                expiresAtMillis = now + 60_000L,
                projectId = "ghost-project",
                onboardedTier = "FREE",
            ),
            now,
        )

        assertEquals(GoogleGeminiAuthStatusCode.READY, status.code)
        assertTrue(status.usable)
    }
}
