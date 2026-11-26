package app.amber.feature.ui.pages.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.amber.ai.core.MessageRole
import app.amber.core.settings.Settings
import app.amber.core.model.Conversation
import app.amber.core.service.ConversationTimelineLoadState
import app.amber.core.service.PendingUserMessage
import app.amber.feature.ui.components.richtext.prewarmMarkdownContent
import app.amber.core.utils.ChatSendTransitionTracker
import kotlin.math.abs
import kotlin.uuid.Uuid
import app.amber.core.model.AssistantRegex

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

internal data class TimelineTailPresentation(
    val reserveVisible: Boolean,
    val dotVisible: Boolean,
    val drainAmortizeGrace: Boolean,
)

private data class SendAppendSnapshot(
    val pendingCount: Int,
    val nodeCount: Int,
    val lastNodeId: Uuid?,
    val lastNodeRole: MessageRole?,
    val lastUserNodeId: Uuid?,
    val pendingIntent: SendStageIntent?,
    val sendStageActive: Boolean,
)

@Composable
internal fun rememberTimelineTailPresentation(
    conversationId: Uuid,
    latestMessageId: Uuid?,
    timelineLoading: Boolean,
    showBottomFollowAnimation: Boolean,
): TimelineTailPresentation {
    // reverseLayout 下 TimelineTail（index 0）就在锚定边：生成结束瞬间移除它的
    // 70dp 指示器 reserve 会让整个时间线单帧下坠。保留空间直到下一条消息成为
    // 尾部（与旧实现的 retainedTailIndicator 语义一致），圆点本身照常淡出。
    var retainedTailReserveMessageId by remember(conversationId) { mutableStateOf<String?>(null) }
    LaunchedEffect(timelineLoading, latestMessageId) {
        val latestId = latestMessageId?.toString()
        when {
            timelineLoading && latestId != null -> retainedTailReserveMessageId = latestId
            latestId != retainedTailReserveMessageId -> retainedTailReserveMessageId = null
        }
    }

    // 生成结束的瞬间 pacing buffer 才开始 drain：文字尾巴还要逐帧生长 ~1s，
    // 动作行也在此刻插入（+48dp）。高度摊铺器若随 timelineLoading 一起撤掉，
    // 这段增长退回单帧阶梯跳（"一行一行往上跳"），按钮插入也成了瞬时重排。
    // true→false 边沿给摊铺器留一个覆盖 drain 的宽限期（首次进会话不布防）。
    var drainAmortizeGrace by remember(conversationId) { mutableStateOf(false) }
    var prevTimelineLoading by remember(conversationId) { mutableStateOf(timelineLoading) }
    LaunchedEffect(timelineLoading) {
        if (prevTimelineLoading && !timelineLoading) {
            drainAmortizeGrace = true
            delay(StreamingDrainAmortizeGraceMs)
            drainAmortizeGrace = false
        } else if (timelineLoading) {
            drainAmortizeGrace = false
        }
        prevTimelineLoading = timelineLoading
    }

    val tailIndicatorReserveVisible = showBottomFollowAnimation &&
        (
            timelineLoading ||
                (retainedTailReserveMessageId != null &&
                    latestMessageId?.toString() == retainedTailReserveMessageId)
            )
    return TimelineTailPresentation(
        reserveVisible = tailIndicatorReserveVisible,
        dotVisible = showBottomFollowAnimation && timelineLoading,
        drainAmortizeGrace = drainAmortizeGrace,
    )
}

/**
 * Pure single-owner scroll gate for send-append auto-scroll: when [ChatSendStageState]
 * is active, the stage owns scroll positioning and the legacy jump-to-tail path must stay off.
 */
internal fun shouldAutoScrollOnSendAppend(
    enableAutoScroll: Boolean,
    pendingAdded: Int,
    directSendAppend: Boolean,
    sendStageActive: Boolean,
): Boolean = enableAutoScroll && !sendStageActive && (pendingAdded > 0 || directSendAppend)

