package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "feishu_doc_snapshot",
    indices = [
        Index("watched_doc_id"),
        Index("captured_at"),
    ],
)
data class FeishuDocSnapshotEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("watched_doc_id")
    val watchedDocId: String,
    @ColumnInfo("plain_text")
    val plainText: String,
    @ColumnInfo("content_hash")
    val contentHash: String,
    @ColumnInfo("heading_list_json")
    val headingListJson: String = "[]",
    @ColumnInfo("captured_at")
    val capturedAt: Long,
)
