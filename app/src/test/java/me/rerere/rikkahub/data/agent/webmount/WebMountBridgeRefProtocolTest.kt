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
        // The WebMount primitives now live across multiple sibling factory files
        // under `webmount/tools/`. Concatenate the package's source so the
        // assertions don't care which file each tool ended up in.
        val source = locatePrimitiveToolsSources().joinToString("\n") { it.readText() }

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

    private fun locatePrimitiveToolsSources(): List<File> {
        val candidates = listOf(
            File("src/main/java/me/rerere/rikkahub/data/agent/webmount/tools"),
            File("app/src/main/java/me/rerere/rikkahub/data/agent/webmount/tools"),
        )
        val dir = candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate webmount/tools package directory")
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".kt") }
            ?.sortedBy { it.name }
            ?.toList()
            ?: error("webmount/tools directory has no .kt files")
    }
}
