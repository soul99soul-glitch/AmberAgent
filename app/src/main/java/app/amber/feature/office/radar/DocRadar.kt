package app.amber.feature.office.radar

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.db.dao.DocChangeLogDAO
import me.rerere.rikkahub.data.db.dao.DocSubscriptionDAO
import me.rerere.rikkahub.data.db.entity.DocChangeLogEntity
import me.rerere.rikkahub.data.db.entity.DocSubscriptionEntity
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * Consolidated document change radar — replaces the old 6-class split
 * (Monitor + Worker + Fetcher + DiffEngine + Analyzer + Notifier).
 *
 * Responsibilities:
 * - subscribe/unsubscribe with immediate baseline capture
 * - periodic + manual change detection
 * - lightweight diff (hash + char count + heading comparison)
 * - WorkManager scheduling
 * - notification on significant changes
 *
 * Fetches documents via MCP tool (same as before). Direct Feishu API
 * is a future improvement when OAuth token scoping is resolved.
 */
class DocRadar(
    private val context: Context,
    private val subscriptionDao: DocSubscriptionDAO,
    private val changeLogDao: DocChangeLogDAO,
    private val mcpManager: McpManager,
    private val settingsStore: SettingsAggregator,
    private val notifier: FeishuChangeNotifier,
) {
    private val workManager = WorkManager.getInstance(context)

    // ---- Subscription lifecycle ----

    /**
     * Subscribe to a document with immediate baseline capture.
     * Returns error message if fetch fails, null on success.
     */
    suspend fun subscribe(
        url: String,
        title: String,
        threshold: Int = 500,
        notifyEnabled: Boolean = true,
    ): String? = withContext(Dispatchers.IO) {
        // Dedup: check if already subscribed
        val existing = subscriptionDao.getByUrl(url)
        if (existing != null) {
            return@withContext "该文档已订阅"
        }

        val docToken = parseDocToken(url)
        val now = System.currentTimeMillis()

        // Fetch initial content for baseline
        val content = fetchDocContent(url)
        val sub = DocSubscriptionEntity(
            id = Uuid.random().toString(),
            docUrl = url,
            docToken = docToken,
            docTitle = title,
            enabled = true,
            threshold = threshold,
            notifyEnabled = notifyEnabled,
            lastContentHash = content?.contentHash ?: "",
            lastCharCount = content?.plainText?.length ?: 0,
            lastHeadingListJson = content?.headingsJson ?: "[]",
            lastCheckedAt = if (content != null) now else null,
            createdAt = now,
            updatedAt = now,
        )
        subscriptionDao.upsert(sub)
        if (content == null) {
            "已订阅但初始基线获取失败（可能 MCP 未配置），下次检查时自动重试"
        } else {
            null // success
        }
    }

    suspend fun unsubscribe(id: String) {
        subscriptionDao.deleteById(id)
    }

    suspend fun updateThreshold(id: String, threshold: Int) {
        subscriptionDao.updateThreshold(id, threshold)
    }

    // ---- Change detection ----

    /**
     * Check all enabled subscriptions. Returns the count of significant changes detected.
     */
    suspend fun checkAll(): Int = withContext(Dispatchers.IO) {
        val subs = subscriptionDao.getEnabled()
        if (subs.isEmpty()) return@withContext 0

        var significantCount = 0
        val now = System.currentTimeMillis()

        for (sub in subs) {
            val change = runCatching { checkOne(sub, now) }
                .onFailure { Log.w(TAG, "check failed for ${sub.docTitle}", it) }
                .getOrNull()
            if (change != null) significantCount++
        }
        significantCount
    }

    /**
     * Check a single subscription. Returns a DocChangeLogEntity if significant
     * change was detected, null otherwise (no change or below threshold).
     */
    suspend fun checkOne(sub: DocSubscriptionEntity, now: Long = System.currentTimeMillis()): DocChangeLogEntity? {
        val content = fetchDocContent(sub.docUrl)
        if (content == null) {
            subscriptionDao.markChecked(sub.id, now)
            return null
        }

        // No baseline yet? Set it now and return (first check after failed subscribe)
        if (!sub.hasBaseline) {
            subscriptionDao.updateBaseline(
                id = sub.id,
                hash = content.contentHash,
                charCount = content.plainText.length,
                headingsJson = content.headingsJson,
                checkedAt = now,
            )
            return null
        }

        // Hash unchanged → no change
        if (content.contentHash == sub.lastContentHash) {
            subscriptionDao.markChecked(sub.id, now)
            return null
        }

        // Diff
        val diff = diffContent(sub, content)

        // Update baseline regardless of significance
        subscriptionDao.updateBaseline(
            id = sub.id,
            hash = content.contentHash,
            charCount = content.plainText.length,
            headingsJson = content.headingsJson,
            checkedAt = now,
        )

        // Below threshold and no structural change → minor, don't create change log
        if (!diff.isSignificant) return null

        // Significant change → create log + notify
        val changeLog = DocChangeLogEntity(
            id = Uuid.random().toString(),
            subscriptionId = sub.id,
            addedChars = diff.addedChars,
            removedChars = diff.removedChars,
            effectiveChange = diff.effectiveChange,
            changedSectionsJson = diff.changedSectionsJson,
            summary = diff.summary,
            detectedAt = now,
        )
        changeLogDao.insert(changeLog)
        subscriptionDao.markChanged(sub.id, now)

        if (sub.notifyEnabled) {
            notifier.notifyChange(
                docTitle = sub.docTitle,
                changeCount = diff.effectiveChange,
                summary = diff.summary,
                changeId = changeLog.id,
            )
        }

        return changeLog
    }

    // ---- Scheduling ----

    fun schedulePeriodicCheck() {
        // Cancel legacy work from pre-refactor version to prevent double-scheduling
        workManager.cancelUniqueWork("feishu_doc_radar")

        val request = PeriodicWorkRequestBuilder<DocRadarWorker>(
            90L, TimeUnit.MINUTES,
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build(),
        ).addTag(WORK_TAG).build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun runOnce() {
        val request = OneTimeWorkRequestBuilder<DocRadarWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            ).addTag(WORK_TAG).build()
        workManager.enqueue(request)
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    // ---- Document fetching (MCP-based, future: direct Feishu API) ----

    private data class FetchedContent(
        val plainText: String,
        val contentHash: String,
        val headingsJson: String,
    )

    private suspend fun fetchDocContent(url: String): FetchedContent? {
        val tool = findDocReaderTool() ?: return null
        return runCatching {
            val args = buildJsonObject { put("url", url) }
            val parts = mcpManager.callConfiguredTool(
                serverId = tool.first,
                serverName = null,
                toolName = tool.second,
                args = args as JsonObject,
            )
            val text = parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
                .joinToString("\n") { it.text }
            if (text.isBlank()) return@runCatching null
            FetchedContent(
                plainText = text,
                contentHash = sha256(text),
                headingsJson = buildHeadingsJson(text),
            )
        }.onFailure { Log.w(TAG, "fetchDocContent failed for $url", it) }
            .getOrNull()
    }

    private fun findDocReaderTool(): Pair<String, String>? {
        val settings = settingsStore.settingsFlow.value
        val feishuServer = settings.mcpServers.firstOrNull { server ->
            val n = server.commonOptions.name
            server.commonOptions.enable &&
                (n.contains("feishu", ignoreCase = true) || n.contains("lark", ignoreCase = true))
        } ?: return null
        val tool = feishuServer.commonOptions.tools.firstOrNull { tool ->
            val name = tool.name.lowercase()
            val desc = tool.description?.lowercase().orEmpty()
            tool.enable &&
                (name.contains("doc") || name.contains("read") || name.contains("fetch") ||
                    name.contains("文档") || desc.contains("document") || desc.contains("文档"))
        } ?: return null
        return Pair(feishuServer.id.toString(), tool.name)
    }

    // ---- Lightweight diff ----

    private data class DiffResult(
        val addedChars: Int,
        val removedChars: Int,
        val effectiveChange: Int,
        val isSignificant: Boolean,
        val changedSectionsJson: String,
        val summary: String,
    )

    private fun diffContent(sub: DocSubscriptionEntity, newContent: FetchedContent): DiffResult {
        // Use hash-aware diff: if hash changed but char counts are similar,
        // content was rewritten (e.g. 500 chars deleted + 500 different added).
        // Fall back to paragraph-set diff when net delta is small but hash differs.
        val charDelta = newContent.plainText.length - sub.lastCharCount
        val netAdded = maxOf(0, charDelta)
        val netRemoved = maxOf(0, -charDelta)
        val netEffective = netAdded + netRemoved

        // If net delta is small but hash changed, do a rough content-level estimate:
        // the document was rewritten. Use the larger of old/new length as effective change.
        val addedChars: Int
        val removedChars: Int
        val effectiveChange: Int
        if (netEffective < sub.threshold && newContent.contentHash != sub.lastContentHash) {
            // Content rewrite detected — estimate based on document size
            val estimatedRewrite = maxOf(newContent.plainText.length, sub.lastCharCount)
            addedChars = estimatedRewrite / 2
            removedChars = estimatedRewrite / 2
            effectiveChange = estimatedRewrite
        } else {
            addedChars = netAdded
            removedChars = netRemoved
            effectiveChange = netEffective
        }

        // Heading-level structural change detection
        val oldHeadings = parseJsonStringList(sub.lastHeadingListJson)
        val newHeadings = parseJsonStringList(newContent.headingsJson)
        val structuralChange = oldHeadings != newHeadings && (oldHeadings.isNotEmpty() || newHeadings.isNotEmpty())

        val changedSections = if (structuralChange) {
            (newHeadings - oldHeadings.toSet()) + (oldHeadings - newHeadings.toSet())
        } else emptyList()

        val isSignificant = effectiveChange >= sub.threshold || structuralChange

        val summary = buildString {
            if (addedChars > 0 && removedChars > 0) append("新增 ${addedChars}字，删除 ${removedChars}字")
            else if (addedChars > 0) append("新增 ${addedChars}字")
            else if (removedChars > 0) append("删除 ${removedChars}字")
            else append("结构变更")
            if (changedSections.isNotEmpty()) {
                append("，涉及: ${changedSections.take(3).joinToString("、")}")
            }
        }

        val sectionsJson = buildString {
            append("[")
            changedSections.take(20).forEachIndexed { i, s ->
                if (i > 0) append(",")
                append("\"${s.replace("\"", "\\\"")}\"")
            }
            append("]")
        }

        return DiffResult(addedChars, removedChars, effectiveChange, isSignificant, sectionsJson, summary)
    }

    // ---- Utilities ----

    private fun parseDocToken(url: String): String {
        // Feishu doc URLs: https://xxx.feishu.cn/docx/TOKEN or /wiki/TOKEN etc.
        // Strip query params and fragments before extracting the last path segment.
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        val segments = path.split("/")
        return segments.lastOrNull()?.takeIf { it.isNotBlank() } ?: url
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun buildHeadingsJson(text: String): String {
        val headings = Regex("""^#{1,6}\s+(.+)$""", RegexOption.MULTILINE)
            .findAll(text).map { it.groupValues[1].trim() }.take(80).toList()
        return buildString {
            append("[")
            headings.forEachIndexed { i, h ->
                if (i > 0) append(",")
                append("\"${h.replace("\"", "\\\"")}\"")
            }
            append("]")
        }
    }

    private fun parseJsonStringList(json: String): List<String> = runCatching {
        kotlinx.serialization.json.Json.decodeFromString<List<String>>(json)
    }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "DocRadar"
        private const val WORK_NAME = "doc_radar"
        private const val WORK_TAG = "doc_radar"
    }
}

/**
 * Thin WorkManager worker that delegates to [DocRadar.checkAll].
 */
class DocRadarWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    override suspend fun doWork(): Result = runCatching {
        val radar = get<DocRadar>()
        radar.checkAll()
        // Prune old change logs (> 30 days)
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000L
        get<DocChangeLogDAO>().pruneOlderThan(cutoff)
        Result.success()
    }.getOrElse {
        Log.w("DocRadarWorker", "radar check failed", it)
        Result.retry()
    }
}
