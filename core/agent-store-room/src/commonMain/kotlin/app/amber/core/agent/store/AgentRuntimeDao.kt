package app.amber.core.agent.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentRuntimeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
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

    /**
     * Highest `seq` already written for this run, or 0 when the run has no
     * events yet. W1's ledger (iOS `IOSAgentRunLedger`) uses this to seed its
     * in-memory seq counter on first write per run, so a fresh ledger instance
     * (e.g. after an app relaunch) continues the sequence instead of colliding
     * with the `(run_id, seq)` unique index.
     */
    @Query("SELECT COALESCE(MAX(seq), 0) FROM agent_event WHERE run_id = :id")
    suspend fun maxEventSeq(id: String): Long

    /**
     * Suspend counterpart to [observeEvents] for one-shot reads (crash-recovery
     * sweeps, tests) that don't want a Flow subscription.
     */
    @Query("SELECT * FROM agent_event WHERE run_id = :id ORDER BY seq ASC")
    suspend fun listEventsForRun(id: String): List<AgentEventEntity>

    @Query("SELECT * FROM agent_run WHERE status IN ('running', 'awaiting_permission', 'recovery_pending')")
    suspend fun listUnfinished(): List<AgentRunEntity>

    @Query("SELECT * FROM agent_run WHERE status = 'awaiting_permission'")
    suspend fun listAwaitingPermission(): List<AgentRunEntity>

    @Query(
        """
        UPDATE agent_run
        SET status = 'awaiting_permission', input_snapshot_ref = :inputSnapshotRef,
            finished_at = NULL, interrupted_reason = NULL
        WHERE run_id = :runId
        """,
    )
    suspend fun markAwaitingPermission(runId: String, inputSnapshotRef: String): Int

    @Query("SELECT * FROM agent_run WHERE message_node_id = :id ORDER BY started_at ASC")
    suspend fun listRunsForMessageNode(id: String): List<AgentRunEntity>

    /**
     * All runs ordered by started_at ascending. Used by iOS AccountView to
     * render a real usage heatmap (grouped by day from startedAt). Kept simple
     * (no day-bucketing SQL) so Swift can bucket with its own calendar logic
     * and timezone. [Slice 5]
     */
    @Query("SELECT * FROM agent_run ORDER BY started_at ASC")
    suspend fun listAllRuns(): List<AgentRunEntity>

    @Query("DELETE FROM trace_span WHERE run_id IN (SELECT run_id FROM agent_run WHERE status = 'completed' AND finished_at < :cutoff)")
    suspend fun pruneOldSpans(cutoff: Long)

    @Query("UPDATE agent_run SET status = 'interrupted', interrupted_reason = :reason, finished_at = :now WHERE run_id = :runId")
    suspend fun markInterrupted(runId: String, reason: String, now: Long)
}
