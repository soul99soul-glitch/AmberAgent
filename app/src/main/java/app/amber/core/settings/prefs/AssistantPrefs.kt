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
import me.rerere.rikkahub.AppScope
import app.amber.core.model.DEFAULT_ASSISTANT_ID
import app.amber.core.settings.PreferencesKeys
import app.amber.core.model.Assistant
import app.amber.core.model.Tag
import app.amber.core.utils.JsonInstant
import app.amber.core.utils.toMutableStateFlow
import kotlin.uuid.Uuid

data class AssistantPrefsData(
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val assistants: List<Assistant> = emptyList(),
    val assistantTags: List<Tag> = emptyList(),
)

class AssistantPrefs(
    private val dataStore: DataStore<Preferences>,
    scope: AppScope,
) {
    internal val rawFlow: Flow<AssistantPrefsData> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { readFrom(it) }
        .distinctUntilChanged()

    val flow: StateFlow<AssistantPrefsData> = rawFlow
        .toMutableStateFlow(scope, AssistantPrefsData())

    suspend fun update(transform: (AssistantPrefsData) -> AssistantPrefsData) {
        dataStore.edit { p ->
            val current = readFrom(p)
            val next = transform(current)
            if (next == current) return@edit
            writeTo(p, next)
        }
    }

    private fun readFrom(p: Preferences): AssistantPrefsData = AssistantPrefsData(
        assistantId = p[PreferencesKeys.SELECT_ASSISTANT]?.let { Uuid.parse(it) }
            ?: DEFAULT_ASSISTANT_ID,
        assistants = JsonInstant.decodeFromString<List<Assistant>>(
            p[PreferencesKeys.ASSISTANTS] ?: "[]"
        ),
        assistantTags = p[PreferencesKeys.ASSISTANT_TAGS]?.let {
            JsonInstant.decodeFromString<List<Tag>>(it)
        } ?: emptyList(),
    )

    private fun writeTo(p: MutablePreferences, data: AssistantPrefsData) {
        p[PreferencesKeys.SELECT_ASSISTANT] = data.assistantId.toString()
        p[PreferencesKeys.ASSISTANTS] = JsonInstant.encodeToString(data.assistants)
        p[PreferencesKeys.ASSISTANT_TAGS] = JsonInstant.encodeToString(data.assistantTags)
    }
}
