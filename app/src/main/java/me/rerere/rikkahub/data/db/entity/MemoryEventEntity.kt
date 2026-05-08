package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_event",
    indices = [Index("conversation_id"), Index("memory_id"), Index("candidate_id")]
)
data class MemoryEventEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("event_type")
    val eventType: String,
    @ColumnInfo("conversation_id")
    val conversationId: String?,
    @ColumnInfo("memory_id")
    val memoryId: Int?,
    @ColumnInfo("candidate_id")
    val candidateId: String?,
    @ColumnInfo("model_id")
    val modelId: String?,
    @ColumnInfo("message")
    val message: String,
    @ColumnInfo("duration_ms")
    val durationMs: Long?,
    @ColumnInfo("message_count")
    val messageCount: Int?,
    @ColumnInfo("created_at")
    val createdAt: Long,
)
