package app.amber.feature.ui.pages.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.withTimeoutOrNull
import app.amber.ai.ui.UIMessage
import app.amber.core.context.ActiveCompactBoundary
import app.amber.core.context.CompactLifecycleState
import app.amber.core.context.CompactSummaryPayloads
import app.amber.core.context.ConversationCompact
import app.amber.core.settings.Settings
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.service.ChatError
import app.amber.core.service.ConversationTimelineLoadState
import app.amber.core.service.PendingUserMessage
import kotlin.uuid.Uuid

private const val TAG = "ChatList"
internal const val HistoryLoadingItemKey = "timeline-history-loading"
internal const val TimelineTailKey = "TimelineTail"
internal val TimelineHorizontalPadding = 16.dp
internal val SendTransitionSlideDistance = 8.dp
// iMessage-style send entrance: the just-sent user bubble springs up from the
// input bar's direction into its slot. The distance approximates the visual
// gap between the input field and where the bubble lands (above the bottom
// padding + tail reserve).
internal val SendEntranceSlideDistance = 84.dp

// 2026-05-15 (1.9.7): top-level Regex avoids recompiling the pattern on every
// recomposition of ContextCompactInProgressMarker. The streaming summary flow
// can update at ~30Hz; per-tick Regex compile is a measurable frame-rate hit
// on mid-tier devices.
internal val COMPACT_SUMMARY_WHITESPACE_RE = Regex("\\s+")

internal data class TimelineHistoryLoadSignal(
    val historyVisible: Boolean,
    val initialized: Boolean,
    val fullyLoaded: Boolean,
    val prefetching: Boolean,
    val loadedNodeCount: Int,
)


internal data class TimelineScrollAnchor(
    val key: Any,
    val offset: Int,
)

internal fun summaryPreviewOf(compact: ConversationCompact): String? {
    val raw = compact.summary.ifBlank { return null }
    return summaryPreviewOf(raw)
}

internal fun summaryPreviewOf(state: CompactLifecycleState): String? {
    val raw = state.streamingSummary.ifBlank { return null }
    return summaryPreviewOf(raw)
}

private fun summaryPreviewOf(raw: String): String? {
    return CompactSummaryPayloads.timelineSummary(raw)?.compactSummaryPreview()
}

private fun String.compactSummaryPreview(maxLength: Int = 1_000): String {
    val collapsed = COMPACT_SUMMARY_WHITESPACE_RE.replace(this, " ").trim()
    return if (collapsed.length > maxLength) collapsed.take(maxLength) + "…" else collapsed
}
internal val TimelineTopPadding = 12.dp
internal val TimelineBottomSafetyPadding = 28.dp
// Breathing room under the waiting dot before the first token arrives. Was
// 156dp — stacked with the other tail reserves it opened a >1000px blank band
// between the sent bubble and the input bar through the whole TTFT window.
internal val PostSendWaitingBottomReserve = 40.dp
internal val AgentWorkingIndicatorReserveHeight = 56.dp
internal val TimelineItemSpacing = 14.dp
// ScrollBottomSpacerHeight keeps a small scroll-over zone below the newest
// message; the dot itself is top-aligned against the content (no lift).
internal val ScrollBottomSpacerHeight = 5.dp
internal const val TailIndicatorHandoffFadeMs = 180

