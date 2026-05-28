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
import me.rerere.ai.provider.ProviderSetting
import app.amber.core.infra.AppScope
import app.amber.core.settings.DEFAULT_PROVIDERS
import app.amber.core.settings.PreferencesKeys
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.toMutableStateFlow

data class ProviderPrefsData(
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val imageModelsSeededVersion: Int = 0,
)

class ProviderPrefs(
    private val dataStore: DataStore<Preferences>,
    scope: AppScope,
) {
    internal val rawFlow: Flow<ProviderPrefsData> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { readFrom(it) }
        .distinctUntilChanged()

    val flow: StateFlow<ProviderPrefsData> = rawFlow
        .toMutableStateFlow(scope, ProviderPrefsData())

    suspend fun update(transform: (ProviderPrefsData) -> ProviderPrefsData) {
        dataStore.edit { p ->
            val current = readFrom(p)
            val next = transform(current)
            if (next == current) return@edit
            writeTo(p, next)
        }
    }

    private fun readFrom(p: Preferences): ProviderPrefsData = ProviderPrefsData(
        providers = p[PreferencesKeys.PROVIDERS]?.let {
            JsonInstant.decodeFromString<List<ProviderSetting>>(it)
        } ?: DEFAULT_PROVIDERS,
        imageModelsSeededVersion = if (p[PreferencesKeys.SEEDED_IMAGE_MODELS_V1] == true) 1 else 0,
    )

    private fun writeTo(p: MutablePreferences, data: ProviderPrefsData) {
        p[PreferencesKeys.PROVIDERS] = JsonInstant.encodeToString(data.providers)
        if (data.imageModelsSeededVersion > 0) {
            p[PreferencesKeys.SEEDED_IMAGE_MODELS_V1] = true
        }
    }
}
