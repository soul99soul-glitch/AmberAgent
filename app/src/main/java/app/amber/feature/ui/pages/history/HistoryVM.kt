package app.amber.feature.ui.pages.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.getCurrentAssistant
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.service.ChatService
import kotlin.uuid.Uuid

private const val TAG = "HistoryVM"

class HistoryVM(
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsAggregator,
    private val chatService: ChatService,
) : ViewModel() {
    val assistant = settingsStore.settingsFlow
        .map { it.getCurrentAssistant() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conversations = settingsStore.settingsFlow
        .map { it.getCurrentAssistant().id }
        .distinctUntilChanged()
        .flatMapLatest { assistantId ->
            conversationRepo.getConversationsOfAssistantPaging(assistantId)
        }
        .catch {
            Log.e(TAG, "Error: ${it.message}")
        }
        .cachedIn(viewModelScope)

    /** 在途删除任务：Undo/purge 必须先 join，避免在途 delete 把刚恢复的会话再次删掉。 */
    private val deleteJobs = mutableMapOf<Uuid, Job>()

    fun deleteConversation(conversation: Conversation) {
        val job = viewModelScope.launch {
            // Cleanup is deferred so the snackbar Undo can restore the
            // conversation with attachments/images/favorites intact.
            chatService.deleteConversation(conversation, deferCleanup = true)
        }
        deleteJobs[conversation.id] = job
        job.invokeOnCompletion { deleteJobs.remove(conversation.id, job) }
    }

    fun purgeDeletedConversation(conversation: Conversation) {
        viewModelScope.launch {
            deleteJobs[conversation.id]?.join()
            conversationRepo.cleanupDeletedConversation(conversation)
        }
    }

    fun deleteAllConversations() {
        val assistant = assistant.value ?: return
        viewModelScope.launch {
            chatService.deleteConversationsOfAssistant(assistant.id)
        }
    }

    fun togglePinStatus(conversationId: Uuid) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversationId)
        }
    }

    fun restoreConversation(conversation: Conversation) {
        viewModelScope.launch {
            deleteJobs[conversation.id]?.join()
            chatService.markConversationRestored(conversation.id)
            conversationRepo.insertConversation(conversation)
        }
    }

    suspend fun getFullConversation(conversationId: Uuid): Conversation? {
        return conversationRepo.getConversationById(conversationId)
    }
}
