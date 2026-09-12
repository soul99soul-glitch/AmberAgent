package app.amber.feature.ui.pages.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import app.amber.core.model.Conversation
import app.amber.core.infra.AppScope
import app.amber.core.repository.ConversationRepository
import app.amber.core.service.ChatService
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import kotlin.uuid.Uuid

private const val TAG = "HistoryVM"

class HistoryVM(
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    private val appScope: AppScope,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
) : ViewModel() {
    private val reloadRequests = MutableStateFlow(0)
    private val _hasUpstreamError = MutableStateFlow(false)
    val hasUpstreamError: StateFlow<Boolean> = _hasUpstreamError

    /**
     * PagingSource failures are reported by Paging through loadState. A failure
     * while constructing or collecting the Pager is a separate boundary; keep
     * it visible to the page and allow a deliberate rebuild of the flow.
     */
    val conversations = reloadRequests
        .flatMapLatest {
            observeConversationPaging(
                source = { conversationRepo.getConversationsPaging() },
                onError = { error ->
                    Log.e(TAG, "Conversation history stream failed", error)
                    _hasUpstreamError.value = true
                },
            ).onEach { _hasUpstreamError.value = false }
        }
        .cachedIn(viewModelScope)

    fun retryUpstream() {
        _hasUpstreamError.value = false
        reloadRequests.value += 1
    }

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
            chatService.purgeDeletedConversation(conversation)
        }
    }

    fun deleteAllConversations() {
        viewModelScope.launch {
            chatService.deleteAllConversations()
        }
    }

    fun togglePinStatus(conversationId: Uuid) {
        viewModelScope.launch {
            chatService.togglePinnedStatus(conversationId)
        }
    }

    fun restoreConversation(conversation: Conversation): Deferred<Unit> =
        appScope.async(Dispatchers.Main.immediate) {
            val expectedRestoreEpoch = captureRestoreEpoch()
            deleteJobs[conversation.id]?.join()
            try {
                withRestoreWrite(expectedRestoreEpoch) {
                    conversationRepo.insertConversation(conversation)
                    chatService.markConversationRestored(conversation.id)
                }
            } catch (error: Exception) {
                purgeDeletedConversation(conversation)
                throw error
            }
        }

    suspend fun getFullConversation(conversationId: Uuid): Conversation? {
        return conversationRepo.getConversationById(conversationId)
    }

    private suspend fun captureRestoreEpoch(): Long? {
        val gate = restoreWriteGate ?: return null
        return currentCoroutineContext()[SyncRestoreWriteEpoch]?.value ?: gate.currentEpoch()
    }

    private suspend fun <T> withRestoreWrite(
        expectedRestoreEpoch: Long?,
        block: suspend () -> T,
    ): T {
        val gate = restoreWriteGate ?: return block()
        val epoch = expectedRestoreEpoch
            ?: currentCoroutineContext()[SyncRestoreWriteEpoch]?.value
            ?: gate.currentEpoch()
        return withContext(SyncRestoreWriteEpoch(epoch)) {
            gate.withCurrentWriterOrCancel(block)
        }
    }
}

/**
 * Catches both Pager construction failures and failures while collecting its
 * flow. A completed failure flow deliberately emits no replacement page so a
 * previously rendered Paging list can stay visible to the UI.
 */
internal fun observeConversationPaging(
    source: () -> Flow<PagingData<Conversation>>,
    onError: (Throwable) -> Unit,
): Flow<PagingData<Conversation>> = flow {
    try {
        emitAll(source())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        onError(error)
    }
}
