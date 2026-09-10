package app.amber.feature.runtime

import app.amber.agent.data.db.dao.RunResumeDAO
import app.amber.agent.data.db.entity.RunResumeEntity
import app.amber.ai.provider.ResponseCursor
import app.amber.ai.provider.ResponseResumeStore
import app.amber.core.sync.core.SyncRestoreWriteGate

/**
 * P6-01 — Room-backed [ResponseResumeStore] keyed by local runId
 * (plan §P6-01 #3: "落 Room 或现有 run 状态存储，选最小方案").
 */
class RoomResponseResumeStore(
    private val dao: RunResumeDAO,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
) : ResponseResumeStore {

    override suspend fun save(runId: String, responseId: String, sequence: Long, providerId: String) {
        withDurableWrite {
            dao.upsert(
                RunResumeEntity(
                    runId = runId,
                    responseId = responseId,
                    sequence = sequence,
                    providerId = providerId,
                )
            )
        }
    }

    override suspend fun load(runId: String): ResponseCursor? =
        dao.getByRunId(runId)?.let {
            ResponseCursor(responseId = it.responseId, sequence = it.sequence, providerId = it.providerId)
        }

    override suspend fun clear(runId: String) {
        withDurableWrite { dao.deleteByRunId(runId) }
    }

    private suspend fun <T> withDurableWrite(block: suspend () -> T): T {
        val gate = restoreWriteGate
        return if (gate == null) block() else gate.withCurrentWriterOrCancel(block)
    }
}
