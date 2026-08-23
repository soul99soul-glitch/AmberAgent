package app.amber.core.agent.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentRuntimeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRunIfAbsent(run: AgentRunEntity): Long

    /**
     * Appends an event only when its run exists. The sequence and run identity
     * are derived by this single SQLite statement, so concurrent callers cannot
     * race a separate MAX(seq) read against the insert.
     */
    @Query(
        """
        INSERT OR IGNORE INTO agent_event (
            event_id, run_id, parent_run_id, seq, type, payload_type, payload,
            payload_schema_version, agent_descriptor_id, agent_version, is_final, ts
        )
        SELECT
            :eventId, run.run_id, run.parent_run_id,
            COALESCE((
                SELECT MAX(existing.seq) + 1
                FROM agent_event AS existing
                WHERE existing.run_id = run.run_id
            ), 1),
            :type, :payloadType, :payload, :payloadSchemaVersion,
            run.agent_descriptor_id, run.agent_version, :isFinal, :ts
        FROM agent_run AS run
        WHERE run.run_id = :runId
        """,
    )
    suspend fun insertRunEvent(
        runId: String,
        eventId: String,
        type: String,
        payloadType: String,
        payload: String,
        payloadSchemaVersion: Int,
        isFinal: Boolean,
        ts: Long,
    ): Long

    @Insert
    suspend fun insertSpan(span: TraceSpanEntity)

    @Insert
    suspend fun insertPermissionIntent(p: PermissionIntentEntity)

    @Query("SELECT * FROM agent_run WHERE run_id = :id")
    fun observeRun(id: String): Flow<AgentRunEntity?>

    @Query("SELECT * FROM agent_run WHERE run_id = :id")
    suspend fun getRun(id: String): AgentRunEntity?

    @Query(
        """
        UPDATE agent_run
        SET status = :status,
            input_snapshot_ref = :inputSnapshotRef,
            interrupted_reason = :detail,
            finished_at = :finishedAt
        WHERE run_id = :runId AND status = :expectedStatus
        """,
    )
    suspend fun transitionRun(
        runId: String,
        expectedStatus: String,
        status: String,
        inputSnapshotRef: String?,
        detail: String?,
        finishedAt: Long?,
    ): Int

    @Query("SELECT * FROM agent_event WHERE run_id = :id ORDER BY seq ASC")
    fun observeEvents(id: String): Flow<List<AgentEventEntity>>

    /**
     * Suspend counterpart to [observeEvents] for one-shot reads (crash-recovery
     * sweeps, tests) that don't want a Flow subscription.
     */
    @Query("SELECT * FROM agent_event WHERE run_id = :id ORDER BY seq ASC")
    suspend fun listEventsForRun(id: String): List<AgentEventEntity>

    @Query(
        """
        SELECT * FROM agent_run
        WHERE agent_descriptor_id IN (:descriptorIds)
          AND status IN ('running', 'awaiting_permission', 'recovery_pending')
        ORDER BY started_at ASC, run_id ASC
        """,
    )
    suspend fun listRecoverable(descriptorIds: List<String>): List<AgentRunEntity>

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

}
