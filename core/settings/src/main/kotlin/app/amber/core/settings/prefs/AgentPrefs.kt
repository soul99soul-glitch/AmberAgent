package app.amber.core.settings.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.amber.core.infra.AppScope
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.PreferencesKeys
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.toMutableStateFlow

data class AgentPrefsData(
    val agentRuntime: AgentRuntimeSetting = AgentRuntimeSetting(),
)

class AgentPrefs(
    private val dataStore: DataStore<Preferences>,
    scope: AppScope,
) {
    internal val rawFlow: Flow<AgentPrefsData> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { readFrom(it) }
        .distinctUntilChanged()

    val flow: StateFlow<AgentPrefsData> = rawFlow
        .toMutableStateFlow(scope, AgentPrefsData())

    suspend fun update(transform: (AgentPrefsData) -> AgentPrefsData) {
        dataStore.edit { p ->
            val current = readFrom(p)
            val next = transform(current)
            if (next == current) return@edit
            writeTo(p, next)
        }
    }

    private fun readFrom(p: Preferences): AgentPrefsData = AgentPrefsData(
        agentRuntime = p[PreferencesKeys.AGENT_RUNTIME]?.let {
            JsonInstant.decodeFromString<AgentRuntimeSetting>(it)
        } ?: AgentRuntimeSetting(),
    )

    private fun writeTo(p: MutablePreferences, data: AgentPrefsData) {
        p[PreferencesKeys.AGENT_RUNTIME] = JsonInstant.encodeToString(data.agentRuntime)
    }
}
