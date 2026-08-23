package app.amber.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.amber.agent.data.db.entity.ConversationContextEventEntity

@Dao
interface ConversationContextEventDAO {
    @Query("SELECT * FROM conversation_context_event WHERE conversation_id = :conversationId ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentEvents(conversationId: String, limit: Int): List<ConversationContextEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ConversationContextEventEntity)

    @Query("DELETE FROM conversation_context_event WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}
