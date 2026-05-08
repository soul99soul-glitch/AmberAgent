package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryDreamPlanEntity

@Dao
interface MemoryDreamPlanDAO {
    @Query("SELECT * FROM memory_dream_plan WHERE status = 'pending' ORDER BY created_at DESC LIMIT 1")
    fun getPendingPlanFlow(): Flow<MemoryDreamPlanEntity?>

    @Query("SELECT * FROM memory_dream_plan WHERE status = 'pending' ORDER BY created_at DESC LIMIT 1")
    suspend fun getPendingPlan(): MemoryDreamPlanEntity?

    @Query("SELECT COUNT(*) FROM memory_dream_plan WHERE source = :source AND created_at >= :createdAfter")
    suspend fun countPlansSince(source: String, createdAfter: Long): Int

    @Query("UPDATE memory_dream_plan SET status = :status, dismissed_at = :dismissedAt WHERE status = 'pending'")
    suspend fun updatePendingStatus(status: String, dismissedAt: Long)

    @Query("UPDATE memory_dream_plan SET status = 'applied', applied_at = :appliedAt WHERE id = :id")
    suspend fun markApplied(id: String, appliedAt: Long)

    @Query("UPDATE memory_dream_plan SET status = 'dismissed', dismissed_at = :dismissedAt WHERE id = :id")
    suspend fun markDismissed(id: String, dismissedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(plan: MemoryDreamPlanEntity)
}
