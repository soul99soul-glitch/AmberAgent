package app.amber.feature.ui.components.richtext.tree

import app.amber.agent.ui.components.richtext.nativebridge.PackedAstReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stage-4 (TD.Rust.1a Task 12) contract pin for [NativeMdNode] — the packed-AST-backed
 * implementation of [MdNode] and the parity twin of [JvmMdNode].
 *
 * These tests load the Rust-generated golden blobs (`<name>.pmda`) from the same corpus
 * [JvmMdTreeTest] parses, wrap the decoded root in [NativeMdNode], and assert the typed accessors /
 * traversal contract against the mapping doc (docs/td-rust-1a-nodetype-mapping.md §2-§5). Every
 * case mirrors a [JvmMdTreeTest] case; assertions match the JVM twin's where the contract says the
 * value must be equal, and DIVERGE (with a prominent comment) where the two parsers genuinely
 * disagree — currently only the case-insensitive `[X]` task marker (see [taskCheckedUppercaseX]).
 *
 * ### Source string for textIn (offset basis)
 * Native packed-AST offsets are UTF-8 BYTE offsets, but [NativeMdNode] now translates them to
 * UTF-16 CHAR offsets internally (NativeMdTree.kt KU-2), so the [MdNode] contract is uniform char
 * offsets. We therefore read each corpus `.md` file as a REAL UTF-8 string — no ISO-8859-1 trick —
 * and the offset assertions still pass BECAUSE of the byte→char translation built in
 * [nativeMdTreeOrNull]. Production passes the preprocessed string with the blob generated from the
 * same text; reading the raw file as UTF-8 reproduces that basis (the corpus blobs were generated
 * from the raw `.md` bytes with no preprocessing).
 *
 * Pure JVM — the [PackedAstReader] decoder is plain Kotlin, no Android/Robolectric needed.
 */
class NativeMdTreeTest {

    private val corpusDir = File("src/test/resources/markdown-corpus")

    /** Load a golden blob, decode its source as real UTF-8, wrap the root via the factory. */
    private fun parseCorpus(name: String): Pair<MdNode, String> {
        require(corpusDir.exists()) {
            "corpus not found — run via ./gradlew :app:testDebugUnitTest from repo root"
        }
        val baseName = name.removeSuffix(".md")
        val blob = File(corpusDir, "$baseName.pmda").readBytes()
        // Real UTF-8: byte→char translation inside NativeMdNode realigns offsets (see class KDoc).
        val source = File(corpusDir, "$baseName.md").readText(Charsets.UTF_8)
        val reader = PackedAstReader(blob)
        require(reader.isValid) { "invalid blob for $baseName" }
        val root = nativeMdTreeOrNull(reader, source) ?: error("null root for $baseName")
        return root to source
    }

