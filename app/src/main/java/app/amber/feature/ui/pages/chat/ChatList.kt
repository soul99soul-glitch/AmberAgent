package app.amber.feature.ui.pages.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.amber.ai.ui.UIMessage
import app.amber.core.context.ActiveCompactBoundary
import app.amber.core.context.CompactLifecycleState
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
internal val TimelineTopPadding = 16.dp
internal val TimelineBottomSafetyPadding = 28.dp
// Breathing room under the waiting dot before the first token arrives. Was
// 156dp — stacked with the other tail reserves it opened a >1000px blank band
// between the sent bubble and the input bar through the whole TTFT window.
internal val PostSendWaitingBottomReserve = 40.dp
internal val AgentWorkingIndicatorReserveHeight = 56.dp
internal val TimelineItemSpacing = 8.dp
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
    // Normal entry composes exactly one branch (no AnimatedContent
    // SubcomposeLayout on the first frame). The original crossfade is kept for
    // the rare user-driven preview toggle: only then are both modes composed
    // for the 160ms transition.
    val displayedMode = remember { mutableStateOf(previewMode) }
    val outgoingMode = remember { mutableStateOf<Boolean?>(null) }
    val modeProgress = remember { Animatable(1f) }
    LaunchedEffect(previewMode) {
        if (previewMode == displayedMode.value) return@LaunchedEffect
        outgoingMode.value = displayedMode.value
        displayedMode.value = previewMode
        modeProgress.snapTo(0f)
        modeProgress.animateTo(1f, tween(durationMillis = 160))
        outgoingMode.value = null
    }
    val modeContent: @Composable (Boolean) -> Unit = { showPreview ->
    if (showPreview) {
        ChatListPreview(
            innerPadding = innerPadding,
            conversation = conversation,
            settings = settings,
            onJumpToMessage = onJumpToMessage,
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
    Box(Modifier.fillMaxSize()) {
        outgoingMode.value?.let { outgoing ->
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - modeProgress.value }
            ) {
                modeContent(outgoing)
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = if (outgoingMode.value != null) modeProgress.value else 1f
                }
        ) {
            modeContent(displayedMode.value)
        }
    }
}
