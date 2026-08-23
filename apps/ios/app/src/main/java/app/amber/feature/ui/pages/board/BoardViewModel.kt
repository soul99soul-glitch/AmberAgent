package app.amber.feature.ui.pages.board

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import app.amber.agent.AppScope
import app.amber.feature.board.BoardRepository
import app.amber.feature.board.BoardTaskRepository
import app.amber.feature.board.BoardTaskRunReason
import app.amber.feature.board.BoardTaskRunner
import app.amber.feature.board.TODAY_BOARD_AUTO_MUTE_DISMISS_COUNT
import app.amber.feature.board.TODAY_BOARD_HARD_MUTE_WEIGHT
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
import app.amber.agent.data.db.entity.BoardTaskEntity
import app.amber.agent.data.db.entity.BoardTaskEventEntity
import app.amber.agent.data.db.entity.BoardTaskState
import app.amber.agent.data.db.entity.BoardWeightEntity
import app.amber.agent.data.db.entity.DailyReviewEntity
import app.amber.agent.data.db.entity.OpportunityEntity
import app.amber.feature.runtime.AgentLiveStatusNotifier
import app.amber.feature.runtime.BoardTaskLiveSnapshot
import app.amber.feature.board.OpportunityRepository
import kotlinx.coroutines.launch

