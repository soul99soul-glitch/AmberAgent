package app.amber.feature.ui.components.richtext.tree

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stage-3 (TD.Rust.1a Task 8) contract pin for [JvmMdNode] — the JetBrains-ASTNode-backed
 * implementation of [MdNode].
 *
 * These tests parse real corpus samples with the SAME parser the production renderer uses
 * (`parsePreprocessedMarkdownUncached` in Markdown.kt ~line 757-806:
 * `GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true)` + `MarkdownParser`),
 * wrap the root in [JvmMdNode], and assert the typed accessors / traversal contract against
 * the mapping doc (docs/td-rust-1a-nodetype-mapping.md §2-§5).
 *
 * Pure JVM — the JetBrains parser is plain Kotlin, no Android/Robolectric needed.
 */
class JvmMdTreeTest {

    private val corpusDir = File("src/test/resources/markdown-corpus")

    // Same parser construction as Markdown.kt:134-142 (flavour + parser by lazy).
    private val flavour = GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true)
    private val parser = MarkdownParser(flavour)

    /** Parse a corpus `.md` and wrap its JetBrains root as a [JvmMdNode]. */
    private fun parseCorpus(name: String): Pair<MdNode, String> {
        require(corpusDir.exists()) {
            "corpus not found — run via ./gradlew :app:testDebugUnitTest from repo root"
        }
        val source = File(corpusDir, name).readText()
        val ast = parser.buildMarkdownTreeFromString(source)
        return JvmMdNode(ast, source, parent = null) to source
    }

    /**
     * Pre-order DFS over the tree (mirrors JetBrains child order).
     * Does NOT include the receiver, unlike findChildOfTypeRecursive which is self-first;
     * all call sites start from root.
     */
    private fun MdNode.descendants(): Sequence<MdNode> = sequence {
        for (child in children) {
            yield(child)
            yieldAll(child.descendants())
        }
    }

    private fun MdNode.firstOfType(type: MdNodeType): MdNode? =
        descendants().firstOrNull { it.type == type }

    private fun MdNode.allOfType(type: MdNodeType): List<MdNode> =
        descendants().filter { it.type == type }.toList()

    // ── Root / structural type mapping ────────────────────────────────

    @Test
    fun rootMapsToRootType() {
        val (root, _) = parseCorpus("01-plain-paragraphs.md")
        assertEquals(MdNodeType.Root, root.type)
        assertNull("root has no parent", root.parent)
    }

    @Test
    fun paragraphTypeSpotCheck() {
        val (root, _) = parseCorpus("01-plain-paragraphs.md")
        assertNotNull("plain doc must contain a Paragraph", root.firstOfType(MdNodeType.Paragraph))
    }

    // ── E1: heading levels (02-headings-all-levels.md) ────────────────

    @Test
    fun headingLevelsH1ToH6() {
        val (root, _) = parseCorpus("02-headings-all-levels.md")
        val headings = root.allOfType(MdNodeType.Heading)
        val levels = headings.mapNotNull { it.headingLevel }.toSet()
        // 02 has at least one of each ATX level 1..6.
        assertEquals(setOf(1, 2, 3, 4, 5, 6), levels)
        // Every Heading carries a non-null level in 1..6.
        headings.forEach { h ->
            val level = h.headingLevel
            assertNotNull("Heading must expose a level", level)
            assertTrue("level in 1..6 but was $level", level!! in 1..6)
        }
        // First ATX heading in the file is "# Heading Level 1" → level 1.
        assertEquals(1, headings.first().headingLevel)
    }

    @Test
    fun headingLevelNullForNonHeading() {
        val (root, _) = parseCorpus("01-plain-paragraphs.md")
        assertNull(root.firstOfType(MdNodeType.Paragraph)!!.headingLevel)
    }

    // ── E5 / H6: inline link href + children shape (04-links-inline.md) ─

    @Test
    fun inlineLinkHrefExtraction() {
        val (root, _) = parseCorpus("04-links-inline.md")
        val link = root.allOfType(MdNodeType.Link)
            .first { it.linkHref == "https://example.com" }
        assertEquals("https://example.com", link.linkHref)
    }

    @Test
    fun inlineLinkChildrenPreserveLinkTextAndDestination() {
        // KU-1 / H6: the riskiest mapping. Pin that an INLINE_LINK keeps its JetBrains
        // children un-prefiltered (LINK_TEXT + LINK_DESTINATION present) so the next task
        // can reconstruct label + destination exactly.
        val (root, source) = parseCorpus("04-links-inline.md")
        // "[link text](https://example.com)" — first inline link in the doc.
        val link = root.allOfType(MdNodeType.Link)
            .first { it.linkHref == "https://example.com" && it.textIn(source).startsWith("[link text]") }
        assertEquals("https://example.com", link.linkHref)
        // The full link node text round-trips to the source `[label](url)` form.
        assertEquals("[link text](https://example.com)", link.textIn(source))
        // Children are NOT pre-filtered: the recursive dig for LINK_DESTINATION (the JVM
        // accessor's own mechanism) still finds the destination token under this node.
        assertTrue(
            "link must retain children for label reconstruction",
            link.children.isNotEmpty(),
        )
    }

    // ── 05-links-with-titles.md: title contract (renderer never reads it) ─

    @Test
    fun linkTitleAlwaysNull() {
        // Mapping doc §6 linkTitle note: today's renderer does NOT read link titles;
        // both trees may return null. Pin the JVM contract = null.
        val (root, _) = parseCorpus("05-links-with-titles.md")
        val links = root.allOfType(MdNodeType.Link)
        assertTrue("05 must contain links", links.isNotEmpty())
        links.forEach { assertNull("linkTitle is null on JVM path", it.linkTitle) }
    }

    // ── E3 / H4: fenced code lang (07-fenced-code-kotlin.md) ──────────

    @Test
    fun fencedCodeLangKotlin() {
        val (root, _) = parseCorpus("07-fenced-code-kotlin.md")
        val codeBlocks = root.allOfType(MdNodeType.CodeBlock)
        assertTrue("07 must contain fenced code", codeBlocks.isNotEmpty())
        // Every fence in 07 is tagged `kotlin`.
        codeBlocks.forEach { assertEquals("kotlin", it.codeLang) }
    }

    @Test
    fun indentedCodeLangNull() {
        // 09-indented-code.md is indented (CODE_BLOCK) → no FENCE_LANG → codeLang null.
        val (root, _) = parseCorpus("09-indented-code.md")
        val codeBlocks = root.allOfType(MdNodeType.CodeBlock)
        assertTrue("09 must contain a code block", codeBlocks.isNotEmpty())
        assertTrue(
            "indented code has null codeLang",
            codeBlocks.any { it.codeLang == null },
        )
    }

    @Test
    fun fencedCodeNoLangIsNull() {
        // 08-fenced-code-no-lang.md contains fences with no info-string (` ``` ` alone).
        // Per the KDoc: empty fence info → JetBrains emits no FENCE_LANG token → codeLang null.
        val (root, _) = parseCorpus("08-fenced-code-no-lang.md")
        val codeBlocks = root.allOfType(MdNodeType.CodeBlock)
        assertTrue("08 must contain fenced code blocks", codeBlocks.isNotEmpty())
        codeBlocks.forEach { block ->
            assertNull("fenced code with no info-string must have null codeLang", block.codeLang)
        }
    }

    // ── E2 / R-E2: ordered-list start (14-ordered-list-start.md) ──────

    @Test
    fun orderedListStartIsSeven() {
        val (root, _) = parseCorpus("14-ordered-list-start.md")
        val firstOrdered = root.firstOfType(MdNodeType.ListOrdered)
        assertNotNull("14 must contain an ordered list", firstOrdered)
        assertEquals(7L, firstOrdered!!.listStart)
    }

    @Test
    fun unorderedListStartNull() {
        val (root, _) = parseCorpus("13-nested-lists.md")
        val unordered = root.firstOfType(MdNodeType.ListUnordered)
        assertNotNull("13 must contain an unordered list", unordered)
        assertNull(unordered!!.listStart)
    }

    // ── E6 / H2: task checkbox states (15-task-lists.md) ──────────────

    @Test
    fun taskCheckedStates() {
        val (root, _) = parseCorpus("15-task-lists.md")
        val markers = root.allOfType(MdNodeType.TaskListMarker)
        assertTrue("15 must contain task markers", markers.isNotEmpty())
        // Both checked and unchecked appear in the corpus.
        assertTrue("at least one checked", markers.any { it.taskChecked == true })
        assertTrue("at least one unchecked", markers.any { it.taskChecked == false })
        // Every marker carries a non-null boolean.
        markers.forEach { assertNotNull(it.taskChecked) }
    }

    @Test
    fun taskCheckedCaseSensitivityUppercaseX() {
        // 15 "Case Sensitivity" section: `[X]` (uppercase) — replicate VERBATIM the JVM
        // logic at Markdown.kt:1534 `getTextInNode(content).trim() == "[x]"` which is
        // CASE-SENSITIVE: `[X]` does NOT equal `[x]`, so taskChecked is false.
        // This pins TODAY'S behavior (the sample comment claims it "should" be checked,
        // but the renderer's exact string compare says otherwise — we preserve the
        // renderer, not the sample's aspiration).
        val (root, source) = parseCorpus("15-task-lists.md")
        val uppercaseMarker = root.allOfType(MdNodeType.TaskListMarker)
            .firstOrNull { it.textIn(source).trim() == "[X]" }
        assertNotNull("15 contains a `[X]` uppercase marker", uppercaseMarker)
        assertEquals(
            "JVM verbatim compare is case-sensitive: [X] != [x]",
            false,
            uppercaseMarker!!.taskChecked,
        )
    }

    // ── §5: table alignment unused (12-gfm-table-aligned.md) ──────────

    @Test
    fun tableAlignmentsAlwaysNull() {
        val (root, _) = parseCorpus("12-gfm-table-aligned.md")
        val tables = root.allOfType(MdNodeType.Table)
        assertTrue("12 must contain tables", tables.isNotEmpty())
        tables.forEach { assertNull("tableAlignments unused → null", it.tableAlignments) }
    }

    // ── Traversal: children / nextSibling pre-order order ─────────────

    @Test
    fun childrenAndNextSiblingMatchJetBrainsOrder() {
        val (root, _) = parseCorpus("02-headings-all-levels.md")
        // Walk top-level children via nextSibling() and confirm it matches `children` order.
        val viaChildren = root.children
        if (viaChildren.isEmpty()) return
        val viaSibling = generateSequence(viaChildren.first()) { it.nextSibling() }.toList()
        assertEquals(viaChildren.size, viaSibling.size)
        viaChildren.forEachIndexed { i, c ->
            assertEquals("startOffset order mismatch at $i", c.startOffset, viaSibling[i].startOffset)
            assertEquals("type order mismatch at $i", c.type, viaSibling[i].type)
        }
        // Pre-order: each child's startOffset is non-decreasing.
        viaChildren.zipWithNext().forEach { (a, b) ->
            assertTrue("children not in source order", a.startOffset <= b.startOffset)
        }
    }

    @Test
    fun lastChildNextSiblingIsNull() {
        val (root, _) = parseCorpus("01-plain-paragraphs.md")
        val last = root.children.lastOrNull() ?: return
        assertNull("last child has no next sibling", last.nextSibling())
    }

    @Test
    fun findChildOfTypeRecursiveReturnsSelfWhenMatching() {
        // Replicates Markdown.kt:2882 — `if (this.type in types) return this`.
        val (root, _) = parseCorpus("02-headings-all-levels.md")
        val heading = root.firstOfType(MdNodeType.Heading)!!
        assertEquals(heading, heading.findChildOfTypeRecursive(MdNodeType.Heading))
    }

    @Test
    fun findChildOfTypeRecursiveIsPreOrderDfs() {
        // Root.findChildOfTypeRecursive(Heading) returns the FIRST heading in pre-order,
        // which is the same as descendants().first { Heading } (our reference DFS).
        val (root, _) = parseCorpus("02-headings-all-levels.md")
        val found = root.findChildOfTypeRecursive(MdNodeType.Heading)
        val expected = root.firstOfType(MdNodeType.Heading)
        assertNotNull(found)
        assertEquals(expected!!.startOffset, found!!.startOffset)
    }

    @Test
    fun findChildOfTypeRecursiveNullWhenAbsent() {
        val (root, _) = parseCorpus("01-plain-paragraphs.md")
        assertNull(root.findChildOfTypeRecursive(MdNodeType.MathBlock))
    }

    // ── Type-mapping spot checks across kinds ─────────────────────────

    @Test
    fun blockquoteTypeMapping() {
        val (root, _) = parseCorpus("16-blockquotes-nested.md")
        assertNotNull(root.firstOfType(MdNodeType.Blockquote))
    }

    @Test
    fun tableTypeMapping() {
        val (root, _) = parseCorpus("11-gfm-table-simple.md")
        assertNotNull(root.firstOfType(MdNodeType.Table))
    }

    @Test
    fun horizontalRuleTypeMapping() {
        val (root, _) = parseCorpus("17-thematic-breaks.md")
        assertNotNull(root.firstOfType(MdNodeType.HorizontalRule))
    }

    @Test
    fun htmlBlockTypeMapping() {
        val (root, _) = parseCorpus("20-html-block.md")
        assertNotNull(root.firstOfType(MdNodeType.HtmlBlock))
    }

    @Test
    fun strikethroughTypeMapping() {
        val (root, _) = parseCorpus("23-strikethrough.md")
        assertNotNull(root.firstOfType(MdNodeType.Strikethrough))
    }

    @Test
    fun inlineCodeTypeMapping() {
        val (root, _) = parseCorpus("10-inline-code.md")
        assertNotNull(root.firstOfType(MdNodeType.InlineCode))
    }

    @Test
    fun imageTypeMappingAndSrc() {
        val (root, _) = parseCorpus("06-images.md")
        val image = root.firstOfType(MdNodeType.Image)
        assertNotNull("06 must contain an image", image)
        // Pin the exact URL of the first image in corpus 06-images.md (line 5).
        assertEquals(
            "https://developer.android.com/images/brand/Android_Robot.png",
            image!!.imageSrc,
        )
    }

    @Test
    fun emphasisAndStrongTypeMapping() {
        val (root, _) = parseCorpus("03-emphasis-nesting.md")
        assertNotNull(root.firstOfType(MdNodeType.Emphasis))
        assertNotNull(root.firstOfType(MdNodeType.Strong))
    }

    @Test
    fun mathBlockAndInlineTypeMapping() {
        val (blockRoot, _) = parseCorpus("19-katex-block.md")
        assertNotNull(blockRoot.firstOfType(MdNodeType.MathBlock))
        val (inlineRoot, _) = parseCorpus("18-katex-inline.md")
        assertNotNull(inlineRoot.firstOfType(MdNodeType.MathInline))
    }

    // ── textIn round-trip ─────────────────────────────────────────────

    @Test
    fun textInRoundTripMatchesSourceSubstring() {
        val (root, source) = parseCorpus("07-fenced-code-kotlin.md")
        // A heading node's textIn(source) equals the exact source substring.
        val heading = root.firstOfType(MdNodeType.Heading)!!
        val expected = source.substring(heading.startOffset, heading.endOffset)
        assertEquals(expected, heading.textIn(source))
        // And it begins with the ATX marker for an H2 "## Kotlin Code Examples".
        assertTrue(heading.textIn(source).startsWith("## Kotlin"))
    }

    @Test
    fun inlineCodeTextInRoundTrip() {
        val (root, source) = parseCorpus("10-inline-code.md")
        val code = root.firstOfType(MdNodeType.InlineCode)!!
        // textIn over a CODE_SPAN includes the backticks (the renderer trims them later).
        val slice = code.textIn(source)
        assertEquals(source.substring(code.startOffset, code.endOffset), slice)
        assertTrue("inline code slice retains a backtick", slice.contains("`"))
    }
}
