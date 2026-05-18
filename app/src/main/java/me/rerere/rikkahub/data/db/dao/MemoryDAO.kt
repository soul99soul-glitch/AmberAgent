package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryEntity

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