class BoardViewModel(
    private val boardRepository: BoardRepository,
    private val boardTaskRepository: BoardTaskRepository,
    private val opportunityRepository: OpportunityRepository,
    private val hotListRepository: HotListRepository,
    private val settingsStore: SettingsAggregator,
    private val scheduler: BoardScheduler,
    private val hotListScheduler: HotListScheduler,
    private val appScope: AppScope,
    private val liveStatusNotifier: AgentLiveStatusNotifier,
    private val boardTaskRunner: BoardTaskRunner,
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

    @OptIn(ExperimentalCoroutinesApi::class)
    val tasks: Flow<List<BoardTaskEntity>> = boardDateTick.flatMapLatest {
        boardTaskRepository.observeTaskFlow(boardRepository.todayBoardDate())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val opportunities: Flow<List<OpportunityEntity>> = boardDateTick.flatMapLatest {
        opportunityRepository.observeSuggested()
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

    fun markTaskDone(taskId: String) {
        appScope.launch {
            val task = boardTaskRepository.markDone(taskId) ?: return@launch
            boardRepository.markItemsCompletedBySource(task.sourceType, task.sourceRef)
            recordTaskWeight(task.sourceType, WeightAction.COMPLETE)
            liveStatusNotifier.cancelBoardTask(taskId)
            liveStatusNotifier.notifyBoardTask(task.toLiveSnapshot(content = "已标记完成"))
            notifyActiveBoardTasks()
        }
    }

    fun dispatchTask(taskId: String) {
        appScope.launch {
            val task = boardTaskRepository.dispatch(taskId) ?: return@launch
            recordTaskWeight(task.sourceType, WeightAction.CHAT)
            notifyActiveBoardTasks(updatedTask = task, updatedContent = "已经派发，可打开任务会话继续推进")
            boardTaskRunner.start(task.id, BoardTaskRunReason.DISPATCH)
        }
    }

    fun dispatchOpportunity(opportunityId: String) {
        appScope.launch {
            val task = opportunityRepository.dispatch(opportunityId) ?: return@launch
            recordTaskWeight(task.sourceType, WeightAction.CHAT)
            notifyActiveBoardTasks(updatedTask = task, updatedContent = "已经派发，Amber 正在处理任务")
            boardTaskRunner.start(task.id, BoardTaskRunReason.DISPATCH)
        }
    }

    fun dismissOpportunity(opportunityId: String) {
        appScope.launch {
            opportunityRepository.dismiss(opportunityId, reason = "user_dismissed")
            boardDateTick.value = System.currentTimeMillis()
        }
    }

    fun muteOpportunityType(opportunityId: String) {
        appScope.launch {
            opportunityRepository.mute(opportunityId, scope = "type")
            boardDateTick.value = System.currentTimeMillis()
        }
    }

    fun markTaskDismissed(taskId: String) {
        appScope.launch {
            val task = boardTaskRepository.markDismissed(taskId) ?: return@launch
            boardRepository.markItemsDismissedBySource(task.sourceType, task.sourceRef)
            recordTaskWeight(task.sourceType, WeightAction.DISMISS)
            liveStatusNotifier.cancelBoardTask(taskId)
            notifyActiveBoardTasks()
        }
    }

    fun cancelTask(taskId: String) {
        appScope.launch {
            val task = boardTaskRepository.getTask(taskId) ?: return@launch
            boardTaskRunner.cancel(taskId)
            boardRepository.markItemsDismissedBySource(task.sourceType, task.sourceRef)
            recordTaskWeight(task.sourceType, WeightAction.DISMISS)
            liveStatusNotifier.cancelBoardTask(taskId)
            notifyActiveBoardTasks()
        }
    }

    fun snoozeTask(taskId: String) {
        appScope.launch {
            boardTaskRepository.snooze(taskId)
            liveStatusNotifier.cancelBoardTask(taskId)
            notifyActiveBoardTasks()
        }
    }

    fun startTaskChat(taskId: String) {
        appScope.launch {
            boardTaskRepository.getTask(taskId)?.let { task ->
                recordTaskWeight(task.sourceType, WeightAction.CHAT)
            }
        }
    }

    suspend fun taskSessionPrompt(taskId: String): String {
        val task = boardTaskRepository.getTask(taskId)
            ?: return "请打开任务流并查看这个任务：$taskId"
        val events = boardTaskRepository.recentEvents(taskId)
        return task.executionSessionPrompt(events)
    }

    fun refresh() {
        scheduler.runOnce()
        hotListScheduler.runOnce()
        appScope.launch {
            opportunityRepository.expireSuggested()
            boardDateTick.value = System.currentTimeMillis()
        }
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
        recordTaskWeight(item.sourceType, action)
    }

    private suspend fun recordTaskWeight(sourceType: String, action: WeightAction) {
        val now = System.currentTimeMillis()
        val keyword = "" // MVP: whole-source weights only; keyword filtering in v1.1

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

    private suspend fun notifyActiveBoardTasks(
        updatedTask: BoardTaskEntity? = null,
        updatedContent: String? = null,
    ) {
        val activeTasks = boardTaskRepository.activeNotificationTasks()
        val leadTask = activeTasks.firstOrNull() ?: return
        val content = if (updatedTask?.id == leadTask.id && updatedContent != null) {
            updatedContent
        } else {
            leadTask.defaultLiveContent()
        }
        liveStatusNotifier.notifyBoardTask(
            leadTask.toLiveSnapshot(
                content = content,
                activeTaskCount = activeTasks.size,
            )
        )
    }

    private fun BoardTaskEntity.toLiveSnapshot(
        content: String,
        activeTaskCount: Int = 1,
    ): BoardTaskLiveSnapshot =
        BoardTaskLiveSnapshot(
            taskId = id,
            title = title,
            state = state,
            chipText = chipText,
            content = content,
            updatedAt = updatedAt,
            activeTaskCount = activeTaskCount,
        )

    private fun BoardTaskEntity.defaultLiveContent(): String = when (state) {
        BoardTaskState.WAITING_USER -> "等待用户确认"
        BoardTaskState.IN_PROGRESS -> "已经派发，可打开任务会话继续推进"
        else -> title
    }

    private fun BoardTaskEntity.executionSessionPrompt(events: List<BoardTaskEventEntity>): String {
        val eventText = events
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { event -> "- ${event.type}: ${event.message}" }
            ?: "- 暂无任务事件"
        return """
            这是一个 Amber 任务执行会话，不是闲聊。

            任务：$title
            当前状态：${state.executionLabel()}
            摘要：$summary
            来源：$sourceType

            最近事件：
            $eventText

            请按下面格式推进：
            1. 当前目标
            2. 已完成步骤
            3. 下一步
            4. 是否需要我确认

            进展写回：当你推进、等待确认、受阻、完成或取消任务时，请调用 board_task_record 写入任务状态和事件。

            约束：高风险动作、系统权限动作、发送消息、删除/覆盖数据、ADB/Accessibility 自动操作都必须停在确认前。
        """.trimIndent()
    }

    private fun String.executionLabel(): String = when (this) {
        BoardTaskState.IN_PROGRESS -> "已经派发"
        BoardTaskState.WAITING_USER -> "等待确认"
        BoardTaskState.BLOCKED -> "遇到阻碍"
        BoardTaskState.DONE -> "任务完成"
        BoardTaskState.DISMISSED -> "已忽略"
        else -> this
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
