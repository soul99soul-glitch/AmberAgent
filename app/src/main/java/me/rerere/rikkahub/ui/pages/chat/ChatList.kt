package me.rerere.rikkahub.ui.pages.chat

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.CursorPointer01
import com.composables.icons.lucide.ArrowDownToLine
import com.composables.icons.lucide.ArrowUpToLine
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Cancel01
import android.util.Log
import me.rerere.rikkahub.BuildConfig
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalScrollCaptureInProgress
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.context.ConversationCompact
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ConversationTimelineLoadState
import me.rerere.rikkahub.service.PendingUserMessage
import me.rerere.rikkahub.service.PendingUserMessageMode
import me.rerere.rikkahub.service.previewText
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.components.message.ChatMessageVirtualItem
import me.rerere.rikkahub.ui.components.message.ChatMessageVirtualItemContent
import me.rerere.rikkahub.ui.components.message.buildChatMessageVirtualItems
import me.rerere.rikkahub.ui.components.debug.StreamProfilerOverlay
import me.rerere.rikkahub.ui.components.message.chatMessageVirtualizationPrewarmTexts
import me.rerere.rikkahub.ui.components.richtext.prewarmMarkdownContent
import me.rerere.rikkahub.ui.components.ui.ErrorCardsDisplay
import me.rerere.rikkahub.ui.components.ui.ListSelectableItem
import me.rerere.rikkahub.ui.components.ui.PigLoadingIndicator
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.components.ui.WorkspaceSearchField
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.hooks.ImeLazyListAutoScroller
import me.rerere.rikkahub.utils.plus
import kotlin.uuid.Uuid

private const val TAG = "ChatList"
// M0.1 diagnostic: trace timeline follow / scroll state machine to find why
// auto-scroll occasionally misses the tail of streaming responses. Remove
// after root cause identified.
private const val SCROLL_TAG = "ChatScroll"
private const val LoadingIndicatorKey = "LoadingIndicator"
private const val ScrollBottomKey = "ScrollBottomKey"
private val TimelineHorizontalPadding = 16.dp

// 2026-05-15 (1.9.7): top-level Regex avoids recompiling the pattern on every
// recomposition of ContextCompactInProgressMarker. The streaming summary flow
// can update at ~30Hz; per-tick Regex compile is a measurable frame-rate hit
// on mid-tier devices.
private val COMPACT_SUMMARY_WHITESPACE_RE = Regex("\\s+")

/**
 * Slice a ConversationCompact.summary to a ≤80-char one-line preview suitable
 * for the under-divider label on [ContextCompactMarker]. Strips the structured
 * JSON body (everything from the first `{`), collapses whitespace, ellipsises
 * when too long. Returns null when there's no usable prose preamble — caller
 * should hide the preview line entirely in that case (don't render an empty
 * subtitle).
 */
private fun summaryPreviewOf(compact: ConversationCompact): String? {
    val raw = compact.summary.ifBlank { return null }
    val cutIndex = raw.indexOf('{')
    val prose = if (cutIndex >= 0) raw.substring(0, cutIndex) else raw
    val collapsed = COMPACT_SUMMARY_WHITESPACE_RE.replace(prose, " ").trim()
    if (collapsed.isEmpty()) return null
    return if (collapsed.length > 80) collapsed.take(80) + "…" else collapsed
}
private val TimelineTopPadding = 12.dp
private val TimelineBottomSafetyPadding = 28.dp
private val TimelineItemSpacing = 14.dp
private val TimelineMessageInnerSpacing = 4.dp
private val TimelineSelectionToolbarOffset = 56.dp
private const val MarkdownPrewarmBeforeItems = 4
private const val MarkdownPrewarmAfterItems = 8
private const val MarkdownPrewarmMaxTexts = 32
private val ActionOptionLineRegex = Regex(
    """^\s*(?:[-*+•·]|[0-9]{1,2}[.)、]|[（(]?[一二三四五六七八九十]{1,3}[）).、])\s+(.+?)\s*$"""
)
private val ActionOptionCuePhrases = listOf(
    "你想让我",
    "你想让",
    "你要我",
    "要不要我",
    "你想要",
    "我可以帮你",
    "我可以继续",
    "你可以选择",
    "请选择",
    "哪一种",
    "哪种",
    "哪一个",
    "做哪",
    "选项",
    "choose",
    "option",
    "which one",
    "do next",
    "next step",
    "would you like",
)

private enum class TimelineFollowMode {
    Idle,
    FollowingBottom,
    PausedForUser,
}

private fun Conversation.latestRenderToken(): String {
    val message = currentMessages.lastOrNull() ?: return "${messageNodes.size}:empty"
    val part = message.parts.lastOrNull()
    return buildString {
        append(messageNodes.size)
        append(':')
        append(message.id)
        append(':')
        append(message.parts.size)
        append(':')
        append(part?.compactRenderToken().orEmpty())
    }
}

@Suppress("DEPRECATION")
private fun UIMessagePart.compactRenderToken(): String = when (this) {
    is UIMessagePart.Text -> "text:${text.length}:${text.takeLast(16)}"
    is UIMessagePart.Reasoning -> "reasoning:${reasoning.length}:${finishedAt != null}"
    is UIMessagePart.Tool -> {
        val outputToken = output.lastOrNull()?.compactRenderToken().orEmpty()
        "tool:$toolCallId:$toolName:$isExecuted:${approvalState.compactRenderToken()}:${output.size}:$outputToken"
    }

    is UIMessagePart.Image -> "image:${url.length}:${metadata.hashCode()}"
    is UIMessagePart.Video -> "video:${url.length}:${metadata.hashCode()}"
    is UIMessagePart.Audio -> "audio:${url.length}:${metadata.hashCode()}"
    is UIMessagePart.Document -> "document:$fileName:${url.length}:${metadata.hashCode()}"
    is UIMessagePart.Search -> "search"
    is UIMessagePart.ToolCall -> "tool_call:$toolCallId:${arguments.length}:${approvalState.compactRenderToken()}"
    is UIMessagePart.ToolResult -> "tool_result:$toolCallId:${content.hashCode()}"
}

private fun ToolApprovalState.compactRenderToken(): String = when (this) {
    ToolApprovalState.Auto -> "auto"
    ToolApprovalState.Pending -> "pending"
    ToolApprovalState.Approved -> "approved"
    is ToolApprovalState.Denied -> "denied:${reason.length}"
    is ToolApprovalState.Answered -> "answered:${answer.length}"
}

private fun Modifier.dashedRoundedBorder(color: Color, radius: androidx.compose.ui.unit.Dp): Modifier {
    return drawBehind {
        drawRoundRect(
            color = color,
            size = size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx(), radius.toPx()),
            style = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 7.dp.toPx())),
            ),
        )
    }
}

