package app.amber.review

import android.app.Application
import androidx.room.Room
import app.amber.agent.data.db.AppDatabase
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ProviderSetting
import app.amber.agent.data.db.dao.HotListDAO
import app.amber.agent.data.db.entity.DeepReadCacheEntity
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.feature.board.hotlist.HotListRepository
import app.amber.feature.board.hotlist.deepread.DeepReadAgentRunManager
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.feature.board.hotlist.deepread.DeepReadSourcePrefetcher
import okhttp3.OkHttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeepReadMetadataTest {
    private lateinit var database: AppDatabase
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test
    fun forceRunKeepsPinnedAndSourceMetadataWhenNoModelCanRun() = runBlocking {
        val repository = HotListRepository(database.hotListDao(), json)
        repository.saveDeepRead("topic", "Topic", DeepReadOutput(summary = "Existing content"), ttlDays = 0,
            sourceUrl = "https://example.com/article")
        repository.setDeepReadPinned("topic", true)
        val settings = ReflectionFixture.allocate<SettingsAggregator>()
        ReflectionFixture.set(settings, "_settingsFlow", MutableStateFlow(Settings(providers = emptyList())))
        val manager = ReflectionFixture.allocate<DeepReadAgentRunManager>()
        ReflectionFixture.set(manager, "appContext", RuntimeEnvironment.getApplication())
        ReflectionFixture.set(manager, "settingsStore", settings)
        ReflectionFixture.set(manager, "hotListRepository", repository)
        ReflectionFixture.set(manager, "mutexes", ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>())

        val result = manager.run("topic", "Topic", force = true)

        assertTrue(result.isFailure)
        val persisted = database.hotListDao().getDeepRead("topic")
        assertNotNull("Regeneration must not delete the user's bookmarked record", persisted)
        assertTrue(persisted!!.pinned)
        assertEquals("https://example.com/article", persisted.sourceUrl)
    }

    @Test
    fun admittedForceRunResetsOnlyContentAndDoesNotReuseAnotherTitlesCache() = runBlocking {
        val repository = HotListRepository(database.hotListDao(), json)
        repository.saveDeepRead("topic", "Topic", DeepReadOutput(summary = "Old content"), ttlDays = 0,
            sourceUrl = "https://example.com/article")
        repository.setDeepReadPinned("topic", true)
        repository.saveDeepRead("other-id", "Topic", DeepReadOutput(summary = "Duplicate title cache"), ttlDays = 0)
        val model = Model(modelId = "test", abilities = listOf(ModelAbility.TOOL))
        val settingsValue = Settings(chatModelId = model.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
            searchServices = emptyList(), searchEnabledServiceIds = emptyList())
        val settings = ReflectionFixture.allocate<SettingsAggregator>()
        ReflectionFixture.set(settings, "_settingsFlow", MutableStateFlow(settingsValue))
        val manager = ReflectionFixture.allocate<DeepReadAgentRunManager>()
        ReflectionFixture.set(manager, "appContext", RuntimeEnvironment.getApplication())
        ReflectionFixture.set(manager, "settingsStore", settings)
        ReflectionFixture.set(manager, "hotListRepository", repository)
        ReflectionFixture.set(manager, "mutexes", ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>())
        // No search services or topic seeds: actual prefetch returns empty without network.
        ReflectionFixture.set(manager, "sourcePrefetcher", DeepReadSourcePrefetcher(settings, repository, OkHttpClient()))

        assertTrue(manager.run("topic", "Topic", force = true).isFailure)

        val persisted = database.hotListDao().getDeepRead("topic")!!
        assertTrue(persisted.pinned)
        assertEquals("https://example.com/article", persisted.sourceUrl)
        assertEquals("", repository.getFreshDeepRead("topic", title = "Topic")!!.summary)
        assertEquals("Duplicate title cache", repository.getFreshDeepRead("other-id")!!.summary)
    }

    @Test fun generationWritePreservesUserPinCommittedWhileWaitingToWrite() = pinWhileWriting(false, true)
    @Test fun generationWritePreservesUserUnpinCommittedWhileWaitingToWrite() = pinWhileWriting(true, false)

    private fun pinWhileWriting(original: Boolean, latest: Boolean) = runBlocking {
        val dao = database.hotListDao()
        dao.upsertDeepRead(DeepReadCacheEntity(
            topicId = "topic", title = "Topic", outputJson = "{}", createdAt = 1,
            expiresAt = Long.MAX_VALUE, updatedAt = 1, pinned = original,
            sourceUrl = "https://example.com/article",
        ))
        // Remove Room's query-dispatch suspension so UNDISPATCHED reaches the
        // held production write gate deterministically; all storage remains real Room.
        val synchronousReads = object : HotListDAO by dao {
            override suspend fun getDeepRead(topicId: String): DeepReadCacheEntity? =
                runBlocking { dao.getDeepRead(topicId) }
        }
        val gate = SyncRestoreWriteGate()
        val repository = HotListRepository(synchronousReads, json, gate)
        val holdingGate = CompletableDeferred<Unit>()
        val commitUserChoice = CompletableDeferred<Unit>()
        val userWrite = async {
            gate.withWriter {
                holdingGate.complete(Unit)
                commitUserChoice.await()
                dao.setDeepReadPinned("topic", latest)
            }
        }
        holdingGate.await()
        val generationWrite = async(start = CoroutineStart.UNDISPATCHED) {
            repository.saveDeepRead("topic", "Topic", DeepReadOutput(summary = "New content"), ttlDays = 0)
        }
        commitUserChoice.complete(Unit)
        userWrite.await()
        generationWrite.await()

        val saved = dao.getDeepRead("topic")!!
        assertEquals(latest, saved.pinned)
        assertEquals("https://example.com/article", saved.sourceUrl)
        assertEquals("New content", repository.getFreshDeepRead("topic")!!.summary)
    }
}
