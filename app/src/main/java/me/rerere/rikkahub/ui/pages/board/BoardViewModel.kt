package me.rerere.rikkahub.ui.pages.board

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.agent.board.BoardRepository
import me.rerere.rikkahub.data.agent.board.TODAY_BOARD_AUTO_MUTE_DISMISS_COUNT
import me.rerere.rikkahub.data.agent.board.TODAY_BOARD_HARD_MUTE_WEIGHT
import me.rerere.rikkahub.data.agent.board.hotlist.HotListDashboard
import me.rerere.rikkahub.data.agent.board.hotlist.HotListItem
import me.rerere.rikkahub.data.agent.board.hotlist.HotListProviderSnapshot
import me.rerere.rikkahub.data.agent.board.hotlist.HotListRepository
import me.rerere.rikkahub.data.agent.board.hotlist.HotListScheduler
import me.rerere.rikkahub.data.agent.board.hotlist.HotTopic
import me.rerere.rikkahub.data.agent.board.hotlist.HotTopicSource
import me.rerere.rikkahub.data.agent.board.hotlist.applyInterestFilter
import me.rerere.rikkahub.data.agent.board.hotlist.filterEnabledSources
import me.rerere.rikkahub.data.agent.board.hotlist.presentationTitle
import me.rerere.rikkahub.data.agent.board.worker.BoardScheduler
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.db.entity.BoardItemEntity
import me.rerere.rikkahub.data.db.entity.BoardWeightEntity
import me.rerere.rikkahub.data.db.entity.DailyReviewEntity
import kotlinx.coroutines.launch

class BoardViewModel(
    private val boardRepository: BoardRepository,
    private val hotListRepository: HotListRepository,
    private val settingsStore: SettingsAggregator,
    private val scheduler: BoardScheduler,
    private val hotListScheduler: HotListScheduler,
    private val appScope: AppScope,
) : ViewModel() {

    /** Trigger that re-evaluates [todayBoardDate] on refresh to handle the 04:00 cutoff. */
    private val boardDateTick = MutableStateFlow(0L)

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: Flow<List<BoardItemEntity>> = boardDateTick.flatMapLatest {
        boardRepository.observeItems(boardRepository.todayBoardDate())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val dailyReview: Flow<DailyReviewEntity?> = boardDateTick.flatMapLatest {
        boardRepository.observeDailyReview(boardRepository.todayBoardDate())
    }

    val settings = settingsStore.settingsFlow

    val hotListDashboard: Flow<HotListDashboard> = combine(
        hotListRepository.observeDashboard(),
        settings,
        hotListRepository.observeSources(),
    ) { dashboard, currentSettings, customSources ->
        val board = currentSettings.agentRuntime.todayBoard
        val enabledSources = board.hotListEnabledSources + customSources
            .filter { it.enabled }
            .map { it.id }
        dashboard
            .filterEnabledSources(enabledSources)
            .applyInterestFilter(board.hotListFocusKeywords, board.hotListFilterMode)
    }

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
        hotListScheduler.runOnce()
        // Re-evaluate todayBoardDate in case we crossed the 04:00 cutoff since creation.
        boardDateTick.value = System.currentTimeMillis()
    }

    fun refreshHotList() {
        hotListScheduler.runOnce()
    }

    suspend fun confirmDeepReadCost() {
        settingsStore.update { current ->
            current.copy(
                agentRuntime = current.agentRuntime.copy(
                    todayBoard = current.agentRuntime.todayBoard.copy(
                        deepReadFirstUseConfirmed = true,
                    )
                )
            )
        }
    }

    suspend fun createProviderTopic(provider: HotListProviderSnapshot, item: HotListItem): HotTopic {
        val title = item.presentationTitle
        val source = HotTopicSource(
            providerId = provider.providerId,
            providerName = provider.providerName,
            rank = item.rank,
            title = item.title,
            displayTitle = item.displayTitle,
            url = item.url,
            heat = item.heat,
            images = item.images,
        )
        val topic = HotTopic(
            id = HotListRepository.topicId("${provider.providerId}:${item.url ?: item.title}"),
            title = title,
            sources = listOf(source),
            sourceCount = 1,
            bestRank = item.rank,
            latestFetchedAt = provider.fetchedAt,
        )
        return topic
    }

    suspend fun prepareDeepReadTopic(topic: HotTopic, forceRegenerate: Boolean = false): HotTopic {
        hotListRepository.upsertTopic(topic)
        if (forceRegenerate) {
            hotListRepository.clearDeepRead(topic.id)
        }
        return topic
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
