package app.amber.feature.ui.pages.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.amber.ai.provider.Model
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.isEmptyInputMessage
import app.amber.agent.LAST_CONVERSATION_ID_PREF
import app.amber.agent.R
import app.amber.core.settings.defaultReasoningLevelForModel
import app.amber.core.settings.findModelById
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.context.ActiveCompactBoundary
import app.amber.core.context.CompactLifecycleState
import app.amber.core.context.ConversationCompact
import app.amber.core.context.ConversationContextRepository
import app.amber.core.files.FilesManager
import app.amber.core.model.Avatar
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.model.NodeFavoriteTarget
import app.amber.core.repository.ConversationRepository
import app.amber.core.repository.FavoriteRepository
import app.amber.core.service.ChatError
import app.amber.core.service.ChatService
import app.amber.core.service.ConversationTimelineLoadState
import app.amber.core.service.PendingUserMessage
import app.amber.core.service.PendingUserMessageMode
import app.amber.core.service.blockedReason
import app.amber.core.service.errorsForConversation
import app.amber.core.service.globalErrors
import app.amber.core.service.orchestrator.BranchMessageOrchestrator
import app.amber.core.service.orchestrator.RegenerateMessageOrchestrator
import app.amber.core.service.orchestrator.SendMessageOrchestrator
import app.amber.feature.runtime.OutcomeUnknownPrompt
import app.amber.feature.miniapp.ConversationDraftStore
import app.amber.feature.ui.hooks.writeStringPreference
import app.amber.feature.ui.hooks.ChatInputState
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

