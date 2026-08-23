package app.amber.core.settings.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.amber.core.infra.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsAggregatorConcurrencyTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `concurrent transform updates do not overwrite an older snapshot`() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val appScope = AppScope()
        val aggregator = SettingsAggregator(
            dataStore = dataStore,
            uiPrefs = UIPrefs(dataStore, appScope),
            searchPrefs = SearchPrefs(dataStore, appScope),
            agentPrefs = AgentPrefs(dataStore, appScope),
            providerPrefs = ProviderPrefs(dataStore, appScope),
            chatPrefs = ChatPrefs(dataStore, appScope),
            extensionPrefs = ExtensionPrefs(dataStore, appScope),
            assistantPrefs = AssistantPrefs(dataStore, appScope),
            scope = appScope,
        )

        try {
            withTimeout(5_000) {
                aggregator.settingsFlow.first { !it.init }
            }

            (1..100).map {
                async(Dispatchers.Default) {
                    aggregator.update { current ->
                        current.copy(launchCount = current.launchCount + 1)
                    }
                }
            }.awaitAll()

            assertEquals(100, aggregator.settingsFlow.value.launchCount)
        } finally {
            appScope.cancel()
        }
    }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock {
                transform(state.value).also { state.value = it }
            }
    }
}