@Composable
internal fun ChatTimelineScrollEffects(
    conversation: Conversation,
    pendingUserMessages: List<PendingUserMessage>,
    activeGeneration: Boolean,
    timelineLoadState: ConversationTimelineLoadState,
    chatTimelinePlan: ChatTimelinePlan,
    chatRegexes: List<AssistantRegex>,
    state: LazyListState,
    innerPadding: PaddingValues,
    settings: Settings,
    onLoadOlderTimeline: suspend () -> Unit,
    onRecentScrollChanged: (Boolean) -> Unit,
    sendStage: ChatSendStageState? = LocalChatSendStage.current,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val activity = LocalContext.current as? app.amber.agent.RouteActivity
    val conversationId = conversation.id.toString()
    val currentConversationState by rememberUpdatedState(conversation)
    val currentPendingUserMessages by rememberUpdatedState(pendingUserMessages)
    val timelineLoadStateState by rememberUpdatedState(timelineLoadState)
    val chatTimelinePlanState by rememberUpdatedState(chatTimelinePlan)
    val loadOlderTimelineState by rememberUpdatedState(onLoadOlderTimeline)
    val onRecentScrollChangedState by rememberUpdatedState(onRecentScrollChanged)
    LaunchedEffect(conversation.id, activeGeneration) {
        if (!activeGeneration) {
            ChatSendTransitionTracker.clear(conversationId)
        }
    }

    // MessageJumper 的"刚滚动过"信号：任何滚动开始即点亮，停止 1.5s 后熄灭。
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) {
            onRecentScrollChangedState(true)
        } else {
            delay(1500)
            onRecentScrollChangedState(false)
        }
    }

    // User manual drag immediately exits the send stage and hands back the reading anchor.
    // DragInteraction.Start fires strictly on real touch drag (never on programmatic scroll).
    LaunchedEffect(state, sendStage) {
        if (sendStage == null) return@LaunchedEffect
        state.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                sendStage.onUserDrag()
            }
        }
    }

    // Reset stage on branch switch (any existing node up to the staged user node changes
    // selectIndex, or the staged user node disappears from the conversation).
    LaunchedEffect(conversation.id, sendStage) {
        if (sendStage == null) return@LaunchedEffect
        var prevBranchById: Map<Uuid, Int>? = null
        snapshotFlow {
            val active = sendStage.isActive
            val stagedKey = sendStage.stagedMessageKey
            val nodes = currentConversationState.messageNodes
            Triple(active, stagedKey, nodes.map { it.id to it.selectIndex })
        }
            .distinctUntilChanged()
            .collect { (active, stagedKey, branchPairs) ->
                if (!active || stagedKey == null) {
                    prevBranchById = null
                    return@collect
                }
                val stagedIndex = branchPairs.indexOfLast { it.first == stagedKey }
                if (stagedIndex < 0) {
                    sendStage.reset()
                    prevBranchById = null
                    return@collect
                }
                val currentUpToStaged = branchPairs.take(stagedIndex + 1).toMap()
                val previous = prevBranchById
                if (previous != null) {
                    val branchSwitched = currentUpToStaged.any { (nodeId, selectIdx) ->
                        val prevSelectIdx = previous[nodeId]
                        prevSelectIdx != null && prevSelectIdx != selectIdx
                    }
                    if (branchSwitched) {
                        sendStage.reset()
                        prevBranchById = null
                        return@collect
                    }
                }
                prevBranchById = currentUpToStaged
            }
    }

    // Single scroll owner coordination:
    // 1. When a normal send has armed sendStage (pendingIntent matches this conversation)
    //    and a matching new user node appears, activate sendStage and perform at most one
    //    initial index-0 alignment owned by the stage.
    // 2. Otherwise (when sendStage is NOT active) fall back to the legacy direct-send
    //    return-to-tail gated by enableAutoScroll. Queue drains never arm sendStage and
    //    never yank history readers.
    val enableAutoScrollState by rememberUpdatedState(settings.displaySetting.enableAutoScroll)
    LaunchedEffect(state, conversation.id, sendStage) {
        var prevPendingCount = -1
        var prevNodeCount = -1
        var prevLastNodeId: Uuid? = null
        var prevLastUserNodeId: Uuid? = null
        snapshotFlow {
            val nodes = currentConversationState.messageNodes
            val lastNode = nodes.lastOrNull()
            val lastUserNode = nodes.lastOrNull { it.currentMessage.role == MessageRole.USER }
            SendAppendSnapshot(
                pendingCount = currentPendingUserMessages.size,
                nodeCount = nodes.size,
                lastNodeId = lastNode?.id,
                lastNodeRole = lastNode?.currentMessage?.role,
                lastUserNodeId = lastUserNode?.id,
                pendingIntent = sendStage?.pendingIntent,
                sendStageActive = sendStage?.isActive == true,
            )
        }.collect { snap ->
            val pendingAdded = if (prevPendingCount >= 0) snap.pendingCount - prevPendingCount else 0
            val directSendAppend = prevPendingCount == 0 &&
                prevNodeCount >= 0 &&
                snap.nodeCount > prevNodeCount &&
                snap.lastNodeId != null &&
                snap.lastNodeId != prevLastNodeId &&
                snap.lastNodeRole == MessageRole.USER
            val newUserNodeAppeared = prevNodeCount >= 0 &&
                snap.nodeCount > prevNodeCount &&
                snap.lastUserNodeId != null &&
                snap.lastUserNodeId != prevLastUserNodeId
            val stageMatchesPending = sendStage != null &&
                snap.pendingIntent?.conversationId == conversationId &&
                prevPendingCount == 0 &&
                newUserNodeAppeared
            prevPendingCount = snap.pendingCount
            prevNodeCount = snap.nodeCount
            prevLastNodeId = snap.lastNodeId
            prevLastUserNodeId = snap.lastUserNodeId

            if (stageMatchesPending) {
                val appearedKey = snap.lastUserNodeId!!
                sendStage!!.onUserNodeAppeared(appearedKey)
                if (sendStage.consumeInitialScroll()) {
                    state.requestScrollToItem(0)
                }
            } else if (
                shouldAutoScrollOnSendAppend(
                    enableAutoScroll = enableAutoScrollState,
                    pendingAdded = pendingAdded,
                    directSendAppend = directSendAppend,
                    sendStageActive = snap.sendStageActive || (sendStage?.isActive == true),
                )
            ) {
                state.requestScrollToItem(0)
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
                // reverseLayout: scrollBy(+) moves toward the list end, which is
                // now the visual top (older messages) — so volume-up scrolls up.
                scope.launch {
                    state.scrollBy(if (isVolumeUp) scrollAmount else -scrollAmount)
                }
                true
            } else false
        }
        activity?.volumeKeyListeners?.add(listener)
        onDispose {
            activity?.volumeKeyListeners?.remove(listener)
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

    LaunchedEffect(conversation.id, chatRegexes) {
        snapshotFlow {
            val latestConversation = currentConversationState
            val latestTimelinePlan = chatTimelinePlanState
            state.markdownPrewarmTexts(
                messageNodes = latestConversation.messageNodes,
                regexes = chatRegexes,
                loadingLastMessage = latestTimelinePlan.timelineLoading,
                timelinePlan = latestTimelinePlan,
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

}
