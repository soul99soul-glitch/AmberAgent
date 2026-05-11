package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Level-1 feedback learning: a simple weight table keyed by (sourceType, keyword).
 *
 * SignalAggregator.SignalScorer reads this table to bias the coarse filter before handing
 * signals to the BoardAgent. Positive weight means "surface more of this", negative means
 * "surface less". A weight of −10 or below is treated as an effective hard mute until the
 * user explicitly resets it from settings.
 *
 * [keyword] uses empty string [WHOLE_SOURCE] as the sentinel for "applies to the entire
 * source type" (e.g. mute all notifications from com.example.spam). When non-empty, it
 * applies to signals whose title or content contains the keyword (case-insensitive
 * substring match).
 *
 * Weights accumulate across user actions:
 *  - complete  → +1
 *  - chat      → +2
 *  - dismiss   → −1
 *  - 3 dismisses of same (source, keyword) within 7 days → −10 (auto-mute)
 */
@Entity(
    tableName = "board_weight",
    primaryKeys = ["source_type", "keyword"],
    indices = [
        Index("source_type"),
        Index("weight"),
    ],
)
data class BoardWeightEntity(
    @ColumnInfo("source_type")
    val sourceType: String,
    @ColumnInfo("keyword")
    val keyword: String,
    @ColumnInfo("weight")
    val weight: Int,
    @ColumnInfo("dismiss_count_7d")
    val dismissCount7d: Int = 0,
    @ColumnInfo("last_action_at")
    val lastActionAt: Long,
) {
    companion object {
        /** Sentinel value for keyword when the weight applies to the entire source type. */
        const val WHOLE_SOURCE = ""
    }
}
