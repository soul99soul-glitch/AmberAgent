package me.rerere.rikkahub.data.agent.board.hotlist

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.CorePoint
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadOutput
import me.rerere.rikkahub.data.db.dao.HotListDAO
import me.rerere.rikkahub.data.db.entity.DeepReadCacheEntity
import me.rerere.rikkahub.data.db.entity.HotListCacheEntity
import me.rerere.rikkahub.data.db.entity.HotListSourceEntity
import me.rerere.rikkahub.data.db.entity.HotTopicCacheEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DeepReadRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun getFreshDeepReadHonorsTtlAndDecodesJson() = runTest {
        val repo = HotListRepository(FakeHotListDao(), json)
        val output = DeepReadOutput(
            summary = "这是一个有明确正文的深度阅读摘要。",
            corePoints = listOf(CorePoint("核心论点")),
        )

        repo.saveDeepRead("topic", "Topic", output, now = 1_000L)

        assertNotNull(repo.getFreshDeepRead("topic", now = 1_000L + HotListRepository.DEEP_READ_TTL_MS - 1))
        assertNull(repo.getFreshDeepRead("topic", now = 1_000L + HotListRepository.DEEP_READ_TTL_MS + 1))
    }

    @Test
    fun getFreshDeepReadReturnsNullForInvalidJson() = runTest {
        val dao = FakeHotListDao()
        dao.upsertDeepRead(
            DeepReadCacheEntity(
                topicId = "bad",
                title = "Bad",
                outputJson = "{not-json",
                createdAt = 1_000L,
                expiresAt = 2_000L,
                updatedAt = 1_000L,
            )
        )
        val repo = HotListRepository(dao, json)

        assertNull(repo.getFreshDeepRead("bad", now = 1_500L))
    }

    @Test
    fun getFreshDeepReadFallsBackToFreshTitleWhenTopicIdChanges() = runTest {
        val repo = HotListRepository(FakeHotListDao(), json)
        val output = DeepReadOutput(summary = "这是缓存正文。")

        repo.saveDeepRead("old-topic-id", "同一个热榜话题", output, now = 1_000L)

        val cached = repo.getFreshDeepRead(
            topicId = "new-topic-id",
            title = "同一个热榜话题",
            now = 1_000L + HotListRepository.DEEP_READ_TTL_MS - 1,
        )

        assertEquals("这是缓存正文。", cached?.summary)
    }
}

private class FakeHotListDao : HotListDAO {
    private val providerCaches = MutableStateFlow<List<HotListCacheEntity>>(emptyList())
    private val hotTopics = MutableStateFlow<List<HotTopicCacheEntity>>(emptyList())
    private val sources = MutableStateFlow<List<HotListSourceEntity>>(emptyList())
    private val deepReads = mutableMapOf<String, DeepReadCacheEntity>()
    private val deepReadFlows = mutableMapOf<String, MutableStateFlow<DeepReadCacheEntity?>>()

    override fun observeProviderCaches(): Flow<List<HotListCacheEntity>> = providerCaches

    override suspend fun getProviderCache(providerId: String): HotListCacheEntity? =
        providerCaches.value.firstOrNull { it.providerId == providerId }

    override suspend fun upsertProviderCache(entity: HotListCacheEntity) {
        providerCaches.value = providerCaches.value.filterNot { it.providerId == entity.providerId } + entity
    }

    override fun observeHotTopics(limit: Int): Flow<List<HotTopicCacheEntity>> =
        hotTopics.map { it.take(limit) }

    override suspend fun getHotTopic(topicId: String): HotTopicCacheEntity? =
        hotTopics.value.firstOrNull { it.topicId == topicId }

    override suspend fun upsertHotTopics(entities: List<HotTopicCacheEntity>) {
        hotTopics.value = entities
    }

    override suspend fun clearHotTopics() {
        hotTopics.value = emptyList()
    }

    override suspend fun getDeepRead(topicId: String): DeepReadCacheEntity? = deepReads[topicId]

    override suspend fun getFreshDeepReadByTitle(title: String, now: Long): DeepReadCacheEntity? =
        deepReads.values
            .filter { it.title == title && it.expiresAt >= now }
            .maxByOrNull { it.updatedAt }

    override fun observeDeepRead(topicId: String): Flow<DeepReadCacheEntity?> =
        deepReadFlows.getOrPut(topicId) { MutableStateFlow(deepReads[topicId]) }

    override suspend fun upsertDeepRead(entity: DeepReadCacheEntity) {
        deepReads[entity.topicId] = entity
        deepReadFlows.getOrPut(entity.topicId) { MutableStateFlow(null) }.value = entity
    }

    override suspend fun deleteDeepRead(topicId: String) {
        deepReads.remove(topicId)
        deepReadFlows.getOrPut(topicId) { MutableStateFlow(null) }.value = null
    }

    override suspend fun pruneExpiredDeepReads(now: Long): Int {
        val expired = deepReads.values.filter { it.expiresAt < now }
        expired.forEach { deleteDeepRead(it.topicId) }
        return expired.size
    }

    override fun observeSources(): Flow<List<HotListSourceEntity>> = sources

    override suspend fun getEnabledSources(): List<HotListSourceEntity> =
        sources.value.filter { it.enabled }

    override suspend fun upsertSource(entity: HotListSourceEntity) {
        sources.value = sources.value.filterNot { it.id == entity.id } + entity
    }

    override suspend fun deleteSource(id: String) {
        sources.value = sources.value.filterNot { it.id == id }
    }
}
