package app.amber.feature.ui.pages.sessionhome

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amber.core.files.FilesManager
import app.amber.core.model.Avatar
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.service.ChatService
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.home.ContinueCandidate
import app.amber.feature.home.ContinueCandidateAggregator
import app.amber.feature.home.ContinueDismissStore
import app.amber.feature.home.DEFAULT_DISMISS_DURATION
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * SessionHome 首页的 ViewModel：为会话面板提供列表所需的业务操作
 * （运行中任务、删除/置顶/重命名会话、更新设置），以及 P8-08 首页
 * 「继续」聚合（[continueCandidates] + [dismissContinueCandidate]）。
 *
 * 会话分页本身复用 [app.amber.feature.ui.pages.chat.ChatDrawerVM]
 * （activity 作用域，抽屉与首页共享同一滚动位置）。
 */
class SessionHomeVM(
    private val settingsStore: SettingsAggregator,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    private val filesManager: FilesManager,
    private val continueAggregator: ContinueCandidateAggregator,
    private val continueDismissStore: ContinueDismissStore,
) : ViewModel() {

    val conversationJobs: StateFlow<Map<Uuid, Job?>> = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** P8-08：首页「继续」聚合列表（持久投影，进程死亡后仍正确）。 */
    val continueCandidates: StateFlow<List<ContinueCandidate>> = continueAggregator
        .observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** P8-08：暂时隐藏一个候选（默认 24 小时，到期自动恢复）。 */
    fun dismissContinueCandidate(candidate: ContinueCandidate) {
        viewModelScope.launch {
            continueDismissStore.dismiss(
                sourceKind = candidate.sourceKind,
                sourceId = candidate.sourceId,
                until = Instant.now().plus(DEFAULT_DISMISS_DURATION),
            )
        }
    }

    fun updateSettings(newSettings: Settings) {
        viewModelScope.launch {
            val oldSettings = settingsStore.settingsFlow.first()
            checkUserAvatarDelete(oldSettings, newSettings)
            settingsStore.update(newSettings)
        }
    }

    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
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
            chatService.generateTitle(conversation.id, conversationFull, force)
        }
    }
}
