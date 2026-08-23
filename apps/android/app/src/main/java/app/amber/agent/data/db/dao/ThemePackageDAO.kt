package app.amber.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.amber.agent.data.db.entity.ThemePackageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ThemePackageDAO {
    @Query("SELECT * FROM theme_package ORDER BY imported_at_ms DESC")
    fun observeAll(): Flow<List<ThemePackageEntity>>

    @Query("SELECT * FROM theme_package WHERE id = :id")
    suspend fun getById(id: String): ThemePackageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ThemePackageEntity)

    @Query("DELETE FROM theme_package WHERE id = :id")
    suspend fun delete(id: String): Int
}
