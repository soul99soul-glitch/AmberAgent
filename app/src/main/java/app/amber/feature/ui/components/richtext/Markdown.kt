package app.amber.feature.ui.components.richtext

import android.os.Trace
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import app.amber.agent.BuildConfig
import app.amber.feature.ui.components.message.LocalSearchImageUrls
import app.amber.feature.ui.components.message.LocalSearchSources
import app.amber.feature.ui.components.message.SearchSourcesRegistry
import app.amber.feature.ui.components.table.DataTable
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.theme.JetbrainsMono
import app.amber.feature.ui.utils.amberTraceMeasure
import app.amber.core.utils.openUrl
import app.amber.core.utils.toDp
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import app.amber.feature.ui.components.richtext.tree.JvmMdNode
import app.amber.feature.ui.components.richtext.tree.MdNode
import app.amber.feature.ui.components.richtext.tree.MdNodeType
import app.amber.feature.ui.components.richtext.tree.nativeMdTreeOrNull
import app.amber.feature.ui.components.richtext.tree.textIn
import app.amber.feature.ui.components.richtext.nativebridge.MarkdownNativeSwitch
import app.amber.feature.ui.components.richtext.nativebridge.MarkdownPreprocessNative
import app.amber.agent.ui.components.richtext.nativebridge.MarkdownParserNative
import app.amber.agent.ui.components.richtext.nativebridge.PackedAstReader
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * When false, markdown nodes use wrap-content width instead of fill-max-width.
 * Used by user message bubbles to allow adaptive width.
 */
internal val LocalMarkdownFillWidth = compositionLocalOf { true }
private val LocalMarkdownSourceOffsetBase = compositionLocalOf { 0 }
private val LocalMarkdownSyntheticSuffixStart = compositionLocalOf { Int.MAX_VALUE }
private val LocalStreamingMarkdownMotionScope = compositionLocalOf<StreamingMarkdownMotionScope?> { null }

private fun Modifier.fillWidthIf(fill: Boolean): Modifier = if (fill) fillMaxWidth() else this

private val flavour by lazy {
    GFMFlavourDescriptor(
        makeHttpsAutoLinks = true, useSafeLinks = true
    )
}

private val parser by lazy {
    MarkdownParser(flavour)
}

private val INLINE_LATEX_REGEX = Regex("\\\\\\((.+?)\\\\\\)")
private val BLOCK_LATEX_REGEX = Regex("\\\\\\[(.+?)\\\\\\]", RegexOption.DOT_MATCHES_ALL)
val THINKING_REGEX = Regex("<think>([\\s\\S]*?)(?:</think>|$)", RegexOption.DOT_MATCHES_ALL)
private val CODE_BLOCK_REGEX = Regex("```[\\s\\S]*?```|`[^`\n]*`", RegexOption.DOT_MATCHES_ALL)
private val BREAK_LINE_REGEX = Regex("(?i)<br\\s*/?>")
private val BARE_WEB_URL_REGEX = Regex(
    """(?<![\w/@:\[(])((?:[A-Za-z0-9-]+\.)+(?:com|net|org|io|ai|cn|co|dev|app|me|info|xyz|news)(?:/[^\s<>()\[\]{}"']*)?)"""
)
private val TABLE_CELL_MARKDOWN_HINT_REGEX = Regex(
    """[`*_~\[\]()!#$<>\\]|https?://|(?:[A-Za-z0-9-]+\.)+(?:com|net|org|io|ai|cn|co|dev|app|me|info|xyz|news)|\n"""
)

// 预处理markdown内容
private fun preProcess(content: String): String {
    val displayContent = stripSearchImageFencesForDisplay(content)
    // TD.Rust.1b: native single-pass scan if available + flag on. Falls back
    // to the original Kotlin path on null return / not loaded / flag off.
    // The native flag is gated by NativePathPrefs.markdownHtml because
    // preprocess is logically a sub-step of the HTML pipeline.
    if (MarkdownNativeSwitch.isHtmlEnabled() &&
        MarkdownPreprocessNative.available
    ) {
        val nativeResult = MarkdownPreprocessNative.preprocess(displayContent)
        if (nativeResult != null) return nativeResult
    }

    // 先找出所有代码块的位置
    val codeBlocks = mutableListOf<IntRange>()
    CODE_BLOCK_REGEX.findAll(displayContent).forEach { match ->
        codeBlocks.add(match.range)
    }

    // 检查位置是否在代码块内
    fun isInCodeBlock(position: Int): Boolean {
        return codeBlocks.any { range -> position in range }
    }

    // 替换行内公式 \( ... \) 到 $ ... $，但跳过代码块内的内容
    var result = INLINE_LATEX_REGEX.replace(displayContent) { matchResult ->
        if (isInCodeBlock(matchResult.range.first)) {
            matchResult.value // 保持原样
        } else {
            "$" + matchResult.groupValues[1] + "$"
        }
    }

    // 替换块级公式 \[ ... \] 到 $$ ... $$，但跳过代码块内的内容
    result = BLOCK_LATEX_REGEX.replace(result) { matchResult ->
        if (isInCodeBlock(matchResult.range.first)) {
            matchResult.value // 保持原样
        } else {
            "$$" + matchResult.groupValues[1] + "$$"
        }
    }

    return linkifyBareUrlsOutsideCode(result)
}

private fun linkifyBareUrlsOutsideCode(content: String): String {
    val output = StringBuilder(content.length)
    var cursor = 0
    CODE_BLOCK_REGEX.findAll(content).forEach { match ->
        if (cursor < match.range.first) {
            output.append(linkifyBareUrlSegment(content.substring(cursor, match.range.first)))
        }
        output.append(match.value)
        cursor = match.range.last + 1
    }
    if (cursor < content.length) {
        output.append(linkifyBareUrlSegment(content.substring(cursor)))
    }
    return output.toString()
}

private fun linkifyBareUrlSegment(segment: String): String =
    BARE_WEB_URL_REGEX.replace(segment) { match ->
        val raw = match.value
        val url = raw.trimEnd('.', ',', ';', ':', '。', '，', '；', '：')
        val trailing = raw.removePrefix(url)
        "[$url](https://$url)$trailing"
    }

@Preview(showBackground = true)
@Composable
private fun MarkdownPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MarkdownBlock(
                content = "Hi there!", modifier = Modifier.background(Color.Red)
            )
            MarkdownBlock(
                content = """
                    ### 🌍 This is Markdown Test This Markdown Test
                    1. How many roads must a man walk down
                        * the slings and arrows of outrageous fortune, Or to take arms against a sea of troubles,
                        * by opposing end them.
                            * How many times must a man look up, Before he can see the sky?
                            * How many times $ f(x) = \sum_{n=0}^{\infty} \frac{f^{(n)}(a)}{n!}(x-a)^n$
                    2. How many times must a man look up, Before he can see the sky?

                    * [ ] Before they're allowed to be free? Yes, 'n' how many times can a man turn his head
                    * [x] Before they're allowed to be free? Yes, 'n' how many times can a man turn his head

                    4. For in that sleep of death what dreams may come [citation](1)

                    This is Markdown Test, This <br/> is Markdown Test.
                    ha<br/>ha

                    ***
                    This is Markdown Test, This is Markdown Test.

                    | Name | Age | Address | Email | Job | Homepage |
                    | ---- | --- | ------- | ----- | --- | -------- |
                    | John | 25  | New York | john@example.com | Software Engineer | john.com |
                    | Jane | 26  | London   | jane@example.com | Data Scientist | jane.com |

                    ## HTML Escaping
                    This is a &gt;  test

                """.trimIndent()
            )
        }
    }
}

internal data class MarkdownParseResult(
    val preprocessed: String,
    val tree: MdNode,
    val hasHtmlBlocks: Boolean,
    val stableTopLevelBlocks: List<MarkdownTopLevelBlockSnapshot> = emptyList(),
    val activeBaseOffset: Int = 0,
    val syntheticSuffixStart: Int = Int.MAX_VALUE,
)

internal data class MarkdownTopLevelBlockSnapshot(
    val key: String,
    val parseResult: MarkdownParseResult,
    val preserveParagraphBottomPadding: Boolean,
)

internal data class StreamingRepairResult(
    val text: String,
    val syntheticSuffixStart: Int,
)

internal data class StreamingLiveSuffix(
    val text: String,
    val sourceOffset: Int,
)

internal data class StreamingMarkdownMotionKey(
    val type: String,
    val absoluteStartOffset: Int,
)

internal class StreamingMarkdownMotionScope {
    private val animatedKeys = LinkedHashSet<StreamingMarkdownMotionKey>()

    fun claim(key: StreamingMarkdownMotionKey): Boolean {
        val added = animatedKeys.add(key)
        if (added) {
            StreamingRenderProbe.motionClaimCount = animatedKeys.size
        }
        return added
    }

    fun hasSeen(key: StreamingMarkdownMotionKey): Boolean {
        return key in animatedKeys
    }

    fun claimCount(): Int = animatedKeys.size

    fun clear() {
        animatedKeys.clear()
        StreamingRenderProbe.motionClaimCount = 0
    }
}

internal fun streamingMarkdownMotionKey(
    type: MdNodeType,
    sourceOffsetBase: Int,
    nodeStartOffset: Int,
): StreamingMarkdownMotionKey {
    return StreamingMarkdownMotionKey(
        type = type.toString(),
        absoluteStartOffset = sourceOffsetBase + nodeStartOffset,
    )
}

internal fun streamingTableCellMotionKey(
    tableKey: StreamingMarkdownMotionKey,
    rowIndex: Int,
    columnIndex: Int,
    header: Boolean,
): StreamingMarkdownMotionKey {
    val section = if (header) "header" else "row"
    return StreamingMarkdownMotionKey(
        type = "${tableKey.type}:$section:$rowIndex:$columnIndex",
        absoluteStartOffset = tableKey.absoluteStartOffset,
    )
}

private val FENCE_LINE_REGEX = Regex("""(?m)^[ \t]*```""")
private val TRAILING_FENCE_TERMINATOR_REGEX = Regex("""[ \t]*```[ \t]*""")
private val EMPTY_STREAMING_LIVE_SUFFIX = StreamingLiveSuffix("", 0)

private fun String.countNonOverlapping(token: String): Int {
    if (token.isEmpty()) return 0
    var count = 0
    var searchFrom = 0
    while (searchFrom < length) {
        val next = indexOf(token, startIndex = searchFrom)
        if (next < 0) break
        count++
        searchFrom = next + token.length
    }
    return count
}

internal fun repairStreamingMarkdownTail(tail: String): StreamingRepairResult {
    if (tail.isEmpty()) return StreamingRepairResult(tail, tail.length)
    val suffix = StringBuilder()
    val syntheticStart = tail.length

    if (FENCE_LINE_REGEX.findAll(tail).count() % 2 == 1) {
        if (!tail.endsWith('\n')) suffix.append('\n')
        suffix.append("```")
        return StreamingRepairResult(tail + suffix, syntheticStart)
    }

    if (tail.count { it == '`' } % 2 == 1) {
        suffix.append('`')
    }
    if (tail.countNonOverlapping("**") % 2 == 1) {
        suffix.append("**")
    }
    if (tail.countNonOverlapping("$$") % 2 == 1) {
        suffix.append("$$")
    } else if (tail.count { it == '$' } % 2 == 1) {
        suffix.append('$')
    }

    return StreamingRepairResult(tail + suffix, syntheticStart)
}

internal fun streamingLiveSuffixFor(
    renderContent: String,
    activeBaseOffset: Int,
    parsedPreprocessed: String,
    syntheticSuffixStart: Int,
    streaming: Boolean,
): StreamingLiveSuffix {
    if (!streaming) return EMPTY_STREAMING_LIVE_SUFFIX
    if (activeBaseOffset < 0 || activeBaseOffset > renderContent.length) {
        return EMPTY_STREAMING_LIVE_SUFFIX
    }
    val parsedSourceEnd = if (syntheticSuffixStart == Int.MAX_VALUE) {
        parsedPreprocessed.length
    } else {
        (syntheticSuffixStart - activeBaseOffset).coerceIn(0, parsedPreprocessed.length)
    }
    val liveActiveLength = renderContent.length - activeBaseOffset
    if (
        liveActiveLength <= parsedSourceEnd ||
        !renderContent.regionMatches(
            thisOffset = activeBaseOffset,
            other = parsedPreprocessed,
            otherOffset = 0,
            length = parsedSourceEnd,
            ignoreCase = false,
        )
    ) {
        return EMPTY_STREAMING_LIVE_SUFFIX
    }
    return StreamingLiveSuffix(
        text = renderContent.substring(activeBaseOffset + parsedSourceEnd),
        sourceOffset = activeBaseOffset + parsedSourceEnd,
    )
}

