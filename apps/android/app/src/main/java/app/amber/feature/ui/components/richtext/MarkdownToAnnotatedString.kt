package app.amber.feature.ui.components.richtext

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.amber.feature.ui.theme.AtomOneDarkPalette
import app.amber.feature.ui.theme.AtomOneLightPalette
import app.amber.feature.ui.theme.JetbrainsMono
import app.amber.feature.ui.theme.LocalDarkMode
import app.amber.highlight.HighlightToken
import app.amber.highlight.HighlightTextColorPalette
import app.amber.highlight.buildHighlightText
import app.amber.feature.ui.components.richtext.tree.MdNode
import app.amber.feature.ui.components.richtext.tree.MdNodeType
import app.amber.feature.ui.components.richtext.tree.textIn

/**
 * Streaming-phase single-Text markdown mapping: MdNode tree + source string +
 * base style → one AnnotatedString. Settled messages keep the full block-tree
 * renderer (Markdown.kt); this layer trades fidelity for layout stability —
 * a single Text that only grows, never re-lays-out its component tree.
 *
 * Mapping rules (deliberate degradations vs the block tree):
 * - paragraphs: single `\n` between blocks; the block tree's non-last
 *   `padding(bottom = fontSize)` is approximated by an explicit gap line —
 *   a one-space line whose SpanStyle(fontSize) sets the line height.
 * - headings: SpanStyle(fontSize/Bold) copied from [HeaderStyle] + gap lines
 *   mirroring the block tree's headingPadding (16..6dp) before and after.
 * - inline: bold/italic/strikethrough/inline-code(mono+bg)/links
 *   (LinkAnnotation.Clickable → context.openUrl, mirroring the block tree's
 *   AnnotatedString arm: label = linkLabel trimmed of brackets).
 * - code blocks (fenced): JetbrainsMono 12sp + flat surfaceContainer
 *   background, natural line height (approximating the settled
 *   HighlightCodeBlock card); CLOSED fences with a cache hit render the
 *   QuickJS highlight tokens in the same AtomOne palette as the settled path
 *   (an unclosed streaming tail stays uncolored, mirroring
 *   `completeCodeBlock = false`). Indented code renders as plain body text,
 *   matching the settled indented arm (plain Text).
 * - lists: one paragraph per item, synthesized "• "/"◦ "/"▪ " or "N. " prefix;
 *   task items get "☑ "/"☐ ". No indentation (see the ParagraphStyle ban).
 * - blockquote: italic + faint background over the whole quote range; the
 *   settled arm's bar/indent needs drawBehind/ParagraphStyle — unavailable.
 * - table: the settled extractor's rows in one monospace block, header bold
 *   (alignment and cell composables are unavailable inside one Text).
 * - image: "[图片: alt]"; horizontal rule: "———".
 * - MathInline / MathBlock: the settled renderer's delimiter normalization in
 *   a monospace fallback (real KaTeX needs a composable/canvas); HtmlBlock /
 *   Unknown remain raw source passthrough.
 *
 * NO ParagraphStyle span appears anywhere in the output: ANY ParagraphStyle
 * span boundary — identical or different spans, adjacent or not — makes
 * Compose's text layout emit an extra phantom empty line, which stacked
 * 5-8 invisible lines per block boundary on device (2026-08-16 "段间大空白";
 * GapLineLayoutMeasureTest line metrics). Gap-line heights therefore ride
 * SpanStyle(fontSize) covering the gap space AND its terminating newline,
 * and list/quote/code paragraph styles are dropped — the settled block tree
 * restores full geometry on the post-streaming swap.
 *
 * Inserted characters (bullets, task marks, rule glyph, image label, gap
 * lines) are SYNTHETIC — the streaming reveal stamps final-string indices, so
 * no per-character source-offset mapping is attempted (see
 * StreamingSingleTextMarkdown).
 */
