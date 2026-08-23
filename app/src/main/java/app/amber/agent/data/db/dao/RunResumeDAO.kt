package app.amber.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.amber.agent.data.db.entity.RunResumeEntity

@Dao
interface RunResumeDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: RunResumeEntity)

    @Query("SELECT * FROM run_resume WHERE run_id = :runId")
    suspend fun getByRunId(runId: String): RunResumeEntity?

    @Query("DELETE FROM run_resume WHERE run_id = :runId")
    suspend fun deleteByRunId(runId: String)
}
