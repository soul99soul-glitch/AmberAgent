package app.amber.feature.board.hotlist

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.agent.data.db.dao.HotListDAO
import app.amber.agent.data.db.entity.DeepReadCacheEntity
import app.amber.agent.data.db.entity.HotListCacheEntity
import app.amber.agent.data.db.entity.HotListSourceEntity
import app.amber.agent.data.db.entity.HotTopicCacheEntity
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class HotListRepository(
    private val dao: HotListDAO,
    private val json: Json,
) {
    private val deepReadHistoryPreviews = ConcurrentHashMap<String, DeepReadHistoryItem>()

    fun observeDashboard(): Flow<HotListDashboard> = combine(
        dao.observeHotTopics(HOT_LIST_TOPIC_CACHE_LIMIT),
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
        dao.upsertProviderCache(
            HotListCacheEntity(
                providerId = providerId,
                providerName = providerName,
                itemsJson = json.encodeToString(result.items),
                fetchedAt = result.fetchedAt,
                updatedAt = now,
                lastError = lastError,
            )
        )
    }

    suspend fun saveProviderFailure(
        providerId: String,
        providerName: String,
        error: String,
    ) {
        val now = System.currentTimeMillis()
        val existing = dao.getProviderCache(providerId)
        dao.upsertProviderCache(
            HotListCacheEntity(
                providerId = providerId,
                providerName = existing?.providerName ?: providerName,
                itemsJson = existing?.itemsJson ?: json.encodeToString(emptyList<HotListItem>()),
                fetchedAt = existing?.fetchedAt ?: 0L,
                updatedAt = now,
                lastError = error.take(160),
            )
        )
    }

    suspend fun replaceTopics(topics: List<HotTopic>) {
        val now = System.currentTimeMillis()
        dao.replaceHotTopics(topics.map { topic ->
            HotTopicCacheEntity(
                topicId = topic.id,
                title = topic.title,
                sourcesJson = json.encodeToString(topic.sources),
                sourceCount = topic.sourceCount,
                bestRank = topic.bestRank,
                latestFetchedAt = topic.latestFetchedAt,
                updatedAt = now,
            )
        })
    }

    suspend fun upsertTopic(topic: HotTopic) {
        val now = System.currentTimeMillis()
        dao.upsertHotTopics(
            listOf(
                HotTopicCacheEntity(
                    topicId = topic.id,
                    title = topic.title,
                    sourcesJson = json.encodeToString(topic.sources),
                    sourceCount = topic.sourceCount,
                    bestRank = topic.bestRank,
                    latestFetchedAt = topic.latestFetchedAt,
                    updatedAt = now,
                )
            )
        )
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
        now: Long = System.currentTimeMillis(),
    ): DeepReadOutput? {
        dao.getDeepRead(topicId)?.let { direct ->
            direct.toFreshDeepRead(json, now)?.let { return it }
        }
        if (title.isBlank()) return null
        val fallback = dao.getFreshDeepReadByTitle(title, now) ?: return null
        val output = fallback.toFreshDeepRead(json, now) ?: return null
        if (fallback.topicId != topicId) {
            dao.upsertDeepRead(
                fallback.copy(
                    topicId = topicId,
                    title = title,
                    updatedAt = now,
                )
            )
        }
        return output
    }

    suspend fun saveDeepRead(
        topicId: String,
        title: String,
        output: DeepReadOutput,
        now: Long = System.currentTimeMillis(),
    ) {
        dao.upsertDeepRead(
            DeepReadCacheEntity(
                topicId = topicId,
                title = title,
                outputJson = json.encodeToString(output),
                createdAt = now,
                expiresAt = now + DEEP_READ_TTL_MS,
                updatedAt = now,
            )
        )
    }

    suspend fun clearDeepRead(topicId: String) = dao.deleteDeepRead(topicId)

    suspend fun pruneExpiredDeepReads(now: Long = System.currentTimeMillis()): Int =
        dao.pruneExpiredDeepReads(now - DEEP_READ_HISTORY_RETENTION_MS)

    suspend fun upsertSource(entity: HotListSourceEntity) = dao.upsertSource(entity)

    suspend fun deleteSource(id: String) = dao.deleteSource(id)

    companion object {
        const val DEEP_READ_TTL_MS = 24L * 60L * 60L * 1000L
        const val DEEP_READ_HISTORY_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L

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
    )

private fun DeepReadCacheEntity.toFreshDeepRead(json: Json, now: Long = System.currentTimeMillis()): DeepReadOutput? {
    if (!DeepReadCachePolicy.isFresh(expiresAt, now)) return null
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
        expired = !DeepReadCachePolicy.isFresh(expiresAt, now),
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
