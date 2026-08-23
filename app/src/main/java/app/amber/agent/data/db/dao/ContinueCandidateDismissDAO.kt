package app.amber.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.amber.agent.data.db.entity.ContinueCandidateDismissEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContinueCandidateDismissDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ContinueCandidateDismissEntity)

    @Query("SELECT * FROM continue_candidate_dismiss")
    fun observeAll(): Flow<List<ContinueCandidateDismissEntity>>

    @Query("DELETE FROM continue_candidate_dismiss WHERE dismiss_until_ms <= :nowMs")
    suspend fun deleteExpired(nowMs: Long): Int

    @Query("DELETE FROM continue_candidate_dismiss")
    suspend fun clearAll()
}
