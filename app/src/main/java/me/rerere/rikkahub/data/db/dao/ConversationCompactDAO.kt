package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ConversationCompactEntity

@Dao
interface ConversationCompactDAO {
    @Query("SELECT * FROM conversation_compact WHERE conversation_id = :conversationId ORDER BY source_end_index ASC, created_at ASC")
    fun getCompactsOfConversation(conversationId: String): Flow<List<ConversationCompactEntity>>

    @Query("SELECT * FROM conversation_compact WHERE conversation_id = :conversationId ORDER BY source_end_index ASC, created_at ASC")
    suspend fun getCompactsOfConversationOnce(conversationId: String): List<ConversationCompactEntity>

    @Query("SELECT * FROM conversation_compact WHERE id = :id LIMIT 1")
    suspend fun getCompactById(id: String): ConversationCompactEntity?

    @Query("SELECT * FROM conversation_compact WHERE conversation_id = :conversationId AND summary LIKE '%' || :query || '%' ORDER BY updated_at DESC LIMIT :limit")
    suspend fun search(conversationId: String, query: String, limit: Int): List<ConversationCompactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ConversationCompactEntity)

    @Query("DELETE FROM conversation_compact WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}
