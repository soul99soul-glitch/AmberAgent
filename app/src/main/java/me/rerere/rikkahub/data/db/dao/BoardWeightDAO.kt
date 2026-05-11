package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.agent.board.TODAY_BOARD_HARD_MUTE_WEIGHT
import me.rerere.rikkahub.data.db.entity.BoardWeightEntity

@Dao
interface BoardWeightDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(weight: BoardWeightEntity)

    @Query(
        """
        SELECT * FROM board_weight
        WHERE source_type = :sourceType AND keyword = :keyword
        """
    )
    suspend fun get(sourceType: String, keyword: String): BoardWeightEntity?

    @Query("SELECT * FROM board_weight")
    suspend fun getAll(): List<BoardWeightEntity>

    @Query("SELECT * FROM board_weight WHERE source_type = :sourceType")
    suspend fun getForSource(sourceType: String): List<BoardWeightEntity>

    /**
     * Rows with weight at or below [hardMuteThreshold] are treated as hard mutes. Keeping
     * the query centralized here so SignalScorer / Settings UI agree on the cutoff.
     */
    @Query("SELECT * FROM board_weight WHERE weight <= :hardMuteThreshold")
    suspend fun getHardMutes(hardMuteThreshold: Int = TODAY_BOARD_HARD_MUTE_WEIGHT): List<BoardWeightEntity>

    @Query("DELETE FROM board_weight WHERE source_type = :sourceType AND keyword = :keyword")
    suspend fun delete(sourceType: String, keyword: String)

    @Query("DELETE FROM board_weight")
    suspend fun clear()
}
