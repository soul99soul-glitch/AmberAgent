package app.amber.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import app.amber.agent.data.db.entity.DailyReviewEntity

@Dao
interface DailyReviewDAO {
    @Query("SELECT * FROM daily_review WHERE board_date = :boardDate LIMIT 1")
    fun observeByDate(boardDate: String): Flow<DailyReviewEntity?>

    @Query("SELECT * FROM daily_review WHERE board_date = :boardDate LIMIT 1")
    suspend fun getByDate(boardDate: String): DailyReviewEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DailyReviewEntity)

    @Query("DELETE FROM daily_review WHERE board_date < :cutoffDate")
    suspend fun deleteOlderThan(cutoffDate: String)
}
