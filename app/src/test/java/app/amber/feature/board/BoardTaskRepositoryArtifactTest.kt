package app.amber.feature.board

import app.amber.agent.data.db.dao.BoardTaskDao
import app.amber.agent.data.db.dao.BoardTaskEventDao
import app.amber.agent.data.db.entity.BoardTaskEntity
import app.amber.agent.data.db.entity.BoardTaskEventEntity
import app.amber.agent.data.db.entity.BoardTaskEventReview
import app.amber.agent.data.db.entity.BoardTaskState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Behavioral coverage of the v1.2 artifact invariants:
 *  - finishWithArtifact is atomic: state -> waiting_user AND artifact stored in one shot.
 *  - reset paths (dispatch / continue / user reply) clear the artifact so a stale finished
 *    result is never shown while a new round runs.
 *  - waiting_user / done transitions that are NOT resets preserve the artifact.
 */
class BoardTaskRepositoryArtifactTest {

    private fun repo(seed: BoardTaskEntity): Pair<BoardTaskRepository, FakeBoardTaskDao> {
        val dao = FakeBoardTaskDao().apply { tasks[seed.id] = seed }
        return BoardTaskRepository(dao, FakeBoardTaskEventDao()) to dao
    }

    private fun seedTask(state: String, artifact: String? = null) = BoardTaskEntity(
        id = "task-1",
        sourceType = "opportunity",
        sourceRef = "opp-1",
        title = "Prepare",
        summary = "",
        state = state,
        riskLevel = "low",
        chipText = "x",
        displayBoardDate = "2026-05-31",
        createdAt = 1L,
        updatedAt = 1L,
        artifactJson = artifact,
    )

    @Test
    fun finishWithArtifact_sets_waiting_user_and_stores_artifact_atomically() = runTest {
        val (repository, dao) = repo(seedTask(BoardTaskState.IN_PROGRESS))

        val result = repository.finishWithArtifact("task-1", """{"title":"材料"}""", "材料")

        assertNotNull(result)
        assertEquals(BoardTaskState.WAITING_USER, dao.tasks["task-1"]?.state)
        assertEquals("""{"title":"材料"}""", dao.tasks["task-1"]?.artifactJson)
    }

    @Test
    fun finishWithArtifact_does_not_resurrect_terminal_task() = runTest {
        val (repository, dao) = repo(seedTask(BoardTaskState.DISMISSED, artifact = """{"title":"旧材料"}"""))

        val result = repository.finishWithArtifact("task-1", """{"title":"新材料"}""", "新材料")

        assertNull(result)
        assertEquals(BoardTaskState.DISMISSED, dao.tasks["task-1"]?.state)
        assertEquals("""{"title":"旧材料"}""", dao.tasks["task-1"]?.artifactJson)
    }

    @Test
    fun continueTask_clears_artifact_and_resets_to_in_progress() = runTest {
        val (repository, dao) = repo(seedTask(BoardTaskState.WAITING_USER, artifact = """{"title":"旧材料"}"""))

        repository.continueTask("task-1")

        assertEquals(BoardTaskState.IN_PROGRESS, dao.tasks["task-1"]?.state)
        assertNull("stale artifact must be cleared on new round", dao.tasks["task-1"]?.artifactJson)
    }

    @Test
    fun recordUserReply_clears_artifact() = runTest {
        val (repository, dao) = repo(seedTask(BoardTaskState.WAITING_USER, artifact = """{"title":"旧材料"}"""))

        repository.recordUserReply("task-1", "换个角度")

        assertEquals(BoardTaskState.IN_PROGRESS, dao.tasks["task-1"]?.state)
        assertNull(dao.tasks["task-1"]?.artifactJson)
    }

    @Test
    fun markWaitingUser_preserves_existing_artifact() = runTest {
        // markWaitingUser is NOT a reset; an artifact already stored by finishWithArtifact must survive.
        val (repository, dao) = repo(seedTask(BoardTaskState.IN_PROGRESS, artifact = """{"title":"材料"}"""))

        repository.markWaitingUser("task-1", "等待确认")

        assertEquals(BoardTaskState.WAITING_USER, dao.tasks["task-1"]?.state)
        assertEquals("""{"title":"材料"}""", dao.tasks["task-1"]?.artifactJson)
    }
}

private class FakeBoardTaskDao : BoardTaskDao {
    val tasks = linkedMapOf<String, BoardTaskEntity>()

    override suspend fun upsert(task: BoardTaskEntity) {
        tasks[task.id] = task
    }

    override suspend fun getById(id: String): BoardTaskEntity? = tasks[id]

    override suspend fun getBySource(sourceType: String, sourceRef: String): BoardTaskEntity? =
        tasks.values.firstOrNull { it.sourceType == sourceType && it.sourceRef == sourceRef }

    override fun observeTaskFlow(boardDate: String): Flow<List<BoardTaskEntity>> = flowOf(tasks.values.toList())

    override suspend fun updateState(id: String, state: String, chipText: String, updatedAt: Long) {
        tasks[id]?.let { tasks[id] = it.copy(state = state, chipText = chipText, updatedAt = updatedAt) }
    }

    override suspend fun updateStateWithArtifact(
        id: String,
        state: String,
        chipText: String,
        artifactJson: String?,
        updatedAt: Long,
    ) {
        tasks[id]?.let {
            tasks[id] = it.copy(state = state, chipText = chipText, artifactJson = artifactJson, updatedAt = updatedAt)
        }
    }

    override suspend fun resetForNewRound(id: String, state: String, chipText: String, updatedAt: Long) {
        tasks[id]?.let {
            tasks[id] = it.copy(state = state, chipText = chipText, artifactJson = null, updatedAt = updatedAt)
        }
    }

    override suspend fun getActiveNotificationTasks(): List<BoardTaskEntity> = tasks.values.toList()

    override suspend fun deleteOldTerminalTasks(cutoffMs: Long): Int = 0
}

private class FakeBoardTaskEventDao : BoardTaskEventDao {
    private val events = mutableListOf<BoardTaskEventEntity>()

    override suspend fun insert(event: BoardTaskEventEntity) {
        events.add(event)
    }

    override suspend fun getRecentEvents(taskId: String, limit: Int): List<BoardTaskEventEntity> =
        events.filter { it.taskId == taskId }.takeLast(limit).asReversed()

    override suspend fun getReviewEvents(startMs: Long, endMs: Long): List<BoardTaskEventReview> = emptyList()

    override suspend fun deleteOldTerminalEvents(cutoffMs: Long): Int = 0
}