internal enum class LiveSuffixPlacement {
    /** Append to the active paragraph and batch-fade with the AST tail. */
    Inline,

    /** Show as plain text below the settled block; avoids block-boundary reflow. */
    Plain,
}

private val LIVE_SUFFIX_BLOCK_HEADING_REGEX = Regex("""^#{1,6}\s""")
private val LIVE_SUFFIX_BLOCK_LIST_REGEX = Regex("""^[-*+]\s""")
private val LIVE_SUFFIX_BLOCK_ORDERED_LIST_REGEX = Regex("""^\d+\.\s""")
private val LIVE_SUFFIX_BLOCK_QUOTE_REGEX = Regex("""^>\s?""")

internal fun liveSuffixPlacementFor(suffix: String): LiveSuffixPlacement {
    if (suffix.isEmpty()) return LiveSuffixPlacement.Inline
    if (suffix.startsWith("\n\n") || suffix.startsWith("\r\n\r\n")) {
        return LiveSuffixPlacement.Plain
    }
    val trimmedStart = suffix.trimStart()
    if (trimmedStart.isEmpty()) return LiveSuffixPlacement.Inline
    return when {
        LIVE_SUFFIX_BLOCK_HEADING_REGEX.containsMatchIn(trimmedStart) -> LiveSuffixPlacement.Plain
        LIVE_SUFFIX_BLOCK_LIST_REGEX.containsMatchIn(trimmedStart) -> LiveSuffixPlacement.Plain
        LIVE_SUFFIX_BLOCK_ORDERED_LIST_REGEX.containsMatchIn(trimmedStart) -> LiveSuffixPlacement.Plain
        trimmedStart.startsWith("|") -> LiveSuffixPlacement.Plain
        trimmedStart.startsWith("```") -> LiveSuffixPlacement.Plain
        LIVE_SUFFIX_BLOCK_QUOTE_REGEX.containsMatchIn(trimmedStart) -> LiveSuffixPlacement.Plain
        trimmedStart.matches(Regex("""^(-{3,}|\*{3,}|_{3,})\s*$""")) -> LiveSuffixPlacement.Plain
        else -> LiveSuffixPlacement.Inline
    }
}

/**
 * Splits an unparsed live suffix at the first top-level block boundary — a
 * blank line (`\n\n` or `\r\n\r\n`). The first component continues the current
 * block; the second (including the blank line) belongs to subsequent blocks.
 * With no blank line the whole suffix stays with the current block.
 *
 * Pure split — callers apply `<br>` normalization where the parts are rendered.
 */
internal fun splitLiveSuffixAtBlockBoundary(suffix: String): Pair<String, String> {
    val boundary = suffix.indexOf("\n\n").let { unix ->
        val windows = suffix.indexOf("\r\n\r\n")
        when {
            unix == -1 -> windows
            windows == -1 -> unix
            else -> minOf(unix, windows)
        }
    }
    return if (boundary == -1) {
        suffix to ""
    } else {
        suffix.substring(0, boundary) to suffix.substring(boundary)
    }
}

internal fun streamingLiveSuffixForLastRenderableChild(
    children: List<MdNode>,
    child: MdNode,
    liveSuffix: String,
    liveSuffixSourceOffset: Int,
): StreamingLiveSuffix {
    if (liveSuffix.isEmpty()) return EMPTY_STREAMING_LIVE_SUFFIX
    val target = children.asReversed().firstOrNull { it.isStreamingSuffixRenderableTarget() }
        ?: return EMPTY_STREAMING_LIVE_SUFFIX
    return if (child === target) {
        StreamingLiveSuffix(liveSuffix, liveSuffixSourceOffset)
    } else {
        EMPTY_STREAMING_LIVE_SUFFIX
    }
}

private fun MdNode.isStreamingSuffixRenderableTarget(): Boolean {
    // ATX_1..6 → Heading, CODE_FENCE+CODE_BLOCK → CodeBlock, ATX_CONTENT+PARAGRAPH →
    // Paragraph (mapping doc §2 #2/#3/#8/#9, §4 H1) collapse the JetBrains-side
    // duplicates onto the unified MdNodeType variants below.
    return when (type) {
        MdNodeType.Paragraph,
        MdNodeType.ListUnordered,
        MdNodeType.ListOrdered,
        MdNodeType.ListItem,
        MdNodeType.Blockquote,
        MdNodeType.Heading,
        MdNodeType.Image,
        MdNodeType.CodeBlock,
        MdNodeType.HtmlBlock,
        MdNodeType.Text,
        MdNodeType.HorizontalRule,
        MdNodeType.Table,
        MdNodeType.MathBlock,
        MdNodeType.TaskListMarker -> true

        else -> children.any { it.isStreamingSuffixRenderableTarget() }
    }
}

private const val MARKDOWN_PARSE_CACHE_VERSION = 1
private const val MARKDOWN_PARSE_CACHE_MAX_ENTRIES = 128
private const val MARKDOWN_PARSE_CACHE_MAX_CHARS = 1_200_000
private const val MARKDOWN_PERF_TAG = "AmberChatPerf"
private const val MARKDOWN_PARSE_HIT_LOG_MIN_CHARS = 600

/**
 * TD.Rust.1+ streaming parse throttle. Streaming tokens arrive every ~30-60ms
 * from the AI provider; a full markdown re-parse on each tick (typically
 * 5-30ms for long messages) burns CPU and contests the composition thread.
 * Sampling at 200ms cuts the parse rate from ~20/sec to 5/sec without making
 * the displayed text feel lagged — the streaming display buffer still
 * advances every frame; only the AST refresh slows.
 */
private const val MARKDOWN_STREAMING_PARSE_THROTTLE_MS = 200L

/**
 * Codex-style per-character reveal. Each suffix character runs its own
 * fade+lift curve anchored to the moment IT first became visible (see
 * [StreamingCharRevealClock]), instead of a curve shared by the whole parse
 * batch — batch anchoring made late-arriving characters start most of the way
 * through the shared curve and pop in nearly opaque.
 *
 * Absorption side: when a parse tick promotes suffix text into the settled
 * AnnotatedString, mid-fade characters would snap to full opacity. The settled
 * CATCHUP fade bridges that — the newly grown settled range continues from
 * [STREAMING_SETTLED_CATCHUP_START_ALPHA] to opaque, so the tick boundary is
 * invisible. Both mechanisms live entirely in L4.
 */
private const val STREAMING_CHAR_REVEAL_FADE_MS = 170
private const val STREAMING_CHAR_REVEAL_STAGGER_MS = 9
private const val STREAMING_CHAR_REVEAL_MAX_CASCADE_MS = 140
private const val STREAMING_CHAR_REVEAL_MAX_PENDING = 220
private const val STREAMING_CHAR_REVEAL_START_ALPHA = 0f
// Per-glyph baseline lift for the streaming tail. baselineShift only moves the
// glyph baseline, so it does NOT reflow the line or push the block below.
private const val STREAMING_CHAR_REVEAL_LIFT_EM = 0.28f
private const val STREAMING_SETTLED_CATCHUP_FADE_MS = 120
private const val STREAMING_SETTLED_CATCHUP_START_ALPHA = 0.72f
// Only the newest tail of an absorbed range was mid-fade; older characters
// were already opaque. Capping the ramp also bounds span count when a deferred
// parse resumes and absorbs thousands of characters at once.
private const val STREAMING_SETTLED_CATCHUP_MAX_CODEPOINTS = 32
private const val STREAMING_BLOCK_REVEAL_MS = 190
private const val STREAMING_BLOCK_REVEAL_START_ALPHA = 0.86f
// Whole-block translationY rise on first settle, complementing the per-glyph tail lift.
private const val STREAMING_BLOCK_REVEAL_OFFSET_DP = 9
private const val STREAMING_TABLE_CELL_REVEAL_MS = 150
private const val STREAMING_TABLE_CELL_REVEAL_START_ALPHA = 0.72f
private const val STREAMING_TABLE_CELL_REVEAL_MAX_CELLS = 48
private const val STREAMING_LIST_ITEM_REVEAL_MS = 130
private const val STREAMING_LIST_ITEM_STAGGER_LIMIT = 8
private const val STREAMING_LIST_ITEM_STAGGER_MS = 8

private data class MarkdownParseCacheKey(
    val content: String,
    val contentHash: Int,
    val version: Int,
    val preprocessed: Boolean,
)

private object MarkdownParseCache {
    private val lock = Any()
    private var totalChars = 0
    private var hits = 0
    private var misses = 0
    private val entries = object : LinkedHashMap<MarkdownParseCacheKey, MarkdownParseResult>(
        MARKDOWN_PARSE_CACHE_MAX_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MarkdownParseCacheKey, MarkdownParseResult>): Boolean {
            val shouldRemove = size > MARKDOWN_PARSE_CACHE_MAX_ENTRIES
            if (shouldRemove) {
                totalChars -= eldest.key.content.length
            }
            return shouldRemove
        }
    }

    fun get(content: String): MarkdownParseResult? = synchronized(lock) {
        entries[key(content, preprocessed = false)]?.also {
            hits++
            logCacheLocked("hit", content.length, elapsedMs = null)
        }
    }

    fun getOrParse(content: String): MarkdownParseResult {
        get(content)?.let { return it }
        return getOrParseInternal(
            content = content,
            preprocessed = false,
            section = "Amber Markdown parse",
            parser = ::parseMarkdownUncached,
        )
    }

    fun getOrParsePreprocessed(content: String): MarkdownParseResult {
        return getOrParseInternal(
            content = content,
            preprocessed = true,
            section = "Amber Markdown parse preprocessed",
            parser = ::parsePreprocessedMarkdownUncached,
        )
    }

    private fun getOrParseInternal(
        content: String,
        preprocessed: Boolean,
        section: String,
        parser: (String) -> MarkdownParseResult,
    ): MarkdownParseResult {
        synchronized(lock) {
            entries[key(content, preprocessed)]?.let {
                hits++
                logCacheLocked("hit", content.length, elapsedMs = null)
                return it
            }
            misses++
        }
        val startedAt = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        val parsed = traceMarkdown(section) {
            parser(content)
        }
        val elapsedMs = if (BuildConfig.DEBUG) {
            (System.nanoTime() - startedAt) / 1_000_000.0
        } else {
            null
        }
        synchronized(lock) {
            val cacheKey = key(content, preprocessed)
            entries[cacheKey]?.let {
                hits++
                logCacheLocked("hit-after-parse", content.length, elapsedMs)
                return it
            }
            if (content.length > MARKDOWN_PARSE_CACHE_MAX_CHARS) {
                logCacheLocked("oversize-skip", content.length, elapsedMs)
                return parsed
            }
            entries[cacheKey] = parsed
            totalChars += content.length
            trimToBudgetLocked()
            logCacheLocked("miss", content.length, elapsedMs)
        }
        return parsed
    }

    private fun logCacheLocked(
        event: String,
        contentLength: Int,
        elapsedMs: Double?,
    ) {
        if (!BuildConfig.DEBUG || !runCatching { Log.isLoggable(MARKDOWN_PERF_TAG, Log.DEBUG) }.getOrDefault(false)) return
        if (event == "hit" && contentLength < MARKDOWN_PARSE_HIT_LOG_MIN_CHARS) return
        Log.d(
            MARKDOWN_PERF_TAG,
            buildString {
                append("markdownParse event=")
                append(event)
                append(" chars=")
                append(contentLength)
                append(" entries=")
                append(entries.size)
                append(" totalChars=")
                append(totalChars)
                append(" hits=")
                append(hits)
                append(" misses=")
                append(misses)
                elapsedMs?.let {
                    append(" elapsedMs=")
                    append(String.format(java.util.Locale.US, "%.2f", it))
                }
            }
        )
    }

    private fun trimToBudgetLocked() {
        while (
            entries.size > MARKDOWN_PARSE_CACHE_MAX_ENTRIES ||
            totalChars > MARKDOWN_PARSE_CACHE_MAX_CHARS
        ) {
            val iterator = entries.entries.iterator()
            if (!iterator.hasNext()) break
            val eldest = iterator.next()
            totalChars -= eldest.key.content.length
            iterator.remove()
        }
    }

    private fun key(content: String, preprocessed: Boolean) = MarkdownParseCacheKey(
        content = content,
        contentHash = content.hashCode(),
        version = MARKDOWN_PARSE_CACHE_VERSION,
        preprocessed = preprocessed,
    )
}

