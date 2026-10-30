package app.amber.feature.ui.components.richtext

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext
import app.amber.agent.BuildConfig
import app.amber.feature.ui.utils.amberTraceMeasure
import app.amber.core.utils.toDp

/**
 * TD.Rust.1+ streaming parse throttle. Streaming tokens arrive every ~30-60ms
 * from the AI provider; a full markdown re-parse on each tick (typically
 * 5-30ms for long messages) burns CPU and contests the composition thread.
 * Sampling at 200ms cuts the parse rate from ~20/sec to 5/sec without making
 * the displayed text feel lagged — the streaming display buffer still
 * advances every frame; only the AST refresh slows.
 */
private const val MARKDOWN_STREAMING_PARSE_THROTTLE_MS = 120L

@Composable
internal fun MarkdownBlockLegacy(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    fillWidth: Boolean = true,
    streaming: Boolean = false,
    deferStreamingParse: Boolean = false,
    onStreamingVisibleFrame: (() -> Unit)? = null,
    onStreamingVisualActiveChange: ((Boolean) -> Unit)? = null,
    onClickCitation: (String) -> Unit = {}
) {
    // Temporary stream-freeze probe (M0.2): logs the raw content length this
    // MarkdownBlock instance composed with during streaming. Remove with fix.
    if (BuildConfig.DEBUG && streaming) {
        val lastLoggedMdLen = remember { intArrayOf(-1) }
        SideEffect {
            if (lastLoggedMdLen[0] != content.length) {
                lastLoggedMdLen[0] = content.length
                Log.d(
                    "AmberStreamFreeze",
                    "mdBlock len=${content.length} defer=$deferStreamingParse",
                )
            }
        }
    }
    // Settled history (the overwhelmingly common path when opening a saved
    // conversation) needs none of the streaming display buffer, presentation
    // gate, frame loop, or mutation collector below. A cache hit renders the
    // parsed tree directly; a cache miss still falls through to the existing
    // placeholder/background parser path so nothing is shown as raw markup.
    val sawStreaming = remember { booleanArrayOf(streaming) }
    if (streaming) sawStreaming[0] = true
    val settledParse = remember(content) { cachedMarkdownParseResult(content) }
    if (!streaming && !deferStreamingParse && !sawStreaming[0] && settledParse != null) {
        MarkdownBlockRender(
            data = settledParse,
            renderContent = content,
            modifier = modifier,
            style = style,
            fillWidth = fillWidth,
            onClickCitation = onClickCitation,
            streamingLiveSuffix = EMPTY_STREAMING_LIVE_SUFFIX,
            streamingTailActive = null,
            streamingMotionScope = null,
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
    val presentationInputActive = streaming || displayDrainingAfterStream
    val presentation = rememberStreamingMarkdownPresentation(
        content = renderContent,
        inputActive = presentationInputActive,
    )
    val semanticContent = presentation.semanticContent
    val parseAsStreamingState = presentationInputActive || presentation.motionActive
    ReportStreamingVisualActive(
        active = parseAsStreamingState,
        onActiveChange = onStreamingVisualActiveChange,
    )
    val streamingParseCache = remember {
        StreamingMarkdownParseCache()
    }
    val parseDispatcher = LocalMarkdownParseDispatcher.current
    // Cache hits and short cache-miss content are ready on the first frame.
    // Longer cacheable content uses an invisible placeholder; oversize
    // uncacheable content keeps the visible lossless raw fallback.
    val initialParse = remember { cachedMarkdownParseResult(semanticContent) }
    val syncParseOnMiss = !parseAsStreamingState && markdownAllowsSyncParse(semanticContent)
    var (data, setData) = remember {
        mutableStateOf(
            when {
                initialParse != null -> initialParse
                parseAsStreamingState -> plainMarkdownSnapshot(semanticContent)
                syncParseOnMiss -> MarkdownParseCache.getOrParse(semanticContent)
                isMarkdownContentCacheable(semanticContent) -> pendingMarkdownSnapshot(semanticContent)
                else -> plainMarkdownSnapshot(semanticContent)
            }
        )
    }

    // 监听内容变化，重新解析AST树
    // 这里在后台线程解析AST树, 防止频繁更新的时候掉帧
    //
    // TD.Rust.1+ streaming parse throttle (review #8): on streaming content,
    // allow one AST refresh roughly every MARKDOWN_STREAMING_PARSE_THROTTLE_MS
    // so token bursts do not re-parse on every frame. The visible text still
    // advances continuously via rememberStreamingDisplayText; only the AST
    // refresh rate is capped.
    val updatedContent by rememberUpdatedState(semanticContent)
    val updatedParseAsStreaming by rememberUpdatedState(parseAsStreamingState)
    val updatedDeferStreamingParse by rememberUpdatedState(deferStreamingParse)
    val updatedOnStreamingVisibleFrame by rememberUpdatedState(onStreamingVisibleFrame)
    val streamingTailActive = streamingTailActiveWhen(
        streaming = parseAsStreamingState,
    )
    val streamingMotionActive = parseAsStreamingState
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
    )
    val pendingProbe = LocalMarkdownPendingProbe.current
    SideEffect {
        StreamingRenderProbe.liveSuffixLength = streamingLiveSuffix.text.length
        if (!parseAsStreamingState) {
            StreamingRenderProbe.resetStreamingDiagnostics()
        }
        pendingProbe?.invoke(data.isPendingPlaceholder)
    }
    LaunchedEffect(Unit) {
        var lastParsedContent: String? = when {
            initialParse != null || syncParseOnMiss -> semanticContent
            else -> null
        }
        var lastParsedStreaming = parseAsStreamingState
        var lastDeferred = deferStreamingParse
        var lastStreamingParseAtMs = 0L
        snapshotFlow {
            Triple(
                updatedContent,
                updatedParseAsStreaming,
                updatedDeferStreamingParse,
            )
        }
            .distinctUntilChanged()
            .transform { triple ->
                val parseAsStreaming = triple.second
                if (!parseAsStreaming) {
                    lastStreamingParseAtMs = 0L
                    emit(triple)
                    return@transform
                }
                val nowMs = SystemClock.uptimeMillis()
                if (
                    lastStreamingParseAtMs == 0L ||
                    nowMs - lastStreamingParseAtMs >= MARKDOWN_STREAMING_PARSE_THROTTLE_MS
                ) {
                    lastStreamingParseAtMs = nowMs
                    emit(triple)
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
                    val parsed = withContext(parseDispatcher) {
                        if (parseAsStreaming) {
                            streamingParseCache.parse(content = latestContent)
                        } else {
                            // finalParse preserves the streaming-phase key structure
                            // (stable blocks unchanged, active tail keys stay "active:…")
                            // so Compose reuses warm compositions — no rebuild, no
                            // height collapse, no viewport jump.
                            val result = streamingParseCache.finalParse(latestContent)
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d(
                                    "AmberChatScroll",
                                    "[md.finalize] stableBlocks=${result.stableTopLevelBlocks.size} " +
                                        "keyed=${result.stableTopLevelBlocks.isNotEmpty()} " +
                                        "contentLen=${latestContent.length}"
                                )
                            }
                            result
                        }
                    }
                    lastParsedContent = latestContent
                    lastParsedStreaming = parseAsStreaming
                    lastDeferred = latestDeferStreamingParse
                    setData(parsed)
                    if (!parseAsStreaming) {
                        updatedOnStreamingVisibleFrame?.invoke()
                    }
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

    MarkdownBlockRender(
        data = data,
        renderContent = renderContent,
        modifier = modifier,
        style = style,
        fillWidth = fillWidth,
        onClickCitation = onClickCitation,
        streamingLiveSuffix = streamingLiveSuffix,
        streamingTailActive = streamingTailActive,
        streamingMotionScope = streamingMotionScope,
    )
}

@Composable
private fun MarkdownBlockRender(
    data: MarkdownParseResult,
    renderContent: String,
    modifier: Modifier,
    style: TextStyle,
    fillWidth: Boolean,
    onClickCitation: (String) -> Unit,
    streamingLiveSuffix: StreamingLiveSuffix,
    streamingTailActive: StreamingTailActive?,
    streamingMotionScope: StreamingMarkdownMotionScope?,
) {
    TraceMarkdownComposable("Amber MarkdownBlock render") {
      CompositionLocalProvider(
          LocalMarkdownFillWidth provides fillWidth,
      ) {
        if (data.isPlainTextFallback) {
            androidx.compose.material3.Text(
                text = renderContent,
                modifier = modifier
                    .padding(start = 4.dp)
                    .then(
                        if (data.isPendingPlaceholder) {
                            Modifier.clearAndSetSemantics { }
                        } else {
                            Modifier
                        }
                    ),
                style = style,
                color = if (data.isPendingPlaceholder) Color.Transparent else Color.Unspecified,
            )
        } else if (data.hasHtmlBlocks) {
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
                    // Streamdown-style rendering: EVERY frame (streaming tick,
                    // final freeze, settled full parse) renders through the same
                    // stable + active structure with the same key schemes, so
                    // transitions (empty ↔ non-empty stable list, cache reset,
                    // streaming → settled) never re-key existing blocks. The
                    // stable list being empty simply means all children are
                    // active — identical code path, zero branch flip.
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