internal data class MarkdownSingleTextStyle(
    /** Gap-line height for non-last paragraphs (block tree fontSize padding). */
    val paragraphSpaceAfter: TextUnit,
    /** Gap-line heights per heading level 1..6 (block tree headingPadding). */
    val headingSpacing: List<TextUnit>,
    val linkColor: Color,
    val bulletColor: Color,
    val inlineCodeBackground: Color,
    val codeBackground: Color,
    /** Token palette for closed code blocks — the settled path's AtomOne pair. */
    val codePalette: HighlightTextColorPalette?,
    /** Code font size — mirrors the settled HighlightCodeBlock default. */
    val codeFontSize: TextUnit,
    /** Settled blockquote's drawBehind fill (surfaceVariant @ 0.12). */
    val quoteBackground: Color,
    val ruleColor: Color,
)

@Composable
internal fun rememberMarkdownSingleTextStyle(): MarkdownSingleTextStyle {
    val colorScheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val baseFontSize = LocalTextStyle.current.fontSize
    val darkMode = LocalDarkMode.current
    return remember(colorScheme, density, baseFontSize, darkMode) {
        MarkdownSingleTextStyle(
            paragraphSpaceAfter = baseFontSize,
            headingSpacing = HEADING_PADDING_DP.map { with(density) { it.dp.toSp() } },
            linkColor = colorScheme.primary,
            bulletColor = colorScheme.primary,
            inlineCodeBackground = colorScheme.secondaryContainer.copy(alpha = 0.2f),
            codeBackground = colorScheme.surfaceContainer,
            codePalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette,
            codeFontSize = 12.sp,
            quoteBackground = colorScheme.surfaceVariant.copy(alpha = 0.12f),
            ruleColor = colorScheme.primary.copy(alpha = 0.5f),
        )
    }
}

private val HEADING_PADDING_DP = listOf(16, 14, 12, 10, 8, 6)

private val STREAMING_BREAK_LINE_REGEX = Regex("(?i)<br\\s*/?>")

// Private in Markdown.kt (leaf arm) — same rule copied for the single-text path.
private fun streamingResolveEscapes(text: String): String = resolveBackslashEscapes(text)

private data class MdBlockCtx(
    val style: MarkdownSingleTextStyle,
    /** Current list nesting level (bullet glyph progression). */
    val listLevel: Int,
    /** Streaming fallback keeps source delimiters when LaTeX rendering is off. */
    val enableLatexRendering: Boolean,
)

private class JoinedBlock(
    val text: AnnotatedString,
    /** True when this block is a paragraph — non-last paragraphs gain a gap line. */
    val isParagraph: Boolean,
    /** Gap line emitted before the text (headings). */
    val spaceBefore: TextUnit? = null,
    /** Gap line emitted after the text (headings always, paragraphs via join rule). */
    val spaceAfter: TextUnit? = null,
    /**
     * Child blocks contributing their own paragraphs AFTER [text] in the
     * flattened stream (blockquote/root/list/list-item tails). Nested content
     * must NEVER be pre-joined into [text]: embedded blobs re-introduce the
     * ParagraphStyle seams this layer bans (2026-08-16 "段间大空白"). The
     * final string is built once, flat, with no ParagraphStyle at all.
     */
    val nested: List<JoinedBlock>? = null,
    /** Span-level overlay applied over this block's whole nested range (quote). */
    val overlaySpanStyle: SpanStyle? = null,
)

/**
 * MdNode tree + source → AnnotatedString. [root] is normally a Root node;
 * anything else is treated as a single block.
 *
 * [codeHighlights] maps [streamingCodeBlockKey] → QuickJS tokens for CLOSED
 * fenced blocks; a hit renders syntax colors (settled-path AtomOne palette),
 * a miss keeps the flat monospace+background form.
 */
internal fun mdNodeToAnnotatedString(
    source: String,
    root: MdNode,
    style: MarkdownSingleTextStyle,
    onClickUrl: (String) -> Unit = {},
    codeHighlights: Map<String, List<HighlightToken>> = emptyMap(),
    enableLatexRendering: Boolean = true,
): AnnotatedString {
    val ctx = MdBlockCtx(
        style = style,
        listLevel = 0,
        enableLatexRendering = enableLatexRendering,
    )
    val children = if (root.type == MdNodeType.Root) root.children else listOf(root)
    return joinBlocks(buildBlocks(children, source, ctx, onClickUrl, codeHighlights), style)
}

/** Same length+hash key shape the render layer and the highlight pipeline agree on. */
internal fun streamingCodeBlockKey(lang: String, body: String): String =
    "$lang:${body.length}:${body.hashCode()}"

