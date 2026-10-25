package app.amber.agent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton first-upgrade baseline for the activity center.
 *
 * Terminals at or before [establishedAtMs] are historical and must not flood
 * as unread. Live waits still surface; only FAILED/COMPLETED honor the cutoff.
 */
@Entity(tableName = "conversation_activity_baseline")
data class ConversationActivityBaselineEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: Int,
    @ColumnInfo(name = "established_at_ms") val establishedAtMs: Long,
)