@OptIn(ExperimentalCoroutinesApi::class)
class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsAggregator,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    private val analytics: FirebaseAnalytics,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
    private val contextRepository: ConversationContextRepository,
    private val sendMessageOrchestrator: SendMessageOrchestrator,
    private val regenerateMessageOrchestrator: RegenerateMessageOrchestrator,
    private val branchMessageOrchestrator: BranchMessageOrchestrator,
    private val conversationDraftStore: ConversationDraftStore,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    val timelineLoadState: StateFlow<ConversationTimelineLoadState> =
        chatService.getTimelineLoadStateFlow(_conversationId)
    val contextCompacts: StateFlow<List<ConversationCompact>> =
        contextRepository.getCompactsFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeCompactBoundary: StateFlow<ActiveCompactBoundary?> =
        chatService.getActiveCompactBoundaryFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val compactLifecycleState: StateFlow<CompactLifecycleState> =
        chatService.getCompactLifecycleStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, CompactLifecycleState.idle())
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    val pendingUserMessages: StateFlow<List<PendingUserMessage>> =
        chatService
            .getPendingUserMessagesFlow(_conversationId)

    /**
     * Whether automatic context compaction is currently in progress for THIS
     * conversation. Drives the Codex-style "———正在压缩上下文———" timeline divider
     * while a compact summary is being generated.
     */
    val isCompacting: StateFlow<Boolean> =
        chatService
            .getIsCompactingFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Live-streaming summary text for the in-flight compaction. Empty string
     * when no compaction is running. ChatList renders the trailing portion
     * under the shimmer divider while the summary is being generated.
     */
    val streamingSummary: StateFlow<String> =
        chatService
            .getStreamingSummaryFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Kernel-path run id for this conversation, or null when running on the
     * legacy path or no run is active. UI can subscribe to runner.observe(id)
     * via [kernelRunStatus] for run lifecycle (running/completed/failed/cancelled).
     */
    val activeKernelRunId: StateFlow<app.amber.core.agent.runtime.AgentRunId?> =
        chatService.getActiveKernelRunFlow(_conversationId)

    /**
     * Latest snapshot from runner.observe(activeKernelRunId). Null status when
     * no kernel run is active. Surface only reacts when kernel path is in use.
     */
    val kernelRunStatus: StateFlow<app.amber.core.agent.runtime.RunStatus?> =
        activeKernelRunId
            .flatMapLatest { runId ->
                if (runId == null) {
                    kotlinx.coroutines.flow.flowOf(null)
                } else {
                    chatService.kernelRunner()?.observe(runId)?.map { it.status }
                        ?: kotlinx.coroutines.flow.flowOf(null)
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
            // P1-02: refresh OutcomeUnknown prompts (cold-start recovery may
            // have just marked effects after a process death).
            chatService.refreshOutcomeUnknown()
            // P3-03: restore a MiniApp-hosted composer draft (host.sendToConversation)
            // into the input box so the user can review it before sending.
            if (inputState.isEmpty()) {
                conversationDraftStore.load(_conversationId.toString())?.let { draft ->
                    inputState.setContents(draft.toParts())
                }
            }
        }

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference(LAST_CONVERSATION_ID_PREF, _conversationId.toString())
    }

    // P1-02: OutcomeUnknown prompts for this conversation — a non-idempotent
    // tool effect whose outcome was lost needs "confirm retry / abandon".
    val outcomeUnknown: StateFlow<List<OutcomeUnknownPrompt>> =
        chatService.outcomeUnknownFlow
            .map { it[_conversationId.toString()].orEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun reconcileOutcomeUnknown(effectId: String, retry: Boolean) {
        viewModelScope.launch {
            chatService.reconcileOutcomeUnknown(_conversationId, effectId, retry)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 网络搜索
    val enableWebSearch = settings.map {
        it.enableWebSearch
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 当前模型
    val currentChatModel = settings.map { settings ->
        settings.getCurrentChatModel()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 错误状态
    // P8-07: 错误按会话过滤——只暴露属于当前 conversationId 的生成错误；
    // 全局错误（conversationId == null，如全局 Provider 配置错误）单独暴露，
    // 由全局 banner 展示，不冒充当前会话消息错误。
    val errors: StateFlow<List<ChatError>> = chatService.errors
        .map { it.errorsForConversation(_conversationId) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val globalErrors: StateFlow<List<ChatError>> = chatService.errors
        .map { it.globalErrors() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(newSettings: Settings) {
        viewModelScope.launch {
            val oldSettings = settings.value
            // 检查用户头像是否有变化，如果有则删除旧头像
            checkUserAvatarDelete(oldSettings, newSettings)
            settingsStore.update(newSettings)
        }
    }

    // 检查用户头像删除
    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    // 设置聊天模型
    fun setChatModel(model: Model) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                val currentModelId = settings.chatModelId
                val currentModel = settings.findModelById(currentModelId)
                val currentDefaultReasoningLevel = currentModel
                    ?.let { settings.defaultReasoningLevelForModel(it) }
                    ?: settings.defaultReasoningLevelForModel(model)
                val currentReasoningLevel = settings.rememberedReasoningLevelsByModelId[
                    currentModelId.toString()
                ] ?: if (settings.reasoningLevel == ReasoningLevel.AUTO) {
                    currentDefaultReasoningLevel
                } else {
                    settings.reasoningLevel
                }
                val rememberedReasoningLevels = settings.rememberedReasoningLevelsByModelId +
                    (currentModelId.toString() to currentReasoningLevel)
                val selectedReasoningLevel = rememberedReasoningLevels[model.id.toString()]
                    ?: settings.defaultReasoningLevelForModel(model)
                settings.copy(
                    chatModelId = model.id,
                    reasoningLevel = selectedReasoningLevel,
                    rememberedReasoningLevelsByModelId = rememberedReasoningLevels,
                )
            }
        }
    }

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(
        content: List<UIMessagePart>,
        answer: Boolean = true,
        queueMode: PendingUserMessageMode = PendingUserMessageMode.FOLLOWUP,
    ): Boolean {
        val accepted = sendMessageOrchestrator.send(_conversationId, content, answer, queueMode)
        // P3-03: once anything is actually sent, a MiniApp-hosted composer
        // draft is consumed and must not come back on the next open.
        if (accepted) {
            viewModelScope.launch { conversationDraftStore.clear(_conversationId.toString()) }
        }
        return accepted
    }

    fun cancelPendingUserMessage(messageId: String) {
        chatService.cancelPendingUserMessage(_conversationId, messageId)
    }

    fun clearPendingUserMessages() {
        chatService.clearPendingUserMessages(_conversationId)
    }

    fun movePendingUserMessage(messageId: String, offset: Int) {
        chatService.movePendingUserMessage(_conversationId, messageId, offset)
    }

    /**
     * P8-01: 保存用户消息编辑。
     *
     * @param regenerate true 时保存后从新 user variant 生成新 assistant 分支（默认动作
     *   「保存并重新生成」）；false 仅保存（「仅保存」）。
     * @return 是否已受理（false 表示生成中冲突被拒绝——明确提示，不打断当前生成，
     *   调用方不应清空输入框）。
     */
    fun handleMessageEdit(
        parts: List<UIMessagePart>,
        messageId: Uuid,
        regenerate: Boolean = true,
    ): Boolean {
        if (parts.isEmptyInputMessage()) return false
        // 生成中编辑冲突预检（与 ChatService.editMessage 的权威守卫共用同一策略；
        // 这里同步返回 false，避免清空用户已编辑的内容）。
        val conflictReason = blockedReason(conversationJob.value != null, "请先停止生成再编辑消息")
        if (conflictReason != null) {
            chatService.addError(
                IllegalStateException(conflictReason),
                conversationId = _conversationId,
                title = context.getString(R.string.error_title_operation),
            )
            return false
        }
        analytics.logEvent("ai_edit_message", null)

        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts, regenerate)
        }
        return true
    }

    fun handleCompressContext(additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int): Job {
        return viewModelScope.launch {
            chatService.compressConversation(
                _conversationId,
                conversation.value,
                additionalPrompt,
                targetTokens,
                keepRecentMessages
            ).onFailure {
                // P8-07: 压缩是当前会话操作，错误必须绑定会话，不能成为全局错误
                chatService.addError(it, _conversationId, title = context.getString(R.string.error_title_compress_conversation))
            }
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        return branchMessageOrchestrator.fork(_conversationId, message)
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException("请先停止生成再删除消息"),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        regenerateMessageOrchestrator.regenerate(_conversationId, message, regenerateAssistantMsg)
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = ""
    ) {
        analytics.logEvent("ai_tool_approval", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved, reason)
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        analytics.logEvent("ai_tool_answer", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    /** P1-06: Stop 后显式恢复队列 —— 继续发送排队消息并开始生成。 */
    fun resumePendingQueue() {
        viewModelScope.launch {
            chatService.resumePendingQueue(_conversationId)
        }
    }

    /** P1-06: 把队列中第一条消息移回输入框，供编辑后再发送。 */
    fun moveFirstPendingMessageToInput() {
        viewModelScope.launch {
            chatService.takePendingMessageForInput(_conversationId)?.let { parts ->
                inputState.setContents(parts)
            }
        }
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, conversation.value)
        }
    }

    suspend fun ensureTimelineLoaded(): Conversation =
        chatService.ensureConversationTimelineLoaded(_conversationId)

    suspend fun loadOlderTimelinePage() {
        chatService.loadOlderTimelinePage(_conversationId)
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            val updatedConversation = conversation.value.copy(title = title)
            chatService.saveConversation(_conversationId, updatedConversation)
        }
    }

    fun setConversationAutoApproveToolCalls(enabled: Boolean) {
        viewModelScope.launch {
            val updatedConversation = conversation.value.copy(autoApproveToolCalls = enabled)
            chatService.updateConversationState(_conversationId) {
                it.copy(autoApproveToolCalls = enabled)
            }
            chatService.saveConversation(_conversationId, updatedConversation)
            if (enabled) {
                chatService.approvePendingAutoApprovableTools(_conversationId)
            }
        }
    }

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            chatService.deleteConversation(conversation)
        }
    }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversation.id)
        }
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            // 以目标会话 id 落库：从会话列表对「其它」会话重新生成标题时，
            // 传当前会话 id 会把新标题写到当前会话上。
            chatService.generateTitle(conversation.id, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun updateConversation(newConversation: Conversation) {
        chatService.updateConversationState(_conversationId) {
            newConversation
        }
    }

    fun selectMessageNode(nodeId: Uuid, selectIndex: Int) {
        viewModelScope.launch {
            chatService.selectMessageNode(_conversationId, nodeId, selectIndex)
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }
        }
    }

}
