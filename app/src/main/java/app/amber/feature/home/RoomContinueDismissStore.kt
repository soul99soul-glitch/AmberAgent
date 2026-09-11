package app.amber.feature.home

import app.amber.agent.data.db.dao.ContinueCandidateDismissDAO
import app.amber.agent.data.db.entity.ContinueCandidateDismissEntity
import app.amber.core.sync.core.SyncRestoreWriteGate
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** [ContinueDismissStore] 的 Room 实现：持久投影，进程死亡后隐藏状态不丢。 */
class RoomContinueDismissStore(
    private val dao: ContinueCandidateDismissDAO,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
) : ContinueDismissStore {

    override fun observeDismissed(): Flow<List<ContinueCandidateDismissEntity>> = dao.observeAll()

    override suspend fun dismiss(sourceKind: ContinueSourceKind, sourceId: String, until: Instant) {
        withDurableWrite {
            dao.upsert(
                ContinueCandidateDismissEntity(
                    sourceKind = sourceKind.name,
                    sourceId = sourceId,
                    dismissUntilMs = until.toEpochMilli(),
                )
            )
        }
    }

    override suspend fun deleteExpired(nowMs: Long) {
        withDurableWrite {
            dao.deleteExpired(nowMs)
        }
    }

    private suspend fun <T> withDurableWrite(block: suspend () -> T): T {
        val gate = restoreWriteGate ?: return block()
        return gate.withCurrentWriterOrCancel(block)
    }
}
