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
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.settingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.toMutableStateFlow
import kotlin.uuid.Uuid

data class AssistantPrefsData(
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val assistants: List<Assistant> = emptyList(),
    val assistantTags: List<Tag> = emptyList(),
)

class AssistantPrefs(
    context: Context,
    scope: AppScope,
) {
    private val dataStore = context.settingsStore

    val flow: StateFlow<AssistantPrefsData> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { p ->
            AssistantPrefsData(
                assistantId = p[SettingsStore.SELECT_ASSISTANT]?.let { Uuid.parse(it) }
                    ?: DEFAULT_ASSISTANT_ID,
                assistants = JsonInstant.decodeFromString<List<Assistant>>(
                    p[SettingsStore.ASSISTANTS] ?: "[]"
                ),
                assistantTags = p[SettingsStore.ASSISTANT_TAGS]?.let {
                    JsonInstant.decodeFromString<List<Tag>>(it)
                } ?: emptyList(),
            )
        }
        .distinctUntilChanged()
        .toMutableStateFlow(scope, AssistantPrefsData())

    suspend fun update(transform: (AssistantPrefsData) -> AssistantPrefsData) {
        val current = flow.value
        val next = transform(current)
        if (next == current) return
        dataStore.edit { p ->
            p[SettingsStore.SELECT_ASSISTANT] = next.assistantId.toString()
            p[SettingsStore.ASSISTANTS] = JsonInstant.encodeToString(next.assistants)
            p[SettingsStore.ASSISTANT_TAGS] = JsonInstant.encodeToString(next.assistantTags)
        }
    }
}
