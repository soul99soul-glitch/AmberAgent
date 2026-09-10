package app.amber.feature.board.hotlist

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.entity.DeepReadCacheEntity
import app.amber.agent.data.db.entity.HotListCacheEntity
import app.amber.agent.data.db.entity.HotListSourceEntity
import app.amber.agent.data.db.entity.HotTopicCacheEntity
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.sync.core.SyncRestoreWriteRejectedException
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HotListRestoreWriteTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var database: AppDatabase
    private lateinit var restoreWriteGate: SyncRestoreWriteGate
    private lateinit var repository: HotListRepository

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        restoreWriteGate = SyncRestoreWriteGate()
        repository = HotListRepository(database.hotListDao(), json, restoreWriteGate)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `pre-restore hot list owner cannot overwrite restored source cache or deep read`() = runBlocking {
        val oldEpoch = restoreWriteGate.currentEpoch()
        val ownerReachedModelBarrier = CompletableDeferred<Unit>()
        val releaseOwner = CompletableDeferred<Unit>()
        val restoredSource = source("restored-source", "恢复后的自定义源")
        val restoredProvider = provider("restored-provider", "恢复后的热榜")
        val restoredTopic = topic("restored-topic", "恢复后的话题")
        val restoredDeepRead = deepRead("restored-deep-read", "恢复后的深读", "恢复后的正文")
        val staleSource = restoredSource.copy(displayName = "旧 owner 源", updatedAt = 2L)
        val staleProviderResult = HotListResult(
            items = listOf(HotListItem(rank = 1, title = "旧 owner 热榜")),
            fetchedAt = 2L,
        )
        val staleTopic = HotTopic(
            id = restoredTopic.topicId,
            title = "旧 owner 话题",
            sources = emptyList(),
            sourceCount = 0,
            bestRank = Int.MAX_VALUE,
            latestFetchedAt = 2L,
        )

        supervisorScope {
            val owner = async {
                withContext(SyncRestoreWriteEpoch(oldEpoch)) {
                    ownerReachedModelBarrier.complete(Unit)
                    releaseOwner.await()
                    val providerFailure = runCatching {
                        repository.saveProviderResult(
                            providerId = restoredProvider.providerId,
                            providerName = "旧 owner 热榜",
                            result = staleProviderResult,
                        )
                    }.exceptionOrNull()
                    val topicFailure = runCatching {
                        repository.replaceTopics(listOf(staleTopic))
                    }.exceptionOrNull()
                    val deepReadFailure = runCatching {
                        repository.saveDeepRead(
                            topicId = restoredDeepRead.topicId,
                            title = "旧 owner 深读",
                            output = DeepReadOutput(summary = "旧 owner 正文"),
                            now = 2L,
                            ttlDays = 0,
                        )
                    }.exceptionOrNull()
                    val sourceUpsertFailure = runCatching {
                        repository.upsertSource(staleSource)
                    }.exceptionOrNull()
                    val sourceDeleteFailure = runCatching {
                        repository.deleteSource(restoredSource.id)
                    }.exceptionOrNull()
                    listOf(
                        providerFailure,
                        topicFailure,
                        deepReadFailure,
                        sourceUpsertFailure,
                        sourceDeleteFailure,
                    )
                }
            }

            ownerReachedModelBarrier.await()
            restoreWriteGate.withRestore {
                database.hotListDao().upsertSource(restoredSource)
                database.hotListDao().upsertProviderCache(restoredProvider)
                database.hotListDao().replaceHotTopics(listOf(restoredTopic))
                database.hotListDao().upsertDeepRead(restoredDeepRead)
                restoreWriteGate.markDataCommitted()
            }

            releaseOwner.complete(Unit)
            owner.await().forEach { failure ->
                assertTrue(failure is SyncRestoreWriteRejectedException)
            }

            assertEquals(restoredSource, database.hotListDao().observeSources().first().single())
            assertEquals(restoredProvider, database.hotListDao().getProviderCache(restoredProvider.providerId))
            assertEquals(restoredTopic, database.hotListDao().getHotTopic(restoredTopic.topicId))
            assertEquals(restoredDeepRead, database.hotListDao().getDeepRead(restoredDeepRead.topicId))
        }
    }

    @Test
    fun `current hot list owner writes after restore`() = runBlocking {
        val restoredSource = source("restored-source", "恢复后的自定义源")
        val restoredProvider = provider("restored-provider", "恢复后的热榜")
        val restoredDeepRead = deepRead("restored-deep-read", "恢复后的深读", "恢复后的正文")
        restoreWriteGate.withRestore {
            database.hotListDao().upsertSource(restoredSource)
            database.hotListDao().upsertProviderCache(restoredProvider)
            database.hotListDao().upsertDeepRead(restoredDeepRead)
            restoreWriteGate.markDataCommitted()
        }

        val currentEpoch = restoreWriteGate.currentEpoch()
        val currentSource = restoredSource.copy(displayName = "当前源", updatedAt = 3L)
        val currentTopic = HotTopic(
            id = "current-topic",
            title = "当前话题",
            sources = emptyList(),
            sourceCount = 0,
            bestRank = Int.MAX_VALUE,
            latestFetchedAt = 3L,
        )
        withContext(SyncRestoreWriteEpoch(currentEpoch)) {
            repository.upsertSource(currentSource)
            repository.saveProviderResult(
                providerId = restoredProvider.providerId,
                providerName = "当前热榜",
                result = HotListResult(
                    items = listOf(HotListItem(rank = 1, title = "当前热榜")),
                    fetchedAt = 3L,
                ),
            )
            repository.replaceTopics(listOf(currentTopic))
            repository.saveDeepRead(
                topicId = restoredDeepRead.topicId,
                title = "当前深读",
                output = DeepReadOutput(summary = "当前正文"),
                now = 3L,
                ttlDays = 0,
            )
        }

        assertEquals(currentSource, database.hotListDao().observeSources().first().single())
        assertEquals("当前热榜", database.hotListDao().getProviderCache(restoredProvider.providerId)?.providerName)
        val persistedTopic = database.hotListDao().getHotTopic(currentTopic.id)!!
        assertEquals(currentTopic.id, persistedTopic.topicId)
        assertEquals(currentTopic.title, persistedTopic.title)
        assertEquals(currentTopic.latestFetchedAt, persistedTopic.latestFetchedAt)
        assertEquals("当前深读", database.hotListDao().getDeepRead(restoredDeepRead.topicId)?.title)
    }

    private fun source(id: String, name: String) = HotListSourceEntity(
        id = id,
        displayName = name,
        sourceType = "rss",
        url = "https://example.com/$id",
        enabled = true,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun provider(id: String, name: String) = HotListCacheEntity(
        providerId = id,
        providerName = name,
        itemsJson = json.encodeToString(
            ListSerializer(HotListItem.serializer()),
            listOf(HotListItem(rank = 1, title = name)),
        ),
        fetchedAt = 1L,
        updatedAt = 1L,
    )

    private fun topic(id: String, title: String) = HotTopicCacheEntity(
        topicId = id,
        title = title,
        sourcesJson = json.encodeToString(emptyList<HotTopicSource>()),
        sourceCount = 0,
        bestRank = Int.MAX_VALUE,
        latestFetchedAt = 1L,
        updatedAt = 1L,
    )

    private fun deepRead(id: String, title: String, summary: String) = DeepReadCacheEntity(
        topicId = id,
        title = title,
        outputJson = json.encodeToString(DeepReadOutput.serializer(), DeepReadOutput(summary = summary)),
        createdAt = 1L,
        expiresAt = Long.MAX_VALUE,
        updatedAt = 1L,
    )
}
