package app.amber.core.agent.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentRuntimeDao {
    @Insert
    suspend fun insertRun(run: AgentRunEntity)

    @Update
    suspend fun updateRun(run: AgentRunEntity)

    @Insert
    suspend fun insertEvent(event: AgentEventEntity)

    @Insert
    suspend fun insertSpan(span: TraceSpanEntity)

    @Insert
    suspend fun insertPermissionIntent(p: PermissionIntentEntity)

    @Query("SELECT * FROM agent_run WHERE run_id = :id")
    fun observeRun(id: String): Flow<AgentRunEntity?>

    @Query("SELECT * FROM agent_run WHERE run_id = :id")
    suspend fun getRun(id: String): AgentRunEntity?

    @Query("SELECT * FROM agent_event WHERE run_id = :id ORDER BY seq ASC")
    fun observeEvents(id: String): Flow<List<AgentEventEntity>>

    @Query("SELECT * FROM agent_run WHERE status IN ('running', 'awaiting_permission')")
    suspend fun listUnfinished(): List<AgentRunEntity>

    @Query("SELECT * FROM agent_run WHERE message_node_id = :id ORDER BY started_at ASC")
    suspend fun listRunsForMessageNode(id: String): List<AgentRunEntity>

    @Query("DELETE FROM trace_span WHERE run_id IN (SELECT run_id FROM agent_run WHERE status = 'completed' AND finished_at < :cutoff)")
    suspend fun pruneOldSpans(cutoff: Long)

    @Query("UPDATE agent_run SET status = 'interrupted', interrupted_reason = :reason, finished_at = :now WHERE run_id = :runId")
    suspend fun markInterrupted(runId: String, reason: String, now: Long)
}
