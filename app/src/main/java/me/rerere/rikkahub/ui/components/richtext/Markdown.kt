package me.rerere.rikkahub.ui.components.richtext

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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.ui.components.table.DataTable
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.utils.amberTraceMeasure
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.toDp
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.LeafASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import me.rerere.rikkahub.ui.components.richtext.nativebridge.MarkdownNativeSwitch
import me.rerere.rikkahub.ui.components.richtext.nativebridge.PackedAstReader
import java.util.LinkedHashMap
import kotlin.random.Random

/**
 * When false, markdown nodes use wrap-content width instead of fill-max-width.
 * Used by user message bubbles to allow adaptive width.
 */
internal val LocalMarkdownFillWidth = compositionLocalOf { true }

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
    // 先找出所有代码块的位置
    val codeBlocks = mutableListOf<IntRange>()
    CODE_BLOCK_REGEX.findAll(content).forEach { match ->
        codeBlocks.add(match.range)
    }

    // 检查位置是否在代码块内
    fun isInCodeBlock(position: Int): Boolean {
        return codeBlocks.any { range -> position in range }
    }

    // 替换行内公式 \( ... \) 到 $ ... $，但跳过代码块内的内容
    var result = INLINE_LATEX_REGEX.replace(content) { matchResult ->
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
)

internal data class MarkdownTopLevelBlockSnapshot(
    val key: String,
    val parseResult: MarkdownParseResult,
    val preserveParagraphBottomPadding: Boolean,
)

private const val MARKDOWN_PARSE_CACHE_VERSION = 1
private const val MARKDOWN_PARSE_CACHE_MAX_ENTRIES = 128
private const val MARKDOWN_PARSE_CACHE_MAX_CHARS = 1_200_000
private const val MARKDOWN_PERF_TAG = "AmberChatPerf"
private const val MARKDOWN_PARSE_HIT_LOG_MIN_CHARS = 600

// Tag used to mark leaf-text ranges in the static AnnotatedString that
// are eligible for per-frame reveal alpha. The annotation's value carries
// the leaf's absolute startOffset in the source content string so the
// overlay can map static positions back to CharRevealController offsets.
// See applyRevealOverlay below. internal so the parity test can pin it.
internal const val REVEAL_LEAF_TAG = "rikkahub.reveal.leaf"

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
    val astTree = parser.buildMarkdownTreeFromString(preprocessed)
    // Phase 2 Step 5: shadow-compare against native pulldown-cmark AST when
    // `markdownAst` is enabled. The renderer still consumes `astTree` (JVM
    // tree) — we don't swap the consumer because Markdown.kt is mid-migration
    // (see `feedback_rikkahub_rust_native_spike` memory: 12+ commits/month,
    // touching the renderer right now risks merge conflicts with active
    // streaming-render fixes). The shadow path gives us correctness telemetry
    // ahead of the eventual full swap.
    maybeShadowCompareNativeAst(preprocessed, astTree)
    return MarkdownParseResult(preprocessed, astTree, astTree.containsHtmlBlocks())
}