private inline fun <T> traceMarkdown(section: String, block: () -> T): T {
    val tracing = BuildConfig.DEBUG && runCatching {
        Trace.beginSection(section)
        true
    }.getOrDefault(false)
    return try {
        block()
    } finally {
        if (tracing) {
            runCatching { Trace.endSection() }
        }
    }
}

@Composable
private fun TraceMarkdownComposable(section: String, content: @Composable () -> Unit) {
    if (BuildConfig.DEBUG) {
        Trace.beginSection(section)
    }
    content()
    if (BuildConfig.DEBUG) {
        Trace.endSection()
    }
}

private fun MdNode.containsHtmlBlocks(): Boolean {
    if (type == MdNodeType.HtmlBlock) return true
    return children.any { it.containsHtmlBlocks() }
}

private fun parsePreprocessedMarkdownUncached(preprocessed: String): MarkdownParseResult {
    // T-B perf-layer dispatch — flag-gated Rust-renderer activation
    // (`PerfFlags.USE_RUST_MARKDOWN_RENDERER`). Default `false` so the
    // JVM JetBrains-AST path runs unchanged. When enabled, the native
    // parse is invoked first and a successful blob is decoded eagerly
    // to PackedAstReader; we still produce a JVM ASTNode and return it
    // because the renderer body consumes ASTNode (the full renderer
    // ASTNode→PackedAstNode swap is the dedicated sprint in
    // docs/td-rust-1a-feasibility.md).
    //
    // What the flag actually buys you today: the native path's
    // correctness signal is upgraded from sample-rate shadow to
    // every-parse hard-validation, so any divergence in a single
    // user-visible render trips immediately rather than statistically.
    if (app.amber.agent.PerfFlags.USE_RUST_MARKDOWN_RENDERER) {
        val nativeOk = runCatching {
            val blob = MarkdownNativeSwitch.parseAstOrNull(preprocessed)
            if (blob != null) {
                PackedAstReader(blob).root() != null
            } else false
        }.getOrDefault(false)
        if (!nativeOk) {
            android.util.Log.w(
                "Markdown",
                "RUST_MARKDOWN_RENDERER flag on but native parse returned " +
                    "null/error for ${preprocessed.length} chars; JVM fallback continues.",
            )
        }
    }

    // TD.Rust.1a Task 13 — parse-funnel switch. When the `markdownAst` flag is on
    // (same flag the retired shadow-compare read: MarkdownNativeSwitch.isAstEnabled()),
    // the renderer consumes the Rust-parsed packed AST via NativeMdNode instead of the
    // JetBrains tree. Both feed the same parser-agnostic MdNode interface.
    //
    // [X] divergence (Part C / controller-accepted): the native path renders GFM-correct
    // case-INSENSITIVE task checkboxes (`[X]` → checked, matching pulldown-cmark), whereas
    // the JVM fallback below keeps the legacy case-SENSITIVE behavior (`[X]` → unchecked).
    // Same input, opposite checkbox state — see NativeMdTree.taskChecked KDoc for the full
    // rationale and the paired tree tests pinning both sides.
    if (MarkdownNativeSwitch.isAstEnabled()) {
        val blob = MarkdownParserNative.parse(preprocessed)
        if (blob != null) {
            val reader = PackedAstReader(blob)
            if (reader.isValid && reader.validate()) {
                nativeMdTreeOrNull(reader, preprocessed)?.let { root ->
                    // hasHtmlBlocks comes from the blob header flag (bit 0). The Rust builder
                    // sets it exactly when it emits a block-level HtmlBlock node (Event::Html),
                    // deliberately excluding inline HTML — equivalent to the JVM
                    // containsHtmlBlocks() which only recurses for MdNodeType.HtmlBlock
                    // (JetBrains HTML_BLOCK). Verified parity (tree_builder.rs / packed_ast.rs).
                    return MarkdownParseResult(preprocessed, root, reader.hasHtmlBlocks)
                }
            }
            // Blob present but rejected (invalid header / failed bounds-walk / null root):
            // report through the SAME pipeline the retired shadow-compare used, then fall
            // through to the JVM path so the user still sees rendered markdown.
            MarkdownNativeSwitch.reportAstBlobRejected()
        }
        // blob == null means the .so is missing or the bridge errored mid-flight — the
        // bridge already logged/instrumented that (no extra report here); fall through.
    }

    // ── Unchanged JVM (JetBrains) path ──────────────────────────────────────────────────
    val astTree = parser.buildMarkdownTreeFromString(preprocessed)
    // Rule 1: the ONLY place a JetBrains type crosses into the MdNode model.
    // Everything downstream consumes the parser-agnostic interface.
    val tree = JvmMdNode(astTree, preprocessed, null)
    return MarkdownParseResult(preprocessed, tree, tree.containsHtmlBlocks())
}

private fun parseMarkdownUncached(content: String): MarkdownParseResult {
    return parsePreprocessedMarkdownUncached(preProcess(content))
}

internal class StreamingMarkdownParseCache {
    private val lock = Any()
    private var stableRawPrefix = ""
    private var stableBlocks = emptyList<MarkdownTopLevelBlockSnapshot>()

    fun reset() = synchronized(lock) {
        stableRawPrefix = ""
        stableBlocks = emptyList()
    }

    private fun parseRepairedTail(
        tail: String,
        activeBaseOffset: Int,
        blocks: List<MarkdownTopLevelBlockSnapshot>,
    ): MarkdownParseResult {
        val repairedTail = repairStreamingMarkdownTail(tail)
        return parsePreprocessedMarkdownUncached(repairedTail.text)
            .copy(
                stableTopLevelBlocks = blocks,
                activeBaseOffset = activeBaseOffset,
                syntheticSuffixStart = activeBaseOffset + repairedTail.syntheticSuffixStart,
            )
    }

    fun parse(
        content: String,
    ): MarkdownParseResult = traceMarkdown("Amber Markdown parse streaming") {
        val baseline = synchronized(lock) {
            if (stableRawPrefix.isNotEmpty() && content.startsWith(stableRawPrefix)) {
                stableRawPrefix to stableBlocks
            } else {
                stableRawPrefix = ""
                stableBlocks = emptyList()
                "" to emptyList()
            }
        }

        val (prefix, blocks) = baseline
        val activeRaw = content.substring(prefix.length)
        val activePreprocessed = preProcess(activeRaw)
        if (activePreprocessed != activeRaw) {
            reset()
            return@traceMarkdown parseMarkdownUncached(content)
        }
        val activeParse = parsePreprocessedMarkdownUncached(activePreprocessed)
        if (activeParse.hasHtmlBlocks) {
            reset()
            return@traceMarkdown parseMarkdownUncached(content)
        }

        val activeChildren = activeParse.tree.children
        if (activeChildren.size <= 1) {
            return@traceMarkdown parseRepairedTail(
                tail = activePreprocessed,
                activeBaseOffset = prefix.length,
                blocks = blocks,
            )
        }

        // Structural stabilization: every top-level block except the last
        // (still-growing) one is finalized. No longer gated on reveal
        // progress — the batch fade is a purely visual layer.
        val newlyStableChildren = activeChildren.dropLast(1)

        val nextStableBlocks = blocks + newlyStableChildren.map { child ->
            val blockContent = activePreprocessed.substring(child.startOffset, child.endOffset)
            MarkdownTopLevelBlockSnapshot(
                key = "${child.type}:${prefix.length + child.startOffset}:${blockContent.length}:${blockContent.hashCode()}",
                parseResult = MarkdownParseCache.getOrParsePreprocessed(blockContent),
                preserveParagraphBottomPadding = child.type == MdNodeType.Paragraph &&
                    child.nextSibling() != null &&
                    child.findChildOfTypeRecursive(MdNodeType.Image, MdNodeType.MathBlock) == null,
            )
        }
        val activeTailStart = newlyStableChildren.last().endOffset
        val nextPrefixEnd = prefix.length + activeTailStart
        synchronized(lock) {
            stableRawPrefix = content.substring(0, nextPrefixEnd)
            stableBlocks = nextStableBlocks
        }
        val tail = activePreprocessed.substring(activeTailStart)
        parseRepairedTail(
            tail = tail,
            activeBaseOffset = nextPrefixEnd,
            blocks = nextStableBlocks,
        )
    }
}

internal fun prewarmMarkdownContent(content: String) {
    if (content.isNotBlank()) {
        MarkdownParseCache.getOrParse(content)
    }
}

internal fun cachedMarkdownParseResult(content: String): MarkdownParseResult? {
    return MarkdownParseCache.get(content)
}

internal fun parseMarkdownContent(content: String): MarkdownParseResult {
    return MarkdownParseCache.getOrParse(content)
}

internal fun MarkdownParseResult.canRenderByTopLevelBlocks(): Boolean {
    return !hasHtmlBlocks && tree.children.size > 1
}

internal fun MarkdownParseResult.topLevelBlockCount(): Int {
    return tree.children.size
}

internal fun MarkdownParseResult.topLevelBlockKey(index: Int): String {
    val child = tree.children.getOrNull(index) ?: return "missing-$index"
    return "${child.type}:${child.startOffset}:${child.endOffset}"
}

@Composable
internal fun MarkdownTopLevelBlock(
    data: MarkdownParseResult,
    blockIndex: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    onClickCitation: (String) -> Unit = {}
) {
    val child = data.tree.children.getOrNull(blockIndex) ?: return
    ProvideTextStyle(style) {
        Column(
            modifier = modifier
                .padding(start = 4.dp)
                .amberTraceMeasure("Amber MarkdownBlock child measure")
        ) {
            MarkdownNode(
                node = child,
                content = data.preprocessed,
                onClickCitation = onClickCitation,
            )
        }
    }
}

