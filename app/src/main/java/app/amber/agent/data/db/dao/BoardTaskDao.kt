package app.amber.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import app.amber.agent.data.db.entity.BoardTaskEntity
import app.amber.agent.data.db.entity.BoardTaskEventEntity
import app.amber.agent.data.db.entity.BoardTaskEventReview

@Dao
interface BoardTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: BoardTaskEntity)

    @Query("SELECT * FROM board_task WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): BoardTaskEntity?

    @Query("SELECT * FROM board_task WHERE source_type = :sourceType AND source_ref = :sourceRef LIMIT 1")
    suspend fun getBySource(sourceType: String, sourceRef: String): BoardTaskEntity?

    @Query(
        """
        SELECT * FROM board_task
        WHERE display_board_date = :boardDate
            OR state IN ('in_progress', 'waiting_user', 'blocked')
        ORDER BY
            CASE state
                WHEN 'in_progress' THEN 0
                WHEN 'waiting_user' THEN 1
                WHEN 'blocked' THEN 2
                WHEN 'done' THEN 3
                WHEN 'dismissed' THEN 4
                ELSE 6
            END,
            updated_at DESC
        """
    )
    fun observeTaskFlow(boardDate: String): Flow<List<BoardTaskEntity>>

    @Query("UPDATE board_task SET state = :state, chip_text = :chipText, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateState(id: String, state: String, chipText: String, updatedAt: Long)

    /**
     * Atomic finish: set the task to its terminal-of-round state AND store the artifact in one
     * write, so the card never observes "waiting_user with no material" or vice versa.
     */
    @Query(
        "UPDATE board_task SET state = :state, chip_text = :chipText, artifact_json = :artifactJson, " +
            "updated_at = :updatedAt WHERE id = :id"
    )
    suspend fun updateStateWithArtifact(
        id: String,
        state: String,
        chipText: String,
        artifactJson: String?,
        updatedAt: Long,
    )

    /**
     * Reset to in_progress for a new run round, clearing any prior artifact so a stale finished
     * result is never shown while the next round is running.
     */
    @Query(
        "UPDATE board_task SET state = :state, chip_text = :chipText, artifact_json = NULL, " +
            "updated_at = :updatedAt WHERE id = :id"
    )
    suspend fun resetForNewRound(id: String, state: String, chipText: String, updatedAt: Long)

    @Query(
        """
        SELECT * FROM board_task
        WHERE state IN ('waiting_user', 'in_progress')
        ORDER BY
            CASE state
                WHEN 'waiting_user' THEN 0
                WHEN 'in_progress' THEN 1
                ELSE 2
            END,
            updated_at DESC
        """
    )
    suspend fun getActiveNotificationTasks(): List<BoardTaskEntity>

    @Query("DELETE FROM board_task WHERE state IN ('done', 'dismissed') AND updated_at < :cutoffMs")
    suspend fun deleteOldTerminalTasks(cutoffMs: Long): Int
}

@Dao
interface BoardTaskEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: BoardTaskEventEntity)

    @Query(
        """
        SELECT * FROM board_task_event
        WHERE task_id = :taskId
        ORDER BY ts DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentEvents(taskId: String, limit: Int): List<BoardTaskEventEntity>

    @Query(
        """
        SELECT
            event.task_id AS task_id,
            COALESCE(task.title, '') AS task_title,
            COALESCE(task.state, '') AS task_state,
            event.type AS type,
            event.message AS message,
            event.ts AS ts
        FROM board_task_event AS event
        LEFT JOIN board_task AS task ON task.id = event.task_id
        WHERE event.ts >= :startMs AND event.ts < :endMs
        ORDER BY event.ts ASC
        """
    )
    suspend fun getReviewEvents(startMs: Long, endMs: Long): List<BoardTaskEventReview>

    @Query(
        """
        DELETE FROM board_task_event
        WHERE ts < :cutoffMs
            AND task_id NOT IN (
                SELECT id FROM board_task
                WHERE state IN ('in_progress', 'waiting_user', 'blocked')
            )
        """
    )
    suspend fun deleteOldTerminalEvents(cutoffMs: Long): Int
}