/**
 * Top-level structural compare. **Count-only** — the JVM ASTNode exposes
 * UTF-16 char offsets, pulldown-cmark exposes UTF-8 byte offsets; on a
 * CJK / emoji corpus (rikkahub's bread-and-butter) span comparison
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

private class StreamingMarkdownParseCache {
    private val lock = Any()
    private var stableRawPrefix = ""
    private var stableBlocks = emptyList<MarkdownTopLevelBlockSnapshot>()

    fun reset() = synchronized(lock) {
        stableRawPrefix = ""
        stableBlocks = emptyList()
    }

    fun parse(content: String): MarkdownParseResult = traceMarkdown("Amber Markdown parse streaming") {
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
            return@traceMarkdown activeParse.copy(stableTopLevelBlocks = blocks)
        }

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
        parsePreprocessedMarkdownUncached(activePreprocessed.substring(activeTailStart))
            .copy(stableTopLevelBlocks = nextStableBlocks)
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
    onClickCitation: (String) -> Unit = {}
) {
    val streamingParseCache = remember {
        StreamingMarkdownParseCache()
    }
    var (data, setData) = remember {
        mutableStateOf(
            if (streaming) {
                streamingParseCache.parse(content)
            } else {
                streamingParseCache.reset()
                MarkdownParseCache.getOrParse(content)
            }
        )
    }

    // 监听内容变化，重新解析AST树
    // 这里在后台线程解析AST树, 防止频繁更新的时候掉帧
    val updatedContent by rememberUpdatedState(content)
    val updatedStreaming by rememberUpdatedState(streaming)
    LaunchedEffect(Unit) {
        var lastParsedContent = content
        var lastParsedStreaming = streaming
        snapshotFlow { updatedContent to updatedStreaming }
            .distinctUntilChanged()
            .collectLatest { (latestContent, latestStreaming) ->
                if (latestContent == lastParsedContent && latestStreaming == lastParsedStreaming) {
                    return@collectLatest
                }
                try {
                    val parsed = withContext(Dispatchers.Default) {
                        if (latestStreaming) {
                            streamingParseCache.parse(latestContent)
                        } else {
                            streamingParseCache.reset()
                            MarkdownParseCache.getOrParse(latestContent)
                        }
                    }
                    lastParsedContent = latestContent
                    lastParsedStreaming = latestStreaming
                    setData(parsed)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    exception.printStackTrace()
                }
            }
    }

    val revealController = rememberCharRevealController(
        streaming = streaming,
        content = data.preprocessed,
    )

    TraceMarkdownComposable("Amber MarkdownBlock render") {
      CompositionLocalProvider(
          LocalMarkdownFillWidth provides fillWidth,
      ) {
        if (data.hasHtmlBlocks) {
            MarkdownNew(
                content = content,
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
                        children.lastOrNull()?.let { activeChild ->
                            key("active:${activeChild.type}:${activeChild.startOffset}") {
                                CompositionLocalProvider(
                                    LocalCharRevealController provides revealController,
                                ) {
                                    MarkdownNode(
                                        node = activeChild,
                                        content = data.preprocessed,
                                        modifier = nodeModifier,
                                        onClickCitation = onClickCitation,
                                    )
                                }
                            }
                        }
                    } else {
                        children.fastForEachIndexed { index, child ->
                            val childRevealController = if (index == children.lastIndex) {
                                revealController
                            } else {
                                null
                            }
                            CompositionLocalProvider(
                                LocalCharRevealController provides childRevealController,
                            ) {
                                MarkdownNode(
                                    node = child,
                                    content = data.preprocessed,
                                    modifier = nodeModifier,
                                    onClickCitation = onClickCitation,
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
private fun MarkdownNode(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    listLevel: Int = 0,
) {
    when (node.type) {
        // 文件根节点
        MarkdownElementTypes.MARKDOWN_FILE -> {
            node.children.fastForEach { child ->
                MarkdownNode(
                    node = child, content = content, modifier = modifier, onClickCitation = onClickCitation
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
                FlowRow(
                    modifier = modifier.fillWidthIf(LocalMarkdownFillWidth.current),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    node.children.fastForEach { node ->
                        if (node.type == MarkdownTokenTypes.ATX_CONTENT) {
                            Paragraph(
                                node = node,
                                content = content,
                                onClickCitation = onClickCitation,
                                modifier = modifier.padding(vertical = headingPadding),
                                trim = true,
                            )
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
                level = listLevel
            )
        }

        MarkdownElementTypes.ORDERED_LIST -> {
            OrderedListNode(
                node = node,
                content = content,
                modifier = modifier.padding(vertical = 4.dp),
                onClickCitation = onClickCitation,
                level = listLevel
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
                val borderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                val bgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                Column(
                    modifier = Modifier
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                color = bgColor, size = size
                            )
                            drawRect(
                                color = borderColor, size = Size(10f, size.height)
                            )
                        }
                        .padding(8.dp)) {
                    node.children.fastForEach { child ->
                        MarkdownNode(
                            node = child, content = content, onClickCitation = onClickCitation
                        )
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
                    MarkdownNode(
                        node = child, content = content, modifier = modifier, onClickCitation = onClickCitation
                    )
                }
            }
        }

        MarkdownElementTypes.STRONG -> {
            ProvideTextStyle(TextStyle(fontWeight = FontWeight.SemiBold)) {
                node.children.fastForEach { child ->
                    MarkdownNode(
                        node = child, content = content, modifier = modifier, onClickCitation = onClickCitation
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
            TableNode(node = node, content = content, modifier = modifier)
        }

        MarkdownTokenTypes.HORIZONTAL_RULE -> {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
        }

        // 图片
        MarkdownElementTypes.IMAGE -> {
            val altText = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)?.getTextInNode(content) ?: ""
            val imageUrl =
                node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
            Column(
                modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 这里可以使用Coil等图片加载库加载图片
                ZoomableAsyncImage(
                    model = imageUrl,
                    contentDescription = altText,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .widthIn(min = 120.dp)
                        .heightIn(min = 120.dp),
                )
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
            if (enableLatexRendering) {
                MathBlock(
                    formula, modifier = modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            } else {
                Text(
                    text = formula,
                    fontFamily = FontFamily.Monospace,
                    modifier = modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
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
            Text(
                text = code,
                modifier = modifier,
            )
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
            val code = content.substring(
                codeContentStartOffset, codeContentEndOffset
            ).trimIndent()

            val language =
                node.findChildOfTypeRecursive(MarkdownTokenTypes.FENCE_LANG)?.getTextInNode(content) ?: "plaintext"
            val hasEnd = node.findChildOfTypeRecursive(MarkdownTokenTypes.CODE_FENCE_END) != null

            // `search-images` is a virtual code language emitted by
            // SearchImageInjectorTransformer — each line inside the fence is an image
            // URL. SearchImageBlock applies uniform sizing rules (multi-image strip
            // / single full-width / failed image collapses to zero height) so the
            // entire visual stays consistent regardless of which search service
            // produced the image. Other fence languages fall through to syntax
            // highlighting unchanged.
            if (language == "search-images") {
                SearchImageBlock(
                    urls = code.split('\n'),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                return
            }

            HighlightCodeBlock(
                code = code,
                language = language,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .fillMaxWidth(),
                completeCodeBlock = hasEnd
            )
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
            SimpleHtmlBlock(
                html = text, modifier = modifier
            )
        }

        // 其他类型的节点，递归处理子节点
        else -> {
            // 递归处理其他节点的子节点
            node.children.fastForEach { child ->
                MarkdownNode(
                    node = child, content = content, modifier = modifier, onClickCitation = onClickCitation
                )
            }
        }
    }
}

@Composable
private fun UnorderedListNode(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    level: Int = 0
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
        node.children.fastForEach { child ->
            if (child.type == MarkdownElementTypes.LIST_ITEM) {
                ListItemNode(
                    node = child,
                    content = content,
                    bulletText = bulletStyle,
                    onClickCitation = onClickCitation,
                    level = level
                )
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
    level: Int = 0
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
                ListItemNode(
                    node = child,
                    content = content,
                    bulletText = numberText,
                    onClickCitation = onClickCitation,
                    level = level
                )
                index++
            }
        }
    }
}

@Composable
private fun ListItemNode(
    node: ASTNode, content: String, bulletText: String, onClickCitation: (String) -> Unit = {}, level: Int
) {
    Column(
        modifier = Modifier.fillWidthIf(LocalMarkdownFillWidth.current)
    ) {
        // 分离列表项的直接内容和嵌套列表
        val (directContent, nestedLists) = separateContentAndLists(node)
        // directContent 渲染处理
        if (directContent.isNotEmpty()) {
            Row(
                modifier = Modifier.fillWidthIf(LocalMarkdownFillWidth.current)
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
                        MarkdownNode(
                            node = contentChild,
                            content = content,
                            modifier = Modifier.fillWidthIf(LocalMarkdownFillWidth.current),
                            onClickCitation = onClickCitation,
                            listLevel = level,
                        )
                    }
                }
            }
        }
        // nestedLists 渲染处理
        nestedLists.fastForEach { nestedList ->
            MarkdownNode(
                node = nestedList, content = content, onClickCitation = onClickCitation, listLevel = level + 1 // 增加层级
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
) {
    // dumpAst(node, content)
    if (node.findChildOfTypeRecursive(MarkdownElementTypes.IMAGE, GFMElementTypes.BLOCK_MATH) != null) {
        FlowRow(modifier = modifier.fillWidthIf(LocalMarkdownFillWidth.current)) {
            node.children.fastForEach { child ->
                MarkdownNode(
                    node = child, content = content, onClickCitation = onClickCitation
                )
            }
        }
        return
    }

    val colorScheme = MaterialTheme.colorScheme
    // Lifecycle pinned to this Paragraph composable; populated lazily
    // by appendMarkdownNodeContent during the staticAnnotated build
    // (INLINE_LINK citation + INLINE_MATH formula keys). Keep the same
    // remember()-no-key form so the map persists across recompositions
    // alongside staticAnnotated's lambda re-runs — they're created and
    // disposed together as part of the same composable scope.
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

    // Streaming-aware text build, split into two layers so per-frame work
    // is bounded by the active reveal window instead of the whole paragraph:
    //   - staticAnnotated: built once per (content, theme, AST) tuple. Marks
    //     fade-eligible leaves with REVEAL_LEAF_TAG annotations carrying the
    //     leaf's source startOffset.
    //   - annotatedString:  if the controller has active reveal entries,
    //     layer per-codepoint alpha on top of staticAnnotated via
    //     applyRevealOverlay; otherwise pass staticAnnotated through.
    // Previously a single remember(...) keyed on revealClock rebuilt the
    // whole AST every frame at 60 Hz — that contended with LazyColumn
    // scroll measure/layout under concurrent tool calls.
    val revealController = LocalCharRevealController.current
    val baseColor = LocalContentColor.current
    val revealClock = revealController?.nowNanos ?: 0L

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
                    )
                }
            }
        }

        // Cache the REVEAL_LEAF_TAG range list once per static AnnotatedString.
        // getStringAnnotations(tag, ...) walks every annotation in the doc to
        // tag-filter, including any UrlAnnotation / InlineContent annotations
        // pushed by INLINE_LINK / INLINE_MATH. For a paragraph with dozens of
        // citations + formulas that's O(N) per frame — exactly the kind of
        // per-frame regression this refactor exists to prevent.
        val revealRanges = remember(staticAnnotated) {
            staticAnnotated.getStringAnnotations(REVEAL_LEAF_TAG, 0, staticAnnotated.length)
        }

        val annotatedString = remember(
            staticAnnotated,
            revealRanges,
            revealController,
            revealClock,
            baseColor,
        ) {
            if (
                revealController != null &&
                revealController.hasActiveReveals() &&
                revealRanges.isNotEmpty()
            ) {
                applyRevealOverlay(staticAnnotated, revealRanges, revealController, baseColor)
            } else {
                staticAnnotated
            }
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
private fun TableNode(node: ASTNode, content: String, modifier: Modifier = Modifier) {
    val tableData = remember(node, content) {
        traceMarkdown("Amber Markdown table extract") {
            extractMarkdownTableData(node = node, content = content)
        }
    } ?: return

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
        DataTable(
            headers = headers,
            rows = rowComposables,
            modifier = modifier
                .padding(vertical = 8.dp)
                .amberTraceMeasure("Amber DataTable measure"),
            columnMinWidths = List(tableData.columnCount) { 80.dp },
            columnMaxWidths = List(tableData.columnCount) { 200.dp },
        )
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
            // B1 char-reveal: mark the leaf as fade-eligible iff its text is
            // byte-for-byte aligned with the AST source (no trim, no
            // <br>-collapse) AND we have a usable baseColor to modulate.
            // The annotation carries the leaf's absolute startOffset so
            // applyRevealOverlay can map static positions back to the
            // controller's content-offset space. baseColor=Unspecified or
            // trimmed/collapsed text falls through to a bulk append — the
            // reveal effect requires color modulation and a 1:1 offset
            // map, both of which fail in those cases.
            val canFade = baseColor != Color.Unspecified && !trim && text == rawText
            if (canFade) {
                pushStringAnnotation(REVEAL_LEAF_TAG, node.startOffset.toString())
                append(text)
                pop()
            } else {
                append(text)
            }
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
                    )
                }
            }
        }

        node.type == MarkdownElementTypes.INLINE_LINK -> {
            val linkDest =
                node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
            val linkText = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)?.getTextInNode(content)
                ?.trim { it == '[' || it == ']' } ?: linkDest
            if (linkText.startsWith("citation,")) {
                // 如果是引用，则特殊处理
                val domain = linkText.substringAfter("citation,")
                val id = linkDest
                if (id.length == 6) {
                    inlineContents.putIfAbsent(
                        "citation:$linkDest", InlineTextContent(
                            placeholder = Placeholder(
                                width = (domain.length * 7).sp,
                                height = 1.em,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                            ), children = {
                                Box(
                                    modifier = Modifier
                                        .clickable {
                                            onClickCitation(id.trim())
                                        }
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                        .background(colorScheme.tertiaryContainer.copy(0.2f)),
                                    contentAlignment = Alignment.Center) {
                                    Text(
                                        text = domain,
                                        modifier = Modifier.wrapContentSize(),
                                        style = TextStyle(
                                            fontSize = 10.sp,
                                            lineHeight = 10.sp,
                                            fontFamily = JetbrainsMono,
                                            color = colorScheme.onTertiaryContainer,
                                            fontWeight = FontWeight.Thin
                                        ),
                                    )
                                }
                            })
                    )
                    appendInlineContent("citation:$linkDest")
                }
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
                )
            }
        }
    }
}

/**
 * Frame-local alpha overlay for the static [AnnotatedString] produced by
 * [appendMarkdownNodeContent]. Caller passes the pre-cached
 * [REVEAL_LEAF_TAG] range list (caching avoids per-frame
 * getStringAnnotations tag-filter cost — see the [remember] in
 * [Paragraph]); for each range, computes per-codepoint alpha from
 * [revealController] and layers a translucent color [SpanStyle] on top
 * of the existing styling.
 *
 * Cost is O(unrevealed codepoints across all leaves), bounded by
 * [CharRevealController]'s BACKLOG_DEGRADE (≈ 80). Skip-stable-prefix is
 * applied per leaf using [CharRevealController.stableOffsetExclusive].
 *
 * Returns [static] unchanged when there's no usable [baseColor] or
 * [ranges] is empty — preserves Color.Unspecified parity with the
 * pre-refactor leaf-text fast path.
 */
internal fun applyRevealOverlay(
    static: AnnotatedString,
    ranges: List<AnnotatedString.Range<String>>,
    revealController: CharRevealController,
    baseColor: Color,
): AnnotatedString {
    if (baseColor == Color.Unspecified) return static
    if (ranges.isEmpty()) return static
    return buildAnnotatedString {
        append(static)
        ranges.fastForEach { range ->
            val baseOffset = range.item.toIntOrNull() ?: return@fastForEach
            val rangeLen = range.end - range.start
            val stableRel = (revealController.stableOffsetExclusive() - baseOffset)
                .coerceAtLeast(0)
            if (stableRel >= rangeLen) return@fastForEach
            var i = range.start + stableRel
            val rangeEnd = range.end
            while (i < rangeEnd) {
                val cp = static.text.codePointAt(i)
                val cpLen = Character.charCount(cp)
                val alpha = revealController
                    .alphaAt(baseOffset + (i - range.start))
                    .coerceIn(0f, 1f)
                if (alpha < 1f) {
                    addStyle(
                        style = SpanStyle(color = baseColor.copy(alpha = alpha)),
                        start = i,
                        end = i + cpLen,
                    )
                }
                i += cpLen
            }
        }
    }
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