    /** Pre-order DFS over the tree (excludes the receiver), mirroring [JvmMdTreeTest.descendants]. */
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
        val (root, _) = parseCorpus("01-plain-paragraphs")
        assertEquals(MdNodeType.Root, root.type)
        assertNull("root has no parent", root.parent)
    }

    @Test
    fun paragraphTypeSpotCheck() {
        val (root, _) = parseCorpus("01-plain-paragraphs")
        assertNotNull("plain doc must contain a Paragraph", root.firstOfType(MdNodeType.Paragraph))
    }

    // ── E1: heading levels (02-headings-all-levels) ───────────────────

    @Test
    fun headingLevelsH1ToH6() {
        val (root, _) = parseCorpus("02-headings-all-levels")
        val headings = root.allOfType(MdNodeType.Heading)
        val levels = headings.mapNotNull { it.headingLevel }.toSet()
        assertEquals(setOf(1, 2, 3, 4, 5, 6), levels)
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
        val (root, _) = parseCorpus("01-plain-paragraphs")
        assertNull(root.firstOfType(MdNodeType.Paragraph)!!.headingLevel)
    }

    // ── E5 / H6: inline link href + children shape (04-links-inline) ───

    @Test
    fun inlineLinkHrefExtraction() {
        val (root, _) = parseCorpus("04-links-inline")
        val link = root.allOfType(MdNodeType.Link)
            .first { it.linkHref == "https://example.com" }
        assertEquals("https://example.com", link.linkHref)
    }

    @Test
    fun inlineLinkChildrenAndLabelReconstruction() {
        // Native twin of JvmMdTreeTest.inlineLinkChildrenPreserveLinkTextAndDestination. The
        // native tree has NO LINK_TEXT/LINK_DESTINATION dig tokens — href is in extras and the
        // label is the link's inline children. Pin both: href via accessor, label via children.
        val (root, source) = parseCorpus("04-links-inline")
        val link = root.allOfType(MdNodeType.Link)
            .first { it.linkHref == "https://example.com" && it.textIn(source).startsWith("[link text]") }
        assertEquals("https://example.com", link.linkHref)
        // The full link node text round-trips to the source `[label](url)` form.
        assertEquals("[link text](https://example.com)", link.textIn(source))
        // Children carry the visible label (no LINK_TEXT wrapper, no brackets).
        assertTrue("link must retain children for label reconstruction", link.children.isNotEmpty())
        assertEquals("link text", link.linkLabel)
        assertEquals("link text", link.linkInnerText)
    }

    // ── 05-links-with-titles: title contract (renderer never reads it) ─

    @Test
    fun linkTitleNotReadByRenderer() {
        // The renderer never reads link titles (mapping doc §6); the native decoder CAN surface a
        // non-empty title from extras, but it has no render effect. Pin that the accessor is at
        // least defined and the parse succeeds (05 exercises titled links).
        val (root, _) = parseCorpus("05-links-with-titles")
        val links = root.allOfType(MdNodeType.Link)
        assertTrue("05 must contain links", links.isNotEmpty())
        // A titled link decodes its title; an untitled one is null. Either way no crash, and the
        // value is irrelevant to rendering. Just assert the accessor is callable on every link.
        links.forEach { it.linkTitle }
    }

    // ── E3 / H4: fenced code lang (07-fenced-code-kotlin) ─────────────

    @Test
    fun fencedCodeLangKotlin() {
        val (root, _) = parseCorpus("07-fenced-code-kotlin")
        val codeBlocks = root.allOfType(MdNodeType.CodeBlock)
        assertTrue("07 must contain fenced code", codeBlocks.isNotEmpty())
        codeBlocks.forEach { assertEquals("kotlin", it.codeLang) }
    }

    @Test
    fun indentedCodeLangNull() {
        val (root, _) = parseCorpus("09-indented-code")
        val codeBlocks = root.allOfType(MdNodeType.CodeBlock)
        assertTrue("09 must contain a code block", codeBlocks.isNotEmpty())
        assertTrue("indented code has null codeLang", codeBlocks.any { it.codeLang == null })
    }

    @Test
    fun fencedCodeNoLangIsNull() {
        // 08: fences with no info-string → Rust encodes empty lang → codeLang null (same as JVM).
        val (root, _) = parseCorpus("08-fenced-code-no-lang")
        val codeBlocks = root.allOfType(MdNodeType.CodeBlock)
        assertTrue("08 must contain fenced code blocks", codeBlocks.isNotEmpty())
        codeBlocks.forEach { block ->
            assertNull("fenced code with no info-string must have null codeLang", block.codeLang)
        }
    }

    // ── E2 / R-E2: ordered-list start (14-ordered-list-start) ─────────

    @Test
    fun orderedListStartIsSeven() {
        val (root, _) = parseCorpus("14-ordered-list-start")
        val firstOrdered = root.firstOfType(MdNodeType.ListOrdered)
        assertNotNull("14 must contain an ordered list", firstOrdered)
        assertEquals(7L, firstOrdered!!.listStart)
    }

    @Test
    fun unorderedListStartNull() {
        val (root, _) = parseCorpus("13-nested-lists")
        val unordered = root.firstOfType(MdNodeType.ListUnordered)
        assertNotNull("13 must contain an unordered list", unordered)
        assertNull(unordered!!.listStart)
    }

    // ── E6 / H2: task checkbox states (15-task-lists) ─────────────────

    @Test
    fun taskCheckedStates() {
        val (root, _) = parseCorpus("15-task-lists")
        val markers = root.allOfType(MdNodeType.TaskListMarker)
        assertTrue("15 must contain task markers", markers.isNotEmpty())
        assertTrue("at least one checked", markers.any { it.taskChecked == true })
        assertTrue("at least one unchecked", markers.any { it.taskChecked == false })
        markers.forEach { assertNotNull(it.taskChecked) }
    }

    @Test
    fun taskCheckedUppercaseX() {
        // ╔═══════════════════════════════════════════════════════════════════════════════════╗
        // ║ HEADLINE PARITY DIVERGENCE — `[X]` uppercase task marker.                          ║
        // ║ pulldown-cmark is CASE-INSENSITIVE and marks `[X]` as CHECKED (checked byte = 1).  ║
        // ║ The JVM path (`getTextInNode().trim() == "[x]"`) is case-SENSITIVE → `[X]` is      ║
        // ║ UNCHECKED there (see JvmMdTreeTest.taskCheckedCaseSensitivityUppercaseX, asserts    ║
        // ║ false). Same input, opposite rendered checkbox state. pulldown is GFM-correct       ║
        // ║ (the 15-task-lists.md "Case Sensitivity" section says `[X]` "should produce a       ║
        // ║ checked item"). Pinned here as TRUE — a real Stage-4 controller decision.           ║
        // ╚═══════════════════════════════════════════════════════════════════════════════════╝
        val (root, source) = parseCorpus("15-task-lists")
        val uppercaseMarker = root.allOfType(MdNodeType.TaskListMarker)
            .firstOrNull { it.textIn(source).trim() == "[X]" }
        assertNotNull("15 contains a `[X]` uppercase marker", uppercaseMarker)
        assertEquals(
            "pulldown-cmark is case-insensitive: [X] is checked (DIVERGES from JVM's false)",
            true,
            uppercaseMarker!!.taskChecked,
        )
    }

    // ── §5: table alignment unused (12-gfm-table-aligned) ─────────────

    @Test
    fun tableAlignmentsAlwaysNull() {
        val (root, _) = parseCorpus("12-gfm-table-aligned")
        val tables = root.allOfType(MdNodeType.Table)
        assertTrue("12 must contain tables", tables.isNotEmpty())
        tables.forEach { assertNull("tableAlignments unused → null", it.tableAlignments) }
    }

    // ── Traversal: children / nextSibling pre-order order ─────────────

    @Test
    fun childrenAndNextSiblingMatchSourceOrder() {
        val (root, _) = parseCorpus("02-headings-all-levels")
        val viaChildren = root.children
        if (viaChildren.isEmpty()) return
        val viaSibling = generateSequence(viaChildren.first()) { it.nextSibling() }.toList()
        assertEquals(viaChildren.size, viaSibling.size)
        viaChildren.forEachIndexed { i, c ->
            assertEquals("startOffset order mismatch at $i", c.startOffset, viaSibling[i].startOffset)
            assertEquals("type order mismatch at $i", c.type, viaSibling[i].type)
        }
        viaChildren.zipWithNext().forEach { (a, b) ->
            assertTrue("children not in source order", a.startOffset <= b.startOffset)
        }
    }

    @Test
    fun lastChildNextSiblingIsNull() {
        val (root, _) = parseCorpus("01-plain-paragraphs")
        val last = root.children.lastOrNull() ?: return
        assertNull("last child has no next sibling", last.nextSibling())
    }

    @Test
    fun findChildOfTypeRecursiveReturnsSelfWhenMatching() {
        val (root, _) = parseCorpus("02-headings-all-levels")
        val heading = root.firstOfType(MdNodeType.Heading)!!
        assertEquals(heading, heading.findChildOfTypeRecursive(MdNodeType.Heading))
    }

    @Test
    fun findChildOfTypeRecursiveIsPreOrderDfs() {
        val (root, _) = parseCorpus("02-headings-all-levels")
        val found = root.findChildOfTypeRecursive(MdNodeType.Heading)
        val expected = root.firstOfType(MdNodeType.Heading)
        assertNotNull(found)
        assertEquals(expected!!.startOffset, found!!.startOffset)
    }

    @Test
    fun findChildOfTypeRecursiveNullWhenAbsent() {
        val (root, _) = parseCorpus("01-plain-paragraphs")
        assertNull(root.findChildOfTypeRecursive(MdNodeType.MathBlock))
    }

    // ── Type-mapping spot checks across kinds ─────────────────────────

    @Test
    fun blockquoteTypeMapping() {
        val (root, _) = parseCorpus("16-blockquotes-nested")
        assertNotNull(root.firstOfType(MdNodeType.Blockquote))
    }

    @Test
    fun tableTypeMapping() {
        val (root, _) = parseCorpus("11-gfm-table-simple")
        assertNotNull(root.firstOfType(MdNodeType.Table))
    }

    @Test
    fun horizontalRuleTypeMapping() {
        val (root, _) = parseCorpus("17-thematic-breaks")
        assertNotNull(root.firstOfType(MdNodeType.HorizontalRule))
    }

    @Test
    fun htmlBlockTypeMapping() {
        val (root, _) = parseCorpus("20-html-block")
        assertNotNull(root.firstOfType(MdNodeType.HtmlBlock))
    }

    @Test
    fun strikethroughTypeMapping() {
        val (root, _) = parseCorpus("23-strikethrough")
        assertNotNull(root.firstOfType(MdNodeType.Strikethrough))
    }

    @Test
    fun inlineCodeTypeMapping() {
        val (root, _) = parseCorpus("10-inline-code")
        assertNotNull(root.firstOfType(MdNodeType.InlineCode))
    }

    @Test
    fun imageTypeMappingAndSrc() {
        val (root, _) = parseCorpus("06-images")
        val image = root.firstOfType(MdNodeType.Image)
        assertNotNull("06 must contain an image", image)
        assertEquals(
            "https://developer.android.com/images/brand/Android_Robot.png",
            image!!.imageSrc,
        )
    }

    @Test
    fun emphasisAndStrongTypeMapping() {
        val (root, _) = parseCorpus("03-emphasis-nesting")
        assertNotNull(root.firstOfType(MdNodeType.Emphasis))
        assertNotNull(root.firstOfType(MdNodeType.Strong))
    }

    @Test
    fun mathBlockAndInlineTypeMapping() {
        val (blockRoot, _) = parseCorpus("19-katex-block")
        assertNotNull(blockRoot.firstOfType(MdNodeType.MathBlock))
        val (inlineRoot, _) = parseCorpus("18-katex-inline")
        assertNotNull(inlineRoot.firstOfType(MdNodeType.MathInline))
    }

    // ── textIn round-trip ─────────────────────────────────────────────

    @Test
    fun textInRoundTripMatchesSourceSubstring() {
        val (root, source) = parseCorpus("07-fenced-code-kotlin")
        val heading = root.firstOfType(MdNodeType.Heading)!!
        val expected = source.substring(heading.startOffset, heading.endOffset)
        assertEquals(expected, heading.textIn(source))
        assertTrue(heading.textIn(source).startsWith("## Kotlin"))
    }

    @Test
    fun inlineCodeTextInRoundTrip() {
        val (root, source) = parseCorpus("10-inline-code")
        val code = root.firstOfType(MdNodeType.InlineCode)!!
        val slice = code.textIn(source)
        assertEquals(source.substring(code.startOffset, code.endOffset), slice)
        assertTrue("inline code slice retains a backtick", slice.contains("`"))
    }

    // ── Native heading content reconstruction (H1: flat inline, no ATX_CONTENT wrapper) ─

    @Test
    fun headingContentChildrenAreFlatInline() {
        // pulldown headings have inline content directly as children (no ATX_CONTENT wrapper, no
        // `#` marker). The first H1's child Text reconstructs the ATX content WITHOUT the `#`.
        val (root, source) = parseCorpus("02-headings-all-levels")
        val h1 = root.allOfType(MdNodeType.Heading).first { it.headingLevel == 1 }
        val text = h1.children.joinToString("") { it.textIn(source) }
        assertEquals("Heading Level 1", text)
    }

    // ── Stage-3 accessor #1: isFencedCode (fenced vs indented) ────────

    @Test
    fun isFencedCodeTrueForFencedFalseForIndented() {
        val (fencedRoot, _) = parseCorpus("07-fenced-code-kotlin")
        fencedRoot.allOfType(MdNodeType.CodeBlock).forEach {
            assertTrue("fenced kotlin block is fenced", it.isFencedCode)
        }
        // 08: fenced with no info-string — still fenced (node text starts with ```).
        val (noLangRoot, _) = parseCorpus("08-fenced-code-no-lang")
        noLangRoot.allOfType(MdNodeType.CodeBlock).forEach {
            assertTrue("no-lang fence is still fenced", it.isFencedCode)
        }
        // 09: indented — NOT fenced.
        val (indentedRoot, _) = parseCorpus("09-indented-code")
        indentedRoot.allOfType(MdNodeType.CodeBlock).forEach {
            assertFalse("indented block is not fenced", it.isFencedCode)
        }
    }

    @Test
    fun isFencedCodeFalseForNonCodeBlock() {
        val (root, _) = parseCorpus("01-plain-paragraphs")
        assertFalse(root.firstOfType(MdNodeType.Paragraph)!!.isFencedCode)
    }

    // ── Stage-3 accessor #2: codeFenceContentRange (body slice) ───────

    @Test
    fun codeFenceContentRangeSlicesBodyWithoutFences() {
        val (root, source) = parseCorpus("07-fenced-code-kotlin")
        val firstFence = root.allOfType(MdNodeType.CodeBlock).first()
        val range = firstFence.codeFenceContentRange
        assertNotNull("fenced block has a content range", range)
        // The renderer slices content.substring(range.first, range.last + 1).
        val body = source.substring(range!!.first, range.last + 1)
        // Body is the code WITHOUT the opening/closing fence lines or info string.
        assertFalse("body must not contain the opening fence", body.contains("```kotlin"))
        assertFalse("body must not end with a closing fence", body.trimEnd().endsWith("```"))
        assertTrue("body starts with the first code line", body.startsWith("class UserRepository("))
        // After trimIndent the body is non-empty actual code.
        assertTrue(body.trimIndent().startsWith("class UserRepository("))
    }

    @Test
    fun codeFenceContentRangeNullForIndented() {
        val (root, _) = parseCorpus("09-indented-code")
        root.allOfType(MdNodeType.CodeBlock).forEach {
            assertNull("indented block is not a fence → null range", it.codeFenceContentRange)
        }
    }

    // ── Stage-3 accessor #3: codeFenceEndOffset (closed vs truncated) ─

    @Test
    fun codeFenceEndOffsetSetForClosedFence() {
        val (root, _) = parseCorpus("07-fenced-code-kotlin")
        val fence = root.allOfType(MdNodeType.CodeBlock).first()
        // A closed fence's end offset is the node's endOffset.
        assertEquals(fence.endOffset, fence.codeFenceEndOffset)
    }

    @Test
    fun codeFenceEndOffsetNullForTruncatedFence() {
        // 26: streaming-truncated — the fence is never closed (file ends mid-code).
        val (root, _) = parseCorpus("26-streaming-truncated")
        val fence = root.allOfType(MdNodeType.CodeBlock).firstOrNull()
        assertNotNull("26 must contain the unclosed fence", fence)
        assertTrue("the truncated fence is still classified fenced", fence!!.isFencedCode)
        assertNull("unclosed fence → null end offset (still streaming)", fence.codeFenceEndOffset)
    }

    @Test
    fun codeFenceEndOffsetNullForIndented() {
        val (root, _) = parseCorpus("09-indented-code")
        root.allOfType(MdNodeType.CodeBlock).forEach {
            assertNull("indented block has no fence end", it.codeFenceEndOffset)
        }
    }

    // ── Stage-3 accessor #4: contentChildren (== children on native) ──

    @Test
    fun contentChildrenEqualsChildren() {
        // Native tree carries no marker tokens → contentChildren is identity.
        val (root, _) = parseCorpus("03-emphasis-nesting")
        val emphasis = root.firstOfType(MdNodeType.Emphasis)!!
        assertEquals(emphasis.children, emphasis.contentChildren)
        val strong = root.firstOfType(MdNodeType.Strong)!!
        assertEquals(strong.children, strong.contentChildren)
        val (skRoot, _) = parseCorpus("23-strikethrough")
        val strike = skRoot.firstOfType(MdNodeType.Strikethrough)!!
        assertEquals(strike.children, strike.contentChildren)
    }

    @Test
    fun emphasisContentChildrenReconstructInnerText() {
        // First emphasis in 03 is "*italic*" → its content child Text is "italic" (no markers).
        val (root, source) = parseCorpus("03-emphasis-nesting")
        val emphasis = root.firstOfType(MdNodeType.Emphasis)!!
        val inner = emphasis.contentChildren.joinToString("") { it.textIn(source) }
        assertEquals("italic", inner)
    }

    // ── Stage-3 accessor #5: isAutolink (<url> form) ──────────────────

    @Test
    fun isAutolinkTrueForAngleBracketForm() {
        // 04 "Autolinks" section: <https://example.com> and <user@example.com>.
        val (root, source) = parseCorpus("04-links-inline")
        val autolink = root.allOfType(MdNodeType.Link)
            .firstOrNull { it.textIn(source) == "<https://example.com>" }
        assertNotNull("04 must contain an angle-bracket autolink", autolink)
        assertTrue(autolink!!.isAutolink)
    }

    @Test
    fun isAutolinkFalseForInlineLink() {
        val (root, source) = parseCorpus("04-links-inline")
        val inlineLink = root.allOfType(MdNodeType.Link)
            .first { it.textIn(source).startsWith("[link text]") }
        assertFalse("inline [text](url) is not an autolink", inlineLink.isAutolink)
    }

    @Test
    fun isAutolinkFalseForNonLink() {
        val (root, _) = parseCorpus("01-plain-paragraphs")
        assertFalse(root.firstOfType(MdNodeType.Paragraph)!!.isAutolink)
    }

    // ── Stage-3 accessor #6/#7: linkLabel / linkInnerText ─────────────

    @Test
    fun linkLabelAndInnerTextForImage() {
        // Image alt: native label is the concat of the image node's children (no brackets).
        val (root, _) = parseCorpus("06-images")
        val image = root.firstOfType(MdNodeType.Image)!!
        assertEquals("Android robot logo", image.linkLabel)
    }

    @Test
    fun linkInnerTextDigsInnermostTextForNestedEmphasis() {
        // 04 "Nested Inline Content": [*Italic link text*](url) → innermost text "Italic link text".
        val (root, source) = parseCorpus("04-links-inline")
        val nested = root.allOfType(MdNodeType.Link)
            .firstOrNull { it.textIn(source).startsWith("[*Italic link text*]") }
        assertNotNull("04 must contain a link with nested emphasis", nested)
        assertEquals("Italic link text", nested!!.linkInnerText)
    }

    @Test
    fun linkLabelNullForNonLink() {
        val (root, _) = parseCorpus("01-plain-paragraphs")
        assertNull(root.firstOfType(MdNodeType.Paragraph)!!.linkLabel)
        assertNull(root.firstOfType(MdNodeType.Paragraph)!!.linkInnerText)
    }

    // ── T12.5 regression pins: CodeBlockKind wire extras (sample 07 + 31) ─────

    /**
     * T12.5 regression pin — sample 07 (07-fenced-code-kotlin):
     * All code blocks are genuinely fenced; [isFencedCode] must be true.
     * The new [codeFenceKindExtra] returns `true` (kind byte = 1) rather than
     * falling through to the heuristic.
     */
    @Test
    fun sample07_fencedBlocks_isFencedCode_true_via_kindByte() {
        val (root, _) = parseCorpus("07-fenced-code-kotlin")
        val blocks = root.allOfType(MdNodeType.CodeBlock)
        assertTrue("07 must contain code blocks", blocks.isNotEmpty())
        blocks.forEach { block ->
            assertTrue(
                "07 fenced kotlin block must have isFencedCode=true (kind byte path)",
                block.isFencedCode,
            )
        }
    }

    /**
     * T12.5 regression pin — sample 31 (31-deep-nesting):
     * Both code blocks are genuinely fenced (` ```kotlin ` markers); [isFencedCode]
     * must be true. This pin guards against future regressions where the extras
     * path might malfunction for nested/deep fenced blocks.
     *
     * Note: sample 31 contains no indented code blocks — both blocks carry
     * CodeBlockKind::Fenced in the wire extras (kind byte = 1). The false-positive
     * threat class (indented block whose first content line is a literal fence) would
     * require a different corpus sample; the kind-byte encoding eliminates that class
     * unconditionally by deferring to pulldown-cmark's ground truth.
     *
     * Heuristic evidence: with the extras path disabled (codeFenceKindExtra returns null),
     * both blocks would fall through to `codeLang != null` (lang="kotlin") → still true.
     * So for sample 31 the heuristic and extras agree. The kind byte matters for the
     * threat class (indented + literal fence line), where the heuristic gives a
     * false-positive but the kind byte gives false (correct).
     */
    @Test
    fun sample31_fencedBlocks_isFencedCode_true() {
        val (root, _) = parseCorpus("31-deep-nesting")
        val blocks = root.allOfType(MdNodeType.CodeBlock)
        assertTrue("31 must contain code blocks", blocks.isNotEmpty())
        // All 2 code blocks in 31-deep-nesting are genuinely fenced (```kotlin).
        blocks.forEach { block ->
            assertTrue(
                "31 fenced kotlin block must have isFencedCode=true",
                block.isFencedCode,
            )
        }
    }

    /**
     * T12.5 pin — sample 09 (09-indented-code):
     * All code blocks are genuinely indented; [isFencedCode] must be false.
     * The [codeFenceKindExtra] returns `false` (kind byte = 0), which is the
     * authoritative ground truth rather than the heuristic (which also returns
     * false here, but for the right reasons). If an indented block's content
     * started with ` ```kotlin `, the heuristic would give a false-positive while
     * the kind byte path correctly returns false.
     */
    @Test
    fun sample09_indentedBlocks_isFencedCode_false_via_kindByte() {
        val (root, _) = parseCorpus("09-indented-code")
        val blocks = root.allOfType(MdNodeType.CodeBlock)
        assertTrue("09 must contain code blocks", blocks.isNotEmpty())
        blocks.forEach { block ->
            assertFalse(
                "09 indented block must have isFencedCode=false (kind byte path)",
                block.isFencedCode,
            )
        }
    }

    // ── Stage-3 accessor #8: isBlockquoteMarker (always false on native) ─

    @Test
    fun isBlockquoteMarkerAlwaysFalse() {
        // pulldown emits no `>` marker leaf — there is nothing to skip on the native tree.
        val (root, _) = parseCorpus("16-blockquotes-nested")
        val blockquote = root.firstOfType(MdNodeType.Blockquote)!!
        assertFalse("blockquote element itself is not a marker", blockquote.isBlockquoteMarker)
        // No node anywhere in the tree is a blockquote marker.
        assertFalse(
            "native tree has no blockquote-marker token",
            root.descendants().any { it.isBlockquoteMarker },
        )
    }

    // ── KU-2: byte→char offset translation, CJK end-to-end (sample 24) ─

    @Test
    fun cjkHeadingTextInIsExactChineseSubstring() {
        // The blob carries UTF-8 BYTE offsets; reading the file as real UTF-8 + the in-tree
        // byte→char translation must make textIn slice the exact Chinese characters. The first
        // heading in 24-cjk-mixed.md is `## CJK 混合内容`.
        val (root, source) = parseCorpus("24-cjk-mixed")
        val heading = root.allOfType(MdNodeType.Heading).first { it.headingLevel == 2 }
        // The Heading node span is the ATX content (no leading `##`); its concatenated child text
        // reconstructs the visible heading WITHOUT the marker.
        val headingText = heading.children.joinToString("") { it.textIn(source) }
        assertEquals("CJK 混合内容", headingText)
        // And the full node-span textIn round-trips against the real UTF-8 source.
        assertEquals(source.substring(heading.startOffset, heading.endOffset), heading.textIn(source))
    }

    @Test
    fun cjkCodeFenceContentRangeSlicesTranslatedCharOffsets() {
        // 24-cjk-mixed.md ends with a ```kotlin block whose body contains CJK comments (发送缓存值).
        // codeFenceContentRange must be in TRANSLATED char offsets so the renderer's
        // content.substring(range.first, range.last + 1) against the UTF-16 source is faithful —
        // a regression guard for using wrapped (translated) children, not raw packed byte offsets.
        val (root, source) = parseCorpus("24-cjk-mixed")
        val fence = root.allOfType(MdNodeType.CodeBlock).first { it.isFencedCode }
        val range = fence.codeFenceContentRange
        assertNotNull("fenced kotlin block has a content range", range)
        val body = source.substring(range!!.first, range.last + 1)
        // The CJK comment text inside the code body must round-trip intact (would be garbled if the
        // range were raw byte offsets indexed into the UTF-16 string).
        assertTrue("body must contain the CJK code comment", body.contains("发送缓存值"))
        assertTrue("body starts with the first code line", body.trimStart().startsWith("// 用户仓库"))
        assertFalse("body must not contain the opening fence", body.contains("```kotlin"))
    }

    @Test
    fun cjkParagraphTextInRoundTripsAgainstUtf8Source() {
        // A non-heading CJK node must also slice correctly: pick a paragraph that contains both
        // CJK and ASCII and assert textIn == the exact substring of the real UTF-8 source.
        val (root, source) = parseCorpus("24-cjk-mixed")
        val para = root.allOfType(MdNodeType.Paragraph)
            .first { it.textIn(source).contains("Kotlin") && it.textIn(source).contains("静态类型") }
        // Round-trip: translated offsets index the SAME UTF-16 string we read.
        assertEquals(source.substring(para.startOffset, para.endOffset), para.textIn(source))
        // Inline code inside the CJK content slices to the exact backtick span.
        val inlineCode = root.allOfType(MdNodeType.InlineCode)
            .firstOrNull { it.textIn(source) == "`Jetpack Compose`" }
        assertNotNull("24 must contain `Jetpack Compose` inline code", inlineCode)
    }

    // ── KU-2: ByteToCharTranslator direct unit tests (no blob needed) ─

    @Test
    fun translatorIsNullForPureAscii() {
        // Fast path: pure-ASCII source → identity → null translator (zero overhead).
        assertNull(ByteToCharTranslator.of(""))
        assertNull(ByteToCharTranslator.of("plain ascii text 123 !@#"))
    }

    @Test
    fun translatorMapsCjkBytesToChars() {
        // "中" is U+4E2D → 3 UTF-8 bytes, 1 UTF-16 char. "a中b": bytes a=[0], 中=[1..3], b=[4].
        val t = ByteToCharTranslator.of("a中b")!!
        assertEquals(0, t.toCharOffset(0)) // 'a' starts at char 0
        assertEquals(1, t.toCharOffset(1)) // first byte of 中 → char 1
        assertEquals(1, t.toCharOffset(2)) // mid byte of 中 clamps to its start char 1
        assertEquals(1, t.toCharOffset(3)) // last byte of 中 → char 1
        assertEquals(2, t.toCharOffset(4)) // 'b' starts at char 2
        assertEquals(3, t.toCharOffset(5)) // end-of-string sentinel → char length 3
    }

    @Test
    fun translatorHandlesSurrogatePairEmoji() {
        // "🚀" is U+1F680 → 4 UTF-8 bytes, 2 UTF-16 chars (surrogate pair).
        // "x🚀y": bytes x=[0], 🚀=[1..4], y=[5]; chars x=0, 🚀=1..2, y=3.
        val src = "x🚀y"
        val t = ByteToCharTranslator.of(src)!!
        assertEquals(0, t.toCharOffset(0)) // 'x' → char 0
        assertEquals(1, t.toCharOffset(1)) // first byte of 🚀 → char 1 (high surrogate)
        assertEquals(1, t.toCharOffset(4)) // last byte of 🚀 → char 1 (start of code point)
        assertEquals(3, t.toCharOffset(5)) // 'y' → char 3 (after the 2 surrogate chars)
        assertEquals(4, t.toCharOffset(6)) // end sentinel → char length 4
        // Cross-check against the actual UTF-16 string: substring by translated offsets is faithful.
        // 'y' occupies a single byte at byte 5; its char offset is 3.
        assertEquals("y", src.substring(t.toCharOffset(5), t.toCharOffset(6)))
        // The emoji spans byte [1,5) → char [1,3).
        assertEquals("🚀", src.substring(t.toCharOffset(1), t.toCharOffset(5)))
    }

    @Test
    fun translatorHandlesMixedCjkAsciiAndClampsOutOfRange() {
        val src = "Kotlin 是 a language"
        val t = ByteToCharTranslator.of(src)!!
        // "Kotlin " is 7 ASCII bytes/chars; 是 (U+662F) is 3 bytes, 1 char at char index 7.
        assertEquals(7, t.toCharOffset(7))   // first byte of 是 → char 7
        assertEquals(8, t.toCharOffset(10))  // byte right after 是 (the space) → char 8
        // Out-of-range clamps to the string char length (never throws).
        assertEquals(src.length, t.toCharOffset(Int.MAX_VALUE))
        assertEquals(0, t.toCharOffset(-5))
    }

    @Test
    fun asciiFastPathOffsetsIdenticalToByteOffsets() {
        // Regression pin: a pure-ASCII corpus sample produces offsets identical to the raw packed
        // byte offsets (the translator is null → identity), proving the fast path is preserved.
        val baseName = "07-fenced-code-kotlin"
        val blob = File(corpusDir, "$baseName.pmda").readBytes()
        val source = File(corpusDir, "$baseName.md").readText(Charsets.UTF_8)
        // Confirm the sample really is pure ASCII so this is a valid fast-path check.
        assertTrue("01 must be pure ASCII for the fast-path pin", source.all { it.code < 128 })
        assertNull("pure-ASCII source → null translator (identity)", ByteToCharTranslator.of(source))

        val reader = PackedAstReader(blob)
        val translated = nativeMdTreeOrNull(reader, source)!!
        // Re-decode the raw packed byte offsets directly and compare to the translated offsets.
        val rawRoot = PackedAstReader(blob).root()!!
        fun assertSameOffsets(node: MdNode, raw: app.amber.agent.ui.components.richtext.nativebridge.PackedAstNode) {
            assertEquals("startOffset identical on ASCII fast path", raw.startOffset, node.startOffset)
            assertEquals("endOffset identical on ASCII fast path", raw.endOffset, node.endOffset)
            node.children.forEachIndexed { i, child ->
                assertSameOffsets(child, raw.children[i])
            }
        }
        assertSameOffsets(translated, rawRoot)
    }
}
