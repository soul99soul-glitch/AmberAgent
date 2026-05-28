package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.rikkahub.R
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.runtime.SandboxActivityUiState
import app.amber.feature.runtime.ToolActivityStatus
import me.rerere.rikkahub.data.datastore.AgentOperationPreviewMode
import me.rerere.rikkahub.data.ai.tools.parseDeepReadSlashCommand
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.context.ActiveCompactBoundary
import me.rerere.rikkahub.data.context.CompactLifecycleState
import me.rerere.rikkahub.data.context.ContextFootprintEstimator
import me.rerere.rikkahub.data.context.ConversationCompact
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.PendingUserMessage
import me.rerere.rikkahub.service.PendingUserMessageMode
import me.rerere.rikkahub.service.previewText
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.SandboxActivitySheet
import me.rerere.rikkahub.ui.components.ui.WorkspaceIconButton
import me.rerere.rikkahub.ui.components.ui.WorkspaceTone
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.components.workspace.WorkspaceFilePreview
import me.rerere.rikkahub.ui.components.workspace.WorkspaceFileSheet
import me.rerere.rikkahub.ui.components.workspace.WorkspaceFileVM
import app.amber.feature.workspace.WorkspaceManager
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    var showFavoritesLiveSheet by remember { mutableStateOf(false) }
    var previewFilePath by remember { mutableStateOf<String?>(null) }
    val workspaceManager: WorkspaceManager = koinInject()

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
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // Hide keyboard when drawer is open
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            softwareKeyboardController?.hide()
        }
    }

    val windowAdaptiveInfo = currentWindowDpSize()
    val compactTwoPane =
        windowAdaptiveInfo.width >= 720.dp &&
            windowAdaptiveInfo.height >= 450.dp
    val comfortableTwoPane =
        windowAdaptiveInfo.width >= 840.dp &&
            windowAdaptiveInfo.height >= 600.dp
    val isBigScreen =
        compactTwoPane || windowAdaptiveInfo.width >= 1100.dp
    // V3 convo-history.jsx 全屏 history (380×832 phone artboard 全宽)，手机端 drawer 拉出来
    // 覆盖整屏；大屏 (tablet) 保留侧边栏宽度. 用户偏好全屏覆盖, 关闭用系统返回 / 滑动手势.
    val drawerWidth = when {
        compactTwoPane && !comfortableTwoPane -> 240.dp
        comfortableTwoPane && windowAdaptiveInfo.width < 1100.dp -> 252.dp
        isBigScreen -> 304.dp
        else -> windowAdaptiveInfo.width  // 手机端：全屏覆盖
    }

    val inputState = vm.inputState

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
                        add(UIMessagePart.Video(url = file.toString()))
                    } else if (type?.startsWith("audio/") == true) {
                        add(
                            UIMessagePart.Audio(
                                url = file.toString(),
                                fileName = fileName,
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

    val chatAssistant = remember(setting.assistants, conversation.assistantId) {
        setting.getAssistantById(conversation.assistantId)
    }
    val compactInTimelineActive = isCompacting || compactLifecycleState.isActive
    val activeGeneration = loadingJob != null || pendingUserMessages.isNotEmpty() || compactInTimelineActive
    val chatTimelinePlan = rememberChatTimelinePlan(
        conversation = conversation,
        assistant = chatAssistant,
        showAssistantBubble = setting.displaySetting.showAssistantBubble,
        loading = loadingJob != null,
        activeGeneration = activeGeneration,
        hasHistoryLoadingItem = !timelineLoadState.isFullyLoaded,
        pendingMessageCount = pendingUserMessages.size,
    )
    val initialChatListIndex = remember(conversation.id, nodeId, conversation.messageNodes, chatTimelinePlan) {
        if (nodeId != null) {
            val messageIndex = conversation.messageNodes.indexOfFirst { it.id == nodeId }
            chatTimelinePlan.firstLazyIndexForMessage(messageIndex).takeIf { messageIndex >= 0 } ?: 0
        } else {
            chatTimelinePlan.lastIndex.coerceAtLeast(0)
        }
    }
    val chatListState = key(conversation.id) {
        rememberLazyListState(initialFirstVisibleItemIndex = initialChatListIndex)
    }

    LaunchedEffect(nodeId, conversation.messageNodes.size, timelineLoadState.initialized) {
        if (nodeId == null && !vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            withFrameNanos { }
            withFrameNanos { }
            val lastIndex = chatListState.layoutInfo.totalItemsCount - 1
            if (lastIndex >= 0) {
                chatListState.scrollToItem(lastIndex)
                vm.chatListInitialized = true
            }
        }
    }

    LaunchedEffect(nodeId, conversation.messageNodes.size, timelineLoadState.isFullyLoaded) {
        if (nodeId != null && conversation.messageNodes.isNotEmpty() && !vm.chatListInitialized) {
            val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
            if (index >= 0) {
                val listIndex = chatTimelinePlan.firstLazyIndexForMessage(index)
                if (listIndex != null) {
                    chatListState.scrollToItem(listIndex)
                    vm.chatListInitialized = true
                }
            } else if (!timelineLoadState.isFullyLoaded) {
                vm.ensureTimelineLoaded()
            }
        }
    }

    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting,
                        drawerState = drawerState,
                        drawerWidth = drawerWidth,
                        onOpenWorkspace = { showWorkspaceSheet = true },
                        onOpenFavoritesLive = { showFavoritesLiveSheet = true },
                    )
                }
            ) {
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
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    chatTimelinePlan = chatTimelinePlan,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = true,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                    onPreviewWorkspaceFile = { previewFilePath = it },
                )
            }
        }

        else -> {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting,
                        drawerState = drawerState,
                        drawerWidth = drawerWidth,
                        onOpenWorkspace = { showWorkspaceSheet = true },
                        onOpenFavoritesLive = { showFavoritesLiveSheet = true },
                    )
                }
            ) {
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
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    chatTimelinePlan = chatTimelinePlan,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = false,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                    onPreviewWorkspaceFile = { previewFilePath = it },
                )
            }
        }
    }
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
        WorkspaceFileSheet(
            vm = workspaceVm,
            onDismiss = { showWorkspaceSheet = false },
            onOpenFile = { path -> previewFilePath = path },
        )
    }
    if (showFavoritesLiveSheet) {
        FavoritesLiveSheet(
            navController = navController,
            onDismiss = { showFavoritesLiveSheet = false },
        )
    }
    previewFilePath?.let { path ->
        WorkspaceFilePreview(
            relativePath = path,
            workspaceManager = workspaceManager,
            onDismiss = { previewFilePath = null },
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
    timelineLoadState: me.rerere.rikkahub.service.ConversationTimelineLoadState,
    pendingUserMessages: List<PendingUserMessage>,
    contextCompacts: List<ConversationCompact>,
    activeCompactBoundary: ActiveCompactBoundary?,
    compactLifecycleState: CompactLifecycleState,
    isCompacting: Boolean,
    streamingSummary: String,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    chatTimelinePlan: ChatTimelinePlan,
    enableWebSearch: Boolean,
    currentChatModel: Model?,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    onPreviewWorkspaceFile: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var previewMode by rememberSaveable { mutableStateOf(false) }
    var sandboxOverlayOpen by rememberSaveable { mutableStateOf(false) }
    var queuePanelOpen by rememberSaveable { mutableStateOf(false) }
    var suggestionFillPulseKey by remember(conversation.id) { mutableIntStateOf(0) }
    var selectedSandboxIndex by rememberSaveable(conversation.id) { mutableStateOf<Int?>(null) }
    val hazeState = rememberHazeState()
    val activityStore: AgentToolActivityStore = koinInject()
    val liveSandboxActivity by activityStore.sandboxActivity.collectAsStateWithLifecycle()
    val conversationIdText = conversation.id.toString()
    val messageSandboxActivities = remember(conversation.messageNodes, loadingJob, processingStatus) {
        conversation.deriveSandboxActivities(
            loading = loadingJob != null,
            processingStatus = processingStatus,
        )
    }
    val scopedLiveSandboxActivity = liveSandboxActivity?.takeIf { live ->
        live.conversationId == conversationIdText ||
            (live.conversationId == null && messageSandboxActivities.any { it.toolCallId == live.toolCallId })
    }
    val rawSandboxTimeline = mergeSandboxTimeline(
        messageActivities = messageSandboxActivities,
        liveActivity = scopedLiveSandboxActivity?.withStepProgress(conversation),
    )
    val sandboxTimeline = when (setting.agentRuntime.operationPreviewMode) {
        AgentOperationPreviewMode.ALWAYS -> rawSandboxTimeline.ifEmpty {
            listOf(conversation.idleSandboxActivity())
        }
        AgentOperationPreviewMode.AUTO -> rawSandboxTimeline.filter { it.isActiveOperation() }
        AgentOperationPreviewMode.HIDDEN -> emptyList()
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

    LaunchedEffect(sandboxTimeline.size, sandboxTimeline.lastOrNull()?.toolCallId) {
        if (sandboxActivity == null) {
            sandboxOverlayOpen = false
        }
        if (latestSandboxIndex < 0 && selectedSandboxIndex != null) {
            selectedSandboxIndex = null
        }
    }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)
    val pendingQueueCount = pendingUserMessages.size

    // 用 Box 强制 z-order：chatTheme.bg → bloom → Scaffold（透明）
    // 之前 Surface(color=paper) 包 bloom 时，Material3 Scaffold 内 Surface 可能再画一层
    // tonal tint 把 bloom 盖住，导致真机看不到光晕。
    // 必须用 LocalChatTheme.current.bg 而非 workspaceColors().paper —— 后者在浅色模式
    // 硬编码 Color.White (WorkspaceStyle.kt:107)，会把 Paper #FDFAF3 / Plain #FFFFFF 都
    // 强行变白，导致用户切到 Paper 看到的还是白底。
    val chatThemeForBg = me.rerere.rikkahub.ui.pages.chat.LocalChatTheme.current
    val chatThemeBg = chatThemeForBg.bg
    Box(modifier = Modifier.fillMaxSize().background(chatThemeBg)) {
        AssistantBackground(setting = setting)
        // V3 Whisper：空白态满强度 bloom；进入对话按设计稿"蓝光晕去掉 → 干净浅灰白"。
        // 转场用 700ms tween 缓慢淡出，避免发送瞬间硬切。
        // Paper/Midnight 设计稿 (themes.jsx haloConvo) 要求对话态保留 faint 底光氛围
        // → showBloomInConvo=true 时降到 0.25 而非 0；Whisper/Plain 仍然完全淡出
        //
        // 切换 conversation 时 init=false 期间 messageNodes 快照可能是空 (异步还没填), bloom
        // 会误判为"空白态"开始 700ms 渐变到 1f, 内容到了又反向, 用户看到光晕来回闪. 修复:
        // init=false 时 bloom 锁定在对话态值 (低), 等 initialized=true 真实状态明确再切.
        // review P3 #4: 之前每次 recomposition 重算 + messageNodes 瞬态空快照会触发 700ms
        // 反复 fade up/down. 改为只在 (initialized / conversation.id / messageNodes 真实空否
        // / 主题切换) 四个关键 key 变化时重算, 流式 chunk 引起的 recomp 不再扰动 bloom.
        val isMessageListEmpty = conversation.messageNodes.isEmpty()
        val bloomTarget by remember(
            timelineLoadState.initialized,
            conversation.id,
            isMessageListEmpty,
            chatThemeForBg.showBloomInConvo,
        ) {
            derivedStateOf {
                when {
                    !timelineLoadState.initialized -> if (chatThemeForBg.showBloomInConvo) 0.25f else 0f
                    isMessageListEmpty -> 1f
                    chatThemeForBg.showBloomInConvo -> 0.25f
                    else -> 0f
                }
            }
        }
        val bloomIntensity by animateFloatAsState(
            targetValue = bloomTarget,
            animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
            label = "bloomFade",
        )
        if (setting.getCurrentAssistant().background == null && bloomIntensity > 0.001f) {
            WhisperBottomBloom(intensity = bloomIntensity)
        }
        Scaffold(
            topBar = {
                TopBar(
                    settings = setting,
                    conversation = conversation,
                    contextCompacts = contextCompacts,
                    bigScreen = bigScreen,
                    drawerState = drawerState,
                    currentChatModel = currentChatModel,
                    previewMode = previewMode,
                    onNewChat = {
                        navigateToChatPage(navController)
                    },
                    onClickMenu = {
                        previewMode = !previewMode
                    },
                    onUpdateChatModel = {
                        vm.setChatModel(assistant = setting.getCurrentAssistant(), model = it)
                    },
                    onUpdateAssistant = { updated ->
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { a ->
                                    if (a.id == updated.id) updated else a
                                }
                            )
                        )
                    },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    },
                )
            },
            bottomBar = {
                ChatInput(
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
                    sandboxActivity = sandboxActivity,
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
                    onOpenQueue = {
                        queuePanelOpen = true
                    },
                    suggestionFillPulseKey = suggestionFillPulseKey,
                    onSendClick = { queueMode ->
                        val parts = inputState.getContents()
                        val canRouteWithoutChatModel = !inputState.isEditing() && canSendWithoutChatModel(parts)
                        if (currentChatModel == null && !canRouteWithoutChatModel) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(
                                content = parts,
                                queueMode = queueMode,
                            )
                        }
                        inputState.clearInput()
                    },
                    onLongSendClick = { queueMode ->
                        val parts = inputState.getContents()
                        val canRouteWithoutChatModel = !inputState.isEditing() && canSendWithoutChatModel(parts)
                        if (currentChatModel == null && !canRouteWithoutChatModel) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = parts,
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(
                                content = parts,
                                answer = loadingJob != null && queueMode == PendingUserMessageMode.STEER,
                                queueMode = queueMode,
                            )
                        }
                        inputState.clearInput()
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
                        vm.setChatModel(assistant = setting.getCurrentAssistant(), model = it)
                    },
                    onUpdateAssistant = {
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { assistant ->
                                    if (assistant.id == it.id) {
                                        it
                                    } else {
                                        assistant
                                    }
                                }
                            )
                        )
                    },
                    onUpdateSearchService = { index ->
                        vm.updateSettings(
                            setting.copy(
                                searchServiceSelected = index
                            )
                        )
                    },
                )
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
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = {
                    vm.regenerateAtMessage(it)
                },
                onEdit = {
                    inputState.editingMessage = it.id
                    inputState.setContents(it.parts)
                },
                onForkMessage = {
                    scope.launch {
                        val fork = vm.forkMessage(message = it)
                        navigateToChatPage(navController, chatId = fork.id)
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
                        if (currentChatModel == null && !canSendWithoutChatModel(parts)) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                        } else {
                            inputState.editingMessage = null
                            vm.handleMessageSend(
                                content = parts,
                                queueMode = PendingUserMessageMode.FOLLOWUP,
                            )
                            inputState.clearInput()
                        }
                    }
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onJumpToMessage = { index ->
                    previewMode = false
                    scope.launch {
                        val listIndex = chatTimelinePlan.firstLazyIndexForMessage(index)
                            ?: index
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
                        if (currentChatModel == null && !canSendWithoutChatModel(parts)) {
                            toaster.show("请先选择模型", type = ToastType.Error)
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
                    } else if (currentChatModel == null && !canSendWithoutChatModel(parts)) {
                        toaster.show("请先选择模型", type = ToastType.Error)
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
                    contentAlignment = Alignment.Center,
                ) {
                    val nick = setting.displaySetting.userNickname.trim()
                    val heroText = if (nick.isNotEmpty()) "Hi $nick，\n今天想聊点什么？" else "今天想聊点什么？"
                    val chatTheme = LocalChatTheme.current
                    Text(
                        text = heroText,
                        color = chatTheme.ink.copy(alpha = if (loadingJob != null) 0.45f else 1f),
                        fontSize = chatTheme.heroSize.sp,
                        fontWeight = FontWeight(chatTheme.heroWeight),
                        letterSpacing = chatTheme.heroLetter.sp,
                        lineHeight = (chatTheme.heroSize * 1.4f).sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    if (loadingJob != null) {
                        // 上叠 spinner 给 "loading && empty" 中间态一个反馈
                        androidx.compose.material3.CircularProgressIndicator(
                            color = chatTheme.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .size(28.dp)
                                .padding(top = (chatTheme.heroSize * 2.5f).dp.coerceAtMost(80.dp)),
                        )
                    }
                }
            }
            }  // end if (!initialized) else branch (ChatList + hero)
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
            )
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
) {
    val workspace = workspaceColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("排队消息")
        },
        text = {
            if (messages.isEmpty()) {
                Text(
                    text = "当前没有排队消息。",
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
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        dismissButton = {
            if (messages.isNotEmpty()) {
                TextButton(onClick = onClear) {
                    Text("清空")
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
    val workspace = workspaceColors()
    val modeLabel = when (message.mode) {
        PendingUserMessageMode.FOLLOWUP -> "排队下一轮"
        PendingUserMessageMode.STEER -> "等待插话点"
        PendingUserMessageMode.COLLECT -> "收集多条"
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
                        Text("上移")
                    }
                    TextButton(
                        onClick = onMoveDown,
                        enabled = index < total - 1,
                    ) {
                        Text("下移")
                    }
                    TextButton(onClick = onCancel) {
                        Text("取消")
                    }
                }
            }
            Text(
                text = message.previewText(maxChars = 280),
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
private const val MAX_SANDBOX_JSON_PARSE_CHARS = 80_000

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
            title = tool.sandboxTitle(input),
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

private fun Conversation.idleSandboxActivity(): SandboxActivityUiState =
    SandboxActivityUiState(
        toolCallId = "agent-idle-$id",
        toolName = "agent_idle",
        title = "Agent 操作预览",
        status = ToolActivityStatus.RUNNING,
        conversationId = id.toString(),
        inputPreview = "等待下一次工具调用",
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
): ToolActivityStatus = when {
    approvalState is ToolApprovalState.Pending -> ToolActivityStatus.WAITING_FOR_PERMISSION
    approvalState is ToolApprovalState.Denied -> ToolActivityStatus.CANCELLED
    !isExecuted && loading -> ToolActivityStatus.RUNNING
    !isExecuted -> ToolActivityStatus.RUNNING
    outputJson.indicatesFailure() -> ToolActivityStatus.FAILED
    else -> ToolActivityStatus.SUCCEEDED
}

private fun UIMessagePart.Tool.sandboxTitle(input: kotlinx.serialization.json.JsonElement = inputAsJson()): String {
    return when (toolName) {
        "search_web" -> "网页搜索 ${input.getFirstStringContent("query", "q", "keyword", "keywords").orEmpty().compactSandboxText(20)}"
        "scrape_web" -> "打开网页 ${input.getFirstStringContent("url", "link", "uri").orEmpty().compactSandboxText(24)}"
        "webview_search_open" -> "打开搜索页 ${input.getStringContent("query").orEmpty().compactSandboxText(20)}"
        "webview_open" -> "打开网页 ${input.getStringContent("url").orEmpty().compactSandboxText(24)}"
        "webview_wait_for_load" -> "等待网页加载"
        "webview_read" -> "读取网页内容"
        "icloud_status" -> "检查 iCloud 挂载"
        "icloud_list" -> "列出 iCloud ${input.getStringContent("path").orEmpty().compactSandboxText(18)}"
        "icloud_read" -> "读取 iCloud ${input.getStringContent("path").orEmpty().compactSandboxText(20)}"
        "icloud_write" -> "写入 iCloud ${input.getStringContent("path").orEmpty().compactSandboxText(20)}"
        "icloud_search" -> "搜索 iCloud ${input.getStringContent("query").orEmpty().compactSandboxText(20)}"
        "file_list" -> "列出 workspace ${input.getStringContent("path").orEmpty().compactSandboxText(18)}"
        "file_read" -> "读取文件 ${input.getStringContent("path").orEmpty().compactSandboxText(20)}"
        "file_write" -> "写入文件 ${input.getStringContent("path").orEmpty().compactSandboxText(20)}"
        "file_edit" -> "编辑文件 ${input.getStringContent("path").orEmpty().compactSandboxText(20)}"
        "file_search" -> "搜索文件 ${input.getStringContent("query").orEmpty().compactSandboxText(20)}"
        "file_move" -> "移动文件 ${input.getStringContent("from").orEmpty().compactSandboxText(16)}"
        "terminal_execute" -> "执行 Alpine 命令"
        "terminal_session_start" -> "启动终端会话"
        "terminal_session_exec" -> "终端会话执行"
        "terminal_session_read" -> "读取终端输出"
        "terminal_session_stop" -> "停止终端会话"
        "screen_click" -> "点击屏幕"
        "screen_long_click" -> "长按屏幕"
        "screen_swipe" -> "滑动屏幕"
        "screen_input_text" -> "输入文字"
        "screen_back" -> "返回"
        "screen_home" -> "回到桌面"
        "screen_open_app" -> "打开应用 ${input.getStringContent("package").orEmpty().compactSandboxText(18)}"
        "screen_read_ui" -> "读取当前 UI"
        "screen_screenshot" -> "获取屏幕截图"
        "vlm_task" -> "执行 VLM 手机任务"
        else -> if (toolName.startsWith("mcp__")) {
            "调用 MCP ${toolName.removePrefix("mcp__").compactSandboxText(24)}"
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
        "terminal_execute", "terminal_session_exec" -> input.getStringContent("command")
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
    toolName == "search_web" -> "web-search"
    toolName == "scrape_web" -> "webview"
    toolName == "webview_search_open" -> "webview"
    toolName == "webview_open" -> "webview"
    toolName == "webview_wait_for_load" -> "webview"
    toolName == "webview_read" -> "webview"
    toolName.startsWith("icloud_") -> "icloud-web-mount"
    toolName == "terminal_execute" ||
        toolName == "terminal_install_packages" ||
        toolName.startsWith("terminal_job_") -> "alpine-proot-stage1"
    toolName.startsWith("terminal_session_") -> "alpine-proot-session"
    toolName.startsWith("file_") -> "saf-workspace"
    toolName.startsWith("screen_") || toolName == "vlm_task" -> "accessibility-service"
    toolName.startsWith("mcp__") -> "mcp"
    else -> ""
}

private fun UIMessagePart.Tool.defaultWorkspace(): String = when {
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
        status in setOf("failed", "error", "denied")
}

private fun UIMessagePart.Tool.outputTail(outputJson: JsonObject): String {
    val output = outputJson.getStringContent("output")
        ?: outputJson.getStringContent("error")
        ?: outputText(MAX_SANDBOX_OUTPUT_TAIL_CHARS)
    return output.trim().takeLast(MAX_SANDBOX_OUTPUT_TAIL_CHARS)
}

private fun UIMessagePart.Tool.outputJson(): JsonObject {
    val output = outputText(MAX_SANDBOX_JSON_PARSE_CHARS + 1)
    if (output.isBlank()) return JsonObject(emptyMap())
    if (output.length > MAX_SANDBOX_JSON_PARSE_CHARS) return JsonObject(emptyMap())
    return runCatching {
        JsonInstant.parseToJsonElement(output) as? JsonObject
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
    drawerState: DrawerState,
    bigScreen: Boolean,
    currentChatModel: Model?,
    previewMode: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateChatModel: (Model) -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateTitle: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // V3 phone-screen.jsx header 没有 surface —— 直接坐在 bloom 之上
    // 之前用 workspace.paper@96% (legacy 硬编码白底) 把 halo 顶层盖住，所以 Paper
    // 主题下用户反馈"顶栏没变暖纸色"——其实是被白 Surface 罩住了
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(start = 20.dp, end = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!bigScreen) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            // V3: ripple 改圆形 (默认矩形 ripple 跟 36dp 方块大小一致, 看着丑)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .clickable { scope.launch { drawerState.open() } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            AmberHeaderLine(width = 22.dp)
                            AmberHeaderLine(width = 14.dp)
                            AmberHeaderLine(width = 19.dp)
                        }
                    }
                }
                ModelSelector(
                    modelId = settings.getCurrentAssistant().chatModelId ?: settings.chatModelId,
                    providers = settings.providers,
                    type = ModelType.CHAT,
                    minimalText = true,
                    modifier = Modifier.weight(1f, fill = false),
                    currentAssistant = settings.getCurrentAssistant(),
                    onUpdateAssistant = onUpdateAssistant,
                    onSelect = onUpdateChatModel,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // V3 Whisper：进入对话后顶栏右侧多出 22dp Context Ring（仅有消息时）。
                // 真实 used/total —— 取最后一条 assistant 消息的 usage
                // V3 review P2 #2: 之前用 totalTokens (= 该轮 prompt+completion 加起来), 跟 ring
                // 的"上下文占用"语义不符 (短问题 totalTokens 小 → ring 缩水, 反而误导). 改用
                // promptTokens (下一轮 LLM 实际加载的上下文长度), 也是用户最直观的"已占用".
                if (conversation.messageNodes.isNotEmpty()) {
                    val lastAssistant = conversation.currentMessages
                        .lastOrNull { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }
                    val lastUsage = lastAssistant?.usage
                    val messagesFingerprint = remember(conversation.currentMessages) {
                        ContextFootprintEstimator.inputFingerprint(conversation.currentMessages)
                    }
                    val compactsFingerprint = remember(contextCompacts) {
                        contextCompacts.fold(0L) { acc, compact ->
                            (acc * 31) xor compact.id.hashCode().toLong() xor compact.tokenEstimate.toLong()
                        }
                    }
                    val estimatedInputTokens = remember(messagesFingerprint, compactsFingerprint) {
                        ContextFootprintEstimator.estimateConversationInputTokens(conversation, contextCompacts)
                    }
                    val usedTokens = lastUsage?.promptTokens?.takeIf { it > 0 } ?: estimatedInputTokens
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
                // V3 Whisper：phone-screen.jsx 注释 "naked compose icon — no
                // surrounding circle"，仅渲染 26dp ink 描线图标，无背景圆。
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        // V3: ripple 改圆形
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .clickable { onNewChat() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HugeIcons.MessageAdd01,
                        contentDescription = "New Message",
                        tint = LocalChatTheme.current.ink,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AmberHeaderLine(width: androidx.compose.ui.unit.Dp) {
    // 主题感知 ink 色 —— legacy workspaceColors().ink 在浅色硬编码 #1F1F1F，
    // Paper 主题下需要 #2A241B 才能跟 bg/halo 协调
    Box(
        modifier = Modifier
            .width(width)
            .height(1.6.dp)
            .background(LocalChatTheme.current.ink, RoundedCornerShape(1.dp))
    )
}

// V3 Whisper 空白态本应为纯净留白 + 底部光晕；之前的 EmptyChatHero（宝石 + 问候）
// 是误读 phone-screen.jsx 未被 Hero 引用的 AmberMark 导致的；移除。
// AmberMark.kt 文件保留作未来 agent avatar 等场景的备用组件。