// Height-amortizer grace after generation ends: the pacing buffer keeps
// draining its tail (~1s at the 360 chars/s cap) and the action footer inserts
// in the same window — both must still ride the animateContentSize spring or
// the ending falls back to single-frame line jumps / an instant 48dp push.
internal const val StreamingDrainAmortizeGraceMs = 1_500L
internal val TimelineMessageInnerSpacing = 4.dp
internal val TimelineSelectionToolbarOffset = 56.dp
internal const val MarkdownPrewarmBeforeItems = 4
internal const val MarkdownPrewarmAfterItems = 8
internal const val MarkdownPrewarmMaxTexts = 32
internal val ActionOptionLineRegex = Regex(
    """^\s*(?:[-*+•·]|[0-9]{1,2}[.)、]|[（(]?[一二三四五六七八九十]{1,3}[）).、])\s+(.+?)\s*$"""
)
internal val ActionOptionCuePhrases = listOf(
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

internal fun Modifier.dashedRoundedBorder(color: Color, radius: androidx.compose.ui.unit.Dp): Modifier {
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
internal fun ChatList(
    innerPadding: PaddingValues,
    conversation: Conversation,
    timelineLoadState: ConversationTimelineLoadState = ConversationTimelineLoadState(),
    pendingUserMessages: List<PendingUserMessage> = emptyList(),
    contextCompacts: List<ConversationCompact> = emptyList(),
    activeCompactBoundary: ActiveCompactBoundary? = null,
    compactLifecycleState: CompactLifecycleState = CompactLifecycleState.idle(),
    isCompacting: Boolean = false,
    streamingSummary: String = "",
    state: LazyListState,
    loading: Boolean,
    processingStatus: String? = null,
    previewMode: Boolean,
    settings: Settings,
    hazeState: HazeState,
    errors: List<ChatError> = emptyList(),
    globalErrors: List<ChatError> = emptyList(),
    onDismissError: (Uuid) -> Unit = {},
    onClearAllErrors: () -> Unit = {},
    onRegenerate: (UIMessage) -> Unit = {},
    onEdit: (UIMessage) -> Unit = {},
    onQuote: (String) -> Unit = {},
    onForkMessage: (UIMessage) -> Unit = {},
    onDelete: (UIMessage) -> Unit = {},
    onUpdateMessage: (MessageNode) -> Unit = {},
    onClickSuggestion: (String) -> Unit = {},
    onLongClickSuggestion: (String) -> Unit = {},
    onJumpToMessage: (Int) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onCancelPendingMessage: (String) -> Unit = {},
    onOpenQueue: () -> Unit = {},
    onOpenWorkspaceFile: ((String) -> Unit)? = null,
    onGenerativeWidgetAction: (String) -> Unit = {},
    onMiniAppModify: (String) -> Boolean = { false },
    /** P3-02: 保存消息到 Workspace (由 flag 控制是否为 null). */
    onSaveToWorkspace: ((UIMessage) -> Unit)? = null,
    onLoadOlderTimeline: suspend () -> Unit = {},
    onEnsureTimelineLoaded: suspend () -> Conversation = { conversation },
    chatTimelinePlan: ChatTimelinePlan,
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
                activeCompactBoundary = activeCompactBoundary,
                compactLifecycleState = compactLifecycleState,
                isCompacting = isCompacting,
                streamingSummary = streamingSummary,
                state = state,
                loading = loading,
                processingStatus = processingStatus,
                settings = settings,
                hazeState = hazeState,
                errors = errors,
                globalErrors = globalErrors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = onRegenerate,
                onEdit = onEdit,
                onQuote = onQuote,
                onForkMessage = onForkMessage,
                onDelete = onDelete,
                onUpdateMessage = onUpdateMessage,
                onClickSuggestion = onClickSuggestion,
                onLongClickSuggestion = onLongClickSuggestion,
                animatedVisibilityScope = this@AnimatedContent,
                onToolApproval = onToolApproval,
                onToolAnswer = onToolAnswer,
                onToggleFavorite = onToggleFavorite,
                onCancelPendingMessage = onCancelPendingMessage,
                onOpenQueue = onOpenQueue,
                onOpenWorkspaceFile = onOpenWorkspaceFile,
                onGenerativeWidgetAction = onGenerativeWidgetAction,
                onMiniAppModify = onMiniAppModify,
                onSaveToWorkspace = onSaveToWorkspace,
                onLoadOlderTimeline = onLoadOlderTimeline,
                onEnsureTimelineLoaded = onEnsureTimelineLoaded,
                chatTimelinePlan = chatTimelinePlan,
            )
        }
    }
}
