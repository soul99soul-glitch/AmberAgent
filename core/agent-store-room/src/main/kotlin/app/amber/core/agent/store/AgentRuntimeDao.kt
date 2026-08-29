package app.amber.core.agent.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class AgentRuntimeDao {

    // IGNORE makes appendRun create-only: a stale writer re-asserting a run
    // record can never overwrite a newer or terminal state — status changes
    // must go through transitionStatus (CAS) below. Returns the rowId, or -1
    // when a row for the same runId already existed (conflict).
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertRun(run: AgentRunEntity): Long

    @Update
    abstract suspend fun updateRun(run: AgentRunEntity)

    // IGNORE + the unique (run_id, seq) index makes appendEvent idempotent:
    // a retried insert for an already-persisted (runId, seq) is a no-op.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertEvent(event: AgentEventEntity)

    @Insert
    abstract suspend fun insertSpan(span: TraceSpanEntity)

    @Insert
    abstract suspend fun insertPermissionIntent(p: PermissionIntentEntity)

    @Query("SELECT * FROM agent_run WHERE run_id = :id")
    abstract fun observeRun(id: String): Flow<AgentRunEntity?>

    @Query("SELECT * FROM agent_run WHERE run_id = :id")
    abstract suspend fun getRun(id: String): AgentRunEntity?

    @Query("SELECT * FROM agent_event WHERE run_id = :id ORDER BY seq ASC")
    abstract fun observeEvents(id: String): Flow<List<AgentEventEntity>>

    @Query("SELECT * FROM agent_event WHERE run_id = :id ORDER BY seq ASC")
    abstract suspend fun listEvents(id: String): List<AgentEventEntity>

    @Query("DELETE FROM agent_event WHERE run_id = :runId AND type = :type")
    abstract suspend fun deleteEventsByType(runId: String, type: String)

    // Retention sweep (Step 5): RequestSnapshot rows are ~8KB audit payloads,
    // one per wire call — they age out on the same 7-day cold-start sweep as
    // terminal ledger effects instead of accumulating forever.
    @Query("DELETE FROM agent_event WHERE type = :type AND ts < :cutoffMs")
    abstract suspend fun deleteEventsOfTypeOlderThan(type: String, cutoffMs: Long): Int

    @Query(
        "SELECT * FROM agent_run WHERE status IN (" +
            "'created', 'running', 'waiting_user', 'waiting_external', " +
            "'resumable', 'outcome_unknown', 'awaiting_permission'" +
            ")",
    )
    abstract suspend fun listUnfinished(): List<AgentRunEntity>

    @Query("SELECT * FROM agent_run WHERE message_node_id = :id ORDER BY started_at ASC")
    abstract suspend fun listRunsForMessageNode(id: String): List<AgentRunEntity>

    @Query("DELETE FROM trace_span WHERE run_id IN (SELECT run_id FROM agent_run WHERE status = 'completed' AND finished_at < :cutoff)")
    abstract suspend fun pruneOldSpans(cutoff: Long)

    /**
     * Compare-and-set status write: lands only when the persisted status is
     * still [expectedStatus]. Returns the number of rows updated — 0 means
     * the CAS guard lost the race and nothing changed.
     */
    @Query(
        "UPDATE agent_run SET status = :to, " +
            "interrupted_reason = COALESCE(:reason, interrupted_reason), " +
            "finished_at = CASE WHEN :setFinished = 1 THEN :now ELSE finished_at END " +
            "WHERE run_id = :runId AND status = :expectedStatus",
    )
    abstract suspend fun transitionStatus(
        runId: String,
        expectedStatus: String,
        to: String,
        reason: String?,
        setFinished: Boolean,
        now: Long,
    ): Int

    /**
     * Recovery-only interrupt. Guarded to live states so a run that already
     * reached a terminal state is never re-opened by a late recovery pass.
     */
    @Query(
        "UPDATE agent_run SET status = 'interrupted', interrupted_reason = :reason, finished_at = :now " +
            "WHERE run_id = :runId AND status IN (" +
            "'created', 'running', 'waiting_user', 'waiting_external', " +
            "'resumable', 'outcome_unknown', 'awaiting_permission'" +
            ")",
    )
    abstract suspend fun markInterrupted(runId: String, reason: String, now: Long): Int

    @Query("SELECT COALESCE(MAX(seq), 0) FROM agent_event WHERE run_id = :runId")
    abstract suspend fun maxEventSeq(runId: String): Long

    /**
     * Append an event with a database-allocated per-run sequence number.
     * Runs inside one transaction so concurrent writers cannot allocate the
     * same seq (which the idempotent unique index would then silently drop).
     */
    @Transaction
    open suspend fun appendEventAllocatingSeq(event: AgentEventEntity): Long {
        val next = maxEventSeq(event.runId) + 1
        insertEvent(event.copy(seq = next))
        return next
    }
}
