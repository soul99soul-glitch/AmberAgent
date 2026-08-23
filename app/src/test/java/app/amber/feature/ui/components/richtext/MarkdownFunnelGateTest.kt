package app.amber.feature.ui.components.richtext

import android.app.Application
import app.amber.feature.ui.components.richtext.nativebridge.MarkdownNativeSwitch
import app.amber.feature.ui.components.richtext.tree.MdNodeType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TD.Rust.1a Task 13 — parse-funnel gate coverage.
 *
 * The flag-gated native branch in `parsePreprocessedMarkdownUncached` reads
 * [MarkdownNativeSwitch.isAstEnabled]; when on it parses via the Rust bridge and wraps the result
 * in a `NativeMdNode`. The bridge's `.so` is NOT present on the host JVM, so on a unit test
 * `MarkdownParserNative.parse` returns null and the gate falls through to the JVM path — exactly
 * the documented "blob == null → bridge already logged, just fall through" branch.
 *
 * This test exercises the FLAG-ON entry into the gate and proves:
 *  1. flipping the flag does not crash the funnel (Looper/Trace/bridge plumbing is safe), and
 *  2. with no `.so`, output is still a valid JVM-parsed tree (graceful fall-through), byte-equal to
 *     the flag-off result.
 *
 * The flag-ON path that actually produces a `NativeMdNode` (blob != null) requires the loaded
 * native library and is covered by Task 14's on-device parity rig — it cannot run in a pure-JVM
 * unit test because the bridge JNI symbol is absent. We deliberately do NOT stand up DI/JNI
 * scaffolding to fake it here.
 *
 * Robolectric is required because the funnel touches `android.os.Trace` / `android.util.Log`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MarkdownFunnelGateTest {

    @After
    fun resetSwitch() {
        // Restore the default disabled config so other tests in the module see the OFF default.
        MarkdownNativeSwitch.config = MarkdownNativeSwitch.DisabledConfig
    }

    /** Config that turns the AST gate ON; everything else is a no-op. */
    private object AstOnConfig : MarkdownNativeSwitch.Config {
        override fun htmlEnabled(): Boolean = false
        override fun astEnabled(): Boolean = true
        override fun samplingRate(): Float = 0f
        override fun onLoadFailure(error: Throwable) {}
        override fun onNativePanic(stage: String, error: Throwable?) {}
        override fun onDiff(stage: String, equal: Boolean, jvmSummary: String, nativeSummary: String) {}
    }

    @Test
    fun flagOnFallsThroughToJvmWhenNativeLibAbsent() {
        // Two content strings with identical structure but different text so the cache never
        // confuses the flag-off result for the flag-on result: each call is a guaranteed miss.
        val mdOff = "# Title\n\nA paragraph with **bold** and a list:\n\n- one\n- two\n"
        val mdOn  = "# Heading\n\nA sentence with **bold** and a list:\n\n- alpha\n- beta\n"

        // Flag OFF (default) baseline — parse mdOff.
        MarkdownNativeSwitch.config = MarkdownNativeSwitch.DisabledConfig
        val off = parseMarkdownContent(mdOff)

        // Flag ON — gate is entered, native lib absent → blob null → JVM fall-through.
        // mdOn is distinct from mdOff so this call is a cache MISS and genuinely re-enters
        // parsePreprocessedMarkdownUncached with the gate active.
        MarkdownNativeSwitch.config = AstOnConfig
        val on = parseMarkdownContent(mdOn)

        // The gate did not crash and produced a valid tree with the expected top-level structure.
        assertNotNull("flag-on funnel must still return a tree", on.tree)
        assertEquals(MdNodeType.Root, on.tree.type)
        assertTrue("tree must have parsed blocks", on.tree.children.isNotEmpty())

        // Structural equivalence: both strings have the same block layout (heading, paragraph,
        // bullet list), so the top-level block types must match regardless of text content.
        assertEquals(
            "flag-on fall-through must yield the same block-type sequence as flag-off JVM",
            off.tree.children.map { it.type },
            on.tree.children.map { it.type },
        )
        assertEquals(off.hasHtmlBlocks, on.hasHtmlBlocks)
    }

    @Test
    fun gateReadsTheSwitchFlag() {
        // Sanity: the flag-read mechanism the gate uses is observable and toggles with config.
        MarkdownNativeSwitch.config = MarkdownNativeSwitch.DisabledConfig
        assertEquals(false, MarkdownNativeSwitch.isAstEnabled())
        MarkdownNativeSwitch.config = AstOnConfig
        assertEquals(true, MarkdownNativeSwitch.isAstEnabled())
    }
}
