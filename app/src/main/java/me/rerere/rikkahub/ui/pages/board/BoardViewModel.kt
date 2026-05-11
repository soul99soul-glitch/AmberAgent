package me.rerere.rikkahub.ui.pages.board

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.agent.board.BoardRepository
import me.rerere.rikkahub.data.agent.board.TODAY_BOARD_AUTO_MUTE_DISMISS_COUNT
import me.rerere.rikkahub.data.agent.board.TODAY_BOARD_HARD_MUTE_WEIGHT
import me.rerere.rikkahub.data.agent.board.worker.BoardScheduler
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.BoardItemEntity
import me.rerere.rikkahub.data.db.entity.BoardWeightEntity
import kotlinx.coroutines.launch

class BoardViewModel(
    private val boardRepository: BoardRepository,
    private val settingsStore: SettingsStore,
    private val scheduler: BoardScheduler,
    private val appScope: AppScope,
) : ViewModel() {

    /** Trigger that re-evaluates [todayBoardDate] on refresh to handle the 04:00 cutoff. */
    private val boardDateTick = MutableStateFlow(0L)

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: Flow<List<BoardItemEntity>> = boardDateTick.flatMapLatest {
        boardRepository.observeItems(boardRepository.todayBoardDate())
    }

    val settings = settingsStore.settingsFlow

    fun markCompleted(itemId: String) {
        appScope.launch {
            boardRepository.markItemCompleted(itemId)
            boardRepository.getItem(itemId)?.let { recordWeight(it, WeightAction.COMPLETE) }
        }
    }

    fun markDismissed(itemId: String) {
        appScope.launch {
            boardRepository.markItemDismissed(itemId)
            boardRepository.getItem(itemId)?.let { recordWeight(it, WeightAction.DISMISS) }
        }
    }

    fun startChat(itemId: String) {
        appScope.launch {
            boardRepository.getItem(itemId)?.let { recordWeight(it, WeightAction.CHAT) }
        }
    }

    fun refresh() {
        scheduler.runOnce()
        // Re-evaluate todayBoardDate in case we crossed the 04:00 cutoff since creation.
        boardDateTick.value = System.currentTimeMillis()
    }

    // ---- Feedback Learning ------------------------------------------------------------

    private suspend fun recordWeight(item: BoardItemEntity, action: WeightAction) {
        val now = System.currentTimeMillis()
        val keyword = "" // MVP: whole-source weights only; keyword filtering in v1.1

        val existing = boardRepository.getWeight(item.sourceType, keyword)
        val weight = (existing?.weight ?: 0) + action.delta
        val dismissCount = when (action) {
            WeightAction.DISMISS -> (existing?.dismissCount7d ?: 0) + 1
            // Positive actions reset the mute counter so users can un-mute a source
            // through explicit positive engagement.
            else -> 0
        }

        // Auto-mute: 3 consecutive dismisses → hard mute (weight = -10).
        // Only overrides weight on DISMISS to allow positive actions to escape muted state.
        val finalWeight = if (action == WeightAction.DISMISS && dismissCount >= AUTO_MUTE_DISMISS_COUNT) {
            AUTO_MUTE_WEIGHT
        } else {
            weight
        }

        boardRepository.upsertWeight(
            BoardWeightEntity(
                sourceType = item.sourceType,
                keyword = keyword,
                weight = finalWeight,
                dismissCount7d = dismissCount,
                lastActionAt = now,
            )
        )
    }

    private enum class WeightAction(val delta: Int) {
        COMPLETE(+1),
        DISMISS(-1),
        CHAT(+2),
    }

    companion object {
        private const val AUTO_MUTE_DISMISS_COUNT = TODAY_BOARD_AUTO_MUTE_DISMISS_COUNT
        private const val AUTO_MUTE_WEIGHT = TODAY_BOARD_HARD_MUTE_WEIGHT
    }
}
