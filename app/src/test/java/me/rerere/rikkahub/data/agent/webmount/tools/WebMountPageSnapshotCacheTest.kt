package me.rerere.rikkahub.data.agent.webmount.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebMountPageSnapshotCacheTest {
    @Test
    fun cacheHitsForSameSemanticState() {
        WebMountPageSnapshotCache.clear()
        val state = pageState(fingerprint = "a", scrollY = 0)
        val payload = buildJsonObject { put("value", "cached") }

        WebMountPageSnapshotCache.put("s1", "observe", state, payload)

        assertEquals("cached", WebMountPageSnapshotCache.get("s1", "observe", state)!!.jsonObject["value"]!!.let {
            (it as JsonPrimitive).content
        })
    }

    @Test
    fun cacheMissesWhenFingerprintOrScrollChanges() {
        WebMountPageSnapshotCache.clear()
        val state = pageState(fingerprint = "a", scrollY = 0)
        val payload = buildJsonObject { put("value", "cached") }

        WebMountPageSnapshotCache.put("s1", "observe", state, payload)

        assertNull(WebMountPageSnapshotCache.get("s1", "observe", pageState(fingerprint = "b", scrollY = 0)))
        assertNull(WebMountPageSnapshotCache.get("s1", "observe", pageState(fingerprint = "a", scrollY = 100)))
    }

    @Test
    fun invalidateClearsOnlyTargetSession() {
        WebMountPageSnapshotCache.clear()
        val state = pageState(fingerprint = "a", scrollY = 0)
        val payload = buildJsonObject { put("value", "cached") }
        WebMountPageSnapshotCache.put("s1", "observe", state, payload)
        WebMountPageSnapshotCache.put("s2", "observe", state, payload)

        WebMountPageSnapshotCache.invalidate("s1")

        assertNull(WebMountPageSnapshotCache.get("s1", "observe", state))
        assertEquals(payload, WebMountPageSnapshotCache.get("s2", "observe", state))
    }

    @Test
    fun cacheIsGloballyBounded() {
        WebMountPageSnapshotCache.clear()
        val payload = buildJsonObject { put("value", "cached") }

        repeat(40) { index ->
            WebMountPageSnapshotCache.put("s$index", "observe", pageState(fingerprint = "f$index", scrollY = 0), payload)
        }

        assertEquals(32, WebMountPageSnapshotCache.sizeForTest())
        assertNull(WebMountPageSnapshotCache.get("s0", "observe", pageState(fingerprint = "f0", scrollY = 0)))
    }

    @Test
    fun cacheExpiresByTtl() {
        WebMountPageSnapshotCache.clear()
        var now = 1_000L
        WebMountPageSnapshotCache.setClockForTest { now }
        try {
            val state = pageState(fingerprint = "a", scrollY = 0)
            val payload = buildJsonObject { put("value", "cached") }

            WebMountPageSnapshotCache.put("s1", "observe", state, payload)
            now += 3_001L

            assertNull(WebMountPageSnapshotCache.get("s1", "observe", state))
        } finally {
            WebMountPageSnapshotCache.resetClockForTest()
        }
    }

    private fun pageState(fingerprint: String, scrollY: Int) = buildJsonObject {
        put("url", "https://example.com")
        put("semantic_fingerprint", fingerprint)
        put("scroll", buildJsonObject {
            put("x", 0)
            put("y", scrollY)
        })
    }
}