/** Same cap as HighlightText's MAX_CODE_LENGTH — oversized blocks never highlight. */
internal const val STREAMING_CODE_HIGHLIGHT_MAX_CHARS = 4096

/** Fenced-code body extraction shared by the renderer and the highlight collector. */
internal fun streamingCodeBlockBody(node: MdNode, source: String): String? {
    if (!node.isFencedCode) return null
    val range = node.codeFenceContentRange ?: return null
    return source.substring(range.first, range.last + 1).trimIndent()
}

internal class StreamingClosedCodeBlock(val key: String, val lang: String, val body: String)

/**
 * CLOSED fenced code blocks (closing fence present + non-blank lang + body
 * within the highlight cap) — the exact set the render layer will look up in
 * the token cache. Bodies of closed blocks never change while streaming, so
 * each key is highlighted at most once.
 */
internal fun collectStreamingClosedCodeBlocks(root: MdNode, source: String): List<StreamingClosedCodeBlock> {
    val out = ArrayList<StreamingClosedCodeBlock>()
    fun walk(node: MdNode) {
        if (node.type == MdNodeType.CodeBlock &&
            node.isFencedCode &&
            node.codeFenceEndOffset != null
        ) {
            val lang = node.codeLang?.takeIf { it.isNotBlank() }
            // Same trimIndent + trimEnd as buildCodeBlock's key input.
            val body = streamingCodeBlockBody(node, source)?.trimEnd('\n', '\r')
            if (lang != null && !body.isNullOrEmpty() && body.length <= STREAMING_CODE_HIGHLIGHT_MAX_CHARS) {
                out.add(StreamingClosedCodeBlock(streamingCodeBlockKey(lang, body), lang, body))
            }
        }
        node.children.forEach(::walk)
    }
    walk(root)
    return out
}

/**
 * A one-space line whose height renders the block tree's dp gap. The
 * SpanStyle(fontSize) must cover the terminating '\n' as well — an unstyled
 * newline re-inflates the line to body height — and the entry then owns that
 * newline so the joiner adds no second separator. fontSize ≈ 0.875 × target
 * line height (14sp renders a 16px line at density 1 — measured by
 * GapLineLayoutMeasureTest; a ParagraphStyle(lineHeight) would be exact but
 * is banned from this layer, see [joinBlocks]).
 */
private fun gapLineEntry(height: TextUnit): ParaEntry = ParaEntry(
    text = buildAnnotatedString {
        withStyle(SpanStyle(fontSize = height * STREAMING_GAP_FONT_RATIO)) {
            append(STREAMING_GAP_LINE)
        }
    },
    ownsTrailingNewline = true,
)

private const val STREAMING_GAP_LINE = " \n"
private const val STREAMING_GAP_FONT_RATIO = 0.875f

/**
 * Emits a block list into per-block AnnotatedStrings (spans self-contained),
 * skipping the JetBrains blockquote `>` marker token and empty blocks.
 */
private fun buildBlocks(
    children: List<MdNode>,
    source: String,
    ctx: MdBlockCtx,
    onClickUrl: (String) -> Unit,
    codeHighlights: Map<String, List<HighlightToken>> = emptyMap(),
): List<JoinedBlock> {
    val out = ArrayList<JoinedBlock>(children.size)
    for (child in children) {
        if (child.isBlockquoteMarker) continue
        // Empty blocks (marker-only headings, empty lists) must not leave a
        // stray separator line in the join.
        buildBlock(child, source, ctx, onClickUrl, codeHighlights)
            ?.takeIf { it.text.isNotEmpty() || !it.nested.isNullOrEmpty() }
            ?.let { out.add(it) }
    }
    return out
}

/**
 * One paragraph in the flat output stream. [ownsTrailingNewline] marks gap
 * lines whose text already ends with a '\n' inside their fontSize span — the
 * joiner must not add a second separator after them.
 */
private class ParaEntry(
    val text: AnnotatedString,
    val ownsTrailingNewline: Boolean = false,
)

private class SpanOverlay(
    val fromPara: Int,
    val toParaExclusive: Int,
    val style: SpanStyle,
)

