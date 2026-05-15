package me.rerere.rikkahub.data.datastore.prefs

import android.content.Context
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.AgentRuntimeSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.settingsStore
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.toMutableStateFlow

data class AgentPrefsData(
    val agentRuntime: AgentRuntimeSetting = AgentRuntimeSetting(),
)

class AgentPrefs(
    context: Context,
    scope: AppScope,
) {
    private val dataStore = context.settingsStore

    val flow: StateFlow<AgentPrefsData> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { p ->
            AgentPrefsData(
                agentRuntime = p[SettingsStore.AGENT_RUNTIME]?.let {
                    JsonInstant.decodeFromString<AgentRuntimeSetting>(it)
                } ?: AgentRuntimeSetting(),
            )
        }
        .distinctUntilChanged()
        .toMutableStateFlow(scope, AgentPrefsData())

    suspend fun update(transform: (AgentPrefsData) -> AgentPrefsData) {
        val current = flow.value
        val next = transform(current)
        if (next == current) return
        dataStore.edit { p ->
            p[SettingsStore.AGENT_RUNTIME] = JsonInstant.encodeToString(next.agentRuntime)
        }
    }
}
