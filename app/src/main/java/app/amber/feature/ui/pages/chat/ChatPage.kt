package app.amber.feature.ui.pages.chat

import app.amber.feature.ui.utils.amberTraceMeasure
import android.content.Context
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import app.amber.ai.core.MessageRole
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.registry.ModelRegistry
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.isEmptyInputMessage
import app.amber.core.event.AppEvent
import app.amber.core.event.AppEventBus
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.X
import com.composables.icons.lucide.List
import com.composables.icons.lucide.Menu
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Clock
import app.amber.agent.R
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.runtime.SandboxActivityUiState
import app.amber.feature.runtime.ToolActivityStatus
import app.amber.core.settings.AgentOperationPreviewMode
import app.amber.core.ai.mcp.McpToolNamespace
import app.amber.core.ai.tools.parseDeepReadSlashCommand
import app.amber.core.settings.Settings
import app.amber.core.settings.findProvider
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.settings.defaultReasoningLevelForModel
import app.amber.core.files.FilesManager
import app.amber.core.context.ActiveCompactBoundary
import app.amber.core.context.CompactLifecycleState
import app.amber.core.context.ContextFootprintEstimator
import app.amber.core.context.ConversationCompact
import app.amber.core.model.Conversation
import app.amber.core.service.ChatError
import app.amber.core.service.PendingUserMessage
import app.amber.core.service.PendingUserMessageDisplayCopy
import app.amber.core.service.PendingUserMessageMode
import app.amber.core.service.previewText
import app.amber.feature.ui.components.ai.ChatInput
import app.amber.feature.ui.components.ai.SubAgentStatusDock
import app.amber.core.repository.ConversationRepository
import app.amber.feature.ui.components.ai.SandboxActivitySheet
import app.amber.feature.ui.components.ai.TopModelMenu
import app.amber.feature.ui.components.ds.BlinkingCursor
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.ui.components.ui.WorkspaceIconButton
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.components.workspace.WorkspaceFilePreview
import app.amber.feature.ui.components.workspace.WorkspaceFileSheet
import app.amber.feature.ui.components.workspace.WorkspaceFileVM
import app.amber.feature.ui.components.workspace.ArtifactsVM
import app.amber.feature.ui.components.workspace.SaveMessageToWorkspaceDialog
import app.amber.agent.data.workspace.ArtifactRepository
import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import app.amber.feature.workspace.WorkspaceManager
import app.amber.feature.ui.components.message.MessageRenderCache
import app.amber.feature.ui.context.LocalNavController
import app.amber.agent.Screen
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.context.Navigator
import app.amber.feature.ui.hooks.ChatInputState
import app.amber.feature.ui.hooks.EditStateContent
import app.amber.feature.ui.hooks.useEditState
import app.amber.feature.webmount.primitives.WebMountSessionOwner
import app.amber.core.utils.base64Decode
import app.amber.core.utils.jsonPrimitiveOrNull
import app.amber.core.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@Composable
fun ChatPage(
    id: Uuid,
    text: String?,
    files: List<Uri>,
    nodeId: Uuid? = null,
    messageId: String? = null,
    toolCallId: String? = null,
) {
    // T2 perf-layer dispatch — flag-gated route to ChatPageSplit (scaffold).
    // Default flag = false → legacy path below runs unchanged. See PerfFlags.kt
    // + ChatPageSplit.kt for the new code path and on-device verification
    // steps in docs/visual-sanity-check.md.
    if (app.amber.agent.PerfFlags.USE_SPLIT_CHATPAGE_COMPOSABLES) {
        ChatPageSplit(
            id = id,
            text = text,
            files = files,
            nodeId = nodeId,
            messageId = messageId,
            toolCallId = toolCallId,
        )
        return
    }

    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    LaunchedEffect(vm) {
        vm.onChatVisible()
    }
    val toaster = LocalToaster.current
    val savedToWorkspaceText = stringResource(R.string.chat_page_saved_to_workspace)
    val filesManager: FilesManager = koinInject()
    val eventBus: AppEventBus = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    var previewFilePath by remember { mutableStateOf<String?>(null) }
    val workspaceManager: WorkspaceManager = koinInject()
    // P3-01/P3-02: Workspace Artifact Registry — flag-gated create/reparse/delete,
    // list stays read-only visible when off (rollback rules §17.2).
    val artifactRepository: ArtifactRepository = koinInject()
    val capabilityFlags: CapabilityFlags = koinInject()
    val artifactsEnabledFlow = remember {
        capabilityFlags.flow.map { Capability.WorkspaceArtifactsV2 in it.enabled }
    }
    val artifactsEnabled by artifactsEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    // (conversationId, message) pending the "保存到 Workspace" dialog.
    var messageToSave by remember { mutableStateOf<Pair<String, UIMessage>?>(null) }

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val timelineLoadState by vm.timelineLoadState.collectAsStateWithLifecycle()
    val contextCompacts by vm.contextCompacts.collectAsStateWithLifecycle()
    val activeCompactBoundary by vm.activeCompactBoundary.collectAsStateWithLifecycle()
    val compactLifecycleState by vm.compactLifecycleState.collectAsStateWithLifecycle()
    val isCompacting by vm.isCompacting.collectAsStateWithLifecycle()
    val streamingSummary by vm.streamingSummary.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val pendingUserMessageState = vm.pendingUserMessages.collectAsStateWithLifecycle()
    val pendingUserMessages = pendingUserMessageState.value
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val configurationIssue = rememberChatConfigurationIssue(currentChatModel, setting.providers)
    val latestConfigurationIssue by rememberUpdatedState(configurationIssue)
    val context = LocalContext.current
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val globalErrors by vm.globalErrors.collectAsStateWithLifecycle()
    val outcomeUnknown by vm.outcomeUnknown.collectAsStateWithLifecycle()

    val windowAdaptiveInfo = currentWindowDpSize()
    val compactTwoPane =
        windowAdaptiveInfo.width >= 720.dp &&
            windowAdaptiveInfo.height >= 450.dp
    val isBigScreen =
        compactTwoPane || windowAdaptiveInfo.width >= 1100.dp

    val inputState = vm.inputState
    var anchorNodeId by remember(id, messageId, toolCallId) { mutableStateOf<Uuid?>(null) }

    LaunchedEffect(id, messageId, toolCallId) {
        if (nodeId == null && (messageId != null || toolCallId != null)) {
            anchorNodeId = vm.findNodeIdForAnchor(messageId, toolCallId)
        }
    }
    val resolvedNodeId = nodeId ?: anchorNodeId

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            // Skip re-copying URIs that are already file:// inside the workspace mirror.
            // ShareHandlerPage stages shared files there directly (so the Agent's tools
            // can find them under /workspace/uploads/) — running them through
            // createChatFilesByContents would duplicate the bytes into filesDir/upload/.
            // Trailing "/" in the prefix prevents matching a sibling like
            // `…/workspace-mirror-backup/…` against `…/workspace-mirror`.
            val mirrorPrefix = workspaceManager.mirrorDir.absolutePath + "/"
            val localFiles = files.map { uri ->
                val alreadyStaged = uri.scheme == "file" &&
                    uri.path?.startsWith(mirrorPrefix) == true
                if (alreadyStaged) {
                    uri
                } else {
                    filesManager.createChatFilesByContents(listOf(uri)).firstOrNull() ?: uri
                }
            }
            val contentTypes = files.map { file ->
                filesManager.getFileMimeType(file)
            }
            val fileNames = files.map { file ->
                filesManager.getFileNameFromUri(file) ?: file.lastPathSegment ?: "file"
            }
            val parts = buildList {
                localFiles.forEachIndexed { index, file ->
                    val type = contentTypes.getOrNull(index)
                    val fileName = fileNames.getOrNull(index) ?: "file"
                    if (type?.startsWith("image/") == true) {
                        add(UIMessagePart.Image(url = file.toString()))
                    } else if (type?.startsWith("video/") == true) {
                        add(UIMessagePart.Video(url = file.toString(), mime = type))
                    } else if (type?.startsWith("audio/") == true) {
                        add(
                            UIMessagePart.Audio(
                                url = file.toString(),
                                fileName = fileName,
                                mime = type,
                            )
                        )
                    } else {
                        add(
                            UIMessagePart.Document(
                                url = file.toString(),
                                fileName = fileName,
                                mime = type ?: "application/octet-stream"
                            )
                        )
                    }
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    // P6-02: the generated-image carousel emits EditGeneratedImage when the
    // user confirms a "修改" action. Route it to THIS conversation only when
    // the source URL lives in this conversation's chat_images dir — the
    // controlled reference — then send it as a normal user message (text +
    // the source image attachment). The generate_image tool resolves the
    // source from that message and runs its edit mode.
    LaunchedEffect(eventBus, id) {
        val chatImagesRoot = "file://" + filesManager.getChatImagesDir(id).absolutePath + "/"
        eventBus.events.collect { event ->
            if (event is AppEvent.EditGeneratedImage &&
                event.sourceImageUrl.startsWith(chatImagesRoot)
            ) {
                val issue = latestConfigurationIssue
                if (issue != null) {
                    toaster.show(context.getString(issue.messageRes), type = ToastType.Error)
                    return@collect
                }
                vm.handleMessageSend(
                    content = buildList {
                        if (event.prompt.isNotBlank()) add(UIMessagePart.Text(event.prompt))
                        add(UIMessagePart.Image(url = event.sourceImageUrl))
                    },
                    answer = true,
                )
            }
        }
    }

    val chatRegexes = setting.regexes
    val compactInTimelineActive = isCompacting || compactLifecycleState.isActive
    val activeGeneration = loadingJob != null || pendingUserMessages.isNotEmpty() || compactInTimelineActive
    // Initialization publishes the conversation and its load state separately. While the
    // spinner is visible, do not parse and plan history that cannot be displayed yet.
    val timelineConversation = if (timelineLoadState.initialized) {
        conversation
    } else {
        remember(conversation.id) { conversation.copy(messageNodes = emptyList()) }
    }
    val chatTimelinePlan = rememberChatTimelinePlan(
        conversation = timelineConversation,
        regexes = chatRegexes,
        showAssistantBubble = setting.displaySetting.showAssistantBubble,
        loading = loadingJob != null,
        activeGeneration = activeGeneration,
        hasHistoryLoadingItem = !timelineLoadState.isFullyLoaded,
        pendingMessageCount = pendingUserMessages.size,
    )
    // reverseLayout: lazy index 0 就是视觉底部（最新内容），默认状态即钉底，
    // 只有深链 nodeId 需要计算初始索引。
    val initialChatListIndex = remember(conversation.id, resolvedNodeId, conversation.messageNodes, chatTimelinePlan) {
        if (resolvedNodeId != null) {
            val messageIndex = conversation.messageNodes.indexOfFirst { it.id == resolvedNodeId }
            chatTimelinePlan.lazyIndexForMessage(messageIndex).takeIf { messageIndex >= 0 } ?: 0
        } else {
            0
        }
    }
    val chatListState = key(conversation.id) {
        rememberLazyListState(initialFirstVisibleItemIndex = initialChatListIndex)
    }

    LaunchedEffect(resolvedNodeId, conversation.messageNodes.size, timelineLoadState.initialized, timelineLoadState.isFullyLoaded) {
        // 无深链时 reverseLayout 的 (0,0) 默认位就是底部，直接标记完成。
        if (resolvedNodeId == null) {
            vm.chatListInitialized = true
        } else if (!vm.chatListInitialized) {
            // 深链目标可能尚未加载：找到即跳（目标钉在视口底缘、消息向上展开
            // 进入视口），没找到就先补齐时间线再等下一次触发。
            val index = conversation.messageNodes.indexOfFirst { it.id == resolvedNodeId }
            if (index >= 0) {
                val listIndex = chatTimelinePlan.lazyIndexForMessage(index)
                if (listIndex != null) {
                    chatListState.scrollToItem(listIndex)
                    vm.chatListInitialized = true
                }
            } else if (!timelineLoadState.isFullyLoaded) {
                vm.ensureTimelineLoaded()
            }
        }
    }

    // ChatDrawer 侧边栏已废弃移除（手机 ModalNavigationDrawer / 平板 PermanentNavigationDrawer
    // 两个挂载点一起拆掉）：会话列表入口由 Session 首页承担，顶栏左上是返回。
    ChatPageContent(
        inputState = inputState,
        loadingJob = loadingJob,
        processingStatus = processingStatus,
        setting = setting,
        conversation = conversation,
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
        chatTimelinePlan = chatTimelinePlan,
        enableWebSearch = enableWebSearch,
        currentChatModel = currentChatModel,
        configurationIssue = configurationIssue,
        bigScreen = isBigScreen,
        errors = errors,
        globalErrors = globalErrors,
        onDismissError = { vm.dismissError(it) },
        onClearAllErrors = { vm.clearAllErrors() },
        onPreviewWorkspaceFile = { previewFilePath = it },
        outcomeUnknown = outcomeUnknown,
        onReconcileOutcomeUnknown = { effectId, retry -> vm.reconcileOutcomeUnknown(effectId, retry) },
        onSaveToWorkspace = if (artifactsEnabled) {
            { msg -> messageToSave = conversation.id.toString() to msg }
        } else null,
    )
    if (showWorkspaceSheet) {
        val workspaceCtx = LocalContext.current
        val workspaceMgrForVm = workspaceManager
        val workspaceVm = viewModel<WorkspaceFileVM>(
            factory = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return WorkspaceFileVM(workspaceCtx, workspaceMgrForVm) as T
                }
            }
        )
        val artifactsVm = viewModel<ArtifactsVM>(
            factory = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return ArtifactsVM(artifactRepository) as T
                }
            }
        )
        WorkspaceFileSheet(
            vm = workspaceVm,
            artifactsVm = artifactsVm,
            artifactsEnabled = artifactsEnabled,
            onDismiss = { showWorkspaceSheet = false },
            onOpenFile = { path -> previewFilePath = path },
            onOpenSourceConversation = { conversationId ->
                showWorkspaceSheet = false
                navController.navigate(Screen.Chat(id = conversationId))
            },
        )
    }
    previewFilePath?.let { path ->
        WorkspaceFilePreview(
            relativePath = path,
            workspaceManager = workspaceManager,
            onDismiss = { previewFilePath = null },
        )
    }
    messageToSave?.let { (conversationId, message) ->
        SaveMessageToWorkspaceDialog(
            conversationId = conversationId,
            message = message,
            repository = artifactRepository,
            onDismiss = { messageToSave = null },
            onSaved = {
                messageToSave = null
                toaster.show(savedToWorkspaceText, type = ToastType.Success)
            },
        )
    }
}

