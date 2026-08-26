package app.amber.feature.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.X
import com.composables.icons.lucide.MousePointer2
import com.composables.icons.lucide.Check
import app.amber.core.context.ActiveCompactBoundary
import app.amber.core.context.CompactLifecycleState
import app.amber.core.context.CompactLifecycleStatus
import app.amber.core.context.ConversationCompact
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.service.ChatError
import app.amber.core.service.ConversationTimelineLoadState
import app.amber.core.service.PendingUserMessage
import app.amber.feature.ui.components.message.ChatMessage
import app.amber.feature.ui.components.message.ChatMessageVirtualItemContent
import app.amber.feature.ui.components.richtext.prewarmMarkdownContent
import app.amber.feature.ui.components.ui.ErrorCardsDisplay
import app.amber.feature.ui.components.ui.ListSelectableItem
import app.amber.feature.ui.components.ui.Tooltip
import app.amber.feature.ui.components.ui.workspaceColors
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
    globalErrors: List<ChatError> = emptyList(),
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    onRegenerate: (UIMessage) -> Unit,
    onEdit: (UIMessage) -> Unit,
    onQuote: (String) -> Unit = {},
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
    /** P3-02: 保存消息到 Workspace (由 flag 控制是否为 null). */
    onSaveToWorkspace: ((UIMessage) -> Unit)? = null,
    onLoadOlderTimeline: suspend () -> Unit = {},
    onEnsureTimelineLoaded: suspend () -> Conversation = { conversation },
    chatTimelinePlan: ChatTimelinePlan,
) {
    val scope = rememberCoroutineScope()
    val currentConversationState by rememberUpdatedState(conversation)
    val currentPendingUserMessages by rememberUpdatedState(pendingUserMessages)
    val compactInTimelineActive = isCompacting || compactLifecycleState.isActive
    val activeGeneration = loading || pendingUserMessages.isNotEmpty() || compactInTimelineActive
    val activeGenerationState by rememberUpdatedState(activeGeneration)
    val timelineLoadStateState by rememberUpdatedState(timelineLoadState)
    val loadOlderTimelineState by rememberUpdatedState(onLoadOlderTimeline)
    val ensureTimelineLoadedState by rememberUpdatedState(onEnsureTimelineLoaded)
    var isRecentScroll by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val captureProgress = LocalScrollCaptureInProgress.current
    // "At the bottom" under reverseLayout means firstVisibleItemIndex == 0 with
    // only a hair of scroll offset — anything beyond this buffer means the user
    // has scrolled away from the tail and auto-follow must not yank them back.
    val bottomPinBufferPx = with(density) { 24.dp.toPx().roundToInt() }
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
    val showBottomFollowAnimation = settings.displaySetting.showBottomFollowAnimation
    val conversationId = conversation.id.toString()
    val latestMessage = conversation.messageNodes.lastOrNull()?.currentMessage
    val postSendState = chatTimelinePlan.postSendState
    val timelineLoading = chatTimelinePlan.timelineLoading
    val timelineBottomPadding = TimelineBottomSafetyPadding +
        if (postSendState.waitingForAssistantContent) PostSendWaitingBottomReserve else 0.dp
    // reverseLayout 下 TimelineTail（index 0）就在锚定边：生成结束瞬间移除它的
    // 70dp 指示器 reserve 会让整个时间线单帧下坠。保留空间直到下一条消息成为
    // 尾部（与旧实现的 retainedTailIndicator 语义一致），圆点本身照常淡出。
    var retainedTailReserveMessageId by remember(conversation.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(timelineLoading, latestMessage?.id) {
        val latestId = latestMessage?.id?.toString()
        when {
            timelineLoading && latestId != null -> retainedTailReserveMessageId = latestId
            latestId != retainedTailReserveMessageId -> retainedTailReserveMessageId = null
        }
    }

    // 生成结束的瞬间 pacing buffer 才开始 drain：文字尾巴还要逐帧生长 ~1s，
    // 动作行也在此刻插入（+48dp）。高度摊铺器若随 timelineLoading 一起撤掉，
    // 这段增长退回单帧阶梯跳（"一行一行往上跳"），按钮插入也成了瞬时重排。
    // true→false 边沿给摊铺器留一个覆盖 drain 的宽限期（首次进会话不布防）。
    var drainAmortizeGrace by remember(conversation.id) { mutableStateOf(false) }
    var prevTimelineLoading by remember(conversation.id) { mutableStateOf(timelineLoading) }
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

    LaunchedEffect(conversation.id, activeGeneration) {
        if (!activeGeneration) {
            ChatSendTransitionTracker.clear(conversationId)
        }
    }

    // MessageJumper 的"刚滚动过"信号：任何滚动开始即点亮，停止 1.5s 后熄灭。
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) {
            isRecentScroll = true
        } else {
            delay(1500)
            isRecentScroll = false
        }
    }

    if (settings.displaySetting.enableAutoScroll) {
        // 发送统一回底，即使用户正滚在历史里阅读。监听计数覆盖所有发送路径
        // （输入栏/队列/建议/组件）而不逐个改调用点。`prevPendingCount == 0`
        // 守卫把"用户此刻发送"（此前队列空，直接追加 user 尾节点）与"队列排水
        // 追加"（排水前 pending 非空）区分开——后者不得拽走读历史的用户。
        // 其余结构性插入无需处理：TimelineTail 恒占 lazy index 0，Compose 的
        // key 锚定使插入永远发生在它之上（index ≥ 1），钉底视图原生保持不动。
        LaunchedEffect(state, conversation.id) {
            var prevPendingCount = -1
            var prevNodeCount = -1
            var prevLastNodeId: Uuid? = null
            snapshotFlow {
                val lastNode = currentConversationState.messageNodes.lastOrNull()
                Triple(
                    currentPendingUserMessages.size,
                    currentConversationState.messageNodes.size,
                    lastNode?.id to lastNode?.currentMessage?.role,
                )
            }.collect { (pendingCount, nodeCount, lastIdAndRole) ->
                val (lastNodeId, lastRole) = lastIdAndRole
                val pendingAdded = if (prevPendingCount >= 0) pendingCount - prevPendingCount else 0
                val directSendAppend = prevPendingCount == 0 &&
                    prevNodeCount >= 0 &&
                    nodeCount > prevNodeCount &&
                    lastNodeId != null &&
                    lastNodeId != prevLastNodeId &&
                    lastRole == MessageRole.USER
                prevPendingCount = pendingCount
                prevNodeCount = nodeCount
                prevLastNodeId = lastNodeId
                if (pendingAdded > 0 || directSendAppend) {
                    state.requestScrollToItem(0)
                }
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
    val chatRegexes = settings.regexes

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
        chatRegexes,
    ) {
        snapshotFlow {
            state.markdownPrewarmTexts(
                messageNodes = conversation.messageNodes,
                regexes = chatRegexes,
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
    val tailIndicatorReserveVisible = showBottomFollowAnimation &&
        (
            timelineLoading ||
                (retainedTailReserveMessageId != null &&
                    latestMessage?.id?.toString() == retainedTailReserveMessageId)
            )
    val tailIndicatorDotVisible = showBottomFollowAnimation && timelineLoading
    // P8-06: 流式内容继续到达且用户不在底部时显示「回到底部」按钮。
    // reverseLayout 下"在底部" = firstVisibleItemIndex == 0 且偏移在缓冲内；
    // 用户上滑离开后自动跟随天然停止，点击按钮滚回 index 0 即恢复。
    val backToBottomVisible by remember(
        state,
        activeGeneration,
        settings.displaySetting.enableAutoScroll,
    ) {
        derivedStateOf {
            settings.displaySetting.enableAutoScroll &&
                activeGeneration &&
                (
                    state.firstVisibleItemIndex != 0 ||
                        state.firstVisibleItemScrollOffset > bottomPinBufferPx
                    )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
    ) {
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
            // 底部锚定列表：index 0 是视觉底部（最新内容），流式增长从锚定的
            // start 边（屏幕底缘）原生向上展开，钉底跟随不需要任何滚动调用。
            reverseLayout = true,
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
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding(),
                ),
        ) {
            val hasTailCompletedCompactMarkers =
                completedMarkersByTimelineEndIndex[tailTimelineEndIndex].orEmpty().isNotEmpty()
            val hasTailLifecycleCompletedCompactMarker =
                lifecycleCompletedTimelineEndIndex == tailTimelineEndIndex
            val hasTailActiveCompactMarker =
                activeCompactTimelineEndIndex == tailTimelineEndIndex
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
                            contentType = "post-send-hidden-assistant",
                        ) {
                            // Defensive zero-size placeholder: the plan counts
                            // this entry, and under reversed emission every
                            // older message would come after it — skipping the
                            // item would misalign lazyItemMessageIndexes with
                            // real lazy indexes. (Currently unreachable: the
                            // hidden tail assistant is always protected as a
                            // single item, see tailAssistantMessageIndexes.)
                            Spacer(Modifier.fillMaxWidth())
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
                            // iMessage-style send entrance: the just-sent user
                            // bubble springs up from the input bar's direction.
                            // The claim is one-shot and windowed (see tracker),
                            // and only the freshly landed tail can take it —
                            // scroll-backs re-composing old user items never
                            // re-animate.
                            val playSendEntrance = node.currentMessage.role == MessageRole.USER &&
                                index >= conversation.messageNodes.lastIndex - 1 &&
                                remember(node.id) {
                                    ChatSendTransitionTracker.consumeSendEntrance(conversationId)
                                }
                            Column(
                                modifier = Modifier
                                    .then(
                                        // 流式尾部平滑生长：reverseLayout 锚定底部边，
                                        // 每次换行/增块的高度增长默认是单帧阶梯跳
                                        // （"一行一行往上跳"）。单处 animateContentSize
                                        // 把每次增高变成连续伸展 → 已读内容平滑上滑。
                                        // 规格用 no-bounce 弹簧而不是 tween：换行每
                                        // ~150ms 重定向一次，弹簧重定向时保持当前速度
                                        // （速度连续），tween 每次重启都从 0 加速——
                                        // 那是 2026-08-16 录屏里 6Hz 脉动的来源。
                                        // 启用窗口覆盖 loading + drain 宽限期（生成结束
                                        // 后尾巴还在逐帧生长、动作行也在此刻插入）。
                                        // 注意 2026-05-14 教训（ChatMessage.kt:235 注释）：
                                        // 嵌套多层动画曾致流式卡顿——只允许这一处、
                                        // 永远不要在消息内部再叠加。
                                        if (isLoadingMessage || (isLastMessage && drainAmortizeGrace)) {
                                            Modifier.animateContentSize(
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                                    stiffness = Spring.StiffnessMedium,
                                                ),
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .padding(bottom = TimelineItemSpacing)
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
                                    modifier = (if (isPreCompacted) Modifier.alpha(0.4f) else Modifier)
                                        .then(sendEntranceModifier(play = playSendEntrance)),
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
                                        regexes = chatRegexes,
                                        loading = isLoadingMessage,
                                        timelineLoading = timelineLoading,
                                        onRegenerate = {
                                            onRegenerate(node.currentMessage)
                                        },
                                        onEdit = {
                                            onEdit(node.currentMessage)
                                        },
                                        onQuote = onQuote,
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
                                        onSaveToWorkspace = onSaveToWorkspace,
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

                    is ChatTimelineEntry.VirtualMessage -> {
                        val index = entry.messageIndex
                        val node = entry.node
                        val virtualItem = entry.item
                        val isLastMessage = index == conversation.messageNodes.lastIndex
                        val isLoadingMessage = timelineLoading && isLastMessage
                        // In reversed emission the entry at planIndex - 1 is the
                        // slice visually BELOW this one — a slice's bottom
                        // padding separates it from that lower slice (padding
                        // stays in visual coordinates under reverseLayout).
                        // The reading-first slice (virtualIndex == 0) is
                        // emitted last and shows the compact markers /
                        // selection checkbox at visual top.
                        val sliceBelowEntry = chatTimelinePlan.entries.getOrNull(planIndex - 1)
                        val sliceBelow = (sliceBelowEntry as? ChatTimelineEntry.VirtualMessage)
                            ?.takeIf { it.messageIndex == index }
                        val bottomPadding = when {
                            sliceBelow == null -> TimelineItemSpacing
                            sliceBelow.item.isAdjacentMarkdownChild(virtualItem) -> 0.dp
                            else -> TimelineMessageInnerSpacing
                        }
                        val virtualItemKey = "${node.id}:${virtualItem.keySuffix}"
                        item(
                            key = virtualItemKey,
                            contentType = "message-${node.currentMessage.role}-virtual-${virtualItem.keySuffix.substringBefore('-')}",
                        ) {
                            Column(
                                modifier = Modifier.padding(bottom = bottomPadding),
                            ) {
                                if (entry.virtualIndex == 0) {
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
                                    modifier = if (visualCompactedTimelineEndIndex?.let { index <= it } == true) {
                                        Modifier.alpha(0.4f)
                                    } else {
                                        Modifier
                                    },
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
                                    showCheckbox = entry.virtualIndex == 0,
                                ) {
                                    val messageModel = remember(
                                        node.currentMessage.modelId,
                                        node.currentMessage.role,
                                        isLoadingMessage,
                                        settings.providers,
                                        settings.chatModelId,
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
                                        regexes = chatRegexes,
                                        loading = isLoadingMessage,
                                        onRegenerate = {
                                            onRegenerate(node.currentMessage)
                                        },
                                        onEdit = {
                                            onEdit(node.currentMessage)
                                        },
                                        onQuote = onQuote,
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
                                        onSaveToWorkspace = onSaveToWorkspace,
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
                        val pendingMessage = currentPendingUserMessages.getOrNull(entry.pendingIndex)
                            ?: return@forEachIndexed
                        item(
                            key = "pending-${pendingMessage.id}",
                            contentType = "pending",
                        ) {
                            Box(modifier = Modifier.padding(bottom = TimelineItemSpacing)) {
                                PendingUserMessageBubble(
                                    message = pendingMessage,
                                    onCancel = { onCancelPendingMessage(pendingMessage.id) },
                                    queueCount = if (entry.pendingIndex == currentPendingUserMessages.lastIndex) {
                                        currentPendingUserMessages.size
                                    } else {
                                        null
                                    },
                                    onOpenQueue = onOpenQueue,
                                )
                            }
                        }
                    }

                    ChatTimelineEntry.TailCompactMarkers -> {
                        item(
                            key = tailCompactItemKey,
                            contentType = "compact-timeline-tail",
                        ) {
                            if (
                                hasTailCompletedCompactMarkers ||
                                hasTailLifecycleCompletedCompactMarker ||
                                hasTailActiveCompactMarker
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
                            } else {
                                // Zero-size placeholder: the plan always counts
                                // this entry, so skipping the item would shift
                                // lazyItemMessageIndexes against real lazy indexes.
                                Spacer(Modifier.fillMaxWidth())
                            }
                        }
                    }

                    ChatTimelineEntry.TimelineTail -> {
                        item(
                            key = TimelineTailKey,
                            contentType = "timeline-tail",
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                if (tailIndicatorReserveVisible) {
                                    TimelineTailWorkingIndicator(
                                        processingStatus = processingStatus,
                                        visible = tailIndicatorDotVisible,
                                        modifier = Modifier.padding(bottom = TimelineItemSpacing),
                                    )
                                }
                                Spacer(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(ScrollBottomSpacerHeight)
                                )
                            }
                        }
                    }
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

            // P8-06/P8-07: 底部叠层——错误卡片（当前会话消息错误 + 全局 banner）
            // 与「回到底部」按钮纵向排列互不遮挡；位于输入区（Scaffold bottomBar）
            // 之上，不遮挡 Stop/输入区。
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(5f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 错误消息卡片
                ErrorCardsDisplay(
                    errors = errors,
                    globalErrors = globalErrors,
                    onDismissError = onDismissError,
                    onClearAllErrors = onClearAllErrors,
                )
                BackToBottomButton(
                    visible = backToBottomVisible && !captureProgress,
                    onClick = {
                        scope.launch {
                            // 短促平滑回底而非瞬移；reverseLayout 下 index 0 即底部，
                            // 滚回后原生跟随自动恢复。
                            state.animateScrollToItem(0)
                            // 程序触发的滚动不应点亮 MessageJumper 的
                            // "刚滚动过" 信号。
                            isRecentScroll = false
                        }
                    },
                )
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
                            Icon(Lucide.X, null)
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
                            Icon(Lucide.MousePointer2, null)
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
                            Icon(Lucide.Check, null)
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
                show = isRecentScroll &&
                    !state.isScrollInProgress &&
                    !activeGenerationState &&
                    settings.displaySetting.showMessageJumper &&
                    !captureProgress,
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

@Composable
private fun BackToBottomButton(
    visible: Boolean,
    onClick: () -> Unit,
) {
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.padding(bottom = 12.dp),
        enter = fadeIn(animationSpec = tween(140)) +
            slideInVertically(animationSpec = tween(140), initialOffsetY = { it }),
        exit = fadeOut(animationSpec = tween(80)) +
            slideOutVertically(animationSpec = tween(80), targetOffsetY = { it }),
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            color = chatTheme.surface,
            border = BorderStroke(width = 1.dp, color = chatTheme.surfaceEdge),
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Lucide.ArrowDown,
                    contentDescription = null,
                    tint = chatTheme.inkSoft,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "回到底部",
                    style = MaterialTheme.typography.labelMedium,
                    color = chatTheme.ink,
                )
            }
        }
    }
}

/**
 * iMessage-style send entrance for the just-sent user bubble: a spring slide
 * up from the input bar's direction with a slight bottom-right-anchored scale.
 * Pure graphicsLayer — the item's layout slot is final from frame one, the
 * bubble just travels into it, so the surrounding timeline never reflows.
 */
@Composable
private fun sendEntranceModifier(play: Boolean): Modifier {
    if (!play) return Modifier
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.8f,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }
    val slidePx = with(LocalDensity.current) { SendEntranceSlideDistance.toPx() }
    return Modifier.graphicsLayer {
        val eased = progress.value
        translationY = slidePx * (1f - eased)
        alpha = (0.4f + 0.6f * eased).coerceIn(0f, 1f)
        val scale = (0.96f + 0.04f * eased).coerceAtMost(1.01f)
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin(0.92f, 1f)
    }
}
