package me.rerere.rikkahub.data.agent.office.radar

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.FeishuDocChangeDAO
import me.rerere.rikkahub.data.db.dao.FeishuDocDependencyDAO
import me.rerere.rikkahub.data.db.dao.FeishuDocSnapshotDAO
import me.rerere.rikkahub.data.db.dao.FeishuWatchedDocDAO
import me.rerere.rikkahub.data.db.entity.FeishuDocChangeEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

class FeishuDocRadarWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    override suspend fun doWork(): Result = runCatching {
        val settings = get<SettingsStore>().settingsFlow.value
        val enhancement = settings.agentRuntime.feishuOfficeEnhancement
        if (!enhancement.enabled) return Result.success()

        val watchedDocDAO = get<FeishuWatchedDocDAO>()
        val snapshotDAO = get<FeishuDocSnapshotDAO>()
        val changeDAO = get<FeishuDocChangeDAO>()
        val dependencyDAO = get<FeishuDocDependencyDAO>()
        val fetcher = get<FeishuDocumentFetcher>()
        val analyzer = get<FeishuChangeAnalyzer>()
        val notifier = get<FeishuChangeNotifier>()
        val json = get<Json>()

        val watchedDocs = watchedDocDAO.getEnabled()
        if (watchedDocs.isEmpty()) return Result.success()

        val now = System.currentTimeMillis()

        watchedDocs.forEach { doc ->
            val minsSinceLastCheck = doc.lastCheckedAt?.let { (now - it) / 60_000L } ?: Long.MAX_VALUE
            if (minsSinceLastCheck < doc.checkIntervalMin) return@forEach

            val newSnapshot = fetcher.checkDocument(doc) ?: run {
                watchedDocDAO.updateLastChecked(doc.id, now)
                return@forEach
            }

            val latestSnapshot = snapshotDAO.getRecent(doc.id, limit = 2)
                .firstOrNull { it.id != newSnapshot.id }
            val lastSnapshot = latestSnapshot ?: return@forEach.also {
                watchedDocDAO.updateLastChecked(doc.id, now)
            }

            val newHeadings = try {
                json.decodeFromString<List<String>>(newSnapshot.headingListJson)
            } catch (_: Exception) {
                emptyList()
            }

            val diff = FeishuDocumentDiffEngine.diff(
                oldSnapshot = lastSnapshot,
                newSnapshot = newSnapshot,
                headingList = newHeadings,
                threshold = doc.changeThreshold,
            )

            val change = FeishuDocChangeEntity(
                id = Uuid.random().toString(),
                watchedDocId = doc.id,
                fromSnapshotId = lastSnapshot.id,
                toSnapshotId = newSnapshot.id,
                addedChars = diff.addedChars,
                removedChars = diff.removedChars,
                effectiveChange = diff.effectiveChange,
                changedSectionsJson = json.encodeToString(
                    ListSerializer(String.serializer()),
                    diff.changedSections,
                ),
                diffSummary = diff.diffSummary,
                status = if (diff.isSignificant) "new" else "minor",
                createdAt = now,
            )
            changeDAO.insert(change)
            watchedDocDAO.updateLastChecked(doc.id, now)

            if (diff.isSignificant) {
                watchedDocDAO.updateLastChanged(doc.id, now)

                val downstreams = dependencyDAO.getDownstreamsOf(doc.docUrl)
                val analysis = analyzer.analyze(change, doc.docTitle, diff.changedSections, downstreams)
                if (analysis != null) {
                    val analysisJson = json.encodeToString(
                        kotlinx.serialization.serializer<ChangeAnalysis>(),
                        analysis,
                    )
                    changeDAO.updateAnalysis(change.id, analysisJson)
                }

                if (doc.notifyEnabled) {
                    notifier.notifyChange(
                        docTitle = doc.docTitle,
                        changeCount = diff.effectiveChange,
                        summary = analysis?.changeSummary ?: diff.diffSummary,
                        changeId = change.id,
                    )
                    changeDAO.markNotified(change.id)
                }
            }
            return@forEach
        }

        Result.success()
    }.getOrElse { error ->
        Result.failure()
    }
}
