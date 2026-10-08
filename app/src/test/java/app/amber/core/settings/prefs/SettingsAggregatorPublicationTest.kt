package app.amber.core.settings.prefs

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.infra.AppScope
import app.amber.core.settings.DEFAULT_AUTO_MODEL_ID
import app.amber.core.settings.PreferencesKeys
import app.amber.core.settings.secret.SecretCipher
import app.amber.core.settings.secret.SecretRedactor
import app.amber.core.settings.secret.SecretStore
import app.amber.core.settings.secret.SecretStoreBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsAggregatorPublicationTest {
    private lateinit var mainDispatcher: QueuedMainDispatcher

    @Before
    fun setUp() {
        mainDispatcher = QueuedMainDispatcher()
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `acknowledged write publishes canonical settings before delayed domain notifications`() = runBlocking {
        val dataStore = HeldNotificationDataStore()
        val aggregator = buildAggregator(dataStore)
        awaitInitialization(dataStore, aggregator, mainDispatcher.lastQueuedJob())
        val danglingModelId = Uuid.parse("71000000-0000-4000-8000-000000000001")

        // The durable write succeeds while all decoded domain notifications stay held.
        withTimeout(5_000) {
            aggregator.update {
                it.copy(
                    providers = emptyList(),
                    chatModelId = danglingModelId,
                    agentRuntime = it.agentRuntime.copy(agentSoulMarkdown = "latest soul"),
                )
            }
        }

        assertEquals(DEFAULT_AUTO_MODEL_ID, aggregator.settingsFlow.value.chatModelId)
        assertEquals("latest soul", aggregator.settingsFlow.value.agentRuntime.agentSoulMarkdown)
    }

    @Test
    fun `queued older projection cannot overwrite a later acknowledged write`() = runBlocking {
        val dataStore = HeldNotificationDataStore()
        val aggregator = buildAggregator(dataStore)
        // Aggregator starts its publishing coroutine after constructing the six prefs.
        // Capture that coroutine's Job before running any queued Main work.
        val publisherJob = mainDispatcher.lastQueuedJob()
        awaitInitialization(dataStore, aggregator, publisherJob)

        val queuedSoul = "previous queued projection"
        dataStore.edit {
            it[PreferencesKeys.AGENT_RUNTIME] = JsonInstant.encodeToString(
                aggregator.settingsFlow.value.agentRuntime.copy(agentSoulMarkdown = queuedSoul),
            )
        }
        dataStore.releaseNotifications()

        // Drive the decoded domain work until aggregate publication is queued on Main.
        // Keep that exact continuation suspended while a later durable write succeeds.
        val oldPublication = withTimeout(5_000) { mainDispatcher.pauseAt(publisherJob) }
        withTimeout(5_000) {
            aggregator.update {
                it.copy(agentRuntime = it.agentRuntime.copy(agentSoulMarkdown = "latest acknowledged soul"))
            }
        }
        assertEquals("latest acknowledged soul", aggregator.settingsFlow.value.agentRuntime.agentSoulMarkdown)

        oldPublication.run()

        assertEquals("latest acknowledged soul", aggregator.settingsFlow.value.agentRuntime.agentSoulMarkdown)
    }

    private suspend fun awaitInitialization(
        dataStore: HeldNotificationDataStore,
        aggregator: SettingsAggregator,
        publisherJob: Job,
    ) {
        withTimeout(5_000) { mainDispatcher.runUntil { !aggregator.settingsFlow.value.init } }
        // The canonical collector decodes off Main while holding writeMutex.
        // init=false can become visible before its initial Main continuation
        // releases that lock. Finish the initial emit before staging a notification.
        if (dataStore.hasCollectorWithin(publisherJob)) {
            withTimeout(5_000) {
                mainDispatcher.runUntil { dataStore.finishedInitialEmissionWithin(publisherJob) }
            }
        }
    }

    private fun buildAggregator(dataStore: DataStore<Preferences>): SettingsAggregator {
        val scope = AppScope()
        val secretStore = SecretStore(
            backend = object : SecretStoreBackend {
                private val values = mutableMapOf<String, String>()
                override fun get(key: String): String? = values[key]
                override fun put(key: String, value: String) { values[key] = value }
                override fun remove(key: String) { values.remove(key) }
                override fun keys(): Set<String> = values.keys.toSet()
            },
            cipher = object : SecretCipher {
                override fun encrypt(plaintext: String): String = plaintext
                override fun decrypt(stored: String): String = stored
            },
        )
        return SettingsAggregator(
            dataStore = dataStore,
            uiPrefs = UIPrefs(dataStore, scope),
            searchPrefs = SearchPrefs(dataStore, scope, secretStore),
            agentPrefs = AgentPrefs(dataStore, scope),
            providerPrefs = ProviderPrefs(dataStore, scope, secretStore),
            chatPrefs = ChatPrefs(dataStore, scope, secretStore),
            extensionPrefs = ExtensionPrefs(dataStore, scope, secretStore),
            scope = scope,
            secretRedactor = SecretRedactor(secretStore),
        )
    }

    /** Durable reads are current; long-lived collectors receive only released notifications. */
    private class HeldNotificationDataStore : DataStore<Preferences> {
        @Volatile private var persisted: Preferences = emptyPreferences()
        private val writeMutex = Mutex()
        private val collectors = mutableListOf<Channel<Preferences>>()
        private val collectorJobs = mutableListOf<Job>()
        private val finishedInitialEmissions = mutableListOf<Job>()

        override val data: Flow<Preferences> = flow {
            val notifications = Channel<Preferences>(Channel.UNLIMITED)
            val collectorJob = requireNotNull(currentCoroutineContext()[Job])
            synchronized(collectors) {
                collectors += notifications
                collectorJobs += collectorJob
            }
            try {
                emit(persisted)
                synchronized(collectors) { finishedInitialEmissions += collectorJob }
                for (snapshot in notifications) emit(snapshot)
            } finally {
                synchronized(collectors) {
                    collectors -= notifications
                    collectorJobs -= collectorJob
                }
                notifications.close()
            }
        }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            writeMutex.withLock {
                transform(persisted).also { persisted = it }
            }

        fun releaseNotifications() {
            val snapshot = persisted
            synchronized(collectors) { collectors.toList() }.forEach { it.trySend(snapshot).getOrThrow() }
        }

        fun hasCollectorWithin(job: Job): Boolean =
            synchronized(collectors) { collectorJobs.any { job.owns(it) } }

        fun finishedInitialEmissionWithin(job: Job): Boolean =
            synchronized(collectors) { finishedInitialEmissions.any { job.owns(it) } }
    }

    /** Explicit Main queue: background decoding may finish, but publication cannot run early. */
    private class QueuedMainDispatcher : MainCoroutineDispatcher() {
        private data class Task(val context: CoroutineContext, val runnable: Runnable)
        private val tasks = mutableListOf<Task>()
        private val ready = Channel<Unit>(Channel.CONFLATED)
        override val immediate: MainCoroutineDispatcher get() = this

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(tasks) { tasks += Task(context, block) }
            ready.trySend(Unit)
        }

        fun lastQueuedJob(): Job = synchronized(tasks) { requireNotNull(tasks.last().context[Job]) }

        suspend fun runUntil(predicate: () -> Boolean) {
            while (!predicate()) nextTask().runnable.run()
        }

        suspend fun pauseAt(job: Job): Runnable {
            while (true) {
                val next = nextTask()
                // flowOn collects inside a child ScopeCoroutine. Its resumed
                // continuation therefore carries that child Job, not the launch Job.
                if (job.owns(next.context[Job])) return next.runnable
                next.runnable.run()
            }
        }

        private suspend fun nextTask(): Task {
            while (true) {
                synchronized(tasks) {
                    if (tasks.isNotEmpty()) return tasks.removeAt(0)
                }
                ready.receive()
            }
        }
    }
}

private fun Job.owns(candidate: Job?): Boolean =
    this === candidate || children.any { it.owns(candidate) }
