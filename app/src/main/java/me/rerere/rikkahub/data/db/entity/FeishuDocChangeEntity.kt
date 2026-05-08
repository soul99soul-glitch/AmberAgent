package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "feishu_doc_change",
    indices = [
        Index("watched_doc_id"),
        Index("status"),
        Index("created_at"),
    ],
)
data class FeishuDocChangeEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("watched_doc_id")
    val watchedDocId: String,
    @ColumnInfo("from_snapshot_id")
    val fromSnapshotId: String? = null,
    @ColumnInfo("to_snapshot_id")
    val toSnapshotId: String = "",
    @ColumnInfo("added_chars")
    val addedChars: Int = 0,
    @ColumnInfo("removed_chars")
    val removedChars: Int = 0,
    @ColumnInfo("effective_change")
    val effectiveChange: Int = 0,
    @ColumnInfo("changed_sections_json")
    val changedSectionsJson: String = "[]",
    @ColumnInfo("diff_summary")
    val diffSummary: String? = null,
    @ColumnInfo("ai_analysis_json")
    val aiAnalysisJson: String? = null,
    @ColumnInfo("status")
    val status: String = "new",
    @ColumnInfo("notified")
    val notified: Boolean = false,
    @ColumnInfo("created_at")
    val createdAt: Long,
)
