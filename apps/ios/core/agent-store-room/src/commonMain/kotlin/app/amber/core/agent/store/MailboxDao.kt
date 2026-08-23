package app.amber.core.agent.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * P1-b: mailbox 信封存储。Room 即真相：信封明文落库，drain 事务化
 * （查未投递 + 标投递一次完成）保证 exactly-once 折入。
 */
@Dao
interface MailboxDao {

    /**
     * 入队。ABORT：同 id 已存在（含已投递）时失败——生产者重试同一条逻辑信封
     * 不能把已消费的消息重新激活（重复折入），冲突应由生产者按「已入队」处理。
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueue(envelope: MailboxEnvelopeEntity)

    /**
     * 幂等入队（P1-c 终态去重）。IGNORE：同 id 已存在（含已投递）时静默跳过，
     * 不抛冲突异常——与 [enqueue] 的 ABORT 语义互补：ABORT 用于审计严格性
     * （重复即异常），本方法用于已知幂等的重投路径（如 FINAL_ANSWER 的
     * cancel 与 finishStreaming 双触发），重复入队按「已入队」幂等处理。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueueIfAbsent(envelope: MailboxEnvelopeEntity)

    /** 该收件人的全部未投递信封，FIFO（createdAt, id 稳定序）。 */
    @Query(
        """
        SELECT * FROM mailbox_envelope
        WHERE recipient_thread_id = :recipientId AND delivered_at IS NULL
        ORDER BY created_at ASC, id ASC
        """,
    )
    suspend fun pendingForRecipient(recipientId: String): List<MailboxEnvelopeEntity>

    /** 标投递（幂等：已投递的行不再更新，返回实际更新行数）。 */
    @Query(
        "UPDATE mailbox_envelope SET delivered_at = :deliveredAt WHERE id IN (:ids) AND delivered_at IS NULL",
    )
    suspend fun markDelivered(ids: List<String>, deliveredAt: Long): Int

    /** 查 + 标投递一次事务完成；重复/并发调用下最多折入一次（二次返回空）。 */
    @Transaction
    suspend fun drainPending(recipientId: String, deliveredAt: Long): List<MailboxEnvelopeEntity> {
        val pending = pendingForRecipient(recipientId)
        if (pending.isEmpty()) return emptyList()
        val updated = markDelivered(pending.map { it.id }, deliveredAt)
        // 并发 drain 竞态加固（P1-c checker 项）：两个事务可能同时读到同一批
        // 未投递行；`AND delivered_at IS NULL` 保证后写者实际更新 0 行。只有
        // 「全部标成功」的一方才折入，行数不足的并发 loser 返回空——绝不把
        // 同一条信封重复折入两次。
        if (updated < pending.size) return emptyList()
        return pending
    }
}
