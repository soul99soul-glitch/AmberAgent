package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memory_candidate")
data class MemoryCandidateEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("content")
    val content: String,
    @ColumnInfo("scope")
    val scope: String,
    @ColumnInfo("kind")
    val kind: String,
    @ColumnInfo("source_conversation_id")
    val sourceConversationId: String?,
    @ColumnInfo("source_message_ids_json")
    val sourceMessageIdsJson: String,
    @ColumnInfo("expires_at")
    val expiresAt: Long?,
    @ColumnInfo("confidence")
    val confidence: Float,
    @ColumnInfo("reason")
    val reason: String,
    @ColumnInfo("sensitive")
    val sensitive: Boolean,
    @ColumnInfo("status")
    val status: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
