package app.amber.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import app.amber.agent.data.db.entity.MemoryEntity

@Dao
interface MemoryDAO {
    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId")
    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE archived = 0 AND (:now IS NULL OR expires_at IS NULL OR expires_at > :now)")
    suspend fun getActiveMemories(now: Long? = null): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE scope IN (:scopes) AND archived = 0 AND (:now IS NULL OR expires_at IS NULL OR expires_at > :now)")
    suspend fun getActiveMemoriesByScopes(scopes: List<String>, now: Long? = null): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT assistant_id AS assistantId, COUNT(*) AS count FROM memoryentity GROUP BY assistant_id")
    fun getMemoryCountsFlow(): Flow<List<MemoryCount>>

    @Query("SELECT * FROM memoryentity")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE id = :id")
    suspend fun getMemoryById(id: Int): MemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    /**
     * Blind content write used by legacy/manual callers. The SQL-side
     * revision increment is intentional: a model approval bound to the old
     * revision must become stale even when the caller did not provide CAS
     * metadata.
     */
    @Query(
        "UPDATE memoryentity SET content = :content, revision = revision + 1, " +
            "updated_at = :updatedAt WHERE id = :id"
    )
    suspend fun updateContentBlind(
        id: Int,
        content: String,
        updatedAt: Long,
    ): Int

    /**
     * Full-record compare-and-set used by Dream/import paths. Every mutable
     * field is replaced only when the row still has the revision read by the
     * caller; conflicts affect zero rows and must be surfaced to the caller.
     */
    @Query(
        "UPDATE memoryentity SET assistant_id = :assistantId, content = :content, " +
            "scope = :scope, kind = :kind, source_conversation_id = :sourceConversationId, " +
            "source_message_ids_json = :sourceMessageIdsJson, supersedes_ids_json = :supersedesIdsJson, " +
            "expires_at = :expiresAt, confidence = :confidence, pinned = :pinned, " +
            "archived = :archived, created_at = :createdAt, updated_at = :updatedAt, " +
            "last_used_at = :lastUsedAt, revision = revision + 1, source_run_id = :sourceRunId, " +
            "source_trigger = :sourceTrigger WHERE id = :id AND revision = :expectedRevision"
    )
    suspend fun updateRecordCas(
        id: Int,
        assistantId: String,
        content: String,
        scope: String,
        kind: String,
        sourceConversationId: String?,
        sourceMessageIdsJson: String,
        supersedesIdsJson: String,
        expiresAt: Long?,
        confidence: Float,
        pinned: Boolean,
        archived: Boolean,
        createdAt: Long,
        updatedAt: Long,
        lastUsedAt: Long?,
        sourceRunId: String?,
        sourceTrigger: String?,
        expectedRevision: Long,
    ): Int

    // ---- P2-06 compare-and-set writes ----
    // The WHERE revision = :expectedRevision guard makes the write atomic:
    // it affects 0 rows when another writer bumped the revision in between,
    // and the caller must then reject the stale approval instead of
    // overwriting. Both return the number of affected rows.

    @Query(
        "UPDATE memoryentity SET content = :content, revision = revision + 1, " +
            "updated_at = :updatedAt, " +
            "source_run_id = CASE WHEN :sourceRunId IS NULL THEN source_run_id ELSE :sourceRunId END, " +
            "source_trigger = CASE WHEN :sourceTrigger IS NULL THEN source_trigger ELSE :sourceTrigger END " +
            "WHERE id = :id AND revision = :expectedRevision"
    )
    suspend fun updateContentCas(
        id: Int,
        content: String,
        expectedRevision: Long,
        updatedAt: Long,
        sourceRunId: String?,
        sourceTrigger: String?,
    ): Int

    @Query("DELETE FROM memoryentity WHERE id = :id AND revision = :expectedRevision")
    suspend fun deleteCas(id: Int, expectedRevision: Long): Int

    @Query("SELECT revision FROM memoryentity WHERE id = :id")
    suspend fun revisionOf(id: Int): Long?

    @Query("UPDATE memoryentity SET last_used_at = :usedAt WHERE id IN (:ids)")
    suspend fun touchMemories(ids: List<Int>, usedAt: Long)

    @Query("DELETE FROM memoryentity WHERE id = :id")
    suspend fun deleteMemory(id: Int)

    @Query("DELETE FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun deleteMemoriesOfAssistant(assistantId: String)
}

data class MemoryCount(
    val assistantId: String,
    val count: Int,
)
