package app.amber.agent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * P8-08 — 首页「继续」候选的暂时隐藏记录（dismissUntil）。
 *
 * 用户隐藏某候选后，在 [dismissUntilMs] 之前该候选不再出现在聚合列表；
 * 到期后自动恢复。行按 (sourceKind, sourceId) 唯一，隐藏同一项目会刷新到期时间。
 */
@Entity(
    tableName = "continue_candidate_dismiss",
    primaryKeys = ["source_kind", "source_id"],
)
data class ContinueCandidateDismissEntity(
    @ColumnInfo(name = "source_kind") val sourceKind: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "dismiss_until_ms") val dismissUntilMs: Long,
)
