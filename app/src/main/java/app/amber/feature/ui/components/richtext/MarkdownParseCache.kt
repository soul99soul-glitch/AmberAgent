package app.amber.feature.ui.components.richtext

import android.os.Trace
import android.util.Log
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import app.amber.agent.BuildConfig
import app.amber.feature.ui.utils.ChatPerfTrace
import app.amber.core.agent.utils.markdown.CjkCompatibleGfmFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import app.amber.feature.ui.components.richtext.tree.JvmMdNode
import app.amber.feature.ui.components.richtext.tree.MdNode
import app.amber.feature.ui.components.richtext.tree.MdNodeType
import app.amber.feature.ui.components.richtext.tree.TableAlign
import app.amber.feature.ui.components.richtext.tree.nativeMdTreeOrNull
import app.amber.feature.ui.components.richtext.nativebridge.MarkdownNativeSwitch
import app.amber.agent.ui.components.richtext.nativebridge.MarkdownParserNative
import app.amber.agent.ui.components.richtext.nativebridge.PackedAstReader
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

private val flavour by lazy {
    CjkCompatibleGfmFlavourDescriptor(
        makeHttpsAutoLinks = true, useSafeLinks = true
    )
}

internal val markdownJvmParser by lazy {
    MarkdownParser(flavour)
}

internal data class MarkdownParseResult(
    val preprocessed: String,
    val tree: MdNode,
    val hasHtmlBlocks: Boolean,
    val isPlainTextFallback: Boolean = false,
    /**
     * Invisible equal-measurement placeholder used on a cache miss while the
     * real tree is prepared off the main thread. Never shown as raw markup.
     */
    val isPendingPlaceholder: Boolean = false,
    val stableTopLevelBlocks: List<MarkdownTopLevelBlockSnapshot> = emptyList(),
    val activeBaseOffset: Int = 0,
    val syntheticSuffixStart: Int = Int.MAX_VALUE,
)

internal data class MarkdownTopLevelBlockSnapshot(
    val key: String,
    val parseResult: MarkdownParseResult,
    val preserveParagraphBottomPadding: Boolean,
)

internal class EmptyMarkdownRootNode(
    override val endOffset: Int,
) : MdNode {
    override val type: MdNodeType = MdNodeType.Root
    override val startOffset: Int = 0
    override val children: List<MdNode> = emptyList()
    override val parent: MdNode? = null
    override val headingLevel: Int? = null
    override val codeLang: String? = null
    override val linkHref: String? = null
    override val linkTitle: String? = null
    override val imageSrc: String? = null
    override val taskChecked: Boolean? = null
    override val listStart: Long? = null
    override val tableAlignments: List<TableAlign>? = null
    override val isFencedCode: Boolean = false
    override val codeFenceEndOffset: Int? = null
    override val codeFenceContentRange: IntRange? = null
    override val contentChildren: List<MdNode> = emptyList()
    override val isAutolink: Boolean = false
    override val linkLabel: String? = null
    override val linkInnerText: String? = null
    override val isBlockquoteMarker: Boolean = false

    override fun nextSibling(): MdNode? = null

    override fun findChildOfTypeRecursive(vararg types: MdNodeType): MdNode? {
        return if (type in types) this else null
    }
}

/** Temporary, lossless UI fallback; never inserted into the parse cache. */
internal fun plainMarkdownSnapshot(content: String): MarkdownParseResult =
    MarkdownParseResult(content, EmptyMarkdownRootNode(content.length), false, isPlainTextFallback = true)

/**
 * Cache-miss placeholder that keeps the source's layout size but is not
 * user-visible and contributes no semantics. Never inserted into the parse cache.
 */
internal fun pendingMarkdownSnapshot(content: String): MarkdownParseResult =
    MarkdownParseResult(
        preprocessed = content,
        tree = EmptyMarkdownRootNode(content.length),
        hasHtmlBlocks = false,
        isPlainTextFallback = true,
        isPendingPlaceholder = true,
    )

private const val MARKDOWN_PARSE_CACHE_VERSION = 1
private const val MARKDOWN_PARSE_CACHE_MAX_ENTRIES = 128
internal const val MARKDOWN_PARSE_CACHE_MAX_CHARS = 1_200_000
private const val MARKDOWN_PERF_TAG = "AmberChatPerf"
private const val MARKDOWN_PARSE_HIT_LOG_MIN_CHARS = 600

private data class MarkdownParseCacheKey(
    val content: String,
    val contentHash: Int,
    val version: Int,
    val preprocessed: Boolean,
)

