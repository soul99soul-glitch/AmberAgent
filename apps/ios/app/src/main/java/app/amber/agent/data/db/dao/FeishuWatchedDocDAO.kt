package app.amber.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import app.amber.agent.data.db.entity.FeishuWatchedDocEntity

@Dao
interface FeishuWatchedDocDAO {
    @Query("SELECT * FROM feishu_watched_doc WHERE enabled = 1 ORDER BY updated_at DESC")
    fun getEnabledFlow(): Flow<List<FeishuWatchedDocEntity>>

    @Query("SELECT * FROM feishu_watched_doc WHERE enabled = 1 ORDER BY updated_at DESC")
    suspend fun getEnabled(): List<FeishuWatchedDocEntity>

    @Query("SELECT * FROM feishu_watched_doc ORDER BY updated_at DESC")
    suspend fun getAll(): List<FeishuWatchedDocEntity>

    @Query("SELECT * FROM feishu_watched_doc WHERE id = :id")
    suspend fun getById(id: String): FeishuWatchedDocEntity?

    @Query("UPDATE feishu_watched_doc SET last_checked_at = :checkedAt, updated_at = :checkedAt WHERE id = :id")
    suspend fun updateLastChecked(id: String, checkedAt: Long)

    @Query("UPDATE feishu_watched_doc SET last_changed_at = :changedAt, updated_at = :changedAt WHERE id = :id")
    suspend fun updateLastChanged(id: String, changedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(doc: FeishuWatchedDocEntity)

    @Query("DELETE FROM feishu_watched_doc WHERE id = :id")
    suspend fun deleteById(id: String)
}
