package app.amber.feature.ui.pages.chat

import androidx.compose.foundation.layout.PaddingValues
import app.amber.ai.ui.UIMessage
import app.amber.core.service.ChatError
import app.amber.feature.ui.context.Navigator
import app.amber.core.context.ActiveCompactBoundary
import app.amber.core.context.ConversationCompact
import app.amber.core.service.ConversationTimelineLoadState
import app.amber.feature.ui.hooks.ChatInputState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.core.utils.navigateToChatPage
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.utils.ChatPerfTargetKind
import app.amber.feature.ui.utils.ChatPerfTrace
import com.dokar.sonner.ToastType
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.core.settings.Settings
import app.amber.core.context.CompactLifecycleState
import app.amber.core.model.Conversation
import app.amber.core.service.PendingUserMessage
import kotlin.uuid.Uuid

@Composable
internal fun ChatPageTimelineContent(
    timelineReady: Boolean,
    timelinePrepared: Boolean,
    vm: ChatVM,
    resolvedNodeId: Uuid?,
    state: LazyListState,
    timelineLoadState: ConversationTimelineLoadState,
    pendingUserMessages: List<PendingUserMessage>,
    compactLifecycleState: CompactLifecycleState,
    isCompacting: Boolean,
    loading: Boolean,
    settings: Settings,
    content: @Composable (Conversation, ChatTimelinePlan) -> Unit,
) {
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    // Initialization publishes the conversation and its load state separately. While the
    // spinner is visible, or the route is still sliding in, do not parse and plan history
    // that cannot be displayed yet.
    val timelineConversation = if (timelinePrepared && timelineReady) {
        conversation
    } else {
        remember(conversation.id) { conversation.copy(messageNodes = emptyList()) }
    }
    val compactInTimelineActive = isCompacting || compactLifecycleState.isActive
    val activeGeneration = loading || pendingUserMessages.isNotEmpty() || compactInTimelineActive
    val chatTimelinePlan = rememberChatTimelinePlan(
        conversation = timelineConversation,
        regexes = settings.regexes,
        showAssistantBubble = settings.displaySetting.showAssistantBubble,
        loading = loading,
        activeGeneration = activeGeneration,
        hasHistoryLoadingItem = !timelineLoadState.isFullyLoaded,
        pendingMessageCount = pendingUserMessages.size,
        listState = state,
    )

    LaunchedEffect(resolvedNodeId, conversation.messageNodes.size, timelineLoadState.initialized, timelineLoadState.isFullyLoaded, timelinePrepared, timelineReady) {
        if (resolvedNodeId != null && !vm.chatListInitialized) {
            // A deep-link target may live on a history page that has not been loaded yet.
            val index = conversation.messageNodes.indexOfFirst { it.id == resolvedNodeId }
            if (index >= 0) {
                if (!timelinePrepared || !timelineReady) return@LaunchedEffect
                val listIndex = chatTimelinePlan.lazyIndexForMessage(index)
                if (listIndex != null) {
                    state.scrollToItem(listIndex)
                    vm.chatListInitialized = true
                } else if (timelineLoadState.initialized && timelineLoadState.isFullyLoaded) {
                    vm.chatListInitialized = true
                }
            } else if (!timelineLoadState.isFullyLoaded) {
                vm.ensureTimelineLoaded()
            } else if (timelineLoadState.initialized) {
                vm.chatListInitialized = true
            }
        }
    }

    content(conversation, chatTimelinePlan)
}