@Composable
fun MarkdownBlock(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    fillWidth: Boolean = true,
    /**
     * Streaming still uses the incremental/background parse cache. When true,
     * only the trailing active block gets a [StreamingTailActive] marker so
     * newly arrived text can fade in while finalized blocks stay on the fast path.
     */
    streaming: Boolean = false,
    deferStreamingParse: Boolean = false,
    onStreamingVisibleFrame: (() -> Unit)? = null,
    onClickCitation: (String) -> Unit = {}
) {
    // T-C perf-layer dispatch — see PerfFlags + docs/visual-sanity-check.md.
    if (app.amber.agent.PerfFlags.USE_SPLIT_MARKDOWN) {
        MarkdownBlockSplit(
            content = content,
            modifier = modifier,
            style = style,
            fillWidth = fillWidth,
            streaming = streaming,
            deferStreamingParse = deferStreamingParse,
            onStreamingVisibleFrame = onStreamingVisibleFrame,
            onClickCitation = onClickCitation,
        )
        return
    }
    val bufferStreaming = streaming && !content.shouldBypassStreamingDisplayBuffer()
    val renderContent = rememberStreamingDisplayText(
        content = content,
        streaming = bufferStreaming,
        onVisibleFrame = onStreamingVisibleFrame,
    )
    val displayDrainingAfterStream = !streaming &&
        renderContent != content &&
        content.startsWith(renderContent)
    val streamingParseCache = remember {
        StreamingMarkdownParseCache()
    }
    var (data, setData) = remember {
        mutableStateOf(
            if (streaming) {
                streamingParseCache.parse(renderContent)
            } else {
                streamingParseCache.reset()
                MarkdownParseCache.getOrParse(renderContent)
            }
        )
    }

    // 监听内容变化，重新解析AST树
    // 这里在后台线程解析AST树, 防止频繁更新的时候掉帧
    //
    // TD.Rust.1+ streaming parse throttle (review #8): on streaming content,
    // sample the flow at MARKDOWN_STREAMING_PARSE_THROTTLE_MS so that bursts
    // of token arrivals only trigger a re-parse every ~200ms. The visible
    // text already updates continuously via rememberStreamingDisplayText
    // (renderContent vs raw content); the AST re-parse is the expensive
    // step we're cutting.
    val updatedContent by rememberUpdatedState(renderContent)
    val updatedStreaming by rememberUpdatedState(streaming)
    val updatedDraining by rememberUpdatedState(displayDrainingAfterStream)
    val updatedDeferStreamingParse by rememberUpdatedState(deferStreamingParse)
    val streamingTailActive = streamingTailActiveWhen(
        streaming = streaming || displayDrainingAfterStream,
    )
    val streamingMotionActive = streaming || displayDrainingAfterStream
    val streamingMotionScope = remember {
        StreamingMarkdownMotionScope()
    }
    val lastMotionContent = remember {
        mutableStateOf(renderContent)
    }
    LaunchedEffect(streamingMotionActive, renderContent) {
        if (!streamingMotionActive) {
            streamingMotionScope.clear()
            lastMotionContent.value = renderContent
            return@LaunchedEffect
        }
        val previousContent = lastMotionContent.value
        if (
            renderContent.length < previousContent.length ||
            !renderContent.startsWith(previousContent)
        ) {
            streamingMotionScope.clear()
        }
        lastMotionContent.value = renderContent
    }
    val streamingLiveSuffix = streamingLiveSuffixFor(
        renderContent = renderContent,
        activeBaseOffset = data.activeBaseOffset,
        parsedPreprocessed = data.preprocessed,
        syntheticSuffixStart = data.syntheticSuffixStart,
        streaming = streaming,
    )
    SideEffect {
        StreamingRenderProbe.liveSuffixLength = streamingLiveSuffix.text.length
        if (!streaming && !displayDrainingAfterStream) {
            StreamingRenderProbe.resetStreamingDiagnostics()
        }
    }
    LaunchedEffect(Unit) {
        var lastParsedContent = renderContent
        var lastParsedStreaming = streaming
        var lastDeferred = deferStreamingParse
        @OptIn(FlowPreview::class)
        snapshotFlow {
            Triple(
                updatedContent,
                updatedStreaming || updatedDraining,
                updatedDeferStreamingParse,
            )
        }
            .distinctUntilChanged()
            .flatMapLatest { triple ->
                if (triple.second) {
                    flowOf(triple).sample(MARKDOWN_STREAMING_PARSE_THROTTLE_MS)
                } else {
                    flowOf(triple)
                }
            }
            .collectLatest { (latestContent, parseAsStreaming, latestDeferStreamingParse) ->
                if (
                    latestContent == lastParsedContent &&
                    parseAsStreaming == lastParsedStreaming &&
                    latestDeferStreamingParse == lastDeferred
                ) {
                    return@collectLatest
                }
                if (parseAsStreaming && latestDeferStreamingParse) {
                    lastDeferred = latestDeferStreamingParse
                    StreamingRenderProbe.record {
                        "parse_deferred len=${latestContent.length}"
                    }
                    return@collectLatest
                }
                try {
                    val parsed = withContext(Dispatchers.Default) {
                        if (parseAsStreaming) {
                            streamingParseCache.parse(content = latestContent)
                        } else {
                            streamingParseCache.reset()
                            MarkdownParseCache.getOrParse(latestContent)
                        }
                    }
                    lastParsedContent = latestContent
                    lastParsedStreaming = parseAsStreaming
                    lastDeferred = latestDeferStreamingParse
                    setData(parsed)
                    if (parseAsStreaming) {
                        StreamingRenderProbe.parseTickCount++
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    exception.printStackTrace()
                }
            }
    }

    TraceMarkdownComposable("Amber MarkdownBlock render") {
      CompositionLocalProvider(
          LocalMarkdownFillWidth provides fillWidth,
      ) {
        if (data.hasHtmlBlocks) {
            MarkdownNew(
                content = renderContent,
                modifier = modifier.amberTraceMeasure("Amber MarkdownBlock html measure"),
                style = style,
                onClickCitation = onClickCitation,
            )
        } else {
            ProvideTextStyle(style) {
                Column(
                    modifier = modifier
                        .padding(start = 4.dp)
                        .amberTraceMeasure("Amber MarkdownBlock measure")
                ) {
                    val nodeModifier = Modifier.fillWidthIf(LocalMarkdownFillWidth.current)
                    val children = data.tree.children
                    if (data.stableTopLevelBlocks.isNotEmpty()) {
                        data.stableTopLevelBlocks.fastForEach { block ->
                            key(block.key) {
                                CompositionLocalProvider(
                                    LocalStreamingTailActive provides null,
                                    LocalStreamingMarkdownMotionScope provides null,
                                ) {
                                    val blockModifier = if (block.preserveParagraphBottomPadding) {
                                        nodeModifier.padding(bottom = LocalTextStyle.current.fontSize.toDp())
                                    } else {
                                        nodeModifier
                                    }
                                    block.parseResult.tree.children.fastForEach { child ->
                                        MarkdownNode(
                                            node = child,
                                            content = block.parseResult.preprocessed,
                                            modifier = blockModifier,
                                            onClickCitation = onClickCitation,
                                        )
                                    }
                                }
                            }
                        }
                        val activeLastIndex = children.lastIndex
                        children.fastForEachIndexed { index, activeChild ->
                            key("active:${activeChild.type}:${data.activeBaseOffset + activeChild.startOffset}:$index") {
                                val childLiveSuffix = if (index == activeLastIndex) {
                                    streamingLiveSuffix
                                } else {
                                    EMPTY_STREAMING_LIVE_SUFFIX
                                }
                                CompositionLocalProvider(
                                    LocalStreamingTailActive provides streamingTailActive,
                                    LocalMarkdownSourceOffsetBase provides data.activeBaseOffset,
                                    LocalMarkdownSyntheticSuffixStart provides data.syntheticSuffixStart,
                                    LocalStreamingMarkdownMotionScope provides if (index == activeLastIndex) {
                                        streamingMotionScope
                                    } else {
                                        null
                                    },
                                ) {
                                    MarkdownNode(
                                        node = activeChild,
                                        content = data.preprocessed,
                                        modifier = nodeModifier,
                                        onClickCitation = onClickCitation,
                                        liveSuffix = childLiveSuffix.text,
                                        liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                                    )
                                }
                            }
                        }
                    } else {
                        val activeLastIndex = children.lastIndex
                        children.fastForEachIndexed { index, child ->
                            val childStreamingTailActive = if (index == children.lastIndex) {
                                streamingTailActive
                            } else {
                                null
                            }
                            val childLiveSuffix = if (index == activeLastIndex) {
                                streamingLiveSuffix
                            } else {
                                EMPTY_STREAMING_LIVE_SUFFIX
                            }
                            CompositionLocalProvider(
                                LocalStreamingTailActive provides childStreamingTailActive,
                                LocalMarkdownSourceOffsetBase provides data.activeBaseOffset,
                                LocalMarkdownSyntheticSuffixStart provides data.syntheticSuffixStart,
                                LocalStreamingMarkdownMotionScope provides if (childStreamingTailActive != null) {
                                    streamingMotionScope
                                } else {
                                    null
                                },
                            ) {
                                MarkdownNode(
                                    node = child,
                                    content = data.preprocessed,
                                    modifier = nodeModifier,
                                    onClickCitation = onClickCitation,
                                    liveSuffix = childLiveSuffix.text,
                                    liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                                )
                            }
                        }
                    }
                }
            }
        }
      }
    }
}

private fun String.shouldBypassStreamingDisplayBuffer(): Boolean {
    val lower = lowercase()
    return lower.contains("```html") ||
        lower.contains("<html") ||
        lower.contains("<body") ||
        lower.contains("<script") ||
        lower.contains("<style") ||
        lower.contains("<svg")
}

// for debug
private fun dumpAst(node: MdNode, text: String, indent: String = "") {
    println("$indent${node.type} ${if (node.children.isEmpty()) node.textIn(text) else ""}")
    node.children.fastForEach {
        dumpAst(it, text, "$indent  ")
    }
}

object HeaderStyle {
    val H1 = TextStyle(
        fontStyle = FontStyle.Normal, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp
    )

    val H2 = TextStyle(
        fontStyle = FontStyle.Normal, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 30.sp
    )

    val H3 = TextStyle(
        fontStyle = FontStyle.Normal, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp
    )

    val H4 = TextStyle(
        fontStyle = FontStyle.Normal, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 26.sp
    )

    val H5 = TextStyle(
        fontStyle = FontStyle.Normal, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 24.sp
    )

    val H6 = TextStyle(
        fontStyle = FontStyle.Normal, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 22.sp
    )
}

@Composable
private fun StreamingBlockReveal(
    node: MdNode,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    delayMillis: Int = 0,
    durationMillis: Int = STREAMING_BLOCK_REVEAL_MS,
    startAlpha: Float = STREAMING_BLOCK_REVEAL_START_ALPHA,
    offsetYDp: Int = STREAMING_BLOCK_REVEAL_OFFSET_DP,
    content: @Composable (Modifier) -> Unit,
) {
    val sourceOffsetBase = LocalMarkdownSourceOffsetBase.current
    val key = remember(node.type, sourceOffsetBase, node.startOffset) {
        streamingMarkdownMotionKey(
            type = node.type,
            sourceOffsetBase = sourceOffsetBase,
            nodeStartOffset = node.startOffset,
        )
    }
    content(
        modifier.then(
            streamingRevealModifier(
                key = key,
                enabled = enabled,
                delayMillis = delayMillis,
                durationMillis = durationMillis,
                startAlpha = startAlpha,
                offsetYDp = offsetYDp,
            )
        )
    )
}

@Composable
private fun streamingRevealModifier(
    key: StreamingMarkdownMotionKey,
    enabled: Boolean = true,
    delayMillis: Int = 0,
    durationMillis: Int = STREAMING_BLOCK_REVEAL_MS,
    startAlpha: Float = STREAMING_BLOCK_REVEAL_START_ALPHA,
    offsetYDp: Int = STREAMING_BLOCK_REVEAL_OFFSET_DP,
): Modifier {
    val scope = LocalStreamingMarkdownMotionScope.current
    val revealActive = enabled && LocalStreamingTailActive.current != null
    val shouldAnimate = remember(key) {
        mutableStateOf(false)
    }
    LaunchedEffect(scope, revealActive, key) {
        // Once claimed, never un-claim: revealActive drops the moment
        // generation ends, and resetting here snapped a mid-flight block rise
        // to its final position — the message's final block settles right at
        // the end, so its rise was almost always cut. A finished animation is
        // a no-op graphicsLayer, so letting it complete costs nothing.
        if (shouldAnimate.value) return@LaunchedEffect
        val motionScope = scope
        shouldAnimate.value = revealActive && motionScope != null && motionScope.claim(key)
    }
    if (!shouldAnimate.value) return Modifier

    val anim = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        anim.animateTo(
            1f,
            animationSpec = tween(
                durationMillis = durationMillis,
                delayMillis = delayMillis,
                easing = LinearEasing,
            ),
        )
    }
    val offsetPx = with(LocalDensity.current) { offsetYDp.dp.toPx() }
    return Modifier.graphicsLayer {
        val eased = codexStreamingAlphaProgress(anim.value)
        alpha = startAlpha + (1f - startAlpha) * eased
        translationY = offsetPx * (1f - eased)
    }
}

