package app.amber.agent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A detected document change — lightweight record without full text storage.
 *
 * Replaces FeishuDocChangeEntity. Key differences:
 * - No snapshot IDs (no separate snapshot table)
 * - No `notified` flag (notification is fire-and-forget)
 * - [status]: "new" → "read" → "consumed" (consumed by board signal bridge)
 */
@Entity(
    tableName = "doc_change_log",
    indices = [
        Index("subscription_id"),
        Index("status"),
        Index("detected_at"),
    ],
)
data class DocChangeLogEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("subscription_id")
    val subscriptionId: String,
    @ColumnInfo("added_chars")
    val addedChars: Int = 0,
    @ColumnInfo("removed_chars")
    val removedChars: Int = 0,
    @ColumnInfo("effective_change")
    val effectiveChange: Int = 0,
    @ColumnInfo("changed_sections_json")
    val changedSectionsJson: String = "[]",
    @ColumnInfo("summary")
    val summary: String = "",
    @ColumnInfo("status")
    val status: String = "new",
    @ColumnInfo("ai_analysis_json")
    val aiAnalysisJson: String? = null,
    @ColumnInfo("detected_at")
    val detectedAt: Long,
)
