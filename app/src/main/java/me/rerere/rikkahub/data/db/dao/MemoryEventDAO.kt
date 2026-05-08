package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryEventEntity

@Dao
interface MemoryEventDAO {
    @Query("SELECT * FROM memory_event ORDER BY created_at DESC LIMIT :limit")
    fun getRecentEventsFlow(limit: Int): Flow<List<MemoryEventEntity>>

    @Query("SELECT * FROM memory_event ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentEvents(limit: Int): List<MemoryEventEntity>

    @Query("SELECT * FROM memory_event WHERE conversation_id = :conversationId ORDER BY created_at DESC LIMIT :limit")
    suspend fun getEventsOfConversation(conversationId: String, limit: Int): List<MemoryEventEntity>

    @Query("SELECT COUNT(*) FROM memory_event WHERE event_type = :eventType AND created_at >= :createdAfter")
    suspend fun countEventsSince(eventType: String, createdAfter: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: MemoryEventEntity)
}