@Composable
private fun MarkdownNode(
    node: MdNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    listLevel: Int = 0,
    liveSuffix: String = "",
    liveSuffixSourceOffset: Int = 0,
) {
    when (node.type) {
        // 文件根节点
        MdNodeType.Root -> {
            node.children.fastForEach { child ->
                val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                    children = node.children,
                    child = child,
                    liveSuffix = liveSuffix,
                    liveSuffixSourceOffset = liveSuffixSourceOffset,
                )
                MarkdownNode(
                    node = child,
                    content = content,
                    modifier = modifier,
                    onClickCitation = onClickCitation,
                    liveSuffix = childLiveSuffix.text,
                    liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                )
            }
        }

        // 段落
        MdNodeType.Paragraph -> {
            Paragraph(
                node = node,
                content = content,
                modifier = modifier,
                onClickCitation = onClickCitation,
                liveSuffix = liveSuffix,
                liveSuffixSourceOffset = liveSuffixSourceOffset,
            )
        }

        // 标题
        MdNodeType.Heading -> {
            // ATX_1..ATX_6 collapse to one Heading variant; level via headingLevel
            // accessor (mapping doc §3 E1, replaces the per-type when over ATX_n).
            val style = when (node.headingLevel) {
                1 -> HeaderStyle.H1
                2 -> HeaderStyle.H2
                3 -> HeaderStyle.H3
                4 -> HeaderStyle.H4
                5 -> HeaderStyle.H5
                6 -> HeaderStyle.H6
                else -> throw IllegalArgumentException("Unknown header type")
            }
            val headingPadding = when (node.headingLevel) {
                1 -> 16.dp
                2 -> 14.dp
                3 -> 12.dp
                4 -> 10.dp
                5 -> 8.dp
                6 -> 6.dp
                else -> 8.dp
            }
            // Capture the body style BEFORE switching to the heading style, so
            // the live-suffix preview of the paragraph that follows the heading
            // renders as body text rather than leaking the heading's large bold.
            val bodyStyle = LocalTextStyle.current
            ProvideTextStyle(value = style) {
                StreamingBlockReveal(node = node, modifier = modifier.fillWidthIf(LocalMarkdownFillWidth.current)) { revealModifier ->
                    FlowRow(
                        modifier = revealModifier,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        node.children.fastForEach { child ->
                            // ATX_CONTENT (heading inline-content wrapper) maps to
                            // MdNodeType.Paragraph (mapping doc §4 H1); render that child only.
                            if (child.type == MdNodeType.Paragraph) {
                                val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                                    children = node.children,
                                    child = child,
                                    liveSuffix = liveSuffix,
                                    liveSuffixSourceOffset = liveSuffixSourceOffset,
                                )
                                Paragraph(
                                    node = child,
                                    content = content,
                                    onClickCitation = onClickCitation,
                                    modifier = Modifier.padding(vertical = headingPadding),
                                    trim = true,
                                    plainSuffixStyle = bodyStyle,
                                    liveSuffix = childLiveSuffix.text,
                                    liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 列表
        MdNodeType.ListUnordered -> {
            UnorderedListNode(
                node = node,
                content = content,
                modifier = modifier.padding(vertical = 4.dp),
                onClickCitation = onClickCitation,
                level = listLevel,
                liveSuffix = liveSuffix,
                liveSuffixSourceOffset = liveSuffixSourceOffset,
            )
        }

        MdNodeType.ListOrdered -> {
            OrderedListNode(
                node = node,
                content = content,
                modifier = modifier.padding(vertical = 4.dp),
                onClickCitation = onClickCitation,
                level = listLevel,
                liveSuffix = liveSuffix,
                liveSuffixSourceOffset = liveSuffixSourceOffset,
            )
        }

        // Checkbox
        MdNodeType.TaskListMarker -> {
            // taskChecked accessor relocates the `[x]` text-slice (mapping doc §3 E6 / §4 H2).
            val isChecked = node.taskChecked == true
            Surface(
                shape = RoundedCornerShape(2.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                modifier = modifier,
            ) {
                Box(
                    modifier = Modifier
                        .padding(2.dp)
                        .size(LocalTextStyle.current.fontSize.toDp() * 0.8f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isChecked) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // 引用块
        MdNodeType.Blockquote -> {
            ProvideTextStyle(LocalTextStyle.current.copy(fontStyle = FontStyle.Italic)) {
                // Bug fix: 之前用 drawWithContent + drawRect 把 bg 画在 content 之上 (蒙层覆盖
                // 文字), 深色下文字几乎看不见. 改 drawBehind 让 bg 画在 content 之后.
                // 同时降低 bgColor alpha (0.2 → 0.12), 在深色下也清爽.
                val borderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                val bgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f)
                StreamingBlockReveal(node = node, modifier = modifier) { revealModifier ->
                    Column(
                        modifier = revealModifier
                            .drawBehind {
                                drawRect(
                                    color = bgColor, size = size
                                )
                                drawRect(
                                    color = borderColor, size = Size(10f, size.height)
                                )
                            }
                            .padding(8.dp)
                    ) {
                        node.children.fastForEach { child ->
                            val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                                children = node.children,
                                child = child,
                                liveSuffix = liveSuffix,
                                liveSuffixSourceOffset = liveSuffixSourceOffset,
                            )
                            MarkdownNode(
                                node = child,
                                content = content,
                                onClickCitation = onClickCitation,
                                liveSuffix = childLiveSuffix.text,
                                liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                            )
                        }
                    }
                }
            }
        }

        // 链接 — only the INLINE_LINK form had a block-level arm originally. GFM_AUTOLINK (leaf)
        // and AUTOLINK (<url>) collapse to Link too, but the original `when` had no arm for them,
        // so they fell to `else` (recurse). Reproduce: handle only non-leaf, non-autolink Links
        // here; route the rest to the same child-recursion the `else` branch performs.
        MdNodeType.Link -> {
            if (node.isAutolink || node.children.isEmpty()) {
                node.children.fastForEach { child ->
                    val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                        children = node.children,
                        child = child,
                        liveSuffix = liveSuffix,
                        liveSuffixSourceOffset = liveSuffixSourceOffset,
                    )
                    MarkdownNode(
                        node = child,
                        content = content,
                        modifier = modifier,
                        onClickCitation = onClickCitation,
                        liveSuffix = childLiveSuffix.text,
                        liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                    )
                }
            } else {
                // INLINE_LINK. mapping doc §4 H6/KU-1. linkInnerText relocates the
                // LINK_TEXT→GFM_AUTOLINK/TEXT dig (1600-1601); linkHref relocates LINK_DESTINATION (E5).
                val linkText = node.linkInnerText ?: ""
                val linkDest = node.linkHref ?: ""
                val context = LocalContext.current
                Text(
                    text = linkText,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = modifier.clickable {
                        context.openUrl(linkDest)
                    })
            }
        }

        // 加粗和斜体
        MdNodeType.Emphasis -> {
            ProvideTextStyle(TextStyle(fontStyle = FontStyle.Italic)) {
                node.children.fastForEach { child ->
                    val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                        children = node.children,
                        child = child,
                        liveSuffix = liveSuffix,
                        liveSuffixSourceOffset = liveSuffixSourceOffset,
                    )
                    MarkdownNode(
                        node = child,
                        content = content,
                        modifier = modifier,
                        onClickCitation = onClickCitation,
                        liveSuffix = childLiveSuffix.text,
                        liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                    )
                }
            }
        }

        MdNodeType.Strong -> {
            ProvideTextStyle(TextStyle(fontWeight = FontWeight.SemiBold)) {
                node.children.fastForEach { child ->
                    val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                        children = node.children,
                        child = child,
                        liveSuffix = liveSuffix,
                        liveSuffixSourceOffset = liveSuffixSourceOffset,
                    )
                    MarkdownNode(
                        node = child,
                        content = content,
                        modifier = modifier,
                        onClickCitation = onClickCitation,
                        liveSuffix = childLiveSuffix.text,
                        liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                    )
                }
            }
        }

        // GFM 特殊元素
        MdNodeType.Strikethrough -> {
            Text(
                text = node.textIn(content), textDecoration = TextDecoration.LineThrough, modifier = modifier
            )
        }

        MdNodeType.Table -> {
            TableNode(
                node = node,
                content = content,
                modifier = modifier,
                liveSuffix = liveSuffix,
                liveSuffixSourceOffset = liveSuffixSourceOffset,
            )
        }

        MdNodeType.HorizontalRule -> {
            StreamingBlockReveal(node = node) { revealModifier ->
                HorizontalDivider(
                    modifier = revealModifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    thickness = 0.5.dp
                )
            }
        }

        // 图片
        MdNodeType.Image -> {
            // alt = LINK_TEXT text (untrimmed, mapping doc §3 E4 — linkLabel relocates the
            // findChildOfTypeRecursive(LINK_TEXT) dig); src via imageSrc accessor.
            val altText = node.linkLabel ?: ""
            val imageUrl = node.imageSrc ?: ""
            if (LocalSearchImageUrls.current?.contains(imageUrl) == true) return
            StreamingBlockReveal(node = node, modifier = modifier) { revealModifier ->
                Column(
                    modifier = revealModifier, horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 这里可以使用Coil等图片加载库加载图片
                    ZoomableAsyncImage(
                        model = imageUrl,
                        contentDescription = altText,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .widthIn(min = 120.dp, max = 360.dp)
                            .heightIn(min = 120.dp, max = 420.dp),
                    )
                }
            }
        }

        MdNodeType.MathInline -> {
            val formula = node.textIn(content)
            val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
            if (enableLatexRendering) {
                MathInline(
                    formula, modifier = modifier.padding(horizontal = 1.dp)
                )
            } else {
                Text(
                    text = formula,
                    fontFamily = FontFamily.Monospace,
                    modifier = modifier.padding(horizontal = 1.dp)
                )
            }
        }

        MdNodeType.MathBlock -> {
            val formula = node.textIn(content)
            val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
            StreamingBlockReveal(node = node, modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) { revealModifier ->
                if (enableLatexRendering) {
                    MathBlock(
                        formula,
                        modifier = revealModifier
                    )
                } else {
                    Text(
                        text = formula,
                        fontFamily = FontFamily.Monospace,
                        modifier = revealModifier
                    )
                }
            }
        }

        MdNodeType.InlineCode -> {
            val code = node.textIn(content).trim('`')
            Text(
                text = code, fontFamily = FontFamily.Monospace, modifier = modifier
            )
        }

        // 代码块 — CODE_BLOCK (indented) + CODE_FENCE (fenced) collapse to one MdNodeType.CodeBlock
        // (mapping doc §2 #8/#9, §4 H4). isFencedCode picks the original two render paths.
        MdNodeType.CodeBlock -> {
            val fenceRange = node.codeFenceContentRange
            if (!node.isFencedCode || fenceRange == null) {
                // Indented code block (or a malformed fence whose body could not be located —
                // matching the original CODE_FENCE arm's early-returns, which rendered nothing).
                if (node.isFencedCode) return
                val code = node.textIn(content)
                StreamingBlockReveal(node = node, modifier = modifier) { revealModifier ->
                    Text(
                        text = code,
                        modifier = revealModifier,
                    )
                }
            } else {
                // 这里不能直接取CODE_FENCE_CONTENT的内容，因为首行indent没有包含在内
                // 因此，需要往上找到最后一个EOL元素，用它来作为代码块的起始offset
                // (the EOL-backup body-offset computation now lives in JvmMdNode.codeFenceContentRange)
                val codeContentStartOffset = fenceRange.first
                val codeContentEndOffset = fenceRange.last + 1
                val rawCode = content.substring(codeContentStartOffset, codeContentEndOffset)

                val language = node.codeLang ?: "plaintext"
                val sourceOffsetBase = LocalMarkdownSourceOffsetBase.current
                val syntheticSuffixStart = LocalMarkdownSyntheticSuffixStart.current
                val endFenceEnd = node.codeFenceEndOffset
                val hasEnd = endFenceEnd != null && sourceOffsetBase + endFenceEnd <= syntheticSuffixStart
                val liveCodeSuffix = streamingCodeFenceLiveSuffixFor(
                    sourceOffsetBase = sourceOffsetBase,
                    codeContentStartOffset = codeContentStartOffset,
                    codeContentEndOffset = codeContentEndOffset,
                    syntheticSuffixStart = syntheticSuffixStart,
                    liveSuffix = liveSuffix,
                    liveSuffixSourceOffset = liveSuffixSourceOffset,
                )
                val code = (rawCode + liveCodeSuffix).trimIndent()

                StreamingBlockReveal(node = node, modifier = Modifier.padding(bottom = 4.dp).fillMaxWidth()) { revealModifier ->
                    HighlightCodeBlock(
                        code = code,
                        language = language,
                        modifier = revealModifier,
                        completeCodeBlock = hasEnd
                    )
                }
            }
        }

        MdNodeType.Text -> {
            val text = node.textIn(content)
            Text(
                text = text,
                modifier = modifier,
            )
        }

        MdNodeType.HtmlBlock -> {
            val text = node.textIn(content)
            StreamingBlockReveal(node = node, modifier = modifier) { revealModifier ->
                SimpleHtmlBlock(
                    html = text, modifier = revealModifier
                )
            }
        }

        // 其他类型的节点，递归处理子节点
        else -> {
            // 递归处理其他节点的子节点
            node.children.fastForEach { child ->
                val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                    children = node.children,
                    child = child,
                    liveSuffix = liveSuffix,
                    liveSuffixSourceOffset = liveSuffixSourceOffset,
                )
                MarkdownNode(
                    node = child,
                    content = content,
                    modifier = modifier,
                    onClickCitation = onClickCitation,
                    liveSuffix = childLiveSuffix.text,
                    liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                )
            }
        }
    }
}

internal fun streamingCodeFenceLiveSuffixFor(
    sourceOffsetBase: Int,
    codeContentStartOffset: Int,
    codeContentEndOffset: Int,
    syntheticSuffixStart: Int,
    liveSuffix: String,
    liveSuffixSourceOffset: Int,
): String {
    if (liveSuffix.isEmpty()) return ""
    if (syntheticSuffixStart == Int.MAX_VALUE) return ""
    val absoluteCodeStart = sourceOffsetBase + codeContentStartOffset
    val absoluteCodeEnd = sourceOffsetBase + codeContentEndOffset
    if (liveSuffixSourceOffset < absoluteCodeStart) return ""
    if (liveSuffixSourceOffset < absoluteCodeEnd) return ""
    if (liveSuffixSourceOffset != syntheticSuffixStart) return ""
    return liveSuffix.withoutTrailingFenceTerminator()
}

private fun String.withoutTrailingFenceTerminator(): String {
    val trimmedEnd = trimEnd()
    val start = trimmedEnd.lastIndexOf('\n').let { if (it == -1) 0 else it + 1 }
    val lastLine = trimmedEnd.substring(start)
    if (!lastLine.matches(TRAILING_FENCE_TERMINATOR_REGEX)) return this
    return trimmedEnd.substring(0, start).removeSuffix("\n")
}

@Composable
private fun UnorderedListNode(
    node: MdNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    level: Int = 0,
    liveSuffix: String = "",
    liveSuffixSourceOffset: Int = 0,
) {
    val bulletStyle = when (level % 3) {
        0 -> "• "
        1 -> "◦ "
        else -> "▪ "
    }

    Column(
        modifier = modifier
            .fillWidthIf(LocalMarkdownFillWidth.current)
            .padding(start = (level * 8).dp)
    ) {
        var itemIndex = 0
        node.children.fastForEach { child ->
            if (child.type == MdNodeType.ListItem) {
                val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                    children = node.children,
                    child = child,
                    liveSuffix = liveSuffix,
                    liveSuffixSourceOffset = liveSuffixSourceOffset,
                )
                ListItemNode(
                    node = child,
                    content = content,
                    bulletText = bulletStyle,
                    onClickCitation = onClickCitation,
                    level = level,
                    itemIndex = itemIndex,
                    liveSuffix = childLiveSuffix.text,
                    liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                )
                itemIndex++
            }
        }
    }
}

@Composable
private fun OrderedListNode(
    node: MdNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    level: Int = 0,
    liveSuffix: String = "",
    liveSuffixSourceOffset: Int = 0,
) {
    Column(
        modifier = modifier
            .fillWidthIf(LocalMarkdownFillWidth.current)
            .padding(start = (level * 8).dp)
    ) {
        var index = 1
        node.children.fastForEach { child ->
            if (child.type == MdNodeType.ListItem) {
                // R-E2 / KU-7: keep slicing the per-item LIST_NUMBER literal to preserve the
                // author's exact numbering ("7. "). LIST_NUMBER maps to Unknown and is the first
                // Unknown child of the LIST_ITEM, so findChildOfTypeRecursive(Unknown) returns it.
                val numberText =
                    child.findChildOfTypeRecursive(MdNodeType.Unknown)?.textIn(content) ?: "$index. "
                val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                    children = node.children,
                    child = child,
                    liveSuffix = liveSuffix,
                    liveSuffixSourceOffset = liveSuffixSourceOffset,
                )
                ListItemNode(
                    node = child,
                    content = content,
                    bulletText = numberText,
                    onClickCitation = onClickCitation,
                    level = level,
                    itemIndex = index - 1,
                    liveSuffix = childLiveSuffix.text,
                    liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                )
                index++
            }
        }
    }
}

@Composable
private fun ListItemNode(
    node: MdNode,
    content: String,
    bulletText: String,
    onClickCitation: (String) -> Unit = {},
    level: Int,
    itemIndex: Int,
    liveSuffix: String = "",
    liveSuffixSourceOffset: Int = 0,
) {
    Column(
        modifier = Modifier.fillWidthIf(LocalMarkdownFillWidth.current)
    ) {
        // 分离列表项的直接内容和嵌套列表
        val (directContent, nestedLists) = separateContentAndLists(node)
        // directContent 渲染处理
        if (directContent.isNotEmpty()) {
            StreamingBlockReveal(
                node = node,
                enabled = itemIndex < STREAMING_LIST_ITEM_STAGGER_LIMIT,
                delayMillis = itemIndex * STREAMING_LIST_ITEM_STAGGER_MS,
                durationMillis = STREAMING_LIST_ITEM_REVEAL_MS,
            ) { revealModifier ->
                Row(
                    modifier = revealModifier.fillWidthIf(LocalMarkdownFillWidth.current)
                ) {
                    Text(
                        text = bulletText,
                        modifier = Modifier.alignByBaseline(),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        directContent.fastForEach { contentChild ->
                            val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                                children = node.children,
                                child = contentChild,
                                liveSuffix = liveSuffix,
                                liveSuffixSourceOffset = liveSuffixSourceOffset,
                            )
                            MarkdownNode(
                                node = contentChild,
                                content = content,
                                modifier = Modifier.fillWidthIf(LocalMarkdownFillWidth.current),
                                onClickCitation = onClickCitation,
                                listLevel = level,
                                liveSuffix = childLiveSuffix.text,
                                liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                            )
                        }
                    }
                }
            }
        }
        // nestedLists 渲染处理
        nestedLists.fastForEach { nestedList ->
            val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                children = node.children,
                child = nestedList,
                liveSuffix = liveSuffix,
                liveSuffixSourceOffset = liveSuffixSourceOffset,
            )
            MarkdownNode(
                node = nestedList,
                content = content,
                onClickCitation = onClickCitation,
                listLevel = level + 1, // 增加层级
                liveSuffix = childLiveSuffix.text,
                liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
            )
        }
    }
}

