package app.amber.feature.board.hotlist

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import app.amber.feature.board.DeepReadTemplateIds
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.feature.board.hotlist.deepread.isComplete
import app.amber.feature.board.hotlist.deepread.withInferredSectionStates
import app.amber.agent.data.db.dao.HotListDAO
import app.amber.agent.data.db.entity.DeepReadCacheEntity
import app.amber.agent.data.db.entity.HotListCacheEntity
import app.amber.agent.data.db.entity.HotListSourceEntity
import app.amber.agent.data.db.entity.HotTopicCacheEntity
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class HotListRepository(
    private val dao: HotListDAO,
    private val json: Json,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
) {
    private val deepReadHistoryPreviews = ConcurrentHashMap<String, DeepReadHistoryItem>()

    fun observeDashboard(): Flow<HotListDashboard> = combine(
        dao.observeHotTopics(HOT_LIST_TOPIC_CACHE_LIMIT, "$CUSTOM_TOPIC_ID_PREFIX%"),
        dao.observeProviderCaches(),
    ) { topicEntities, providerEntities ->
        val providers = providerEntities.map { it.toSnapshot(json) }
        val titleCache = providers.buildDisplayTitleCache()
        val topics = topicEntities.map { it.toTopic(json).withDisplayTitles(titleCache) }
        HotListDashboard(
            topics = topics,
            providers = providers,
            lastUpdatedAt = providers.maxOfOrNull { it.fetchedAt } ?: topics.maxOfOrNull { it.latestFetchedAt } ?: 0L,
        )
    }

    fun observeSources(): Flow<List<HotListSourceEntity>> = dao.observeSources()

    fun observeDeepRead(topicId: String): Flow<DeepReadOutput?> =
        observeDeepReadEntry(topicId).map { it?.output }

    fun observeDeepReadEntry(
        topicId: String,
        includeExpired: Boolean = false,
    ): Flow<DeepReadHistoryItem?> =
        dao.observeDeepRead(topicId).map { entity ->
            entity
                ?.toHistoryItem(json)
                ?.takeIf { includeExpired || !it.expired }
        }

    fun observeDeepReadHistory(limit: Int = 100): Flow<List<DeepReadHistoryItem>> =
        dao.observeDeepReadHistory(limit).map { entities ->
            val now = System.currentTimeMillis()
            entities.map { it.toHistoryItem(json, now) }
        }

    /** Raw entry for run bookkeeping — expiry filters belong to readers, not writers. */
    suspend fun getDeepReadEntry(topicId: String): DeepReadHistoryItem? =
        dao.getDeepRead(topicId)?.toHistoryItem(json)

    /** Clears a stale synthesis payload when a run falls back to the magazine pipeline. */
    suspend fun clearDeepReadStructuredJson(topicId: String) {
        withUserWrite { dao.clearDeepReadStructuredJson(topicId) }
    }

    /**
     * Entries that completed a full generation and are still valid — the basis
     * for the 付印 milestone count (first/tenth/hundredth). Decoding every row is
     * acceptable here: the query runs once per observed completion on Dispatchers.IO.
     */
    suspend fun countCompletedDeepReads(): Int {
        val now = System.currentTimeMillis()
        return dao.observeAllDeepReads().first().count { entity ->
            val item = entity.toHistoryItem(json, now)
            item.output?.withInferredSectionStates()?.isComplete() == true && !item.expired
        }
    }

    /** All cached deep-read rows — the masthead issue counter (No.N+1). */
    fun observeDeepReadCount(): Flow<Int> = dao.observeDeepReadCount()

    fun rememberDeepReadHistoryPreview(item: DeepReadHistoryItem) {
        deepReadHistoryPreviews[item.topicId] = item
    }

    fun deepReadHistoryPreview(topicId: String): DeepReadHistoryItem? =
        deepReadHistoryPreviews[topicId]

    suspend fun getProviderCache(providerId: String): HotListProviderSnapshot? =
        dao.getProviderCache(providerId)?.toSnapshot(json)

    suspend fun getEnabledSources(): List<HotListSourceEntity> = dao.getEnabledSources()

    suspend fun saveProviderResult(
        providerId: String,
        providerName: String,
        result: HotListResult,
        lastError: String? = null,
    ) {
        val now = System.currentTimeMillis()
        val entity = HotListCacheEntity(
            providerId = providerId,
            providerName = providerName,
            itemsJson = json.encodeToString(result.items),
            fetchedAt = result.fetchedAt,
            updatedAt = now,
            lastError = lastError,
        )
        withOwnerWrite {
            dao.upsertProviderCache(entity)
        }
    }

    suspend fun saveProviderFailure(
        providerId: String,
        providerName: String,
        error: String,
    ) {
        val now = System.currentTimeMillis()
        val existing = dao.getProviderCache(providerId)
        val entity = HotListCacheEntity(
            providerId = providerId,
            providerName = existing?.providerName ?: providerName,
            itemsJson = existing?.itemsJson ?: json.encodeToString(emptyList<HotListItem>()),
            fetchedAt = existing?.fetchedAt ?: 0L,
            updatedAt = now,
            lastError = error.take(160),
        )
        withOwnerWrite {
            dao.upsertProviderCache(entity)
        }
    }

    suspend fun replaceTopics(topics: List<HotTopic>) {
        val now = System.currentTimeMillis()
        val entities = topics.map { topic ->
            HotTopicCacheEntity(
                topicId = topic.id,
                title = topic.title,
                sourcesJson = json.encodeToString(topic.sources),
                sourceCount = topic.sourceCount,
                bestRank = topic.bestRank,
                latestFetchedAt = topic.latestFetchedAt,
                updatedAt = now,
                templateId = topic.deepReadTemplateId,
            )
        }
        withOwnerWrite {
            // Custom deep-read seed topics are user-owned; a hot-list refresh
            // must not wipe the sources a still-running/scheduled deep read needs.
            val preserved = dao.getTopicsByIdPattern("$CUSTOM_TOPIC_ID_PREFIX%")
            dao.replaceHotTopics(entities + preserved)
        }
    }

    suspend fun upsertTopic(topic: HotTopic) {
        val now = System.currentTimeMillis()
        val entity = HotTopicCacheEntity(
            topicId = topic.id,
            title = topic.title,
            sourcesJson = json.encodeToString(topic.sources),
            sourceCount = topic.sourceCount,
            bestRank = topic.bestRank,
            latestFetchedAt = topic.latestFetchedAt,
            updatedAt = now,
            templateId = topic.deepReadTemplateId,
        )
        withUserWrite {
            dao.upsertHotTopics(listOf(entity))
        }
    }

    suspend fun getHotTopic(topicId: String): HotTopic? = dao.getHotTopic(topicId)?.toTopic(json)

    suspend fun getFreshDeepRead(
        topicId: String,
        now: Long = System.currentTimeMillis(),
        title: String? = null,
    ): DeepReadOutput? {
        return dao.getDeepRead(topicId)?.toFreshDeepRead(json, now)
            ?: title
                ?.takeIf { it.isNotBlank() }
                ?.let { dao.getFreshDeepReadByTitle(it, now)?.toFreshDeepRead(json, now) }
    }

    suspend fun materializeFreshDeepRead(
        topicId: String,
        title: String,
        requestedTemplateId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): DeepReadOutput? {
        dao.getDeepRead(topicId)?.let { direct ->
            direct.toFreshDeepRead(json, now)?.let { return it }
        }
        if (title.isBlank()) return null
        val fallback = dao.getFreshDeepReadByTitle(title, now) ?: return null
        // A same-titled entry is only reusable when it was generated under the
        // template this run resolved — otherwise an explicit per-task pick would
        // silently inherit an old magazine/synthesis article.
        if (requestedTemplateId != null &&
            DeepReadTemplateIds.normalize(fallback.templateId) !=
            DeepReadTemplateIds.normalize(requestedTemplateId)
        ) {
            return null
        }
        val output = fallback.toFreshDeepRead(json, now) ?: return null
        if (fallback.topicId != topicId) {
            withOwnerWrite {
                dao.upsertDeepRead(
                    fallback.copy(
                        topicId = topicId,
                        title = title,
                        updatedAt = now,
                    )
                )
            }
        }
        return output
    }

    suspend fun saveDeepRead(
        topicId: String,
        title: String,
        output: DeepReadOutput,
        now: Long = System.currentTimeMillis(),
        ttlDays: Int = DEFAULT_TTL_DAYS,
        sourceUrl: String? = null,
        templateId: String? = null,
        structuredJson: String? = null,
    ) {
        val persistedSourceUrl = sourceUrl?.trim()?.takeIf { it.isNotEmpty() }
        val expiresAt = if (ttlDays <= 0) Long.MAX_VALUE else now + ttlDays * DAY_MS
        val entity = DeepReadCacheEntity(
            topicId = topicId,
            title = title,
            outputJson = json.encodeToString(output),
            createdAt = now,
            expiresAt = expiresAt,
            updatedAt = now,
            sourceUrl = persistedSourceUrl,
            templateId = templateId,
            structuredJson = structuredJson,
        )
        // The Room transaction preserves current pin/source metadata at write time,
        // including user changes made while this owner waited for the restore gate.
        withOwnerWrite { dao.saveDeepReadContent(entity) }
    }

    suspend fun clearDeepRead(topicId: String) = withOwnerOrUserWrite {
        dao.deleteDeepRead(topicId)
    }

    suspend fun setDeepReadPinned(topicId: String, pinned: Boolean) = withUserWrite {
        dao.setDeepReadPinned(topicId, pinned)
    }

    suspend fun pruneExpiredDeepReads(now: Long = System.currentTimeMillis()): Int = withOwnerWrite {
        dao.pruneExpiredDeepReads(now - DEEP_READ_HISTORY_RETENTION_MS)
    }

    suspend fun upsertSource(entity: HotListSourceEntity) = withUserWrite {
        dao.upsertSource(entity)
    }

    suspend fun deleteSource(id: String) = withUserWrite {
        dao.deleteSource(id)
    }

    private suspend fun <T> withOwnerWrite(block: suspend () -> T): T =
        restoreWriteGate?.withCurrentWriterOrCancel(block) ?: block()

    private suspend fun withUserWrite(block: suspend () -> Unit) {
        val gate = restoreWriteGate
        if (gate == null) {
            block()
        } else if (currentCoroutineContext()[SyncRestoreWriteEpoch] != null) {
            // UI calls normally carry no epoch and wait for restore. If a
            // background owner reaches this surface with an epoch, preserve
            // the same stale-owner rejection as the agent writers.
            gate.withCurrentWriterOrCancel(block)
        } else {
            gate.withWriter(block = block)
        }
    }

    private suspend fun withOwnerOrUserWrite(block: suspend () -> Unit) {
        val gate = restoreWriteGate
        if (gate == null) {
            block()
        } else if (currentCoroutineContext()[SyncRestoreWriteEpoch] != null) {
            gate.withCurrentWriterOrCancel(block)
        } else {
            gate.withWriter(block = block)
        }
    }

    companion object {
        const val DEEP_READ_TTL_MS = 24L * 60L * 60L * 1000L
        const val DEEP_READ_HISTORY_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
        const val DEFAULT_TTL_DAYS = 7
        private const val DAY_MS = 24L * 60L * 60L * 1000L

        fun topicId(title: String): String = sha256(
            title.lowercase()
                .replace(Regex("\\s+"), " ")
                .trim()
        ).take(32)

        private fun sha256(input: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}

data class DeepReadHistoryItem(
    val topicId: String,
    val title: String,
    val output: DeepReadOutput?,
    val createdAt: Long,
    val expiresAt: Long,
    val updatedAt: Long,
    val expired: Boolean,
    val pinned: Boolean = false,
    val sourceUrl: String? = null,
    /** Generation/display template this entry was produced with (iOS task.templateId). */
    val templateId: String? = null,
    /** Structured synthesis payload — decodes via `DeepReadTemplateArticle.decode`. */
    val structuredJson: String? = null,
)

private fun HotListCacheEntity.toSnapshot(json: Json): HotListProviderSnapshot =
    HotListProviderSnapshot(
        providerId = providerId,
        providerName = providerName,
        items = runCatching {
            json.decodeFromString(ListSerializer(HotListItem.serializer()), itemsJson)
        }.getOrDefault(emptyList()),
        fetchedAt = fetchedAt,
        stale = lastError != null,
        error = lastError,
    )

private fun HotTopicCacheEntity.toTopic(json: Json): HotTopic =
    HotTopic(
        id = topicId,
        title = title,
        sources = runCatching {
            json.decodeFromString(ListSerializer(HotTopicSource.serializer()), sourcesJson)
        }.getOrDefault(emptyList()),
        sourceCount = sourceCount,
        bestRank = bestRank,
        latestFetchedAt = latestFetchedAt,
        deepReadTemplateId = templateId,
    )

private fun DeepReadCacheEntity.toFreshDeepRead(json: Json, now: Long = System.currentTimeMillis()): DeepReadOutput? {
    if (!DeepReadCachePolicy.isFresh(expiresAt, now, pinned)) return null
    return toDeepReadOutput(json)
}

private fun DeepReadCacheEntity.toHistoryItem(
    json: Json,
    now: Long = System.currentTimeMillis(),
): DeepReadHistoryItem =
    DeepReadHistoryItem(
        topicId = topicId,
        title = title,
        output = toDeepReadOutput(json),
        createdAt = createdAt,
        expiresAt = expiresAt,
        updatedAt = updatedAt,
        expired = !DeepReadCachePolicy.isFresh(expiresAt, now, pinned),
        pinned = pinned,
        sourceUrl = sourceUrl,
        templateId = templateId,
        structuredJson = structuredJson,
    )

private fun DeepReadCacheEntity.toDeepReadOutput(json: Json): DeepReadOutput? =
    runCatching { json.decodeFromString<DeepReadOutput>(outputJson) }.getOrNull()

private fun List<HotListProviderSnapshot>.buildDisplayTitleCache(): Map<String, String> =
    flatMap { provider ->
        provider.items.mapNotNull { item ->
            val display = item.displayTitle?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val keys = listOfNotNull(item.cacheKey(), item.title.cacheKey())
            keys.map { key -> key to display }
        }.flatten()
    }.toMap()

private fun HotTopic.withDisplayTitles(titleCache: Map<String, String>): HotTopic {
    if (titleCache.isEmpty()) return this
    val repairedSources = sources.map { source ->
        val display = source.displayTitle?.takeIf { it.isNotBlank() }
            ?: source.cacheKey()?.let(titleCache::get)
            ?: source.title.cacheKey()?.let(titleCache::get)
        if (display.isNullOrBlank()) source else source.copy(displayTitle = display)
    }
    val repairedTitle = repairedSources
        .firstNotNullOfOrNull { it.displayTitle?.takeIf { display -> display.isNotBlank() && display.countCjk() >= 2 } }
        ?: title
    return copy(title = repairedTitle, sources = repairedSources)
}

private fun HotListItem.cacheKey(): String? =
    (url?.takeIf { it.isNotBlank() } ?: title).cacheKey()

private fun HotTopicSource.cacheKey(): String? =
    (url?.takeIf { it.isNotBlank() } ?: title).cacheKey()

private fun String.cacheKey(): String? =
    takeIf { it.isNotBlank() }
        ?.lowercase()
        ?.replace(Regex("\\s+"), " ")
        ?.trim()

private fun String.countCjk(): Int = count { it in '\u4e00'..'\u9fff' }