/**
 * Joins blocks with single `\n` separators, inserting gap lines for block
 * spacing (spaceBefore/spaceAfter). Non-last paragraphs gain
 * [MarkdownSingleTextStyle.paragraphSpaceAfter] — the block tree's
 * `padding(bottom = fontSize)` for non-last paragraphs.
 *
 * The output is built ONCE from a flat paragraph stream and contains NO
 * ParagraphStyle span: ANY ParagraphStyle span boundary — identical or
 * different spans, adjacent or not — makes Compose's text layout emit an
 * extra phantom empty line, which stacked 5-8 invisible lines per block
 * boundary on device (2026-08-16 "段间大空白"; GapLineLayoutMeasureTest line
 * metrics). Overlapping ParagraphStyle spans are likewise rejected by
 * AnnotatedString, so nested content (quote/root/list tails) is flattened
 * recursively instead of being embedded as pre-joined blobs.
 */
private fun joinBlocks(blocks: List<JoinedBlock>, style: MarkdownSingleTextStyle): AnnotatedString {
    if (blocks.isEmpty()) return AnnotatedString("")
    val paragraphs = ArrayList<ParaEntry>()
    val overlays = ArrayList<SpanOverlay>()
    flattenBlocks(blocks, style, paragraphs, overlays)
    if (paragraphs.isEmpty()) return AnnotatedString("")
    return buildAnnotatedString {
        val paraStarts = IntArray(paragraphs.size)
        val paraEnds = IntArray(paragraphs.size)
        paragraphs.forEachIndexed { index, entry ->
            if (index > 0 && !paragraphs[index - 1].ownsTrailingNewline) append('\n')
            paraStarts[index] = length
            if (index == paragraphs.lastIndex && entry.ownsTrailingNewline) {
                // A trailing gap line drops its embedded '\n' — a final
                // newline would render an extra empty tail line.
                append(entry.text.subSequence(0, entry.text.length - 1))
            } else {
                append(entry.text)
            }
            paraEnds[index] = length
        }
        overlays.forEach { overlay ->
            addStyle(overlay.style, paraStarts[overlay.fromPara], paraEnds[overlay.toParaExclusive - 1])
        }
    }
}

private fun flattenBlocks(
    blocks: List<JoinedBlock>,
    style: MarkdownSingleTextStyle,
    paragraphs: MutableList<ParaEntry>,
    overlays: MutableList<SpanOverlay>,
) {
    blocks.forEachIndexed { index, block ->
        block.spaceBefore?.let { paragraphs += gapLineEntry(it) }
        if (block.text.isNotEmpty()) {
            paragraphs += ParaEntry(block.text)
        }
        block.nested?.let { nested ->
            val from = paragraphs.size
            flattenBlocks(nested, style, paragraphs, overlays)
            if (block.overlaySpanStyle != null && paragraphs.size > from) {
                overlays += SpanOverlay(from, paragraphs.size, block.overlaySpanStyle)
            }
        }
        val trailingGap = block.spaceAfter
            ?: if (block.isParagraph && index < blocks.lastIndex) style.paragraphSpaceAfter else null
        trailingGap?.let { paragraphs += gapLineEntry(it) }
    }
}

private fun buildBlock(
    node: MdNode,
    source: String,
    ctx: MdBlockCtx,
    onClickUrl: (String) -> Unit,
    codeHighlights: Map<String, List<HighlightToken>> = emptyMap(),
): JoinedBlock? {
    return when (node.type) {
        MdNodeType.Root -> JoinedBlock(
            AnnotatedString(""),
            isParagraph = false,
            nested = buildBlocks(node.children, source, ctx, onClickUrl, codeHighlights),
        )

        MdNodeType.Paragraph -> buildParagraph(node, source, ctx, onClickUrl)

        MdNodeType.Heading -> buildHeading(node, source, ctx, onClickUrl)

        MdNodeType.ListUnordered -> buildList(node, source, ctx, onClickUrl, ordered = false, codeHighlights)

        MdNodeType.ListOrdered -> buildList(node, source, ctx, onClickUrl, ordered = true, codeHighlights)

        MdNodeType.Blockquote -> buildBlockquote(node, source, ctx, onClickUrl, codeHighlights)

        MdNodeType.CodeBlock -> buildCodeBlock(node, source, ctx, codeHighlights)

        MdNodeType.Table -> buildTable(node, source)

        MdNodeType.HorizontalRule -> JoinedBlock(
            buildAnnotatedString {
                withStyle(SpanStyle(color = ctx.style.ruleColor)) {
                    append(STREAMING_RULE_GLYPH)
                }
            },
            isParagraph = false,
        )

        MdNodeType.HtmlBlock -> rawBlock(node.textIn(source))

        // Compose AnnotatedString cannot host the settled MathInline/MathBlock
        // canvas. Keep the formula body and use the exact delimiter
        // normalization shared by LatexText instead of leaking `$`/`\\[` into
        // the streaming UI.
        MdNodeType.MathBlock -> buildMathBlock(node, source, ctx)

        // Image/MathInline at block level (defensive — normally wrapped in a
        // paragraph): render inline so no source character is dropped.
        MdNodeType.Image -> inlineBlock(node, source, ctx, onClickUrl)

        else -> rawBlock(node.textIn(source))
    }
}

