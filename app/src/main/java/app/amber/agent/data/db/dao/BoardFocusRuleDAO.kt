package app.amber.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import app.amber.agent.data.db.entity.BoardFocusRuleEntity

@Dao
interface BoardFocusRuleDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: BoardFocusRuleEntity)

    @Query("SELECT * FROM board_focus_rule ORDER BY sort_order ASC, created_at ASC")
    fun flowAll(): Flow<List<BoardFocusRuleEntity>>

    @Query("SELECT * FROM board_focus_rule WHERE active = 1 ORDER BY sort_order ASC, created_at ASC")
    suspend fun getActive(): List<BoardFocusRuleEntity>

    @Query("DELETE FROM board_focus_rule WHERE id = :id")
    suspend fun deleteById(id: String)
}
