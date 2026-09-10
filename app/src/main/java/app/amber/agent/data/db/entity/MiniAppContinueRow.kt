package app.amber.agent.data.db.entity

import androidx.room.ColumnInfo

/** Small, indexed projection used by Home; it never loads the app HTML. */
data class MiniAppContinueRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "lastRunAt") val lastRunAt: Long?,
    @ColumnInfo(name = "latest_version_created_at") val latestVersionCreatedAt: Long,
)
