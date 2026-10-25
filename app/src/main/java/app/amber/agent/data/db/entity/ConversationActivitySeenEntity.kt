package app.amber.agent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-run read watermark for the cross-conversation activity center.
 *
 * Version is the terminal [updated_at_ms] or [finished_at_ms] the user actually
 * opened. A newer version of the same run, or a different runId, can alert again.
 */
@Entity(
    tableName = "conversation_activity_seen",
    indices = [Index("conversation_id")],
)
data class ConversationActivitySeenEntity(
    @PrimaryKey
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "version_ms") val versionMs: Long,
)
