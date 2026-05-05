package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation_compact",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversation_id")]
)
data class ConversationCompactEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("summary")
    val summary: String,
    @ColumnInfo("level")
    val level: Int,
    @ColumnInfo("source_start_index")
    val sourceStartIndex: Int,
    @ColumnInfo("source_end_index")
    val sourceEndIndex: Int,
    @ColumnInfo("source_message_ids_json")
    val sourceMessageIdsJson: String,
    @ColumnInfo("token_estimate")
    val tokenEstimate: Int,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("status")
    val status: String,
)
