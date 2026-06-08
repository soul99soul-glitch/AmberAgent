package app.amber.feature.ui.components.richtext

import android.os.Looper
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
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
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.LeafASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import app.amber.feature.ui.components.richtext.nativebridge.MarkdownNativeSwitch
import app.amber.feature.ui.components.richtext.nativebridge.MarkdownPreprocessNative
import app.amber.agent.ui.components.richtext.nativebridge.PackedAstReader
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import kotlin.random.Random

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
    val astTree: ASTNode,
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
        return animatedKeys.add(key)
    }

    fun hasSeen(key: StreamingMarkdownMotionKey): Boolean {
        return key in animatedKeys
    }

    fun clear() {
        animatedKeys.clear()
    }
}

internal fun streamingMarkdownMotionKey(
    type: IElementType,
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

internal fun streamingLiveSuffixForLastRenderableChild(
    children: List<ASTNode>,
    child: ASTNode,
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

private fun ASTNode.isStreamingSuffixRenderableTarget(): Boolean {
    return when (type) {
        MarkdownElementTypes.PARAGRAPH,
        MarkdownElementTypes.UNORDERED_LIST,
        MarkdownElementTypes.ORDERED_LIST,
        MarkdownElementTypes.LIST_ITEM,
        MarkdownElementTypes.BLOCK_QUOTE,
        MarkdownElementTypes.ATX_1,
        MarkdownElementTypes.ATX_2,
        MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5,
        MarkdownElementTypes.ATX_6,
        MarkdownElementTypes.IMAGE,
        MarkdownElementTypes.CODE_FENCE,
        MarkdownElementTypes.CODE_BLOCK,
        MarkdownElementTypes.HTML_BLOCK,
        MarkdownTokenTypes.ATX_CONTENT,
        MarkdownTokenTypes.TEXT,
        MarkdownTokenTypes.HORIZONTAL_RULE,
        GFMElementTypes.TABLE,
        GFMElementTypes.BLOCK_MATH,
        GFMTokenTypes.CHECK_BOX -> true

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
 * Codex-style batch reveal window. The live suffix starts faint but readable,
 * reaches near-opaque by the 200ms parse tick, then finishes softly. This keeps
 * the alpha motion visible without letting parse absorption pop the tail.
 */
private const val STREAMING_BATCH_FADE_MS = 220
private const val STREAMING_BATCH_CHAR_STAGGER_MS = 12
private const val STREAMING_BATCH_STAGGER_MAX_CODEPOINTS = 96
private const val STREAMING_BATCH_MAX_STAGGER_FRACTION = 0.42f
private const val STREAMING_BATCH_REVEAL_START_ALPHA = 0.24f
private const val STREAMING_BATCH_REVEAL_LIFT_EM = 0.10f
private const val STREAMING_BLOCK_REVEAL_MS = 170
private const val STREAMING_BLOCK_REVEAL_START_ALPHA = 0.86f
private const val STREAMING_BLOCK_REVEAL_OFFSET_DP = 3
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

private fun ASTNode.containsHtmlBlocks(): Boolean {
    if (type == MarkdownElementTypes.HTML_BLOCK) return true
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

    val astTree = parser.buildMarkdownTreeFromString(preprocessed)
    // Phase 2 Step 5: shadow-compare against native pulldown-cmark AST when
    // `markdownAst` is enabled. The renderer still consumes `astTree` (JVM
    // tree) — we don't swap the consumer because Markdown.kt is mid-migration
    // (see `feedback_amber_agent_rust_native_spike` memory: 12+ commits/month,
    // touching the renderer right now risks merge conflicts with active
    // streaming-render fixes). The shadow path gives us correctness telemetry
    // ahead of the eventual full swap.
    //
    // TD.Rust.1a — full renderer switch (consume PackedAstReader as primary
    // AST) is documented in docs/td-rust-1a-feasibility.md as a dedicated
    // multi-day sprint. The shadow path already validates structural parity
    // when `markdownAst` flag is on; turning that into a render-time switch
    // requires either a JVM-side ASTNode adapter over PackedAstReader
    // (defeats the perf win) or a 2000-LOC renderer rewrite to consume
    // PackedAstNode directly (needs on-device QA over 30+ markdown samples).
    // Recommendation: defer until a device-test rig exists; until then the
    // shadow path provides the same observability with zero render risk.
    maybeShadowCompareNativeAst(preprocessed, astTree)
    return MarkdownParseResult(preprocessed, astTree, astTree.containsHtmlBlocks())
}

/**
 * Top-level structural compare. **Count-only** — the JVM ASTNode exposes
 * UTF-16 char offsets, pulldown-cmark exposes UTF-8 byte offsets; on a
 * CJK / emoji corpus (AmberAgent's bread-and-butter) span comparison
 * deterministically diverges and would swamp Crashlytics with false-positive
 * mismatches (review Step 5 P0-1). The "real" semantic-equivalence compare
 * needs an offset-normalizer + a JVM↔Rust type-name mapping table and
 * arrives with the eventual renderer swap.
 *
 * Cost-gated by the shared sample rate so streaming chunks don't pay the
 * JNI + decode bill per tick — only a fraction of parses run native and
 * compare (review Step 5 P1-1).
 */
private fun maybeShadowCompareNativeAst(preprocessed: String, jvmTree: ASTNode) {
    if (!MarkdownNativeSwitch.isAstEnabled()) return
    // Skip when called on the composition (main) thread — the JNI parse +
    // PackedAstReader decode would block the UI. Caches in this file
    // sometimes hit a parse on main during first composition; let those
    // fast-path through JVM and only sample compare on background renders.
    if (Looper.myLooper() == Looper.getMainLooper()) return
    val rate = MarkdownNativeSwitch.sampleRate()
    if (rate <= 0f) return
    // Caller-side roll decides whether to incur the JNI + decode cost at all.
    // `reportAstDiff` also samples internally for matches (so dashboards see
    // a sparse "sampling is alive" heartbeat) — that's an intentional second
    // gate, the divergence path bypasses it and always reports.
    if (Random.nextFloat() >= rate) return

    // Whole-body try/catch: `PackedAstReader.root()` / `.children` is lazy
    // and can throw (e.g. "varint too long") on a magic-valid but truncated
    // native blob. Without this guard a corrupted Rust output would surface
    // as an exception inside the JVM renderer path that just consumed the
    // valid JVM tree — i.e. the shadow harness, which is supposed to NEVER
    // affect the caller, would break the caller (Codex review P1-2).
    try {
        val blob = MarkdownNativeSwitch.parseAstOrNull(preprocessed) ?: return
        val reader = PackedAstReader(blob)
        val nativeRoot = reader.root()
        if (!reader.isValid || nativeRoot == null) {
            MarkdownNativeSwitch.reportAstBlobRejected()
            return
        }
        val jvmCount = jvmTree.children.size
        val nativeChildren = nativeRoot.children
        val nativeCount = nativeChildren.size
        val equal = jvmCount == nativeCount
        val nativeTypeCodes = nativeChildren.take(8).map { it.typeCode }
        MarkdownNativeSwitch.reportAstDiff(
            equal = equal,
            jvmSummary = "blocks=$jvmCount",
            nativeSummary = "blocks=$nativeCount typeCodes=$nativeTypeCodes",
        )
    } catch (t: Throwable) {
        android.util.Log.w("MarkdownAstShadow", "native AST decode/compare threw", t)
        MarkdownNativeSwitch.reportAstBlobRejected()
    }
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

        val activeChildren = activeParse.astTree.children
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
                preserveParagraphBottomPadding = child.type == MarkdownElementTypes.PARAGRAPH &&
                    child.nextSibling() != null &&
                    child.findChildOfTypeRecursive(MarkdownElementTypes.IMAGE, GFMElementTypes.BLOCK_MATH) == null,
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
    return !hasHtmlBlocks && astTree.children.size > 1
}

internal fun MarkdownParseResult.topLevelBlockCount(): Int {
    return astTree.children.size
}

internal fun MarkdownParseResult.topLevelBlockKey(index: Int): String {
    val child = astTree.children.getOrNull(index) ?: return "missing-$index"
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
    val child = data.astTree.children.getOrNull(blockIndex) ?: return
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
     * only the trailing active block gets a [CharRevealController] so newly
     * arrived text can fade in while finalized blocks stay on the fast path.
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
    val updatedDeferStreamingParse by rememberUpdatedState(deferStreamingParse)
    val revealController = rememberCharRevealController(
        streaming = streaming || displayDrainingAfterStream,
        content = renderContent,
        immediateMode = app.amber.agent.PerfFlags.STREAMING_IMMEDIATE_CONTENT_REVEAL,
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
    LaunchedEffect(Unit) {
        var lastParsedContent = renderContent
        var lastParsedStreaming = streaming
        var lastDeferred = deferStreamingParse
        @OptIn(FlowPreview::class)
        snapshotFlow {
            Triple(updatedContent, updatedStreaming, updatedDeferStreamingParse)
        }
            .distinctUntilChanged()
            .let { upstream ->
                // Sample only when streaming. When the chat-turn is settled,
                // the user expects an immediate re-parse (e.g. they edited
                // an old message), so don't throttle.
                if (streaming) {
                    upstream.sample(MARKDOWN_STREAMING_PARSE_THROTTLE_MS)
                } else {
                    upstream
                }
            }
            .collectLatest { (latestContent, latestStreaming, latestDeferStreamingParse) ->
                if (
                    latestContent == lastParsedContent &&
                    latestStreaming == lastParsedStreaming &&
                    latestDeferStreamingParse == lastDeferred
                ) {
                    return@collectLatest
                }
                if (latestStreaming && latestDeferStreamingParse) {
                    lastDeferred = latestDeferStreamingParse
                    StreamingRenderProbe.record {
                        "parse_deferred len=${latestContent.length}"
                    }
                    return@collectLatest
                }
                try {
                    val parsed = withContext(Dispatchers.Default) {
                        if (latestStreaming) {
                            streamingParseCache.parse(content = latestContent)
                        } else {
                            streamingParseCache.reset()
                            MarkdownParseCache.getOrParse(latestContent)
                        }
                    }
                    lastParsedContent = latestContent
                    lastParsedStreaming = latestStreaming
                    lastDeferred = latestDeferStreamingParse
                    setData(parsed)
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
                    val children = data.astTree.children
                    if (data.stableTopLevelBlocks.isNotEmpty()) {
                        data.stableTopLevelBlocks.fastForEach { block ->
                            key(block.key) {
                                CompositionLocalProvider(
                                    LocalCharRevealController provides null,
                                    LocalStreamingMarkdownMotionScope provides null,
                                ) {
                                    val blockModifier = if (block.preserveParagraphBottomPadding) {
                                        nodeModifier.padding(bottom = LocalTextStyle.current.fontSize.toDp())
                                    } else {
                                        nodeModifier
                                    }
                                    block.parseResult.astTree.children.fastForEach { child ->
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
                                    LocalCharRevealController provides revealController,
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
                            val childRevealController = if (index == children.lastIndex) {
                                revealController
                            } else {
                                null
                            }
                            val childLiveSuffix = if (index == activeLastIndex) {
                                streamingLiveSuffix
                            } else {
                                EMPTY_STREAMING_LIVE_SUFFIX
                            }
                            CompositionLocalProvider(
                                LocalCharRevealController provides childRevealController,
                                LocalMarkdownSourceOffsetBase provides data.activeBaseOffset,
                                LocalMarkdownSyntheticSuffixStart provides data.syntheticSuffixStart,
                                LocalStreamingMarkdownMotionScope provides if (childRevealController != null) {
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
private fun dumpAst(node: ASTNode, text: String, indent: String = "") {
    println("$indent${node.type} ${if (node.children.isEmpty()) node.getTextInNode(text) else ""} | ${node.javaClass.simpleName}")
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
    node: ASTNode,
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
    val revealActive = enabled && LocalCharRevealController.current != null
    val shouldAnimate = remember(key) {
        mutableStateOf(false)
    }
    LaunchedEffect(scope, revealActive, key) {
        val motionScope = scope
        shouldAnimate.value = if (revealActive && motionScope != null) {
            motionScope.claim(key)
        } else {
            false
        }
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
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    listLevel: Int = 0,
    liveSuffix: String = "",
    liveSuffixSourceOffset: Int = 0,
) {
    when (node.type) {
        // 文件根节点
        MarkdownElementTypes.MARKDOWN_FILE -> {
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
        MarkdownElementTypes.PARAGRAPH -> {
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
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3, MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6 -> {
            val style = when (node.type) {
                MarkdownElementTypes.ATX_1 -> HeaderStyle.H1
                MarkdownElementTypes.ATX_2 -> HeaderStyle.H2
                MarkdownElementTypes.ATX_3 -> HeaderStyle.H3
                MarkdownElementTypes.ATX_4 -> HeaderStyle.H4
                MarkdownElementTypes.ATX_5 -> HeaderStyle.H5
                MarkdownElementTypes.ATX_6 -> HeaderStyle.H6
                else -> throw IllegalArgumentException("Unknown header type")
            }
            val headingPadding = when (node.type) {
                MarkdownElementTypes.ATX_1 -> 16.dp
                MarkdownElementTypes.ATX_2 -> 14.dp
                MarkdownElementTypes.ATX_3 -> 12.dp
                MarkdownElementTypes.ATX_4 -> 10.dp
                MarkdownElementTypes.ATX_5 -> 8.dp
                MarkdownElementTypes.ATX_6 -> 6.dp
                else -> 8.dp
            }
            ProvideTextStyle(value = style) {
                StreamingBlockReveal(node = node, modifier = modifier.fillWidthIf(LocalMarkdownFillWidth.current)) { revealModifier ->
                    FlowRow(
                        modifier = revealModifier,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        node.children.fastForEach { child ->
                            if (child.type == MarkdownTokenTypes.ATX_CONTENT) {
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
        MarkdownElementTypes.UNORDERED_LIST -> {
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

        MarkdownElementTypes.ORDERED_LIST -> {
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
        GFMTokenTypes.CHECK_BOX -> {
            val isChecked = node.getTextInNode(content).trim() == "[x]"
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
        MarkdownElementTypes.BLOCK_QUOTE -> {
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

        // 链接
        MarkdownElementTypes.INLINE_LINK -> {
            val linkText = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)
                ?.findChildOfTypeRecursive(GFMTokenTypes.GFM_AUTOLINK, MarkdownTokenTypes.TEXT)?.getTextInNode(content)
                ?: ""
            val linkDest =
                node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
            val context = LocalContext.current
            Text(
                text = linkText,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = modifier.clickable {
                    context.openUrl(linkDest)
                })
        }

        // 加粗和斜体
        MarkdownElementTypes.EMPH -> {
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

        MarkdownElementTypes.STRONG -> {
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
        GFMElementTypes.STRIKETHROUGH -> {
            Text(
                text = node.getTextInNode(content), textDecoration = TextDecoration.LineThrough, modifier = modifier
            )
        }

        GFMElementTypes.TABLE -> {
            TableNode(
                node = node,
                content = content,
                modifier = modifier,
                liveSuffix = liveSuffix,
                liveSuffixSourceOffset = liveSuffixSourceOffset,
            )
        }

        MarkdownTokenTypes.HORIZONTAL_RULE -> {
            StreamingBlockReveal(node = node) { revealModifier ->
                HorizontalDivider(
                    modifier = revealModifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    thickness = 0.5.dp
                )
            }
        }

        // 图片
        MarkdownElementTypes.IMAGE -> {
            val altText = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)?.getTextInNode(content) ?: ""
            val imageUrl =
                node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
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

        GFMElementTypes.INLINE_MATH -> {
            val formula = node.getTextInNode(content)
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

        GFMElementTypes.BLOCK_MATH -> {
            val formula = node.getTextInNode(content)
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

        MarkdownElementTypes.CODE_SPAN -> {
            val code = node.getTextInNode(content).trim('`')
            Text(
                text = code, fontFamily = FontFamily.Monospace, modifier = modifier
            )
        }

        MarkdownElementTypes.CODE_BLOCK -> {
            val code = node.getTextInNode(content)
            StreamingBlockReveal(node = node, modifier = modifier) { revealModifier ->
                Text(
                    text = code,
                    modifier = revealModifier,
                )
            }
        }

        // 代码块
        MarkdownElementTypes.CODE_FENCE -> {
            // 这里不能直接取CODE_FENCE_CONTENT的内容，因为首行indent没有包含在内
            // 因此，需要往上找到最后一个EOL元素，用它来作为代码块的起始offset
            val contentStartIndex = node.children.indexOfFirst { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
            if (contentStartIndex == -1) return
            val eolElement =
                node.children.subList(0, contentStartIndex).findLast { it.type == MarkdownTokenTypes.EOL } ?: return
            val codeContentStartOffset = eolElement.endOffset
            val codeContentEndOffset =
                node.children.findLast { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }?.endOffset ?: return
            val rawCode = content.substring(codeContentStartOffset, codeContentEndOffset)

            val language =
                node.findChildOfTypeRecursive(MarkdownTokenTypes.FENCE_LANG)?.getTextInNode(content) ?: "plaintext"
            val sourceOffsetBase = LocalMarkdownSourceOffsetBase.current
            val syntheticSuffixStart = LocalMarkdownSyntheticSuffixStart.current
            val endFence = node.findChildOfTypeRecursive(MarkdownTokenTypes.CODE_FENCE_END)
            val hasEnd = endFence != null && sourceOffsetBase + endFence.endOffset <= syntheticSuffixStart
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

        MarkdownTokenTypes.TEXT -> {
            val text = node.getTextInNode(content)
            Text(
                text = text,
                modifier = modifier,
            )
        }

        MarkdownElementTypes.HTML_BLOCK -> {
            val text = node.getTextInNode(content)
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
    node: ASTNode,
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
            if (child.type == MarkdownElementTypes.LIST_ITEM) {
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
    node: ASTNode,
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
            if (child.type == MarkdownElementTypes.LIST_ITEM) {
                val numberText =
                    child.findChildOfTypeRecursive(MarkdownTokenTypes.LIST_NUMBER)?.getTextInNode(content) ?: "$index. "
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
    node: ASTNode,
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
private fun separateContentAndLists(listItemNode: ASTNode): Pair<List<ASTNode>, List<ASTNode>> {
    val directContent = mutableListOf<ASTNode>()
    val nestedLists = mutableListOf<ASTNode>()
    listItemNode.children.fastForEach { child ->
        when (child.type) {
            MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST -> {
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
    node: ASTNode,
    content: String,
    trim: Boolean = false,
    onClickCitation: (String) -> Unit = {},
    modifier: Modifier,
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
        node.findChildOfTypeRecursive(MarkdownElementTypes.IMAGE, GFMElementTypes.BLOCK_MATH) != null
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
        node.findChildOfTypeRecursive(GFMElementTypes.INLINE_MATH) != null
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
    val revealController = LocalCharRevealController.current
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
        // Batch reveal (L4 decoupled): only the unparsed live suffix animates.
        // The settled prefix stays opaque; it already revealed earlier while
        // it was itself the live suffix.
        val combined = remember(staticAnnotated, liveSuffix, baseColor) {
            if (liveSuffix.isEmpty()) {
                staticAnnotated
            } else {
                buildAnnotatedString {
                    append(staticAnnotated)
                    append(liveSuffix.replace(BREAK_LINE_REGEX, "\n"))
                }
            }
        }
        // revealController != null marks the active streaming block; only then
        // is there a suffix to fade. liveSuffixSourceOffset changes once per
        // parse tick, so it is the batch key — a fresh Animatable per batch
        // drives the newly-arrived text from 0→1.
        val streamingTail = revealController != null && liveSuffix.isNotEmpty()
        val suffixAlpha = if (streamingTail) {
            val anim = remember(liveSuffixSourceOffset) { Animatable(0f) }
            LaunchedEffect(liveSuffixSourceOffset) {
                anim.animateTo(
                    1f,
                    animationSpec = tween(
                        durationMillis = STREAMING_BATCH_FADE_MS,
                        easing = LinearEasing,
                    ),
                )
            }
            anim.value
        } else {
            1f
        }
        val annotatedString = remember(combined, staticAnnotated.length, suffixAlpha, baseColor) {
            applyBatchRevealSuffix(
                combined = combined,
                staticLength = staticAnnotated.length,
                suffixProgress = suffixAlpha,
                baseColor = baseColor,
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
    }
}

@Composable
private fun TableNode(
    node: ASTNode,
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
        MarkdownBlock(content = content)
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
    node: ASTNode,
    content: String,
    sourceOffsetBase: Int,
    liveSuffix: String,
    liveSuffixSourceOffset: Int,
): MarkdownTableData? {
    val settledData = extractMarkdownTableData(node = node, content = content) ?: return null
    if (liveSuffix.isEmpty()) return settledData

    val absoluteTableEnd = sourceOffsetBase + node.endOffset
    if (liveSuffixSourceOffset != absoluteTableEnd) return settledData

    val tableText = node.getTextInNode(content) + liveSuffix
    val streamingTable = parser
        .buildMarkdownTreeFromString(tableText)
        .children
        .firstOrNull { it.type == GFMElementTypes.TABLE }
        ?: return settledData

    return extractMarkdownTableData(node = streamingTable, content = tableText) ?: settledData
}

internal fun extractMarkdownTableData(node: ASTNode, content: String): MarkdownTableData? {
    val headerNode = node.children.find { it.type == GFMElementTypes.HEADER }
    val rowNodes = node.children.filter { it.type == GFMElementTypes.ROW }
    val columnCount = headerNode?.children?.count { it.type == GFMTokenTypes.CELL } ?: 0
    if (columnCount == 0) return null

    val headerCells = headerNode?.children
        ?.filter { it.type == GFMTokenTypes.CELL }
        ?.map { it.getTextInNode(content).trim() }
        ?: emptyList()
    val rows = rowNodes.map { rowNode ->
        rowNode.children
            .filter { it.type == GFMTokenTypes.CELL }
            .map { it.getTextInNode(content).trim() }
    }
    return MarkdownTableData(
        columnCount = columnCount,
        headers = headerCells,
        rows = rows,
    )
}

/**
 * Walks a markdown [ASTNode] and appends its rendered content into the
 * receiver [AnnotatedString.Builder] — including spans for EMPH /
 * STRONG / STRIKETHROUGH / links / code / inline math.
 *
 */
internal fun AnnotatedString.Builder.appendMarkdownNodeContent(
    node: ASTNode,
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
        node.type == MarkdownTokenTypes.BLOCK_QUOTE -> {}

        node.type == GFMTokenTypes.GFM_AUTOLINK -> {
            val link = node.getTextInNode(content)
            withLink(openUrlLinkAnnotation(link, onClickUrl)) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(link)
                }
            }
        }

        node is LeafASTNode -> {
            val rawText = node.getTextInNode(content)
            val text = (if (trim) rawText.trim() else rawText)
                .replace(BREAK_LINE_REGEX, "\n")
            append(text)
        }

        node.type == MarkdownElementTypes.EMPH -> {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                node.children.trim(MarkdownTokenTypes.EMPH, 1).fastForEach {
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

        node.type == MarkdownElementTypes.STRONG -> {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                node.children.trim(MarkdownTokenTypes.EMPH, 2).fastForEach {
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

        node.type == GFMElementTypes.STRIKETHROUGH -> {
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                node.children.trim(GFMTokenTypes.TILDE, 2).fastForEach {
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

        node.type == MarkdownElementTypes.INLINE_LINK -> {
            val linkDest =
                node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
            val linkText = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)?.getTextInNode(content)
                ?.trim { it == '[' || it == ']' } ?: linkDest
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

        node.type == MarkdownElementTypes.AUTOLINK -> {
            val links = node.children.trim(MarkdownTokenTypes.LT, 1).trim(MarkdownTokenTypes.GT, 1)
            links.fastForEach { link ->
                withLink(openUrlLinkAnnotation(link.getTextInNode(content), onClickUrl)) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(link.getTextInNode(content))
                    }
                }
            }
        }

        node.type == MarkdownElementTypes.CODE_SPAN -> {
            val code = node.getTextInNode(content).trim('`')
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

        node.type == GFMElementTypes.INLINE_MATH -> {
            if (enableLatexRendering) {
                // formula as id
                val formula = node.getTextInNode(content)
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
                val formula = node.getTextInNode(content)
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
 * Per-arrival-batch streaming reveal layer.
 * Instead of mapping per-codepoint alpha back to source offsets, it styles
 * only one contiguous range — the live suffix appended after the
 * statically-parsed text. [staticLength] is the boundary between settled
 * text (always opaque) and the revealing suffix.
 *
 * Returns [combined] unchanged when there's nothing to fade (no usable
 * baseColor, suffix already fully revealed, or the boundary is out of range).
 */
internal fun applyBatchRevealSuffix(
    combined: AnnotatedString,
    staticLength: Int,
    suffixProgress: Float,
    baseColor: Color,
): AnnotatedString {
    if (baseColor == Color.Unspecified) return combined
    if (suffixProgress >= 1f) return combined
    if (staticLength < 0 || staticLength >= combined.length) return combined
    val progress = suffixProgress.coerceIn(0f, 1f)
    val codePointCount = combined.text
        .codePointCount(staticLength, combined.length)
        .coerceAtLeast(1)
    if (codePointCount > STREAMING_BATCH_STAGGER_MAX_CODEPOINTS) {
        val eased = easeStreamingReveal(progress)
        return buildAnnotatedString {
            append(combined)
            addStyle(
                style = streamingRevealSpanStyle(
                    baseColor = baseColor,
                    localProgress = progress,
                    eased = eased,
                ),
                start = staticLength,
                end = combined.length,
            )
        }
    }
    val staggerFraction = if (codePointCount <= 1) {
        0f
    } else {
        minOf(
            STREAMING_BATCH_CHAR_STAGGER_MS.toFloat() / STREAMING_BATCH_FADE_MS,
            STREAMING_BATCH_MAX_STAGGER_FRACTION / (codePointCount - 1),
        )
    }
    return buildAnnotatedString {
        append(combined)
        var offset = staticLength
        var index = 0
        while (offset < combined.length) {
            val codePoint = combined.text.codePointAt(offset)
            val nextOffset = offset + Character.charCount(codePoint)
            val delay = index * staggerFraction
            val localProgress = ((progress - delay) / (1f - delay))
                .coerceIn(0f, 1f)
            val eased = easeStreamingReveal(localProgress)
            addStyle(
                style = streamingRevealSpanStyle(
                    baseColor = baseColor,
                    localProgress = localProgress,
                    eased = eased,
                ),
                start = offset,
                end = nextOffset,
            )
            offset = nextOffset
            index++
        }
    }
}

private fun streamingRevealSpanStyle(
    baseColor: Color,
    localProgress: Float,
    eased: Float,
): SpanStyle {
    val alphaProgress = codexStreamingAlphaProgress(localProgress)
    val alpha = STREAMING_BATCH_REVEAL_START_ALPHA +
        (1f - STREAMING_BATCH_REVEAL_START_ALPHA) * alphaProgress
    return SpanStyle(
        color = baseColor.copy(alpha = alpha),
        baselineShift = BaselineShift(-STREAMING_BATCH_REVEAL_LIFT_EM * (1f - eased)),
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

private fun ASTNode.getTextInNode(text: String): String {
    return text.substring(startOffset, endOffset)
}

private fun ASTNode.getTextInNode(text: String, type: IElementType): String {
    var startOffset = -1
    var endOffset = -1
    children.fastForEach {
        if (it.type == type) {
            if (startOffset == -1) {
                startOffset = it.startOffset
            }
            endOffset = it.endOffset
        }
    }
    if (startOffset == -1 || endOffset == -1) {
        return ""
    }
    return text.substring(startOffset, endOffset)
}

private fun ASTNode.nextSibling(): ASTNode? {
    val brother = this.parent?.children ?: return null
    for (i in brother.indices) {
        if (brother[i] == this) {
            if (i + 1 < brother.size) {
                return brother[i + 1]
            }
        }
    }
    return null
}

private fun ASTNode.findChildOfTypeRecursive(vararg types: IElementType): ASTNode? {
    if (this.type in types) return this
    for (child in children) {
        val result = child.findChildOfTypeRecursive(*types)
        if (result != null) return result
    }
    return null
}

private fun ASTNode.traverseChildren(
    action: (ASTNode) -> Unit
) {
    children.fastForEach { child ->
        action(child)
        child.traverseChildren(action)
    }
}

private fun List<ASTNode>.trim(type: IElementType, size: Int): List<ASTNode> {
    if (this.isEmpty() || size <= 0) return this
    var start = 0
    var end = this.size
    // 从头裁剪
    var trimmed = 0
    while (start < end && trimmed < size && this[start].type == type) {
        start++
        trimmed++
    }
    // 从尾裁剪
    trimmed = 0
    while (end > start && trimmed < size && this[end - 1].type == type) {
        end--
        trimmed++
    }
    return this.subList(start, end)
}
