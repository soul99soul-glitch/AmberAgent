package app.amber.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.amber.agent.data.db.entity.ProactiveCardEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProactiveCardDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ProactiveCardEntity): Long

    @Query(
        "SELECT * FROM proactive_cards " +
            "WHERE gateway_id = :gatewayId " +
            "ORDER BY received_at ASC, seq ASC"
    )
    fun observeForGateway(gatewayId: String): Flow<List<ProactiveCardEntity>>

    @Query(
        "SELECT * FROM proactive_cards " +
            "WHERE gateway_id = :gatewayId AND signal_id = :signalId " +
            "ORDER BY received_at DESC, seq DESC LIMIT 1"
    )
    suspend fun findBySignal(gatewayId: String, signalId: String): ProactiveCardEntity?

    @Query(
        "UPDATE proactive_cards SET notified_at = :notifiedAt " +
            "WHERE gateway_id = :gatewayId AND signal_id = :signalId AND notified_at IS NULL"
    )
    suspend fun markNotified(gatewayId: String, signalId: String, notifiedAt: Long): Int

    @Query(
        "UPDATE proactive_cards SET feedback_state = :feedbackState " +
            "WHERE gateway_id = :gatewayId AND signal_id = :signalId"
    )
    suspend fun setFeedback(gatewayId: String, signalId: String, feedbackState: String): Int
}
