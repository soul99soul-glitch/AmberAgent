package app.amber.feature.ui.pages.sessionhome

import app.amber.core.model.Conversation
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal const val HOME_DELETE_UNDO_MS = 5_000L

internal data class HomeDeleteUndoState(
    val pendingIds: Set<Uuid> = emptySet(),
    val deadlineMillis: Long? = null,
    val remainingSeconds: Int = 0,
    val committingIds: Set<Uuid> = emptySet(),
    // Retain completed/uncertain IDs for this VM's lifetime: an old cached Paging
    // generation or a stale Continue projection must never flash them back in.
    val retainedIds: Set<Uuid> = emptySet(),
    val rowVersions: Map<Uuid, Long> = emptyMap(),
) {
    val hiddenIds: Set<Uuid> get() = pendingIds + committingIds + retainedIds
}

internal enum class HomeDeleteFailureKind { Restored, AlreadyDeleted, Unverified }
internal data class HomeDeleteFailure(val sequence: Long, val kind: HomeDeleteFailureKind)

/**
 * Main-thread confined, owned only by viewModelScope. No durable pending state:
 * cancelling the owner abandons pending work without invoking the delete service.
 * Clock, delay and UI lifecycle are independent; only this owner can commit.
 */
internal class HomeDeleteUndo(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val delete: suspend (Conversation) -> Unit,
    private val exists: suspend (Uuid) -> Boolean,
    private val onError: (Throwable) -> Unit = {},
) {
    private val _state = MutableStateFlow(HomeDeleteUndoState())
    val state = _state.asStateFlow()
    private val _failure = MutableStateFlow<HomeDeleteFailure?>(null)
    val failure = _failure.asStateFlow()
    private val pending = linkedMapOf<Uuid, Conversation>()
    private var batch = 0L
    private var errorSequence = 0L
    private var timer: Job? = null

    fun enqueue(conversation: Conversation) {
        // A duplicate callback cannot extend the window, even at its boundary.
        if (conversation.id in _state.value.hiddenIds) return
        if ((_state.value.deadlineMillis ?: Long.MAX_VALUE) <= nowMillis()) commit(batch)
        pending[conversation.id] = conversation
        val token = ++batch
        val deadline = nowMillis() + HOME_DELETE_UNDO_MS
        _state.value = _state.value.copy(
            pendingIds = pending.keys.toSet(), deadlineMillis = deadline, remainingSeconds = 5,
        )
        timer?.cancel()
        timer = scope.launch {
            while (token == batch) {
                val remaining = deadline - nowMillis()
                if (remaining <= 0) {
                    commit(token)
                    return@launch
                }
                _state.value = _state.value.copy(remainingSeconds = ((remaining + 999) / 1000).toInt())
                delay(minOf(remaining, 100L))
            }
        }
    }

    fun undo(): Boolean {
        val deadline = _state.value.deadlineMillis ?: return false
        if (nowMillis() >= deadline) {
            commit(batch)
            return false
        }
        val ids = pending.keys.toSet()
        pending.clear()
        batch++
        timer?.cancel()
        val old = _state.value
        _state.value = old.copy(
            pendingIds = emptySet(), deadlineMillis = null, remainingSeconds = 0,
            rowVersions = old.rowVersions + ids.associateWith { (old.rowVersions[it] ?: 0L) + 1 },
        )
        return true
    }

    fun acknowledgeFailure(sequence: Long) {
        if (_failure.value?.sequence == sequence) _failure.value = null
    }

    private fun commit(token: Long) {
        if (token != batch || pending.isEmpty()) return
        val conversations = pending.values.toList()
        pending.clear()
        batch++
        // This transition is synchronous: undo can never enter a committing batch.
        _state.value = _state.value.copy(
            pendingIds = emptySet(), deadlineMillis = null, remainingSeconds = 0,
            committingIds = _state.value.committingIds + conversations.map { it.id },
        )
        scope.launch {
            for (conversation in conversations) {
                currentCoroutineContext().ensureActive()
                var restored = false
                try {
                    delete(conversation)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    onError(error)
                    // NonCancellable deletion can throw *after* the durable delete.
                    // Only a positive database check permits restoring this row.
                    val present = try {
                        exists(conversation.id)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (checkError: Exception) {
                        onError(checkError)
                        null
                    }
                    restored = present == true
                    _failure.value = HomeDeleteFailure(++errorSequence, when (present) {
                        true -> HomeDeleteFailureKind.Restored
                        false -> HomeDeleteFailureKind.AlreadyDeleted
                        null -> HomeDeleteFailureKind.Unverified
                    })
                }
                val old = _state.value
                _state.value = old.copy(
                    committingIds = old.committingIds - conversation.id,
                    retainedIds = if (restored) old.retainedIds else old.retainedIds + conversation.id,
                    rowVersions = if (restored) old.rowVersions +
                        (conversation.id to ((old.rowVersions[conversation.id] ?: 0L) + 1))
                        else old.rowVersions,
                )
            }
        }
    }
}