private const val STREAMING_RULE_GLYPH = "———"

private fun rawBlock(text: String): JoinedBlock? {
    val clean = text.trimEnd('\n', '\r')
    if (clean.isEmpty()) return null
    return JoinedBlock(AnnotatedString(clean), isParagraph = false)
}

private fun buildMathBlock(
    node: MdNode,
    source: String,
    ctx: MdBlockCtx,
): JoinedBlock? {
    val rawFormula = node.textIn(source)
    val formula = if (ctx.enableLatexRendering) processLatex(rawFormula) else rawFormula
    if (formula.isBlank()) return null
    val text = buildAnnotatedString {
        withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
            append(formula)
        }
    }
    return JoinedBlock(
        text = text,
        isParagraph = false,
    )
}

private fun inlineBlock(
    node: MdNode,
    source: String,
    ctx: MdBlockCtx,
    onClickUrl: (String) -> Unit,
): JoinedBlock? {
    val text = buildAnnotatedString { appendInlineNode(node, source, ctx, onClickUrl) }
    if (text.isEmpty()) return null
    return JoinedBlock(text, isParagraph = false)
}

private fun buildParagraph(
    node: MdNode,
    source: String,
    ctx: MdBlockCtx,
    onClickUrl: (String) -> Unit,
): JoinedBlock? {
    // Trailing EOL/whitespace Unknown tokens terminate the paragraph in the
    // JetBrains tree; the inter-block gap is synthesized by joinBlocks, so the
    // literal terminators must not render as extra line breaks.
    val inlineChildren = node.children.dropLastWhile {
        it.type == MdNodeType.Unknown && it.textIn(source).isBlank()
    }
    if (inlineChildren.isEmpty()) return null
    val text = buildAnnotatedString {
        appendInlineRun(inlineChildren, source, ctx, onClickUrl)
    }
    if (text.isEmpty()) return null
    return JoinedBlock(
        text = text,
        isParagraph = true,
    )
}

private fun buildHeading(
    node: MdNode,
    source: String,
    ctx: MdBlockCtx,
    onClickUrl: (String) -> Unit,
): JoinedBlock? {
    val level = (node.headingLevel ?: 1).coerceIn(1, 6)
    // JVM shape: inline content sits in an ATX_CONTENT→Paragraph wrapper child
    // (markers are Unknown siblings); native shape: flat inline children.
    val contentNode = node.children.firstOrNull { it.type == MdNodeType.Paragraph }
        ?: node.takeIf { it.children.any { child -> child.type != MdNodeType.Unknown } }
    val inlineChildren = contentNode?.children.orEmpty().dropLastWhile {
        it.type == MdNodeType.Unknown && it.textIn(source).isBlank()
    }
    if (inlineChildren.isEmpty()) return null
    val fontSize = when (level) {
        1 -> HeaderStyle.H1.fontSize
        2 -> HeaderStyle.H2.fontSize
        3 -> HeaderStyle.H3.fontSize
        4 -> HeaderStyle.H4.fontSize
        5 -> HeaderStyle.H5.fontSize
        else -> HeaderStyle.H6.fontSize
    }
    val text = buildAnnotatedString {
        withStyle(SpanStyle(fontSize = fontSize, fontWeight = FontWeight.Bold)) {
            appendInlineRun(inlineChildren, source, ctx, onClickUrl)
        }
    }.trimEnds() // block tree heading path trims (trim = true)
    val spacing = ctx.style.headingSpacing.getOrElse(level - 1) { 8.sp }
    return JoinedBlock(
        text = text,
        isParagraph = false,
        spaceBefore = spacing,
        spaceAfter = spacing,
    )
}

