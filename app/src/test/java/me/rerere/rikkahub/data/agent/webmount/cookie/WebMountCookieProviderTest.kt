package app.amber.feature.webmount.cookie

import app.amber.feature.webmount.core.WebMountCookieBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMountCookieProviderTest {

    @Test
    fun mergesCookieHeadersByName_lastWriterWins() {
        val target = linkedMapOf<String, String>()

        WebMountCookieProvider.mergeCookieHeaderInto(
            raw = "TOKEN=global; other=1",
            target = target,
        )
        WebMountCookieProvider.mergeCookieHeaderInto(
            raw = "TOKEN=china; VALIDATE=t=upload:1",
            target = target,
        )

        assertEquals("TOKEN=china; other=1; VALIDATE=t=upload:1", target.values.joinToString("; "))
    }

    @Test
    fun preferredFor_movesEndpointsWithMatchedCookieUrlsToFront() {
        val provider = WebMountCookieProvider()
        val a = EndpointSpec(
            id = "a",
            displayName = "A",
            loginUrl = "https://a.example/login",
            apiBase = "https://a.example/api",
            origin = "https://a.example",
            cookieUrls = listOf("https://a.example"),
        )
        val b = EndpointSpec(
            id = "b",
            displayName = "B",
            loginUrl = "https://b.example/login",
            apiBase = "https://b.example/api",
            origin = "https://b.example",
            cookieUrls = listOf("https://b.example", "https://b-alt.example"),
        )
        val bundle = WebMountCookieBundle(
            header = "TOKEN=ok",
            sourceUrls = listOf("https://b-alt.example"),
        )

        val ordered = provider.preferredFor(bundle, listOf(a, b))

        assertEquals("b", ordered.first().id)
        assertEquals("a", ordered.last().id)
    }

    @Test
    fun preferredFor_returnsOriginalOrderWhenNoMatch() {
        val provider = WebMountCookieProvider()
        val a = EndpointSpec("a", "A", "x", "x", "x", listOf("https://a"))
        val b = EndpointSpec("b", "B", "y", "y", "y", listOf("https://b"))

        val ordered = provider.preferredFor(WebMountCookieBundle("TOKEN=z"), listOf(a, b))

        assertEquals(listOf("a", "b"), ordered.map { it.id })
    }

    @Test
    fun cookieBundle_valueParsesNamedCookies() {
        val bundle = WebMountCookieBundle(header = "A=1; B=two; C=three=four")

        assertEquals("1", bundle.value("A"))
        assertEquals("two", bundle.value("B"))
        assertEquals("three=four", bundle.value("C"))
        assertNull(bundle.value("missing"))
    }

    @Test
    fun cookieBundle_hasAllReturnsTrueOnlyWhenEveryNameIsPresent() {
        val bundle = WebMountCookieBundle(header = "A=1; B=2")

        assertTrue(bundle.hasAll(setOf("A", "B")))
        assertTrue(bundle.hasAll(setOf("A")))
        assertFalse(bundle.hasAll(setOf("A", "C")))
    }

    @Test
    fun guessSessionCookieNamePrefersKnownDurableCookie() {
        val provider = WebMountCookieProvider()

        val guessed = provider.guessSessionCookieName(
            newCookies = mapOf(
                "WEIBOCN_FROM" to "1110006030",
                "SUB" to "_2A25long-lived-session",
                "XSRF-TOKEN" to "short",
            ),
            preferredNames = listOf("SUB", "SUBP", "SSOLoginState"),
        )

        assertEquals("SUB", guessed)
    }

    @Test
    fun pickPresentCookieNameSkipsDeletedValues() {
        val provider = WebMountCookieProvider()

        val picked = provider.pickPresentCookieName(
            cookies = mapOf(
                "SUB" to "deleted",
                "SUBP" to "lasting",
            ),
            preferredNames = listOf("SUB", "SUBP"),
        )

        assertEquals("SUBP", picked)
    }
}
