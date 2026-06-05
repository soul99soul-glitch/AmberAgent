package app.amber.feature.ui.pages.chat

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Tick01
import app.amber.agent.BuildConfig
import app.amber.agent.PerfFlags
import app.amber.agent.R
import app.amber.core.context.ActiveCompactBoundary
import app.amber.core.context.CompactLifecycleState
import app.amber.core.context.CompactLifecycleStatus
import app.amber.core.context.ConversationCompact
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.settings.getAssistantById
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.service.ChatError
import app.amber.core.service.ConversationTimelineLoadState
import app.amber.core.service.PendingUserMessage
import app.amber.feature.ui.components.message.ChatMessage
import app.amber.feature.ui.components.message.ChatMessageVirtualItem
import app.amber.feature.ui.components.message.ChatMessageVirtualItemContent
import app.amber.feature.ui.components.richtext.prewarmMarkdownContent
import app.amber.feature.ui.components.ui.ErrorCardsDisplay
import app.amber.feature.ui.components.ui.ListSelectableItem
import app.amber.feature.ui.components.ui.Tooltip
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.hooks.ImeLazyListAutoScroller
import app.amber.core.utils.ChatSendTransitionTracker
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
    animatedVisibilityScope: AnimatedVisibilityScope,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onCancelPendingMessage: (String) -> Unit = {},
    onOpenQueue: () -> Unit = {},
    onOpenWorkspaceFile: ((String) -> Unit)? = null,
    onGenerativeWidgetAction: (String) -> Unit = {},
    onMiniAppModify: (String) -> Boolean = { false },
    onLoadOlderTimeline: suspend () -> Unit = {},
    onEnsureTimelineLoaded: suspend () -> Conversation = { conversation },
    chatTimelinePlan: ChatTimelinePlan,
) {
    val scope = rememberCoroutineScope()
    val currentConversationState by rememberUpdatedState(conversation)
    val compactInTimelineActive = isCompacting || compactLifecycleState.isActive
    val activeGeneration = loading || pendingUserMessages.isNotEmpty() || compactInTimelineActive
    val activeGenerationState by rememberUpdatedState(activeGeneration)
    val enableAutoScrollState by rememberUpdatedState(settings.displaySetting.enableAutoScroll)
    val timelineLoadStateState by rememberUpdatedState(timelineLoadState)
    val loadOlderTimelineState by rememberUpdatedState(onLoadOlderTimeline)
    val ensureTimelineLoadedState by rememberUpdatedState(onEnsureTimelineLoaded)
    var isRecentScroll by remember { mutableStateOf(false) }
    var followMode by remember(conversation.id) { mutableStateOf(TimelineFollowMode.Idle) }
    var programmaticScrollInProgress by remember(conversation.id) { mutableStateOf(false) }
    var programmaticScrollToken by remember(conversation.id) { mutableIntStateOf(0) }
    var imeProgrammaticScrollToken by remember(conversation.id) { mutableStateOf<Int?>(null) }
    var userScrollInTimeline by remember(conversation.id) { mutableStateOf(false) }
    var previousActiveGeneration by remember(conversation.id) { mutableStateOf(activeGeneration) }
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
    val activity = LocalContext.current as? app.amber.agent.RouteActivity
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
    val latestMessage = conversation.messageNodes.lastOrNull()?.currentMessage
    val conversationId = conversation.id.toString()
    val postSendState = chatTimelinePlan.postSendState
    val timelineLoading = chatTimelinePlan.timelineLoading
    val timelineBottomPadding = TimelineBottomSafetyPadding +
        if (postSendState.waitingForAssistantContent) PostSendWaitingBottomReserve else 0.dp

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

    suspend fun scrollToTimelineBottom(
        reason: String,
        smoothLargeMove: Boolean = false,
    ) {
        val token = beginProgrammaticScroll()
        logScroll("scrollToTimelineBottom.enter", "reason=$reason token=$token")
        try {
            val maxSmoothStepPx = with(density) { 18.dp.toPx() }
            val maxFrames = if (smoothLargeMove) 8 else 1
            var frameIndex = 0
            while (frameIndex < maxFrames) {
                val totalItems = state.layoutInfo.totalItemsCount
                if (totalItems <= 0) break
                val distancePx = state.distanceToTimelineBottomPx()
                when {
                    distancePx == null -> {
                        if (!smoothLargeMove) {
                            state.scrollToItem(totalItems - 1)
                            logScroll("scrollToTimelineBottom.snap", "reason=$reason token=$token")
                            withFrameNanos { }
                            val settleDistancePx = state.distanceToTimelineBottomPx()
                            if (settleDistancePx != null && settleDistancePx > 0) {
                                state.scrollBy(settleDistancePx.toFloat())
                                logScroll(
                                    "scrollToTimelineBottom.snapSettle",
                                    "reason=$reason token=$token distancePx=$settleDistancePx",
                                )
                            }
                            break
                        }
                        if (!state.canScrollForward) break
                        state.scrollBy(maxSmoothStepPx)
                        logScroll(
                            "scrollToTimelineBottom.scrollByHiddenAnchor",
                            "reason=$reason token=$token stepPx=$maxSmoothStepPx frame=$frameIndex",
                        )
                        frameIndex++
                        withFrameNanos { }
                    }

                    distancePx > 0 -> {
                        val stepPx = if (smoothLargeMove) {
                            minOf(distancePx.toFloat(), maxSmoothStepPx)
                        } else {
                            distancePx.toFloat()
                        }
                        state.scrollBy(stepPx)
                        logScroll(
                            "scrollToTimelineBottom.scrollBy",
                            "reason=$reason token=$token distancePx=$distancePx stepPx=$stepPx frame=$frameIndex",
                        )
                        if (!smoothLargeMove || distancePx.toFloat() <= maxSmoothStepPx) break
                        frameIndex++
                        withFrameNanos { }
                    }

                    else -> break
                }
            }
        } finally {
            logScroll("scrollToTimelineBottom.exit", "reason=$reason token=$token")
            endProgrammaticScroll(token)
        }
    }

    fun requestTimelineBottom(reason: String) {
        val totalItems = state.layoutInfo.totalItemsCount
        if (totalItems <= 0) return
        val token = beginProgrammaticScroll()
        logScroll("requestTimelineBottom", "reason=$reason token=$token totalItems=$totalItems")
        state.requestScrollToItem(totalItems - 1)
        endProgrammaticScroll(token)
    }

    suspend fun waitForGenerationEndSettleGate(): Boolean {
        repeat(TimelineFollowEndSettlePolicy.MaxSettleFrames) {
            if (
                TimelineFollowEndSettlePolicy.canSettleNow(
                    followMode = followMode,
                    userScrollInTimeline = userScrollInTimeline,
                    scrollInProgress = state.isScrollInProgress,
                )
            ) {
                return true
            }
            if (
                !TimelineFollowEndSettlePolicy.canAttemptSettle(
                    followMode = followMode,
                    userScrollInTimeline = userScrollInTimeline,
                )
            ) {
                return false
            }
            withFrameNanos { }
        }
        return TimelineFollowEndSettlePolicy.canAttemptSettle(
            followMode = followMode,
            userScrollInTimeline = userScrollInTimeline,
        )
    }

    suspend fun settleAfterGenerationEnd() {
        if (!waitForGenerationEndSettleGate()) {
            logScroll("generationEndSettle.skip", "gate=false")
            return
        }
        repeat(TimelineFollowEndSettlePolicy.MaxSettleFrames) { frame ->
            val distanceBefore = state.distanceToTimelineBottomPx()
            if (
                TimelineFollowEndSettlePolicy.isCloseEnoughToBottom(
                    distancePx = distanceBefore,
                    bottomBufferPx = bottomFollowBufferPx,
                )
            ) {
                logScroll("generationEndSettle.done", "frame=$frame distancePx=$distanceBefore")
                return
            }
            if (
                !TimelineFollowEndSettlePolicy.canAttemptSettle(
                    followMode = followMode,
                    userScrollInTimeline = userScrollInTimeline,
                )
            ) {
                logScroll("generationEndSettle.stop", "frame=$frame distancePx=$distanceBefore")
                return
            }
            val reason = if (frame == 0) {
                "generationEnded"
            } else {
                "generationEnded-settle-$frame"
            }
            scrollToTimelineBottom(reason, smoothLargeMove = false)
            withFrameNanos { }
            val distanceAfter = state.distanceToTimelineBottomPx()
            logScroll(
                "generationEndSettle.frame",
                "frame=$frame before=$distanceBefore after=$distanceAfter",
            )
            if (
                TimelineFollowEndSettlePolicy.isCloseEnoughToBottom(
                    distancePx = distanceAfter,
                    bottomBufferPx = bottomFollowBufferPx,
                )
            ) {
                return
            }
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
    val selectedItemSet by remember {
        derivedStateOf { selectedItems.toSet() }
    }
    var selecting by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    var exportConversation by remember(conversation.id) { mutableStateOf<Conversation?>(null) }
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
    val deferStreamingMarkdownParse by remember(activeGeneration, userScrollInTimeline, programmaticScrollInProgress, followMode) {
        derivedStateOf {
            activeGeneration &&
                userScrollInTimeline &&
                followMode == TimelineFollowMode.PausedForUser &&
                !programmaticScrollInProgress
        }
    }
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
    val lazyItemMessageIndexes = chatTimelinePlan.lazyItemMessageIndexes
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
        val effectPlan = TimelineFollowEndSettlePolicy.effectPlan(
            wasActiveGeneration = previousActiveGeneration,
            activeGeneration = activeGeneration,
            autoScrollEnabled = settings.displaySetting.enableAutoScroll,
        )
        previousActiveGeneration = activeGeneration
        logScroll(
            "LE_init",
            "enableAutoScroll=${settings.displaySetting.enableAutoScroll} convId=${conversation.id}"
        )
        if (effectPlan.enterIdleAfterEndSettle) {
            if (effectPlan.runEndSettleBeforeIdle) {
                settleAfterGenerationEnd()
            }
            val idleReason = if (settings.displaySetting.enableAutoScroll) {
                "generationOff"
            } else {
                "autoScrollOff"
            }
            logScroll("LE_init.branch", "→ enterIdleFollowMode ($idleReason)")
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
        chatTimelinePlan,
        timelineLoading,
        chatAssistant,
    ) {
        snapshotFlow {
            state.markdownPrewarmTexts(
                messageNodes = conversation.messageNodes,
                assistant = chatAssistant,
                loadingLastMessage = timelineLoading,
                timelinePlan = chatTimelinePlan,
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

    // V3 Whisper：空白态让 ChatPage 的 bloom 透上来；有消息则淡入纯白覆盖。
    // 用 paper(#FFFFFF) 而非 canvas(#F7F7F5)，避免与 TopBar 之间出现灰白分界线。
    // Paper/Midnight 主题 (showBloomInConvo=true) 对话态需要保留底层 bloom，
    // 把 canvasAlpha 上限压到 0.85 让 0.25 强度的 convo bloom 透得出来
    val hasContent = conversation.messageNodes.isNotEmpty()
    val chatThemeForCanvas = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val maxCanvasAlpha = if (chatThemeForCanvas.showBloomInConvo) 0.85f else 1f
    val canvasAlpha by animateFloatAsState(
        targetValue = if (hasContent) maxCanvasAlpha else 0f,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "canvasFade",
    )
    val backgroundColor = workspace.paper.copy(alpha = canvasAlpha)
    val latestRenderToken = conversation.latestRenderToken()
    val showBottomFollowAnimation = settings.displaySetting.showBottomFollowAnimation
    // Keep the working indicator inside the LazyColumn during streaming. The
    // old pinned overlay looked detached from the generated content: it hid
    // abruptly when the user scrolled history and reappeared abruptly when
    // returning. As a timeline item it moves with the real content bottom,
    // matching RikkaHub's simpler bottom-follow feel.
    val showPinnedAgentWorkingIndicator = false
    val streamingVisibleEvents = remember(conversation.id) {
        createStreamingBottomFollowEvents()
    }
    fun requestStreamingBottomFollow(reason: String) {
        streamingVisibleEvents.tryEmit(reason)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
    ) {
        if (settings.displaySetting.enableAutoScroll) {
            LaunchedEffect(streamingVisibleEvents, conversation.id) {
                val bottomFollowEvents = streamingVisibleEvents.conflate()
                bottomFollowEvents.collect { reason ->
                    val willFollow = activeGenerationState &&
                        followMode == TimelineFollowMode.FollowingBottom &&
                        !userScrollInTimeline
                    if (!willFollow) return@collect
                    withFrameNanos { }
                    val stillFollowing = activeGenerationState &&
                        followMode == TimelineFollowMode.FollowingBottom &&
                        !userScrollInTimeline
                    if (stillFollowing) {
                        if (
                            PerfFlags.USE_UNIFIED_STREAMING_BOTTOM_FOLLOW ||
                            PerfFlags.STREAMING_IMMEDIATE_CONTENT_REVEAL
                        ) {
                            scrollToTimelineBottom("stream-$reason", smoothLargeMove = false)
                        } else {
                            requestTimelineBottom("stream-$reason")
                        }
                    }
                }
            }
            LaunchedEffect(
                latestRenderToken,
                conversation.id,
                processingStatus,
                pendingUserMessages.size,
                loading,
            ) {
                if (!activeGeneration) return@LaunchedEffect
                val willFollow = activeGenerationState &&
                    followMode == TimelineFollowMode.FollowingBottom &&
                    !userScrollInTimeline
                logScroll(
                    "LE_chunk",
                    "loading=$loading pendingUserMsgs=${pendingUserMessages.size} " +
                        "tokenSuffix=${latestRenderToken.takeLast(40)} → ${if (willFollow) "EMIT" else "SKIP"}"
                )
                if (willFollow) {
                    requestStreamingBottomFollow("chunk")
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
                .pointerInput(conversation.id) {
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
                            logScroll(
                                "pointerDown",
                                "enableAS=$enableAutoScrollState activeGen=$activeGenerationState",
                            )
                            do {
                                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                            } while (event.changes.any { it.pressed })
                        } finally {
                            userScrollInTimeline = false
                        }
                    }
                }
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding(),
                ),
        ) {
            chatTimelinePlan.entries.forEachIndexed { planIndex, entry ->
                when (entry) {
                    ChatTimelineEntry.HistoryLoading -> {
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

                    is ChatTimelineEntry.PostSendHiddenAssistant -> {
                        item(
                            key = entry.node.id,
                            contentType = "post-send-waiting-assistant",
                        ) {
                            PostSendWaitingIndicator(
                                visible = showBottomFollowAnimation,
                                modifier = Modifier.padding(bottom = TimelineItemSpacing),
                            )
                        }
                    }

                    is ChatTimelineEntry.Message -> {
                        val index = entry.messageIndex
                        val node = entry.node
                        val isLastMessage = index == conversation.messageNodes.lastIndex
                        val isLoadingMessage = timelineLoading && isLastMessage
                        val isPreCompacted = visualCompactedTimelineEndIndex?.let { index <= it } == true
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
                                            selecting = true
                                            selectedItems.clear()
                                            selectedItems.addAll(conversation.messageNodes.take(index + 1).map { it.id })
                                        },
                                        onUpdate = {
                                            onUpdateMessage(it)
                                        },
                                        isFavorite = node.isFavorite,
                                        onToggleFavorite = {
                                            onToggleFavorite?.invoke(node)
                                        },
                                        onToolApproval = onToolApproval,
                                        onToolAnswer = onToolAnswer,
                                        onOpenWorkspaceFile = onOpenWorkspaceFile,
                                        onGenerativeWidgetAction = onGenerativeWidgetAction,
                                        onMiniAppModify = onMiniAppModify,
                                        onStreamingVisibleFrame = if (isLoadingMessage) {
                                            { requestStreamingBottomFollow("visible") }
                                        } else {
                                            null
                                        },
                                        deferStreamingParse = isLoadingMessage && deferStreamingMarkdownParse,
                                        lastMessage = isLastMessage,
                                    )
                                }
                            }
                        }
                    }

                    is ChatTimelineEntry.VirtualMessage -> {
                        val index = entry.messageIndex
                        val node = entry.node
                        val virtualItem = entry.item
                        val isFirstVirtualItem = entry.virtualIndex == 0
                        val isLastVirtualItem = entry.virtualIndex == entry.virtualCount - 1
                        val nextVirtualItem = (chatTimelinePlan.entries.getOrNull(planIndex + 1) as? ChatTimelineEntry.VirtualMessage)
                            ?.takeIf { it.messageIndex == index }
                            ?.item
                        val isLastMessage = index == conversation.messageNodes.lastIndex
                        val isLoadingMessage = timelineLoading && isLastMessage
                        val isPreCompacted = visualCompactedTimelineEndIndex?.let { index <= it } == true
                        val bottomPadding = when {
                            isLastVirtualItem -> TimelineItemSpacing
                            virtualItem.isAdjacentMarkdownChild(nextVirtualItem) -> 0.dp
                            else -> TimelineMessageInnerSpacing
                        }
                        val virtualItemKey = if (virtualItem is ChatMessageVirtualItem.Header) {
                            node.id
                        } else {
                            "${node.id}:${virtualItem.keySuffix}"
                        }
                        item(
                            key = virtualItemKey,
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
                                            selectedItems.addAll(conversation.messageNodes.take(index + 1).map { it.id })
                                        },
                                        onUpdate = {
                                            onUpdateMessage(it)
                                        },
                                        isFavorite = node.isFavorite,
                                        onToggleFavorite = {
                                            onToggleFavorite?.invoke(node)
                                        },
                                        onToolApproval = onToolApproval,
                                        onToolAnswer = onToolAnswer,
                                        onOpenWorkspaceFile = onOpenWorkspaceFile,
                                        onGenerativeWidgetAction = onGenerativeWidgetAction,
                                        onMiniAppModify = onMiniAppModify,
                                        lastMessage = isLastMessage,
                                    )
                                }
                            }
                        }
                    }

                    ChatTimelineEntry.PostSendWaitingAssistant,
                    is ChatTimelineEntry.Pending,
                    ChatTimelineEntry.Loading,
                    ChatTimelineEntry.ScrollBottom -> Unit
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

            chatTimelinePlan.entries.forEach { entry ->
                when (entry) {
                    ChatTimelineEntry.PostSendWaitingAssistant -> {
                        item(
                            key = "post-send-waiting-${postSendState.sentUserMessageId ?: conversation.id}",
                            contentType = "post-send-waiting-assistant",
                        ) {
                            PostSendWaitingIndicator(
                                visible = showBottomFollowAnimation,
                                modifier = Modifier.padding(bottom = TimelineItemSpacing),
                            )
                        }
                    }

                    is ChatTimelineEntry.Pending -> {
                        val pendingMessage = pendingUserMessages.getOrNull(entry.pendingIndex)
                            ?: return@forEach
                        item(
                            key = "pending-${pendingMessage.id}",
                            contentType = "pending",
                        ) {
                            Box(modifier = Modifier.padding(bottom = TimelineItemSpacing)) {
                                PendingUserMessageBubble(
                                    message = pendingMessage,
                                    onCancel = { onCancelPendingMessage(pendingMessage.id) },
                                    queueCount = if (entry.pendingIndex == pendingUserMessages.lastIndex) {
                                        pendingUserMessages.size
                                    } else {
                                        null
                                    },
                                    onOpenQueue = onOpenQueue,
                                )
                            }
                        }
                    }

                    ChatTimelineEntry.Loading -> {
                        item(
                            key = LoadingIndicatorKey,
                            contentType = "loading",
                        ) {
                            if (showPinnedAgentWorkingIndicator) {
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(AgentWorkingIndicatorReserveHeight)
                                )
                            } else {
                                if (showBottomFollowAnimation) {
                                    Box(modifier = Modifier.padding(bottom = TimelineItemSpacing)) {
                                        AgentWorkingIndicator(processingStatus = processingStatus)
                                    }
                                }
                            }
                        }
                    }

                    ChatTimelineEntry.ScrollBottom -> {
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

                    ChatTimelineEntry.HistoryLoading,
                    is ChatTimelineEntry.PostSendHiddenAssistant,
                    is ChatTimelineEntry.Message,
                    is ChatTimelineEntry.VirtualMessage -> Unit
                }
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

            AnimatedVisibility(
                visible = showPinnedAgentWorkingIndicator && !captureProgress,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = AgentWorkingIndicatorOverlayBottomOffset)
                    .zIndex(6f),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                AgentWorkingIndicator(processingStatus = processingStatus)
            }

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
                                    scope.launch {
                                        val fullConversation = ensureTimelineLoadedState()
                                        selectedItems.clear()
                                        selectedItems.addAll(fullConversation.messageNodes.map { it.id })
                                    }
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
                                scope.launch {
                                    val source = if (
                                        timelineLoadStateState.initialized &&
                                        timelineLoadStateState.isFullyLoaded &&
                                        timelineLoadStateState.oldestLoadedIndex == 0
                                    ) {
                                        currentConversationState
                                    } else {
                                        ensureTimelineLoadedState()
                                    }
                                    selecting = false
                                    exportConversation = source
                                    val messages = source.messageNodes.filter { it.id in selectedItemSet }
                                    if (messages.isNotEmpty()) {
                                        showExportSheet = true
                                    }
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
                    exportConversation = null
                    selectedItems.clear()
                },
                conversation = exportConversation ?: conversation,
                selectedMessages = (exportConversation ?: conversation).messageNodes.filter { it.id in selectedItemSet }
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

private fun LazyListState.distanceToTimelineBottomPx(): Int? {
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems == 0) return 0
    val bottomItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == totalItems - 1 }
        ?: return null
    val contentBottom = bottomItem.offset + bottomItem.size + layoutInfo.afterContentPadding
    return (contentBottom - layoutInfo.viewportEndOffset).coerceAtLeast(0)
}
