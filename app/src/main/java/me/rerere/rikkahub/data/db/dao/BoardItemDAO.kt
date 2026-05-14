package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.BoardItemEntity

@Dao
interface BoardItemDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<BoardItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: BoardItemEntity)

    @Query("SELECT * FROM board_item WHERE id = :id")
    suspend fun getById(id: String): BoardItemEntity?

    @Query(
        """
        SELECT * FROM board_item
        WHERE board_date = :boardDate
        ORDER BY
            CASE urgency WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END,
            signal_time DESC
        """
    )
    fun flowByDate(boardDate: String): Flow<List<BoardItemEntity>>

    @Query(
        """
        SELECT * FROM board_item
        WHERE board_date = :boardDate
        ORDER BY
            CASE urgency WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END,
            signal_time DESC
        """
    )
    suspend fun getByDate(boardDate: String): List<BoardItemEntity>

    @Query("SELECT * FROM board_item WHERE board_date = :boardDate AND status = 'active'")
    suspend fun getActiveByDate(boardDate: String): List<BoardItemEntity>

    @Query("SELECT * FROM board_item WHERE board_date = :boardDate AND status = 'completed'")
    suspend fun getCompletedByDate(boardDate: String): List<BoardItemEntity>

    @Query("UPDATE board_item SET status = 'completed', completed_at = :completedAt WHERE id = :id")
    suspend fun markCompleted(id: String, completedAt: Long)

    @Query("UPDATE board_item SET status = 'dismissed', dismissed_at = :dismissedAt WHERE id = :id")
    suspend fun markDismissed(id: String, dismissedAt: Long)

    /**
     * Archive stale items from previous days. Runs at the 04:00 cutoff so the board always
     * shows only today's surface.
     */
    @Query("DELETE FROM board_item WHERE board_date < :keepFromDate")
    suspend fun deleteBefore(keepFromDate: String): Int
}
