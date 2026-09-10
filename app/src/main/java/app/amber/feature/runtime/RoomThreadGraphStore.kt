package app.amber.feature.runtime

import app.amber.agent.data.db.dao.ThreadGraphDAO
import app.amber.agent.data.db.entity.ThreadMessageEntity
import app.amber.agent.data.db.entity.ThreadNodeEntity
import app.amber.agent.data.db.entity.ThreadResultEntity
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.feature.subagent.ThreadDeliveryState
import app.amber.feature.subagent.ThreadGraphStore
import app.amber.feature.subagent.ThreadMessageRecord
import app.amber.feature.subagent.ThreadNodeRecord
import app.amber.feature.subagent.ThreadResultRecord
import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Schema version of the thread graph tables — surfaced on the debug page. */
const val THREAD_GRAPH_SCHEMA_VERSION = 1

/**
 * P4-02 durable thread graph on Room (schema v13). Pure additive tables; the
 * store is only written/read when the `thread_graph_v2` capability flag is on
 * (rollback rules §17.2 keep the tables and their data when the flag flips).
 */
class RoomThreadGraphStore(
    private val dao: ThreadGraphDAO,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
) : ThreadGraphStore {

    override suspend fun captureWriteContext(): CoroutineContext {
        val gate = restoreWriteGate ?: return EmptyCoroutineContext
        return currentCoroutineContext()[SyncRestoreWriteEpoch]
            ?: SyncRestoreWriteEpoch(gate.currentEpoch())
    }

    override suspend fun upsertNode(node: ThreadNodeRecord) {
        withDurableWrite {
            dao.upsertNode(
                ThreadNodeEntity(
                    threadId = node.threadId,
                    parentThreadId = node.parentThreadId,
                    rootRunId = node.rootRunId,
                    conversationId = node.conversationId,
                    status = node.status,
                    task = node.task,
                    startedAtMs = node.startedAtMs,
                    updatedAtMs = node.updatedAtMs,
                )
            )
        }
    }

    override suspend fun getNode(threadId: String): ThreadNodeRecord? =
        dao.getNode(threadId)?.let { node ->
            ThreadNodeRecord(
                threadId = node.threadId,
                parentThreadId = node.parentThreadId,
                rootRunId = node.rootRunId,
                conversationId = node.conversationId,
                status = node.status,
                task = node.task,
                startedAtMs = node.startedAtMs,
                updatedAtMs = node.updatedAtMs,
            )
        }

    override suspend fun listNodesByRootRun(rootRunId: String): List<ThreadNodeRecord> =
        dao.listNodesByRootRun(rootRunId).map { node ->
            ThreadNodeRecord(
                threadId = node.threadId,
                parentThreadId = node.parentThreadId,
                rootRunId = node.rootRunId,
                conversationId = node.conversationId,
                status = node.status,
                task = node.task,
                startedAtMs = node.startedAtMs,
                updatedAtMs = node.updatedAtMs,
            )
        }

    override suspend fun enqueueMessage(message: ThreadMessageRecord) {
        withDurableWrite { dao.upsertMessage(message.toEntity()) }
    }

    override suspend fun getMessage(messageId: String): ThreadMessageRecord? =
        dao.getMessage(messageId)?.toRecord()

    override suspend fun listMessages(threadId: String): List<ThreadMessageRecord> =
        dao.listMessages(threadId).map { it.toRecord() }

    override suspend fun listQueuedMessages(threadId: String): List<ThreadMessageRecord> =
        dao.listQueuedMessages(threadId).map { it.toRecord() }

    override suspend fun claimQueuedMessages(threadId: String): List<ThreadMessageRecord> =
        withDurableWrite {
            dao.claimQueuedMessages(
                threadId = threadId,
                state = ThreadDeliveryState.DELIVERED.name,
                updatedAtMs = Instant.now().toEpochMilli(),
            ).map { it.toRecord() }
        }

    override suspend fun requeueDeliveredMessages(threadId: String): Int =
        withDurableWrite {
            dao.requeueDeliveredMessages(threadId, Instant.now().toEpochMilli())
        }

    override suspend fun markMessageDelivered(messageId: String) {
        withDurableWrite {
            val message = dao.getMessage(messageId) ?: return@withDurableWrite
            if (message.deliveryState != ThreadDeliveryState.QUEUED.name) return@withDurableWrite
            dao.updateDeliveryState(messageId, ThreadDeliveryState.DELIVERED.name, Instant.now().toEpochMilli())
        }
    }

    override suspend fun markDeliveredMessagesPersisted(threadId: String): Int =
        withDurableWrite {
            dao.markDeliveredAsPersisted(threadId, ThreadDeliveryState.PERSISTED.name, Instant.now().toEpochMilli())
        }

    override suspend fun upsertResult(result: ThreadResultRecord) {
        withDurableWrite {
            dao.upsertResult(
                ThreadResultEntity(
                    threadId = result.threadId,
                    finalAnswer = result.finalAnswer,
                    artifactsJson = result.artifactsJson,
                    terminalReason = result.terminalReason,
                    finishedAtMs = result.finishedAtMs,
                )
            )
        }
    }

    private suspend fun <T> withDurableWrite(block: suspend () -> T): T {
        val gate = restoreWriteGate
        return if (gate == null) block() else gate.withCurrentWriterOrCancel(block)
    }

    override suspend fun getResult(threadId: String): ThreadResultRecord? =
        dao.getResult(threadId)?.let { result ->
            ThreadResultRecord(
                threadId = result.threadId,
                finalAnswer = result.finalAnswer,
                artifactsJson = result.artifactsJson,
                terminalReason = result.terminalReason,
                finishedAtMs = result.finishedAtMs,
            )
        }

    private fun ThreadMessageRecord.toEntity(): ThreadMessageEntity = ThreadMessageEntity(
        messageId = messageId,
        threadId = threadId,
        sender = sender,
        recipient = recipient,
        kind = kind,
        payload = payload,
        payloadDigest = payloadDigest,
        deliveryState = deliveryState,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )

    private fun ThreadMessageEntity.toRecord(): ThreadMessageRecord = ThreadMessageRecord(
        messageId = messageId,
        threadId = threadId,
        sender = sender,
        recipient = recipient,
        kind = kind,
        payload = payload,
        payloadDigest = payloadDigest,
        deliveryState = deliveryState,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )
}