// 分离列表项的直接内容和嵌套列表
private fun separateContentAndLists(listItemNode: MdNode): Pair<List<MdNode>, List<MdNode>> {
    val directContent = mutableListOf<MdNode>()
    val nestedLists = mutableListOf<MdNode>()
    listItemNode.children.fastForEach { child ->
        when (child.type) {
            MdNodeType.ListUnordered, MdNodeType.ListOrdered -> {
                nestedLists.add(child)
            }

            else -> {
                directContent.add(child)
            }
        }
    }
    return directContent to nestedLists
}

@Composable
private fun Paragraph(
    node: MdNode,
    content: String,
    trim: Boolean = false,
    onClickCitation: (String) -> Unit = {},
    modifier: Modifier,
    // Style for the plain tail suffix (text belonging to the NEXT block, shown
    // as a preview before the parser stabilizes it). Defaults to the current
    // text style, but callers that override LocalTextStyle for their own block
    // (e.g. a heading) must pass the base body style so the preview of the
    // following block isn't rendered with this block's style.
    plainSuffixStyle: TextStyle = LocalTextStyle.current,
    liveSuffix: String = "",
    liveSuffixSourceOffset: Int = 0,
) {
    // dumpAst(node, content)
    // Cache the AST-walk result so a per-frame recomposition (triggered by
    // revealClock subscription below) doesn't re-traverse the whole subtree
    // just to decide between FlowRow-of-MarkdownNode (images/block-math
    // present) and the inline AnnotatedString path. Pre-cache mirrors
    // hasInlineMath's pattern further down.
    val hasImageOrBlockMath = remember(node) {
        node.findChildOfTypeRecursive(MdNodeType.Image, MdNodeType.MathBlock) != null
    }
    if (hasImageOrBlockMath) {
        FlowRow(modifier = modifier.fillWidthIf(LocalMarkdownFillWidth.current)) {
            node.children.fastForEach { child ->
                val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                    children = node.children,
                    child = child,
                    liveSuffix = liveSuffix,
                    liveSuffixSourceOffset = liveSuffixSourceOffset,
                )
                MarkdownNode(
                    node = child,
                    content = content,
                    onClickCitation = onClickCitation,
                    liveSuffix = childLiveSuffix.text,
                    liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                )
            }
        }
        return
    }

    val colorScheme = MaterialTheme.colorScheme
    // Populated lazily by appendMarkdownNodeContent during static rebuilds
    // via putIfAbsent for INLINE_LINK citation + INLINE_MATH formula
    // keys. Lives as long as this Paragraph composable (remember without
    // keys), so entries from previously rendered inline nodes can persist
    // across content changes — pre-existing behavior, not introduced by
    // the reveal-overlay refactor. Acceptable because content changes
    // during streaming only append new inline nodes; the user doesn't
    // edit/remove inline content mid-stream in this app.
    val inlineContents = remember {
        mutableStateMapOf<String, InlineTextContent>()
    }
    val hasInlineMath = remember(node) {
        node.findChildOfTypeRecursive(MdNodeType.MathInline) != null
    }
    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering

    val textStyle = LocalTextStyle.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val onClickUrl: (String) -> Unit = remember(context) {
        { url -> context.openUrl(url) }
    }
    val searchSources = LocalSearchSources.current

    // Streaming-aware text build: static parsed text is opaque; only the
    // live suffix (newest, not-yet-parsed text) fades in as one batch.
    val baseColor = LocalContentColor.current
    val sourceOffsetBase = LocalMarkdownSourceOffsetBase.current

    FlowRow(
        modifier = modifier
            .fillWidthIf(LocalMarkdownFillWidth.current)
            .then(
                if (node.nextSibling() != null) Modifier.padding(bottom = LocalTextStyle.current.fontSize.toDp())
                else Modifier
            )
    ) {
        // `node` is included as a defensive key — in practice it's stable
        // across content-equal recompositions because MarkdownParseCache
        // returns the same root reference, so this rarely contributes to
        // invalidations. Kept so a future cache refactor that breaks the
        // identity invariant doesn't silently stale the static AnnotatedString.
        val staticAnnotated = remember(
            content,
            enableLatexRendering,
            onClickUrl,
            baseColor,
            node,
            trim,
            colorScheme,
            density,
            textStyle,
            searchSources,
        ) {
            buildAnnotatedString {
                node.children.fastForEach { child ->
                    appendMarkdownNodeContent(
                        node = child,
                        content = content,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        onClickCitation = onClickCitation,
                        style = textStyle,
                        density = density,
                        trim = trim,
                        enableLatexRendering = enableLatexRendering,
                        onClickUrl = onClickUrl,
                        baseColor = baseColor,
                        sourceOffsetBase = sourceOffsetBase,
                        searchSources = searchSources,
                    )
                }
            }
        }
        // Batch reveal (L4 decoupled): only the inline unparsed suffix animates.
        // A suffix that opens a new block renders as plain tail text below to
        // avoid block-boundary reflow.
        val suffixPlacement = liveSuffixPlacementFor(liveSuffix)
        // Even an Inline-classified suffix can spill past a blank line into the
        // next block (e.g. a heading's trailing words followed by the paragraph
        // after it). Keep only the pre-boundary part inline (this block's
        // style); everything past the blank line drops below as plain body text.
        val (blockInlineSuffix, blockPlainSuffix) = splitLiveSuffixAtBlockBoundary(liveSuffix)
        val inlineLiveSuffix: String
        val plainLiveSuffix: String
        val plainSuffixSourceOffset: Int
        if (suffixPlacement == LiveSuffixPlacement.Inline) {
            inlineLiveSuffix = blockInlineSuffix
            plainLiveSuffix = blockPlainSuffix.replace(BREAK_LINE_REGEX, "\n")
            plainSuffixSourceOffset = liveSuffixSourceOffset + blockInlineSuffix.length
        } else {
            inlineLiveSuffix = ""
            plainLiveSuffix = liveSuffix.replace(BREAK_LINE_REGEX, "\n")
            plainSuffixSourceOffset = liveSuffixSourceOffset
        }
        val combined = remember(staticAnnotated, inlineLiveSuffix, baseColor) {
            if (inlineLiveSuffix.isEmpty()) {
                staticAnnotated
            } else {
                buildAnnotatedString {
                    append(staticAnnotated)
                    append(inlineLiveSuffix.replace(BREAK_LINE_REGEX, "\n"))
                }
            }
        }
        // Appearance-anchored reveal: every suffix character runs its own
        // fade+lift from the frame it first appears (clock keyed by absolute
        // source offsets, so a parse tick shrinking the suffix does not
        // restart carried-over characters). Gate on the suffix itself, NOT on
        // the streaming-tail marker — the marker drops the instant generation
        // ends, and the final batch must finish fading on its own clock.
        // Separate clocks for the inline and plain segments: each clock prunes
        // by its own source offset, and the plain segment starts at a higher
        // offset than the inline one.
        val streamingTail = inlineLiveSuffix.isNotEmpty() || plainLiveSuffix.isNotEmpty()
        val inlineRevealClock = remember { StreamingCharRevealClock() }
        val plainRevealClock = remember { StreamingCharRevealClock() }
        // Seeded with System.nanoTime, NOT 0: Choreographer frame nanos share
        // the System.nanoTime timebase, and characters stamped before the
        // first frame callback would otherwise read as ages old and skip
        // their fade entirely.
        var revealNowNanos by remember { mutableLongStateOf(System.nanoTime()) }
        LaunchedEffect(streamingTail) {
            if (!streamingTail) return@LaunchedEffect
            while (true) {
                withFrameNanos { revealNowNanos = it }
            }
        }
        // Settled catch-up: when a parse tick promotes suffix text into
        // staticAnnotated, the newest characters are usually still mid-fade —
        // without this they snap to full opacity at every tick boundary. The
        // grown range keeps fading to opaque on its own short animation.
        // (A non-streaming static grow — e.g. an edited message — also takes
        // this path; a 120ms fade on appended text is harmless there.)
        val staticLength = staticAnnotated.length
        var settledCatchupStart by remember { mutableIntStateOf(staticLength) }
        var prevStaticLength by remember { mutableIntStateOf(staticLength) }
        val settledCatchupAnim = remember { Animatable(1f) }
        LaunchedEffect(staticLength) {
            if (staticLength > prevStaticLength && prevStaticLength > 0) {
                settledCatchupStart = prevStaticLength
                prevStaticLength = staticLength
                settledCatchupAnim.snapTo(0f)
                settledCatchupAnim.animateTo(
                    1f,
                    animationSpec = tween(
                        durationMillis = STREAMING_SETTLED_CATCHUP_FADE_MS,
                        easing = LinearEasing,
                    ),
                )
            } else {
                prevStaticLength = staticLength
            }
        }
        val annotatedString = remember(
            combined,
            staticLength,
            revealNowNanos,
            settledCatchupAnim.value,
            baseColor,
        ) {
            applyStreamingCharReveal(
                combined = combined,
                staticLength = staticLength,
                suffixSourceOffset = liveSuffixSourceOffset,
                clock = inlineRevealClock,
                nowNanos = revealNowNanos,
                baseColor = baseColor,
                settledCatchupStart = settledCatchupStart,
                settledCatchupAlpha = settledCatchupAnim.value,
            )
        }

        Text(
            text = annotatedString,
            modifier = Modifier.fillWidthIf(LocalMarkdownFillWidth.current),
            inlineContent = inlineContents,
            softWrap = true,
            overflow = TextOverflow.Visible,
            style = LocalTextStyle.current.copy(
                lineHeight = if (hasInlineMath && enableLatexRendering) TextUnit.Unspecified else LocalTextStyle.current.lineHeight
            )
        )
        if (plainLiveSuffix.isNotEmpty()) {
            val plainAnnotatedString = remember(plainLiveSuffix, revealNowNanos, baseColor) {
                applyStreamingCharRevealPlain(
                    text = plainLiveSuffix,
                    suffixSourceOffset = plainSuffixSourceOffset,
                    clock = plainRevealClock,
                    nowNanos = revealNowNanos,
                    baseColor = baseColor,
                )
            }
            Text(
                text = plainAnnotatedString,
                modifier = Modifier.fillWidthIf(LocalMarkdownFillWidth.current),
                softWrap = true,
                overflow = TextOverflow.Visible,
                style = plainSuffixStyle.copy(
                    lineHeight = if (hasInlineMath && enableLatexRendering) {
                        TextUnit.Unspecified
                    } else {
                        plainSuffixStyle.lineHeight
                    },
                ),
            )
        }
    }
}

