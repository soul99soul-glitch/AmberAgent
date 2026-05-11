package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Raw signal captured by any collector (notification / calendar / feishu / chat / time).
 *
 * This is the pre-aggregation store. The SignalAggregator reads from here, runs dedup and
 * weight-based filtering, and the BoardAgent then reasons over the filtered batch.
 *
 * [sourceType] is a free-form string (notification / calendar / feishu_msg / feishu_doc /
 * chat / time) rather than an enum so new collectors can add entries without touching
 * migrations. The value is defined as SignalSourceType constants in code.
 *
 * [sourceRef] is the external identifier of the originating object (status bar key,
 * calendar event id, feishu message id, etc.). Combined with [sourceType] it forms the
 * natural key used by the deduplicator.
 *
 * [contentHash] is a hash of the meaningful content of the signal. Two signals with the
 * same content hash from the same source should be treated as duplicates even if they
 * arrive at different times with different [sourceRef] values (e.g. a re-posted
 * notification after the app cleared the previous one).
 *
 * [processed] flips to true once a BoardAgent run has considered this signal. Processed
 * signals are retained for a limited window for context and then pruned by the scheduler
 * to keep the table bounded.
 */
@Entity(
    tableName = "board_signal",
    indices = [
        Index("source_type"),
        Index("processed"),
        Index("created_at"),
        Index(value = ["source_type", "source_ref"], unique = false),
        Index(value = ["content_hash", "source_type"]),
    ],
)
data class BoardSignalEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("source_type")
    val sourceType: String,
    @ColumnInfo("source_ref")
    val sourceRef: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("content")
    val content: String,
    @ColumnInfo("content_hash")
    val contentHash: String,
    @ColumnInfo("signal_time")
    val signalTime: Long,
    @ColumnInfo("metadata_json")
    val metadataJson: String = "{}",
    @ColumnInfo("processed")
    val processed: Boolean = false,
    @ColumnInfo("processed_at")
    val processedAt: Long? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
)
