package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores a daily review entry — a Markdown diary-style summary of what the user
 * did today: app usage, completed board items, chat activity, and work outcomes.
 *
 * Two phases per day:
 * - `noon`  (13:00): first half-day summary
 * - `evening` (19:00): appends to the noon content with afternoon updates
 *
 * [content] is always the **cumulative** Markdown for the day. The evening phase
 * doesn't replace the noon content — it appends a new section below it. This means
 * the UI can always render [content] directly without merging.
 */
@Entity(
    tableName = "daily_review",
    indices = [
        Index("board_date", unique = true),
    ],
)
data class DailyReviewEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("board_date")
    val boardDate: String,
    @ColumnInfo("content")
    val content: String,
    @ColumnInfo("phase")
    val phase: String,  // "noon" or "evening"
    @ColumnInfo("generated_at")
    val generatedAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
