package app.amber.feature.webmount.primitives

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

class WebMountBridgeBootstrapTest {
    @Test
    fun packagedSourcesUseOneOrderedPrivateBootstrap() {
        val assets = locateAssets()
        val sourceNames = listOf(
            "bridge-network.js",
            "bridge-feishu.js",
            "bridge.js",
        )
        assertEquals(
            sourceNames,
            Json.decodeFromString<List<String>>(
                File(assets, WebMountBridgeBootstrap.MANIFEST_ASSET).readText(),
            ),
        )
        val reads = mutableListOf<String>()
        val source = WebMountBridgeBootstrap.load { assetPath ->
            reads += assetPath
            File(assets, assetPath).readText()
        }
        assertEquals(
            listOf(WebMountBridgeBootstrap.MANIFEST_ASSET) + sourceNames.map { "webmount/$it" },
            reads,
        )
        // Exact text is the contract also used by both Node/browser harnesses.
        val expected = "(function () {\n" + sourceNames.joinToString("\n;\n") {
            File(assets, "webmount/$it").readText()
        } + "\n})();"
        assertEquals(expected, source)
    }

    @Test
    fun missingManifestOrAnyListedSourceFailsAssembly() {
        val assets = locateAssets()
        val sourceNames = Json.decodeFromString<List<String>>(
            File(assets, WebMountBridgeBootstrap.MANIFEST_ASSET).readText(),
        )
        val requiredPaths = listOf(WebMountBridgeBootstrap.MANIFEST_ASSET) + sourceNames.map { "webmount/$it" }
        requiredPaths.forEach { missing ->
            val error = IOException("missing asset: $missing")
            try {
                WebMountBridgeBootstrap.load { assetPath ->
                    if (assetPath == missing) throw error
                    File(assets, assetPath).readText()
                }
                fail("Missing $missing must fail before returning a partial bootstrap")
            } catch (actual: IOException) {
                assertSame(error, actual)
            }
        }
    }

    private fun locateAssets(): File = listOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { File(it, WebMountBridgeBootstrap.MANIFEST_ASSET).isFile }
        ?: error("Could not locate WebMount bootstrap assets")
}
