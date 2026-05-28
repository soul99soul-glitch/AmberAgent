package me.rerere.rikkahub.data.agent.webmount.login

import app.amber.feature.webmount.cookie.CookieSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMountLoginDetectorTest {
    @Test
    fun successUrlDoesNotCountAsSignedInWithoutCookies() {
        val target = target(
            requiredCookieSets = listOf(setOf("auth_token", "ct0")),
            successUrlPatterns = listOf(Regex("""^https://x\.com/home$""")),
        )

        val status = WebMountLoginDetector.evaluate(
            target = target,
            currentUrl = "https://x.com/home",
            snapshot = CookieSnapshot.EMPTY,
        )

        assertTrue(status is WebMountLoginStatus.UrlMatched)
    }

    @Test
    fun requiredCookieSetSignsIn() {
        val target = target(requiredCookieSets = listOf(setOf("auth_token", "ct0")))
        val snapshot = CookieSnapshot.fromRawHeaders(
            mapOf("https://x.com" to "auth_token=token; ct0=csrf")
        )

        val status = WebMountLoginDetector.evaluate(target, "https://x.com/i/flow/login", snapshot)

        assertEquals(WebMountLoginStatus.SignedIn(setOf("auth_token", "ct0")), status)
    }

    @Test
    fun reportsSmallestMissingCookieSet() {
        val target = target(
            requiredCookieSets = listOf(
                setOf("auth_token", "ct0", "twid"),
                setOf("fallback_session"),
            )
        )
        val snapshot = CookieSnapshot.fromRawHeaders(mapOf("https://x.com" to "auth_token=token"))

        val status = WebMountLoginDetector.evaluate(target, "https://x.com/login", snapshot)

        assertEquals(WebMountLoginStatus.MissingCookies(setOf("fallback_session")), status)
    }

    private fun target(
        requiredCookieSets: List<Set<String>>,
        successUrlPatterns: List<Regex> = emptyList(),
    ): WebMountLoginTarget {
        return WebMountLoginTarget(
            id = "x_com",
            displayName = "X.com",
            startUrl = "https://x.com/i/flow/login",
            stationId = null,
            urls = listOf("https://x.com"),
            requiredCookieSets = requiredCookieSets,
            candidateCookieNames = requiredCookieSets.flatten().distinct(),
            successUrlPatterns = successUrlPatterns,
            manualCookieFields = emptyList(),
        )
    }
}
