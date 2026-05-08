package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "feishu_doc_dependency",
    indices = [
        Index("upstream_url"),
        Index("downstream_url"),
        Index("enabled"),
    ],
)
data class FeishuDocDependencyEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("upstream_url")
    val upstreamUrl: String,
    @ColumnInfo("downstream_url")
    val downstreamUrl: String,
    @ColumnInfo("upstream_label")
    val upstreamLabel: String = "",
    @ColumnInfo("downstream_label")
    val downstreamLabel: String = "",
    @ColumnInfo("relation_note")
    val relationNote: String = "",
    @ColumnInfo("enabled")
    val enabled: Boolean = true,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
