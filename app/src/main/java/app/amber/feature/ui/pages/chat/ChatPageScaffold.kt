package app.amber.feature.ui.pages.chat

import app.amber.feature.ui.utils.AmberTraceSection
import app.amber.feature.ui.utils.amberTraceMeasure
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import app.amber.feature.ui.components.ai.TopModelMenu
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
        Box(modifier = Modifier.fillMaxSize().amberCanvas()) {
            Scaffold(
                modifier = Modifier.amberTraceMeasure("Amber ChatPage measure"),
                topBar = {
                    AmberTraceSection("Amber ChatTopBar compose") {
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
                    }
                },
                bottomBar = {
                    AmberTraceSection("Amber ChatComposer compose") {
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
                    }
                },
                containerColor = Color.Transparent,
            ) { innerPadding ->
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

                    // Graphite TopModelMenu —— 从 header 正下方 (innerPadding.top) 卷帘展开、覆盖内容区
                    // 的服务商/模型手风琴下拉（替代旧 ModalBottomSheet）。
                    val chatModelIdForMenu = setting.chatModelId
                    val currentProviderIdForMenu = chatProvidersForMenu.firstOrNull { p ->
                        p.models.any { it.id == chatModelIdForMenu }
                    }?.id
                    TopModelMenu(
                        open = modelMenuOpen,
                        providers = chatProvidersForMenu,
                        modelType = ModelType.CHAT,
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
                        modifier = Modifier.padding(
                            top = innerPadding.calculateTopPadding(),
                            bottom = innerPadding.calculateBottomPadding(),
                        ),
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
