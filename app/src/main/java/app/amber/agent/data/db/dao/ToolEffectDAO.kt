package app.amber.agent.data.db.dao

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.amber.agent.data.db.entity.ToolEffectEntity

@Dao
interface ToolEffectDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(effect: ToolEffectEntity)

    @Query("SELECT * FROM tool_effect WHERE effect_id = :effectId")
    suspend fun getByEffectId(effectId: String): ToolEffectEntity?

    @Query("SELECT * FROM tool_effect WHERE tool_call_id = :toolCallId ORDER BY created_at_ms ASC")
    suspend fun getByToolCallId(toolCallId: String): List<ToolEffectEntity>

    @Query("SELECT * FROM tool_effect WHERE run_id = :runId ORDER BY created_at_ms ASC")
    suspend fun listByRun(runId: String): List<ToolEffectEntity>

    @Query("SELECT * FROM tool_effect WHERE status = 'OUTCOME_UNKNOWN'")
    suspend fun listOutcomeUnknown(): List<ToolEffectEntity>

    @Query("SELECT * FROM tool_effect WHERE run_id IN (SELECT run_id FROM run_terminal WHERE conversation_id = :conversationId) ORDER BY created_at_ms ASC")
    suspend fun listByConversation(conversationId: String): List<ToolEffectEntity>

    /** Completed effects already tied to a live conversation, without loading message JSON. */
    @Query(
        """
        SELECT effect.*, run_terminal.conversation_id AS conversation_id
        FROM tool_effect AS effect
        JOIN run_terminal ON run_terminal.run_id = effect.run_id
        JOIN conversationentity ON conversationentity.id = run_terminal.conversation_id
        WHERE effect.tool_name = :toolName
          AND effect.status = 'FINISHED'
          AND effect.finished_at_ms >= :sinceMs
        ORDER BY effect.finished_at_ms DESC
        LIMIT :limit
        """
    )
    suspend fun listRecentFinishedWithConversation(
        toolName: String,
        sinceMs: Long,
        limit: Int,
    ): List<ToolEffectConversationRow>

    @Query(
        "DELETE FROM tool_effect WHERE status IN (:statuses) AND updated_at_ms < :cutoffMs " +
            "AND NOT EXISTS (SELECT 1 FROM run_terminal " +
            "WHERE run_terminal.run_id = tool_effect.run_id " +
            "AND run_terminal.state IN " + RUN_TERMINAL_LIVE_STATES + ")"
    )
    suspend fun deleteTerminalOlderThan(statuses: List<String>, cutoffMs: Long): Int
}

data class ToolEffectConversationRow(
    @Embedded val effect: ToolEffectEntity,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
)
