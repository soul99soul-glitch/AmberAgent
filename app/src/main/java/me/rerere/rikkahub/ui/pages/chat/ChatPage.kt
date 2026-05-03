package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.agent.AgentToolActivityStore
import me.rerere.rikkahub.data.agent.SandboxActivityUiState
import me.rerere.rikkahub.data.agent.ToolActivityStatus
import me.rerere.rikkahub.data.datastore.AgentOperationPreviewMode
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.SandboxActivitySheet
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

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
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
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    val inputState = vm.inputState

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            val localFiles = filesManager.createChatFilesByContents(files)
            val contentTypes = files.mapNotNull { file ->
                filesManager.getFileMimeType(file)
            }
            val parts = buildList {
                localFiles.forEachIndexed { index, file ->
                    val type = contentTypes.getOrNull(index)
                    if (type?.startsWith("image/") == true) {
                        add(UIMessagePart.Image(url = file.toString()))
                    } else if (type?.startsWith("video/") == true) {
                        add(UIMessagePart.Video(url = file.toString()))
                    } else if (type?.startsWith("audio/") == true) {
                        add(UIMessagePart.Audio(url = file.toString()))
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

    val chatListState = rememberLazyListState()
    LaunchedEffect(vm) {
        if (nodeId == null && !vm.chatListInitialized && chatListState.layoutInfo.totalItemsCount > 0) {
            chatListState.scrollToItem(chatListState.layoutInfo.totalItemsCount - 1)
            vm.chatListInitialized = true
        }
    }

    LaunchedEffect(nodeId, conversation.messageNodes.size) {
        if (nodeId != null && conversation.messageNodes.isNotEmpty() && !vm.chatListInitialized) {
            val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
            if (index >= 0) {
                chatListState.scrollToItem(index)
            }
            vm.chatListInitialized = true
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
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = true,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
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
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = false,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
            BackHandler(drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }
        }
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
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    enableWebSearch: Boolean,
    currentChatModel: Model?,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var previewMode by rememberSaveable { mutableStateOf(false) }
    var sandboxOverlayOpen by rememberSaveable { mutableStateOf(false) }
    var selectedSandboxIndex by rememberSaveable(conversation.id) { mutableStateOf<Int?>(null) }
    val hazeState = rememberHazeState()
    val activityStore: AgentToolActivityStore = koinInject()
    val liveSandboxActivity by activityStore.sandboxActivity.collectAsStateWithLifecycle()
    val messageSandboxActivities = remember(conversation.messageNodes, loadingJob, processingStatus) {
        conversation.deriveSandboxActivities(
            loading = loadingJob != null,
            processingStatus = processingStatus,
        )
    }
    val rawSandboxTimeline = mergeSandboxTimeline(
        messageActivities = messageSandboxActivities,
        liveActivity = liveSandboxActivity?.withStepProgress(conversation),
    )
    val sandboxTimeline = when (setting.agentRuntime.operationPreviewMode) {
        AgentOperationPreviewMode.ALWAYS -> rawSandboxTimeline
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

    LaunchedEffect(sandboxTimeline.size, sandboxTimeline.lastOrNull()?.toolCallId) {
        if (sandboxActivity == null) {
            sandboxOverlayOpen = false
        }
        if (latestSandboxIndex < 0 && selectedSandboxIndex != null) {
            selectedSandboxIndex = null
        }
    }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        AssistantBackground(setting = setting)
        Scaffold(
            topBar = {
                TopBar(
                    settings = setting,
                    conversation = conversation,
                    bigScreen = bigScreen,
                    drawerState = drawerState,
                    previewMode = previewMode,
                    onNewChat = {
                        navigateToChatPage(navController)
                    },
                    onClickMenu = {
                        previewMode = !previewMode
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
                    mcpManager = vm.mcpManager,
                    hazeState = hazeState,
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
                    onSendClick = {
                        if (currentChatModel == null) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(inputState.getContents())
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onLongSendClick = {
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(content = inputState.getContents(), answer = false)
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
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
                    onCompressContext = { additionalPrompt, targetTokens, keepRecentMessages ->
                        vm.handleCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
                    },
                )
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            ChatList(
                innerPadding = innerPadding,
                conversation = conversation,
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
                    vm.updateConversation(
                        conversation.copy(
                            messageNodes = conversation.messageNodes.map { node ->
                                if (node.id == newNode.id) {
                                    newNode
                                } else {
                                    node
                                }
                            }
                        ))
                    vm.saveConversationAsync()
                },
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
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
                        chatListState.animateScrollToItem(index)
                    }
                },
                onToolApproval = { toolCallId, approved, reason ->
                    vm.handleToolApproval(toolCallId, approved, reason)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
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
    }
}

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
    val sandboxTools = sandboxActivityTools()
    if (sandboxTools.isEmpty()) {
        return processingStatus?.takeIf { loading && it.isNotBlank() }?.let {
            listOf(
                SandboxActivityUiState(
                    toolCallId = "processing-status",
                    toolName = "agent_processing",
                    title = it,
                    status = ToolActivityStatus.RUNNING,
                    runtime = "agent-run",
                    canCancel = true,
                    stepIndex = 1,
                    stepTotal = 1,
                )
            )
        } ?: emptyList()
    }

    return sandboxTools.mapIndexed { index, tool ->
        val status = tool.activityStatus(loading)
        val outputJson = tool.outputJson()
        SandboxActivityUiState(
            toolCallId = tool.toolCallId,
            toolName = tool.toolName,
            title = tool.sandboxTitle(),
            status = status,
            inputPreview = tool.inputPreview(),
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

private fun SandboxActivityUiState.withStepProgress(conversation: Conversation): SandboxActivityUiState {
    val sandboxTools = conversation.sandboxActivityTools()
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

private fun Conversation.currentRunMessages() =
    currentMessages.drop((currentMessages.indexOfLast { it.role == MessageRole.USER } + 1).coerceAtLeast(0))

private fun UIMessagePart.Tool.isSandboxActivityTool(): Boolean =
    toolName.startsWith("mcp__") ||
        toolName in setOf(
            "search_web",
            "scrape_web",
            "webview_open",
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

private fun UIMessagePart.Tool.activityStatus(loading: Boolean): ToolActivityStatus = when {
    approvalState is ToolApprovalState.Pending -> ToolActivityStatus.WAITING_FOR_PERMISSION
    approvalState is ToolApprovalState.Denied -> ToolActivityStatus.CANCELLED
    !isExecuted && loading -> ToolActivityStatus.RUNNING
    !isExecuted -> ToolActivityStatus.RUNNING
    hasFailure() -> ToolActivityStatus.FAILED
    else -> ToolActivityStatus.SUCCEEDED
}

private fun UIMessagePart.Tool.sandboxTitle(): String {
    val input = inputAsJson()
    return when (toolName) {
        "search_web" -> "网页搜索 ${input.getFirstStringContent("query", "q", "keyword", "keywords").orEmpty().compactSandboxText(20)}"
        "scrape_web" -> "打开网页 ${input.getFirstStringContent("url", "link", "uri").orEmpty().compactSandboxText(24)}"
        "webview_open" -> "打开网页 ${input.getStringContent("url").orEmpty().compactSandboxText(24)}"
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

private fun UIMessagePart.Tool.inputPreview(): String {
    val input = inputAsJson()
    return when (toolName) {
        "search_web" -> input.getFirstStringContent("query", "q", "keyword", "keywords")
        "scrape_web" -> input.getFirstStringContent("url", "link", "uri")
        "webview_open" -> input.getStringContent("url")
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
    toolName == "webview_open" -> "webview"
    toolName == "terminal_execute" -> "alpine-proot-stage1"
    toolName.startsWith("terminal_session_") -> "android-shell-stage0"
    toolName.startsWith("file_") -> "saf-workspace"
    toolName.startsWith("screen_") || toolName == "vlm_task" -> "accessibility-service"
    toolName.startsWith("mcp__") -> "mcp"
    else -> ""
}

private fun UIMessagePart.Tool.defaultWorkspace(): String = when {
    toolName.startsWith("terminal_") || toolName.startsWith("file_") -> "/workspace"
    else -> ""
}

private fun UIMessagePart.Tool.hasFailure(): Boolean {
    val outputJson = outputJson()
    val exitCode = outputJson["exit_code"]?.jsonPrimitiveOrNull?.intOrNull
    val error = outputJson.getStringContent("error")
    return !error.isNullOrBlank() ||
        (exitCode != null && exitCode != 0) ||
        outputText().contains("Exception", ignoreCase = true)
}

private fun UIMessagePart.Tool.outputTail(outputJson: JsonObject): String {
    val output = outputJson.getStringContent("output")
        ?: outputJson.getStringContent("error")
        ?: outputText()
    return output.trim().takeLast(1_600)
}

private fun UIMessagePart.Tool.outputJson(): JsonObject {
    val output = outputText()
    if (output.isBlank()) return JsonObject(emptyMap())
    return runCatching {
        JsonInstant.parseToJsonElement(output) as? JsonObject
    }.getOrNull() ?: JsonObject(emptyMap())
}

private fun UIMessagePart.Tool.outputText(): String =
    output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

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
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateTitle: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        navigationIcon = {
            if (!bigScreen) {
                FilledTonalIconButton(
                    onClick = {
                        scope.launch { drawerState.open() }
                    },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                ) {
                    Icon(HugeIcons.Menu03, "Messages")
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(
                    topStart = 22.dp,
                    topEnd = 16.dp,
                    bottomEnd = 22.dp,
                    bottomStart = 16.dp,
                ),
            ) {
                Text(
                    text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            FilledTonalIconButton(
                onClick = {
                    onClickMenu()
                },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            ) {
                Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, "Chat Options")
            }

            FilledIconButton(
                onClick = {
                    onNewChat()
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            ) {
                Icon(HugeIcons.MessageAdd01, "New Message")
            }
        },
    )
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}
