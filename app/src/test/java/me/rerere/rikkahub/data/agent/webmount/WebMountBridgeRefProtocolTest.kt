package me.rerere.rikkahub.data.agent.webmount

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebMountBridgeRefProtocolTest {
    @Test
    fun bridgeUsesStandardsCompliantCssRefs() {
        val source = locateBridge().readText()

        assertTrue(source.contains(":nth-of-type("))
        assertFalse("bridge.js must not emit the old non-standard :nth(...) selector", source.contains(":nth("))
        assertTrue(source.contains("snapshot_id"))
        assertTrue(source.contains("fingerprint"))
        assertTrue(source.contains("case 'get'"))
        assertTrue(source.contains("allowFingerprintFallback: false"))
        assertTrue(source.contains("requireStableRect: true"))
        assertTrue(source.contains("sameFingerprint(fingerprintOf(entry.el), entry.fingerprint, requireStableRect)"))
        assertTrue(source.contains("sameFingerprint(fingerprintOf(el), entry.fingerprint, requireStableRect)"))
        assertTrue(source.contains("selector_fallback"))
        assertTrue(source.contains("case 'feishu_snapshot'"))
        assertTrue(source.contains("not_feishu_doc_page"))
        assertTrue(source.contains("current_webview_session"))
    }

    @Test
    fun primitiveToolsExposeTargetAndGetTool() {
        val source = locatePrimitiveTools().readText()

        assertTrue(source.contains("name = \"wm_get\""))
        assertTrue(source.contains("put(\"target\""))
        assertTrue(source.contains("wm_click requires target or selector"))
        assertTrue(source.contains("wm_type requires target or selector"))
    }

    private fun locateBridge(): File {
        val candidates = listOf(
            File("src/main/assets/webmount/bridge.js"),
            File("app/src/main/assets/webmount/bridge.js"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Could not locate webmount bridge.js")
    }

    private fun locatePrimitiveTools(): File {
        val candidates = listOf(
            File("src/main/java/me/rerere/rikkahub/data/agent/webmount/tools/WebMountPrimitiveTools.kt"),
            File("app/src/main/java/me/rerere/rikkahub/data/agent/webmount/tools/WebMountPrimitiveTools.kt"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Could not locate WebMountPrimitiveTools.kt")
    }
}
