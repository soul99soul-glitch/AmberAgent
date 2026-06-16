package app.amber.feature.ui.pages.councilroom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.feature.modelcouncil.CouncilRoomManager
import app.amber.feature.modelcouncil.CouncilRoomMode
import app.amber.feature.modelcouncil.HostAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flattenConcat
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * ViewModel for the [CouncilRoomPage]. Owns nothing — it is a thin bridge between
 * the page UI and the [CouncilRoomManager] singleton. The room state itself lives
 * in the manager (keyed by conversationId); this VM just subscribes and forwards
 * user actions.
 *
 * Following the [app.amber.feature.ui.pages.chat.ChatVM] pattern: the
 * conversationId is passed as a Koin runtime parameter (first ctor param),
 * parsed to [Uuid], and threaded into every manager call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CouncilRoomVM(
    conversationId: String,
    private val manager: CouncilRoomManager,
) : ViewModel() {
    private val cid: Uuid = Uuid.parse(conversationId)

    /**
     * Live room state. [CouncilRoomManager.observeRoom] is `suspend` (cold-loads
     * from SQLite on first access), so we emit its resulting StateFlow from a
     * cold flow and flatten it into a single hot stream.
     *
     * [SharingStarted.WhileSubscribed] releases the store subscription when the
     * page leaves composition (backgrounded / navigated away), which keeps the
     * manager's in-memory room slot from being pinned unnecessarily.
     */
    val room: StateFlow<CouncilRoom?> = flow {
        emit(manager.observeRoom(cid))
    }.flattenConcat().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = null,
    )

    // ── user actions ───────────────────────────────────────────────────────

    fun sendUserMessage(text: String, mentionTargets: List<String>) {
        viewModelScope.launch {
            manager.userMessage(cid, text, mentionTargets)
        }
    }

    fun triggerHostAction(action: HostAction) {
        viewModelScope.launch {
            manager.hostAction(cid, action)
        }
    }

    fun switchMode(mode: CouncilRoomMode) {
        viewModelScope.launch {
            manager.switchMode(cid, mode)
        }
    }

    fun requestSynthesize() {
        viewModelScope.launch {
            manager.synthesize(cid)
        }
    }

    fun close() {
        viewModelScope.launch {
            manager.close(cid, cancel = true)
        }
    }
}
