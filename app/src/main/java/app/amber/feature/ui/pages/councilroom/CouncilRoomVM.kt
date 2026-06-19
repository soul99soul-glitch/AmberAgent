package app.amber.feature.ui.pages.councilroom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.transformers.DocumentAsPromptTransformer
import app.amber.core.settings.findModelById
import app.amber.core.settings.getCurrentAssistant
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.feature.modelcouncil.CouncilRoomManager
import app.amber.feature.modelcouncil.CouncilRoomMode
import app.amber.feature.modelcouncil.HostAction
import app.amber.feature.modelcouncil.toCouncilParticipant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
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
    private val settingsStore: SettingsAggregator,
) : ViewModel() {
    private val cid: Uuid = Uuid.parse(conversationId)

    /**
     * Bumped to force [room] to re-subscribe to the store. Needed by [restart]:
     * closing a room evicts its store slot (and its StateFlow), and re-opening
     * creates a brand-new slot/flow. A subscription captured before the restart
     * would otherwise stay pinned to the dead slot and never see the new room.
     */
    private val reopen = MutableStateFlow(0)

    /**
     * Read-only mirror of [reopen] for the UI to use as a composition `key` so a
     * fresh room forces a brand-new [CouncilRoomBody] composition (timeline's
     * remember state — entries / poppedKeys / listState / scroll position — all
     * reset to the new room instead of being carried over from the old one).
     */
    val reopenToken: StateFlow<Int> = reopen.asStateFlow()

    /**
     * True while [restart] is closing the old room and opening a new one. The
     * page observes this to show a "议会重置中…" placeholder + crossfade
     * transition while the new room is materializing, instead of a hard cut.
     */
    private val _isRestarting = MutableStateFlow(false)
    val isRestarting: StateFlow<Boolean> = _isRestarting.asStateFlow()

    /**
     * Live room state. [CouncilRoomManager.observeRoom] is `suspend` (cold-loads
     * from SQLite on first access) and returns the store's per-conversation
     * StateFlow. We re-resolve it whenever [reopen] changes so a restart re-binds
     * to the fresh slot.
     *
     * [SharingStarted.WhileSubscribed] releases the store subscription when the
     * page leaves composition (backgrounded / navigated away), which keeps the
     * manager's in-memory room slot from being pinned unnecessarily.
     */
    val room: StateFlow<CouncilRoom?> = reopen
        .flatMapLatest { manager.observeRoom(cid) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = null,
        )

    // ── user actions ───────────────────────────────────────────────────────

    fun sendUserMessage(
        text: String,
        mentionTargets: List<String>,
        attachments: List<UIMessagePart> = emptyList(),
    ) {
        viewModelScope.launch {
            // Documents can't be seen by the council's generation path, so extract
            // their text here (app layer has the parsers) and hand it to the manager
            // to inline into member prompts. Images ride along as parts untouched.
            val documents = attachments.filterIsInstance<UIMessagePart.Document>()
            val attachmentText = buildString {
                documents.forEachIndexed { index, doc ->
                    if (index > 0) append("\n\n")
                    append("## 文件：${doc.fileName}\n")
                    append(DocumentAsPromptTransformer.extractText(doc))
                }
            }
            manager.userMessage(cid, text, mentionTargets, attachments, attachmentText)
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

    /**
     * Answer a host ask_user question and resume the suspended council. Called
     * from the timeline's answer card ([CouncilAskUserCard]).
     */
    fun resumeAfterUserAnswer(answer: String) {
        viewModelScope.launch {
            manager.resumeAfterUserAnswer(cid, answer)
        }
    }

    /**
     * Discard the current (usually finished) deliberation and start a brand-new
     * council in the same conversation, seeded from the latest configured seats.
     * Mirrors the drawer's open flow: close (force terminal + evict) → openRoom
     * overwrites the persisted room, then [reopen] re-binds [room] to the fresh
     * store slot. No-op when no seats are configured (nothing to deliberate).
     */
    fun restart() {
        viewModelScope.launch {
            android.util.Log.i("CouncilRestart", "restart() invoked for cid=$cid")
            val settings = settingsStore.settingsFlow.value
            val seats = settings.agentRuntime.modelCouncil.defaultSeats
            if (seats.isEmpty()) {
                android.util.Log.w("CouncilRestart", "restart() aborted: no defaultSeats configured")
                return@launch
            }
            _isRestarting.value = true
            try {
                val assistant = settings.getCurrentAssistant()
                val guests = seats.map { seat ->
                    seat.toCouncilParticipant().copy(
                        modelName = settings.findModelById(seat.modelId)?.displayName.orEmpty(),
                    )
                }
                android.util.Log.i("CouncilRestart", "restart(): calling close(cancel=true)")
                val closeResult = runCatching { manager.close(cid, cancel = true) }
                android.util.Log.i("CouncilRestart", "restart(): close returned $closeResult")
                if (closeResult.isFailure) {
                    android.util.Log.e("CouncilRestart", "restart(): close threw, aborting restart", closeResult.exceptionOrNull())
                    return@launch
                }
                android.util.Log.i("CouncilRestart", "restart(): calling openRoom")
                val openResult = runCatching {
                    manager.openRoom(
                        conversationId = cid,
                        hostAssistantId = assistant.id,
                        hostName = assistant.name.removeSuffix(" Agent").ifBlank { "Amber" },
                        objective = "多模型协作讨论",
                        initialGuests = guests,
                        maxRounds = settings.agentRuntime.modelCouncil.defaultRounds.coerceIn(2, 6),
                        hostModelIdOverride = settings.agentRuntime.modelCouncil.hostModelId,
                    )
                }
                android.util.Log.i("CouncilRestart", "restart(): openRoom returned $openResult; room before reopen bump = ${manager.peekRoom(cid)?.let { "status=${it.status} msgs=${it.messages.size}" }}")
                if (openResult.isFailure) {
                    android.util.Log.e("CouncilRestart", "restart(): openRoom threw, aborting restart", openResult.exceptionOrNull())
                    return@launch
                }
                val beforeBump = reopen.value
                reopen.value += 1
                android.util.Log.i("CouncilRestart", "restart(): reopen bumped $beforeBump -> ${reopen.value}")
            } finally {
                _isRestarting.value = false
            }
        }
    }
}