@Composable
private fun ChatPageContent(
    inputState: ChatInputState,
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
    chatTimelinePlan: ChatTimelinePlan,
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
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val conversationRepository: ConversationRepository = koinInject()
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
    val hazeState = rememberHazeState()
    val activityStore: AgentToolActivityStore = koinInject()
    val liveSandboxActivity by activityStore.sandboxActivity.collectAsStateWithLifecycle()
    val webMountSessionOwner: WebMountSessionOwner = koinInject()
    val webMountSessions by webMountSessionOwner.sessions.collectAsStateWithLifecycle()
    val conversationWebMountSessions = webMountSessions.filter {
        it.conversationId == conversation.id.toString()
    }
    var dismissedWebMountSessionIds by rememberSaveable(conversation.id) {
        mutableStateOf(emptyList<String>())
    }
    val visibleWebMountSessions = conversationWebMountSessions.filterNot {
        it.sessionId in dismissedWebMountSessionIds
    }
    val conversationIdText = conversation.id.toString()
    val latestSandboxConversation by rememberUpdatedState(conversation)
    val latestSandboxLoading by rememberUpdatedState(loadingJob != null)
    val latestSandboxProcessingStatus by rememberUpdatedState(processingStatus)
    val latestSandboxLocaleTag by rememberUpdatedState(localeTag)
    val messageSandboxActivities by produceState(
        initialValue = emptyList<SandboxActivityUiState>(),
        key1 = conversation.id,
    ) {
        snapshotFlow {
            SandboxActivityInput(
                conversation = latestSandboxConversation,
                loading = latestSandboxLoading,
                processingStatus = latestSandboxProcessingStatus,
                localeTag = latestSandboxLocaleTag,
            )
        }.collectLatest { input ->
            value = withContext(Dispatchers.Default) {
                input.conversation.deriveSandboxActivities(
                    loading = input.loading,
                    processingStatus = input.processingStatus,
                    context = resourceContext,
                )
            }
        }
    }
    val scopedLiveSandboxActivity = liveSandboxActivity?.takeIf { live ->
        live.conversationId == conversationIdText ||
            (live.conversationId == null && messageSandboxActivities.any { it.toolCallId == live.toolCallId })
    }
    val rawSandboxTimeline = mergeSandboxTimeline(
        messageActivities = messageSandboxActivities,
        liveActivity = scopedLiveSandboxActivity?.withStepProgress(conversation),
    )
    val sandboxTimeline = when {
        // The design's empty chat keeps the composer single-line and leaves the
        // hero unobstructed. The ALWAYS preview mode still shows real activity;
        // only the synthetic idle placeholder is suppressed until a task exists.
        conversation.messageNodes.isEmpty() && rawSandboxTimeline.isEmpty() -> emptyList()
        setting.agentRuntime.operationPreviewMode == AgentOperationPreviewMode.ALWAYS -> rawSandboxTimeline.ifEmpty {
            listOf(conversation.idleSandboxActivity(resourceContext))
        }
        setting.agentRuntime.operationPreviewMode == AgentOperationPreviewMode.AUTO -> rawSandboxTimeline.filter { it.isActiveOperation() }
        else -> emptyList()
    }
    val latestSandboxIndex = sandboxTimeline.lastIndex
    val currentSandboxIndex = selectedSandboxIndex
        ?.takeIf { latestSandboxIndex >= 0 }
        ?.coerceIn(0, latestSandboxIndex)
        ?: latestSandboxIndex
    val sandboxActivity = sandboxTimeline.getOrNull(currentSandboxIndex)
    val canCancelSandbox = sandboxActivity?.canCancel == true && loadingJob != null
    val canPreviewPrevious = currentSandboxIndex > 0
    val canPreviewNext = currentSandboxIndex >= 0 && currentSandboxIndex < latestSandboxIndex
    fun canSendWithoutChatModel(parts: List<UIMessagePart>): Boolean =
        parseDeepReadSlashCommand(parts) != null

    // P8-01: 编辑确认 —— 提交当前输入为新的 user variant。regenerate=true 时
    // 保存后自动从新 variant 生成回复（默认动作）。受理失败（生成中冲突）时
    // 不清空输入，用户可停止生成后重试。
    fun confirmEdit(regenerate: Boolean) {
        val parts = inputState.getContents()
        if (parts.isEmptyInputMessage()) return
        if (regenerate && configurationIssue != null) {
            toaster.show(context.getString(configurationIssue.messageRes), type = ToastType.Error)
            return
        }
        val accepted = vm.handleMessageEdit(
            parts = parts,
            messageId = inputState.editingMessage!!,
            regenerate = regenerate,
        )
        if (accepted) {
            inputState.clearInput()
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

    val pendingQueueCount = pendingUserMessages.size

    // Graphite TopModelMenu: header 下方卷帘下拉的开合状态（顶栏触发器 + 内容区 overlay 共享）
    var modelMenuOpen by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize().amberCanvas()) {
        Scaffold(
            modifier = Modifier.amberTraceMeasure("Amber ChatPage measure"),
            topBar = {
                TopBar(
                    settings = setting,
                    conversation = conversation,
                    contextCompacts = contextCompacts,
                    bigScreen = bigScreen,
                    onBack = {
                        navController.returnToSessionHome()
                    },
                    currentChatModel = currentChatModel,
                    previewMode = previewMode,
                    onClickMenu = {
                        previewMode = !previewMode
                    },
                    onUpdateChatModel = {
                        vm.setChatModel(model = it)
                    },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    },
                    modelMenuOpen = modelMenuOpen,
                    onToggleModelMenu = { modelMenuOpen = !modelMenuOpen },
                )
            },
            bottomBar = {
                Column {
                    outcomeUnknown.forEach { prompt ->
                        OutcomeUnknownCard(
                            prompt = prompt,
                            onRetry = { onReconcileOutcomeUnknown(prompt.effectId, true) },
                            onAbandon = { onReconcileOutcomeUnknown(prompt.effectId, false) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    // P8-01: 编辑确认栏 ——「仅保存」与「保存并重新生成」（默认主按钮）。
                    // 生成中两个动作禁用并提示，保存不打断当前生成。
                    if (inputState.isEditing()) {
                        UserMessageEditConfirmBar(
                            generating = loadingJob != null,
                            onSaveOnly = { confirmEdit(regenerate = false) },
                            onSaveAndRegenerate = { confirmEdit(regenerate = true) },
                            onCancelEdit = {
                                val discardedFiles = inputState.drainAttachmentFilesForDiscard()
                                inputState.clearInput()
                                if (discardedFiles.isNotEmpty()) {
                                    scope.launch { filesManager.deleteChatFiles(discardedFiles) }
                                }
                            },
                        )
                    }
                    if (loadingJob == null && configurationIssue != null &&
                        (inputState.isEditing() || !canSendWithoutChatModel(inputState.getContents()))) {
                        ChatConfigurationHint(configurationIssue) {
                            if (configurationIssue == ChatConfigurationIssue.MissingModel && chatProvidersForMenu.isNotEmpty()) {
                                modelMenuOpen = true
                            } else {
                                val owner = currentChatModel?.findProvider(setting.providers, checkOverwrite = false)
                                navController.navigate(owner?.let { Screen.SettingProviderDetail(it.id.toString()) } ?: Screen.SettingProvider)
                            }
                        }
                    }
                    ChatInput(
                    aboveComposerContent = {
                        SubAgentStatusDock(
                            currentConversationId = conversation.id,
                            onOpenConversation = { sourceId ->
                                if (sourceId != conversation.id) {
                                    scope.launch {
                                        if (conversationRepository.existsConversationById(sourceId)) {
                                            navController.navigate(Screen.Chat(id = sourceId.toString()))
                                        } else {
                                            toaster.show(context.getString(R.string.workspace_source_unavailable), type = ToastType.Error)
                                        }
                                    }
                                }
                            },
                        )
                    },
                    state = inputState,
                    loading = loadingJob != null,
                    settings = setting,
                    conversation = conversation,
                    contextCompacts = contextCompacts,
                    compactLifecycleState = compactLifecycleState,
                    pendingQueueCount = pendingQueueCount,
                    hazeState = hazeState,
                    timelineScrolling = chatListState.isScrollInProgress,
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    enableSearch = enableWebSearch,
                    onToggleSearch = {
                        vm.updateSettings(setting.copy(enableWebSearch = !enableWebSearch))
                    },
                    sandboxActivity = sandboxActivity?.takeUnless {
                        it.runtime == "WebMount" && conversationWebMountSessions.isNotEmpty()
                    },
                    onOpenSandbox = {
                        sandboxOverlayOpen = true
                    },
                    onPreviousSandbox = if (canPreviewPrevious) {
                        { selectedSandboxIndex = currentSandboxIndex - 1 }
                    } else {
                        null
                    },
                    onNextSandbox = if (canPreviewNext) {
                        {
                            val next = currentSandboxIndex + 1
                            selectedSandboxIndex = if (next >= latestSandboxIndex) null else next
                        }
                    } else {
                        null
                    },
                    onCancelSandbox = if (canCancelSandbox) {
                        { vm.stopGeneration() }
                    } else {
                        null
                    },
                    webMountSessions = visibleWebMountSessions,
                    webMountActivity = scopedLiveSandboxActivity
                        ?.takeIf { it.runtime == "WebMount" && it.isActiveOperation() }
                        ?.title,
                    onDismissWebMount = {
                        dismissedWebMountSessionIds = conversationWebMountSessions.map { it.sessionId }
                    },
                    onOpenWebMountSession = { sessionId, reopen ->
                        navController.navigate(Screen.WebMountSession(sessionId = sessionId, reopen = reopen))
                    },
                    onOpenQueue = {
                        queuePanelOpen = true
                    },
                    suggestionFillPulseKey = suggestionFillPulseKey,
                    onSendClick = { queueMode, parts ->
                        val canRouteWithoutChatModel = !inputState.isEditing() && canSendWithoutChatModel(parts)
                        if (configurationIssue != null && !canRouteWithoutChatModel) {
                            toaster.show(context.getString(configurationIssue.messageRes), type = ToastType.Error)
                            return@ChatInput
                        }
                        val accepted = if (inputState.isEditing()) {
                            // P8-01: 发送键 = 默认动作「保存并重新生成」
                            vm.handleMessageEdit(
                                parts = parts,
                                messageId = inputState.editingMessage!!,
                                regenerate = true,
                            )
                        } else {
                            vm.handleMessageSend(
                                content = parts,
                                queueMode = queueMode,
                            )
                        }
                        if (accepted) {
                            inputState.clearInput()
                        }
                    },
                    onLongSendClick = { queueMode, parts ->
                        val canRouteWithoutChatModel = !inputState.isEditing() && canSendWithoutChatModel(parts)
                        if (configurationIssue != null && !canRouteWithoutChatModel) {
                            toaster.show(context.getString(configurationIssue.messageRes), type = ToastType.Error)
                            return@ChatInput
                        }
                        val accepted = if (inputState.isEditing()) {
                            // P8-01: 编辑态长按发送同样走默认动作「保存并重新生成」
                            vm.handleMessageEdit(
                                parts = parts,
                                messageId = inputState.editingMessage!!,
                                regenerate = true,
                            )
                        } else {
                            vm.handleMessageSend(
                                content = parts,
                                answer = loadingJob != null && queueMode == PendingUserMessageMode.STEER,
                                queueMode = queueMode,
                            )
                        }
                        if (accepted) {
                            inputState.clearInput()
                        }
                    },
                    onCompactContext = {
                        vm.handleCompressContext(
                            additionalPrompt = "",
                            targetTokens = setting.agentRuntime.contextCompaction.maxSummaryTokens,
                            keepRecentMessages = (setting.agentRuntime.contextCompaction.keepRecentTurns * 2)
                                .coerceAtLeast(16),
                        )
                    },
                    onUpdateChatModel = {
                        vm.setChatModel(model = it)
                    },
                    onUpdateSettings = { vm.updateSettings(it) },
                    onUpdateSearchService = { index ->
                        vm.updateSettings(
                            setting.copy(
                                searchServiceSelected = index
                            )
                        )
                    },
                )
                }
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
            // V3: timeline 未初始化时 (conversation 刚切换), 用 spinner 完全覆盖整个 chat 区域,
            //   不渲染 ChatList / hero 这些底层内容. 加载完 (initialized=true) 才显示真实内容,
            //   避免"空白态闪一下再切到历史对话"的副作用.
            if (!timelineLoadState.initialized) {
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
            ChatList(
                innerPadding = innerPadding,
                conversation = conversation,
                timelineLoadState = timelineLoadState,
                pendingUserMessages = pendingUserMessages,
                contextCompacts = contextCompacts,
                activeCompactBoundary = activeCompactBoundary,
                compactLifecycleState = compactLifecycleState,
                isCompacting = isCompacting,
                streamingSummary = streamingSummary,
                state = chatListState,
                loading = loadingJob != null,
                processingStatus = processingStatus,
                previewMode = previewMode,
                settings = setting,
                hazeState = hazeState,
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
                    inputState.setContents(it.parts)
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
                    if (loadingJob != null) {
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
                        inputState.setMessageText(text)
                        suggestionFillPulseKey += 1
                    }
                },
                onLongClickSuggestion = { suggestion ->
                    val text = suggestion.trim()
                    if (text.isNotEmpty()) {
                        val parts = listOf(UIMessagePart.Text(text))
                        if (configurationIssue != null && !canSendWithoutChatModel(parts)) {
                            toaster.show(context.getString(configurationIssue.messageRes), type = ToastType.Error)
                        } else {
                            vm.handleMessageSend(
                                content = parts,
                                queueMode = PendingUserMessageMode.FOLLOWUP,
                            )
                        }
                    }
                },
                onJumpToMessage = { index ->
                    previewMode = false
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
                    queuePanelOpen = true
                },
                onGenerativeWidgetAction = { instruction ->
                    val text = instruction.trim()
                    if (text.isNotEmpty()) {
                        val parts = listOf(UIMessagePart.Text(text))
                        if (configurationIssue != null && !canSendWithoutChatModel(parts)) {
                            toaster.show(context.getString(configurationIssue.messageRes), type = ToastType.Error)
                        } else {
                            inputState.editingMessage = null
                            vm.handleMessageSend(
                                content = parts,
                                queueMode = PendingUserMessageMode.FOLLOWUP,
                            )
                        }
                    }
                },
                onMiniAppModify = { instruction ->
                    val text = instruction.trim()
                    val parts = listOf(UIMessagePart.Text(text))
                    if (text.isEmpty()) {
                        false
                    } else if (configurationIssue != null && !canSendWithoutChatModel(parts)) {
                        toaster.show(context.getString(configurationIssue.messageRes), type = ToastType.Error)
                        false
                    } else {
                        vm.handleMessageSend(
                            content = parts,
                            queueMode = PendingUserMessageMode.FOLLOWUP,
                        )
                    }
                },
                onLoadOlderTimeline = {
                    vm.loadOlderTimelinePage()
                },
                onEnsureTimelineLoaded = {
                    vm.ensureTimelineLoaded()
                },
                chatTimelinePlan = chatTimelinePlan,
            )
            // V3 convo-screen.jsx:27-32 底部 56dp fade scrim ——
            // 长消息滚动接近 composer pill 时溶解到 bg，不硬切边线
            // 仅对话态显示（空白态不需要）
            if (conversation.messageNodes.isNotEmpty()) {
                val chatBg = LocalChatTheme.current.bg
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                0.00f to chatBg.copy(alpha = 0f),
                                0.75f to chatBg,
                                1.00f to chatBg,
                            )
                        )
                )
            }

            // V3 空白态: hero 问候语 (此 else 分支已守护 initialized=true, 不会闪现)
            // review P2 #3: 之前 (isEmpty && loadingJob==null) 在 "loading && empty" 中间态没 UI,
            // 401/慢响应时整屏空白看着像卡死. 改成 isEmpty 时 hero 一直保留 (loading 时 hero 上叠
            // 半透蒙层 spinner 反馈), 直到第一个 chunk 落 (messageNodes 非空) 再切到 ChatList.
            if (conversation.messageNodes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    val nick = setting.displaySetting.userNickname.trim()
                    val heroText = if (nick.isNotEmpty()) {
                        stringResource(R.string.chat_page_hero_greeting_with_name, nick)
                    } else {
                        stringResource(R.string.chat_page_hero_greeting)
                    }
                    val chatTheme = LocalChatTheme.current
                    val amberTokens = LocalAmberTokens.current
                    val heroDim = if (loadingJob != null) 0.45f else 1f
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.amber_wordmark),
                                contentDescription = "Amber",
                                modifier = Modifier.size(width = 118.dp, height = 30.dp),
                                tint = amberTokens.ink.copy(alpha = heroDim),
                            )
                            BlinkingCursor(
                                width = 8.dp,
                                height = 18.dp,
                                modifier = Modifier,
                            )
                        }
                        Text(
                            text = heroText,
                            modifier = Modifier
                                .padding(top = 16.dp)
                                .widthIn(max = 288.dp),
                            color = chatTheme.ink.copy(alpha = heroDim),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = (-0.24).sp,
                            lineHeight = 29.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                    val imeVisible = WindowInsets.isImeVisible
                    val inputHasContent = inputState.textContent.text.isNotEmpty() ||
                        inputState.messageContent.isNotEmpty() ||
                        inputState.attachmentImports.isNotEmpty()
                    if (loadingJob == null && !imeVisible && !inputHasContent) {
                        EmptyChatSuggestions(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                            onSelect = { suggestion ->
                                inputState.setMessageText(suggestion)
                                suggestionFillPulseKey += 1
                            },
                        )
                    }
                    if (loadingJob != null) {
                        // 上叠 spinner 给 "loading && empty" 中间态一个反馈
                        androidx.compose.material3.CircularProgressIndicator(
                            color = chatTheme.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(top = 120.dp)
                                .size(28.dp),
                        )
                    }
                }
            }
            }  // end if (!initialized) else branch (ChatList + hero)

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
                modifier = Modifier.padding(top = innerPadding.calculateTopPadding()),
            )
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
    }
}

