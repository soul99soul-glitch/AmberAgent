package app.amber.feature.ui.pages.chat

import android.net.Uri
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Transition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.event.AppEvent
import app.amber.core.event.AppEventBus
import app.amber.agent.R
import app.amber.core.files.FilesManager
import app.amber.core.model.Conversation
import app.amber.feature.ui.adaptive.LocalTwoPaneLayout
import app.amber.feature.ui.components.ai.ChatInputSubmissionOwner
import app.amber.feature.ui.components.workspace.WorkspaceFilePreview
import app.amber.feature.ui.components.workspace.SaveMessageToWorkspaceDialog
import app.amber.agent.data.workspace.ArtifactRepository
import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import app.amber.feature.workspace.WorkspaceManager
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.utils.ChatPerfTrace
import app.amber.agent.Screen
import app.amber.feature.ui.context.LocalToaster
import app.amber.core.utils.base64Decode
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
    /** The route's enter transition; the timeline composes once it has settled. */
    enterTransition: Transition<EnterExitState>? = null,
) {

    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    LaunchedEffect(vm) {
        app.amber.feature.ui.utils.ChatPerfHomeEntry.chatObserved(id.toString())
        ChatPerfTrace.entryRequested()
        vm.onChatVisible()
    }
    val toaster = LocalToaster.current
    val savedToWorkspaceText = stringResource(R.string.chat_page_saved_to_workspace)
    val filesManager: FilesManager = koinInject()
    val eventBus: AppEventBus = koinInject()
    val navController = LocalNavController.current
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
    val inputConversationFlow = remember(vm.conversation) {
        vm.conversation
            .map(Conversation::toChatPageInputSnapshot)
            .distinctUntilChanged()
    }
    val initialInputSnapshot = remember(id) {
        vm.conversation.value.toChatPageInputSnapshot()
    }
    val inputSnapshot by inputConversationFlow.collectAsStateWithLifecycle(
        initialValue = initialInputSnapshot,
    )
    val conversation = remember(inputSnapshot) { inputSnapshot.toConversation() }
    val timelineLoadState by vm.timelineLoadState.collectAsStateWithLifecycle()
    val timelineMarkdownReady by vm.timelineMarkdownReady.collectAsStateWithLifecycle()
    val timelinePrepared = timelineLoadState.initialized && timelineMarkdownReady
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

    // Beside the session list the list is the way back, so the header drops its back arrow.
    val isBigScreen = LocalTwoPaneLayout.current != null

    val inputState = vm.inputState
    val inputSubmissionOwner = remember(inputState) { ChatInputSubmissionOwner(inputState) }
    var anchorNodeId by remember(id, messageId, toolCallId) { mutableStateOf<Uuid?>(null) }

    LaunchedEffect(id, messageId, toolCallId) {
        if (nodeId == null && (messageId != null || toolCallId != null)) {
            val foundNodeId = vm.findNodeIdForAnchor(messageId, toolCallId)
            anchorNodeId = foundNodeId
            if (foundNodeId == null) {
                vm.chatListInitialized = true
            }
        }
    }
    val resolvedNodeId = nodeId ?: anchorNodeId

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(vm, files, text) {
        if (vm.routeInputInitialized) return@LaunchedEffect
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
        // The draft and this receipt share the VM lifetime: configuration
        // recreation retains edits, while a new route/VM can accept a delivery.
        vm.routeInputInitialized = true
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

    val compactInTimelineActive = isCompacting || compactLifecycleState.isActive
    val activeGeneration = loadingJob != null || pendingUserMessages.isNotEmpty() || compactInTimelineActive
    // Preserve the deep-link first-visible index at the same point as before. This
    // snapshot is used only when the LazyListState is first created; live plans are
    // collected and built inside the list content below.
    val initialConversation = remember(id) { vm.conversation.value }
    val initialTimelineConversation = if (timelinePrepared) {
        initialConversation
    } else {
        remember(id) { initialConversation.copy(messageNodes = emptyList()) }
    }
    val initialChatTimelinePlan = if (resolvedNodeId != null) {
        rememberChatTimelinePlan(
            conversation = initialTimelineConversation,
            regexes = setting.regexes,
            showAssistantBubble = setting.displaySetting.showAssistantBubble,
            loading = loadingJob != null,
            activeGeneration = activeGeneration,
            hasHistoryLoadingItem = !timelineLoadState.isFullyLoaded,
            pendingMessageCount = pendingUserMessages.size,
        )
    } else {
        null
    }
    // reverseLayout: lazy index 0 就是视觉底部（最新内容），默认状态即钉底，
    // 只有深链 nodeId 需要计算初始索引。
    val initialChatListIndex = remember(id, resolvedNodeId, initialConversation.messageNodes, initialChatTimelinePlan) {
        if (resolvedNodeId != null) {
            val messageIndex = initialConversation.messageNodes.indexOfFirst { it.id == resolvedNodeId }
            initialChatTimelinePlan?.lazyIndexForMessage(messageIndex).takeIf { messageIndex >= 0 } ?: 0
        } else {
            0
        }
    }
    val chatListState = key(id) {
        rememberLazyListState(initialFirstVisibleItemIndex = initialChatListIndex)
    }

    LaunchedEffect(resolvedNodeId) {
        // 无锚点时 reverseLayout 的 (0,0) 默认位就是底部。
        if (nodeId == null && messageId == null && toolCallId == null) {
            vm.chatListInitialized = true
        }
    }

    val timelineReady = rememberEnterTransitionSettled(enterTransition)

    // ChatDrawer 侧边栏已废弃移除（手机 ModalNavigationDrawer / 平板 PermanentNavigationDrawer
    // 两个挂载点一起拆掉）：会话列表入口由 Session 首页承担，顶栏左上是返回。
    ChatPageScaffold(
        timelineReady = timelineReady,
        timelinePrepared = timelinePrepared,
        inputState = inputState,
        inputSubmissionOwner = inputSubmissionOwner,
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
        resolvedNodeId = resolvedNodeId,
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


/**
 * True once the route's enter transition has landed (or immediately when there is none).
 * Kept across returns to this entry, so coming back does not blank the timeline again.
 * A timeout guards against a transition that never reports settling.
 */
@Composable
private fun rememberEnterTransitionSettled(transition: Transition<EnterExitState>?): Boolean {
    var settled by rememberSaveable { mutableStateOf(transition == null) }
    if (!settled && transition != null) {
        LaunchedEffect(transition) {
            withTimeoutOrNull(ENTER_SETTLE_TIMEOUT_MS) {
                snapshotFlow { transition.currentState == EnterExitState.Visible && !transition.isRunning }
                    .first { it }
            }
            settled = true
        }
    }
    return settled
}

private const val ENTER_SETTLE_TIMEOUT_MS = 600L