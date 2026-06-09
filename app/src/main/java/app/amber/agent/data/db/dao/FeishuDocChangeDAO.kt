package app.amber.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import app.amber.agent.data.db.entity.FeishuDocChangeEntity

@Dao
interface FeishuDocChangeDAO {
    @Query("SELECT * FROM feishu_doc_change WHERE watched_doc_id = :watchedDocId ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecent(watchedDocId: String, limit: Int = 20): List<FeishuDocChangeEntity>

    @Query("SELECT * FROM feishu_doc_change WHERE status = 'new' ORDER BY created_at DESC")
    fun getNewFlow(): Flow<List<FeishuDocChangeEntity>>

    @Query("SELECT * FROM feishu_doc_change WHERE status = 'new' ORDER BY created_at DESC")
    suspend fun getNew(): List<FeishuDocChangeEntity>

    @Query(
        """
        SELECT * FROM feishu_doc_change 
        WHERE watched_doc_id = :watchedDocId AND from_snapshot_id = :fromSnapshotId AND to_snapshot_id = :toSnapshotId 
        LIMIT 1
        """
    )
    suspend fun findBySnapshots(
        watchedDocId: String,
        fromSnapshotId: String?,
        toSnapshotId: String,
    ): FeishuDocChangeEntity?

    @Query("UPDATE feishu_doc_change SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE feishu_doc_change SET ai_analysis_json = :analysisJson WHERE id = :id")
    suspend fun updateAnalysis(id: String, analysisJson: String)

    @Query("UPDATE feishu_doc_change SET notified = 1 WHERE id = :id")
    suspend fun markNotified(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(change: FeishuDocChangeEntity)
}
