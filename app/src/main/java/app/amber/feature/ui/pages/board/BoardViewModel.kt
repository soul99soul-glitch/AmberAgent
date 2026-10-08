package app.amber.feature.ui.pages.board

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import app.amber.agent.AppScope
import app.amber.feature.board.BoardRepository
import app.amber.feature.board.TODAY_BOARD_AUTO_MUTE_DISMISS_COUNT
import app.amber.feature.board.TODAY_BOARD_HARD_MUTE_WEIGHT
import app.amber.feature.board.hotlist.CUSTOM_TOPIC_ID_PREFIX
import app.amber.feature.board.hotlist.CUSTOM_TOPIC_PROVIDER_ID
import app.amber.feature.board.hotlist.DeepReadSeedInput
import app.amber.feature.board.hotlist.HotListDashboard
import app.amber.feature.board.hotlist.HotListItem
import app.amber.feature.board.hotlist.HotListProviderSnapshot
import app.amber.feature.board.hotlist.HotListRepository
import app.amber.feature.board.hotlist.HotListScheduler
import app.amber.feature.board.hotlist.HotTopic
import app.amber.feature.board.hotlist.HotTopicSource
import app.amber.feature.board.hotlist.applyInterestFilter
import app.amber.feature.board.hotlist.filterEnabledSources
import app.amber.feature.board.hotlist.presentationTitle
import app.amber.feature.board.worker.BoardScheduler
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.agent.data.db.entity.BoardItemEntity
import app.amber.agent.data.db.entity.BoardWeightEntity
import app.amber.agent.data.db.entity.DailyReviewEntity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID

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

    /** Masthead issue counter — iOS shows No.tasks+1 in the discovery dateline. */
    val deepReadCount: Flow<Int> = hotListRepository.observeDeepReadCount()

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

    /**
     * Builds a user-created deep-read topic from pasted text / link / file seeds,
     * persists it in the hot-topic cache (so the prefetcher picks the seeds up on
     * every run, including retries and process restarts), and returns it for the
     * regular [prepareDeepReadTopic] + navigate flow.
     */
    suspend fun prepareCustomDeepReadTopic(
        title: String,
        seeds: List<DeepReadSeedInput>,
        templateId: String? = null,
    ): HotTopic {
        val sources = seeds.mapIndexed { index, seed ->
            HotTopicSource(
                providerId = CUSTOM_TOPIC_PROVIDER_ID,
                providerName = seed.providerName,
                rank = index + 1,
                title = seed.title,
                url = seed.url,
                content = seed.content,
            )
        }
        val topic = HotTopic(
            id = CUSTOM_TOPIC_ID_PREFIX + UUID.randomUUID().toString(),
            title = title.trim(),
            sources = sources,
            sourceCount = sources.size,
            bestRank = 1,
            latestFetchedAt = System.currentTimeMillis(),
            // The per-topic pick wins over the board default at run time
            // (iOS task.templateId), and is stored for history labels.
            deepReadTemplateId = templateId?.takeIf { it.isNotBlank() },
        )
        hotListRepository.upsertTopic(topic)
        return topic
    }

    // ---- Feedback Learning ------------------------------------------------------------

    private suspend fun recordWeight(item: BoardItemEntity, action: WeightAction) {
        val now = System.currentTimeMillis()
        val keyword = "" // MVP: whole-source weights only; keyword filtering in v1.1
        val sourceType = item.sourceType

        val existing = boardRepository.getWeight(sourceType, keyword)
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
                sourceType = sourceType,
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
