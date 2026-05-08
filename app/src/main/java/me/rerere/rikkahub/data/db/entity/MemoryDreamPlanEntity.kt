package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_dream_plan",
    indices = [
        Index("status"),
        Index("source"),
        Index("created_at"),
    ],
)
data class MemoryDreamPlanEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("plan_json")
    val planJson: String,
    @ColumnInfo("status")
    val status: String,
    @ColumnInfo("source")
    val source: String,
    @ColumnInfo("merge_count")
    val mergeCount: Int,
    @ColumnInfo("promote_count")
    val promoteCount: Int,
    @ColumnInfo("archive_count")
    val archiveCount: Int,
    @ColumnInfo("ignore_candidate_count")
    val ignoreCandidateCount: Int,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("applied_at")
    val appliedAt: Long?,
    @ColumnInfo("dismissed_at")
    val dismissedAt: Long?,
)
