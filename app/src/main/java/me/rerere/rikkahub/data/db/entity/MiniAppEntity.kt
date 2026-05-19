package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "mini_app",
    indices = [
        Index(value = ["pinned"]),
        Index(value = ["updatedAt"]),
        Index(value = ["sourceConversationId"]),
    ]
)
data class MiniAppEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val htmlContent: String,
    val sourceConversationId: String? = null,
    val sourceMessageId: String? = null,
    val iconEmoji: String? = null,
    val category: String? = null,
    val permissionsJson: String = "[]",
    val pinned: Boolean = false,
    val runCount: Int = 0,
    val boardSummary: String? = null,
    val version: Int = 1,
    val htmlHash: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "mini_app_grant",
    primaryKeys = ["appId", "permission"],
    indices = [
        Index(value = ["appId"]),
    ],
)
data class MiniAppGrantEntity(
    val appId: String,
    val permission: String,
    val decision: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "mini_app_version",
    primaryKeys = ["appId", "versionNumber"],
    indices = [
        Index(value = ["appId"]),
        Index(value = ["createdAt"]),
    ],
)
data class MiniAppVersionEntity(
    val appId: String,
    val versionNumber: Int,
    val htmlContent: String,
    val htmlHash: String,
    val changeNote: String? = null,
    val createdAt: Long,
)