private fun buildBlockquote(
    node: MdNode,
    source: String,
    ctx: MdBlockCtx,
    onClickUrl: (String) -> Unit,
    codeHighlights: Map<String, List<HighlightToken>> = emptyMap(),
): JoinedBlock? {
    val inner = buildBlocks(node.children, source, ctx, onClickUrl, codeHighlights)
    if (inner.isEmpty()) return null
    // Settled style: italic + faint background fill, TEXT COLOR UNCHANGED
    // (the settled arm only provides an italic TextStyle; no dimming). The
    // vertical bar and indent need drawBehind/ParagraphStyle — unavailable in
    // a single Text. The italic/background look rides the nested range as a
    // SPAN overlay so no pre-joined blob (and its paragraph-style seams)
    // ever forms.
    return JoinedBlock(
        text = AnnotatedString(""),
        isParagraph = false,
        nested = inner,
        overlaySpanStyle = SpanStyle(fontStyle = FontStyle.Italic, background = ctx.style.quoteBackground),
    )
}

private fun buildList(
    node: MdNode,
    source: String,
    ctx: MdBlockCtx,
    onClickUrl: (String) -> Unit,
    ordered: Boolean,
    codeHighlights: Map<String, List<HighlightToken>>,
): JoinedBlock {
    val innerCtx = ctx.copy(listLevel = ctx.listLevel + 1)
    val items = ArrayList<JoinedBlock>(node.children.size)
    var index = 1
    node.children.forEach { child ->
        if (child.type != MdNodeType.ListItem) return@forEach
        val prefix = if (ordered) {
            // JVM: the literal LIST_NUMBER marker ("7. ") preserves the author's
            // exact numbering; native (no marker): derive from listStart.
            child.findChildOfTypeRecursive(MdNodeType.Unknown)?.textIn(source)
                ?: "${(node.listStart ?: 1L) + (index - 1)}. "
        } else {
            when (ctx.listLevel % 3) {
                0 -> "• "
                1 -> "◦ "
                else -> "▪ "
            }
        }
        buildListItem(child, source, innerCtx, onClickUrl, prefix, codeHighlights)?.let {
            items.add(it)
        }
        index++
    }
    return JoinedBlock(
        text = AnnotatedString(""),
        isParagraph = false,
        nested = items,
    )
}

private fun buildListItem(
    item: MdNode,
    source: String,
    ctx: MdBlockCtx,
    onClickUrl: (String) -> Unit,
    prefix: String,
    codeHighlights: Map<String, List<HighlightToken>>,
): JoinedBlock? {
    val paragraph = item.children.firstOrNull { it.type == MdNodeType.Paragraph }
    val inlineRun = (paragraph?.children ?: item.children.filter { child ->
        child.type != MdNodeType.Unknown &&
            child.type != MdNodeType.TaskListMarker &&
            child.type != MdNodeType.ListUnordered &&
            child.type != MdNodeType.ListOrdered
    }).dropLastWhile { it.type == MdNodeType.Unknown && it.textIn(source).isBlank() }
    val taskMarker = item.children.firstOrNull { it.type == MdNodeType.TaskListMarker }
    val nestedLists = item.children.filter {
        it.type == MdNodeType.ListUnordered || it.type == MdNodeType.ListOrdered
    }
    // Non-list, non-marker children (blockquote, html block, …) — rendered as
    // blocks so no source character is dropped.
    val others = item.children.filter { child ->
        child.type != MdNodeType.Paragraph &&
            child.type != MdNodeType.TaskListMarker &&
            child.type != MdNodeType.ListUnordered &&
            child.type != MdNodeType.ListOrdered &&
            child.type != MdNodeType.Unknown
    }
    if (inlineRun.isEmpty() && taskMarker == null && nestedLists.isEmpty() && others.isEmpty()) {
        return null
    }
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = ctx.style.bulletColor)) {
            append(prefix)
        }
        if (taskMarker != null) {
            withStyle(SpanStyle(color = ctx.style.bulletColor)) {
                append(if (taskMarker.taskChecked == true) "☑ " else "☐ ")
            }
        }
        if (inlineRun.isNotEmpty()) {
            appendInlineRun(inlineRun, source, ctx, onClickUrl)
        }
    }
    val tailBlocks = buildList {
        nestedLists.forEach { nested ->
            buildBlock(nested, source, ctx, onClickUrl, codeHighlights)?.let { add(it) }
        }
        others.forEach { other ->
            buildBlock(other, source, ctx, onClickUrl, codeHighlights)?.let { add(it) }
        }
    }
    return JoinedBlock(
        text = text,
        isParagraph = false,
        // Nested lists / stray blocks follow the item head as their own
        // paragraphs in the flat stream (never a pre-joined blob).
        nested = tailBlocks.takeIf { it.isNotEmpty() },
    )
}