@Composable
internal fun ChatPageTimelineHost(
    timelineReady: Boolean,
    timelinePrepared: Boolean,
    conversationId: Uuid,
    hasMessages: Boolean,
    inputState: ChatInputState,
    inputActions: ChatPageInputActions,
    loading: Boolean,
    processingStatus: String?,
    setting: Settings,
    timelineLoadState: ConversationTimelineLoadState,
    pendingUserMessages: List<PendingUserMessage>,
    contextCompacts: List<ConversationCompact>,
    activeCompactBoundary: ActiveCompactBoundary?,
    compactLifecycleState: CompactLifecycleState,
    isCompacting: Boolean,
    streamingSummary: String,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    resolvedNodeId: Uuid?,
    configurationIssue: ChatConfigurationIssue?,
    errors: List<ChatError>,
    globalErrors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    onPreviewWorkspaceFile: (String) -> Unit,
    onSaveToWorkspace: ((UIMessage) -> Unit)?,
    innerPadding: PaddingValues,
    previewMode: Boolean,
    onExitPreview: () -> Unit,
    jumpTarget: TimelineJumpTarget?,
    onJumpTargetConsumed: () -> Unit,
    timelineHighlight: TimelineHighlightState,
    onFillSuggestion: (String) -> Unit,
    onOpenQueue: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resourceContext = context
    val toaster = LocalToaster.current
    // History fades in once it can compose; a page shown without an enter slide
    // (returning from another screen) starts fully visible.
    val timelineVisible = timelineReady && timelinePrepared
    val readinessTick = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    if (ChatPerfTrace.enabled) {
        DisposableEffect(Unit) {
            ChatPerfTrace.readinessInvalidation = { readinessTick.intValue++ }
            onDispose { ChatPerfTrace.readinessInvalidation = null }
        }
    }
    val timelineFade = remember(conversationId) { Animatable(if (timelineVisible) 1f else 0f) }
    LaunchedEffect(timelineVisible) {
        if (timelineVisible) timelineFade.animateTo(1f, tween(durationMillis = 180))
        else timelineFade.snapTo(0f)
    }

    ChatPageTimelineContent(
        timelineReady = timelineReady,
        timelinePrepared = timelinePrepared,
        vm = vm,
        resolvedNodeId = resolvedNodeId,
        state = chatListState,
        timelineLoadState = timelineLoadState,
        pendingUserMessages = pendingUserMessages,
        compactLifecycleState = compactLifecycleState,
        isCompacting = isCompacting,
        loading = loading,
        settings = setting,
    ) { timelineConversation, chatTimelinePlan ->
        val targetNode = timelineConversation.messageNodes.lastOrNull()
        SideEffect {
            if (timelineReady && timelinePrepared) {
                ChatPerfTrace.beginTarget(
                    kind = if (targetNode == null) {
                        ChatPerfTargetKind.EMPTY_HERO
                    } else {
                        ChatPerfTargetKind.MESSAGE
                    },
                    nodeId = targetNode?.id,
                )
            }
        }
        val latestJumpConversation by rememberUpdatedState(timelineConversation)
        val latestJumpPlan by rememberUpdatedState(chatTimelinePlan)
        LaunchedEffect(jumpTarget) {
            val target = jumpTarget ?: return@LaunchedEffect
            try {
                fun messageIndex() = latestJumpConversation.messageNodes.indexOfFirst { it.id == target.nodeId }
                if (messageIndex() < 0) {
                    // The node may sit on an older page. Keyed on the target only, so page
                    // merges cannot cancel the load half-way.
                    val full = vm.ensureTimelineLoaded()
                    if (full.messageNodes.none { it.id == target.nodeId }) return@LaunchedEffect
                }
                val bottomIndex = withTimeoutOrNull(2_000) {
                    snapshotFlow {
                        messageIndex().takeIf { it >= 0 }?.let(latestJumpPlan::lazyIndexForMessage)
                    }.filterNotNull().first()
                } ?: return@LaunchedEffect
                val node = latestJumpConversation.messageNodes.first { it.id == target.nodeId }
                // The variant may have been switched since the recap was generated.
                if (target.messageId != null &&
                    node.messages.getOrNull(node.selectIndex)?.id?.toString() != target.messageId
                ) {
                    return@LaunchedEffect
                }
                onExitPreview()
                val topIndex = latestJumpPlan.lazyItemMessageIndexes.lastIndexOf(messageIndex())
                    .takeIf { it >= bottomIndex } ?: bottomIndex
                chatListState.revealMessage(bottomIndex, topIndex)
                scope.launch { timelineHighlight.flash(target.nodeId) }
            } finally {
                onJumpTargetConsumed()
            }
        }
        // V3: timeline 未初始化时 (conversation 刚切换), 用 spinner 完全覆盖整个 chat 区域,
        //   不渲染 ChatList / hero 这些底层内容. 加载完 (initialized=true) 才显示真实内容,
        //   避免"空白态闪一下再切到历史对话"的副作用.
        if (!timelineReady && timelinePrepared) {
            // The enter slide stays light: history composes after it lands.
            Box(Modifier.fillMaxSize())
        } else if (!timelinePrepared) {
            val chatTheme = LocalChatTheme.current
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(chatTheme.bg),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = chatTheme.accent,
                    trackColor = chatTheme.accent.copy(alpha = 0.16f),
                    strokeWidth = 2.4.dp,
                    modifier = Modifier.size(28.dp),
                )
            }
        } else {
            // A draw callback, not withFrameNanos: the background prewarm is only
            // released after this layer has actually been drawn for the first
            // time. reset per mount so restore/re-entry waits for its own draw.
            val firstDraw = remember(conversationId) { booleanArrayOf(false) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        // Draw-scope state read: late parse/image/viewport
                        // readiness invalidates a fresh draw of ready content.
                        readinessTick.intValue
                        drawContent()
                        if (ChatPerfTrace.enabled) {
                            ChatPerfTrace.onTargetDraw(timelineFade.value)
                        }
                        if (!firstDraw[0]) {
                            firstDraw[0] = true
                            vm.onTimelineFirstFrameRendered()
                        }
                    }
            ) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = timelineFade.value }) {
                ChatList(
                    innerPadding = innerPadding,
                    conversation = timelineConversation,
                    timelineLoadState = timelineLoadState,
                    pendingUserMessages = pendingUserMessages,
                    contextCompacts = contextCompacts,
                    activeCompactBoundary = activeCompactBoundary,
                    compactLifecycleState = compactLifecycleState,
                    isCompacting = isCompacting,
                    streamingSummary = streamingSummary,
                    state = chatListState,
                    loading = loading,
                    processingStatus = processingStatus,
                    previewMode = previewMode,
                    settings = setting,
                    errors = errors,
                    globalErrors = globalErrors,
                    onDismissError = onDismissError,
                    onClearAllErrors = onClearAllErrors,
                    onRegenerate = {
                        if (configurationIssue != null) {
                            toaster.show(context.getString(configurationIssue.messageRes), type = ToastType.Error)
                        } else {
                            vm.regenerateAtMessage(it)
                        }
                    },
                    onEdit = {
                        inputState.editingMessage = it.id
                        vm.replaceInputContents(it.parts)
                    },
                    onQuote = { quote ->
                        if (quote.isNotEmpty()) {
                            val current = inputState.textContent.text.toString()
                            if (current.isBlank()) {
                                inputState.setMessageText(quote)
                            } else {
                                inputState.appendText("\n\n$quote")
                            }
                        }
                    },
                    onForkMessage = {
                        scope.launch {
                            try {
                                val fork = vm.forkMessage(message = it)
                                navigateToChatPage(navController, chatId = fork.id)
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                toaster.show(
                                    error.message ?: resourceContext.getString(R.string.error_title_operation),
                                    type = ToastType.Error,
                                )
                            }
                        }
                    },
                    onDelete = {
                        if (loading) {
                            vm.showDeleteBlockedWhileGeneratingError()
                        } else {
                            vm.deleteMessage(it)
                        }
                    },
                    onUpdateMessage = { newNode ->
                        vm.selectMessageNode(newNode.id, newNode.selectIndex)
                    },
                    onClickSuggestion = { suggestion ->
                        val text = suggestion.trim()
                        if (text.isNotEmpty()) {
                            onFillSuggestion(text)
                        }
                    },
                    onLongClickSuggestion = { suggestion ->
                        inputActions.sendSuggestion(suggestion)
                    },
                    onJumpToMessage = { index ->
                        onExitPreview()
                        scope.launch {
                            val listIndex = chatTimelinePlan.lazyIndexForMessage(index)
                                ?: return@launch
                            chatListState.animateScrollToItem(listIndex)
                        }
                    },
                    onToolApproval = { toolCallId, approved, reason ->
                        vm.handleToolApproval(toolCallId, approved, reason)
                    },
                    onToolAnswer = { toolCallId, answer ->
                        vm.handleToolAnswer(toolCallId, answer)
                    },
                    onOpenWorkspaceFile = { path ->
                        onPreviewWorkspaceFile(path)
                    },
                    onSaveToWorkspace = onSaveToWorkspace,
                    onToggleFavorite = { node ->
                        vm.toggleMessageFavorite(node)
                    },
                    onCancelPendingMessage = { messageId ->
                        vm.cancelPendingUserMessage(messageId)
                    },
                    onOpenQueue = {
                        onOpenQueue()
                    },
                    onGenerativeWidgetAction = { instruction ->
                        inputActions.sendWidgetAction(instruction)
                    },
                    onMiniAppModify = inputActions::sendSuggestion,
                    onLoadOlderTimeline = {
                        vm.loadOlderTimelinePage()
                    },
                    onEnsureTimelineLoaded = {
                        vm.ensureTimelineLoaded()
                    },
                    chatTimelinePlan = chatTimelinePlan,
                )
                // V3 空白态: hero 问候语 (此 else 分支已守护 initialized=true, 不会闪现)
                // review P2 #3: 之前 (isEmpty && loadingJob==null) 在 "loading && empty" 中间态没 UI,
                // 401/慢响应时整屏空白看着像卡死. 改成 isEmpty 时 hero 一直保留 (loading 时 hero 上叠
                // 半透蒙层 spinner 反馈), 直到第一个 chunk 落 (messageNodes 非空) 再切到 ChatList.
                if (!hasMessages) {
                    SideEffect { ChatPerfTrace.onEmptyHeroComposed() }
                    val startSuggestions by rememberChatStartSuggestions(vm, setting)
                    val nick = setting.displaySetting.userNickname.trim()
                    val heroText = if (nick.isNotEmpty()) {
                        stringResource(R.string.chat_page_hero_greeting_with_name, nick)
                    } else {
                        stringResource(R.string.chat_page_hero_greeting)
                    }
                    val inputHasContent = inputState.textContent.text.isNotEmpty() ||
                        inputState.messageContent.isNotEmpty() || inputState.attachmentImports.isNotEmpty()
                    ChatEmptyContent(
                        heroText = heroText,
                        generatedSuggestions = startSuggestions,
                        loading = loading,
                        showSuggestions = !loading && !WindowInsets.isImeVisible && !inputHasContent,
                        onSelect = { suggestion ->
                            onFillSuggestion(suggestion)
                        },
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                    )
                }
            }  // end timeline fade Box
            }  // end first-draw Box
        }  // end if (!initialized) else branch (ChatList + hero)
    }
}
