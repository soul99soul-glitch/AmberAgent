package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.DocChangeLogEntity

@Dao
interface DocChangeLogDAO {
    @Query("SELECT * FROM doc_change_log WHERE subscription_id = :subId ORDER BY detected_at DESC LIMIT :limit")
    suspend fun getRecent(subId: String, limit: Int = 20): List<DocChangeLogEntity>

    @Query("SELECT * FROM doc_change_log WHERE status = 'new' ORDER BY detected_at DESC")
    fun getNewFlow(): Flow<List<DocChangeLogEntity>>

    @Query("SELECT * FROM doc_change_log WHERE status = 'new' ORDER BY detected_at DESC")
    suspend fun getNew(): List<DocChangeLogEntity>

    @Query("UPDATE doc_change_log SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE doc_change_log SET ai_analysis_json = :json WHERE id = :id")
    suspend fun updateAnalysis(id: String, json: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DocChangeLogEntity)

    @Query("DELETE FROM doc_change_log WHERE detected_at < :olderThanMs")
    suspend fun pruneOlderThan(olderThanMs: Long)
}
