package me.rerere.rikkahub.data.agent.board.collector

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.agent.board.BoardSignalSourceType
import me.rerere.rikkahub.data.db.dao.FeishuDocChangeDAO
import me.rerere.rikkahub.data.db.dao.FeishuWatchedDocDAO

/**
 * Bridges the existing Feishu Document Radar pipeline into the Today Board signal stream.
 *
 * Radar already detects significant document changes and writes [FeishuDocChangeEntity]
 * rows tagged with status='new'. Rather than re-implement the diff layer here, this
 * collector simply re-projects those rows as Board signals. Items already converted are
 * marked status='consumed' via [onIngested], driven by the aggregator only after the
 * signal is actually persisted, so dedup-rejected or cancelled-mid-run signals don't
 * silently lose the radar entry.
 */
class FeishuDocSignalCollector(
    private val watchedDocDao: FeishuWatchedDocDAO,
    private val changeDao: FeishuDocChangeDAO,
) : BoardSignalCollector {
    override val sourceType: String = BoardSignalSourceType.FEISHU_DOC

    override suspend fun collect(limit: Int): List<RawBoardSignal> = withContext(Dispatchers.IO) {
        val newChanges = changeDao.getNew()
        if (newChanges.isEmpty()) return@withContext emptyList()
        val docsById = watchedDocDao.getAll().associateBy { it.id }
        val results = mutableListOf<RawBoardSignal>()
        for (change in newChanges.take(limit)) {
            val doc = docsById[change.watchedDocId] ?: continue
            val title = "${doc.docTitle} 已更新"
            val summary = change.diffSummary?.takeIf { it.isNotBlank() }
                ?: "新增 ${change.addedChars} 字 / 删除 ${change.removedChars} 字 / 净变更 ${change.effectiveChange}"
            val content = buildString {
                appendLine(summary)
                if (!change.aiAnalysisJson.isNullOrBlank()) {
                    append("\n[已附 AI 摘要]")
                }
            }.trim()
            results += RawBoardSignal(
                sourceType = BoardSignalSourceType.FEISHU_DOC,
                // Use the change.id directly so onIngested can flip the right row.
                sourceRef = change.id,
                title = title.take(120),
                content = content.take(2_000),
                signalTime = change.createdAt,
                metadataJson = """{"watched_doc_id":"${change.watchedDocId}","effective_change":${change.effectiveChange}}""",
            )
        }
        results
    }

    /**
     * Flip the radar entry to 'consumed' only after the signal is persisted, so we don't
     * lose changes if dedup rejects the signal or the worker is cancelled before save.
     */
    override suspend fun onIngested(signal: RawBoardSignal) {
        if (signal.sourceType != BoardSignalSourceType.FEISHU_DOC) return
        runCatching { changeDao.updateStatus(signal.sourceRef, "consumed") }
    }
}
