package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.DeepReadCacheEntity
import me.rerere.rikkahub.data.db.entity.HotListCacheEntity
import me.rerere.rikkahub.data.db.entity.HotListSourceEntity
import me.rerere.rikkahub.data.db.entity.HotTopicCacheEntity

@Dao
interface HotListDAO {
    @Query("SELECT * FROM hot_list_cache ORDER BY provider_id ASC")
    fun observeProviderCaches(): Flow<List<HotListCacheEntity>>

    @Query("SELECT * FROM hot_list_cache WHERE provider_id = :providerId")
    suspend fun getProviderCache(providerId: String): HotListCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProviderCache(entity: HotListCacheEntity)

    @Query(
        """
        SELECT * FROM hot_topic_cache
        ORDER BY source_count DESC, best_rank ASC, latest_fetched_at DESC
        LIMIT :limit
        """
    )
    fun observeHotTopics(limit: Int = 10): Flow<List<HotTopicCacheEntity>>

    @Query("SELECT * FROM hot_topic_cache WHERE topic_id = :topicId")
    suspend fun getHotTopic(topicId: String): HotTopicCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHotTopics(entities: List<HotTopicCacheEntity>)

    @Query("DELETE FROM hot_topic_cache")
    suspend fun clearHotTopics()

    @Transaction
    suspend fun replaceHotTopics(entities: List<HotTopicCacheEntity>) {
        clearHotTopics()
        if (entities.isNotEmpty()) upsertHotTopics(entities)
    }

    @Query("SELECT * FROM deep_read_cache WHERE topic_id = :topicId")
    suspend fun getDeepRead(topicId: String): DeepReadCacheEntity?

    @Query(
        """
        SELECT * FROM deep_read_cache
        WHERE title = :title AND expires_at >= :now
        ORDER BY updated_at DESC
        LIMIT 1
        """
    )
    suspend fun getFreshDeepReadByTitle(title: String, now: Long): DeepReadCacheEntity?

    @Query("SELECT * FROM deep_read_cache WHERE topic_id = :topicId")
    fun observeDeepRead(topicId: String): Flow<DeepReadCacheEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDeepRead(entity: DeepReadCacheEntity)

    @Query("DELETE FROM deep_read_cache WHERE topic_id = :topicId")
    suspend fun deleteDeepRead(topicId: String)

    @Query("DELETE FROM deep_read_cache WHERE expires_at < :now")
    suspend fun pruneExpiredDeepReads(now: Long): Int

    @Query("SELECT * FROM hot_list_source ORDER BY sort_order ASC, display_name ASC")
    fun observeSources(): Flow<List<HotListSourceEntity>>

    @Query("SELECT * FROM hot_list_source WHERE enabled = 1 ORDER BY sort_order ASC, display_name ASC")
    suspend fun getEnabledSources(): List<HotListSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSource(entity: HotListSourceEntity)

    @Query("DELETE FROM hot_list_source WHERE id = :id")
    suspend fun deleteSource(id: String)
}