private fun buildCodeBlock(
    node: MdNode,
    source: String,
    ctx: MdBlockCtx,
    codeHighlights: Map<String, List<HighlightToken>>,
): JoinedBlock? {
    // Indented code: the settled arm renders a plain Text in body style — no
    // mono font, no background. Fenced: JetbrainsMono 12sp on the settled
    // card's surface color; CLOSED fences with cached tokens get the syntax
    // colors (unclosed streaming tails stay flat, mirroring
    // completeCodeBlock = false in HighlightCodeBlock).
    if (!node.isFencedCode) {
        val clean = node.textIn(source).trimEnd('\n', '\r')
        if (clean.isEmpty()) return null
        return JoinedBlock(AnnotatedString(clean), isParagraph = false)
    }
    val lang = node.codeLang
    val body = streamingCodeBlockBody(node, source)
        // Malformed/unclosed fence without a locatable body — fall back to
        // the raw span rather than rendering nothing.
        ?: node.textIn(source).trimEnd('\n', '\r')
    val clean = body.trimEnd('\n', '\r')
    if (clean.isEmpty()) return null
    val tokens = codeHighlights.takeIf {
        node.codeFenceEndOffset != null &&
            !lang.isNullOrBlank() &&
            clean.length <= STREAMING_CODE_HIGHLIGHT_MAX_CHARS
    }?.get(streamingCodeBlockKey(lang!!, clean))
        ?.takeIf { it.isNotEmpty() }
    val text = buildAnnotatedString {
        withStyle(
            SpanStyle(
                fontFamily = JetbrainsMono,
                fontSize = ctx.style.codeFontSize,
                background = ctx.style.codeBackground,
            )
        ) {
            if (tokens != null && ctx.style.codePalette != null) {
                val palette = ctx.style.codePalette!!
                tokens.forEach { token -> buildHighlightText(token, palette) }
            } else {
                append(clean)
            }
        }
    }
    return JoinedBlock(
        text = text,
        isParagraph = false,
    )
}

private fun buildTable(node: MdNode, source: String): JoinedBlock? {
    // Reuse the settled renderer's table extraction so JVM/native trees and
    // ragged rows have the same column semantics. DataTable fills missing
    // cells; the single-Text fallback does the same with empty strings.
    val table = extractMarkdownTableData(node = node, content = source) ?: return null
    val cellsForColumns: (List<String>) -> List<String> = { cells ->
        List(table.columnCount) { column -> cells.getOrElse(column) { "" } }
    }
    val headerCells = cellsForColumns(table.headers)
    val rows = table.rows.map(cellsForColumns)
    val text = buildAnnotatedString {
        if (headerCells.any { it.isNotEmpty() }) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)) {
                append(headerCells.joinToString(" | "))
            }
        }
        rows.forEach { row ->
            append('\n')
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                append(row.joinToString(" | "))
            }
        }
    }
    return JoinedBlock(text, isParagraph = false)
}

private fun AnnotatedString.Builder.appendInlineRun(
    nodes: List<MdNode>,
    source: String,
    ctx: MdBlockCtx,
    onClickUrl: (String) -> Unit,
) {
    for (node in nodes) {
        appendInlineNode(node, source, ctx, onClickUrl)
    }
}

