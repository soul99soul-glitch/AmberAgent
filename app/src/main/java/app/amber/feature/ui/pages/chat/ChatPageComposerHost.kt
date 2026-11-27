package app.amber.feature.ui.pages.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.R
import app.amber.ai.provider.Model
import app.amber.core.context.CompactLifecycleState
import app.amber.core.context.ConversationCompact
import app.amber.core.files.FilesManager
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.settings.Settings
import app.amber.feature.macgateway.CloudDialogueSubmission
import app.amber.feature.macgateway.CloudDialogueUiState
import app.amber.feature.macgateway.CloudSubmissionKind
import app.amber.feature.macgateway.CloudTaskSession
import app.amber.feature.macgateway.MacGatewayRepository
import app.amber.feature.runtime.OutcomeUnknownPrompt
import app.amber.feature.runtime.SandboxActivityUiState
import app.amber.feature.ui.components.ai.ChatInput
import app.amber.feature.ui.components.ai.ChatInputSubmissionOwner
import app.amber.feature.ui.components.ai.CloudDialogueStatusDock
import app.amber.feature.ui.components.ai.SubAgentDockState
import app.amber.feature.ui.components.ai.SubAgentStatusDock
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.hooks.ChatInputState
import app.amber.feature.webmount.primitives.WebMountSessionOwner
import com.dokar.sonner.ToastType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * Immutable conversation-scoped projection of cloud/local async task state consumed by the
 * composer dock. Isolates the composer from `cursor` ticks, unrelated conversation updates,
 * and gateway reachability refreshes (R6-4).
 */
@Immutable
internal data class ChatComposerCloudStateSlice(
    val conversationId: String,
    val cloudActive: Boolean,
    val selectedFollowupWorkItemId: String?,
    val activeTasks: List<CloudTaskSession>,
    val outcomeUnknownTasks: List<CloudTaskSession>,
    val recentInputs: List<CloudDialogueSubmission>,
) {
    fun toDockUiState(): CloudDialogueUiState = CloudDialogueUiState(
        enabled = cloudActive,
        activeConversationId = conversationId,
        cursor = 0L,
        selectedFollowupWorkItemId = selectedFollowupWorkItemId,
        tasks = (activeTasks + outcomeUnknownTasks).distinctBy { it.workItemId },
        submissions = recentInputs,
        lastError = null,
    )
}

internal fun projectChatComposerCloudState(
    conversationId: String,
    cloudActive: Boolean,
    cloudDialogueState: CloudDialogueUiState,
    localAsyncState: CloudDialogueUiState,
): ChatComposerCloudStateSlice {
    val source = if (cloudActive) cloudDialogueState else localAsyncState
    val activeTasks = source.activeTasksForConversation(conversationId)
    val outcomeUnknownTasks = source.outcomeUnknownTasksForConversation(conversationId)
    val recentInputs = source.submissionsForConversation(conversationId)
        .filter {
            it.kind == CloudSubmissionKind.FOLLOWUP ||
                it.stopTooLate ||
                (cloudActive && it.kind == CloudSubmissionKind.MESSAGE)
        }
        .take(3)
    val resolvedFollowupWorkItemId = source.resolvedFollowupTarget(conversationId)?.workItemId
    return ChatComposerCloudStateSlice(
        conversationId = conversationId,
        cloudActive = cloudActive,
        selectedFollowupWorkItemId = resolvedFollowupWorkItemId,
        activeTasks = activeTasks,
        outcomeUnknownTasks = outcomeUnknownTasks,
        recentInputs = recentInputs,
    )
}

internal fun observeChatComposerCloudStateSlice(
    conversationId: String,
    cloudActiveFlow: Flow<Boolean>,
    cloudDialogueStateFlow: Flow<CloudDialogueUiState>,
    localAsyncStateFlow: Flow<CloudDialogueUiState>,
): Flow<ChatComposerCloudStateSlice> = combine(
    cloudActiveFlow.distinctUntilChanged(),
    cloudDialogueStateFlow,
    localAsyncStateFlow,
) { cloudActive, cloudDialogueState, localAsyncState ->
    projectChatComposerCloudState(
        conversationId = conversationId,
        cloudActive = cloudActive,
        cloudDialogueState = cloudDialogueState,
        localAsyncState = localAsyncState,
    )
}.distinctUntilChanged()

