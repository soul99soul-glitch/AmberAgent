package app.amber.feature.ui.components.richtext.tree

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.amber.agent.ui.components.richtext.nativebridge.PackedAstReader
import app.amber.core.settings.Settings
import app.amber.feature.ui.components.richtext.MarkdownParseResult
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
     * Render the production renderer over the NATIVE (packed-AST) tree, built from a corpus golden
     * blob exactly as [MarkdownTreeParityTest] does its native side. Used to pin the class [F] / [G]
     * fixes, which only fire on the native shape (the JVM tree carries the literal LIST_NUMBER marker
     * and the relocated TaskListMarker, so it never reaches the native-only branches).
     */
    private fun renderNativeTreeDump(sampleBaseName: String): String {
        val dir = java.io.File("src/test/resources/markdown-corpus")
        val rawText = java.io.File(dir, "$sampleBaseName.md").readText()
        val blob = java.io.File(dir, "$sampleBaseName.pmda").readBytes()
        val reader = PackedAstReader(blob)
        val root = nativeMdTreeOrNull(reader, rawText)
            ?: error("native tree had no decodable root for $sampleBaseName")
        val result = MarkdownParseResult(
            preprocessed = rawText,
            tree = root,
            hasHtmlBlocks = reader.hasHtmlBlocks,
        )
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

    /**
     * Parity class [B] — CommonMark backslash-escape resolution in rendered Text leaves.
     *
     * The JetBrains (JVM) tree keeps a backslash escape (`\*` / `\!` / `\_`) LITERALLY in the Text
     * leaf slice; per CommonMark a `\` before an ASCII-punctuation char escapes it, so the rendered
     * text must show just the punctuation. `\*not italic\*` must render `*not italic*` (the
     * backslashes consumed, the asterisks NOT interpreted as emphasis — there is no Emphasis span).
     * The fix resolves escapes in the generic leaf arm of `appendMarkdownNodeContent`, which is the
     * SHARED arm both tree shapes route Text leaves through, so the native tree (which already slices
     * the escape away) is idempotent under the same rule.
     */
    @Test
    fun escapedPunctuationRendersLiterallyWithoutEmphasis() {
        val dump = renderJvmTreeDump("\\*not-emph\\*\n")
        assertTrue(
            "escaped asterisks must render as literal `*` with backslashes consumed; got dump:\n$dump",
            dump.contains("text=*not-emph*"),
        )
        assertFalse(
            "no backslash may survive before the escaped punctuation; got dump:\n$dump",
            dump.contains("\\*"),
        )
    }

    /**
     * Companion to the escape guard: a backslash before a NON-punctuation character is NOT an escape
     * (CommonMark only escapes the ASCII-punctuation set). `back\slash` must keep its literal
     * backslash — the rule must not strip backslashes indiscriminately.
     */
    @Test
    fun backslashBeforeNonPunctuationStaysLiteral() {
        val dump = renderJvmTreeDump("back\\slash\n")
        assertTrue(
            "backslash before a letter is not an escape and must survive; got dump:\n$dump",
            dump.contains("text=back\\slash"),
        )
    }

    /**
     * Scope guard: escapes inside an inline-code span must stay RAW. Code spans render via the
     * dedicated `InlineCode` arm (which slices the raw source and only strips backticks), hoisted
     * ABOVE the generic leaf arm, so the escape-resolution rule can never touch them. `` `\!` `` must
     * render the literal `\!` inside the code span.
     */
    @Test
    fun escapeInsideInlineCodeStaysRaw() {
        val dump = renderJvmTreeDump("`\\!`\n")
        assertTrue(
            "escape inside inline code must stay raw (`\\!`); got dump:\n$dump",
            dump.contains("\\!"),
        )
    }

    /**
     * Parity class [F] — native ordered-list start number. The native (pulldown) tree carries NO
     * literal LIST_NUMBER marker child on its list items, so the renderer must derive the displayed
     * number from the list's `listStart` accessor: `(listStart ?: 1) + itemIndex`. Sample 14 authors a
     * list starting at 7 (`listStart == 7L`), so the native render must show `7.` `8.` `9.` … — NOT
     * the old `1.`-renumbered fallback. Pins the [F] fix on the native shape (the JVM tree slices the
     * literal marker and is exercised by the corpus snapshot suite).
     */
    @Test
    fun nativeOrderedListStartRendersSourceNumbers() {
        val dump = renderNativeTreeDump("14-ordered-list-start")
        // The first ordered list (start = 7) must render its items beginning at 7, not 1.
        assertTrue(
            "native ordered list must render the source start number `7.`; got dump:\n$dump",
            dump.contains("text=7."),
        )
        assertTrue(
            "native ordered list must continue `8.`; got dump:\n$dump",
            dump.contains("text=8."),
        )
        assertFalse(
            "native ordered list authored at 7 must NOT renumber from 1; got dump:\n$dump",
            dump.contains("text=1."),
        )
    }

    /**
     * Parity class [G] — native task-list `[x]` marker text. On the native tree a task item's
     * checkbox is a CHILDLESS TaskListMarker leaf flat among the item's inline children; the renderer
     * must render it as a checkbox composable (no contentDescription → no dump line) and EXCLUDE its
     * `[x]` text from the grouped inline run. Sample 15's first item is `- [x] Increment …`: the
     * rendered text must be `Increment …` with NO literal `[x]` / `[ ]` leaking in.
     */
    @Test
    fun nativeTaskItemRendersCheckboxAndExcludesMarkerText() {
        val dump = renderNativeTreeDump("15-task-lists")
        // The item text must survive, with NO marker prefix — the bug rendered `[x]Increment …` as a
        // single Text run (the childless TaskListMarker leaf appended its raw `[x]` ahead of the
        // item's text). The fix renders the text run as `Increment …` (marker stripped, checkbox
        // rendered separately with no contentDescription).
        assertTrue(
            "native task item text must render with no marker prefix; got dump:\n$dump",
            dump.contains("text=Increment"),
        )
        // The leaked-marker shapes the bug produced — a task marker glued to the start of the item's
        // text — must NOT appear. (A bare `[x]` also occurs in this sample's intro PROSE, so we assert
        // the specific marker+text concatenation rather than a bare `[x]`/`[ ]`.)
        assertFalse(
            "no `[x]` task marker may be glued to the item text; got dump:\n$dump",
            dump.contains("text=[x]"),
        )
        assertFalse(
            "no `[ ]` task marker may be glued to the item text; got dump:\n$dump",
            dump.contains("text=[ ]"),
        )
    }

    companion object {
        private const val TAG = "edge-shape-jvm-tree"
    }
}