@Composable
private fun EmptyChatSuggestions(
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val suggestions = listOf(
        stringResource(R.string.amber_redesign_suggestion_board),
        stringResource(R.string.amber_redesign_suggestion_reply),
        stringResource(R.string.amber_redesign_suggestion_concept),
    )
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        suggestions.forEach { suggestion ->
            Surface(
                onClick = { onSelect(suggestion) },
                modifier = Modifier.heightIn(min = 40.dp),
                shape = CircleShape,
                color = Color.Transparent,
                contentColor = tokens.ink2,
                border = BorderStroke(1.dp, tokens.accent.copy(alpha = 0.10f)),
                tonalElevation = 0.dp,
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = suggestion,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = tokens.ink2,
                    )
                }
            }
        }
    }
}

@Composable
private fun UserMessageEditConfirmBar(
    generating: Boolean,
    onSaveOnly: () -> Unit,
    onSaveAndRegenerate: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    val workspace = workspaceColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!generating) {
            Text(
                text = stringResource(R.string.edit),
                style = MaterialTheme.typography.labelMedium,
                color = workspace.muted,
            )
            Spacer(modifier = Modifier.weight(1f))
        }
        if (generating) {
            Text(
                text = stringResource(R.string.chat_page_edit_generating),
                style = MaterialTheme.typography.labelSmall,
                color = workspace.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        TextButton(onClick = onCancelEdit) {
            Text(stringResource(R.string.chat_page_cancel), maxLines = 1)
        }
        TextButton(onClick = onSaveOnly, enabled = !generating) {
            Text(stringResource(R.string.chat_page_save_only), maxLines = 1)
        }
        Button(onClick = onSaveAndRegenerate, enabled = !generating) {
            Text(stringResource(R.string.chat_page_save_and_regenerate), maxLines = 1)
        }
    }
}

