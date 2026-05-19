package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "hot_list_cache",
    indices = [
        Index("fetched_at"),
        Index("provider_id"),
    ],
)
data class HotListCacheEntity(
    @PrimaryKey
    @ColumnInfo("provider_id")
    val providerId: String,
    @ColumnInfo("provider_name")
    val providerName: String,
    @ColumnInfo("items_json")
    val itemsJson: String,
    @ColumnInfo("fetched_at")
    val fetchedAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("last_error")
    val lastError: String? = null,
)

@Entity(
    tableName = "hot_topic_cache",
    indices = [
        Index("source_count"),
        Index("best_rank"),
        Index("latest_fetched_at"),
    ],
)
data class HotTopicCacheEntity(
    @PrimaryKey
    @ColumnInfo("topic_id")
    val topicId: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("sources_json")
    val sourcesJson: String,
    @ColumnInfo("source_count")
    val sourceCount: Int,
    @ColumnInfo("best_rank")
    val bestRank: Int,
    @ColumnInfo("latest_fetched_at")
    val latestFetchedAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "deep_read_cache",
    indices = [
        Index("topic_id"),
        Index("expires_at"),
    ],
)
data class DeepReadCacheEntity(
    @PrimaryKey
    @ColumnInfo("topic_id")
    val topicId: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("output_json")
    val outputJson: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("expires_at")
    val expiresAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "hot_list_source",
    indices = [
        Index("enabled"),
        Index("sort_order"),
    ],
)
data class HotListSourceEntity(
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,
    @ColumnInfo("display_name")
    val displayName: String,
    @ColumnInfo("source_type")
    val sourceType: String,
    @ColumnInfo("url")
    val url: String,
    @ColumnInfo("enabled")
    val enabled: Boolean = false,
    @ColumnInfo("field_mapping_json")
    val fieldMappingJson: String = "{}",
    @ColumnInfo("sort_order")
    val sortOrder: Int = 0,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("last_success_at")
    val lastSuccessAt: Long? = null,
)