@Composable
private fun TableNode(
    node: MdNode,
    content: String,
    modifier: Modifier = Modifier,
    liveSuffix: String = "",
    liveSuffixSourceOffset: Int = 0,
) {
    val settledTableData = remember(node, content) {
        traceMarkdown("Amber Markdown table extract") {
            extractMarkdownTableData(node = node, content = content)
        }
    } ?: return
    val sourceOffsetBase = LocalMarkdownSourceOffsetBase.current
    val tableData = remember(node, content, sourceOffsetBase, liveSuffix, liveSuffixSourceOffset) {
        traceMarkdown("Amber Markdown table streaming extract") {
            extractStreamingMarkdownTableData(
                node = node,
                content = content,
                sourceOffsetBase = sourceOffsetBase,
                liveSuffix = liveSuffix,
                liveSuffixSourceOffset = liveSuffixSourceOffset,
            )
        }
    } ?: settledTableData
    val tableMotionKey = remember(node.type, sourceOffsetBase, node.startOffset) {
        streamingMarkdownMotionKey(
            type = node.type,
            sourceOffsetBase = sourceOffsetBase,
            nodeStartOffset = node.startOffset,
        )
    }
    val firstLiveRowIndex = if (liveSuffix.isNotEmpty()) {
        settledTableData.rows.size
    } else {
        0
    }
    val tableCellRevealEnabled =
        tableData.rows.size * tableData.columnCount <= STREAMING_TABLE_CELL_REVEAL_MAX_CELLS

    // 创建表头composable列表
    val headers = List(tableData.columnCount) { columnIndex ->
        @Composable {
            TableCellContent(tableData.headers.getOrElse(columnIndex) { "" })
        }
    }

    // 创建行数据composable列表
    val rowComposables = tableData.rows.map { rowData ->
        List(tableData.columnCount) { columnIndex ->
            @Composable {
                TableCellContent(rowData.getOrElse(columnIndex) { "" })
            }
        }
    }

    TraceMarkdownComposable("Amber DataTable render") {
        StreamingBlockReveal(
            node = node,
            modifier = modifier
                .padding(vertical = 8.dp)
                .amberTraceMeasure("Amber DataTable measure"),
        ) { revealModifier ->
            DataTable(
                headers = headers,
                rows = rowComposables,
                modifier = revealModifier,
                columnMinWidths = List(tableData.columnCount) { 80.dp },
                columnMaxWidths = List(tableData.columnCount) { 200.dp },
                bodyCellModifier = { rowIndex, columnIndex ->
                    streamingRevealModifier(
                        key = streamingTableCellMotionKey(
                            tableKey = tableMotionKey,
                            rowIndex = rowIndex,
                            columnIndex = columnIndex,
                            header = false,
                        ),
                        enabled = tableCellRevealEnabled &&
                            (liveSuffix.isEmpty() || rowIndex >= firstLiveRowIndex),
                        durationMillis = STREAMING_TABLE_CELL_REVEAL_MS,
                        startAlpha = STREAMING_TABLE_CELL_REVEAL_START_ALPHA,
                        offsetYDp = 0,
                    )
                },
            )
        }
    }
}

@Composable
private fun TableCellContent(content: String) {
    if (TABLE_CELL_MARKDOWN_HINT_REGEX.containsMatchIn(content)) {
        MarkdownBlock(content = content, fillWidth = false)
    } else {
        Text(
            text = content,
            modifier = Modifier.padding(start = 4.dp),
            softWrap = true,
            overflow = TextOverflow.Visible,
            style = LocalTextStyle.current,
        )
    }
}

internal data class MarkdownTableData(
    val columnCount: Int,
    val headers: List<String>,
    val rows: List<List<String>>,
)

internal fun extractStreamingMarkdownTableData(
    node: MdNode,
    content: String,
    sourceOffsetBase: Int,
    liveSuffix: String,
    liveSuffixSourceOffset: Int,
): MarkdownTableData? {
    val settledData = extractMarkdownTableData(node = node, content = content) ?: return null
    if (liveSuffix.isEmpty()) return settledData

    val absoluteTableEnd = sourceOffsetBase + node.endOffset
    if (liveSuffixSourceOffset != absoluteTableEnd) return settledData

    val tableText = node.textIn(content) + liveSuffix
    // Re-parse the appended table text on the JVM parser and wrap the table node in JvmMdNode
    // so extractMarkdownTableData stays MdNode-typed (the only place a JetBrains parse leaks
    // here, mirroring the parse-funnel wrapping at parsePreprocessedMarkdownUncached).
    //
    // THIRD funnel site — stays JVM-only by design (TD.Rust.1a Task 13, not in scope): this
    // re-parses a tiny appended table fragment for live streaming, not a full document. The
    // `markdownAst` native switch is intentionally NOT applied here; the fragment is JvmMdNode
    // regardless of the flag. Switching it would add JNI cost per streaming tick for no parity
    // benefit (the settled table already went through the flag-gated main funnel).
    val streamingTable = JvmMdNode(parser.buildMarkdownTreeFromString(tableText), tableText, null)
        .children
        .firstOrNull { it.type == MdNodeType.Table }
        ?: return settledData

    return extractMarkdownTableData(node = streamingTable, content = tableText) ?: settledData
}

internal fun extractMarkdownTableData(node: MdNode, content: String): MarkdownTableData? {
    val headerNode = node.children.find { it.type == MdNodeType.TableHead }
    val rowNodes = node.children.filter { it.type == MdNodeType.TableRow }
    val columnCount = headerNode?.children?.count { it.type == MdNodeType.TableCell } ?: 0
    if (columnCount == 0) return null

    val headerCells = headerNode?.children
        ?.filter { it.type == MdNodeType.TableCell }
        ?.map { it.textIn(content).trim() }
        ?: emptyList()
    val rows = rowNodes.map { rowNode ->
        rowNode.children
            .filter { it.type == MdNodeType.TableCell }
            .map { it.textIn(content).trim() }
    }
    return MarkdownTableData(
        columnCount = columnCount,
        headers = headerCells,
        rows = rows,
    )
}

/**
 * Walks a markdown [MdNode] and appends its rendered content into the
 * receiver [AnnotatedString.Builder] — including spans for EMPH /
 * STRONG / STRIKETHROUGH / links / code / inline math.
 *
 */
