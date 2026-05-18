package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.MessageDayStatEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeStatEntity

@Dao
interface MessageStatsDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodeStats(stats: List<MessageNodeStatEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDayStats(stats: List<MessageDayStatEntity>)

    @Query(
        "SELECT COALESCE(SUM(total_messages), 0) AS totalMessages, " +
            "COALESCE(SUM(prompt_tokens), 0) AS promptTokens, " +
            "COALESCE(SUM(completion_tokens), 0) AS completionTokens, " +
            "COALESCE(SUM(cached_tokens), 0) AS cachedTokens " +
            "FROM message_node_stat"
    )
    suspend fun getTokenStats(): MessageTokenStats

    @Query(
        "SELECT day, COALESCE(SUM(count), 0) AS count " +
            "FROM message_day_stat " +
            "WHERE day >= :startDate " +
            "GROUP BY day"
    )
    suspend fun getMessageCountPerDay(startDate: String): List<MessageDayCount>
}
