package app.amber.feature.ui.components.richtext.tree

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.amber.core.settings.Settings
import app.amber.feature.ui.components.richtext.MarkdownTreeForParityTest
import app.amber.feature.ui.components.richtext.parseRawMarkdownForParityTest
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.Navigator
import app.amber.highlight.Highlighter
import app.amber.highlight.LocalHighlighter
import kotlinx.coroutines.CoroutineExceptionHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TD.Rust.1a Phase 3-B — focused guards for the two flag-OFF render regressions that the
 * shape-agnostic dispatch (commits 0af494d5 heading + 82280b1f list-item) introduced on
 * marker-only JVM edge shapes. Both shapes route into the no-Paragraph-wrapper branch that was
 * added for the NATIVE tree, but the JVM tree can also reach it with ONLY marker/whitespace
 * [MdNodeType.Unknown] children — and the new fallback would then emit those raw markers (`##` /
 * `-`) as literal text. The two `MdNodeType.Unknown` guards in Markdown.kt suppress that; these
 * tests pin them.
 *
 * These render the production renderer over the JVM (JetBrains) tree via the same seam the parity
 * rig uses ([MarkdownTreeForParityTest]); the Robolectric scaffolding mirrors
 * [MarkdownTreeParityTest] / [MarkdownRendererSnapshotTest] verbatim. A pure-JVM tree-shape
 * assertion documents the exact edge shape each guard targets (so a future parser change that alters
 * the shape is caught here, not silently).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class MarkdownEdgeShapeRenderTest {

    private val swallowAsyncLoadFailures = CoroutineExceptionHandler { _, t ->
        if (generateSequence<Throwable>(t) { it.cause }.none { it is UnsatisfiedLinkError }) {
            throw t
        }
    }

    @get:Rule
    val compose = createComposeRule(swallowAsyncLoadFailures)

    private fun renderJvmTreeDump(rawText: String): String {
        val result = parseRawMarkdownForParityTest(rawText)
        val highlighter = Highlighter(RuntimeEnvironment.getApplication())
        compose.setContent {
            CompositionLocalProvider(
                LocalSettings provides Settings(),
                LocalNavController provides Navigator(mutableListOf()),
                LocalHighlighter provides highlighter,
            ) {
                MaterialTheme {
                    Box(Modifier.testTag(TAG)) {
                        MarkdownTreeForParityTest(content = rawText, result = result)
                    }
                }
            }
        }
        compose.waitForIdle()
        // dumpNormalized ignores TestTag, so the tagged Box adds no line — only its rendered subtree.
        return compose.onNodeWithTag(TAG).fetchSemanticsNode().dumpNormalized()
    }

    /**
     * Item 1 — a marker-only ATX heading (`##` with no trailing inline text). JVM shape: a Heading
     * with a single ATX_HEADER `#`-marker child mapped to [MdNodeType.Unknown], NO Paragraph wrapper.
     * Before the guard, the `?: node` fallback routed the Heading through Paragraph and its leaf arm
     * appended the literal `##`. After the guard it renders nothing.
     */
    @Test
    fun markerOnlyHeadingRendersEmpty() {
        // Document the exact JVM edge shape this guard targets.
        val root = parseRawMarkdownForParityTest("##\n").tree
        val heading = root.children.first { it.type == MdNodeType.Heading }
        assertEquals("marker-only heading has exactly one child", 1, heading.children.size)
        assertEquals(
            "the heading's sole child is an Unknown marker token (no Paragraph wrapper, no inline content)",
            MdNodeType.Unknown,
            heading.children.first().type,
        )

        val dump = renderJvmTreeDump("##\n")
        // The whole dump must carry no rendered text at all — and certainly not the literal markers.
        assertFalse(
            "marker-only heading must render no text; got dump:\n$dump",
            dump.contains("text="),
        )
        assertFalse("no literal `#` markers may leak into the render; got:\n$dump", dump.contains("#"))
    }

    /**
     * Item 2 — a nested-FIRST list item (`- \n  - nested`). JVM shape: the outer LIST_ITEM's direct
     * content (nested list removed) is ONLY marker/whitespace [MdNodeType.Unknown] tokens, NO
     * Paragraph wrapper. Before the guard, the no-wrapper branch grouped those raw tokens through
     * InlineRunMdNode → Paragraph and emitted the literal `-`. After the guard the empty inline run
     * renders nothing; the nested `nested` item (which has a real Paragraph wrapper) still renders.
     */
    @Test
    fun nestedFirstListItemRendersWithoutStrayMarker() {
        val dump = renderJvmTreeDump("- \n  - nested\n")
        // The nested item's text must survive…
        assertEquals(
            "the nested list item should render its text; got dump:\n$dump",
            true,
            dump.contains("text=nested"),
        )
        // …with no stray bullet marker leaking from the empty outer item. The renderer's own bullet
        // glyphs are `•`/`◦`/`▪` (UnorderedListNode), never a raw `-`, so any `-` in the dump is the
        // regression. Guard against the literal hyphen specifically.
        assertFalse(
            "no literal `-` list marker may leak into the render; got:\n$dump",
            dump.contains("-"),
        )
    }

    /**
     * Parity class [A] regression guard — heading interior spaces around punctuation.
     *
     * The JetBrains tree splits a heading's inline text into MULTIPLE TEXT leaf tokens at
     * punctuation (em-dash / `(` / `"` / `:`). The heading render path appends each leaf through
     * [appendMarkdownNodeContent] with `trim = true`. When that trim was applied PER TOKEN, the
     * space BEFORE/AFTER the punctuation was eaten on each side, so `## GFM Tables — Simple`
     * rendered as `GFM Tables—Simple` (interior spaces lost). The fix trims the assembled heading
     * content ONCE at its outer boundaries instead of trimming each token, so interior token-boundary
     * spaces survive while leading/trailing whitespace of the whole heading is still removed.
     */
    @Test
    fun headingPreservesInteriorSpacesAroundPunctuation() {
        val dump = renderJvmTreeDump("## GFM Tables — Simple\n")
        assertTrue(
            "heading must preserve interior spaces around the em-dash; got dump:\n$dump",
            dump.contains("text=GFM Tables — Simple"),
        )
    }

    /**
     * Companion to the class-[A] guard above: leading/trailing whitespace of the WHOLE heading must
     * STILL be trimmed (the intentional part of `trim = true`). `## foo ` → `foo`, not `foo `.
     * `dumpNormalized` itself trims line ends, so to pin this at the renderer level we assert the
     * outer-boundary trim survives alongside an interior space being preserved.
     */
    @Test
    fun headingTrimsOuterWhitespaceButKeepsInterior() {
        val dump = renderJvmTreeDump("##   Alpha — Beta   \n")
        assertTrue(
            "heading must keep the interior space and trim outer whitespace; got dump:\n$dump",
            dump.contains("text=Alpha — Beta"),
        )
    }

    companion object {
        private const val TAG = "edge-shape-jvm-tree"
    }
}