internal fun AnnotatedString.Builder.appendMarkdownNodeContent(
    node: MdNode,
    content: String,
    trim: Boolean = false,
    inlineContents: MutableMap<String, InlineTextContent>,
    colorScheme: ColorScheme,
    density: Density,
    style: TextStyle,
    enableLatexRendering: Boolean = true,
    onClickCitation: (String) -> Unit = {},
    onClickUrl: (String) -> Unit = {},
    baseColor: Color = Color.Unspecified,
    sourceOffsetBase: Int = 0,
    searchSources: SearchSourcesRegistry? = null,
) {
    when {
        // BLOCK_QUOTE marker token (`>`) — mapping doc §4 H9. The token maps to Unknown; the
        // original `type == BLOCK_QUOTE -> {}` no-op skipped rendering the `>` prefix.
        // isBlockquoteMarker checks the real JetBrains type so only the genuine marker is skipped
        // (a `>` autolink delimiter or escaped `\>` is NOT). Must precede the generic leaf arm.
        node.isBlockquoteMarker -> {}

        // Links — INLINE_LINK + AUTOLINK + GFM_AUTOLINK all map to MdNodeType.Link (mapping doc
        // §2 #18/#19/#23, §4 H6/H7). Discriminate by structure (NOT linkHref — an inline link
        // with an angle-bracket destination `[t](<url>)` has no LINK_DESTINATION child and so a
        // null linkHref):
        //  - leaf (no children)  → GFM_AUTOLINK (bare url), original 2485 arm
        //  - isAutolink (<url>)  → AUTOLINK, original 2606 arm (trim LT/GT via contentChildren)
        //  - otherwise           → INLINE_LINK, original 2564 arm (pills / normal link)
        // Placed before the generic leaf arm so the leaf GFM_AUTOLINK is caught here (matching the
        // original order where GFM_AUTOLINK precedes the leaf arm).
        node.type == MdNodeType.Link && node.children.isEmpty() -> {
            val link = node.textIn(content)
            withLink(openUrlLinkAnnotation(link, onClickUrl)) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(link)
                }
            }
        }

        node.children.isEmpty() -> {
            val rawText = node.textIn(content)
            val text = (if (trim) rawText.trim() else rawText)
                .replace(BREAK_LINE_REGEX, "\n")
            append(text)
        }

        node.type == MdNodeType.Emphasis -> {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                // contentChildren strips the `*`/`_` markers via the real EMPH token type
                // (mapping doc §4 H5 — markers collapse to Unknown so can't be stripped here).
                node.contentChildren.fastForEach {
                    appendMarkdownNodeContent(
                        node = it,
                        content = content,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        density = density,
                        style = style,
                        enableLatexRendering = enableLatexRendering,
                        onClickCitation = onClickCitation,
                        onClickUrl = onClickUrl,
                        baseColor = baseColor,
                        sourceOffsetBase = sourceOffsetBase,
                        searchSources = searchSources,
                    )
                }
            }
        }

        node.type == MdNodeType.Strong -> {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                // contentChildren strips the 2+2 `**` markers via the real EMPH token type (§4 H5).
                node.contentChildren.fastForEach {
                    appendMarkdownNodeContent(
                        node = it,
                        content = content,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        density = density,
                        style = style,
                        enableLatexRendering = enableLatexRendering,
                        onClickCitation = onClickCitation,
                        onClickUrl = onClickUrl,
                        baseColor = baseColor,
                        sourceOffsetBase = sourceOffsetBase,
                        searchSources = searchSources,
                    )
                }
            }
        }

        node.type == MdNodeType.Strikethrough -> {
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                // contentChildren strips the 2+2 `~~` markers via the real TILDE token type (§4 H5).
                node.contentChildren.fastForEach {
                    appendMarkdownNodeContent(
                        node = it,
                        content = content,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        density = density,
                        style = style,
                        enableLatexRendering = enableLatexRendering,
                        onClickCitation = onClickCitation,
                        onClickUrl = onClickUrl,
                        baseColor = baseColor,
                        sourceOffsetBase = sourceOffsetBase,
                        searchSources = searchSources,
                    )
                }
            }
        }

        node.type == MdNodeType.Link && !node.isAutolink -> {
            // INLINE_LINK (everything that is a Link, non-leaf, and not the <url> autolink form;
            // gating on linkHref would wrongly route `[t](<url>)` — which has no LINK_DESTINATION
            // — to the autolink arm). Destination via linkHref (E5); visible label = LINK_TEXT
            // text trimmed of brackets (linkLabel relocates the dig, §4 H6/KU-1).
            val linkDest = node.linkHref ?: ""
            val linkText = node.linkLabel?.trim { it == '[' || it == ']' } ?: linkDest
            val searchSource = searchSources?.lookup(linkDest)
            if (linkText.startsWith("citation,")) {
                // 如果是引用，则特殊处理
                val domain = linkText.substringAfter("citation,")
                val id = linkDest
                if (id.length == 6) {
                    appendSearchSourcePill(
                        key = "citation:$linkDest",
                        label = domain,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        onClick = { onClickCitation(id.trim()) },
                    )
                } else {
                    append(domain)
                }
            } else if (searchSource != null) {
                appendSearchSourcePill(
                    key = "search-source:${searchSource.host}:$linkDest",
                    label = searchSource.name,
                    inlineContents = inlineContents,
                    colorScheme = colorScheme,
                    onClick = { onClickUrl(linkDest) },
                )
            } else {
                withLink(openUrlLinkAnnotation(linkDest, onClickUrl)) {
                    withStyle(
                        SpanStyle(
                            color = colorScheme.primary, textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(linkText)
                    }
                }
            }
        }

        node.type == MdNodeType.Link -> {
            // AUTOLINK (<url>) — the only remaining Link form here (leaf GFM_AUTOLINK and
            // INLINE_LINK already handled). contentChildren strips the `<`/`>` delimiter tokens
            // (LT/GT, which collapse to Unknown) via the real types (mapping doc §4 H7); the
            // surviving inner-autolink token renders as an italic clickable link.
            node.contentChildren.fastForEach { link ->
                withLink(openUrlLinkAnnotation(link.textIn(content), onClickUrl)) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(link.textIn(content))
                    }
                }
            }
        }

        node.type == MdNodeType.InlineCode -> {
            val code = node.textIn(content).trim('`')
            withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 0.95.em,
                    background = colorScheme.secondaryContainer.copy(alpha = 0.2f),
                )
            ) {
                append(code)
            }
        }

        node.type == MdNodeType.MathInline -> {
            if (enableLatexRendering) {
                // formula as id
                val formula = node.textIn(content)
                appendInlineContent(formula, "[Latex]")
                val (width, height) = with(density) {
                    assumeLatexSize(
                        latex = formula, fontSize = style.fontSize.toPx()
                    ).let {
                        it.width().toSp() to it.height().toSp()
                    }
                }
                inlineContents.putIfAbsent(/* key = */ formula,/* value = */ InlineTextContent(
                    placeholder = Placeholder(
                        width = width, height = height, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                    ), children = {
                        MathInline(
                            latex = formula, modifier = Modifier
                        )
                    })
                )
            } else {
                // 禁用 LaTeX 渲染时，以等宽字体显示原始公式
                val formula = node.textIn(content)
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 0.95.em,
                    )
                ) {
                    append(formula)
                }
            }
        }

        // 其他类型继续递归处理
        else -> {
            node.children.fastForEach {
                appendMarkdownNodeContent(
                    node = it,
                    content = content,
                    inlineContents = inlineContents,
                    colorScheme = colorScheme,
                    density = density,
                    style = style,
                    enableLatexRendering = enableLatexRendering,
                    onClickCitation = onClickCitation,
                    onClickUrl = onClickUrl,
                    baseColor = baseColor,
                    sourceOffsetBase = sourceOffsetBase,
                    searchSources = searchSources,
                )
            }
        }
    }
}

/**
 * Appearance-anchored streaming reveal layer (L4).
 * Instead of mapping per-codepoint alpha back to source offsets through the
 * markdown transforms, it styles only one contiguous range — the live suffix
 * appended after the statically-parsed text. [staticLength] is the boundary
 * between settled text and the revealing suffix. Each suffix character fades
 * and lifts on its own clock (stamped by [clock] the first frame it is seen),
 * so glyphs float in continuously regardless of parse-tick timing.
 *
 * [settledCatchupStart]/[settledCatchupAlpha] bridge the absorption boundary:
 * the range of settled text grown by the latest parse tick keeps fading to
 * opaque instead of snapping (the suffix characters it replaced were usually
 * still mid-fade).
 *
 * Returns [combined] unchanged when there's nothing to animate.
 */
internal fun applyStreamingCharReveal(
    combined: AnnotatedString,
    staticLength: Int,
    suffixSourceOffset: Int,
    clock: StreamingCharRevealClock,
    nowNanos: Long,
    baseColor: Color,
    settledCatchupStart: Int = staticLength,
    settledCatchupAlpha: Float = 1f,
): AnnotatedString {
    if (baseColor == Color.Unspecified) return combined
    if (staticLength < 0 || staticLength > combined.length) return combined
    val hasSuffix = staticLength < combined.length
    val catchupActive = settledCatchupAlpha < 1f &&
        settledCatchupStart in 0 until staticLength
    if (!hasSuffix && !catchupActive) return combined

    val fadeNanos = STREAMING_CHAR_REVEAL_FADE_MS * 1_000_000L
    if (hasSuffix) {
        clock.stamp(
            suffixText = combined.text.substring(staticLength),
            suffixSourceOffset = suffixSourceOffset,
            nowNanos = nowNanos,
            fadeNanos = fadeNanos,
            staggerNanos = STREAMING_CHAR_REVEAL_STAGGER_MS * 1_000_000L,
            maxCascadeNanos = STREAMING_CHAR_REVEAL_MAX_CASCADE_MS * 1_000_000L,
            maxPending = STREAMING_CHAR_REVEAL_MAX_PENDING,
        )
    }
    return buildAnnotatedString {
        append(combined)
        if (catchupActive) {
            // Gradient tail: within the freshly absorbed range, older characters
            // were already near-opaque when the parse tick promoted them, only
            // the newest were mid-fade. A uniform overlay would visibly DARKEN
            // the old end every tick, so the alpha ramps from opaque at the old
            // end down to the catch-up floor at the newest, and the whole ramp
            // animates to 1. Per-codepoint spans, bounded by the absorbed range.
            val fullRamp = ArrayList<Pair<Int, Int>>()
            var walker = settledCatchupStart
            while (walker < staticLength) {
                val cp = combined.text.codePointAt(walker)
                val next = walker + Character.charCount(cp)
                fullRamp.add(walker to next)
                walker = next
            }
            val ramp = if (fullRamp.size > STREAMING_SETTLED_CATCHUP_MAX_CODEPOINTS) {
                fullRamp.subList(
                    fullRamp.size - STREAMING_SETTLED_CATCHUP_MAX_CODEPOINTS,
                    fullRamp.size,
                )
            } else {
                fullRamp
            }
            val animated = codexStreamingAlphaProgress(settledCatchupAlpha)
            ramp.forEachIndexed { index, (start, end) ->
                val youth = if (ramp.size <= 1) 1f else index.toFloat() / (ramp.size - 1)
                val floor = 1f - (1f - STREAMING_SETTLED_CATCHUP_START_ALPHA) * youth
                val alpha = floor + (1f - floor) * animated
                if (alpha < 1f) {
                    addStyle(
                        style = SpanStyle(color = baseColor.copy(alpha = alpha)),
                        start = start,
                        end = end,
                    )
                }
            }
        }
        var offset = staticLength
        while (offset < combined.length) {
            val codePoint = combined.text.codePointAt(offset)
            val nextOffset = offset + Character.charCount(codePoint)
            val progress = clock.progressAt(
                absOffset = suffixSourceOffset + (offset - staticLength),
                nowNanos = nowNanos,
                fadeNanos = fadeNanos,
            )
            if (progress < 1f) {
                addStyle(
                    style = streamingRevealSpanStyle(
                        baseColor = baseColor,
                        localProgress = progress,
                        eased = easeStreamingReveal(progress),
                    ),
                    start = offset,
                    end = nextOffset,
                )
            }
            offset = nextOffset
        }
    }
}

internal fun applyStreamingCharRevealPlain(
    text: String,
    suffixSourceOffset: Int,
    clock: StreamingCharRevealClock,
    nowNanos: Long,
    baseColor: Color,
): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    return applyStreamingCharReveal(
        combined = buildAnnotatedString { append(text) },
        staticLength = 0,
        suffixSourceOffset = suffixSourceOffset,
        clock = clock,
        nowNanos = nowNanos,
        baseColor = baseColor,
    )
}

private fun streamingRevealSpanStyle(
    baseColor: Color,
    localProgress: Float,
    eased: Float,
): SpanStyle {
    val alphaProgress = codexStreamingAlphaProgress(localProgress)
    val alpha = STREAMING_CHAR_REVEAL_START_ALPHA +
        (1f - STREAMING_CHAR_REVEAL_START_ALPHA) * alphaProgress
    return SpanStyle(
        color = baseColor.copy(alpha = alpha),
        baselineShift = BaselineShift(-STREAMING_CHAR_REVEAL_LIFT_EM * (1f - eased)),
    )
}

private fun easeStreamingReveal(progress: Float): Float {
    val inverse = 1f - progress.coerceIn(0f, 1f)
    return 1f - inverse * inverse * inverse
}

private fun codexStreamingAlphaProgress(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return p * p * (3f - 2f * p)
}

private fun openUrlLinkAnnotation(url: String, onClickUrl: (String) -> Unit): LinkAnnotation.Url {
    return LinkAnnotation.Url(
        url = url,
        linkInteractionListener = LinkInteractionListener { annotation ->
            val target = (annotation as? LinkAnnotation.Url)?.url ?: url
            onClickUrl(target)
        },
    )
}

