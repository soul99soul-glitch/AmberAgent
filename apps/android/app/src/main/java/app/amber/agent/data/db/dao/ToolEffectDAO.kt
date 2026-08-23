package app.amber.agent.data.db.dao

import androidx.room.Dao
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

    @Query("DELETE FROM tool_effect WHERE status IN (:statuses) AND updated_at_ms < :cutoffMs")
    suspend fun deleteTerminalOlderThan(statuses: List<String>, cutoffMs: Long): Int
}