@Composable
private fun PendingUserMessageQueueDialog(
    messages: List<PendingUserMessage>,
    onDismiss: () -> Unit,
    onCancelMessage: (String) -> Unit,
    onMoveMessage: (String, Int) -> Unit,
    onClear: () -> Unit,
    onResumeQueue: () -> Unit,
    onMoveToInput: () -> Unit,
) {
    val workspace = workspaceColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.chat_page_queue_title))
        },
        text = {
            if (messages.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_page_queue_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = workspace.muted,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    messages.forEachIndexed { index, message ->
                        PendingUserMessageQueueRow(
                            index = index,
                            total = messages.size,
                            message = message,
                            onCancel = { onCancelMessage(message.id) },
                            onMoveUp = { onMoveMessage(message.id, -1) },
                            onMoveDown = { onMoveMessage(message.id, 1) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            // P1-06: Stop 后会话进入 idle，队列保留；用户显式选择继续队列
            // 或把第一条移回输入框编辑。
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (messages.isNotEmpty()) {
                    TextButton(onClick = onMoveToInput) {
                        Text(stringResource(R.string.chat_page_queue_move_to_input))
                    }
                    TextButton(onClick = onResumeQueue) {
                        Text(stringResource(R.string.chat_page_queue_resume))
                    }
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.chat_page_close))
                }
                if (messages.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.clear))
                    }
                }
            }
        },
    )
}

