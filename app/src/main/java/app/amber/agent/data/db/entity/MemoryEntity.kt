package app.amber.agent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class MemoryEntity(
    @PrimaryKey(true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    @ColumnInfo("scope")
    val scope: String = "long_term",
    @ColumnInfo("kind")
    val kind: String = "note",
    @ColumnInfo("source_conversation_id")
    val sourceConversationId: String? = null,
    @ColumnInfo("source_message_ids_json")
    val sourceMessageIdsJson: String = "[]",
    @ColumnInfo(name = "supersedes_ids_json", defaultValue = "'[]'")
    val supersedesIdsJson: String = "[]",
    @ColumnInfo("expires_at")
    val expiresAt: Long? = null,
    @ColumnInfo("confidence")
    val confidence: Float = 1f,
    @ColumnInfo("pinned")
    val pinned: Boolean = false,
    @ColumnInfo("archived")
    val archived: Boolean = false,
    @ColumnInfo("created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo("updated_at")
    val updatedAt: Long = createdAt,
    @ColumnInfo("last_used_at")
    val lastUsedAt: Long? = null,
)