internal object MarkdownParseCache {
    private val lock = Any()
    private var totalChars = 0
    private var hits = 0
    private var misses = 0
    private val inFlight = HashMap<MarkdownParseCacheKey, CompletableFuture<MarkdownParseResult>>()
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
        val cacheKey = key(content, preprocessed)
        val pending: CompletableFuture<MarkdownParseResult>
        val parseHere: Boolean
        synchronized(lock) {
            entries[cacheKey]?.let {
                hits++
                logCacheLocked("hit", content.length, elapsedMs = null)
                return it
            }
            val current = inFlight[cacheKey]
            if (current != null) {
                pending = current
                parseHere = false
            } else {
                pending = CompletableFuture()
                inFlight[cacheKey] = pending
                misses++
                parseHere = true
            }
        }
        if (!parseHere) {
            val result = try {
                pending.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
            synchronized(lock) {
                hits++
                logCacheLocked("hit-in-flight", content.length, elapsedMs = null)
            }
            return result
        }
        try {
            val startedAt = System.nanoTime()
            val parsed = traceMarkdown(section) {
                parser(content)
            }
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000.0
            val elapsedMs = if (BuildConfig.DEBUG || BuildConfig.CHAT_PERF_TRACE) elapsed else null
            ChatPerfTrace.recordMarkdownParse(elapsed)
            synchronized(lock) {
                if (content.length > MARKDOWN_PARSE_CACHE_MAX_CHARS) {
                    logCacheLocked("oversize-skip", content.length, elapsedMs)
                } else {
                    entries[cacheKey] = parsed
                    totalChars += content.length
                    trimToBudgetLocked()
                    logCacheLocked("miss", content.length, elapsedMs)
                }
            }
            pending.complete(parsed)
            return parsed
        } catch (failure: Throwable) {
            pending.completeExceptionally(failure)
            throw failure
        } finally {
            synchronized(lock) {
                inFlight.remove(cacheKey, pending)
            }
        }
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

internal inline fun <T> traceMarkdown(section: String, block: () -> T): T {
    val tracing = (BuildConfig.DEBUG || BuildConfig.CHAT_PERF_TRACE) && runCatching {
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

private fun MdNode.containsHtmlBlocks(): Boolean {
    if (type == MdNodeType.HtmlBlock) return true
    return children.any { it.containsHtmlBlocks() }
}

internal fun parsePreprocessedMarkdownUncached(preprocessed: String): MarkdownParseResult {
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
    val astTree = markdownJvmParser.buildMarkdownTreeFromString(preprocessed)
    // Rule 1: the ONLY place a JetBrains type crosses into the MdNode model.
    // Everything downstream consumes the parser-agnostic interface.
    val tree = JvmMdNode(astTree, preprocessed, null)
    return MarkdownParseResult(preprocessed, tree, tree.containsHtmlBlocks())
}

internal fun parseMarkdownUncached(content: String): MarkdownParseResult {
    return parsePreprocessedMarkdownUncached(preprocessMarkdownContent(content))
}

/** Deterministic cacheability: oversize input is rendered by the lossless raw fallback. */
internal fun isMarkdownContentCacheable(content: String): Boolean =
    content.length <= MARKDOWN_PARSE_CACHE_MAX_CHARS

internal enum class MarkdownPreparationOutcome {
    /** The parsed result is now in the bounded LRU. */
    PREPARED,

    /** Deterministically outside the cache budget; callers must not retry. */
    UNCACHEABLE,

    /** Parse/store failed once; callers may retry a bounded number of times. */
    FAILED,
}

/**
 * Parse on the calling (background) thread and report whether the result is
 * actually retrievable from the bounded cache. A result that is parsed but
 * rejected by the entry/character budget is [MarkdownPreparationOutcome.UNCACHEABLE]
 * so the owner never waits for a revision that can never arrive.
 */
internal fun prepareMarkdownContent(content: String): MarkdownPreparationOutcome {
    if (content.isBlank() || !isMarkdownContentCacheable(content)) {
        return MarkdownPreparationOutcome.UNCACHEABLE
    }
    return try {
        MarkdownParseCache.getOrParse(content)
        if (MarkdownParseCache.get(content) != null) {
            MarkdownPreparationOutcome.PREPARED
        } else {
            // Parsed, then rejected or immediately evicted by the char budget.
            MarkdownPreparationOutcome.UNCACHEABLE
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        MarkdownPreparationOutcome.FAILED
    }
}

/**
 * Legacy synchronous warm-up kept for callers that only want best effort.
 * New preparation queues must use [prepareMarkdownContent] so an oversized or
 * rejected result is reported instead of being retried forever.
 */
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

/**
 * Test-only seam (TD.Rust.1a Phase 3-B parity rig). Builds the JVM (JetBrains) tree
 * over the EXACT [rawText] given — bypassing [preprocessMarkdownContent] — so the produced
 * [MarkdownParseResult.tree] describes the same raw input the golden `.pmda` blobs were
 * generated from. With all native flags off (the unit-test default), this runs the
 * pure JetBrains parse path. See MarkdownTreeParityTest.
 */
@androidx.annotation.VisibleForTesting
internal fun parseRawMarkdownForParityTest(rawText: String): MarkdownParseResult =
    parsePreprocessedMarkdownUncached(rawText)
