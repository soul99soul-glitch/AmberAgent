package app.amber.feature.ui.pages.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.service.ChatService
import app.amber.core.infra.AppScope
import kotlin.uuid.Uuid

private const val TAG = "HistoryVM"

class HistoryVM(
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    private val appScope: AppScope,
) : ViewModel() {
    val conversations = conversationRepo.getConversationsPaging()
        .catch {
            Log.e(TAG, "Error: ${it.message}")
        }
        .cachedIn(viewModelScope)

    /** 在途删除任务：Undo/purge 必须先 join，避免在途 delete 把刚恢复的会话再次删掉。 */
    private val deleteJobs = mutableMapOf<Uuid, Job>()

    fun deleteConversation(conversation: Conversation): Deferred<Unit> {
        val job = appScope.async(Dispatchers.Main.immediate) {
            // Cleanup is deferred so the snackbar Undo can restore the
            // conversation with attachments/images/favorites intact.
            chatService.deleteConversation(conversation, deferCleanup = true)
        }
        deleteJobs[conversation.id] = job
        job.invokeOnCompletion { deleteJobs.remove(conversation.id, job) }
        return job
    }

    fun purgeDeletedConversation(conversation: Conversation) {
        appScope.launch(Dispatchers.Main.immediate) {
            deleteJobs[conversation.id]?.join()
            // A failed delete must never remove files from a still-live conversation.
            if (!conversationRepo.existsConversationById(conversation.id)) {
                conversationRepo.cleanupDeletedConversation(conversation)
            }
        }
    }

    fun deleteAllConversations() {
        viewModelScope.launch {
            chatService.deleteAllConversations()
        }
    }

    fun togglePinStatus(conversationId: Uuid) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversationId)
        }
    }

    fun restoreConversation(conversation: Conversation): Deferred<Unit> =
        appScope.async(Dispatchers.Main.immediate) {
            deleteJobs[conversation.id]?.join()
            try {
                conversationRepo.insertConversation(conversation)
                chatService.markConversationRestored(conversation.id)
            } catch (error: Exception) {
                purgeDeletedConversation(conversation)
                throw error
            }
        }

    suspend fun getFullConversation(conversationId: Uuid): Conversation? {
        return conversationRepo.getConversationById(conversationId)
    }
}
