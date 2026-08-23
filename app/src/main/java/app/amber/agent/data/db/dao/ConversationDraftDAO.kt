package app.amber.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.amber.agent.data.db.entity.ConversationDraftEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDraftDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: ConversationDraftEntity)

    @Query("SELECT * FROM conversation_draft WHERE conversation_id = :conversationId")
    suspend fun get(conversationId: String): ConversationDraftEntity?

    @Query("DELETE FROM conversation_draft WHERE conversation_id = :conversationId")
    suspend fun delete(conversationId: String): Int

    /** P8-08：聚合仍存在所属会话的草稿（会话已删除的草稿不再出现）。 */
    @Query(
        "SELECT d.* FROM conversation_draft d " +
            "INNER JOIN conversationentity c ON c.id = d.conversation_id " +
            "ORDER BY d.updated_at_ms DESC"
    )
    fun observeDraftsWithExistingConversation(): Flow<List<ConversationDraftEntity>>
}
