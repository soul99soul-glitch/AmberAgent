package me.rerere.rikkahub.ui.pages.chat

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowDownDouble
import me.rerere.hugeicons.stroke.ArrowUpDouble
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Cancel01
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.DisposableEffect
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
import me.rerere.rikkahub.ui.components.message.chatMessageVirtualizationPrewarmTexts
import me.rerere.rikkahub.ui.components.richtext.prewarmMarkdownContent
import me.rerere.rikkahub.ui.components.ui.ErrorCardsDisplay
import me.rerere.rikkahub.ui.components.ui.ListSelectableItem
import me.rerere.rikkahub.ui.components.ui.PigLoadingIndicator
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.hooks.ImeLazyListAutoScroller
import me.rerere.rikkahub.utils.plus
import kotlin.uuid.Uuid

private const val TAG = "ChatList"
private const val LoadingIndicatorKey = "LoadingIndicator"
private const val ScrollBottomKey = "ScrollBottomKey"
private val TimelineHorizontalPadding = 16.dp
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

@Composable
private fun ContextCompactMarker(modifier: Modifier = Modifier) {
    val workspace = workspaceColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
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
    val density = LocalDensity.current
    val bottomFollowBufferPx = with(density) { 48.dp.toPx().toInt() }
    val activity = LocalContext.current as? me.rerere.rikkahub.RouteActivity
    val workspace = workspaceColors()

    fun beginProgrammaticScroll(): Int {
        val token = programmaticScrollToken + 1
        programmaticScrollToken = token
        programmaticScrollInProgress = true
        return token
    }

    fun endProgrammaticScroll(token: Int) {
        scope.launch {
            withFrameNanos { }
            delay(120)
            if (programmaticScrollToken == token) {
                programmaticScrollInProgress = false
            }
        }
    }

    fun enterIdleFollowMode() {
        followMode = TimelineFollowMode.Idle
        autoFollowResumeToken += 1
    }

    fun resumeBottomFollow() {
        followMode = TimelineFollowMode.FollowingBottom
        autoFollowResumeToken += 1
    }

    fun pauseAutoFollowTemporarily(
        mode: TimelineFollowMode,
        scheduleIdleReturn: Boolean,
    ) {
        followMode = mode
        if (scheduleIdleReturn) {
            autoFollowResumeToken += 1
        }
    }

    suspend fun scrollToTimelineBottom() {
        val token = beginProgrammaticScroll()
        try {
            var lastIndex = state.layoutInfo.totalItemsCount - 1
            if (lastIndex >= 0) {
                state.scrollToItem(lastIndex)
            }
            withFrameNanos { }
            lastIndex = state.layoutInfo.totalItemsCount - 1
            if (lastIndex >= 0 && !state.isAtTimelineBottom(bottomFollowBufferPx)) {
                state.scrollToItem(lastIndex)
            }
        } finally {
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
                scope.launch { state.scrollBy(if (isVolumeUp) -scrollAmount else scrollAmount) }
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
        if (!settings.displaySetting.enableAutoScroll || !activeGeneration) {
            enterIdleFollowMode()
        } else if (
            followMode == TimelineFollowMode.Idle &&
            (
                state.isAtTimelineBottom(bottomFollowBufferPx) ||
                    state.isNearListEnd(bufferItems = 4)
                )
        ) {
            resumeBottomFollow()
        }
    }

    val latestMessage = conversation.currentMessages.lastOrNull()
    LaunchedEffect(conversation.id, latestMessage?.id, activeGeneration) {
        if (
            activeGeneration &&
            settings.displaySetting.enableAutoScroll &&
            latestMessage?.role == MessageRole.USER
        ) {
            resumeBottomFollow()
        }
    }

    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) {
            isRecentScroll = true
            if (activeGenerationState && !programmaticScrollInProgress) {
                pauseAutoFollowTemporarily(
                    mode = TimelineFollowMode.PausedForUser,
                    scheduleIdleReturn = true,
                )
            }
        } else {
            if (activeGenerationState && !programmaticScrollInProgress) {
                if (state.isAtTimelineBottom(bottomFollowBufferPx)) {
                    resumeBottomFollow()
                } else {
                    pauseAutoFollowTemporarily(
                        mode = TimelineFollowMode.PausedForUser,
                        scheduleIdleReturn = true,
                    )
                }
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
        if (
            activeGeneration &&
            settings.displaySetting.enableAutoScroll &&
            followMode != TimelineFollowMode.Idle &&
            followMode != TimelineFollowMode.FollowingBottom
        ) {
            val token = autoFollowResumeToken
            delay(8_000)
            if (
                token == autoFollowResumeToken &&
                activeGenerationState &&
                followMode != TimelineFollowMode.Idle &&
                followMode != TimelineFollowMode.FollowingBottom &&
                !state.isScrollInProgress
            ) {
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
            val latestRenderToken = conversation.latestRenderToken()
            LaunchedEffect(
                latestRenderToken,
                processingStatus,
                conversation.messageNodes.size,
                pendingUserMessages.size,
                loading,
            ) {
                if (
                    activeGenerationState &&
                    followMode == TimelineFollowMode.FollowingBottom &&
                    !state.isScrollInProgress
                ) {
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
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Only pause follow on sustained press (drag intent), not quick taps
                        val up = withTimeoutOrNull(150) { waitForUpOrCancellation() }
                        if (up == null && activeGenerationState && settings.displaySetting.enableAutoScroll) {
                            pauseAutoFollowTemporarily(
                                mode = TimelineFollowMode.PausedForUser,
                                scheduleIdleReturn = true,
                            )
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
                            markers.forEach {
                                ContextCompactMarker(
                                    modifier = Modifier.padding(bottom = TimelineItemSpacing)
                                )
                            }
                            ListSelectableItem(
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
                                modifier = Modifier.padding(bottom = bottomPadding)
                            ) {
                                if (isFirstVirtualItem) {
                                    val markers = compactMarkersByEndIndex[index - 1].orEmpty()
                                    markers.forEach {
                                        ContextCompactMarker(
                                            modifier = Modifier.padding(bottom = TimelineItemSpacing)
                                        )
                                    }
                                }
                                TimelineSelectableMessageItem(
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
                show = isRecentScroll && !state.isScrollInProgress && settings.displaySetting.showMessageJumper && !captureProgress,
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
    return lastVisibleItem.offset + lastVisibleItem.size <= layoutInfo.viewportEndOffset + bufferPx
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
        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.history_page_search)) },
            leadingIcon = {
                Icon(
                    imageVector = HugeIcons.Search01,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = "Clear",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            singleLine = true,
            shape = CircleShape,
            maxLines = 1,
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (!isUser) Modifier.padding(end = 24.dp) else Modifier
                        ),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    onJumpToMessage(originalIndex)
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
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
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
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
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = {
                    scope.launch {
                        state.scrollToItem(0)
                    }
                },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowUpDouble,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(
                            (state.firstVisibleItemIndex - 1).fastCoerceAtLeast(
                                0
                            )
                        )
                    }
                },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowUp01,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(state.firstVisibleItemIndex + 1)
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.scrollToItem(state.layoutInfo.totalItemsCount - 1)
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f),
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowDownDouble,
                    contentDescription = stringResource(R.string.chat_page_scroll_to_bottom),
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
        }
    }
}
