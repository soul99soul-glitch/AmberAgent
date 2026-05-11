package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single item rendered on the Today Board — produced by BoardAgent from one or more
 * raw signals.
 *
 * [category] controls which section of Tab A (分区式) the item lands in:
 *  - action     → 要做的事 (TODO)
 *  - attention  → 值得关注
 *  - info       → 今日动态
 *
 * [urgency] drives the pin-top / timeline ordering in Tab B (紧急度).
 *
 * [status] transitions: active → completed | dismissed. A nightly cleanup (at 04:00 local)
 * archives completed/dismissed items so the next day's board is fresh.
 *
 * [boardDate] is the yyyy-MM-dd bucket this item belongs to — not necessarily the creation
 * date of the underlying signal, since a signal received at 23:50 may belong to today's or
 * tomorrow's board depending on when the agent ran.
 *
 * [sourceType] / [sourceRef] point back to the originating BoardSignalEntity so the "聊一
 * 下" flow can hydrate the full original content into the new chat's system prompt without
 * having to replay the aggregation.
 *
 * Feedback is recorded out-of-band in BoardWeightEntity; this table only tracks item
 * lifecycle, not learning weights.
 */
@Entity(
    tableName = "board_item",
    indices = [
        Index("board_date"),
        Index("status"),
        Index("urgency"),
        Index("category"),
        Index("created_at"),
    ],
)
data class BoardItemEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("source_type")
    val sourceType: String,
    @ColumnInfo("source_ref")
    val sourceRef: String,
    @ColumnInfo("source_content")
    val sourceContent: String,
    @ColumnInfo("urgency")
    val urgency: String,
    @ColumnInfo("category")
    val category: String,
    @ColumnInfo("reason")
    val reason: String,
    @ColumnInfo("suggestion")
    val suggestion: String,
    @ColumnInfo("signal_time")
    val signalTime: Long,
    @ColumnInfo("status")
    val status: String = "active",
    @ColumnInfo("completed_at")
    val completedAt: Long? = null,
    @ColumnInfo("dismissed_at")
    val dismissedAt: Long? = null,
    @ColumnInfo("board_date")
    val boardDate: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
)
