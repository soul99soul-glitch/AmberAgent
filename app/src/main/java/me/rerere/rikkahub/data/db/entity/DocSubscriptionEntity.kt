package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A document the user is subscribed to for change monitoring.
 *
 * Replaces the old FeishuWatchedDoc + FeishuDocSnapshot split: the baseline
 * (last known content hash, char count, heading list) is inlined here so we
 * never need to store full document text in the DB.
 *
 * [docToken] is parsed from [docUrl] at subscription time — it's the identifier
 * used in Feishu API calls (/open-apis/docx/v1/documents/{token}/raw_content).
 */
@Entity(
    tableName = "doc_subscription",
    indices = [
        Index("enabled"),
        Index("doc_url"),
    ],
)
data class DocSubscriptionEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("doc_url")
    val docUrl: String,
    @ColumnInfo("doc_token")
    val docToken: String,
    @ColumnInfo("doc_title")
    val docTitle: String,
    @ColumnInfo("enabled")
    val enabled: Boolean = true,
    @ColumnInfo("threshold")
    val threshold: Int = 500,
    @ColumnInfo("notify_enabled")
    val notifyEnabled: Boolean = true,
    // ---- Inline baseline (replaces separate snapshot table) ----
    @ColumnInfo("last_content_hash")
    val lastContentHash: String = "",
    @ColumnInfo("last_char_count")
    val lastCharCount: Int = 0,
    @ColumnInfo("last_heading_list_json")
    val lastHeadingListJson: String = "[]",
    // ---- Timestamps ----
    @ColumnInfo("last_checked_at")
    val lastCheckedAt: Long? = null,
    @ColumnInfo("last_changed_at")
    val lastChangedAt: Long? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
) {
    val hasBaseline: Boolean get() = lastContentHash.isNotBlank()
}
