package app.amber.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.amber.agent.data.db.entity.RunTerminalEntity

@Dao
interface RunTerminalDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(run: RunTerminalEntity)

    @Query("SELECT * FROM run_terminal WHERE run_id = :runId")
    suspend fun getByRunId(runId: String): RunTerminalEntity?

    @Query(
        "SELECT * FROM run_terminal WHERE conversation_id = :conversationId " +
            "AND state IN ('RUNNING', 'WAITING_USER', 'WAITING_EXTERNAL', 'RESUMABLE', 'OUTCOME_UNKNOWN') " +
            "ORDER BY started_at_ms DESC LIMIT 1"
    )
    suspend fun activeByConversation(conversationId: String): RunTerminalEntity?

    @Query(
        "SELECT * FROM run_terminal WHERE state IN ('RUNNING', 'WAITING_USER', 'WAITING_EXTERNAL', 'RESUMABLE', 'OUTCOME_UNKNOWN')"
    )
    suspend fun listUnfinished(): List<RunTerminalEntity>

    /**
     * Atomically stop a persisted approval pause. The in-memory ownership
     * entry is intentionally released when the generation flow pauses, so a
     * notification Stop needs this durable ownership path as a fallback.
     */
    @Query(
        "UPDATE run_terminal SET state = 'CANCELLED', pause_reason = 'USER_STOP', " +
            "updated_at_ms = :nowMs, finished_at_ms = :nowMs " +
            "WHERE run_id = :runId AND conversation_id = :conversationId " +
            "AND state = 'WAITING_USER' AND finished_at_ms IS NULL"
    )
    suspend fun cancelWaitingUser(
        runId: String,
        conversationId: String,
        nowMs: Long,
    ): Int
}
