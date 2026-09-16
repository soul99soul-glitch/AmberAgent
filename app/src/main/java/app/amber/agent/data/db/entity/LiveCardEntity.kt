package app.amber.agent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户显式保存的 Live 伴随卡片（蓝图 §7.3 P1-3"保存卡片"）。
 *
 * 只存卡片内容与来源元数据，不存原始屏幕文本（contentText/uiTree 不落库）。
 * 独立保留策略：用户手动保存的数据不做 TTL 自动清理，只能由用户删除；
 * 与 agent_event 里 30 天 TTL 的 run 终态记录互不为副本（那边是执行日志，
 * 这里是用户资产，全文权威来源以本表为准）。
 */
@Entity(
    tableName = "live_card",
    indices = [Index("created_at")],
)
data class LiveCardEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "app_label") val appLabel: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "action_label") val actionLabel: String,
    @ColumnInfo(name = "watching") val watching: String,
    @ColumnInfo(name = "key_points_json") val keyPointsJson: String,
    @ColumnInfo(name = "suggestions_json") val suggestionsJson: String,
    @ColumnInfo(name = "screen_signature") val screenSignature: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
