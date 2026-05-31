package app.amber.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import app.amber.agent.data.db.entity.OpportunityEntity
import app.amber.agent.data.db.entity.ReferenceAnchorEntity

@Dao
interface OpportunityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OpportunityEntity)

    @Query("SELECT * FROM opportunity WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): OpportunityEntity?

    @Query("SELECT * FROM opportunity WHERE dedupe_key = :dedupeKey LIMIT 1")
    suspend fun getByDedupeKey(dedupeKey: String): OpportunityEntity?

    @Query(
        """
        SELECT * FROM opportunity
        WHERE status = 'suggested'
            AND (:now < expires_at OR expires_at IS NULL)
        ORDER BY confidence DESC, COALESCE(trigger_at, updated_at) ASC, updated_at DESC
        """
    )
    fun observeSuggested(now: Long): Flow<List<OpportunityEntity>>

    @Query(
        """
        SELECT * FROM opportunity
        WHERE status = 'suggested'
            AND (:now < expires_at OR expires_at IS NULL)
        ORDER BY confidence DESC, COALESCE(trigger_at, updated_at) ASC, updated_at DESC
        LIMIT :limit
        """
    )
    suspend fun getSuggested(now: Long, limit: Int): List<OpportunityEntity>

    @Query(
        """
        UPDATE opportunity
        SET status = 'dispatched',
            dispatched_task_id = :taskId,
            updated_at = :now
        WHERE id = :id AND status = 'suggested'
        """
    )
    suspend fun markDispatched(id: String, taskId: String, now: Long): Int

    @Query(
        """
        UPDATE opportunity
        SET status = 'dismissed',
            dismissed_reason = :reason,
            updated_at = :now
        WHERE id = :id
        """
    )
    suspend fun dismiss(id: String, reason: String?, now: Long)

    @Query(
        """
        UPDATE opportunity
        SET status = 'muted',
            mute_scope = :scope,
            updated_at = :now
        WHERE id = :id
        """
    )
    suspend fun mute(id: String, scope: String, now: Long)

    @Query(
        """
        UPDATE opportunity
        SET status = 'muted',
            mute_scope = 'type',
            updated_at = :now
        WHERE opportunity_type = :type AND status = 'suggested'
        """
    )
    suspend fun muteType(type: String, now: Long): Int

    @Query("SELECT COUNT(*) FROM opportunity WHERE opportunity_type = :type AND status = 'muted' AND mute_scope = 'type'")
    suspend fun hasMutedType(type: String): Int

    @Query("UPDATE opportunity SET status = 'expired', updated_at = :now WHERE status = 'suggested' AND expires_at IS NOT NULL AND expires_at <= :now")
    suspend fun expire(now: Long): Int

    @Query("DELETE FROM opportunity WHERE status IN ('dispatched', 'dismissed', 'expired', 'muted') AND updated_at < :cutoffMs")
    suspend fun deleteOldTerminal(cutoffMs: Long): Int
}

@Dao
interface ReferenceAnchorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReferenceAnchorEntity)

    @Query("SELECT * FROM reference_anchor WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ReferenceAnchorEntity?

    @Query("SELECT * FROM reference_anchor WHERE dependency_id = :dependencyId ORDER BY match_confidence DESC, updated_at DESC")
    suspend fun getByDependency(dependencyId: String): List<ReferenceAnchorEntity>

    @Query(
        """
        SELECT * FROM reference_anchor
        WHERE upstream_doc_ref = :upstreamDocRef
            AND status IN ('confirmed', 'drifted', 'acked')
        ORDER BY match_confidence DESC, updated_at DESC
        LIMIT :limit
        """
    )
    suspend fun getActiveByUpstream(upstreamDocRef: String, limit: Int): List<ReferenceAnchorEntity>

    @Query(
        """
        UPDATE reference_anchor
        SET status = 'drifted',
            last_value = :lastValue,
            evidence_json = :evidenceJson,
            score_json = :scoreJson,
            last_checked_at = :now,
            updated_at = :now
        WHERE id = :id
        """
    )
    suspend fun markDrifted(id: String, lastValue: String, evidenceJson: String, scoreJson: String, now: Long)

    @Query(
        """
        UPDATE reference_anchor
        SET status = 'acked',
            baseline_value = COALESCE(last_value, baseline_value),
            last_checked_at = :now,
            updated_at = :now
        WHERE id = :id
        """
    )
    suspend fun ack(id: String, now: Long)
}