@Composable
private fun PendingUserMessageQueueRow(
    index: Int,
    total: Int,
    message: PendingUserMessage,
    onCancel: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val context = LocalContext.current
    val workspace = workspaceColors()
    val modeLabel = when (message.mode) {
        PendingUserMessageMode.FOLLOWUP -> stringResource(R.string.chat_page_queue_mode_followup)
        PendingUserMessageMode.STEER -> stringResource(R.string.chat_page_queue_mode_steer)
        PendingUserMessageMode.COLLECT -> stringResource(R.string.chat_page_queue_mode_collect)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = workspace.paper,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, workspace.hairline),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "#${index + 1} · $modeLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = workspace.muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = onMoveUp,
                        enabled = index > 0,
                    ) {
                        Text(stringResource(R.string.chat_page_queue_move_up))
                    }
                    TextButton(
                        onClick = onMoveDown,
                        enabled = index < total - 1,
                    ) {
                        Text(stringResource(R.string.chat_page_queue_move_down))
                    }
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.chat_page_cancel))
                    }
                }
            }
            Text(
                text = message.previewText(
                    maxChars = 280,
                    copy = PendingUserMessageDisplayCopy.from(context),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = workspace.ink,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val MAX_SANDBOX_TIMELINE_ITEMS = 24
private const val MAX_SANDBOX_OUTPUT_TAIL_CHARS = 1_600
private const val MAX_SANDBOX_JSON_PARSE_CHARS = 3_200_000

private data class SandboxActivityInput(
    val conversation: Conversation,
    val loading: Boolean,
    val processingStatus: String?,
    val localeTag: String,
)

private fun mergeSandboxTimeline(
    messageActivities: List<SandboxActivityUiState>,
    liveActivity: SandboxActivityUiState?,
): List<SandboxActivityUiState> {
    val merged = if (liveActivity == null) {
        messageActivities
    } else {
        val replaced = messageActivities.map { activity ->
            if (activity.toolCallId == liveActivity.toolCallId) liveActivity else activity
        }
        if (replaced.any { it.toolCallId == liveActivity.toolCallId }) {
            replaced
        } else {
            replaced + liveActivity
        }
    }
    return merged.mapIndexed { index, activity ->
        activity.copy(stepIndex = index + 1, stepTotal = merged.size)
    }
}

private fun Conversation.deriveSandboxActivities(
    loading: Boolean,
    processingStatus: String?,
    context: Context,
): List<SandboxActivityUiState> {
    val sandboxTools = sandboxActivityTools().takeLast(MAX_SANDBOX_TIMELINE_ITEMS)
    if (sandboxTools.isEmpty()) {
        return processingStatus?.takeIf { loading && it.isNotBlank() }?.let {
            listOf(
                SandboxActivityUiState(
                    toolCallId = "processing-status",
                    toolName = "agent_processing",
                    title = it,
                    status = ToolActivityStatus.RUNNING,
                    conversationId = id.toString(),
                    runtime = "agent-run",
                    canCancel = true,
                    stepIndex = 1,
                    stepTotal = 1,
                )
            )
        } ?: emptyList()
    }

    return sandboxTools.mapIndexed { index, tool ->
        val outputJson = tool.outputJson()
        val input = tool.inputAsJson()
        val status = tool.activityStatus(loading, outputJson)
        SandboxActivityUiState(
            toolCallId = tool.toolCallId,
            toolName = tool.toolName,
            title = tool.sandboxTitle(context, input),
            status = status,
            conversationId = id.toString(),
            inputPreview = tool.inputPreview(input),
            outputTail = tool.outputTail(outputJson),
            runtime = outputJson.getStringContent("runtime") ?: tool.defaultRuntime(),
            workspace = outputJson.getStringContent("workspace") ?: tool.defaultWorkspace(),
            stepIndex = index + 1,
            stepTotal = sandboxTools.size,
            canCancel = loading && status in setOf(
                ToolActivityStatus.RUNNING,
                ToolActivityStatus.WAITING_FOR_PERMISSION
            ),
        )
    }
}

private fun Conversation.idleSandboxActivity(context: Context): SandboxActivityUiState =
    SandboxActivityUiState(
        toolCallId = "agent-idle-$id",
        toolName = "agent_idle",
        title = context.getString(R.string.chat_page_agent_operation_preview),
        status = ToolActivityStatus.RUNNING,
        conversationId = id.toString(),
        inputPreview = context.getString(R.string.chat_page_waiting_for_tool_call),
        runtime = "standby",
        stepIndex = null,
        stepTotal = null,
    )

private fun SandboxActivityUiState.withStepProgress(conversation: Conversation): SandboxActivityUiState {
    val sandboxTools = conversation.sandboxActivityTools().takeLast(MAX_SANDBOX_TIMELINE_ITEMS)
    val matchedIndex = sandboxTools.indexOfFirst { it.toolCallId == toolCallId }
    val inferredStep = if (matchedIndex >= 0) matchedIndex + 1 else sandboxTools.size + 1
    return copy(
        stepIndex = stepIndex ?: inferredStep.takeIf { it > 0 },
        stepTotal = stepTotal ?: inferredStep.takeIf { it > 0 },
    )
}

private fun Conversation.sandboxActivityTools(): List<UIMessagePart.Tool> =
    currentRunMessages().flatMap { message ->
        message.parts.filterIsInstance<UIMessagePart.Tool>()
            .filter { it.isSandboxActivityTool() }
    }

private fun Conversation.currentRunMessages() = currentMessages.let { messages ->
    messages.drop((messages.indexOfLast { it.role == MessageRole.USER } + 1).coerceAtLeast(0))
}

private fun UIMessagePart.Tool.isSandboxActivityTool(): Boolean =
    toolName.startsWith("mcp__") ||
        toolName in setOf(
            "search_web",
            "scrape_web",
            "webview_search_open",
            "webview_open",
            "webview_wait_for_load",
            "webview_read",
            "icloud_status",
            "icloud_list",
            "icloud_read",
            "icloud_write",
            "icloud_search",
            "file_list",
            "file_read",
            "file_write",
            "file_edit",
            "file_search",
            "file_move",
            "terminal_execute",
            "terminal_install_packages",
            "terminal_workspace_flush",
            "terminal_job_start",
            "terminal_job_read",
            "terminal_job_wait",
            "terminal_job_stop",
            "terminal_session_start",
            "terminal_session_exec",
            "terminal_session_read",
            "terminal_session_stop",
            "screen_click",
            "screen_long_click",
            "screen_swipe",
            "screen_input_text",
            "screen_back",
            "screen_home",
            "screen_open_app",
            "screen_read_ui",
            "screen_screenshot",
            "vlm_task",
        )

private fun UIMessagePart.Tool.activityStatus(
    loading: Boolean,
    outputJson: JsonObject,
): ToolActivityStatus {
    val reportedStatus = outputJson.getStringContent("status")?.lowercase()
    return when {
        approvalState is ToolApprovalState.Pending -> ToolActivityStatus.WAITING_FOR_PERMISSION
        approvalState is ToolApprovalState.Denied -> ToolActivityStatus.CANCELLED
        !isExecuted && loading -> ToolActivityStatus.RUNNING
        !isExecuted -> ToolActivityStatus.RUNNING
        reportedStatus in setOf("queued", "running") -> ToolActivityStatus.RUNNING
        reportedStatus == "cancelled" -> ToolActivityStatus.CANCELLED
        reportedStatus == "timed_out" -> ToolActivityStatus.TIMED_OUT
        reportedStatus == "interrupted" -> ToolActivityStatus.INTERRUPTED
        outputJson.indicatesFailure() -> ToolActivityStatus.FAILED
        else -> ToolActivityStatus.SUCCEEDED
    }
}

private fun UIMessagePart.Tool.sandboxTitle(
    context: Context,
    input: kotlinx.serialization.json.JsonElement = inputAsJson(),
): String {
    return when (toolName) {
        "search_web" -> context.getString(
            R.string.chat_page_tool_search_web,
            input.getFirstStringContent("query", "q", "keyword", "keywords")
                .orEmpty()
                .compactSandboxText(20),
        )
        "scrape_web" -> context.getString(
            R.string.chat_page_tool_open_web,
            input.getFirstStringContent("url", "link", "uri")
                .orEmpty()
                .compactSandboxText(24),
        )
        "webview_search_open" -> context.getString(
            R.string.chat_page_tool_open_search_page,
            input.getStringContent("query").orEmpty().compactSandboxText(20),
        )
        "webview_open" -> context.getString(
            R.string.chat_page_tool_open_web,
            input.getStringContent("url").orEmpty().compactSandboxText(24),
        )
        "webview_wait_for_load" -> context.getString(R.string.chat_page_tool_wait_web_load)
        "webview_read" -> context.getString(R.string.chat_page_tool_read_web)
        "icloud_status" -> context.getString(R.string.chat_page_tool_check_icloud_mount)
        "icloud_list" -> context.getString(
            R.string.chat_page_tool_list_icloud,
            input.getStringContent("path").orEmpty().compactSandboxText(18),
        )
        "icloud_read" -> context.getString(
            R.string.chat_page_tool_read_icloud,
            input.getStringContent("path").orEmpty().compactSandboxText(20),
        )
        "icloud_write" -> context.getString(
            R.string.chat_page_tool_write_icloud,
            input.getStringContent("path").orEmpty().compactSandboxText(20),
        )
        "icloud_search" -> context.getString(
            R.string.chat_page_tool_search_icloud,
            input.getStringContent("query").orEmpty().compactSandboxText(20),
        )
        "file_list" -> context.getString(
            R.string.chat_page_tool_list_workspace,
            input.getStringContent("path").orEmpty().compactSandboxText(18),
        )
        "file_read" -> context.getString(
            R.string.chat_page_tool_read_file,
            input.getStringContent("path").orEmpty().compactSandboxText(20),
        )
        "file_write" -> context.getString(
            R.string.chat_page_tool_write_file,
            input.getStringContent("path").orEmpty().compactSandboxText(20),
        )
        "file_edit" -> context.getString(
            R.string.chat_page_tool_edit_file,
            input.getStringContent("path").orEmpty().compactSandboxText(20),
        )
        "file_search" -> context.getString(
            R.string.chat_page_tool_search_file,
            input.getStringContent("query").orEmpty().compactSandboxText(20),
        )
        "file_move" -> context.getString(
            R.string.chat_page_tool_move_file,
            input.getStringContent("from").orEmpty().compactSandboxText(16),
        )
        "terminal_execute" -> context.getString(R.string.chat_page_tool_execute_alpine_command)
        "terminal_install_packages" -> context.getString(R.string.chat_page_tool_install_terminal_packages)
        "terminal_workspace_flush" -> context.getString(R.string.chat_page_tool_sync_terminal_workspace)
        "terminal_job_start" -> context.getString(R.string.chat_page_tool_start_terminal_job)
        "terminal_job_read" -> context.getString(R.string.chat_page_tool_read_terminal_job)
        "terminal_job_wait" -> context.getString(R.string.chat_page_tool_wait_terminal_job)
        "terminal_job_stop" -> context.getString(R.string.chat_page_tool_stop_terminal_job)
        "terminal_session_start" -> context.getString(R.string.chat_page_tool_start_terminal_session)
        "terminal_session_exec" -> context.getString(R.string.chat_page_tool_execute_terminal_session)
        "terminal_session_read" -> context.getString(R.string.chat_page_tool_read_terminal_output)
        "terminal_session_stop" -> context.getString(R.string.chat_page_tool_stop_terminal_session)
        "screen_click" -> context.getString(R.string.chat_page_tool_screen_click)
        "screen_long_click" -> context.getString(R.string.chat_page_tool_screen_long_click)
        "screen_swipe" -> context.getString(R.string.chat_page_tool_screen_swipe)
        "screen_input_text" -> context.getString(R.string.chat_page_tool_screen_input_text)
        "screen_back" -> context.getString(R.string.chat_page_tool_screen_back)
        "screen_home" -> context.getString(R.string.chat_page_tool_screen_home)
        "screen_open_app" -> context.getString(
            R.string.chat_page_tool_open_app,
            input.getStringContent("package").orEmpty().compactSandboxText(18),
        )
        "screen_read_ui" -> context.getString(R.string.chat_page_tool_read_current_ui)
        "screen_screenshot" -> context.getString(R.string.chat_page_tool_screenshot)
        "vlm_task" -> context.getString(R.string.chat_page_tool_vlm_task)
        else -> if (toolName.startsWith("mcp__")) {
            context.getString(
                R.string.chat_page_tool_call_mcp,
                McpToolNamespace.displayName(toolName).compactSandboxText(24),
            )
        } else {
            toolName
        }
    }
}

private fun UIMessagePart.Tool.inputPreview(input: kotlinx.serialization.json.JsonElement = inputAsJson()): String {
    return when (toolName) {
        "search_web" -> input.getFirstStringContent("query", "q", "keyword", "keywords")
        "scrape_web" -> input.getFirstStringContent("url", "link", "uri")
        "webview_search_open" -> input.getStringContent("query")
        "webview_open" -> input.getStringContent("url")
        "webview_wait_for_load" -> input.getStringContent("target_url")
        "webview_read" -> input.getStringContent("url")
        "icloud_list", "icloud_read", "icloud_write" -> input.getStringContent("path")
        "icloud_search" -> input.getStringContent("query")
        "terminal_execute", "terminal_job_start", "terminal_session_exec" -> input.getStringContent("command")
        "terminal_install_packages" -> input.getStringContent("packages")
        "terminal_job_read", "terminal_job_wait", "terminal_job_stop" -> input.getStringContent("job_id")
        "file_list", "file_read", "file_write", "file_edit" -> input.getStringContent("path")
        "file_search" -> input.getStringContent("query")
        "file_move" -> input.getStringContent("from")
        "screen_open_app" -> input.getStringContent("package")
        "screen_input_text" -> input.getStringContent("text")
        "vlm_task" -> input.getStringContent("goal")
        else -> null
    }?.compactSandboxText(180) ?: input.toString().compactSandboxText(180)
}

private fun UIMessagePart.Tool.defaultRuntime(): String = when {
    toolName in setOf("terminal_execute", "terminal_job_start") &&
        inputAsJson().getStringContent("ssh_profile_id") != null -> "remote_ssh"
    toolName in setOf("terminal_execute", "terminal_job_start") &&
        inputAsJson().getStringContent("runtime") != null -> inputAsJson().getStringContent("runtime").orEmpty()
    toolName == "search_web" -> "web-search"
    toolName == "scrape_web" -> "webview"
    toolName == "webview_search_open" -> "webview"
    toolName == "webview_open" -> "webview"
    toolName == "webview_wait_for_load" -> "webview"
    toolName == "webview_read" -> "webview"
    toolName.startsWith("icloud_") -> "icloud-web-mount"
    toolName == "terminal_execute" ||
        toolName == "terminal_install_packages" ||
        toolName.startsWith("terminal_job_") -> "terminal"
    toolName == "terminal_workspace_flush" -> "saf-workspace"
    toolName.startsWith("terminal_session_") -> "alpine-proot-session"
    toolName.startsWith("file_") -> "saf-workspace"
    toolName.startsWith("screen_") || toolName == "vlm_task" -> "accessibility-service"
    toolName.startsWith("mcp__") -> "mcp"
    else -> ""
}

private fun UIMessagePart.Tool.defaultWorkspace(): String = when {
    defaultRuntime() == "remote_ssh" -> inputAsJson().getStringContent("ssh_profile_id").orEmpty()
    toolName.startsWith("terminal_job_") -> ""
    toolName.startsWith("terminal_") || toolName.startsWith("file_") -> "/workspace"
    toolName.startsWith("icloud_") -> "/icloud"
    else -> ""
}

private fun JsonObject.indicatesFailure(): Boolean {
    val exitCode = this["exit_code"]?.jsonPrimitiveOrNull?.intOrNull
    val error = getStringContent("error")
    val status = this["status"]?.jsonPrimitiveOrNull?.contentOrNull?.lowercase()
    val failed = this["failed"]?.jsonPrimitiveOrNull?.contentOrNull?.toBooleanStrictOrNull() == true
    return !error.isNullOrBlank() ||
        (exitCode != null && exitCode != 0) ||
        failed ||
        status in setOf("failed", "error", "denied", "timed_out", "interrupted", "policy_denied")
}

private fun UIMessagePart.Tool.outputTail(outputJson: JsonObject): String {
    val output = outputJson.getStringContent("output")
        ?: outputJson.getStringContent("output_tail")
        ?: outputJson.getStringContent("error")
        ?: outputText(MAX_SANDBOX_OUTPUT_TAIL_CHARS)
    return output.trim().takeLast(MAX_SANDBOX_OUTPUT_TAIL_CHARS)
}

private fun UIMessagePart.Tool.outputJson(): JsonObject {
    var textChars = 0L
    var textParts = 0
    output.forEach { part ->
        if (part is UIMessagePart.Text) {
            if (textParts > 0) textChars++ // `textOutputForJson` joins text parts with '\n'.
            textChars += part.text.length.toLong()
            textParts++
        }
    }
    if (textChars == 0L || textChars > MAX_SANDBOX_JSON_PARSE_CHARS.toLong()) {
        return JsonObject(emptyMap())
    }
    return runCatching {
        MessageRenderCache.toolOutputJson(output) as? JsonObject
    }.getOrNull() ?: JsonObject(emptyMap())
}

private fun UIMessagePart.Tool.outputText(maxChars: Int = Int.MAX_VALUE): String {
    val textParts = output.filterIsInstance<UIMessagePart.Text>()
    if (maxChars == Int.MAX_VALUE) {
        return textParts.joinToString("\n") { it.text }
    }
    var remaining = maxChars
    val chunks = ArrayDeque<String>()
    for (part in textParts.asReversed()) {
        if (remaining <= 0) break
        val text = part.text
        val chunk = if (text.length > remaining) text.takeLast(remaining) else text
        chunks.addFirst(chunk)
        remaining -= chunk.length
    }
    return chunks.joinToString("\n")
}

private fun JsonElement?.getStringContent(key: String): String? =
    (this as? JsonObject)?.get(key)?.jsonPrimitiveOrNull?.contentOrNull

private fun JsonElement?.getFirstStringContent(vararg keys: String): String? {
    val json = this as? JsonObject ?: return null
    return keys.firstNotNullOfOrNull { key ->
        json[key]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}

private fun SandboxActivityUiState.isActiveOperation(): Boolean =
    status == ToolActivityStatus.RUNNING || status == ToolActivityStatus.WAITING_FOR_PERMISSION

private fun String.compactSandboxText(maxLength: Int): String {
    val compact = trim().replace(Regex("\\s+"), " ")
    return if (compact.length > maxLength) compact.take(maxLength - 1) + "…" else compact
}

@Composable
private fun TopBar(
    settings: Settings,
    conversation: Conversation,
    contextCompacts: List<ConversationCompact>,
    bigScreen: Boolean,
    onBack: () -> Unit,
    currentChatModel: Model?,
    previewMode: Boolean,
    onClickMenu: () -> Unit,
    onUpdateChatModel: (Model) -> Unit,
    onUpdateTitle: (String) -> Unit,
    modelMenuOpen: Boolean,
    onToggleModelMenu: () -> Unit,
) {
    val newSessionLabel = stringResource(R.string.chat_page_new_session)
    // V3 phone-screen.jsx header 没有 surface —— 直接坐在 chatTheme.bg 之上
    // 之前用 workspace.paper@96% (legacy 硬编码白底)，用户反馈"顶栏没变暖纸色"
    // ——其实是被白 Surface 罩住了
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // The header and composer use matching fine accent rules around the timeline.
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!bigScreen) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .clickable { onBack() },
                        contentAlignment = Alignment.Center,
                    ) {
                        AmberHeaderBackArrow()
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .clickable { onToggleModelMenu() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val amberTokens = LocalAmberTokens.current
                    val amberType = LocalAmberType.current
                    val sessionTitle = conversation.title.ifBlank { newSessionLabel }
                    Text(
                        text = sessionTitle,
                        style = amberType.sessionTitle,
                        color = amberTokens.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Graphite §6.2 ChatHeader model-id trigger: mono model-id + a chevron that
                    // rotates 180° while the TopModelMenu dropdown is open. Tapping toggles it
                    // (the dropdown itself is rendered in the content area, anchored under header).
                    val chevronRotation by animateFloatAsState(
                        targetValue = if (modelMenuOpen) 180f else 0f,
                        animationSpec = tween(durationMillis = 280),
                        label = "modelMenuChevron",
                    )
                    Row(
                        modifier = Modifier
                            .padding(top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = currentChatModel?.modelId
                                ?: stringResource(R.string.model_list_select_model),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = amberType.meta.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            // ink3↔ink2 中点：ink3 偏淡、ink2 偏深，取中间的暖中灰
                            color = lerp(amberTokens.ink3, amberTokens.ink2, 0.5f),
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Icon(
                            imageVector = Lucide.ArrowDown,
                            contentDescription = null,
                            tint = amberTokens.ink3,
                            // 箭头 14→13，陪着字号一起缩
                            modifier = Modifier
                                .size(13.dp)
                                .rotate(chevronRotation),
                        )
                    }
                }

                Box(
                    modifier = Modifier.widthIn(min = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                // V3 Whisper：进入对话后顶栏右侧多出 22dp Context Ring（仅有消息时）。
                // 真实 used/total —— 取最后一条 assistant 消息的 usage
                // V3 review P2 #2: 之前用 totalTokens (= 该轮 prompt+completion 加起来), 跟 ring
                // 的"上下文占用"语义不符 (短问题 totalTokens 小 → ring 缩水, 反而误导). 改用
                // promptTokens (下一轮 LLM 实际加载的上下文长度), 也是用户最直观的"已占用".
                if (conversation.messageNodes.isNotEmpty()) {
                    val contextInputTokenCache = remember(conversation.id) {
                        ContextFootprintEstimator.ConversationInputTokenCache()
                    }
                    val lastAssistant = remember(conversation.messageNodes) {
                        conversation.messageNodes
                            .asReversed()
                            .asSequence()
                            .map { it.currentMessage }
                            .firstOrNull { it.role == app.amber.ai.core.MessageRole.ASSISTANT }
                    }
                    val lastUsage = lastAssistant?.usage
                    val measuredPromptTokens = lastUsage?.promptTokens?.takeIf { it > 0 }
                    // The estimate is only a fallback. Once the latest assistant message has
                    // real prompt usage, calculating a full-history estimate here adds work but
                    // can never affect the displayed value.
                    val latestConversation by rememberUpdatedState(conversation)
                    val latestContextCompacts by rememberUpdatedState(contextCompacts)
                    val latestMeasuredPromptTokens by rememberUpdatedState(measuredPromptTokens)
                    val estimatedInputTokens by produceState(
                        initialValue = 0,
                        key1 = conversation.id,
                    ) {
                        snapshotFlow {
                            Triple(
                                latestConversation,
                                latestContextCompacts,
                                latestMeasuredPromptTokens,
                            )
                        }.collectLatest { (snapshotConversation, snapshotCompacts, measured) ->
                            if (measured != null) {
                                value = 0
                            } else {
                                value = withContext(Dispatchers.Default) {
                                    contextInputTokenCache.estimateConversationInputTokens(
                                        snapshotConversation,
                                        snapshotCompacts,
                                    )
                                }
                            }
                        }
                    }
                    val usedTokens = measuredPromptTokens ?: estimatedInputTokens
                    val usedK = ((usedTokens + 999) / 1000).coerceAtLeast(0)
                    // total: 优先使用持续维护的 registry，未知/自定义模型再退回 provider 配置。
                    val contextWindowTokens = currentChatModel?.let { model ->
                        ModelRegistry.MODEL_CONTEXT_WINDOW.getData(model.modelId)
                            ?: model.contextWindowTokens
                    }
                    val totalK = (((contextWindowTokens ?: 200_000) + 999) / 1000).coerceAtLeast(1)
                    // V3: 接真实 token 数据给 popup. 速度算的是端到端 (含网络/排队), 不是
                    // 模型纯推理. createdAt 是 message 第一字符落地时刻, finishedAt 是最后字符,
                    // 所以差值 = 全部 streaming 时长.
                    val elapsedMs = if (lastAssistant?.finishedAt != null) {
                        val createdInstant = lastAssistant.createdAt
                            .toInstant(kotlinx.datetime.TimeZone.currentSystemDefault())
                        val finishedInstant = lastAssistant.finishedAt!!
                            .toInstant(kotlinx.datetime.TimeZone.currentSystemDefault())
                        (finishedInstant.toEpochMilliseconds() - createdInstant.toEpochMilliseconds())
                            .takeIf { it > 0 }
                    } else null
                        ContextRing(
                        used = usedK,
                        total = totalK,
                        lastTurnTotalTokens = lastUsage?.totalTokens,
                        lastTurnCompletionTokens = lastUsage?.completionTokens,
                        lastTurnCachedTokens = lastUsage?.cachedTokens,
                        lastTurnPromptTokens = lastUsage?.promptTokens,
                        lastTurnElapsedMs = elapsedMs,
                    )
                }
            }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(LocalAmberTokens.current.accent.copy(alpha = 0.10f)),
            )
        }
    }
}

/**
 * 顶栏返回箭头：延续抽象细线语言（1.6dp 圆帽 ink 线）。
 * 顶栏左上一度是汉堡（三条横线，开 ChatDrawer），侧边栏被 Session 首页取代后
 * 改为返回箭头，与当前顶栏的细线语言保持一致。
 */
@Composable
private fun AmberHeaderBackArrow() {
    // 主题感知 ink 色 —— legacy workspaceColors().ink 在浅色硬编码 #1F1F1F，
    // Paper 主题下需要 #2A241B 才能跟 bg 协调
    val ink = LocalChatTheme.current.ink
    Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val cy = h / 2
        val strokeWidth = 1.6.dp.toPx()
        // 主横线 + 左端 chevron 两臂
        drawLine(ink, Offset(w * 0.86f, cy), Offset(w * 0.18f, cy), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(ink, Offset(w * 0.18f, cy), Offset(w * 0.48f, h * 0.22f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(ink, Offset(w * 0.18f, cy), Offset(w * 0.48f, h * 0.78f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
    }
}

// V3 Whisper 空白态本应为纯净留白 + 底部光晕；之前的 EmptyChatHero（宝石 + 问候）
// 是误读 phone-screen.jsx 未被 Hero 引用的 AmberMark 导致的；移除。
// AmberMark.kt 文件保留作未来 agent avatar 等场景的备用组件。

/**
 * P1-02 OutcomeUnknown prompt card — reuses the approval-card visual
 * language (warning pill + icon buttons). A non-idempotent tool effect whose
 * outcome was lost after an interruption needs an explicit user decision:
 * "确认重试" re-executes the tool; "放弃" writes a structured rejection and
 * continues the run.
 */
@Composable
private fun OutcomeUnknownCard(
    prompt: app.amber.feature.runtime.OutcomeUnknownPrompt,
    onRetry: () -> Unit,
    onAbandon: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    Surface(
        modifier = modifier.clip(RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = workspace.row,
        contentColor = workspace.ink,
        border = androidx.compose.foundation.BorderStroke(1.dp, workspace.hairline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Lucide.Clock,
                contentDescription = null,
                tint = workspace.amber,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.chat_page_tool_result_unknown),
                    style = MaterialTheme.typography.labelMedium,
                    color = workspace.ink,
                )
                Text(
                    text = stringResource(
                        R.string.chat_page_tool_result_unknown_detail,
                        prompt.toolName,
                        prompt.effectId.take(8),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = workspace.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            WorkspaceIconButton(
                onClick = onAbandon,
                modifier = Modifier.size(48.dp),
                size = 28.dp,
                iconSize = 14.dp,
                tone = WorkspaceTone.Danger,
                icon = Lucide.X,
                contentDescription = stringResource(R.string.chat_page_abandon),
            )
            WorkspaceIconButton(
                onClick = onRetry,
                modifier = Modifier.size(48.dp),
                size = 28.dp,
                iconSize = 14.dp,
                tone = WorkspaceTone.Success,
                icon = Lucide.RefreshCw,
                contentDescription = stringResource(R.string.chat_page_confirm_retry),
            )
        }
    }
}
