package app.amber.feature.webmount.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMountModelsTest {

    @Test
    fun oauthToken_isExpiredHonorsSkew() {
        val now = 1_000_000L
        val token = WebMountOAuthToken(
            provider = "p",
            accessToken = "tok",
            refreshToken = null,
            scope = null,
            expiresAtMs = now + 60_000L,
            acquiredAtMs = now - 60_000L,
        )

        // Token still has 60s of life; default skew (5 min) considers it already expired.
        assertTrue(token.isExpired(nowMs = now))
        // With no skew, token is still alive.
        assertFalse(token.isExpired(nowMs = now, skewMs = 0L))
    }

    @Test
    fun probeResult_factoryHelpersClassify() {
        assertEquals(
            WebMountProbeResult.Success(WebMountCapability.READ_WRITE, "ok"),
            WebMountProbeResult.success(WebMountCapability.READ_WRITE, "ok"),
        )
        assertEquals(
            WebMountProbeResult.LoginRequired("need login"),
            WebMountProbeResult.loginRequired("need login"),
        )
        assertEquals(
            WebMountProbeResult.Degraded("rate-limited"),
            WebMountProbeResult.degraded("rate-limited"),
        )
        assertEquals(WebMountProbeResult.NotSupported, WebMountProbeResult.notSupported())
    }

    @Test
    fun cookieBundle_emptyConstantIsBlank() {
        assertTrue(WebMountCookieBundle.EMPTY.isEmpty)
    }
}
