package app.amber.agent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "message_node_stat",
    foreignKeys = [
        ForeignKey(
            entity = MessageNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["node_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversation_id")],
    primaryKeys = ["node_id"]
)
data class MessageNodeStatEntity(
    @ColumnInfo("node_id")
    val nodeId: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("total_messages")
    val totalMessages: Int,
    @ColumnInfo("prompt_tokens")
    val promptTokens: Long,
    @ColumnInfo("completion_tokens")
    val completionTokens: Long,
    @ColumnInfo("cached_tokens")
    val cachedTokens: Long,
)

@Entity(
    tableName = "message_day_stat",
    foreignKeys = [
        ForeignKey(
            entity = MessageNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["node_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("day")],
    primaryKeys = ["node_id", "day"]
)
data class MessageDayStatEntity(
    @ColumnInfo("node_id")
    val nodeId: String,
    @ColumnInfo("day")
    val day: String,
    @ColumnInfo("count")
    val count: Int,
)