private fun AnnotatedString.Builder.appendInlineNode(
    node: MdNode,
    source: String,
    ctx: MdBlockCtx,
    onClickUrl: (String) -> Unit,
) {
    when {
        node.isBlockquoteMarker -> Unit

        node.type == MdNodeType.Text -> append(
            streamingResolveEscapes(node.textIn(source)).replace(STREAMING_BREAK_LINE_REGEX, "\n")
        )

        node.type == MdNodeType.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            node.contentChildren.forEach { appendInlineNode(it, source, ctx, onClickUrl) }
        }

        node.type == MdNodeType.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
            node.contentChildren.forEach { appendInlineNode(it, source, ctx, onClickUrl) }
        }

        node.type == MdNodeType.Strikethrough -> withStyle(
            SpanStyle(textDecoration = TextDecoration.LineThrough)
        ) {
            node.contentChildren.forEach { appendInlineNode(it, source, ctx, onClickUrl) }
        }

        node.type == MdNodeType.InlineCode -> withStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 0.95.em,
                background = ctx.style.inlineCodeBackground,
            )
        ) {
            append(node.textIn(source).trim('`'))
        }

        node.type == MdNodeType.Link -> appendLink(node, source, ctx, onClickUrl)

        node.type == MdNodeType.Image -> appendImage(node, source)

        // A single AnnotatedString cannot embed the settled JLatexMath
        // composable. Reuse its delimiter normalization and keep the formula
        // visibly distinct without introducing a second math engine.
        node.type == MdNodeType.MathInline || node.type == MdNodeType.MathBlock -> withStyle(
            SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.95.em)
        ) {
            val rawFormula = node.textIn(source)
            append(if (ctx.enableLatexRendering) processLatex(rawFormula) else rawFormula)
        }

        node.type == MdNodeType.InlineHtml -> append(node.textIn(source))

        node.type == MdNodeType.SoftBreak || node.type == MdNodeType.HardBreak -> append('\n')

        // TaskListMarker is consumed by the item prefix; Unknown (markers,
        // EOLs, unmapped tokens) passes through raw — the leaf-arm contract.
        node.type == MdNodeType.TaskListMarker -> Unit

        else -> append(
            streamingResolveEscapes(node.textIn(source)).replace(STREAMING_BREAK_LINE_REGEX, "\n")
        )
    }
}

private fun AnnotatedString.Builder.appendLink(
    node: MdNode,
    source: String,
    ctx: MdBlockCtx,
    onClickUrl: (String) -> Unit,
) {
    when {
        // GFM_AUTOLINK leaf (bare URL).
        node.children.isEmpty() -> {
            val url = node.textIn(source)
            withLink(streamingClickableLink(url, ctx.style, onClickUrl)) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(url)
                }
            }
        }

        // AUTOLINK `<url>` — contentChildren strips the angle brackets.
        node.isAutolink -> node.contentChildren.forEach { child ->
            val text = child.textIn(source)
            withLink(streamingClickableLink(text, ctx.style, onClickUrl)) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(text)
                }
            }
        }

        // INLINE_LINK — visible label = linkLabel trimmed of brackets (the
        // AnnotatedString arm of the block tree); destination via linkHref.
        else -> {
            val dest = node.linkHref ?: ""
            val label = node.linkLabel?.trim { it == '[' || it == ']' } ?: dest
            withLink(streamingClickableLink(dest, ctx.style, onClickUrl)) {
                withStyle(
                    SpanStyle(
                        color = ctx.style.linkColor,
                        textDecoration = TextDecoration.Underline,
                    )
                ) {
                    append(label)
                }
            }
        }
    }
}

private fun streamingClickableLink(
    url: String,
    style: MarkdownSingleTextStyle,
    onClickUrl: (String) -> Unit,
): LinkAnnotation.Clickable {
    return LinkAnnotation.Clickable(
        tag = url,
        styles = TextLinkStyles(
            style = SpanStyle(
                color = style.linkColor,
                textDecoration = TextDecoration.Underline,
            )
        ),
        linkInteractionListener = LinkInteractionListener {
            onClickUrl(url)
        },
    )
}

private fun AnnotatedString.Builder.appendImage(node: MdNode, source: String) {
    val alt = node.linkLabel?.trim { it == '[' || it == ']' }?.takeIf { it.isNotBlank() }
    append(if (alt != null) "[图片: $alt]" else "[图片]")
}
