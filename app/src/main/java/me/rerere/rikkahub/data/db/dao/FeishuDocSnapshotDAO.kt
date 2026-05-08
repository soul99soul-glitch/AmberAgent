package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.FeishuDocSnapshotEntity

@Dao
interface FeishuDocSnapshotDAO {
    @Query("SELECT * FROM feishu_doc_snapshot WHERE watched_doc_id = :watchedDocId ORDER BY captured_at DESC LIMIT 1")
    suspend fun getLatest(watchedDocId: String): FeishuDocSnapshotEntity?

    @Query("SELECT * FROM feishu_doc_snapshot WHERE watched_doc_id = :watchedDocId ORDER BY captured_at DESC LIMIT :limit")
    suspend fun getRecent(watchedDocId: String, limit: Int = 10): List<FeishuDocSnapshotEntity>

    @Query("SELECT COUNT(*) FROM feishu_doc_snapshot WHERE watched_doc_id = :watchedDocId")
    suspend fun count(watchedDocId: String): Int

    @Query(
        """
        DELETE FROM feishu_doc_snapshot WHERE id IN (
            SELECT id FROM feishu_doc_snapshot WHERE watched_doc_id = :watchedDocId
            ORDER BY captured_at DESC LIMIT -1 OFFSET :keepCount
        )
        """
    )
    suspend fun trimTo(watchedDocId: String, keepCount: Int = 10)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snapshot: FeishuDocSnapshotEntity)
}
