package app.amber.agent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "feishu_watched_doc",
    indices = [
        Index("enabled"),
        Index("last_checked_at"),
    ],
)
data class FeishuWatchedDocEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("doc_url")
    val docUrl: String,
    @ColumnInfo("doc_title")
    val docTitle: String,
    @ColumnInfo("enabled")
    val enabled: Boolean = true,
    @ColumnInfo("change_threshold")
    val changeThreshold: Int = 500,
    @ColumnInfo("check_interval_min")
    val checkIntervalMin: Int = 90,
    @ColumnInfo("last_checked_at")
    val lastCheckedAt: Long? = null,
    @ColumnInfo("last_changed_at")
    val lastChangedAt: Long? = null,
    @ColumnInfo("notify_enabled")
    val notifyEnabled: Boolean = true,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