@Composable
private fun TimelineHistoryLoadingIndicator(
    prefetching: Boolean,
    loadedNodeCount: Int,
    totalNodeCount: Int,
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = workspace.paper.copy(alpha = 0.72f),
        contentColor = workspace.muted,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PigLoadingIndicator(modifier = Modifier.size(18.dp))
            Text(
                text = if (prefetching) {
                    "正在预取更早消息 $loadedNodeCount/$totalNodeCount"
                } else {
                    "更早消息准备中 $loadedNodeCount/$totalNodeCount"
                },
                style = MaterialTheme.typography.labelMedium,
                color = workspace.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Codex-style "auto-compacting in progress" divider — two hairlines flanking a
 * label whose text is painted with a horizontal gradient that sweeps left→right
 * on an infinite loop. The sweep is implemented via `TextStyle(brush=...)` with
 * three color stops anchored around `phase`, so the highlight band slides across
 * the glyphs without redrawing the whole row each frame. Once compaction
 * completes the consumer swaps this for [ContextCompactMarker] (final state).
 *
 * Why the swap instead of fading the same widget: the "completed" marker is
 * decorative (visual divider in the timeline) and lives forever; the
 * "in-progress" marker is transient feedback (~5-30s) and shouldn't accumulate
 * in chat history. Treating them as separate Composables keeps the lifetime
 * model simple.
 */
@Composable
private fun ContextCompactInProgressMarker(
    modifier: Modifier = Modifier,
    streamingText: String = "",
) {
    val workspace = workspaceColors()
    val transition = rememberInfiniteTransition(label = "compactShimmer")
    val phase by transition.animateFloat(
        // Sweep range is wider than [0, 1] so the highlight band travels off both
        // ends — gives a brief "pause" at the edges before the next pass, which
        // reads as a deliberate rhythm rather than a frantic strobe. 1400ms is
        // the sweet spot the reviewer flagged: 1800ms felt sluggish, 1000ms
        // felt frantic. Restart (not Reverse) means the highlight always goes
        // left→right, matching Codex's reading direction.
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerPhase",
    )
    val baseColor = workspace.muted
    // Highlight tone: same hue as the base muted, but brighter so the band reads
    // as a glint rather than a different color (Codex behaviour, not a rainbow).
    val highlightColor = workspace.ink
    // No `remember` wrap here — `phase` changes every frame so caching is a
    // no-op. Build the brush directly per recomposition; the cost is 5
    // ColorStop allocations, which is negligible vs the canvas redraw the
    // gradient triggers anyway.
    // 5-stop linear gradient: muted → muted → bright → muted → muted, with
    // the bright stop anchored at `phase`. Width of the bright band is
    // ~0.15 of the total in each direction = 30% of width feels lit at any
    // given moment. Wider would wash the text, narrower would feel laser-y.
    val brush = Brush.horizontalGradient(
        colorStops = arrayOf(
            0f to baseColor,
            (phase - 0.15f).coerceIn(0f, 1f) to baseColor,
            phase.coerceIn(0f, 1f) to highlightColor,
            (phase + 0.15f).coerceIn(0f, 1f) to baseColor,
            1f to baseColor,
        ),
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = workspace.hairline,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Package01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = workspace.muted,
                )
                Text(
                    text = stringResource(R.string.chat_context_auto_compacting),
                    style = MaterialTheme.typography.labelLarge.copy(brush = brush),
                )
            }
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = workspace.hairline,
            )
        }
        // 2026-05-15 (1.9.7): show only the PROSE prefix of the streaming
        // summary (everything before the first `{`), not the raw JSON tail.
        // buildCompressionPrompt asks the LLM to write a single-sentence
        // human-readable summary BEFORE the JSON body, so the early tokens
        // are prose; once the model starts emitting JSON we freeze the
        // displayed text (no more updates, since `proseOnly` stops growing).
        // Whitespace-collapsed for stable rendering height; trailing 120 chars
        // + leading ellipsis once it overflows. When stream hasn't produced
        // any prose yet (cold start, slow first token), fall back to the
        // generic subtitle so the marker isn't a void of whitespace.
        val proseOnly = remember(streamingText) {
            val cutIndex = streamingText.indexOf('{')
            val raw = if (cutIndex >= 0) streamingText.substring(0, cutIndex) else streamingText
            COMPACT_SUMMARY_WHITESPACE_RE.replace(raw, " ").trim()
        }
        val tailText = remember(proseOnly) {
            if (proseOnly.length > 120) "…" + proseOnly.takeLast(120) else proseOnly
        }
        Text(
            text = tailText.ifBlank { stringResource(R.string.chat_context_auto_compacting_subtitle) },
            style = MaterialTheme.typography.labelSmall,
            color = workspace.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun ContextCompactMarker(
    modifier: Modifier = Modifier,
    summaryPreview: String? = null,
) {
    val workspace = workspaceColors()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = workspace.hairline,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Package01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = workspace.muted,
            )
            Text(
                text = stringResource(R.string.chat_context_auto_compacted),
                style = MaterialTheme.typography.labelLarge,
                color = workspace.muted,
            )
        }
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = workspace.hairline,
        )
        }
        // 2026-05-15 (1.9.5): when shown as the transient post-compact marker
        // (bottom of LazyColumn, 8s window), show a short preview of the freshly
        // generated summary so the user can actually SEE what was compacted —
        // not just "something happened". Lightweight alternative to a full
        // streaming view (would have required 200+ lines + new flows). 80 chars
        // is enough for a sentence + ellipsis on most phone widths.
        if (summaryPreview != null) {
            Text(
                text = summaryPreview,
                style = MaterialTheme.typography.labelSmall,
                color = workspace.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

@Composable
private fun PendingUserMessageBubble(
    message: PendingUserMessage,
    onCancel: () -> Unit,
    queueCount: Int? = null,
    onOpenQueue: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    val borderColor = when (message.mode) {
        PendingUserMessageMode.FOLLOWUP -> workspace.muted.copy(alpha = 0.42f)
        PendingUserMessageMode.STEER -> workspace.blue.copy(alpha = 0.48f)
        PendingUserMessageMode.COLLECT -> workspace.blue.copy(alpha = 0.28f)
    }
    val textColor = when (message.mode) {
        PendingUserMessageMode.FOLLOWUP -> workspace.muted
        PendingUserMessageMode.STEER -> workspace.blue.copy(alpha = 0.82f)
        PendingUserMessageMode.COLLECT -> workspace.muted.copy(alpha = 0.9f)
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopEnd,
        ) {
            val bubbleMaxWidth = maxOf(96.dp, minOf(maxWidth * 0.72f, 560.dp))
            Column(
                horizontalAlignment = Alignment.End,
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(min = 96.dp, max = bubbleMaxWidth)
                        .dashedRoundedBorder(borderColor, 10.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = workspace.paper.copy(alpha = 0.62f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = message.previewText(),
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = "取消排队消息",
                                tint = textColor.copy(alpha = 0.72f),
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
                if (queueCount != null && queueCount > 0) {
                    Text(
                        text = "已排队 $queueCount 条",
                        modifier = Modifier
                            .padding(top = 4.dp, end = 2.dp)
                            .clickable { onOpenQueue() },
                        style = MaterialTheme.typography.labelSmall,
                        color = workspace.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun ChatList(
    innerPadding: PaddingValues,
    conversation: Conversation,
    timelineLoadState: ConversationTimelineLoadState = ConversationTimelineLoadState(),
    pendingUserMessages: List<PendingUserMessage> = emptyList(),
    contextCompacts: List<ConversationCompact> = emptyList(),
    isCompacting: Boolean = false,
    streamingSummary: String = "",
    state: LazyListState,
    loading: Boolean,
    processingStatus: String? = null,
    previewMode: Boolean,
    settings: Settings,
    hazeState: HazeState,
    errors: List<ChatError> = emptyList(),
    onDismissError: (Uuid) -> Unit = {},
    onClearAllErrors: () -> Unit = {},
    onRegenerate: (UIMessage) -> Unit = {},
    onEdit: (UIMessage) -> Unit = {},
    onForkMessage: (UIMessage) -> Unit = {},
    onDelete: (UIMessage) -> Unit = {},
    onUpdateMessage: (MessageNode) -> Unit = {},
    onClickSuggestion: (String) -> Unit = {},
    onLongClickSuggestion: (String) -> Unit = {},
    onTranslate: ((UIMessage, java.util.Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onJumpToMessage: (Int) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onCancelPendingMessage: (String) -> Unit = {},
    onOpenQueue: () -> Unit = {},
    onOpenWorkspaceFile: ((String) -> Unit)? = null,
    onGenerativeWidgetAction: (String) -> Unit = {},
) {
    AnimatedContent(
        targetState = previewMode,
        label = "ChatListMode",
        transitionSpec = {
            (fadeIn() + scaleIn(initialScale = 0.8f) togetherWith fadeOut() + scaleOut(targetScale = 0.8f))
        }
    ) { target ->
        if (target) {
            ChatListPreview(
                innerPadding = innerPadding,
                conversation = conversation,
                settings = settings,
                hazeState = hazeState,
                onJumpToMessage = onJumpToMessage,
                animatedVisibilityScope = this@AnimatedContent,
            )
        } else {
            ChatListNormal(
                innerPadding = innerPadding,
                conversation = conversation,
                timelineLoadState = timelineLoadState,
                pendingUserMessages = pendingUserMessages,
                contextCompacts = contextCompacts,
                isCompacting = isCompacting,
                streamingSummary = streamingSummary,
                state = state,
                loading = loading,
                processingStatus = processingStatus,
                settings = settings,
                hazeState = hazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = onRegenerate,
                onEdit = onEdit,
                onForkMessage = onForkMessage,
                onDelete = onDelete,
                onUpdateMessage = onUpdateMessage,
                onClickSuggestion = onClickSuggestion,
                onLongClickSuggestion = onLongClickSuggestion,
                onTranslate = onTranslate,
                onClearTranslation = onClearTranslation,
                animatedVisibilityScope = this@AnimatedContent,
                onToolApproval = onToolApproval,
                onToolAnswer = onToolAnswer,
                onToggleFavorite = onToggleFavorite,
                onCancelPendingMessage = onCancelPendingMessage,
                onOpenQueue = onOpenQueue,
                onOpenWorkspaceFile = onOpenWorkspaceFile,
                onGenerativeWidgetAction = onGenerativeWidgetAction,
            )
        }
    }
}

@Composable
private fun ChatListNormal(
    innerPadding: PaddingValues,
    conversation: Conversation,
    timelineLoadState: ConversationTimelineLoadState,
    pendingUserMessages: List<PendingUserMessage>,
    contextCompacts: List<ConversationCompact>,
    isCompacting: Boolean,
    streamingSummary: String,
    state: LazyListState,
    loading: Boolean,
    processingStatus: String? = null,
    settings: Settings,
    hazeState: HazeState,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    onRegenerate: (UIMessage) -> Unit,
    onEdit: (UIMessage) -> Unit,
    onForkMessage: (UIMessage) -> Unit,
    onDelete: (UIMessage) -> Unit,
    onUpdateMessage: (MessageNode) -> Unit,
    onClickSuggestion: (String) -> Unit,
    onLongClickSuggestion: (String) -> Unit,
    onTranslate: ((UIMessage, java.util.Locale) -> Unit)?,
    onClearTranslation: (UIMessage) -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onCancelPendingMessage: (String) -> Unit = {},
    onOpenQueue: () -> Unit = {},
    onOpenWorkspaceFile: ((String) -> Unit)? = null,
    onGenerativeWidgetAction: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val activeGeneration = loading || pendingUserMessages.isNotEmpty()
    val activeGenerationState by rememberUpdatedState(activeGeneration)
    var isRecentScroll by remember { mutableStateOf(false) }
    var followMode by remember(conversation.id) { mutableStateOf(TimelineFollowMode.Idle) }
    var autoFollowResumeToken by remember(conversation.id) { mutableIntStateOf(0) }
    var programmaticScrollInProgress by remember(conversation.id) { mutableStateOf(false) }
    var programmaticScrollToken by remember(conversation.id) { mutableIntStateOf(0) }
    var imeProgrammaticScrollToken by remember(conversation.id) { mutableStateOf<Int?>(null) }
    var userScrollInTimeline by remember(conversation.id) { mutableStateOf(false) }
    val density = LocalDensity.current
    // 2026-05-16: 24dp ≈ 1 line of CJK at default size. 48dp (≈ 1.7 lines)
    // turned out to be too generous — releasing finger with one full visible
    // line still off-screen would re-arm follow and the next chunk yanked
    // away. Per M0.3 review feedback. If "靠近底部就跟随" feels too tight at
    // 24dp, raise back toward 32-36dp; if a "tiny scroll → re-arm → yank"
    // regression appears (defended originally by `!canScrollForward`), the
    // M0.3 followMode-guard at LE_scrollProgress.else is the real backstop —
    // tune buffer freely.
    val bottomFollowBufferPx = with(density) { 24.dp.toPx().toInt() }
    val activity = LocalContext.current as? me.rerere.rikkahub.RouteActivity
    val workspace = workspaceColors()

    // M0.1 diagnostic helper. Snapshots the follow/scroll state at each
    // transition point so we can correlate "哗哗出字最后没贴底" timeline
    // events from logcat. Gated on BuildConfig.DEBUG so the unconditional
    // Log.d is stripped in release variants — Android does not strip Log.d
    // automatically without ProGuard rules. Remove with the rest of M0.1
    // logging after the root cause is fixed.
    fun logScroll(event: String, extra: String = "") {
        if (!BuildConfig.DEBUG) return
        Log.d(
            SCROLL_TAG,
            "[$event] follow=$followMode prog=$programmaticScrollInProgress " +
                "token=$programmaticScrollToken activeGen=$activeGenerationState " +
                "scrollInProgress=${state.isScrollInProgress} " +
                "canScrollFwd=${state.canScrollForward} resumeToken=$autoFollowResumeToken" +
                if (extra.isNotEmpty()) " | $extra" else ""
        )
    }

    fun beginProgrammaticScroll(): Int {
        val token = programmaticScrollToken + 1
        programmaticScrollToken = token
        programmaticScrollInProgress = true
        logScroll("beginProgrammaticScroll", "newToken=$token")
        return token
    }

    fun endProgrammaticScroll(token: Int) {
        scope.launch {
            // 2026-05-14: dropped the additional `delay(120)`. Original purpose was
            // to absorb the isScrollInProgress flicker that happened right after
            // animateScrollToItem settled (one frame of "still scrolling" state
            // bouncing back to false). withFrameNanos { } alone — i.e. waiting
            // exactly one composition frame — is enough for that flicker to clear.
            // The 120ms tail created a window where a user finger-down was mis-
            // classified as programmatic, so the `isRecentScroll = true` gate (now
            // checking !programmaticScrollInProgress) would silently skip and
            // MessageJumper wouldn't surface. Snapping the flag back after one
            // frame closes that window.
            withFrameNanos { }
            val matched = programmaticScrollToken == token
            if (matched) {
                programmaticScrollInProgress = false
            }
            logScroll("endProgrammaticScroll", "token=$token matched=$matched")
        }
    }

    fun enterIdleFollowMode() {
        followMode = TimelineFollowMode.Idle
        autoFollowResumeToken += 1
        logScroll("enterIdleFollowMode")
    }

    fun resumeBottomFollow() {
        followMode = TimelineFollowMode.FollowingBottom
        autoFollowResumeToken += 1
        logScroll("resumeBottomFollow")
    }

    fun pauseAutoFollowTemporarily(
        mode: TimelineFollowMode,
        scheduleIdleReturn: Boolean,
    ) {
        followMode = mode
        if (scheduleIdleReturn) {
            autoFollowResumeToken += 1
        }
        logScroll("pauseAutoFollowTemporarily", "mode=$mode scheduleIdle=$scheduleIdleReturn")
    }

    suspend fun scrollToTimelineBottom() {
        val token = beginProgrammaticScroll()
        logScroll("scrollToTimelineBottom.enter", "token=$token isAtBottom=${state.isAtTimelineBottom(0)}")
        try {
            // B3 (smooth-streaming-rendering-guide §10 lerp): one
            // synchronous scroll step per call instead of an animateScrollBy
            // tween. Rationale:
            //
            //  - No in-flight animation means nothing to cancel when
            //    LE_chunk fires again on the next accumulator flush.
            //    The historical 80ms LinearEasing tween (and the
            //    spring(StiffnessMediumLow) before it that wedged
            //    isScrollInProgress=true for ~1s) both relied on
            //    cancel-on-restart; that race surface is now gone.
            //
            //  - scrollBy(value) wraps `state.scroll(MutatePriority
            //    .Default) { snapToBy(value) }` — the snap itself is
            //    immediate, but the surrounding scroll() mutator
            //    still flips isScrollInProgress true→false within a
            //    single frame. There is NO in-flight animation to
            //    cancel-restart, but LE_scrollProgress will still
            //    fire on each call. M0.3's followMode==PausedForUser
            //    guard at the LE_scrollProgress.else branch is the
            //    reason this doesn't misclassify our programmatic
            //    scroll as "user stopped scrolling mid-list" — do
            //    not remove that guard thinking it's dead code.
            //
            //  - Scroll by the actual measured distance to the bottom
            //    sentinel whenever it is visible. This keeps bursty providers
            //    (DeepSeek after a long reasoning phase, for example) from
            //    growing the tail by more than our old fixed 35%-viewport step
            //    and silently falling behind while followMode still says
            //    FollowingBottom.
            //
            //  - If the sentinel has already fallen out of the viewport, the
            //    distance is unknowable from LazyListLayoutInfo. In that one
            //    case snap to the sentinel item; following-bottom is an explicit
            //    "stick to tail" mode, so restoring the invariant is preferable
            //    to waiting for more chunks that may never come.
            val totalItems = state.layoutInfo.totalItemsCount
            if (totalItems > 0) {
                val distancePx = state.distanceToTimelineBottomPx()
                when {
                    distancePx == null -> {
                        state.scrollToItem(totalItems - 1)
                        logScroll(
                            "scrollToTimelineBottom.afterSnapToTail",
                            "token=$token isAtBottom=${state.isAtTimelineBottom(0)}"
                        )
                    }
                    distancePx > 0 -> {
                        state.scrollBy(distancePx.toFloat())
                        logScroll(
                            "scrollToTimelineBottom.afterScroll",
                            "token=$token distancePx=$distancePx isAtBottom=${state.isAtTimelineBottom(0)}"
                        )
                    }
                }
            }
        } finally {
            logScroll("scrollToTimelineBottom.exit", "token=$token isAtBottom=${state.isAtTimelineBottom(0)}")
            endProgrammaticScroll(token)
        }
    }

    DisposableEffect(Unit) {
        val listener: (Boolean) -> Boolean = { isVolumeUp ->
            if (settings.displaySetting.enableVolumeKeyScroll) {
                val bottomPaddingPx = with(density) {
                    (32.dp + innerPadding.calculateBottomPadding()).toPx()
                }
                val scrollAmount = (state.layoutInfo.viewportSize.height - bottomPaddingPx) *
                    settings.displaySetting.volumeKeyScrollRatio
                scope.launch {
                    userScrollInTimeline = true
                    try {
                        state.scrollBy(if (isVolumeUp) -scrollAmount else scrollAmount)
                        withFrameNanos { }
                    } finally {
                        userScrollInTimeline = false
                    }
                }
                true
            } else false
        }
        activity?.volumeKeyListeners?.add(listener)
        onDispose {
            activity?.volumeKeyListeners?.remove(listener)
        }
    }

    // 聊天选择
    val selectedItems = remember { mutableStateListOf<Uuid>() }
    var selecting by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    val compactMarkersByEndIndex = remember(contextCompacts, timelineLoadState.oldestLoadedIndex) {
        contextCompacts
            .filter { compact -> compact.status == "completed" }
            .mapNotNull { compact ->
                val visibleEndIndex = compact.sourceEndIndex - timelineLoadState.oldestLoadedIndex
                visibleEndIndex.takeIf { it >= 0 }?.let { it to compact }
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
    }

    // Set of message ids covered by an already-completed compact. ChatMessage
    // container alpha is dimmed for these so the user can VISUALLY tell that
    // "above the '———已自动压缩———' divider is no longer in active context,
    // only the summary is sent to the model." Keyed on contextCompacts ONLY —
    // NOT on conversation.messageNodes — so the streaming-time
    // updateConversation() that re-emits a new messageNodes List reference per
    // 33ms chunk does NOT cause this Set to be rebuilt. Per-node containment
    // is checked inline at render time (O(1) HashSet lookup).
    val coveredMessageIds = remember(contextCompacts) {
        contextCompacts
            .filter { it.status == "completed" }
            .flatMap { it.sourceMessageIds }
            .toSet()
    }

    // 2026-05-15 (1.9.9): user clarified the expected UX — "应该只有一段
    // '———已自动压缩———' 作为分割线，上面是灰色的历史内容，下面是历史内容的摘要".
    // Single source of truth = the permanent ContextCompactMarker injected at
    // each compact's sourceEndIndex via compactMarkersByEndIndex below. That
    // marker now carries the summary preview (1.9.8 change), so users see
    // exactly one "———已自动压缩———" line, divider above is pre-compacted
    // (dimmed via coveredMessageIds), divider below is the summary + active
    // context.
    //
    // The shimmer-while-compacting marker is still rendered at the LazyColumn
    // bottom while `isCompacting` is true (visual feedback that work is in
    // progress), but it vanishes the moment compactConversation returns and
    // the permanent marker takes over at the correct historical position.
    // The previous transient-bottom-marker-with-8s-timeout was an extra
    // moving piece I added trying to cover both reading positions — removed.
    val useTimelineHaze by remember {
        derivedStateOf { !state.isScrollInProgress }
    }
    val chatAssistant = remember(settings.assistants, conversation.assistantId) {
        settings.getAssistantById(conversation.assistantId)
    }
    val showAssistantBubble = settings.displaySetting.showAssistantBubble
    var markdownVirtualizationEpoch by remember(conversation.id) { mutableIntStateOf(0) }
    val lazyItemMessageIndexes = remember(
        conversation.messageNodes,
        timelineLoadState.isFullyLoaded,
        pendingUserMessages.size,
        loading,
        chatAssistant,
        showAssistantBubble,
        markdownVirtualizationEpoch,
    ) {
        buildLazyItemMessageIndexMap(
            messageNodes = conversation.messageNodes,
            assistant = chatAssistant,
            showAssistantBubble = showAssistantBubble,
            loading = loading,
            hasHistoryLoadingItem = !timelineLoadState.isFullyLoaded,
            pendingMessageCount = pendingUserMessages.size,
        )
    }
    // 自动跟随键盘滚动
    ImeLazyListAutoScroller(
        lazyListState = state,
        shouldScroll = {
            settings.displaySetting.enableAutoScroll &&
                followMode != TimelineFollowMode.PausedForUser &&
                (
                    followMode == TimelineFollowMode.FollowingBottom ||
                        state.isAtTimelineBottom(bottomFollowBufferPx) ||
                        state.isNearListEnd()
                    )
        },
        onProgrammaticScrollStart = {
            imeProgrammaticScrollToken = beginProgrammaticScroll()
        },
        onProgrammaticScrollEnd = {
            imeProgrammaticScrollToken?.let(::endProgrammaticScroll)
            imeProgrammaticScrollToken = null
        },
    )

    LaunchedEffect(
        settings.displaySetting.enableAutoScroll,
        activeGeneration,
        conversation.id,
    ) {
        logScroll(
            "LE_init",
            "enableAutoScroll=${settings.displaySetting.enableAutoScroll} convId=${conversation.id}"
        )
        if (!settings.displaySetting.enableAutoScroll || !activeGeneration) {
            logScroll("LE_init.branch", "→ enterIdleFollowMode (autoScrollOff or generationOff)")
            enterIdleFollowMode()
        } else if (
            followMode == TimelineFollowMode.Idle &&
            (
                state.isAtTimelineBottom(bottomFollowBufferPx) ||
                    state.isNearListEnd(bufferItems = 4)
                )
        ) {
            logScroll(
                "LE_init.branch",
                "→ resumeBottomFollow (isAtBottom=${state.isAtTimelineBottom(bottomFollowBufferPx)} nearEnd=${state.isNearListEnd(bufferItems = 4)})"
            )
            resumeBottomFollow()
        } else {
            logScroll(
                "LE_init.branch",
                "→ noop (currentFollow=$followMode isAtBottom=${state.isAtTimelineBottom(bottomFollowBufferPx)})"
            )
        }
    }

    val latestMessage = conversation.currentMessages.lastOrNull()
    LaunchedEffect(conversation.id, latestMessage?.id, activeGeneration) {
        logScroll(
            "LE_userMsg",
            "latestRole=${latestMessage?.role} latestId=${latestMessage?.id} enableAS=${settings.displaySetting.enableAutoScroll}"
        )
        if (
            activeGeneration &&
            settings.displaySetting.enableAutoScroll &&
            latestMessage?.role == MessageRole.USER
        ) {
            logScroll("LE_userMsg.branch", "→ resumeBottomFollow")
            resumeBottomFollow()
        }
    }

    LaunchedEffect(state.isScrollInProgress) {
        logScroll("LE_scrollProgress", "isScrollInProgress=${state.isScrollInProgress}")
        if (state.isScrollInProgress) {
            // 2026-05-14: gate isRecentScroll on `!programmaticScrollInProgress`.
            // Previously this was unconditional, which meant the streaming-time
            // animateScrollToItem (in scrollToTimelineBottom, fired by the
            // latestRenderToken LaunchedEffect on every accumulator flush) kept
            // re-arming "the user just scrolled" state. MessageJumper's
            // visibility = `isRecentScroll && !isScrollInProgress` then flipped
            // true→false→true on every chunk's scroll lifecycle, causing the
            // jumper card to slide in/out repeatedly — user reported it as
            // "右边这个四个箭头的导航按钮疯狂的往外弹". Only mark recent-scroll
            // when the user actually initiated the gesture.
            if (userScrollInTimeline && !programmaticScrollInProgress) {
                isRecentScroll = true
            }
            if (activeGenerationState && userScrollInTimeline && !programmaticScrollInProgress) {
                pauseAutoFollowTemporarily(
                    mode = TimelineFollowMode.PausedForUser,
                    scheduleIdleReturn = true,
                )
            }
        } else {
            // 2026-05-16 M0.3 fix: gate on followMode == PausedForUser to
            // defeat a race where endProgrammaticScroll's withFrameNanos {}
            // flipped prog=false BEFORE this LE re-ran on
            // isScrollInProgress=false. Without the followMode guard we
            // misread "programmatic scroll just ended" as "user stopped
            // scrolling mid-list" and paused auto-follow, stranding the
            // entire rest of the streaming response off-screen (logcat
            // proof: 01:24:02.260-.270, follow flipped FollowingBottom →
            // PausedForUser within the same ms as endProgrammaticScroll).
            //
            // Invariant: any genuine user scroll source passed through the
            // if-branch above and set follow=PausedForUser. So this branch
            // only needs to decide "did the user release at the bottom?" —
            // never to demote from FollowingBottom.
            if (
                activeGenerationState &&
                !programmaticScrollInProgress &&
                followMode == TimelineFollowMode.PausedForUser
            ) {
                // 2026-05-16: relaxed the bottom check from `!canScrollForward`
                // (must be at the absolute scroll-max, no wiggle room) to
                // `isAtTimelineBottom(bottomFollowBufferPx)` (within 48dp of
                // the bottom). User feedback: the strict version felt
                // unforgiving — had to scrub every last pixel before follow
                // re-armed. Trade-off the M0.3 guard above mostly defeats:
                // a short user scroll ending within 48dp could re-arm follow
                // and the next chunk yanks back. If that resurfaces, lower
                // bottomFollowBufferPx or add a brief user-input cooldown.
                if (state.isAtTimelineBottom(bottomFollowBufferPx)) {
                    logScroll("LE_scrollProgress.stop", "→ resumeBottomFollow (isAtBottom buffer=${bottomFollowBufferPx}px)")
                    resumeBottomFollow()
                } else {
                    logScroll("LE_scrollProgress.stop", "→ keep PausedForUser (not at bottom)")
                    pauseAutoFollowTemporarily(
                        mode = TimelineFollowMode.PausedForUser,
                        scheduleIdleReturn = true,
                    )
                }
            } else {
                logScroll(
                    "LE_scrollProgress.stop",
                    "→ noop guarded (follow=$followMode prog=$programmaticScrollInProgress)"
                )
            }
            delay(1500)
            isRecentScroll = false
        }
    }

    LaunchedEffect(
        autoFollowResumeToken,
        activeGeneration,
        settings.displaySetting.enableAutoScroll,
    ) {
        logScroll("LE_30sResume", "enter")
        if (
            activeGeneration &&
            settings.displaySetting.enableAutoScroll &&
            followMode != TimelineFollowMode.Idle &&
            followMode != TimelineFollowMode.FollowingBottom
        ) {
            val token = autoFollowResumeToken
            // Was 8s, but users complained that tapping the screen barely pauses follow before
            // it yanks back to bottom. Give them 30s of breathing room before auto-resuming;
            // they can still re-arm follow manually by scrolling to the bottom.
            delay(30_000)
            logScroll("LE_30sResume.afterDelay", "tokenStillValid=${token == autoFollowResumeToken}")
            if (
                token == autoFollowResumeToken &&
                activeGenerationState &&
                followMode != TimelineFollowMode.Idle &&
                followMode != TimelineFollowMode.FollowingBottom &&
                !state.isScrollInProgress &&
                state.isAtTimelineBottom(bottomFollowBufferPx)
            ) {
                logScroll("LE_30sResume.fired", "→ resumeBottomFollow + scrollToTimelineBottom")
                resumeBottomFollow()
                scrollToTimelineBottom()
            }
        }
    }

    LaunchedEffect(
        conversation.id,
        conversation.messageNodes,
        loading,
        chatAssistant,
        showAssistantBubble,
    ) {
        val contents = conversation.messageNodes
            .flatMapIndexed { index, node ->
                node.chatMessageVirtualizationPrewarmTexts(
                    assistant = chatAssistant,
                    showAssistantBubble = showAssistantBubble,
                    loading = loading,
                    lastMessage = index == conversation.messageNodes.lastIndex,
                )
            }
            .distinct()
        if (contents.isNotEmpty()) {
            withContext(Dispatchers.Default) {
                contents.forEach { content ->
                    prewarmMarkdownContent(content)
                }
            }
            markdownVirtualizationEpoch += 1
        }
    }

    LaunchedEffect(
        conversation.id,
        conversation.messageNodes,
        timelineLoadState.isFullyLoaded,
        loading,
        chatAssistant,
        lazyItemMessageIndexes,
    ) {
        snapshotFlow {
            state.markdownPrewarmTexts(
                messageNodes = conversation.messageNodes,
                assistant = chatAssistant,
                loadingLastMessage = loading,
                lazyItemMessageIndexes = lazyItemMessageIndexes,
            )
        }
            .distinctUntilChanged()
            .collectLatest { contents ->
                withContext(Dispatchers.Default) {
                    contents.distinct().forEach { content ->
                        prewarmMarkdownContent(content)
                    }
                }
            }
    }

    ChatJankProbe(
        state = state,
        messageNodes = conversation.messageNodes,
        lazyItemMessageIndexes = lazyItemMessageIndexes,
        loading = loading,
        timelineLoadState = timelineLoadState,
        pendingUserMessages = pendingUserMessages,
    )

    // 对话大小警告对话框
    val sizeInfo = rememberConversationSizeInfo(conversation)
    var showSizeWarningDialog by rememberSaveable(conversation.id) { mutableStateOf(true) }
    if (sizeInfo.showWarning && showSizeWarningDialog) {
        ConversationSizeWarningDialog(
            sizeInfo = sizeInfo,
            onDismiss = { showSizeWarningDialog = false }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(workspace.canvas),
    ) {
        if (settings.displaySetting.enableAutoScroll) {
            // 2026-05-15: simplified from 1.8.10's snapshotFlow + rememberUpdatedState
            // + sample(60ms) trio back to a direct LaunchedEffect keyed on the
            // freshly-computed render token.
            //
            // 1.8.10 (d1a7ab17) added rememberUpdatedState wrappers around the params
            // read inside snapshotFlow so the flow would re-emit on each streaming
            // chunk. Mechanically valid for the standard "snapshotFlow read tracking"
            // contract, but on-device follow-bottom still didn't catch. Multiple
            // possible failure modes there — ChatViewModel emit cadence, snapshot
            // tracking on a value-typed Conversation, sample()-induced skips when
            // scroll-in-progress eats every alternate sample — and the diagnostic
            // surface was wide.
            //
            // Trust Compose recomposition as the trigger instead. `latestRenderToken`
            // is recomputed at every recompose (params and conversation are read
            // directly). When ANY of (conversation contents, processingStatus,
            // pendingUserMessages count, loading) changes such that the outer
            // ChatListNormal recomposes, latestRenderToken usually differs → key
            // changes → LaunchedEffect restarts → if we're following + idle, scroll.
            //
            // What this gives up: no 60ms coalesce, so streaming chunks queue at
            // LazyListState.scroll{}'s mutex on tight bursts. Each scroll is
            // tween(80ms); MessageStreamAccumulator now flushes at 16ms (one Compose
            // frame) so a burst is real — but a new LaunchedEffect launch cancels
            // the in-flight scroll cleanly, and LinearEasing has no velocity to
            // preserve so the cancel doesn't show as a hitch. Net effect: the scroll
            // continuously chases the latest tail with no perceptible discrete steps.
            //
            // What this gains: deterministic — one chunk in, one scroll out. No
            // SnapshotState indirection, no FlowPreview API surface, no
            // rememberUpdatedState chain to reason about.
            val latestRenderToken = conversation.latestRenderToken()
            LaunchedEffect(
                latestRenderToken,
                conversation.id,
                processingStatus,
                pendingUserMessages.size,
                loading,
            ) {
                val willScroll = activeGenerationState &&
                    followMode == TimelineFollowMode.FollowingBottom &&
                    !state.isScrollInProgress
                logScroll(
                    "LE_chunk",
                    "loading=$loading pendingUserMsgs=${pendingUserMessages.size} " +
                        "tokenSuffix=${latestRenderToken.takeLast(40)} → ${if (willScroll) "SCROLL" else "SKIP"}"
                )
                if (willScroll) {
                    scrollToTimelineBottom()
                }
            }
        }

        LazyColumn(
            state = state,
            contentPadding = PaddingValues(
                start = TimelineHorizontalPadding,
                top = TimelineTopPadding,
                end = TimelineHorizontalPadding,
                bottom = TimelineBottomSafetyPadding + innerPadding.calculateBottomPadding(),
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (useTimelineHaze) Modifier.hazeSource(state = hazeState) else Modifier
                )
                .pointerInput(activeGeneration, settings.displaySetting.enableAutoScroll, conversation.id) {
                    awaitEachGesture {
                        // Use Initial pass so we can tag a real LazyColumn scroll
                        // as user-originated before the scroll progress effect runs.
                        // A tap alone should not pause bottom follow; the pause is
                        // applied when LazyListState actually enters scrolling.
                        // Do not use waitForUpOrCancellation here: LazyColumn can
                        // consume a real drag, and we need this marker to survive
                        // until every pointer is actually lifted.
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        userScrollInTimeline = true
                        try {
                            logScroll("pointerDown", "enableAS=${settings.displaySetting.enableAutoScroll}")
                            do {
                                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                            } while (event.changes.any { it.pressed })
                        } finally {
                            userScrollInTimeline = false
                        }
                    }
                }
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            if (!timelineLoadState.isFullyLoaded) {
                item(
                    key = "timeline-history-loading",
                    contentType = "history-loading",
                ) {
                    TimelineHistoryLoadingIndicator(
                        prefetching = timelineLoadState.prefetchingOlder,
                        loadedNodeCount = timelineLoadState.loadedNodeCount,
                        totalNodeCount = timelineLoadState.totalNodeCount,
                        modifier = Modifier.padding(bottom = TimelineItemSpacing),
                    )
                }
            }

            conversation.messageNodes.forEachIndexed { index, node ->
                val isLastMessage = index == conversation.messageNodes.lastIndex
                val isLoadingMessage = loading && isLastMessage
                val isPreCompacted = coveredMessageIds.isNotEmpty() &&
                    node.currentMessage.id.toString() in coveredMessageIds
                val virtualItems = buildChatMessageVirtualItems(
                    node = node,
                    assistant = chatAssistant,
                    showAssistantBubble = showAssistantBubble,
                    loading = isLoadingMessage,
                    lastMessage = isLastMessage,
                )
                if (virtualItems == null) {
                    item(
                        key = node.id,
                        contentType = "message-${node.currentMessage.role}",
                    ) {
                        Column(modifier = Modifier.padding(bottom = TimelineItemSpacing)) {
                            val markers = compactMarkersByEndIndex[index - 1].orEmpty()
                            markers.forEach { compact ->
                                // 2026-05-15 (1.9.8): historical marker also carries
                                // the summary preview (was display-divider-only before).
                                // Users scrolling back through the timeline can see what
                                // each compaction summarised — same content the transient
                                // bottom marker shows when compact just finished, but
                                // permanent at the proper sourceEndIndex position.
                                ContextCompactMarker(
                                    modifier = Modifier.padding(bottom = TimelineItemSpacing),
                                    summaryPreview = summaryPreviewOf(compact),
                                )
                            }
                            // 2026-05-15 (1.9.5): pass alpha modifier directly to
                            // ListSelectableItem instead of wrapping in a Box. Compose's
                            // Modifier.alpha(1f) is a no-op (returns Modifier as-is, no
                            // graphics layer allocated), so this skips the wrapper Box
                            // for active messages and avoids the 100+ unnecessary
                            // Composable nodes a long covered-history conversation
                            // would otherwise produce. Divider above is rendered
                            // separately (outside this modifier chain), so it remains
                            // fully opaque as the boundary marker.
                            ListSelectableItem(
                                modifier = if (isPreCompacted) Modifier.alpha(0.4f) else Modifier,
                                key = node.id,
                                onSelectChange = {
                                    if (!selectedItems.contains(node.id)) {
                                        selectedItems.add(node.id)
                                    } else {
                                        selectedItems.remove(node.id)
                                    }
                                },
                                selectedKeys = selectedItems,
                                enabled = selecting,
                            ) {
                                val messageModel = remember(node.currentMessage.modelId, settings.providers) {
                                    node.currentMessage.modelId?.let { settings.findModelById(it) }
                                }
                                ChatMessage(
                                    node = node,
                                    model = messageModel,
                                    assistant = chatAssistant,
                                    loading = isLoadingMessage,
                                    onRegenerate = {
                                        onRegenerate(node.currentMessage)
                                    },
                                    onEdit = {
                                        onEdit(node.currentMessage)
                                    },
                                    onFork = {
                                        onForkMessage(node.currentMessage)
                                    },
                                    onDelete = {
                                        onDelete(node.currentMessage)
                                    },
                                    onShare = {
                                        selecting = true  // 使用 CoroutineScope 延迟状态更新
                                        selectedItems.clear()
                                        selectedItems.addAll(conversation.messageNodes.map { it.id }
                                            .subList(0, index + 1))
                                    },
                                    onUpdate = {
                                        onUpdateMessage(it)
                                    },
                                    isFavorite = node.isFavorite,
                                    onToggleFavorite = {
                                        onToggleFavorite?.invoke(node)
                                    },
                                    onTranslate = onTranslate,
                                    onClearTranslation = onClearTranslation,
                                    onToolApproval = onToolApproval,
                                    onToolAnswer = onToolAnswer,
                                    onOpenWorkspaceFile = onOpenWorkspaceFile,
                                    onGenerativeWidgetAction = onGenerativeWidgetAction,
                                    lastMessage = isLastMessage,
                                )
                            }
                        }
                    }
                } else {
                    virtualItems.forEachIndexed { virtualIndex, virtualItem ->
                        val isFirstVirtualItem = virtualIndex == 0
                        val isLastVirtualItem = virtualIndex == virtualItems.lastIndex
                        val nextVirtualItem = virtualItems.getOrNull(virtualIndex + 1)
                        val bottomPadding = when {
                            isLastVirtualItem -> TimelineItemSpacing
                            virtualItem.isAdjacentMarkdownChild(nextVirtualItem) -> 0.dp
                            else -> TimelineMessageInnerSpacing
                        }
                        item(
                            key = "${node.id}:${virtualItem.keySuffix}",
                            contentType = "message-${node.currentMessage.role}-virtual-${virtualItem.keySuffix.substringBefore('-')}",
                        ) {
                            Column(
                                modifier = Modifier.padding(bottom = bottomPadding),
                            ) {
                                if (isFirstVirtualItem) {
                                    val markers = compactMarkersByEndIndex[index - 1].orEmpty()
                                    markers.forEach {
                                        ContextCompactMarker(
                                            modifier = Modifier.padding(bottom = TimelineItemSpacing)
                                        )
                                    }
                                }
                                // See non-virtual branch comment above — alpha is passed to
                                // TimelineSelectableMessageItem.modifier so the active-message
                                // path doesn't pay for an extra Box wrapper.
                                TimelineSelectableMessageItem(
                                    modifier = if (isPreCompacted) Modifier.alpha(0.4f) else Modifier,
                                    key = node.id,
                                    onSelectChange = {
                                        if (!selectedItems.contains(node.id)) {
                                            selectedItems.add(node.id)
                                        } else {
                                            selectedItems.remove(node.id)
                                        }
                                    },
                                    selectedKeys = selectedItems,
                                    enabled = selecting,
                                    showCheckbox = isFirstVirtualItem,
                                ) {
                                    val messageModel = remember(node.currentMessage.modelId, settings.providers) {
                                        node.currentMessage.modelId?.let { settings.findModelById(it) }
                                    }
                                    ChatMessageVirtualItemContent(
                                        node = node,
                                        item = virtualItem,
                                        model = messageModel,
                                        assistant = chatAssistant,
                                        loading = isLoadingMessage,
                                        onRegenerate = {
                                            onRegenerate(node.currentMessage)
                                        },
                                        onEdit = {
                                            onEdit(node.currentMessage)
                                        },
                                        onFork = {
                                            onForkMessage(node.currentMessage)
                                        },
                                        onDelete = {
                                            onDelete(node.currentMessage)
                                        },
                                        onShare = {
                                            selecting = true
                                            selectedItems.clear()
                                            selectedItems.addAll(conversation.messageNodes.map { it.id }
                                                .subList(0, index + 1))
                                        },
                                        onUpdate = {
                                            onUpdateMessage(it)
                                        },
                                        isFavorite = node.isFavorite,
                                        onToggleFavorite = {
                                            onToggleFavorite?.invoke(node)
                                        },
                                        onTranslate = onTranslate,
                                        onClearTranslation = onClearTranslation,
                                        onToolApproval = onToolApproval,
                                        onToolAnswer = onToolAnswer,
                                        onOpenWorkspaceFile = onOpenWorkspaceFile,
                                        onGenerativeWidgetAction = onGenerativeWidgetAction,
                                        lastMessage = isLastMessage,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            itemsIndexed(
                items = pendingUserMessages,
                key = { _, item -> "pending-${item.id}" },
                contentType = { _, _ -> "pending" },
            ) { index, pendingMessage ->
                Box(modifier = Modifier.padding(bottom = TimelineItemSpacing)) {
                    PendingUserMessageBubble(
                        message = pendingMessage,
                        onCancel = { onCancelPendingMessage(pendingMessage.id) },
                        queueCount = if (index == pendingUserMessages.lastIndex) pendingUserMessages.size else null,
                        onOpenQueue = onOpenQueue,
                    )
                }
            }

            // Codex-style "auto-compacting" divider. Lives between
            // pending-user-messages and the loading indicator so users see the
            // sequence:
            //   [my message]
            //   → [shimmer "正在自动压缩"]
            //   → (compact done) [solid "已自动压缩"] (8s)
            //   → [AI thinking]
            //
            // The transient finished marker at this position is in ADDITION to
            // the permanent ContextCompactMarker that gets inserted at the
            // historical sourceEndIndex (compactMarkersByEndIndex above) — that
            // one stays in the timeline as the boundary between compacted and
            // active context; this transient one is feedback at the user's
            // current viewport position.
            if (isCompacting) {
                item(
                    key = "compact-in-progress",
                    contentType = "compact-in-progress",
                ) {
                    ContextCompactInProgressMarker(
                        modifier = Modifier.padding(bottom = TimelineItemSpacing),
                        streamingText = streamingSummary,
                    )
                }
            }
            // 1.9.9: removed the post-compact transient bottom marker. The
            // permanent marker at sourceEndIndex (rendered in
            // compactMarkersByEndIndex above) is the single visible boundary;
            // its built-in summaryPreview displays the prose summary right
            // below the divider, exactly matching the user's mental model:
            //   [pre-compacted msg]   (dimmed)
            //   [pre-compacted msg]   (dimmed)
            //   ─── 已自动压缩 ───
            //   "summary preview ..."
            //   [active msg]

            if (loading) {
                item(
                    key = LoadingIndicatorKey,
                    contentType = "loading",
                ) {
                    Box(modifier = Modifier.padding(bottom = TimelineItemSpacing)) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = workspace.paper,
                            contentColor = workspace.muted,
                            tonalElevation = 0.dp,
                            shadowElevation = 1.dp,
                            border = BorderStroke(1.dp, workspace.hairline),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                PigLoadingIndicator(
                                    modifier = Modifier.size(24.dp)
                                )
                                AnimatedVisibility(
                                    visible = processingStatus != null,
                                ) {
                                    Text(
                                        text = processingStatus ?: "",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = workspace.muted,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 为了能正确滚动到这
            item(
                key = ScrollBottomKey,
                contentType = "scroll-bottom",
            ) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 错误消息卡片
            ErrorCardsDisplay(
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(5f)
            )

            // B4: dev-only streaming profiler overlay (FPS / reveal queue
            // depth / degraded indicator). Self-strips in release variants
            // via BuildConfig.DEBUG. Top-end so it doesn't overlap the
            // ErrorCards / selection toolbar / MessageJumper at the bottom.
            StreamProfilerOverlay(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
                    .zIndex(10f),
            )

            // 完成选择
            AnimatedVisibility(
                visible = selecting,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -TimelineSelectionToolbarOffset),
                enter = slideInVertically(
                    initialOffsetY = { it * 2 },
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it * 2 },
                ),
            ) {
                HorizontalFloatingToolbar(
                    expanded = true,
                ) {
                    Tooltip(
                        tooltip = {
                            Text("Clear selection")
                        }
                    ) {
                        IconButton(
                            onClick = {
                                selecting = false
                                selectedItems.clear()
                            }
                        ) {
                            Icon(HugeIcons.Cancel01, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text("Select all")
                        }
                    ) {
                        IconButton(
                            onClick = {
                                if (selectedItems.isNotEmpty()) {
                                    selectedItems.clear()
                                } else {
                                    selectedItems.addAll(conversation.messageNodes.map { it.id })
                                }
                            }
                        ) {
                            Icon(HugeIcons.CursorPointer01, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text("Confirm")
                        }
                    ) {
                        FilledIconButton(
                            onClick = {
                                selecting = false
                                val messages = conversation.messageNodes.filter { it.id in selectedItems }
                                if (messages.isNotEmpty()) {
                                    showExportSheet = true
                                }
                            }
                        ) {
                            Icon(HugeIcons.Tick01, null)
                        }
                    }
                }
            }

            // 导出对话框
            ChatExportSheet(
                visible = showExportSheet,
                onDismissRequest = {
                    showExportSheet = false
                    selectedItems.clear()
                },
                conversation = conversation,
                selectedMessages = conversation.messageNodes.filter { it.id in selectedItems }
                    .map { it.currentMessage }
            )

            val captureProgress = LocalScrollCaptureInProgress.current

            // 消息快速跳转
            MessageJumper(
                show = isRecentScroll && !state.isScrollInProgress && !activeGenerationState && settings.displaySetting.showMessageJumper && !captureProgress,
                onLeft = settings.displaySetting.messageJumperOnLeft,
                scope = scope,
                state = state
            )

            // Suggestion
            val actionSuggestions = remember(conversation.messageNodes, conversation.chatSuggestions) {
                conversation.actionSuggestionTexts()
            }
            if (actionSuggestions.isNotEmpty() && !captureProgress) {
                ChatSuggestionsRow(
                    suggestions = actionSuggestions,
                    onClickSuggestion = onClickSuggestion,
                    onLongClickSuggestion = onLongClickSuggestion,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

/**
 * 提取包含搜索词的文本片段，确保匹配词在开头可见
 */
private fun extractMatchingSnippet(
    text: String,
    query: String
): String {
    if (query.isBlank()) {
        return text
    }

    val matchIndex = text.indexOf(query, ignoreCase = true)
    if (matchIndex == -1) {
        return text
    }

    // 直接从匹配词开始显示，确保匹配词在最前面
    val snippet = text.substring(matchIndex)

    // 只在前面有内容时添加省略号
    return if (matchIndex > 0) {
        "...$snippet"
    } else {
        snippet
    }
}

internal fun LazyListState.isNearListEnd(bufferItems: Int = 2): Boolean {
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems == 0) return true
    val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return true
    return lastVisibleIndex >= totalItems - 1 - bufferItems
}

internal fun LazyListState.isAtTimelineBottom(bufferPx: Int = 0): Boolean {
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems == 0) return true
    val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull() ?: return true
    if (lastVisibleItem.index < totalItems - 1) return false
    val contentBottom = lastVisibleItem.offset + lastVisibleItem.size + layoutInfo.afterContentPadding
    return contentBottom <= layoutInfo.viewportEndOffset + bufferPx
}

private fun LazyListState.distanceToTimelineBottomPx(): Int? {
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems == 0) return 0
    val bottomItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == totalItems - 1 }
        ?: return null
    val contentBottom = bottomItem.offset + bottomItem.size + layoutInfo.afterContentPadding
    return (contentBottom - layoutInfo.viewportEndOffset).coerceAtLeast(0)
}

private fun LazyListState.markdownPrewarmTexts(
    messageNodes: List<MessageNode>,
    assistant: Assistant?,
    loadingLastMessage: Boolean,
    lazyItemMessageIndexes: List<Int?>,
): List<String> {
    if (messageNodes.isEmpty()) return emptyList()
    val visibleMessageIndexes = layoutInfo.visibleItemsInfo
        .mapNotNull { lazyItemMessageIndexes.getOrNull(it.index) }
        .filter { it in messageNodes.indices }
        .distinct()
    if (visibleMessageIndexes.isEmpty()) return emptyList()

    val start = (visibleMessageIndexes.minOrNull()!! - MarkdownPrewarmBeforeItems)
        .coerceAtLeast(0)
    val end = (visibleMessageIndexes.maxOrNull()!! + MarkdownPrewarmAfterItems)
        .coerceAtMost(messageNodes.lastIndex)

    return buildList {
        for (index in start..end) {
            if (loadingLastMessage && index == messageNodes.lastIndex) continue
            addAll(messageNodes[index].markdownPrewarmTexts(assistant))
            if (size >= MarkdownPrewarmMaxTexts) break
        }
    }.take(MarkdownPrewarmMaxTexts)
}

internal fun buildLazyItemMessageIndexMap(
    messageNodes: List<MessageNode>,
    assistant: Assistant?,
    showAssistantBubble: Boolean,
    loading: Boolean,
    hasHistoryLoadingItem: Boolean,
    pendingMessageCount: Int,
): List<Int?> = buildList {
    if (hasHistoryLoadingItem) add(null)
    messageNodes.forEachIndexed { index, node ->
        val itemCount = buildChatMessageVirtualItems(
            node = node,
            assistant = assistant,
            showAssistantBubble = showAssistantBubble,
            loading = loading && index == messageNodes.lastIndex,
            lastMessage = index == messageNodes.lastIndex,
        )?.size ?: 1
        repeat(itemCount) { add(index) }
    }
    repeat(pendingMessageCount) { add(null) }
    if (loading) add(null)
    add(null)
}

internal fun List<Int?>.firstLazyIndexForMessage(messageIndex: Int): Int? {
    return indexOfFirst { it == messageIndex }.takeIf { it >= 0 }
}

private fun ChatMessageVirtualItem.isAdjacentMarkdownChild(next: ChatMessageVirtualItem?): Boolean {
    return this is ChatMessageVirtualItem.MarkdownChild &&
        next is ChatMessageVirtualItem.MarkdownChild &&
        block.index == next.block.index
}

@Composable
private fun TimelineSelectableMessageItem(
    key: Uuid,
    selectedKeys: List<Uuid>,
    onSelectChange: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showCheckbox: Boolean = true,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (enabled) {
            if (showCheckbox) {
                Checkbox(
                    checked = key in selectedKeys,
                    onCheckedChange = {
                        onSelectChange(key)
                    }
                )
            } else {
                Spacer(modifier = Modifier.size(48.dp))
            }
        }
        Box(
            modifier = Modifier.weight(1f)
        ) {
            content()
        }
    }
}

private fun MessageNode.markdownPrewarmTexts(assistant: Assistant?): List<String> {
    val message = currentMessage
    val scope = if (message.role == MessageRole.USER) {
        AssistantAffectScope.USER
    } else {
        AssistantAffectScope.ASSISTANT
    }
    return message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .mapNotNull { part ->
            part.text
                .replaceRegexes(
                    assistant = assistant,
                    scope = scope,
                    visual = true,
                )
                .takeIf { it.isNotBlank() }
        }
}

private fun Conversation.actionSuggestionTexts(): List<String> {
    val lastMessage = messageNodes.lastOrNull()?.currentMessage
    if (lastMessage?.role != MessageRole.ASSISTANT) return emptyList()

    val explicitOptions = lastMessage.parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .extractAssistantActionOptions()

    return (explicitOptions + chatSuggestions)
        .mapNotNull { it.normalizedActionSuggestionOrNull() }
        .distinctBy { it.compactSuggestionKey() }
        .take(10)
}

private fun String.extractAssistantActionOptions(): List<String> {
    val lines = lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList()
    val cueIndex = lines.indexOfLast { it.isActionOptionCueLine() }
    if (cueIndex < 0) return emptyList()

    val options = mutableListOf<String>()
    for (line in lines.drop(cueIndex + 1).take(12)) {
        val option = ActionOptionLineRegex.matchEntire(line)
            ?.groupValues
            ?.getOrNull(1)
            ?.normalizedActionSuggestionOrNull()
        if (option == null) {
            if (options.isNotEmpty()) break
            continue
        }
        options += option
        if (options.size >= 8) break
    }
    return options
}

private fun String.isActionOptionCueLine(): Boolean {
    if (ActionOptionCuePhrases.any { phrase -> contains(phrase, ignoreCase = true) }) {
        return true
    }
    val asksForNextAction = contains("接下来") || contains("下一步")
    val hasChoiceSignal = contains("?") ||
        contains("？") ||
        contains("哪") ||
        contains("选") ||
        contains("想") ||
        contains("要")
    return asksForNextAction && hasChoiceSignal
}

private fun String.normalizedActionSuggestionOrNull(): String? {
    val rawText = trim()
    val text = (ActionOptionLineRegex.matchEntire(rawText)?.groupValues?.getOrNull(1) ?: rawText)
        .removePrefix("[ ]")
        .removePrefix("[x]")
        .removePrefix("[X]")
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .trim()
        .trim('：', ':', '。', '，', ',', '；', ';')
        .replace(Regex("""\s+"""), " ")
        .takeIf { it.length in 2..80 }
        ?: return null
    if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) {
        return null
    }
    return text
}

private fun String.compactSuggestionKey(): String {
    return replace(Regex("""\s+"""), "").lowercase()
}

private fun buildHighlightedText(
    text: String,
    query: String,
    highlightColor: Color
): AnnotatedString {
    if (query.isBlank()) {
        return AnnotatedString(text)
    }

    return buildAnnotatedString {
        var startIndex = 0
        var index = text.indexOf(query, startIndex, ignoreCase = true)

        while (index >= 0) {
            // 添加高亮前的文本
            append(text.substring(startIndex, index))

            // 添加高亮文本
            withStyle(
                style = SpanStyle(
                    background = highlightColor,
                    color = Color.Black
                )
            ) {
                append(text.substring(index, index + query.length))
            }

            startIndex = index + query.length
            index = text.indexOf(query, startIndex, ignoreCase = true)
        }

        // 添加剩余文本
        if (startIndex < text.length) {
            append(text.substring(startIndex))
        }
    }
}

@Composable
private fun ChatListPreview(
    innerPadding: PaddingValues,
    conversation: Conversation,
    contextCompacts: List<ConversationCompact> = emptyList(),
    settings: Settings,
    hazeState: HazeState,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onJumpToMessage: (Int) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    // 过滤消息，同时保留原始 index 避免后续 O(n) indexOf 查找
    val filteredMessages = remember(conversation.messageNodes, searchQuery) {
        if (searchQuery.isBlank()) {
            conversation.messageNodes.mapIndexed { index, node -> index to node }
        } else {
            conversation.messageNodes.mapIndexed { index, node -> index to node }
                .filter { (_, node) -> node.currentMessage.toText().contains(searchQuery, ignoreCase = true) }
        }
    }

    Column(
        modifier = Modifier
            .padding(top = innerPadding.calculateTopPadding())
            .fillMaxSize(),
    ) {
        WorkspaceSearchField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            placeholder = stringResource(R.string.history_page_search),
        )

        // 消息预览
        LazyColumn(
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 32.dp + innerPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            itemsIndexed(
                items = filteredMessages,
                key = { index, item -> item.second.id },
            ) { _, (originalIndex, node) ->
                val message = node.currentMessage
                val isUser = message.role == me.rerere.ai.core.MessageRole.USER
                val workspace = workspaceColors()
                // Per-role light fill — user messages get the very-light blue
                // (blueContainer #EAF4FF), AI gets the very-light green
                // (greenContainer #EDF9EF). Border picks up the same hue at
                // ~22% alpha. Replaces the previous 3dp left-edge accent +
                // paper fill: a 3dp stripe was too subtle to read the role
                // at a glance.
                val previewContainer = if (isUser) workspace.blueContainer else workspace.greenContainer
                val previewBorder = if (isUser) {
                    workspace.blue.copy(alpha = 0.22f)
                } else {
                    workspace.green.copy(alpha = 0.22f)
                }
                Surface(
                    onClick = { onJumpToMessage(originalIndex) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    color = previewContainer,
                    contentColor = workspace.ink,
                    border = androidx.compose.foundation.BorderStroke(1.dp, previewBorder),
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                if (isUser) R.string.history_page_search_role_user
                                else R.string.history_page_search_role_assistant
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = workspace.muted,
                        )
                        val highlightColor = workspace.amberContainer
                        val highlightedText = remember(searchQuery, message) {
                            val fullText = message.toText().trim().ifBlank { "[...]" }
                            val messageText = extractMatchingSnippet(
                                text = fullText,
                                query = searchQuery
                            )
                            buildHighlightedText(
                                text = messageText,
                                query = searchQuery,
                                highlightColor = highlightColor
                            )
                        }
                        Text(
                            text = highlightedText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = workspace.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatSuggestionsRow(
    modifier: Modifier = Modifier,
    suggestions: List<String>,
    onClickSuggestion: (String) -> Unit,
    onLongClickSuggestion: (String) -> Unit,
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(
            items = suggestions,
            key = { it },
        ) { suggestion ->
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .combinedClickable(
                        onClick = { onClickSuggestion(suggestion) },
                        onLongClick = { onLongClickSuggestion(suggestion) },
                    ),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                ),
            ) {
                Text(
                    text = suggestion,
                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.MessageJumper(
    show: Boolean,
    onLeft: Boolean,
    scope: CoroutineScope,
    state: LazyListState
) {
    AnimatedVisibility(
        visible = show,
        modifier = Modifier.align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd),
        enter = slideInHorizontally(
            initialOffsetX = { if (onLeft) -it * 2 else it * 2 },
        ),
        exit = slideOutHorizontally(
            targetOffsetX = { if (onLeft) -it * 2 else it * 2 },
        )
    ) {
        // Notion-like 浮动导航：单个 12dp 圆角卡片 + hairline outline + 内部
        // 用细 divider 分组 4 个 IconButton，配色走 onSurfaceVariant 灰阶，避免
        // 之前那种 4 个独立圆形蓝色蒙层与整体 surface/outline 体系冲突。
        val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
        Surface(
            // width(IntrinsicSize.Min) is load-bearing here. Material3 HorizontalDivider
            // defaults to fillMaxWidth, which would otherwise propagate "fill the parent"
            // up through Column and stretch the floating card across the entire chat
            // area. Asking the Surface for its min-intrinsic width clamps Column to the
            // max of its children's minIntrinsicWidth — the 36dp IconButtons — which is
            // what we actually want the divider to inherit.
            modifier = Modifier
                .padding(8.dp)
                .width(IntrinsicSize.Min),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            ),
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
        ) {
            Column {
                IconButton(
                    onClick = {
                        scope.launch {
                            state.scrollToItem(0)
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        // 跳到顶：arrow-up-to-line 用带横线的端点字形，比双 chevron 更
                        // 明确表达「到达边界」语义。
                        imageVector = Lucide.ArrowUpToLine,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(18.dp),
                    )
                }
                HorizontalDivider(
                    color = dividerColor,
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                IconButton(
                    onClick = {
                        scope.launch {
                            state.animateScrollToItem(
                                (state.firstVisibleItemIndex - 1).fastCoerceAtLeast(0)
                            )
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        // 上一条：chevron 无 stem 形态，与 ArrowUpToLine 区分「步进 vs 端点」
                        imageVector = Lucide.ChevronUp,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(18.dp),
                    )
                }
                HorizontalDivider(
                    color = dividerColor,
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                IconButton(
                    onClick = {
                        scope.launch {
                            state.animateScrollToItem(state.firstVisibleItemIndex + 1)
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Lucide.ChevronDown,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(18.dp),
                    )
                }
                HorizontalDivider(
                    color = dividerColor,
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                IconButton(
                    onClick = {
                        scope.launch {
                            state.scrollToItem(state.layoutInfo.totalItemsCount - 1)
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Lucide.ArrowDownToLine,
                        contentDescription = stringResource(R.string.chat_page_scroll_to_bottom),
                        tint = iconTint,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
