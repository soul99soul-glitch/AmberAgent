package app.amber.feature.ui.pages.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.R
import app.amber.ai.provider.Model
import app.amber.core.context.CompactLifecycleState
import app.amber.core.context.ConversationCompact
import app.amber.core.files.FilesManager
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.settings.Settings
import app.amber.feature.runtime.OutcomeUnknownPrompt
import app.amber.feature.runtime.SandboxActivityUiState
import app.amber.feature.ui.components.ai.ChatInput
import app.amber.feature.ui.components.ai.ChatInputSubmissionOwner
import app.amber.feature.ui.components.ai.SubAgentDockState
import app.amber.feature.ui.components.ai.SubAgentStatusDock
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.hooks.ChatInputState
import app.amber.feature.webmount.primitives.WebMountSessionOwner
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
internal fun ChatPageComposerHost(
    inputState: ChatInputState,
    inputSubmissionOwner: ChatInputSubmissionOwner,
    inputActions: ChatPageInputActions,
    loading: Boolean,
    setting: Settings,
    conversation: Conversation,
    contextCompacts: List<ConversationCompact>,
    compactLifecycleState: CompactLifecycleState,
    pendingQueueCount: Int,
    timelineScrolling: Boolean,
    enableWebSearch: Boolean,
    configurationIssue: ChatConfigurationIssue?,
    outcomeUnknown: List<OutcomeUnknownPrompt>,
    onReconcileOutcomeUnknown: (effectId: String, retry: Boolean) -> Unit,
    sandboxActivity: SandboxActivityUiState?,
    scopedLiveSandboxActivity: SandboxActivityUiState?,
    onOpenSandbox: () -> Unit,
    onPreviousSandbox: (() -> Unit)?,
    onNextSandbox: (() -> Unit)?,
    onCancelSandbox: (() -> Unit)?,
    onOpenQueue: () -> Unit,
    suggestionFillPulseKey: Int,
    onConfigureModel: () -> Unit,
    onOpenConversation: (Uuid) -> Unit,
    onOpenWebMountSession: (sessionId: String, reopen: Boolean) -> Unit,
    onStop: () -> Unit,
    onUpdateChatModel: (Model) -> Unit,
    onUpdateSettings: (Settings) -> Unit,
    onCompactContext: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    val conversationRepository: ConversationRepository = koinInject()
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

    // Most history entries have no subagent dock. Keep the (class-heavy) dock
    // out of the first composition until it has ever had a task; once shown it
    // stays composed so its own exit animation is unchanged.
    val subAgentDockState: SubAgentDockState = koinInject()
    val subAgentDockUi by subAgentDockState.uiState.collectAsStateWithLifecycle()
    val subAgentDockHasTasks = subAgentDockUi.enabled && subAgentDockUi.tasks.isNotEmpty()
    var subAgentDockEverShown by remember(conversation.id) { mutableStateOf(false) }
    LaunchedEffect(subAgentDockHasTasks) {
        if (subAgentDockHasTasks) subAgentDockEverShown = true
    }

    // Same reading column as the timeline on tablets; phones are narrower than it.
    Column(
        Modifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = app.amber.feature.ui.adaptive.ChatReadingWidth),
    ) {
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
                generating = loading,
                onSaveOnly = { inputActions.confirmEdit(regenerate = false) },
                onSaveAndRegenerate = { inputActions.confirmEdit(regenerate = true) },
                onCancelEdit = {
                    val discardedFiles = inputState.drainAttachmentFilesForDiscard()
                    inputState.clearInput()
                    if (discardedFiles.isNotEmpty()) {
                        scope.launch { filesManager.deleteChatFiles(discardedFiles) }
                    }
                },
            )
        }
        if (!loading && configurationIssue != null &&
            (inputState.isEditing() || !inputActions.canSendWithoutChatModel(inputState.getContents()))) {
            ChatConfigurationHint(configurationIssue, onConfigureModel)
        }
        ChatInput(
            submissionOwner = inputSubmissionOwner,
            aboveComposerContent = {
                if (subAgentDockEverShown || subAgentDockHasTasks) {
                    SubAgentStatusDock(
                        currentConversationId = conversation.id,
                        onOpenConversation = { sourceId ->
                            if (sourceId != conversation.id) {
                                scope.launch {
                                    if (conversationRepository.existsConversationById(sourceId)) {
                                        onOpenConversation(sourceId)
                                    } else {
                                        toaster.show(context.getString(R.string.workspace_source_unavailable), type = ToastType.Error)
                                    }
                                }
                            }
                        },
                    )
                }
            },
            state = inputState,
            loading = loading,
            settings = setting,
            conversation = conversation,
            contextCompacts = contextCompacts,
            compactLifecycleState = compactLifecycleState,
            pendingQueueCount = pendingQueueCount,
            timelineScrolling = timelineScrolling,
            onCancelClick = onStop,
            enableSearch = enableWebSearch,
            onToggleSearch = {
                onUpdateSettings(setting.copy(enableWebSearch = !enableWebSearch))
            },
            sandboxActivity = sandboxActivity?.takeUnless {
                it.runtime == "WebMount" && conversationWebMountSessions.isNotEmpty()
            },
            onOpenSandbox = onOpenSandbox,
            onPreviousSandbox = onPreviousSandbox,
            onNextSandbox = onNextSandbox,
            onCancelSandbox = onCancelSandbox,
            webMountSessions = visibleWebMountSessions,
            webMountActivity = scopedLiveSandboxActivity
                ?.takeIf { it.runtime == "WebMount" && it.isActiveOperation() }
                ?.title,
            onDismissWebMount = {
                dismissedWebMountSessionIds = conversationWebMountSessions.map { it.sessionId }
            },
            onOpenWebMountSession = onOpenWebMountSession,
            onOpenQueue = onOpenQueue,
            suggestionFillPulseKey = suggestionFillPulseKey,
            onSendClick = inputActions::send,
            onLongSendClick = inputActions::longSend,
            onCompactContext = onCompactContext,
            onUpdateChatModel = onUpdateChatModel,
            onUpdateSettings = onUpdateSettings,
            onUpdateSearchService = { index ->
                onUpdateSettings(
                    setting.copy(
                        searchServiceSelected = index
                    )
                )
            },
        )
    }
}
