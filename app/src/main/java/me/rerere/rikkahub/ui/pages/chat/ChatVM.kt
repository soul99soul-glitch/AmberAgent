package me.rerere.rikkahub.ui.pages.chat

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
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.LAST_CONVERSATION_ID_PREF
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.context.ActiveCompactBoundary
import me.rerere.rikkahub.data.context.CompactLifecycleState
import me.rerere.rikkahub.data.context.ConversationCompact
import me.rerere.rikkahub.data.context.ConversationContextRepository
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.ConversationTimelineLoadState
import me.rerere.rikkahub.service.PendingUserMessage
import me.rerere.rikkahub.service.PendingUserMessageMode
import me.rerere.rikkahub.service.orchestrator.BranchMessageOrchestrator
import me.rerere.rikkahub.service.orchestrator.RegenerateMessageOrchestrator
import me.rerere.rikkahub.service.orchestrator.SendMessageOrchestrator
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.hooks.ChatInputState
import java.util.Locale
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
    val kernelRunStatus: StateFlow<app.amber.core.agent.runtime.AgentRunStatus?> =
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
        }

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference(LAST_CONVERSATION_ID_PREF, _conversationId.toString())
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
    val errors: StateFlow<List<ChatError>> = chatService.errors

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
    fun setChatModel(assistant: Assistant, model: Model) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            it.copy(
                                chatModelId = model.id
                            )
                        } else {
                            it
                        }
                    })
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
    ): Boolean = sendMessageOrchestrator.send(_conversationId, content, answer, queueMode)

    fun cancelPendingUserMessage(messageId: String) {
        chatService.cancelPendingUserMessage(_conversationId, messageId)
    }

    fun clearPendingUserMessages() {
        chatService.clearPendingUserMessages(_conversationId)
    }

    fun movePendingUserMessage(messageId: String, offset: Int) {
        chatService.movePendingUserMessage(_conversationId, messageId, offset)
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        analytics.logEvent("ai_edit_message", null)

        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts)
        }
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
                chatService.addError(it, title = context.getString(R.string.error_title_compress_conversation))
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
            conversationRepo.deleteConversation(conversation)
        }
    }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversation.id)
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            val updatedConversation = conversationFull.copy(assistantId = targetAssistantId)
            if (conversation.id == _conversationId) {
                chatService.saveConversation(_conversationId, updatedConversation)
                settingsStore.updateAssistant(targetAssistantId)
            } else {
                conversationRepo.updateConversation(updatedConversation)
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(_conversationId, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
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
