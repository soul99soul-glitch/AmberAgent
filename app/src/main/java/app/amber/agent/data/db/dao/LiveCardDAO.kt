package app.amber.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.amber.agent.data.db.entity.LiveCardEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LiveCardDAO {

    @Insert
    suspend fun insert(card: LiveCardEntity): Long

    @Query("SELECT * FROM live_card ORDER BY created_at DESC LIMIT :limit")
    fun latestFlow(limit: Int): Flow<List<LiveCardEntity>>

    /** 同屏幕签名+同结论的卡片已存在则不再重复保存（防历史区刷屏）。 */
    @Query("SELECT COUNT(*) FROM live_card WHERE screen_signature = :signature AND watching = :watching")
    suspend fun countSame(signature: String, watching: String): Int

    @Query("DELETE FROM live_card WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT COUNT(*) FROM live_card")
    suspend fun count(): Int
}
