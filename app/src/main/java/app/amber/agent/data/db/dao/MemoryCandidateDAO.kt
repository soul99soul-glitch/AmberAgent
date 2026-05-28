package app.amber.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import app.amber.agent.data.db.entity.MemoryCandidateEntity

@Dao
interface MemoryCandidateDAO {
    @Query("SELECT * FROM memory_candidate ORDER BY created_at DESC")
    fun getCandidatesFlow(): Flow<List<MemoryCandidateEntity>>

    @Query("SELECT * FROM memory_candidate WHERE status = :status ORDER BY created_at DESC")
    fun getCandidatesByStatusFlow(status: String): Flow<List<MemoryCandidateEntity>>

    @Query("SELECT * FROM memory_candidate WHERE status = :status ORDER BY created_at DESC")
    suspend fun getCandidatesByStatus(status: String): List<MemoryCandidateEntity>

    @Query("SELECT * FROM memory_candidate ORDER BY created_at DESC")
    suspend fun getAllCandidates(): List<MemoryCandidateEntity>

    @Query("SELECT * FROM memory_candidate WHERE id = :id")
    suspend fun getCandidateById(id: String): MemoryCandidateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(candidate: MemoryCandidateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(candidates: List<MemoryCandidateEntity>)

    @Update
    suspend fun update(candidate: MemoryCandidateEntity)
}
