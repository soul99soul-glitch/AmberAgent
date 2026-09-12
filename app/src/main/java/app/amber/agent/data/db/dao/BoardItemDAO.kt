package app.amber.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import app.amber.agent.data.db.entity.BoardItemEntity

@Dao
interface BoardItemDAO {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(item: BoardItemEntity): Long

    @Query(
        """
        UPDATE board_item
        SET title = :title,
            source_type = :sourceType,
            source_ref = :sourceRef,
            source_content = :sourceContent,
            urgency = :urgency,
            category = :category,
            reason = :reason,
            suggestion = :suggestion,
            signal_time = :signalTime,
            board_date = :boardDate
        WHERE id = :id
        """
    )
    suspend fun updateGeneratedFields(
        id: String,
        title: String,
        sourceType: String,
        sourceRef: String,
        sourceContent: String,
        urgency: String,
        category: String,
        reason: String,
        suggestion: String,
        signalTime: Long,
        boardDate: String,
    )

    /** Upsert generated fields without resetting the user's lifecycle decision. */
    @Transaction
    suspend fun insertAll(items: List<BoardItemEntity>) {
        for (item in items) insert(item)
    }

    @Transaction
    suspend fun insert(item: BoardItemEntity) {
        insertIfAbsent(item)
        updateGeneratedFields(
            id = item.id,
            title = item.title,
            sourceType = item.sourceType,
            sourceRef = item.sourceRef,
            sourceContent = item.sourceContent,
            urgency = item.urgency,
            category = item.category,
            reason = item.reason,
            suggestion = item.suggestion,
            signalTime = item.signalTime,
            boardDate = item.boardDate,
        )
    }

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

    @Query(
        """
        UPDATE board_item
        SET status = 'completed', completed_at = :completedAt
        WHERE source_type = :sourceType
            AND source_ref = :sourceRef
            AND board_date = :boardDate
            AND status = 'active'
        """
    )
    suspend fun markCompletedBySource(sourceType: String, sourceRef: String, boardDate: String, completedAt: Long)

    @Query("UPDATE board_item SET status = 'dismissed', dismissed_at = :dismissedAt WHERE id = :id")
    suspend fun markDismissed(id: String, dismissedAt: Long)

    @Query(
        """
        UPDATE board_item
        SET status = 'dismissed', dismissed_at = :dismissedAt
        WHERE source_type = :sourceType
            AND source_ref = :sourceRef
            AND board_date = :boardDate
            AND status = 'active'
        """
    )
    suspend fun markDismissedBySource(sourceType: String, sourceRef: String, boardDate: String, dismissedAt: Long)

    /**
     * Archive stale items from previous days. Runs at the 04:00 cutoff so the board always
     * shows only today's surface.
     */
    @Query("DELETE FROM board_item WHERE board_date < :keepFromDate")
    suspend fun deleteBefore(keepFromDate: String): Int
}
