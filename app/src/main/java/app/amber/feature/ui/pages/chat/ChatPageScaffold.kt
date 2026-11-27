package app.amber.feature.ui.pages.chat

import app.amber.feature.ui.utils.AmberTraceSection
import app.amber.feature.ui.utils.ChatPerfCommandBridge
import app.amber.feature.ui.utils.ChatPerfTrace
import app.amber.feature.ui.utils.amberTraceMeasure
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import java.util.concurrent.atomic.AtomicLong
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import app.amber.core.recap.RECAP_MIN_USER_MESSAGES
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.amber.ai.core.MessageRole
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ModelType
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.macgateway.MacGatewayRepository
import app.amber.feature.prompts.AgentSoulStore
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.ui.components.ai.AgentSoulSheet
import app.amber.feature.ui.components.ai.ModelMenuReasoningFooter
import app.amber.feature.ui.components.ai.ModelPickerSheet
import app.amber.feature.ui.components.ai.pickerModelsByProvider
import app.amber.feature.ui.components.ai.reasoningLevelsForModel
import app.amber.core.settings.Settings
import app.amber.core.settings.findProvider
import app.amber.core.settings.defaultReasoningLevelForModel
import app.amber.core.context.ActiveCompactBoundary
import app.amber.core.context.CompactLifecycleState
import app.amber.core.context.ConversationCompact
import app.amber.core.model.Conversation
import app.amber.core.service.ChatError
import app.amber.core.service.PendingUserMessage
import app.amber.core.service.PendingUserMessageMode
import app.amber.feature.ui.components.ai.ChatInputSubmissionOwner
import app.amber.feature.ui.components.ai.SandboxActivitySheet
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.ui.ImagePreviewDialog
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.context.Navigator
import app.amber.feature.ui.hooks.ChatInputState
import app.amber.core.utils.navigateToChatPage
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@OptIn(ExperimentalLayoutApi::class)
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
    val resourceContext = context
    val localeTag = resourceContext.resources.configuration.locales.toLanguageTags()
    var previewMode by rememberSaveable { mutableStateOf(false) }
    var sandboxOverlayOpen by rememberSaveable { mutableStateOf(false) }
    var suggestionFillPulseKey by remember(conversation.id) { mutableIntStateOf(0) }
    var selectedSandboxIndex by rememberSaveable(conversation.id) { mutableStateOf<Int?>(null) }
    val activityStore: AgentToolActivityStore = koinInject()
    val macGatewayRepository: MacGatewayRepository = koinInject()
    val agentSoulStore: AgentSoulStore = koinInject()
    val soulState by agentSoulStore.state.collectAsStateWithLifecycle()
    var agentSoulSheetOpen by rememberSaveable { mutableStateOf(false) }
    val localAsyncTaskCoordinator: app.amber.core.service.LocalAsyncTaskCoordinator = koinInject()
    val selectedRole by macGatewayRepository.selectedDialogueRole.collectAsStateWithLifecycle()
    val personalAgentStage = LocalChatStageMode.current as? ChatStageMode.PersonalAgent
    val dialogueRole = if (personalAgentStage != null) personalAgentStage.dialogueRole else selectedRole
    val remoteGateway = dialogueRole?.let(macGatewayRepository::slot)
    val isCloudDialogueActive = remoteGateway != null
    val connectionGeneration = remoteGateway?.connectionGeneration?.collectAsStateWithLifecycle()?.value
    val composerGeneration = personalAgentStage?.dialogueGeneration ?: connectionGeneration
    val cloudScope = rememberCoroutineScope()
    val conversationIdString = conversation.id.toString()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val sendStage = remember(conversationIdString) { ChatSendStageState() }
    val sendRevisionCounter = remember(conversationIdString) { AtomicLong(0L) }
    DisposableEffect(sendStage) {
        onDispose { sendStage.reset() }
    }
    LaunchedEffect(conversationIdString, remoteGateway, composerGeneration) {
        sendStage.reset()
        if (remoteGateway != null) {
            remoteGateway.bindActiveConversation(conversationIdString, composerGeneration)
        } else {
            localAsyncTaskCoordinator.bindActiveConversation(conversation.id)
        }
    }
    val activeTaskProgressFlow = remember(conversationIdString, remoteGateway, localAsyncTaskCoordinator) {
        val sourceFlow = remoteGateway?.cloudDialogueState ?: localAsyncTaskCoordinator.uiState
        sourceFlow
            .map { state ->
                state.activeTasksForConversation(conversationIdString)
                    .lastOrNull()
                    ?.progressText
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
            .distinctUntilChanged()
    }
    val activeTaskProgressText by activeTaskProgressFlow.collectAsStateWithLifecycle(initialValue = null)
    val effectiveProcessingStatus = processingStatus?.trim()?.takeIf { it.isNotEmpty() }
        ?: activeTaskProgressText
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
        cloudDialogueMode = isCloudDialogueActive,
        conversationId = conversationIdString,
        sendStage = sendStage,
        isFollowupTargetActive = {
            if (remoteGateway != null) {
                remoteGateway.cloudDialogueState.value.resolvedFollowupTarget(conversationIdString) != null
            } else {
                vm.pendingUserMessages.value.isNotEmpty() ||
                    localAsyncTaskCoordinator.uiState.value.activeTasksForConversation(conversationIdString).isNotEmpty()
            }
        },
        nextSendRevision = { sendRevisionCounter.incrementAndGet() },
        hideKeyboard = {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        },
        showError = { messageRes ->
            toaster.show(context.getString(messageRes), type = ToastType.Error)
        },
        sendMessage = send@{ parts, answer, queueMode, messageId ->
            if (dialogueRole != macGatewayRepository.selectedDialogueRole.value) return@send null
            if (remoteGateway != null) {
                val text = parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }
                    .trim()
                if (text.isEmpty() || !remoteGateway.state.value.isCloudDialogueActive ||
                    composerGeneration != remoteGateway.connectionGeneration.value
                ) {
                    null
                } else {
                    val followupTarget = remoteGateway.cloudDialogueState.value
                        .resolvedFollowupTarget(conversationIdString)
                    val admitted = CompletableDeferred<Boolean>()
                    cloudScope.launch {
                        try {
                            if (dialogueRole != macGatewayRepository.selectedDialogueRole.value) return@launch
                            if (followupTarget != null) {
                                remoteGateway.sendCloudFollowup(
                                    conversationId = conversationIdString,
                                    workItemId = followupTarget.workItemId,
                                    text = text,
                                    requestId = messageId.toString(),
                                    expectedConnectionGeneration = composerGeneration,
                                    onLocalEcho = { admitted.complete(true) },
                                )
                            } else {
                                remoteGateway.sendCloudMessage(
                                    conversationId = conversationIdString,
                                    text = text,
                                    requestId = messageId.toString(),
                                    expectedConnectionGeneration = composerGeneration,
                                    onLocalEcho = { admitted.complete(true) },
                                )
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            toaster.show(context.getString(app.amber.agent.R.string.error_title_send_message), type = ToastType.Error)
                        }
                    }.invokeOnCompletion { admitted.complete(false) }
                    admitted
                }
            } else if (vm.handleMessageSend(parts, answer, queueMode, messageId)) {
                CompletableDeferred(true)
            } else {
                null
            }
        },
        editMessage = vm::handleMessageEdit,
    )

    if (ChatPerfTrace.enabled || app.amber.agent.BuildConfig.BUILD_TYPE == "perffixture") {
        DisposableEffect(inputActions, conversation.id, focusManager) {
            ChatPerfCommandBridge.sendText = { text ->
                val parts = listOf(app.amber.ai.ui.UIMessagePart.Text(text))
                var accepted: Boolean? = null
                cloudScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    accepted = inputActions.send(PendingUserMessageMode.FOLLOWUP, parts, null)
                }
                accepted ?: true
            }
            ChatPerfCommandBridge.stopGeneration = {
                vm.stopGeneration()
                true
            }
            ChatPerfCommandBridge.snapshot = {
                "conversation=${conversation.id} nodes=${conversation.messageNodes.size}" +
                    " loading=${loadingJob != null}"
            }
            ChatPerfCommandBridge.clearFocus = {
                focusManager.clearFocus(force = true)
                true
            }
            onDispose {
                ChatPerfCommandBridge.sendText = null
                ChatPerfCommandBridge.stopGeneration = null
                ChatPerfCommandBridge.snapshot = null
                ChatPerfCommandBridge.clearFocus = null
            }
        }
    }

    LaunchedEffect(sandboxTimeline.size, sandboxTimeline.lastOrNull()?.toolCallId) {
        if (sandboxActivity == null) {
            sandboxOverlayOpen = false
        }
        if (latestSandboxIndex < 0 && selectedSandboxIndex != null) {
            selectedSandboxIndex = null
        }
    }

    val canChooseChatModel = !isCloudDialogueActive || inputState.isEditing()
    val composerFocusRequester = remember(conversation.id) { FocusRequester() }
    var composerInputFocused by remember(conversation.id) { mutableStateOf(false) }
    var keyboardDismissRequested by remember(conversation.id) { mutableStateOf(false) }
    var modelPickerOpen by remember(conversation.id, dialogueRole, canChooseChatModel) { mutableStateOf(false) }
    var modelPickerOpening by remember(conversation.id, dialogueRole, canChooseChatModel) { mutableStateOf(false) }
    var restoreKeyboardAfterModelPicker by remember(conversation.id, dialogueRole, canChooseChatModel) { mutableStateOf(false) }
    var modelSelectedDuringPicker by remember(conversation.id, dialogueRole, canChooseChatModel) { mutableStateOf(false) }
    var pendingRestoreKeyboard by remember(conversation.id, dialogueRole, canChooseChatModel) { mutableStateOf(false) }
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val imeVisible = WindowInsets.isImeVisible
    // Keep only the stable WindowInsets reference here; NEVER call imeInsets.getBottom(density)
    // in composition or the entire ChatPageScaffold will recompose on every 120Hz IME frame.
    val imeInsets = WindowInsets.ime
    val latestWindowFocused = rememberUpdatedState(windowInfo.isWindowFocused)
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
    val activityScope = rememberCoroutineScope()
    val otherActivityFlow = remember(activityCenter, conversation.id) {
        activityCenter.notices(excluding = conversation.id.toString())
    }
    val otherActivity by otherActivityFlow.collectAsStateWithLifecycle(
        initialValue = emptyList(),
    )
    val activityLifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(activityCenter, conversation.id, activityLifecycleOwner) {
        activityCenter.consumeWhileResumed(
            activityLifecycleOwner.lifecycle,
            conversation.id.toString(),
        )
    }
    LaunchedEffect(resolvedNodeId, jumpTarget, jumpToLiveKey) {
        if (resolvedNodeId != null || jumpTarget != null || jumpToLiveKey > 0) {
            sendStage.reset()
        }
    }
    LaunchedEffect(jumpToLiveKey) {
        if (jumpToLiveKey > 0) {
            previewMode = false
            // reverseLayout: lazy index 0 is the visual bottom (newest content).
            chatListState.animateScrollToItem(0)
        }
    }
    val stage = LocalChatStageMode.current
    val isPersonalAgentStage = stage is ChatStageMode.PersonalAgent
    var workspaceSheetOpen by rememberSaveable { mutableStateOf(false) }
    val floatingTopInset = if (isPersonalAgentStage) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
            app.amber.feature.ui.pages.personalagent.PersonalAgentTopChromeHeight
    } else {
        0.dp
    }
    val personalAgentStartSuggestions = if (isPersonalAgentStage && conversation.messageNodes.isEmpty()) {
        rememberChatStartSuggestions(vm, setting).value
    } else {
        emptyList()
    }
    fun closeHeaderPanels() {
        recapOpen = false
        shelfOpen = false
        activityOpen = false
    }
    fun toggleShelf() {
        if (shelfOpen) {
            shelfOpen = false
        } else {
            recapOpen = false
            activityOpen = false
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            shelfOpen = true
        }
    }
    val modeOverlayOpen = personalAgentStage?.isModeOverlayOpen == true
    LaunchedEffect(modeOverlayOpen) {
        if (modeOverlayOpen) {
            closeHeaderPanels()
            keyboardDismissRequested = false
            modelPickerOpening = false
            restoreKeyboardAfterModelPicker = false
            modelSelectedDuringPicker = false
            pendingRestoreKeyboard = false
            modelPickerOpen = false
            keyboardController?.hide()
            if (!imeVisible) {
                focusManager.clearFocus(force = true)
            }
        }
    }
    LaunchedEffect(keyboardDismissRequested, imeVisible) {
        if (!keyboardDismissRequested) return@LaunchedEffect
        if (!imeVisible) {
            // Native IME hide finished (or IME was already hidden): now silently clear focus
            // without interrupting any in-flight WindowInsetsAnimation.
            focusManager.clearFocus(force = true)
            keyboardDismissRequested = false
        }
    }
    LaunchedEffect(modelPickerOpening) {
        if (!modelPickerOpening) return@LaunchedEffect
        // Let the native soft keyboard close uninterrupted before clearing focus and raising the
        // bottom sheet so the two windows never fight or stutter.
        withTimeoutOrNull(280L) {
            snapshotFlow { imeInsets.getBottom(density) == 0 }.first { it }
        }
        focusManager.clearFocus(force = true)
        delay(16L)
        if (modelPickerOpening) {
            modelPickerOpening = false
            modelPickerOpen = true
        }
    }
    LaunchedEffect(pendingRestoreKeyboard, modelPickerOpen) {
        if (!pendingRestoreKeyboard || modelPickerOpen) return@LaunchedEffect
        // Wait for the ModalBottomSheet dialog window to detach so InputMethodManager accepts
        // the show request on the first try.
        withTimeoutOrNull(260L) {
            snapshotFlow { latestWindowFocused.value }.first { it }
        }
        delay(36L)
        try {
            keyboardDismissRequested = false
            composerFocusRequester.requestFocus()
            keyboardController?.show()
        } finally {
            pendingRestoreKeyboard = false
        }
    }
    fun openModelPicker() {
        if (modelPickerOpen || modelPickerOpening) return
        closeHeaderPanels()
        val wasKeyboardActive = composerInputFocused || imeVisible
        restoreKeyboardAfterModelPicker = wasKeyboardActive
        modelSelectedDuringPicker = false
        pendingRestoreKeyboard = false
        keyboardDismissRequested = false
        if (wasKeyboardActive && imeInsets.getBottom(density) > 0) {
            // Hide keyboard first via WindowInsetsController WITHOUT tearing down InputConnection
            // via clearFocus(), preserving the native 120Hz close animation.
            modelPickerOpening = true
            keyboardController?.hide()
        } else {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            modelPickerOpen = true
        }
    }
    fun toggleRecap() {
        if (recapOpen) {
            recapOpen = false
        } else {
            closeHeaderPanels()
            recapOpen = true
            vm.requestRecap()
        }
    }
    fun jumpToLive() {
        closeHeaderPanels()
        jumpToLiveKey += 1
    }
    CompositionLocalProvider(
        LocalTimelineHighlight provides timelineHighlight,
        LocalChatSendStage provides sendStage,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .amberCanvas(),
        ) {
            Scaffold(
                modifier = Modifier.amberTraceMeasure("Amber ChatPage measure"),
                contentWindowInsets = if (isPersonalAgentStage) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
                topBar = {
                    if (!isPersonalAgentStage) {
                    AmberTraceSection("Amber ChatTopBar compose") {
                    ChatPageTopBar(
                        conversationFlow = vm.conversation,
                        bigScreen = bigScreen,
                        onBack = {
                            navController.returnToSessionHome()
                        },
                        generating = loadingJob != null,
                        processingStatus = processingStatus,
                        recapEligible = recapEligible,
                        recapOpen = recapOpen,
                        shelfOpen = shelfOpen,
                        activityOpen = activityOpen,
                        otherActivityCount = otherActivity.size,
                        soulDisplayName = soulState.displayName,
                        soulIsDefault = soulState.isDefault,
                        onOpenAgentSoul = {
                            closeHeaderPanels()
                            agentSoulSheetOpen = true
                        },
                        onIslandOpenRecap = ::toggleRecap,
                        onIslandJumpToLive = ::jumpToLive,
                        onIslandStop = { vm.stopGeneration() },
                        onToggleShelf = ::toggleShelf,
                        onToggleActivity = {
                            if (activityOpen) {
                                activityOpen = false
                            } else {
                                recapOpen = false
                                shelfOpen = false
                                activityOpen = true
                            }
                        },
                    )
                    }
                    }
                },
                bottomBar = {
                    AmberTraceSection("Amber ChatComposer compose") {
                    ChatPageComposerHost(
                        inputState = inputState,
                        inputSubmissionOwner = inputSubmissionOwner,
                        conversationFlow = vm.conversation,
                        suggestionsPaused = isCompacting || compactLifecycleState.isActive,
                        inputActions = inputActions,
                        loading = loadingJob != null,
                        setting = setting,
                        conversation = conversation,
                        contextCompacts = contextCompacts,
                        compactLifecycleState = compactLifecycleState,
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
                        suggestionFillPulseKey = suggestionFillPulseKey,
                        startSuggestions = personalAgentStartSuggestions,
                        onFillSuggestion = { suggestion ->
                            inputState.setMessageText(suggestion)
                            suggestionFillPulseKey += 1
                        },
                        onSendSuggestion = { suggestion ->
                            inputActions.sendSuggestion(suggestion)
                        },
                        onConfigureModel = {
                            val owner = currentChatModel?.findProvider(setting.providers, checkOverwrite = false)
                            navController.navigate(owner?.let { Screen.SettingProviderDetail(it.id.toString()) } ?: Screen.SettingProvider)
                        },
                        onOpenConversation = { sourceId -> navController.navigate(Screen.Chat(id = sourceId.toString())) },
                        onOpenWebMountSession = { sessionId, reopen ->
                            navController.navigate(Screen.WebMountSession(sessionId = sessionId, reopen = reopen))
                        },
                        onStop = vm::stopGeneration,
                        currentChatModel = currentChatModel,
                        onOpenModelPicker = if (canChooseChatModel) ::openModelPicker else null,
                        modelPickerOpen = modelPickerOpen,
                        modelPickerTransitioning = modelPickerOpening || pendingRestoreKeyboard,
                        keyboardDismissRequested = keyboardDismissRequested,
                        modeOverlayOpen = modeOverlayOpen,
                        inputFocused = composerInputFocused,
                        onInputFocusChanged = { focused ->
                            composerInputFocused = focused
                            if (focused) keyboardDismissRequested = false
                        },
                        composerFocusRequester = composerFocusRequester,
                        onSelectReasoningLevel = vm::setChatReasoningLevel,
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
                // Defer innerPadding reads to layout phase so per-frame IME height updates in
                // Scaffold's bottomBar never trigger composition of the whole timeline content.
                val panelOverlayPadding = remember(isPersonalAgentStage, floatingTopInset, innerPadding) {
                    object : PaddingValues {
                        override fun calculateLeftPadding(layoutDirection: LayoutDirection) =
                            innerPadding.calculateStartPadding(layoutDirection)
                        override fun calculateTopPadding() =
                            if (isPersonalAgentStage) floatingTopInset else innerPadding.calculateTopPadding()
                        override fun calculateRightPadding(layoutDirection: LayoutDirection) =
                            innerPadding.calculateEndPadding(layoutDirection)
                        override fun calculateBottomPadding() = innerPadding.calculateBottomPadding()
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (composerInputFocused || imeVisible) {
                                Modifier.pointerInput(Unit) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(
                                            requireUnconsumed = false,
                                            pass = PointerEventPass.Final,
                                        )
                                        val up = waitForUpOrCancellation(pass = PointerEventPass.Final)
                                        if (up != null && !down.isConsumed && !up.isConsumed &&
                                            (up.position - down.position).getDistance() <= viewConfiguration.touchSlop
                                        ) {
                                            if (imeInsets.getBottom(density) > 0) {
                                                // Hide via native WindowInsetsController first;
                                                // clearFocus() runs silently once !imeVisible so
                                                // the native IME close animation never stutters.
                                                keyboardDismissRequested = true
                                                keyboardController?.hide()
                                            } else {
                                                focusManager.clearFocus(force = true)
                                                keyboardController?.hide()
                                            }
                                        }
                                    }
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    ChatPageTimelineHost(
                        timelineReady = timelineReady,
                        timelinePrepared = timelinePrepared,
                        conversationId = conversation.id,
                        hasMessages = conversation.messageNodes.isNotEmpty(),
                        inputState = inputState,
                        inputActions = inputActions,
                        loading = loadingJob != null,
                        processingStatus = effectiveProcessingStatus,
                        setting = setting,
                        timelineLoadState = timelineLoadState,
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
                        floatingTopContentPadding = floatingTopInset,
                        previewMode = previewMode,
                        onExitPreview = { previewMode = false },
                        jumpTarget = jumpTarget,
                        onJumpTargetConsumed = { jumpTarget = null },
                        timelineHighlight = timelineHighlight,
                        onFillSuggestion = { suggestion ->
                            inputState.setMessageText(suggestion)
                            suggestionFillPulseKey += 1
                        },
                    )

                    if (stage is ChatStageMode.PersonalAgent) {
                        PersonalAgentTopChromeOverlay(
                            stage = stage,
                            conversationFlow = vm.conversation,
                            generativeUiSetting = setting.agentRuntime.generativeUi,
                            generating = loadingJob != null,
                            soulDisplayName = soulState.displayName,
                            shelfOpen = shelfOpen,
                            onOpenWorkspace = {
                                closeHeaderPanels()
                                workspaceSheetOpen = true
                            },
                            onOpenAgentSoul = {
                                closeHeaderPanels()
                                agentSoulSheetOpen = true
                            },
                            onTopicClick = {
                                when {
                                    loadingJob != null -> jumpToLive()
                                    recapEligible -> toggleRecap()
                                    else -> toaster.show(context.getString(R.string.chat_island_recap_hint))
                                }
                            },
                            onStop = vm::stopGeneration,
                            onToggleShelf = ::toggleShelf,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }

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
                        modifier = Modifier.padding(panelOverlayPadding),
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
                        modifier = Modifier.padding(panelOverlayPadding),
                    )

                    ChatActivityPanelHost(
                        visible = activityOpen,
                        notices = otherActivity,
                        onDismiss = { activityOpen = false },
                        modifier = Modifier.padding(panelOverlayPadding),
                        onOpen = { notice ->
                            val target = activityNoticeNavigationTarget(
                                notice.conversationId,
                                conversation.id,
                            ) ?: return@ChatActivityPanelHost
                            activityOpen = false
                            activityCenter.rememberOpenedNotice(notice)
                            activityScope.launch { activityCenter.markSeen(notice) }
                            navigateToChatPage(navController, chatId = target)
                        },
                    )

                    shelfImagePreview?.let { url ->
                        ImagePreviewDialog(images = listOf(url)) { shelfImagePreview = null }
                    }
                }  // end outer Box (fillMaxSize)
            }

            if (modelPickerOpen) {
                val providers = setting.providers.filter { it.enabled }
                val availableModels = remember(providers) {
                    providers.pickerModelsByProvider(ModelType.CHAT, preferredInputModality = null)
                }
                val hasModels = availableModels.values.any { it.isNotEmpty() }
                val reasoningModel = currentChatModel?.takeIf { ModelAbility.REASONING in it.abilities }
                val reasoningLevels = reasoningModel?.let(::reasoningLevelsForModel).orEmpty()
                // Freeze footer presence for this open session so picking a reasoning model does
                // not suddenly insert a footer mid-dismiss and jerk the sheet height.
                val showReasoningFooter = remember {
                    reasoningModel != null && reasoningLevels.isNotEmpty()
                }
                ModelPickerSheet(
                    modelId = setting.chatModelId.takeIf { currentChatModel != null },
                    providers = providers,
                    type = ModelType.CHAT,
                    allowPartialExpansion = true,
                    onSelect = { model ->
                        modelSelectedDuringPicker = true
                        vm.setChatModel(model)
                    },
                    onDismiss = {
                        val shouldReopenKeyboard = restoreKeyboardAfterModelPicker && modelSelectedDuringPicker
                        restoreKeyboardAfterModelPicker = false
                        modelSelectedDuringPicker = false
                        modelPickerOpen = false
                        if (shouldReopenKeyboard) {
                            pendingRestoreKeyboard = true
                        }
                    },
                    header = {
                        if (!hasModels) {
                            TextButton(
                                onClick = {
                                    restoreKeyboardAfterModelPicker = false
                                    modelSelectedDuringPicker = false
                                    modelPickerOpen = false
                                    navController.navigate(Screen.SettingProvider)
                                },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            ) {
                                Text(stringResource(R.string.chat_setup_configure))
                            }
                        } else if (conversation.messageNodes.isNotEmpty()) {
                            ChatModelMenuUsageHeader(vm.conversation, contextCompacts, currentChatModel)
                        }
                    },
                    footer = if (showReasoningFooter && reasoningModel != null && reasoningLevels.isNotEmpty()) {
                        {
                            ModelMenuReasoningFooter(
                                currentLevel = setting.rememberedReasoningLevelsByModelId[reasoningModel.id.toString()]
                                    ?: if (setting.reasoningLevel == ReasoningLevel.AUTO) {
                                        setting.defaultReasoningLevelForModel(reasoningModel)
                                    } else {
                                        setting.reasoningLevel
                                    },
                                levels = reasoningLevels,
                                onChange = { level -> vm.setChatReasoningLevel(reasoningModel.id, level) },
                            )
                        }
                    } else null,
                )
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

            if (agentSoulSheetOpen) {
                AgentSoulSheet(onDismiss = { agentSoulSheetOpen = false })
            }

            if (stage is ChatStageMode.PersonalAgent) {
                stage.workspaceSheet(
                    workspaceSheetOpen,
                    conversation.messageNodes.isNotEmpty(),
                    soulState.displayName,
                    { workspaceSheetOpen = false },
                    {
                        workspaceSheetOpen = false
                        recapOpen = false
                        activityOpen = false
                        shelfOpen = true
                    },
                    {
                        workspaceSheetOpen = false
                        closeHeaderPanels()
                        agentSoulSheetOpen = true
                    },
                )
            }
        }
    }  // end CompositionLocalProvider(LocalTimelineHighlight)
}

@Composable
private fun PersonalAgentTopChromeOverlay(
    stage: ChatStageMode.PersonalAgent,
    conversationFlow: kotlinx.coroutines.flow.StateFlow<Conversation>,
    generativeUiSetting: app.amber.core.settings.GenerativeUiSetting,
    generating: Boolean,
    soulDisplayName: String,
    shelfOpen: Boolean,
    onOpenWorkspace: () -> Unit,
    onOpenAgentSoul: () -> Unit,
    onTopicClick: () -> Unit,
    onStop: () -> Unit,
    onToggleShelf: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleFlow = remember(conversationFlow) {
        conversationFlow.map { it.title }.distinctUntilChanged()
    }
    val title by titleFlow.collectAsStateWithLifecycle(initialValue = conversationFlow.value.title)
    val nodeCountFlow = remember(conversationFlow) {
        conversationFlow.map { it.id to it.messageNodes.size }.distinctUntilChanged()
    }
    val nodeCountKey by nodeCountFlow.collectAsStateWithLifecycle(
        initialValue = conversationFlow.value.let { it.id to it.messageNodes.size },
    )
    val shelfItemCount by produceState(
        initialValue = 0,
        nodeCountKey,
        generating,
        generativeUiSetting,
    ) {
        val snapshot = conversationFlow.value
        value = if (snapshot.messageNodes.isEmpty()) {
            0
        } else {
            kotlinx.coroutines.withContext(Dispatchers.Default) {
                snapshot.collectShelfItems(generativeUiSetting).size
            }
        }
    }
    Box(modifier = modifier) {
        stage.topChrome(
            PersonalAgentTopBarState(
                title = title,
                generating = generating,
                soulDisplayName = soulDisplayName,
                shelfOpen = shelfOpen,
                shelfItemCount = shelfItemCount,
                onOpenWorkspace = onOpenWorkspace,
                onOpenAgentSoul = onOpenAgentSoul,
                onTopicClick = onTopicClick,
                onStop = onStop,
                onToggleShelf = onToggleShelf,
            ),
        )
    }
}
