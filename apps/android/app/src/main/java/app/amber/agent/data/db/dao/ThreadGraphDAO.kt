package app.amber.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.amber.agent.data.db.entity.ThreadMessageEntity
import app.amber.agent.data.db.entity.ThreadNodeEntity
import app.amber.agent.data.db.entity.ThreadResultEntity

/**
 * P4-02 persistent thread graph DAO. All writes are upserts (REPLACE) so
 * recovery can re-apply a node/result safely after a crash mid-transition.
 */
@Dao
interface ThreadGraphDAO {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNode(node: ThreadNodeEntity)

    @Query("SELECT * FROM thread_node WHERE thread_id = :threadId")
    suspend fun getNode(threadId: String): ThreadNodeEntity?

    @Query("SELECT * FROM thread_node WHERE root_run_id = :rootRunId ORDER BY started_at_ms ASC")
    suspend fun listNodesByRootRun(rootRunId: String): List<ThreadNodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: ThreadMessageEntity)

    @Query("SELECT * FROM thread_message WHERE message_id = :messageId")
    suspend fun getMessage(messageId: String): ThreadMessageEntity?

    @Query("SELECT * FROM thread_message WHERE thread_id = :threadId ORDER BY created_at_ms ASC")
    suspend fun listMessages(threadId: String): List<ThreadMessageEntity>

    @Query("SELECT * FROM thread_message WHERE thread_id = :threadId AND delivery_state = 'QUEUED' ORDER BY created_at_ms ASC")
    suspend fun listQueuedMessages(threadId: String): List<ThreadMessageEntity>

    @Query("UPDATE thread_message SET delivery_state = :state, updated_at_ms = :updatedAtMs WHERE message_id IN (:messageIds) AND delivery_state = 'QUEUED'")
    suspend fun markQueuedMessagesClaimed(
        messageIds: List<String>,
        state: String,
        updatedAtMs: Long,
    ): Int

    /** Query and claim in one Room transaction so concurrent drains do not duplicate messages. */
    @Transaction
    suspend fun claimQueuedMessages(
        threadId: String,
        state: String,
        updatedAtMs: Long,
    ): List<ThreadMessageEntity> {
        val queued = listQueuedMessages(threadId)
        if (queued.isEmpty()) return emptyList()
        markQueuedMessagesClaimed(
            messageIds = queued.map { it.messageId },
            state = state,
            updatedAtMs = updatedAtMs,
        )
        return queued.map { it.copy(deliveryState = state, updatedAtMs = updatedAtMs) }
    }

    @Query("UPDATE thread_message SET delivery_state = 'QUEUED', updated_at_ms = :updatedAtMs WHERE thread_id = :threadId AND delivery_state = 'DELIVERED'")
    suspend fun requeueDeliveredMessages(threadId: String, updatedAtMs: Long): Int

    @Query("UPDATE thread_message SET delivery_state = :state, updated_at_ms = :updatedAtMs WHERE message_id = :messageId")
    suspend fun updateDeliveryState(messageId: String, state: String, updatedAtMs: Long)

    @Query("UPDATE thread_message SET delivery_state = :state, updated_at_ms = :updatedAtMs WHERE thread_id = :threadId AND delivery_state = 'DELIVERED'")
    suspend fun markDeliveredAsPersisted(threadId: String, state: String, updatedAtMs: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertResult(result: ThreadResultEntity)

    @Query("SELECT * FROM thread_result WHERE thread_id = :threadId")
    suspend fun getResult(threadId: String): ThreadResultEntity?
}
