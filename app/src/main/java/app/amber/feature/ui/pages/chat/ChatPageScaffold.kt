package app.amber.feature.ui.pages.chat

import app.amber.feature.ui.utils.amberTraceMeasure
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import app.amber.core.recap.RECAP_MIN_USER_MESSAGES
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.amber.ai.core.MessageRole
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.ui.UIMessage
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.core.settings.Settings
import app.amber.core.settings.findProvider
import app.amber.core.settings.defaultReasoningLevelForModel
import app.amber.core.context.ActiveCompactBoundary
import app.amber.core.context.CompactLifecycleState
import app.amber.core.context.ConversationCompact
import app.amber.core.model.Conversation
import app.amber.core.service.ChatError
import app.amber.core.service.PendingUserMessage
import app.amber.feature.ui.components.ai.ChatInputSubmissionOwner
import app.amber.feature.ui.components.ai.SandboxActivitySheet
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.ui.ImagePreviewDialog
import app.amber.agent.Screen
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.context.Navigator
import app.amber.feature.ui.hooks.ChatInputState
import app.amber.core.utils.navigateToChatPage
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
internal fun ChatPageScaffold(
    timelineReady: Boolean,
    timelinePrepared: Boolean,
    inputState: ChatInputState,
    inputSubmissionOwner: ChatInputSubmissionOwner,
    loadingJob: Job?,
    processingStatus: String? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    timelineLoadState: app.amber.core.service.ConversationTimelineLoadState,
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
    enableWebSearch: Boolean,
    currentChatModel: Model?,
    configurationIssue: ChatConfigurationIssue?,
    errors: List<ChatError>,
    globalErrors: List<ChatError> = emptyList(),
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    onPreviewWorkspaceFile: (String) -> Unit,
    outcomeUnknown: List<app.amber.feature.runtime.OutcomeUnknownPrompt> = emptyList(),
    onReconcileOutcomeUnknown: (effectId: String, retry: Boolean) -> Unit = { _, _ -> },
    /** P3-02: 保存消息到 Workspace 的入口 (由 workspace_artifacts_v2 flag 控制非 null). */
    onSaveToWorkspace: ((UIMessage) -> Unit)? = null,
) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val chatProvidersForMenu = setting.providers.filter { provider ->
        provider.enabled && provider.models.any { it.type == ModelType.CHAT }
    }
    val chatModelIdForMenu = setting.chatModelId
    val currentProviderIdForMenu = chatProvidersForMenu.firstOrNull { provider ->
        provider.models.any { it.id == chatModelIdForMenu }
    }?.id
    val currentReasoningLevelForMenu = currentChatModel?.let { model ->
        setting.rememberedReasoningLevelsByModelId[model.id.toString()]
            ?: if (setting.reasoningLevel == ReasoningLevel.AUTO) {
                setting.defaultReasoningLevelForModel(model)
            } else {
                setting.reasoningLevel
            }
    }
    val resourceContext = context
    val localeTag = resourceContext.resources.configuration.locales.toLanguageTags()
    var previewMode by rememberSaveable { mutableStateOf(false) }
    var sandboxOverlayOpen by rememberSaveable { mutableStateOf(false) }
    var queuePanelOpen by rememberSaveable { mutableStateOf(false) }
    var suggestionFillPulseKey by remember(conversation.id) { mutableIntStateOf(0) }
    var selectedSandboxIndex by rememberSaveable(conversation.id) { mutableStateOf<Int?>(null) }
    val activityStore: AgentToolActivityStore = koinInject()
    val liveSandboxActivity by activityStore.sandboxActivity.collectAsStateWithLifecycle()
    val sandboxProjection = rememberChatSandboxProjection(
        conversation = conversation,
        conversationFlow = vm.conversation,
        loading = loadingJob != null,
        processingStatus = processingStatus,
        previewMode = setting.agentRuntime.operationPreviewMode,
        liveSandboxActivity = liveSandboxActivity,
        resourceContext = resourceContext,
        localeTag = localeTag,
    )
    val sandboxTimeline = sandboxProjection.timeline
    val scopedLiveSandboxActivity = sandboxProjection.scopedLiveActivity
    val latestSandboxIndex = sandboxTimeline.lastIndex
    val currentSandboxIndex = selectedSandboxIndex
        ?.takeIf { latestSandboxIndex >= 0 }
        ?.coerceIn(0, latestSandboxIndex)
        ?: latestSandboxIndex
    val sandboxActivity = sandboxTimeline.getOrNull(currentSandboxIndex)
    val canCancelSandbox = sandboxActivity?.canCancel == true && loadingJob != null
    val canPreviewPrevious = currentSandboxIndex > 0
    val canPreviewNext = currentSandboxIndex >= 0 && currentSandboxIndex < latestSandboxIndex
    val inputActions = ChatPageInputActions(
        input = inputState,
        submission = inputSubmissionOwner,
        configurationIssue = configurationIssue,
        generating = loadingJob != null,
        showError = { messageRes ->
            toaster.show(context.getString(messageRes), type = ToastType.Error)
        },
        sendMessage = { parts, answer, queueMode ->
            vm.handleMessageSend(parts, answer, queueMode)
        },
        editMessage = vm::handleMessageEdit,
    )

    LaunchedEffect(sandboxTimeline.size, sandboxTimeline.lastOrNull()?.toolCallId) {
        if (sandboxActivity == null) {
            sandboxOverlayOpen = false
        }
        if (latestSandboxIndex < 0 && selectedSandboxIndex != null) {
            selectedSandboxIndex = null
        }
    }

    val pendingQueueCount = pendingUserMessages.size

    // Graphite TopModelMenu: header 下方卷帘下拉的开合状态（顶栏触发器 + 内容区 overlay 共享）
    var modelMenuOpen by remember { mutableStateOf(false) }
    // 菜单挂在 app shell 的模态层上（遮罩必须盖住 header、输入区以及展开屏左侧列表），
    // 但面板仍要从 header 正下方、聊天栏内展开，所以记下 Scaffold 测出的顶栏高度
    // （content 的 innerPadding.top）与聊天栏在窗口里的左边界（双栏时在左侧列表之后）。
    var headerInset by remember { mutableStateOf(0.dp) }
    var chatPaneStartInset by remember { mutableStateOf(0.dp) }
    val windowDensity = LocalDensity.current
    // Header island: recap panel, shelf panel, and timeline jumps they trigger.
    var recapOpen by remember(conversation.id) { mutableStateOf(false) }
    var shelfOpen by remember(conversation.id) { mutableStateOf(false) }
    var activityOpen by remember(conversation.id) { mutableStateOf(false) }
    // 产物架里点图片：直接看大图，而不是先跳到那条消息再找图。
    var shelfImagePreview by remember(conversation.id) { mutableStateOf<String?>(null) }
    var renameOpen by remember { mutableStateOf(false) }
    var jumpTarget by remember(conversation.id) { mutableStateOf<TimelineJumpTarget?>(null) }
    var jumpToLiveKey by remember(conversation.id) { mutableIntStateOf(0) }
    val timelineHighlight = remember(conversation.id) { TimelineHighlightState() }
    // Node count changes on every new message; roles never change in place, so counting
    // user nodes only when the count moves keeps this off the per-chunk streaming path.
    val recapEligibleFlow = remember(vm) {
        combine(
            vm.conversation
                .map { it.messageNodes }
                .distinctUntilChanged { old, new -> old.size == new.size }
                .map { nodes ->
                    nodes.count { node -> node.messages.getOrNull(node.selectIndex)?.role == MessageRole.USER }
                }
                .flowOn(Dispatchers.Default),
            vm.timelineLoadState,
            vm.recapState,
        ) { userCount, load, recap ->
            userCount >= RECAP_MIN_USER_MESSAGES ||
                (load.initialized && !load.isFullyLoaded && !recap.ineligible)
        }.distinctUntilChanged()
    }
    val recapEligible by recapEligibleFlow.collectAsStateWithLifecycle(initialValue = false)
    // 跨对话活动：只读投影，别的对话还在跑时顶栏出现卫星。
    val activityCenter: app.amber.feature.runtime.ConversationActivityCenter = koinInject()
    val otherActivityFlow = remember(activityCenter, conversation.id) {
        activityCenter.notices(excluding = conversation.id.toString())
    }
    val otherActivity by otherActivityFlow.collectAsStateWithLifecycle(
        initialValue = emptyList(),
    )
    LaunchedEffect(jumpToLiveKey) {
        if (jumpToLiveKey > 0) {
            previewMode = false
            // reverseLayout: lazy index 0 is the visual bottom (newest content).
            chatListState.animateScrollToItem(0)
        }
    }
    fun closeHeaderPanels() {
        recapOpen = false
        shelfOpen = false
        activityOpen = false
    }
    CompositionLocalProvider(LocalTimelineHighlight provides timelineHighlight) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .amberCanvas()
                .onGloballyPositioned { coordinates ->
                    chatPaneStartInset = with(windowDensity) {
                        coordinates.boundsInWindow().left.toDp()
                    }
                },
        ) {
            Scaffold(
                modifier = Modifier.amberTraceMeasure("Amber ChatPage measure"),
                topBar = {
                    ChatPageTopBar(
                        conversationFlow = vm.conversation,
                        bigScreen = bigScreen,
                        onBack = {
                            navController.returnToSessionHome()
                        },
                        currentChatModel = currentChatModel,
                        generating = loadingJob != null,
                        processingStatus = processingStatus,
                        recapEligible = recapEligible,
                        recapOpen = recapOpen,
                        shelfOpen = shelfOpen,
                        activityOpen = activityOpen,
                        otherActivityCount = otherActivity.size,
                        modelMenuOpen = modelMenuOpen,
                        onToggleModelMenu = {
                            closeHeaderPanels()
                            modelMenuOpen = !modelMenuOpen
                        },
                        onIslandOpenRecap = {
                            if (recapOpen) {
                                recapOpen = false
                            } else {
                                modelMenuOpen = false
                                shelfOpen = false
                                activityOpen = false
                                recapOpen = true
                                vm.requestRecap()
                            }
                        },
                        onIslandJumpToLive = {
                            closeHeaderPanels()
                            modelMenuOpen = false
                            jumpToLiveKey += 1
                        },
                        onIslandStop = { vm.stopGeneration() },
                        onToggleShelf = {
                            if (shelfOpen) {
                                shelfOpen = false
                            } else {
                                modelMenuOpen = false
                                recapOpen = false
                                activityOpen = false
                                shelfOpen = true
                            }
                        },
                        onToggleActivity = {
                            if (activityOpen) {
                                activityOpen = false
                            } else {
                                modelMenuOpen = false
                                recapOpen = false
                                shelfOpen = false
                                activityOpen = true
                            }
                        },
                    )
                },
                bottomBar = {
                    ChatPageComposerHost(
                        inputState = inputState,
                        inputSubmissionOwner = inputSubmissionOwner,
                        inputActions = inputActions,
                        loading = loadingJob != null,
                        setting = setting,
                        conversation = conversation,
                        contextCompacts = contextCompacts,
                        compactLifecycleState = compactLifecycleState,
                        pendingQueueCount = pendingQueueCount,
                        timelineScrolling = chatListState.isScrollInProgress,
                        enableWebSearch = enableWebSearch,
                        configurationIssue = configurationIssue,
                        outcomeUnknown = outcomeUnknown,
                        onReconcileOutcomeUnknown = onReconcileOutcomeUnknown,
                        sandboxActivity = sandboxActivity,
                        scopedLiveSandboxActivity = scopedLiveSandboxActivity,
                        onOpenSandbox = { sandboxOverlayOpen = true },
                        onPreviousSandbox = if (canPreviewPrevious) {
                            { selectedSandboxIndex = currentSandboxIndex - 1 }
                        } else null,
                        onNextSandbox = if (canPreviewNext) {
                            {
                                val next = currentSandboxIndex + 1
                                selectedSandboxIndex = if (next >= latestSandboxIndex) null else next
                            }
                        } else null,
                        onCancelSandbox = if (canCancelSandbox) { { vm.stopGeneration() } } else null,
                        onOpenQueue = { queuePanelOpen = true },
                        suggestionFillPulseKey = suggestionFillPulseKey,
                        onConfigureModel = {
                            if (configurationIssue == ChatConfigurationIssue.MissingModel && chatProvidersForMenu.isNotEmpty()) {
                                modelMenuOpen = true
                            } else {
                                val owner = currentChatModel?.findProvider(setting.providers, checkOverwrite = false)
                                navController.navigate(owner?.let { Screen.SettingProviderDetail(it.id.toString()) } ?: Screen.SettingProvider)
                            }
                        },
                        onOpenConversation = { sourceId -> navController.navigate(Screen.Chat(id = sourceId.toString())) },
                        onOpenWebMountSession = { sessionId, reopen ->
                            navController.navigate(Screen.WebMountSession(sessionId = sessionId, reopen = reopen))
                        },
                        onStop = vm::stopGeneration,
                        onUpdateChatModel = { vm.setChatModel(model = it) },
                        onUpdateSettings = vm::updateSettings,
                        onCompactContext = {
                            vm.handleCompressContext(
                                additionalPrompt = "",
                                targetTokens = setting.agentRuntime.contextCompaction.maxSummaryTokens,
                                keepRecentMessages = (setting.agentRuntime.contextCompaction.keepRecentTurns * 2)
                                    .coerceAtLeast(16),
                            )
                        },
                    )
                },
                containerColor = Color.Transparent,
            ) { innerPadding ->
                val topInset = innerPadding.calculateTopPadding()
                if (headerInset != topInset) {
                    SideEffect { headerInset = topInset }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    ChatPageTimelineHost(
                        timelineReady = timelineReady,
                        timelinePrepared = timelinePrepared,
                        conversationId = conversation.id,
                        hasMessages = conversation.messageNodes.isNotEmpty(),
                        inputState = inputState,
                        inputActions = inputActions,
                        loading = loadingJob != null,
                        processingStatus = processingStatus,
                        setting = setting,
                        timelineLoadState = timelineLoadState,
                        pendingUserMessages = pendingUserMessages,
                        contextCompacts = contextCompacts,
                        activeCompactBoundary = activeCompactBoundary,
                        compactLifecycleState = compactLifecycleState,
                        isCompacting = isCompacting,
                        streamingSummary = streamingSummary,
                        navController = navController,
                        vm = vm,
                        chatListState = chatListState,
                        resolvedNodeId = resolvedNodeId,
                        configurationIssue = configurationIssue,
                        errors = errors,
                        globalErrors = globalErrors,
                        onDismissError = onDismissError,
                        onClearAllErrors = onClearAllErrors,
                        onPreviewWorkspaceFile = onPreviewWorkspaceFile,
                        onSaveToWorkspace = onSaveToWorkspace,
                        innerPadding = innerPadding,
                        previewMode = previewMode,
                        onExitPreview = { previewMode = false },
                        jumpTarget = jumpTarget,
                        onJumpTargetConsumed = { jumpTarget = null },
                        timelineHighlight = timelineHighlight,
                        onFillSuggestion = { suggestion ->
                            inputState.setMessageText(suggestion)
                            suggestionFillPulseKey += 1
                        },
                        onOpenQueue = { queuePanelOpen = true },
                    )

                    ChatRecapPanelHost(
                        conversationFlow = vm.conversation,
                        recapStateFlow = vm.recapState,
                        timelineLoadState = vm.timelineLoadState,
                        onRefresh = { vm.requestRecap(force = true) },
                        visible = recapOpen,
                        onDismiss = { recapOpen = false },
                        onRename = { renameOpen = true },
                        onJump = { target ->
                            recapOpen = false
                            jumpTarget = target
                        },
                        onUseNextStep = { step ->
                            recapOpen = false
                            val current = inputState.textContent.text.toString()
                            if (current.isBlank()) {
                                inputState.setMessageText(step)
                            } else {
                                inputState.appendText("\n$step")
                            }
                            suggestionFillPulseKey += 1
                        },
                        modifier = Modifier.padding(
                            top = innerPadding.calculateTopPadding(),
                            bottom = innerPadding.calculateBottomPadding(),
                        ),
                    )
                    ChatShelfPanelHost(
                        conversationFlow = vm.conversation,
                        settingsFlow = vm.settings,
                        loadFullConversation = vm::loadFullConversation,
                        visible = shelfOpen,
                        onDismiss = { shelfOpen = false },
                        onOpen = { item ->
                            shelfOpen = false
                            if (item.kind == ShelfItemKind.IMAGE && item.target.isNotBlank()) {
                                shelfImagePreview = item.target
                            } else {
                                jumpTarget = TimelineJumpTarget(item.nodeId, item.messageId)
                            }
                        },
                        modifier = Modifier.padding(
                            top = innerPadding.calculateTopPadding(),
                            bottom = innerPadding.calculateBottomPadding(),
                        ),
                    )

                    ChatActivityPanelHost(
                        visible = activityOpen,
                        notices = otherActivity,
                        onDismiss = { activityOpen = false },
                        modifier = Modifier.padding(
                            top = innerPadding.calculateTopPadding(),
                            bottom = innerPadding.calculateBottomPadding(),
                        ),
                        onOpen = { conversationId ->
                            activityOpen = false
                            val target = runCatching { Uuid.parse(conversationId) }.getOrNull()
                            if (target != null && target != conversation.id) {
                                navigateToChatPage(navController, chatId = target)
                            }
                        },
                    )

                    shelfImagePreview?.let { url ->
                        ImagePreviewDialog(images = listOf(url)) { shelfImagePreview = null }
                    }
                }  // end outer Box (fillMaxSize)
            }

            // Graphite TopModelMenu —— 发布到 app shell 的模态层：遮罩铺满整个应用窗口
            // （header、输入区、展开屏左侧会话列表），面板仍从 header 正下方、聊天栏内卷帘展开。
            // 只挂在 ChatPage 自己身上时，header 返回和左栏设置会在第一次点击时穿透。
            ChatModelMenuOverlay(
                open = modelMenuOpen,
                providers = chatProvidersForMenu,
                currentProviderId = currentProviderIdForMenu,
                currentModelId = chatModelIdForMenu,
                onSelect = { model ->
                    vm.setChatModel(model = model)
                    modelMenuOpen = false
                },
                onClose = { modelMenuOpen = false },
                reasoningLevel = currentReasoningLevelForMenu,
                onUpdateReasoningLevel = { level ->
                    currentChatModel?.let { model ->
                        vm.updateSettings(
                            setting.copy(
                                reasoningLevel = level,
                                rememberedReasoningLevelsByModelId =
                                    setting.rememberedReasoningLevelsByModelId +
                                        (model.id.toString() to level),
                            )
                        )
                    }
                },
                panelTopInset = headerInset,
                panelStartInset = chatPaneStartInset,
                header = if (conversation.messageNodes.isNotEmpty()) {
                    {
                        ChatModelMenuUsageHeader(
                            conversationFlow = vm.conversation,
                            contextCompacts = contextCompacts,
                            currentChatModel = currentChatModel,
                        )
                    }
                } else {
                    null
                },
            )

            if (sandboxOverlayOpen && sandboxActivity != null) {
                SandboxActivitySheet(
                    activity = sandboxActivity,
                    onDismiss = { sandboxOverlayOpen = false },
                    onCancel = if (canCancelSandbox) {
                        { vm.stopGeneration() }
                    } else {
                        null
                    },
                    onPrevious = if (canPreviewPrevious) {
                        { selectedSandboxIndex = currentSandboxIndex - 1 }
                    } else {
                        null
                    },
                    onNext = if (canPreviewNext) {
                        {
                            val next = currentSandboxIndex + 1
                            selectedSandboxIndex = if (next >= latestSandboxIndex) null else next
                        }
                    } else {
                        null
                    },
                )
            }

            if (queuePanelOpen) {
                PendingUserMessageQueueDialog(
                    messages = pendingUserMessages,
                    onDismiss = { queuePanelOpen = false },
                    onCancelMessage = { vm.cancelPendingUserMessage(it) },
                    onMoveMessage = { id, offset -> vm.movePendingUserMessage(id, offset) },
                    onClear = { vm.clearPendingUserMessages() },
                    onResumeQueue = { vm.resumePendingQueue() },
                    onMoveToInput = { vm.moveFirstPendingMessageToInput() },
                )
            }

            if (renameOpen) {
                ChatTitleRenameDialog(
                    title = vm.conversation.value.title,
                    onDismiss = { renameOpen = false },
                    onConfirm = {
                        renameOpen = false
                        vm.updateTitle(it)
                    },
                )
            }
        }
    }  // end CompositionLocalProvider(LocalTimelineHighlight)
}
