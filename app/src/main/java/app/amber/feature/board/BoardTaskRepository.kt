package app.amber.feature.board

import kotlinx.coroutines.flow.Flow
import app.amber.agent.data.db.dao.BoardTaskDao
import app.amber.agent.data.db.dao.BoardTaskEventDao
import app.amber.agent.data.db.entity.BoardTaskEntity
import app.amber.agent.data.db.entity.BoardTaskEventEntity
import app.amber.agent.data.db.entity.BoardTaskEventReview
import app.amber.agent.data.db.entity.BoardTaskEventType
import app.amber.agent.data.db.entity.BoardTaskState
import app.amber.agent.data.db.entity.boardTaskChipForState
import app.amber.agent.data.db.entity.stableBoardTaskId
import java.security.MessageDigest

class BoardTaskRepository(
    private val taskDao: BoardTaskDao,
    private val eventDao: BoardTaskEventDao,
) {
    fun observeTaskFlow(boardDate: String): Flow<List<BoardTaskEntity>> =
        taskDao.observeTaskFlow(boardDate)

    suspend fun getTask(id: String): BoardTaskEntity? = taskDao.getById(id)

    suspend fun activeNotificationTasks(): List<BoardTaskEntity> =
        taskDao.getActiveNotificationTasks()

    suspend fun recentEvents(taskId: String, limit: Int = DEFAULT_RECENT_EVENT_LIMIT): List<BoardTaskEventEntity> =
        eventDao.getRecentEvents(taskId, limit).asReversed()

    suspend fun createDispatched(
        sourceType: String,
        sourceRef: String,
        title: String,
        summary: String,
        riskLevel: String,
        displayBoardDate: String,
        metadataJson: String = "{}",
    ): BoardTaskEntity {
        val now = System.currentTimeMillis()
        val existing = taskDao.getBySource(sourceType, sourceRef)
        val baseTask = existing ?: BoardTaskEntity(
            id = stableBoardTaskId(sourceType, sourceRef),
            sourceType = sourceType,
            sourceRef = sourceRef,
            title = title.take(MAX_TITLE_CHARS),
            summary = summary.take(MAX_SUMMARY_CHARS),
            state = BoardTaskState.IN_PROGRESS,
            riskLevel = riskLevel,
            chipText = boardTaskChipForState(BoardTaskState.IN_PROGRESS),
            displayBoardDate = displayBoardDate,
            createdAt = now,
            updatedAt = now,
        )
        val task = baseTask.copy(
            title = title.take(MAX_TITLE_CHARS),
            summary = summary.take(MAX_SUMMARY_CHARS),
            riskLevel = riskLevel,
            state = BoardTaskState.IN_PROGRESS,
            chipText = boardTaskChipForState(BoardTaskState.IN_PROGRESS),
            displayBoardDate = displayBoardDate,
            updatedAt = now,
            // Re-dispatching an existing task starts a fresh round; drop any prior artifact so the
            // card never shows last round's finished material while the new round runs.
            artifactJson = null,
        )
        taskDao.upsert(task)
        if (existing?.state != BoardTaskState.IN_PROGRESS) {
            addEvent(
                taskId = task.id,
                type = BoardTaskEventType.DISPATCHED,
                message = if (existing == null) "任务已派发，等待任务会话继续推进" else "任务已重新派发，等待任务会话继续推进",
                now = now,
                metadataJson = metadataJson,
            )
        }
        return task
    }

    suspend fun markDone(taskId: String): BoardTaskEntity? =
        updateState(taskId, BoardTaskState.DONE, BoardTaskEventType.DONE, "任务已完成")

    suspend fun dispatch(taskId: String): BoardTaskEntity? {
        val existing = taskDao.getById(taskId) ?: return null
        if (existing.state == BoardTaskState.IN_PROGRESS) return existing
        return resetToInProgress(taskId, BoardTaskEventType.DISPATCHED, "任务已派发，等待任务会话继续推进")
    }

    suspend fun continueTask(taskId: String, message: String = "用户确认继续推进"): BoardTaskEntity? =
        resetToInProgress(taskId, BoardTaskEventType.USER_REPLIED, message)

    /**
     * Atomic finish: store the structured artifact and move to waiting_user in a single write,
     * plus an ARTIFACT_READY event whose message feeds the live notification. Returns null when
     * the task no longer exists or has already reached a terminal state.
     */
    suspend fun finishWithArtifact(taskId: String, artifactJson: String, summary: String): BoardTaskEntity? {
        val existing = taskDao.getById(taskId) ?: return null
        if (existing.state in BoardTaskState.terminal) return null
        val now = System.currentTimeMillis()
        val chip = boardTaskChipForState(BoardTaskState.WAITING_USER)
        taskDao.updateStateWithArtifact(taskId, BoardTaskState.WAITING_USER, chip, artifactJson, now)
        addEvent(
            taskId = taskId,
            type = BoardTaskEventType.ARTIFACT_READY,
            message = summary.take(MAX_EVENT_MESSAGE_CHARS),
            now = now,
        )
        return taskDao.getById(taskId)
    }

    suspend fun pauseForUser(taskId: String, message: String = "任务已暂停，等待用户继续"): BoardTaskEntity? =
        updateState(taskId, BoardTaskState.WAITING_USER, BoardTaskEventType.WAITING_USER, message)

    suspend fun snooze(taskId: String, message: String = "用户选择稍后处理"): BoardTaskEntity? {
        val task = taskDao.getById(taskId) ?: return null
        addEvent(taskId = taskId, type = BoardTaskEventType.USER_REPLIED, message = message, now = System.currentTimeMillis())
        return task
    }

    suspend fun recordUserReply(taskId: String, reply: String): BoardTaskEntity? =
        resetToInProgress(
            taskId = taskId,
            eventType = BoardTaskEventType.USER_REPLIED,
            message = "用户补充指令：${reply.trim()}",
        )

    suspend fun recordProgress(taskId: String, message: String): BoardTaskEntity? {
        val task = taskDao.getById(taskId) ?: return null
        addEvent(taskId = taskId, type = BoardTaskEventType.PROGRESS, message = message, now = System.currentTimeMillis())
        return task
    }

    suspend fun markDismissed(taskId: String): BoardTaskEntity? =
        updateState(taskId, BoardTaskState.DISMISSED, BoardTaskEventType.DISMISSED, "任务已忽略")

    suspend fun cancel(taskId: String): BoardTaskEntity? =
        updateState(taskId, BoardTaskState.DISMISSED, BoardTaskEventType.CANCELLED, "任务已取消")

    suspend fun markWaitingUser(taskId: String, message: String = "等待用户确认"): BoardTaskEntity? =
        updateState(taskId, BoardTaskState.WAITING_USER, BoardTaskEventType.WAITING_USER, message)

    suspend fun markBlocked(taskId: String, message: String = "任务遇到阻碍"): BoardTaskEntity? =
        updateState(taskId, BoardTaskState.BLOCKED, BoardTaskEventType.BLOCKED, message)

    suspend fun reviewEvents(startMs: Long, endMs: Long): List<BoardTaskEventReview> =
        eventDao.getReviewEvents(startMs, endMs)

    suspend fun pruneOldTerminal(cutoffMs: Long) {
        taskDao.deleteOldTerminalTasks(cutoffMs)
        eventDao.deleteOldTerminalEvents(cutoffMs)
    }

    private suspend fun updateState(
        taskId: String,
        state: String,
        eventType: String,
        message: String,
    ): BoardTaskEntity? {
        taskDao.getById(taskId) ?: return null
        val now = System.currentTimeMillis()
        val chip = boardTaskChipForState(state)
        taskDao.updateState(taskId, state, chip, now)
        addEvent(taskId = taskId, type = eventType, message = message, now = now)
        return taskDao.getById(taskId)
    }

    /**
     * Reset a task to in_progress for a new run round, clearing any prior artifact (single-slot
     * freshness). Used by dispatch / continue / user-reply paths — NOT by markWaitingUser or
     * markDone, which must preserve the just-finished artifact.
     */
    private suspend fun resetToInProgress(
        taskId: String,
        eventType: String,
        message: String,
    ): BoardTaskEntity? {
        taskDao.getById(taskId) ?: return null
        val now = System.currentTimeMillis()
        val chip = boardTaskChipForState(BoardTaskState.IN_PROGRESS)
        taskDao.resetForNewRound(taskId, BoardTaskState.IN_PROGRESS, chip, now)
        addEvent(taskId = taskId, type = eventType, message = message, now = now)
        return taskDao.getById(taskId)
    }

    private suspend fun addEvent(
        taskId: String,
        type: String,
        message: String,
        now: Long,
        metadataJson: String = "{}",
    ) {
        eventDao.insert(
            BoardTaskEventEntity(
                id = stableEventId(taskId, type, message, now),
                taskId = taskId,
                type = type,
                message = message.take(MAX_EVENT_MESSAGE_CHARS),
                metadataJson = metadataJson,
                ts = now,
            )
        )
    }

    private fun stableEventId(taskId: String, type: String, message: String, ts: Long): String {
        val input = "$taskId|$type|$ts|$message"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    companion object {
        private const val MAX_TITLE_CHARS = 200
        private const val MAX_SUMMARY_CHARS = 500
        private const val MAX_EVENT_MESSAGE_CHARS = 500
        private const val DEFAULT_RECENT_EVENT_LIMIT = 8
    }
}
