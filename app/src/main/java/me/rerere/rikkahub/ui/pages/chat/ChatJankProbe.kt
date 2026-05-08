package me.rerere.rikkahub.ui.pages.chat

import android.util.Log
import android.view.Choreographer
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.service.ConversationTimelineLoadState
import me.rerere.rikkahub.service.PendingUserMessage
import kotlin.math.abs

private const val ChatPerfTag = "AmberChatPerf"
private const val LongFrameMs = 48.0
private const val CriticalFrameMs = 96.0
private val TableHintRegex = Regex("""(?m)^\s*\|.+\|\s*$""")
private val CodeHintRegex = Regex("""```|`[^`\n]+`""")
private val LatexHintRegex = Regex("""\$\$|\\\(|\\\[|(?<!\$)\$(?!\$)""")
private val LinkHintRegex = Regex("""\[[^\]]+]\([^)]+\)|https?://|[A-Za-z0-9.-]+\.(com|net|org|io|ai|cn|dev|app)""")

@Composable
internal fun ChatJankProbe(
    state: LazyListState,
    messageNodes: List<MessageNode>,
    lazyItemMessageIndexes: List<Int?>,
    loading: Boolean,
    timelineLoadState: ConversationTimelineLoadState,
    pendingUserMessages: List<PendingUserMessage>,
) {
    if (!BuildConfig.DEBUG) return

    val stateRef = rememberUpdatedState(state)
    val nodesRef = rememberUpdatedState(messageNodes)
    val lazyItemMessageIndexesRef = rememberUpdatedState(lazyItemMessageIndexes)
    val loadingRef = rememberUpdatedState(loading)
    val timelineRef = rememberUpdatedState(timelineLoadState)
    val pendingRef = rememberUpdatedState(pendingUserMessages)

    DisposableEffect(Unit) {
        val choreographer = Choreographer.getInstance()
        var lastFrameNanos = 0L
        var lastIndex = 0
        var lastOffset = 0
        var frameCount = 0

        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frameCount += 1
                if (lastFrameNanos != 0L) {
                    val deltaMs = (frameTimeNanos - lastFrameNanos) / 1_000_000.0
                    if (deltaMs >= LongFrameMs && Log.isLoggable(ChatPerfTag, Log.DEBUG)) {
                        val lazyState = stateRef.value
                        val nodes = nodesRef.value
                        if (nodes.isEmpty()) {
                            lastFrameNanos = frameTimeNanos
                            lastIndex = lazyState.firstVisibleItemIndex
                            lastOffset = lazyState.firstVisibleItemScrollOffset
                            choreographer.postFrameCallback(this)
                            return
                        }
                        val layoutInfo = lazyState.layoutInfo
                        val itemMessageIndexes = lazyItemMessageIndexesRef.value
                        val firstIndex = lazyState.firstVisibleItemIndex
                        val firstOffset = lazyState.firstVisibleItemScrollOffset
                        val direction = scrollDirection(
                            index = firstIndex,
                            offset = firstOffset,
                            lastIndex = lastIndex,
                            lastOffset = lastOffset,
                        )
                        val timeline = timelineRef.value
                        val visibleIndexes = layoutInfo.visibleItemsInfo
                            .mapNotNull { itemMessageIndexes.getOrNull(it.index) }
                            .filter { it in nodes.indices }
                        val focusIndexes = visibleIndexes
                            .plus(
                                listOf(
                                    itemMessageIndexes.getOrNull(firstIndex - 1),
                                    itemMessageIndexes.getOrNull(firstIndex),
                                    itemMessageIndexes.getOrNull(firstIndex + 1),
                                ).filterNotNull()
                            )
                            .filter { it in nodes.indices }
                            .distinct()
                            .sorted()

                        val severity = if (deltaMs >= CriticalFrameMs) "CRITICAL" else "LONG"
                        Log.w(
                            ChatPerfTag,
                            buildString {
                                append(severity)
                                append(" frameMs=")
                                append("%.1f".format(deltaMs))
                                append(" frame=")
                                append(frameCount)
                                append(" dir=")
                                append(direction)
                                append(" scrolling=")
                                append(lazyState.isScrollInProgress)
                                append(" first=")
                                append(firstIndex)
                                append('@')
                                append(firstOffset)
                                append(" visibleRaw=")
                                append(layoutInfo.visibleItemsInfo.joinToString(prefix = "[", postfix = "]") {
                                    it.perfSummary()
                                })
                                append(" loaded=")
                                append(timeline.loadedNodeCount)
                                append('/')
                                append(timeline.totalNodeCount)
                                append(" oldest=")
                                append(timeline.oldestLoadedIndex)
                                append(" prefetch=")
                                append(timeline.prefetchingOlder)
                                append(" loading=")
                                append(loadingRef.value)
                                append(" pending=")
                                append(pendingRef.value.size)
                                append(" focus=")
                                append(focusIndexes.joinToString(prefix = "[", postfix = "]") { index ->
                                    nodes[index].perfSummary(index)
                                })
                            },
                        )
                    }
                }
                lastFrameNanos = frameTimeNanos
                lastIndex = stateRef.value.firstVisibleItemIndex
                lastOffset = stateRef.value.firstVisibleItemScrollOffset
                choreographer.postFrameCallback(this)
            }
        }

        choreographer.postFrameCallback(callback)
        onDispose {
            choreographer.removeFrameCallback(callback)
        }
    }
}

private fun scrollDirection(
    index: Int,
    offset: Int,
    lastIndex: Int,
    lastOffset: Int,
): String = when {
    index > lastIndex -> "down"
    index < lastIndex -> "up"
    offset > lastOffset -> "down"
    offset < lastOffset -> "up"
    abs(offset - lastOffset) < 2 -> "still"
    else -> "unknown"
}

private fun LazyListItemInfo.perfSummary(): String {
    val compactKey = key.toString()
        .replace('\n', ' ')
        .replace(',', ';')
        .take(96)
    return "$index:$offset:$size:$compactKey"
}

private fun MessageNode.perfSummary(index: Int): String {
    val message = runCatching { currentMessage }.getOrNull()
        ?: return "$index:<invalid>"
    val textParts = message.parts.filterIsInstance<UIMessagePart.Text>()
    val text = textParts.joinToString("\n") { it.text }
    val reasoningChars = message.parts.filterIsInstance<UIMessagePart.Reasoning>()
        .sumOf { it.reasoning.length }
    val toolParts = message.parts.filterIsInstance<UIMessagePart.Tool>()
    return buildString {
        append(index)
        append(':')
        append(message.role.name.lowercase())
        append(':')
        append(id.toString().take(8))
        append(":chars=")
        append(text.length)
        append(":parts=")
        append(message.parts.size)
        append(":tables=")
        append(TableHintRegex.findAll(text).count())
        append(":code=")
        append(CodeHintRegex.containsMatchIn(text))
        append(":latex=")
        append(LatexHintRegex.containsMatchIn(text))
        append(":link=")
        append(LinkHintRegex.containsMatchIn(text))
        append(":reason=")
        append(reasoningChars)
        append(":tools=")
        append(toolParts.size)
        append(":toolOut=")
        append(toolParts.sumOf { tool -> tool.output.size })
    }
}
