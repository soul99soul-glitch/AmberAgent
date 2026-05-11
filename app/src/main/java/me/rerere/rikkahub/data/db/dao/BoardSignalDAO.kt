package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.BoardSignalEntity

@Dao
interface BoardSignalDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(signal: BoardSignalEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(signal: BoardSignalEntity): Long

    @Query("SELECT * FROM board_signal WHERE id = :id")
    suspend fun getById(id: String): BoardSignalEntity?

    @Query(
        """
        SELECT * FROM board_signal
        WHERE source_type = :sourceType AND source_ref = :sourceRef
        LIMIT 1
        """
    )
    suspend fun findBySourceRef(sourceType: String, sourceRef: String): BoardSignalEntity?

    @Query(
        """
        SELECT * FROM board_signal
        WHERE content_hash = :contentHash AND source_type = :sourceType
          AND created_at >= :sinceMs
        LIMIT 1
        """
    )
    suspend fun findDuplicateByHash(
        contentHash: String,
        sourceType: String,
        sinceMs: Long,
    ): BoardSignalEntity?

    @Query(
        """
        SELECT * FROM board_signal
        WHERE processed = 0
        ORDER BY signal_time ASC
        LIMIT :limit
        """
    )
    suspend fun getUnprocessed(limit: Int = 200): List<BoardSignalEntity>

    @Query("SELECT COUNT(*) FROM board_signal WHERE processed = 0")
    suspend fun countUnprocessed(): Int

    @Query(
        """
        UPDATE board_signal SET processed = 1, processed_at = :processedAt
        WHERE id IN (:ids)
        """
    )
    suspend fun markProcessed(ids: List<String>, processedAt: Long)

    /**
     * Prune processed signals older than the cutoff. Unprocessed signals are never pruned
     * — if the scheduler has not caught up, we should surface them later, not drop them.
     */
    @Query("DELETE FROM board_signal WHERE processed = 1 AND created_at < :olderThanMs")
    suspend fun pruneProcessedBefore(olderThanMs: Long): Int

    @Query("SELECT * FROM board_signal WHERE created_at >= :sinceMs ORDER BY signal_time DESC")
    suspend fun getSince(sinceMs: Long): List<BoardSignalEntity>
}
