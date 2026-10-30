package app.amber.feature.ui.pages.chat

import app.amber.feature.ui.utils.amberTraceMeasure
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalScrollCaptureInProgress
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import app.amber.ai.ui.UIMessage
import app.amber.agent.R
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowDown
import app.amber.core.context.ActiveCompactBoundary
import app.amber.core.context.CompactLifecycleState
import app.amber.core.context.ConversationCompact
import app.amber.core.settings.Settings
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.service.ChatError
import app.amber.core.service.ConversationTimelineLoadState
import app.amber.core.service.PendingUserMessage
import app.amber.feature.ui.components.message.resolveThemeTryOnCardTarget
import app.amber.feature.ui.components.ui.ErrorCardsDisplay
import app.amber.feature.ui.theme.ThemePackageManager
import android.os.Trace
import app.amber.agent.BuildConfig
import kotlin.math.roundToInt
import kotlin.uuid.Uuid
import org.koin.compose.koinInject

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
    if (BuildConfig.DEBUG) Trace.beginSection("Amber ChatListNormal scaffold")
    val themePackageManager = koinInject<ThemePackageManager>()
    val activeThemeTryOn by themePackageManager.tryOn.collectAsState(initial = null)
    val themeTryOnCardTarget = remember(
        conversation.messageNodes,
        activeThemeTryOn?.pkg?.id,
        activeThemeTryOn?.candidateDigest,
    ) {
        resolveThemeTryOnCardTarget(conversation.messageNodes, activeThemeTryOn)
    }
    val scope = rememberCoroutineScope()
    val pendingUserMessagesState = rememberUpdatedState(pendingUserMessages)
    val compactInTimelineActive = isCompacting || compactLifecycleState.isActive
    val activeGeneration = loading || pendingUserMessages.isNotEmpty() || compactInTimelineActive
    val activeGenerationState by rememberUpdatedState(activeGeneration)
    var isRecentScroll by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val captureProgress = LocalScrollCaptureInProgress.current
    // "At the bottom" under reverseLayout means firstVisibleItemIndex == 0 with
    // only a hair of scroll offset — anything beyond this buffer means the user
    // has scrolled away from the tail and auto-follow must not yank them back.
    val bottomPinBufferPx = with(density) { 24.dp.toPx().roundToInt() }
    val sendTransitionSlidePx = with(density) { SendTransitionSlideDistance.roundToPx() }
    if (BuildConfig.DEBUG) Trace.beginSection("Amber chatlist suggestions")
    val actionSuggestions = if (activeGeneration) {
        // Suggestions are hidden for the whole generation. Avoid joining and
        // rescanning the growing assistant tail on every stream snapshot; the
        // settled branch below recomputes them when they can become visible.
        emptyList()
    } else {
        remember(conversation.messageNodes, conversation.chatSuggestions) {
            conversation.actionSuggestionTexts()
        }
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
    if (BuildConfig.DEBUG) Trace.endSection()
    val showBottomFollowAnimation = settings.displaySetting.showBottomFollowAnimation
    val latestMessage = conversation.messageNodes.lastOrNull()?.currentMessage
    val postSendState = chatTimelinePlan.postSendState
    val timelineLoading = chatTimelinePlan.timelineLoading
    val timelineBottomPadding = TimelineBottomSafetyPadding +
    if (postSendState.waitingForAssistantContent) PostSendWaitingBottomReserve else 0.dp
    val tailPresentation = rememberTimelineTailPresentation(
        conversation.id, latestMessage?.id, timelineLoading, showBottomFollowAnimation,
    )
    ChatTimelineScrollEffects(
        conversation = conversation,
        pendingUserMessages = pendingUserMessages,
        activeGeneration = activeGeneration,
        timelineLoadState = timelineLoadState,
        chatTimelinePlan = chatTimelinePlan,
        chatRegexes = settings.regexes,
        state = state,
        innerPadding = innerPadding,
        settings = settings,
        onLoadOlderTimeline = onLoadOlderTimeline,
        onRecentScrollChanged = { isRecentScroll = it },
    )

    if (BuildConfig.DEBUG) Trace.beginSection("Amber chatlist compaction+selection")
    val selection = rememberTimelineSelectionState(conversation.id)
    val compaction = rememberTimelineCompactionPresentation(
        conversation, contextCompacts, compactLifecycleState, isCompacting, streamingSummary, state,
    )

    // 对话大小警告对话框
    val sizeInfo = rememberConversationSizeInfo(conversation)
    if (BuildConfig.DEBUG) Trace.endSection()
    var showSizeWarningDialog by rememberSaveable(conversation.id) { mutableStateOf(true) }
    if (sizeInfo.showWarning && showSizeWarningDialog) {
        ConversationSizeWarningDialog(
            sizeInfo = sizeInfo,
            onDismiss = { showSizeWarningDialog = false }
        )
    }

    // ChatPage owns the canvas; leave the timeline transparent so its texture
    // stays visible between opaque message/tool cards without changing their theme tokens.
    // P8-06: 流式内容继续到达且用户不在底部时显示「回到底部」按钮。
    // reverseLayout 下"在底部" = firstVisibleItemIndex == 0 且偏移在缓冲内；
    // 用户上滑离开后自动跟随天然停止，点击按钮滚回 index 0 即恢复。
    // 不受 enableAutoScroll 门控：关闭自动跟随后，这是唯一的手动回底入口。
    val backToBottomVisible by remember(
        state,
        activeGeneration,
    ) {
        derivedStateOf {
            activeGeneration &&
            (
                state.firstVisibleItemIndex != 0 ||
                state.firstVisibleItemScrollOffset > bottomPinBufferPx
            )
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        app.amber.feature.ui.context.LocalReasoningScrollAnchor provides
            app.amber.feature.ui.context.rememberReasoningScrollAnchor(state),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
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
                    // Centred as padding rather than a narrower list, so the gutters still scroll.
                    val readingGutter = app.amber.feature.ui.adaptive.chatReadingGutter(
                        app.amber.feature.ui.adaptive.currentChatPaneWidth(),
                    )
                    // 可见窗口前后各预取若干条消息里的图片，滚入时直接命中内存缓存。
                    TimelineImagePrefetch(
                        listState = state,
                        plan = chatTimelinePlan,
                        messageNodes = conversation.messageNodes,
                    )
                    TimelineMarkdownPrefetch(
                        listState = state,
                        plan = chatTimelinePlan,
                        messageNodes = conversation.messageNodes,
                        regexes = settings.regexes,
                        sessionId = conversation.id,
                    )
                    if (BuildConfig.DEBUG) Trace.beginSection("Amber chatlist lazy column")
                    LazyColumn(
                        state = state,
                        // 底部锚定列表：index 0 是视觉底部（最新内容），流式增长从锚定的
                        // start 边（屏幕底缘）原生向上展开，钉底跟随不需要任何滚动调用。
                        reverseLayout = true,
                        contentPadding = PaddingValues(
                            start = TimelineHorizontalPadding + readingGutter,
                            top = TimelineTopPadding,
                            end = TimelineHorizontalPadding + readingGutter,
                            bottom = timelineBottomPadding,
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .amberTraceMeasure("Amber Chat list measure")
                            .padding(
                                top = innerPadding.calculateTopPadding(),
                                bottom = innerPadding.calculateBottomPadding(),
                            ),
                    ) {
                        chatTimelineItems(
                            conversation = conversation,
                            chatTimelinePlan = chatTimelinePlan,
                            timelineLoadState = timelineLoadState,
                            pendingUserMessagesState = pendingUserMessagesState,
                            compactLifecycleState = compactLifecycleState,
                            compaction = compaction,
                            selection = selection,
                            state = state,
                            bottomPinBufferPx = bottomPinBufferPx,
                            drainAmortizeGrace = tailPresentation.drainAmortizeGrace,
                            tailIndicatorReserveVisible = tailPresentation.reserveVisible,
                            tailIndicatorDotVisible = tailPresentation.dotVisible,
                            showBottomFollowAnimation = showBottomFollowAnimation,
                            processingStatus = processingStatus,
                            settings = settings,
                            themeTryOnCardTarget = themeTryOnCardTarget,
                            onRegenerate = onRegenerate,
                            onEdit = onEdit,
                            onQuote = onQuote,
                            onForkMessage = onForkMessage,
                            onDelete = onDelete,
                            onUpdateMessage = onUpdateMessage,
                            onToggleFavorite = onToggleFavorite,
                            onSaveToWorkspace = onSaveToWorkspace,
                            onToolApproval = onToolApproval,
                            onToolAnswer = onToolAnswer,
                            onOpenWorkspaceFile = onOpenWorkspaceFile,
                            onGenerativeWidgetAction = onGenerativeWidgetAction,
                            onMiniAppModify = onMiniAppModify,
                            onCancelPendingMessage = onCancelPendingMessage,
                            onOpenQueue = onOpenQueue,
                        )
                    }
                    if (BuildConfig.DEBUG) Trace.endSection()

                    if (BuildConfig.DEBUG) Trace.beginSection("Amber chatlist overlays")
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        // Fade only the message layer. Keep this behind every floating
                        // control so suggestion chips retain their text and fill contrast.
                        if (conversation.messageNodes.isNotEmpty()) {
                            val chatBg = LocalChatTheme.current.bg
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .background(
                                        Brush.verticalGradient(
                                            0.00f to chatBg.copy(alpha = 0f),
                                            0.75f to chatBg,
                                            1.00f to chatBg,
                                        )
                                    ),
                            )
                        }

                        if (compaction.showFloatingCompactMarker) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .zIndex(4f)
                                    .padding(horizontal = TimelineHorizontalPadding, vertical = 8.dp),
                            ) {
                                ContextCompactInProgressMarker(
                                    streamingText = compaction.activeCompactStreamingSummary,
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

                        ChatTimelineSelectionControls(
                            selection, conversation, timelineLoadState, scope, onEnsureTimelineLoaded,
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
                    if (BuildConfig.DEBUG) Trace.endSection()
                }
            }
        }
    }
    if (BuildConfig.DEBUG) Trace.endSection()
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
                    text = stringResource(R.string.chat_page_scroll_to_bottom),
                    style = MaterialTheme.typography.labelMedium,
                    color = chatTheme.ink,
                )
            }
        }
    }
}
