package app.amber.feature.runtime

import app.amber.agent.data.db.dao.ThreadGraphDAO
import app.amber.agent.data.db.entity.ThreadMessageEntity
import app.amber.agent.data.db.entity.ThreadNodeEntity
import app.amber.agent.data.db.entity.ThreadResultEntity
import app.amber.feature.subagent.ThreadDeliveryState
import app.amber.feature.subagent.ThreadGraphStore
import app.amber.feature.subagent.ThreadMessageRecord
import app.amber.feature.subagent.ThreadNodeRecord
import app.amber.feature.subagent.ThreadResultRecord
import java.time.Instant

/** Schema version of the thread graph tables — surfaced on the debug page. */
const val THREAD_GRAPH_SCHEMA_VERSION = 1

/**
 * P4-02 durable thread graph on Room (schema v13). Pure additive tables; the
 * store is only written/read when the `thread_graph_v2` capability flag is on
 * (rollback rules §17.2 keep the tables and their data when the flag flips).
 */
class RoomThreadGraphStore(
    private val dao: ThreadGraphDAO,
) : ThreadGraphStore {

    override suspend fun upsertNode(node: ThreadNodeRecord) {
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
        dao.upsertMessage(message.toEntity())
    }

    override suspend fun getMessage(messageId: String): ThreadMessageRecord? =
        dao.getMessage(messageId)?.toRecord()

    override suspend fun listMessages(threadId: String): List<ThreadMessageRecord> =
        dao.listMessages(threadId).map { it.toRecord() }

    override suspend fun listQueuedMessages(threadId: String): List<ThreadMessageRecord> =
        dao.listQueuedMessages(threadId).map { it.toRecord() }

    override suspend fun claimQueuedMessages(threadId: String): List<ThreadMessageRecord> =
        dao.claimQueuedMessages(
            threadId = threadId,
            state = ThreadDeliveryState.DELIVERED.name,
            updatedAtMs = Instant.now().toEpochMilli(),
        ).map { it.toRecord() }

    override suspend fun requeueDeliveredMessages(threadId: String): Int =
        dao.requeueDeliveredMessages(threadId, Instant.now().toEpochMilli())

    override suspend fun markMessageDelivered(messageId: String) {
        val message = dao.getMessage(messageId) ?: return
        if (message.deliveryState != ThreadDeliveryState.QUEUED.name) return
        dao.updateDeliveryState(messageId, ThreadDeliveryState.DELIVERED.name, Instant.now().toEpochMilli())
    }

    override suspend fun markDeliveredMessagesPersisted(threadId: String): Int =
        dao.markDeliveredAsPersisted(threadId, ThreadDeliveryState.PERSISTED.name, Instant.now().toEpochMilli())

    override suspend fun upsertResult(result: ThreadResultRecord) {
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