@Composable
internal fun ChatPageComposerHost(
    inputState: ChatInputState,
    inputSubmissionOwner: ChatInputSubmissionOwner,
    conversationFlow: Flow<Conversation>,
    suggestionsPaused: Boolean,
    inputActions: ChatPageInputActions,
    loading: Boolean,
    setting: Settings,
    conversation: Conversation,
    contextCompacts: List<ConversationCompact>,
    compactLifecycleState: CompactLifecycleState,
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
    suggestionFillPulseKey: Int,
    startSuggestions: List<String> = emptyList(),
    onFillSuggestion: (String) -> Unit = {},
    onSendSuggestion: (String) -> Boolean = { false },
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
    val macGatewayRepository: MacGatewayRepository = koinInject()
    val localAsyncTaskCoordinator: app.amber.core.service.LocalAsyncTaskCoordinator = koinInject()
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

    val stage = LocalChatStageMode.current
    val isPersonalAgentStage = stage is ChatStageMode.PersonalAgent
    val imeVisible = WindowInsets.isImeVisible
    val inputHasContent = inputState.textContent.text.isNotEmpty() ||
        inputState.messageContent.isNotEmpty() || inputState.attachmentImports.isNotEmpty()
    val defaultBoardSuggestion = stringResource(R.string.amber_redesign_suggestion_board)
    val defaultReplySuggestion = stringResource(R.string.amber_redesign_suggestion_reply)
    val defaultConceptSuggestion = stringResource(R.string.amber_redesign_suggestion_concept)
    val visibleConfigurationIssue = configurationIssue?.takeIf {
        !loading && (inputState.isEditing() || !inputActions.canSendWithoutChatModel(inputState.getContents()))
    }
    val duetSuggestions = if (isPersonalAgentStage && visibleConfigurationIssue == null &&
        !loading && !suggestionsPaused && !imeVisible && !inputHasContent && !inputState.isEditing()
    ) {
        if (conversation.messageNodes.isEmpty()) {
            startSuggestions.ifEmpty {
                listOf(defaultBoardSuggestion, defaultReplySuggestion, defaultConceptSuggestion)
            }
        } else {
            // The input snapshot omits chatSuggestions and same-ID streaming edits.
            val suggestionsFlow = remember(conversationFlow) {
                conversationFlow.map(Conversation::actionSuggestionTexts).distinctUntilChanged()
            }
            suggestionsFlow.collectAsStateWithLifecycle(initialValue = emptyList()).value
        }
    } else {
        emptyList()
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
        if (isPersonalAgentStage && duetSuggestions.isNotEmpty()) {
            PersonalAgentSuggestionStrip(
                suggestions = duetSuggestions,
                onClickSuggestion = onFillSuggestion,
                onLongClickSuggestion = { onSendSuggestion(it) },
            )
        }
        if (visibleConfigurationIssue != null) {
            ChatConfigurationHint(
                issue = visibleConfigurationIssue,
                compactPill = isPersonalAgentStage,
                onConfigure = onConfigureModel,
            )
        }
        ChatInput(
            submissionOwner = inputSubmissionOwner,
            duetStyle = isPersonalAgentStage,
            belowComposerContent = { composerImeVisible ->
                if (stage is ChatStageMode.PersonalAgent) {
                    stage.bottomChrome(composerImeVisible)
                }
            },
            aboveComposerContent = {
                ChatComposerCloudStatusDockHost(
                    conversationId = conversation.id,
                    macGatewayRepository = macGatewayRepository,
                    localAsyncTaskCoordinator = localAsyncTaskCoordinator,
                )
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
            timelineScrolling = timelineScrolling,
            onCancelClick = onStop,
            enableSearch = enableWebSearch,
            onToggleSearch = {
                onUpdateSettings(setting.copy(enableWebSearch = !enableWebSearch))
            },
            sandboxActivity = sandboxActivity?.takeUnless {
                (isPersonalAgentStage && it.toolName == "agent_idle") ||
                    (it.runtime == "WebMount" && conversationWebMountSessions.isNotEmpty())
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

@Composable
private fun ChatComposerCloudStatusDockHost(
    conversationId: Uuid,
    macGatewayRepository: MacGatewayRepository,
    localAsyncTaskCoordinator: app.amber.core.service.LocalAsyncTaskCoordinator,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val conversationIdString = conversationId.toString()
    val cloudStateSliceFlow = remember(conversationIdString, macGatewayRepository, localAsyncTaskCoordinator) {
        observeChatComposerCloudStateSlice(
            conversationId = conversationIdString,
            cloudActiveFlow = macGatewayRepository.state.map { it.isCloudDialogueActive },
            cloudDialogueStateFlow = macGatewayRepository.cloudDialogueState,
            localAsyncStateFlow = localAsyncTaskCoordinator.uiState,
        )
    }
    val initialCloudStateSlice = remember(conversationIdString, macGatewayRepository, localAsyncTaskCoordinator) {
        projectChatComposerCloudState(
            conversationId = conversationIdString,
            cloudActive = macGatewayRepository.state.value.isCloudDialogueActive,
            cloudDialogueState = macGatewayRepository.cloudDialogueState.value,
            localAsyncState = localAsyncTaskCoordinator.uiState.value,
        )
    }
    val cloudStateSlice by cloudStateSliceFlow.collectAsStateWithLifecycle(
        initialValue = initialCloudStateSlice,
    )
    val isCloudActive = cloudStateSlice.cloudActive
    val dockUiState = remember(cloudStateSlice) { cloudStateSlice.toDockUiState() }

    CloudDialogueStatusDock(
        conversationId = conversationIdString,
        cloudActive = isCloudActive,
        uiState = dockUiState,
        onSelectFollowupTarget = { workItemId ->
            if (isCloudActive) {
                macGatewayRepository.selectFollowupTarget(workItemId)
            } else {
                localAsyncTaskCoordinator.selectFollowupTarget(conversationId, workItemId)
            }
        },
        onStopTask = { workItemId ->
            scope.launch {
                runCatching {
                    if (isCloudActive) {
                        macGatewayRepository.stopCloudWorkItem(workItemId)
                    } else {
                        localAsyncTaskCoordinator.stopTask(conversationId, workItemId)
                    }
                }.onFailure { error ->
                    toaster.show(error.message ?: "Stop failed", type = ToastType.Error)
                }
            }
        },
        onRecheckOutcomeUnknown = { workItemId ->
            if (isCloudActive) {
                scope.launch {
                    runCatching { macGatewayRepository.recheckCloudOutcomeUnknown(workItemId) }
                        .onSuccess { settled ->
                            if (!settled) {
                                toaster.show(
                                    context.getString(R.string.mac_gateway_cloud_outcome_unknown_hint),
                                )
                            }
                        }
                        .onFailure { error ->
                            toaster.show(error.message ?: "Recheck failed", type = ToastType.Error)
                        }
                }
            }
        },
        onRetryOutcomeUnknown = { workItemId ->
            if (isCloudActive) {
                scope.launch {
                    runCatching {
                        macGatewayRepository.retryCloudOutcomeUnknown(
                            conversationId = conversationIdString,
                            workItemId = workItemId,
                        )
                    }.onFailure { error ->
                        toaster.show(error.message ?: "Retry failed", type = ToastType.Error)
                    }
                }
            }
        },
        onAbandonOutcomeUnknown = { workItemId ->
            if (isCloudActive) {
                scope.launch {
                    macGatewayRepository.abandonCloudOutcomeUnknown(workItemId)
                }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonalAgentSuggestionStrip(
    suggestions: List<String>,
    onClickSuggestion: (String) -> Unit,
    onLongClickSuggestion: (String) -> Unit,
) {
    val tokens = app.amber.feature.ui.theme.LocalAmberTokens.current
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 6.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(
            items = suggestions,
            key = { it },
        ) { suggestion ->
            Surface(
                modifier = Modifier
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = { onClickSuggestion(suggestion) },
                        onLongClick = { onLongClickSuggestion(suggestion) },
                    ),
                shape = CircleShape,
                color = if (tokens.isDark) tokens.surface else tokens.raised,
                contentColor = tokens.ink2,
                border = BorderStroke(0.5.dp, tokens.floatingOutline),
                tonalElevation = 0.dp,
            ) {
                Text(
                    text = suggestion,
                    color = tokens.ink2,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}
