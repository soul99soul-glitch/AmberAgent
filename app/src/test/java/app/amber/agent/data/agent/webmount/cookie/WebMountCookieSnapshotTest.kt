package app.amber.feature.webmount.cookie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMountCookieSnapshotTest {
    @Test
    fun parsesMultipleUrlsAndUsesLastValueByName() {
        val snapshot = CookieSnapshot.fromRawHeaders(
            mapOf(
                "https://x.com" to "auth_token=old; ct0=csrf",
                "https://api.x.com" to "auth_token=new; twid=u%3D1",
            )
        )

        assertEquals(listOf("old", "new"), snapshot.valuesByName("auth_token"))
        assertEquals("new", snapshot.asLastWinsMap()["auth_token"])
        assertTrue(snapshot.containsAll(setOf("auth_token", "ct0")))
        assertEquals("auth_token=new; ct0=csrf; twid=u%3D1", snapshot.toHeader())
        assertEquals(listOf("https://x.com", "https://api.x.com"), snapshot.sourceUrls())
    }

    @Test
    fun ignoresUnusableCookieValuesWhenCheckingPresence() {
        val snapshot = CookieSnapshot.fromRawHeaders(
            mapOf("https://example.com" to "sessionid=deleted; other=value")
        )

        assertFalse(snapshot.containsAll(setOf("sessionid")))
        assertEquals(listOf("sessionid"), snapshot.missing(setOf("sessionid")))
        assertFalse(snapshot.asLastWinsMap().containsKey("sessionid"))
    }

    @Test
    fun tombstoneDoesNotOverrideEarlierUsableCookie() {
        val snapshot = CookieSnapshot.fromRawHeaders(
            mapOf(
                "https://x.com" to "auth_token=valid; ct0=csrf",
                "https://api.x.com" to "auth_token=deleted",
            )
        )

        assertTrue(snapshot.containsAll(setOf("auth_token", "ct0")))
        assertEquals("valid", snapshot.asLastWinsMap()["auth_token"])
        assertEquals("auth_token=valid; ct0=csrf", snapshot.toHeader())
    }
}
