package app.amber.feature.ui.components.richtext.tree

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.amber.core.settings.Settings
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.Navigator
import app.amber.highlight.Highlighter
import app.amber.highlight.LocalHighlighter
import kotlinx.coroutines.CoroutineExceptionHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Stage-1 golden pin of today's renderer output for the 32-sample corpus.
 *
 * Regenerate goldens:
 *   ./gradlew :app:testDebugUnitTest -PupdateMarkdownSnapshots=true \
 *     --tests "app.amber.feature.ui.components.richtext.tree.MarkdownRendererSnapshotTest"
 *
 * Stage 3 MUST keep these byte-identical (zero-change proof of the re-type of
 * the 2916-line Markdown.kt). After the mechanical re-type, this suite must
 * still produce the same dumps without -PupdateMarkdownSnapshots.
 *
 * Determinism / normalization (see [dumpNormalized]):
 *  - Renders through the production chat entry [MarkdownBlock] with the default
 *    (non-streaming) params — exactly the non-widget assistant-message path
 *    in ChatMessageRenderers.kt. Streaming is off, so no async re-parse runs.
 *  - The renderer requires three CompositionLocals: [LocalSettings] (read for
 *    `displaySetting.enableLatexRendering`, default true), [LocalNavController]
 *    (HighlightCodeBlock stores a Navigator for an "open in WebView" click that
 *    the snapshot never fires), and [LocalHighlighter] (code-block syntax
 *    highlighting). We provide a stock [Settings], an empty [Navigator], and a
 *    real [Highlighter] (its QuickJS init is lazy + off-thread, and the code
 *    Text starts as the raw source, so the dumped *.text* is the raw code
 *    whether or not async highlighting completes). Everything else (LocalDarkMode,
 *    LocalExportContext, LocalTextStyle, …) already has a default. We do NOT use the
 *    app's AmberAgentTheme wrapper — it casts LocalView.context to Activity and
 *    pulls DataStore/Koin, neither of which exists in a Robolectric unit test.
 *    A bare MaterialTheme is the minimal wrapper that satisfies the renderer.
 *  - KaTeX (samples 18/19) renders via JLatexMath onto a Canvas (no semantics
 *    text) when the drawable builds, or falls back to a plain Text(rawFormula)
 *    when it cannot. Either branch is synchronous (inside remember), so it is
 *    stable run-to-run on a given JVM. No special-casing is needed.
 *  - Images (sample 06) load asynchronously via Coil; [dumpNormalized] collapses
 *    image nodes to `image=<alt-text>`, which is static AST data, so the golden
 *    never depends on whether the bitmap finished loading.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class MarkdownRendererSnapshotTest(
    private val sampleName: String,
    private val sampleFile: File,
) {

    // The code-block highlighter (QuickJS + Prism) loads a native `.so` that does
    // not exist on the host JVM, so HighlightText's off-thread highlight() resumes
    // its LaunchedEffect with an UnsatisfiedLinkError. That async failure is
    // irrelevant to what we pin (the dumped code Text is the raw source either way),
    // so we swallow it in the compose effect context instead of letting it crash
    // waitForIdle(). This is the same "async load can't complete on the JVM"
    // normalization the test header documents.
    private val swallowAsyncLoadFailures = CoroutineExceptionHandler { _, t ->
        if (t !is UnsatisfiedLinkError) throw t
    }

    @get:Rule
    val compose = createComposeRule(swallowAsyncLoadFailures)

    @Test
    fun semanticsDumpMatchesGolden() {
        val content = sampleFile.readText()

        val highlighter = Highlighter(RuntimeEnvironment.getApplication())
        compose.setContent {
            // Minimal locals the renderer reads:
            //  - LocalSettings (enableLatexRendering),
            //  - LocalNavController (HighlightCodeBlock stores it for a click handler
            //    the snapshot never fires),
            //  - LocalHighlighter (code-block syntax highlighting). The Highlighter's
            //    QuickJS init is lazy and runs off-thread inside the highlight()
            //    LaunchedEffect; the visible Text starts as the raw `code` and the
            //    dump captures only `.text` (not span colors), so the code-block text
            //    is the raw source regardless of whether highlighting completes.
            CompositionLocalProvider(
                LocalSettings provides Settings(),
                LocalNavController provides Navigator(mutableListOf()),
                LocalHighlighter provides highlighter,
            ) {
                MaterialTheme {
                    MarkdownBlock(content = content)
                }
            }
        }
        compose.waitForIdle()

        val actual = compose.onRoot().fetchSemanticsNode().dumpNormalized()
        val goldenFile = File(SNAPSHOT_DIR, "$sampleName.txt")

        if (UPDATE_SNAPSHOTS) {
            goldenFile.parentFile?.mkdirs()
            goldenFile.writeText(actual)
            return
        }

        assertTrue(
            "golden snapshot missing for '$sampleName' — run with " +
                "-PupdateMarkdownSnapshots=true to generate it (${goldenFile.path})",
            goldenFile.exists(),
        )
        val expected = goldenFile.readText()
        if (expected != actual) {
            throw AssertionError(buildSnapshotDiff(sampleName, expected, actual))
        }
    }

    companion object {
        private val SNAPSHOT_DIR = File("src/test/resources/markdown-corpus-snapshots")
        private val CORPUS_DIR = File("src/test/resources/markdown-corpus")
        private val UPDATE_SNAPSHOTS: Boolean =
            System.getProperty("updateMarkdownSnapshots") == "true"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun samples(): List<Array<Any>> =
            CORPUS_DIR.listFiles { f -> f.extension == "md" }
                .orEmpty()
                .sortedBy { it.name }
                .map { arrayOf(it.nameWithoutExtension, it) }

        /** Unified-ish diff: the two strings plus the first differing line. */
        private fun buildSnapshotDiff(name: String, expected: String, actual: String): String {
            val exp = expected.lines()
            val act = actual.lines()
            val firstDiff = (0 until maxOf(exp.size, act.size)).firstOrNull {
                exp.getOrNull(it) != act.getOrNull(it)
            }
            val pointer = if (firstDiff != null) {
                "\nfirst diff at line ${firstDiff + 1}:\n" +
                    "  expected: ${exp.getOrNull(firstDiff)}\n" +
                    "  actual:   ${act.getOrNull(firstDiff)}"
            } else {
                ""
            }
            return buildString {
                appendLine("snapshot mismatch for sample '$name'")
                appendLine(pointer)
                appendLine("----- EXPECTED -----")
                appendLine(expected)
                appendLine("----- ACTUAL -----")
                appendLine(actual)
                appendLine(
                    "Regenerate with: ./gradlew :app:testDebugUnitTest " +
                        "-PupdateMarkdownSnapshots=true --tests " +
                        "\"app.amber.feature.ui.components.richtext.tree.MarkdownRendererSnapshotTest\"",
                )
            }
        }
    }
}

/**
 * Wire-version guard for the golden parser blobs. Each `.pmda` in the corpus is a
 * binary AST snapshot whose 5th byte (index 4, right after the `PMDA` magic) is the
 * format version. Stage 3 re-types the renderer but must keep consuming version-1
 * blobs; if the native parser bumps the format and the committed blobs are stale,
 * this fails loudly and points at the regen script.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MarkdownCorpusBlobVersionGuardTest {

    @Test
    fun everyCorpusBlobIsWireVersionOne() {
        val corpusDir = File("src/test/resources/markdown-corpus")
        val blobs = corpusDir.listFiles { f -> f.extension == "pmda" }.orEmpty()
        assertTrue("no .pmda blobs found in ${corpusDir.path}", blobs.isNotEmpty())

        blobs.sortedBy { it.name }.forEach { blob ->
            val bytes = blob.readBytes()
            assertTrue("${blob.name} too short to hold a version byte", bytes.size > 4)
            assertEquals(
                "golden blob stale — run native/markdown-parser/regen-corpus.sh",
                1,
                bytes[4].toInt(),
            )
        }
    }
}
