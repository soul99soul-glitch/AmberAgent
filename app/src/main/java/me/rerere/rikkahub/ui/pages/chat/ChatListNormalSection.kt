package me.rerere.rikkahub.ui.pages.chat

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalScrollCaptureInProgress
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.context.ActiveCompactBoundary
import me.rerere.rikkahub.data.context.CompactLifecycleState
import me.rerere.rikkahub.data.context.CompactLifecycleStatus
import me.rerere.rikkahub.data.context.ConversationCompact
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ConversationTimelineLoadState
import me.rerere.rikkahub.service.PendingUserMessage
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.components.message.ChatMessageVirtualItemContent
import me.rerere.rikkahub.ui.components.message.buildChatMessageVirtualItems
import me.rerere.rikkahub.ui.components.richtext.prewarmMarkdownContent
import me.rerere.rikkahub.ui.components.ui.ErrorCardsDisplay
import me.rerere.rikkahub.ui.components.ui.ListSelectableItem
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.hooks.ImeLazyListAutoScroller
import me.rerere.rikkahub.utils.ChatSendTransitionTracker
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

@Composable
internal fun ChatListNormal(
    innerPadding: PaddingValues,
    conversation: Conversation,
    timelineLoadState: ConversationTimelineLoadState,
    pendingUserMessages: List<PendingUserMessage>,
    contextCompacts: List<ConversationCompact>,
    activeCompactBoundary: ActiveCompactBoundary?,
    compactLifecycleState: CompactLifecycleState,
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
    onLoadOlderTimeline: suspend () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val compactInTimelineActive = isCompacting || compactLifecycleState.isActive
    val activeGeneration = loading || pendingUserMessages.isNotEmpty() || compactInTimelineActive
    val activeGenerationState by rememberUpdatedState(activeGeneration)
    val timelineLoadStateState by rememberUpdatedState(timelineLoadState)
    val loadOlderTimelineState by rememberUpdatedState(onLoadOlderTimeline)
    var isRecentScroll by remember { mutableStateOf(false) }
    var followMode by remember(conversation.id) { mutableStateOf(TimelineFollowMode.Idle) }
    var programmaticScrollInProgress by remember(conversation.id) { mutableStateOf(false) }
    var programmaticScrollToken by remember(conversation.id) { mutableIntStateOf(0) }
    var imeProgrammaticScrollToken by remember(conversation.id) { mutableStateOf<Int?>(null) }
    var userScrollInTimeline by remember(conversation.id) { mutableStateOf(false) }
    val density = LocalDensity.current
    val captureProgress = LocalScrollCaptureInProgress.current
    // 2026-05-16: 24dp ≈ 1 line of CJK at default size. 48dp (≈ 1.7 lines)
    // turned out to be too generous — releasing finger with one full visible
    // line still off-screen would re-arm follow and the next chunk yanked
    // away. Per M0.3 review feedback. If "靠近底部就跟随" feels too tight at
    // 24dp, raise back toward 32-36dp; if a "tiny scroll → re-arm → yank"
    // regression appears (defended originally by `!canScrollForward`), the
    // M0.3 followMode-guard at LE_scrollProgress.else is the real backstop —
    // tune buffer freely.
    val bottomFollowBufferPx = with(density) { 24.dp.toPx().toInt() }
    val sendTransitionSlidePx = with(density) { SendTransitionSlideDistance.roundToPx() }
    val activity = LocalContext.current as? me.rerere.rikkahub.RouteActivity
    val workspace = workspaceColors()
    val actionSuggestions = remember(conversation.messageNodes, conversation.chatSuggestions) {
        conversation.actionSuggestionTexts()
    }
    val visibleSuggestions = if (
        actionSuggestions.isNotEmpty() &&
        !activeGeneration &&
        !captureProgress
    ) {
        actionSuggestions
    } else {
        emptyList()
    }
    val latestMessage = conversation.currentMessages.lastOrNull()
    val latestMessageId = latestMessage?.id?.toString()
    val conversationId = conversation.id.toString()
    val latestIsPreSendTail = ChatSendTransitionTracker.isPreSendLatestMessage(
        conversationId = conversationId,
        messageId = latestMessageId,
    )
    val latestIsSentUserTail = latestMessage?.role == MessageRole.USER &&
        ChatSendTransitionTracker.isSentUserMessage(
            conversationId = conversationId,
            messageId = latestMessageId,
        )
    val postSendTailWaitingForAssistant = activeGeneration &&
        (latestIsPreSendTail || latestIsSentUserTail)
    val postSendSentUserMessageId = ChatSendTransitionTracker.sentUserMessageId(conversationId)
    val postSendSentUserMessageIndex = remember(
        conversation.messageNodes,
        postSendSentUserMessageId,
    ) {
        if (postSendSentUserMessageId != null) {
            conversation.messageNodes
                .indexOfFirst { node -> node.currentMessage.id.toString() == postSendSentUserMessageId }
                .takeIf { it >= 0 }
        } else {
            null
        }
    }
    val postSendAssistantMessageIndex = remember(
        conversation.messageNodes,
        postSendSentUserMessageIndex,
    ) {
        if (postSendSentUserMessageIndex == null) {
            null
        } else {
            val assistantIndex = postSendSentUserMessageIndex + 1
            val assistantNode = conversation.messageNodes.getOrNull(assistantIndex)
            if (assistantNode?.currentMessage?.role == MessageRole.ASSISTANT) {
                assistantIndex
            } else {
                null
            }
        }
    }
    val postSendAssistantHasVisibleContent = remember(
        conversation.messageNodes,
        postSendAssistantMessageIndex,
    ) {
        postSendAssistantMessageIndex?.let { index ->
            conversation.messageNodes
                .getOrNull(index)
                ?.currentMessage
                ?.parts
                ?.hasVisibleTimelineContent() == true
        } == true
    }
    val postSendHiddenAssistantMessageIndex = remember(
        conversation.messageNodes,
        postSendAssistantMessageIndex,
        postSendAssistantHasVisibleContent,
    ) {
        postSendAssistantMessageIndex?.takeIf { index ->
            index == conversation.messageNodes.lastIndex && !postSendAssistantHasVisibleContent
        }
    }
    val postSendWaitingForAssistantContent = activeGeneration &&
        postSendSentUserMessageIndex != null &&
        !postSendAssistantHasVisibleContent &&
        (postSendTailWaitingForAssistant || postSendAssistantMessageIndex != null)
    val timelineLoading = loading && !postSendWaitingForAssistantContent
    val timelineBottomPadding = TimelineBottomSafetyPadding +
        innerPadding.calculateBottomPadding() +
        if (postSendWaitingForAssistantContent) PostSendWaitingBottomReserve else 0.dp

    LaunchedEffect(conversation.id, activeGeneration) {
        if (!activeGeneration) {
            ChatSendTransitionTracker.clear(conversationId)
        }
    }

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
                "canScrollFwd=${state.canScrollForward}" +
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
        logScroll("enterIdleFollowMode")
    }

    fun resumeBottomFollow() {
        followMode = TimelineFollowMode.FollowingBottom
        logScroll("resumeBottomFollow")
    }

    fun pauseAutoFollowTemporarily(mode: TimelineFollowMode) {
        followMode = mode
        logScroll("pauseAutoFollowTemporarily", "mode=$mode")
    }

    fun requestTimelineBottom(reason: String) {
        val totalItems = state.layoutInfo.totalItemsCount
        if (totalItems > 0) {
            state.requestScrollToItem(totalItems - 1)
            logScroll("requestTimelineBottom", "$reason totalItems=$totalItems")
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

    fun List<LazyListItemInfo>.isBottomAnchorVisible(): Boolean {
        val lastItem = lastOrNull() ?: return false
        val inputBarHeight = with(density) { innerPadding.calculateBottomPadding().toPx() }
        val lastPos = lastItem.offset + lastItem.size
        val inputPos = state.layoutInfo.viewportEndOffset - inputBarHeight.roundToInt()
        return lastPos <= inputPos - 8
    }

    // 聊天选择
    val selectedItems = remember { mutableStateListOf<Uuid>() }
    var selecting by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    fun timelineAnchorForCompactEvent(eventAt: Long): Int {
        if (conversation.messageNodes.isEmpty()) return -1
        val timeZone = TimeZone.currentSystemDefault()
        val anchor = conversation.messageNodes.indexOfLast { node ->
            node.currentMessage.createdAt.toInstant(timeZone).toEpochMilliseconds() <= eventAt
        }
        return anchor.coerceIn(-1, conversation.messageNodes.lastIndex)
    }

    val completedCompacts = remember(contextCompacts) {
        contextCompacts.filter { compact -> compact.status == "completed" }
    }
    val completedMarkersByTimelineEndIndex = remember(
        contextCompacts,
        conversation.messageNodes,
    ) {
        completedCompacts
            .map { compact -> timelineAnchorForCompactEvent(compact.createdAt) to compact }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
    }
    val completedCompactIds = remember(contextCompacts) {
        contextCompacts
            .filter { it.status == "completed" }
            .map { it.id }
            .toSet()
    }
    val activeCompactTimelineEndIndex = remember(
        compactLifecycleState,
        isCompacting,
        conversation.messageNodes.size,
    ) {
        conversation.messageNodes.lastIndex
            .takeIf { it >= 0 && (compactLifecycleState.isActive || isCompacting) }
    }
    val lifecycleCompletedTimelineEndIndex = remember(
        compactLifecycleState,
        completedCompactIds,
        conversation.messageNodes.size,
    ) {
        compactLifecycleState
            .takeIf {
                it.status == CompactLifecycleStatus.COMPLETED &&
                    it.completedCompactId != null &&
                    it.completedCompactId !in completedCompactIds
            }
            ?.let { timelineAnchorForCompactEvent(it.anchorAt.takeIf { anchor -> anchor > 0L } ?: it.updatedAt) }
    }
    val activeCompactStreamingSummary = compactLifecycleState.streamingSummary.ifBlank { streamingSummary }
    val tailTimelineEndIndex = conversation.messageNodes.lastIndex
    val tailCompactItemKey = remember(conversation.id, tailTimelineEndIndex) {
        "compact-timeline-tail-${conversation.id}-$tailTimelineEndIndex"
    }
    val compactTailMarkerVisible by remember(state, tailCompactItemKey) {
        derivedStateOf {
            state.layoutInfo.visibleItemsInfo.any { item -> item.key == tailCompactItemKey }
        }
    }
    val showFloatingCompactMarker = (compactLifecycleState.isActive || isCompacting) &&
        !compactTailMarkerVisible &&
        !state.isScrollInProgress
    // Timeline index covered by completed compacts. Messages above that visible
    // divider are dimmed so the user can tell they are represented by summary
    // context, even if the runtime privately keeps recent turns for continuity.
    val visualCompactedTimelineEndIndex = remember(
        completedMarkersByTimelineEndIndex,
        lifecycleCompletedTimelineEndIndex,
    ) {
        (completedMarkersByTimelineEndIndex.keys + listOfNotNull(lifecycleCompletedTimelineEndIndex))
            .maxOrNull()
    }

    // Front-end dimming is based on what the user sees: every message above the
    // completed divider is visually old context, even if the runtime keeps a
    // few recent turns behind the scenes for continuity. The source ids remain
    // the model-substitution contract; this index is only presentation state.
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
        timelineLoading,
        chatAssistant,
        showAssistantBubble,
        markdownVirtualizationEpoch,
        postSendWaitingForAssistantContent,
        postSendAssistantMessageIndex,
    ) {
        buildLazyItemMessageIndexMap(
            messageNodes = conversation.messageNodes,
            assistant = chatAssistant,
            showAssistantBubble = showAssistantBubble,
            loading = timelineLoading,
            hasHistoryLoadingItem = !timelineLoadState.isFullyLoaded,
            pendingMessageCount = pendingUserMessages.size,
            hasPostSendWaitingPlaceholder = postSendWaitingForAssistantContent &&
                postSendAssistantMessageIndex == null,
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
        if (!settings.displaySetting.enableAutoScroll) {
            logScroll("LE_init.branch", "→ enterIdleFollowMode (autoScrollOff)")
            enterIdleFollowMode()
        } else if (!activeGeneration) {
            if (
                followMode == TimelineFollowMode.FollowingBottom &&
                !userScrollInTimeline &&
                !state.isScrollInProgress
            ) {
                requestTimelineBottom("generationEnded")
            }
            logScroll("LE_init.branch", "→ enterIdleFollowMode (generationOff)")
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
            // Previously this was unconditional, which meant programmatic
            // bottom-follow scrolls during streaming kept re-arming "the user
            // just scrolled" state. MessageJumper's
            // visibility = `isRecentScroll && !isScrollInProgress` then flipped
            // true→false→true on every chunk's scroll lifecycle, causing the
            // jumper card to slide in/out repeatedly — user reported it as
            // "右边这个四个箭头的导航按钮疯狂的往外弹". Only mark recent-scroll
            // when the user actually initiated the gesture.
            if (userScrollInTimeline && !programmaticScrollInProgress) {
                isRecentScroll = true
            }
            if (activeGenerationState && userScrollInTimeline && !programmaticScrollInProgress) {
                pauseAutoFollowTemporarily(TimelineFollowMode.PausedForUser)
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
                    pauseAutoFollowTemporarily(TimelineFollowMode.PausedForUser)
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

    LaunchedEffect(conversation.id, state) {
        snapshotFlow {
            val loadState = timelineLoadStateState
            val visibleItems = state.layoutInfo.visibleItemsInfo
            TimelineHistoryLoadSignal(
                historyVisible = visibleItems.any { it.key == HistoryLoadingItemKey },
                initialized = loadState.initialized,
                fullyLoaded = loadState.isFullyLoaded,
                prefetching = loadState.prefetchingOlder,
                loadedNodeCount = loadState.loadedNodeCount,
            )
        }
            .distinctUntilChanged()
            .collect { signal ->
                if (!signal.initialized || signal.fullyLoaded || signal.prefetching || !signal.historyVisible) {
                    return@collect
                }
                val anchor = state.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key != HistoryLoadingItemKey }
                    ?.let { TimelineScrollAnchor(key = it.key, offset = it.offset) }
                loadOlderTimelineState()
                withFrameNanos { }
                anchor?.let { previous ->
                    val current = state.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == previous.key }
                        ?: return@let
                    val delta = current.offset - previous.offset
                    if (abs(delta) > 1) {
                        state.scrollBy(delta.toFloat())
                    }
                }
            }
    }

    LaunchedEffect(
        conversation.id,
        conversation.messageNodes,
        timelineLoadState.isFullyLoaded,
        timelineLoading,
        chatAssistant,
        lazyItemMessageIndexes,
    ) {
        snapshotFlow {
            state.markdownPrewarmTexts(
                messageNodes = conversation.messageNodes,
                assistant = chatAssistant,
                loadingLastMessage = timelineLoading,
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
                if (contents.isNotEmpty()) {
                    markdownVirtualizationEpoch += 1
                }
            }
    }

    ChatJankProbe(
        state = state,
        messageNodes = conversation.messageNodes,
        lazyItemMessageIndexes = lazyItemMessageIndexes,
        loading = timelineLoading,
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
            LaunchedEffect(conversation.id, activeGeneration) {
                if (!activeGeneration) return@LaunchedEffect
                snapshotFlow { state.layoutInfo.visibleItemsInfo }
                    .collect { visibleItemsInfo ->
                        val willFollow = activeGenerationState &&
                            followMode == TimelineFollowMode.FollowingBottom &&
                            !userScrollInTimeline &&
                            !state.isScrollInProgress
                        if (willFollow) {
                            requestTimelineBottom(
                                "layoutFollow visibleItems=${visibleItemsInfo.size} " +
                                    "bottomVisible=${visibleItemsInfo.isBottomAnchorVisible()}",
                            )
                        }
                    }
            }
        }

        val sessionVisibility = remember(conversation.id) {
            MutableTransitionState(false).apply { targetState = true }
        }
        AnimatedVisibility(
            visibleState = sessionVisibility,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(animationSpec = tween(140)) +
                slideInVertically(
                    animationSpec = tween(140),
                    initialOffsetY = { -sendTransitionSlidePx },
                ),
            exit = fadeOut(animationSpec = tween(80)),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
            state = state,
            contentPadding = PaddingValues(
                start = TimelineHorizontalPadding,
                top = TimelineTopPadding,
                end = TimelineHorizontalPadding,
                bottom = timelineBottomPadding,
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
                    key = HistoryLoadingItemKey,
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
                if (index == postSendHiddenAssistantMessageIndex) {
                    item(
                        key = node.id,
                        contentType = "post-send-waiting-assistant",
                    ) {
                        PostSendWaitingIndicator(
                            modifier = Modifier.padding(bottom = TimelineItemSpacing),
                        )
                    }
                    return@forEachIndexed
                }
                val isLastMessage = index == conversation.messageNodes.lastIndex
                val isLoadingMessage = timelineLoading && isLastMessage
                val isPreCompacted = visualCompactedTimelineEndIndex?.let { index <= it } == true
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
                        Column(
                            modifier = Modifier.padding(bottom = TimelineItemSpacing)
                        ) {
                            TimelineCompactMarkers(
                                timelineEndIndex = index - 1,
                                completedMarkersByTimelineEndIndex = completedMarkersByTimelineEndIndex,
                                lifecycleCompletedTimelineEndIndex = lifecycleCompletedTimelineEndIndex,
                                compactLifecycleState = compactLifecycleState,
                                activeCompactTimelineEndIndex = activeCompactTimelineEndIndex,
                                activeCompactStreamingSummary = activeCompactStreamingSummary,
                                modifier = Modifier.padding(bottom = TimelineItemSpacing),
                            )
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
                                val messageModel = remember(
                                        node.currentMessage.modelId,
                                        node.currentMessage.role,
                                        isLoadingMessage,
                                        settings.providers,
                                        settings.chatModelId,
                                        chatAssistant?.chatModelId,
                                    ) {
                                        node.currentMessage.modelId?.let { settings.findModelById(it) }
                                            ?: if (isLoadingMessage && node.currentMessage.role == MessageRole.ASSISTANT) {
                                                settings.getCurrentChatModel()
                                            } else {
                                                null
                                            }
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
                }
                if (virtualItems != null) {
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
                                    TimelineCompactMarkers(
                                        timelineEndIndex = index - 1,
                                        completedMarkersByTimelineEndIndex = completedMarkersByTimelineEndIndex,
                                        lifecycleCompletedTimelineEndIndex = lifecycleCompletedTimelineEndIndex,
                                        compactLifecycleState = compactLifecycleState,
                                        activeCompactTimelineEndIndex = activeCompactTimelineEndIndex,
                                        activeCompactStreamingSummary = activeCompactStreamingSummary,
                                        modifier = Modifier.padding(bottom = TimelineItemSpacing),
                                    )
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
                                    val messageModel = remember(
                                        node.currentMessage.modelId,
                                        node.currentMessage.role,
                                        isLoadingMessage,
                                        settings.providers,
                                        settings.chatModelId,
                                        chatAssistant?.chatModelId,
                                    ) {
                                        node.currentMessage.modelId?.let { settings.findModelById(it) }
                                            ?: if (isLoadingMessage && node.currentMessage.role == MessageRole.ASSISTANT) {
                                                settings.getCurrentChatModel()
                                            } else {
                                                null
                                            }
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

            val hasTailCompletedCompactMarkers =
                completedMarkersByTimelineEndIndex[tailTimelineEndIndex].orEmpty().isNotEmpty()
            val hasTailLifecycleCompletedCompactMarker =
                lifecycleCompletedTimelineEndIndex == tailTimelineEndIndex
            val hasTailActiveCompactMarker =
                activeCompactTimelineEndIndex == tailTimelineEndIndex
            if (
                hasTailCompletedCompactMarkers ||
                hasTailLifecycleCompletedCompactMarker ||
                hasTailActiveCompactMarker
            ) {
                item(
                    key = tailCompactItemKey,
                    contentType = "compact-timeline-tail",
                ) {
                    TimelineCompactMarkers(
                        timelineEndIndex = tailTimelineEndIndex,
                        completedMarkersByTimelineEndIndex = completedMarkersByTimelineEndIndex,
                        lifecycleCompletedTimelineEndIndex = lifecycleCompletedTimelineEndIndex,
                        compactLifecycleState = compactLifecycleState,
                        activeCompactTimelineEndIndex = activeCompactTimelineEndIndex,
                        activeCompactStreamingSummary = activeCompactStreamingSummary,
                        modifier = Modifier.padding(bottom = TimelineItemSpacing),
                    )
                }
            }

            if (postSendWaitingForAssistantContent && postSendAssistantMessageIndex == null) {
                item(
                    key = "post-send-waiting-${postSendSentUserMessageId ?: conversation.id}",
                    contentType = "post-send-waiting-assistant",
                ) {
                    PostSendWaitingIndicator(
                        modifier = Modifier.padding(bottom = TimelineItemSpacing),
                    )
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

            if (timelineLoading) {
                item(
                    key = LoadingIndicatorKey,
                    contentType = "loading",
                ) {
                    AgentWorkingIndicator(
                        processingStatus = processingStatus,
                        modifier = Modifier.padding(8.dp),
                    )
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
            if (showFloatingCompactMarker) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .zIndex(4f)
                        .padding(horizontal = TimelineHorizontalPadding, vertical = 8.dp),
                ) {
                    ContextCompactInProgressMarker(
                        streamingText = activeCompactStreamingSummary,
                    )
                }
            }

            // 错误消息卡片
            ErrorCardsDisplay(
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(5f)
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

            // 消息快速跳转
            MessageJumper(
                show = isRecentScroll && !state.isScrollInProgress && !activeGenerationState && settings.displaySetting.showMessageJumper && !captureProgress,
                onLeft = settings.displaySetting.messageJumperOnLeft,
                scope = scope,
                state = state
            )

            // Suggestion chips are intentionally instant here. They sit in the
            // bottom overlay while the input panel has its own height animation;
            // animating both during send makes the timeline feel like it is
            // being tugged from two places.
            if (visibleSuggestions.isNotEmpty()) {
                ChatSuggestionsRow(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    suggestions = visibleSuggestions,
                    onClickSuggestion = onClickSuggestion,
                    onLongClickSuggestion = onLongClickSuggestion,
                )
            }
            }
        }
        }
    }
}
