package app.amber.feature.board.collector

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.amber.feature.board.BoardSignalSourceType
import me.rerere.rikkahub.data.db.dao.DocChangeLogDAO
import me.rerere.rikkahub.data.db.dao.DocSubscriptionDAO

/**
 * Bridges the document radar change log into the Today Board signal stream.
 *
 * Reads `doc_change_log` entries with status='new' and projects them as Board
 * signals. Items are marked 'consumed' via [onIngested] only after the signal
 * is actually persisted by the aggregator.
 *
 * Adapted to the refactored DocRadar data model (DocSubscription + DocChangeLog
 * instead of FeishuWatchedDoc + FeishuDocChange + FeishuDocSnapshot).
 */
class FeishuDocSignalCollector(
    private val subscriptionDao: DocSubscriptionDAO,
    private val changeLogDao: DocChangeLogDAO,
) : BoardSignalCollector {
    override val sourceType: String = BoardSignalSourceType.FEISHU_DOC

    override suspend fun collect(limit: Int): List<RawBoardSignal> = withContext(Dispatchers.IO) {
        val newChanges = changeLogDao.getNew()
        if (newChanges.isEmpty()) return@withContext emptyList()
        val subsById = subscriptionDao.getAll().associateBy { it.id }
        val results = mutableListOf<RawBoardSignal>()
        for (change in newChanges.take(limit)) {
            val sub = subsById[change.subscriptionId] ?: continue
            val title = "${sub.docTitle} 已更新"
            val content = change.summary.ifBlank {
                "变更 ${change.effectiveChange} 字"
            }
            results += RawBoardSignal(
                sourceType = BoardSignalSourceType.FEISHU_DOC,
                sourceRef = change.id,
                title = title.take(120),
                content = content.take(2_000),
                signalTime = change.detectedAt,
                metadataJson = """{"subscription_id":"${change.subscriptionId}","effective_change":${change.effectiveChange}}""",
            )
        }
        results
    }

    override suspend fun onIngested(signal: RawBoardSignal) {
        if (signal.sourceType != BoardSignalSourceType.FEISHU_DOC) return
        runCatching { changeLogDao.updateStatus(signal.sourceRef, "consumed") }
    }
}
