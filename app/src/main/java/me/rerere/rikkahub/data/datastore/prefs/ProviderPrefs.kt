package me.rerere.rikkahub.data.datastore.prefs

import android.content.Context
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.settingsStore
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.toMutableStateFlow

data class ProviderPrefsData(
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val imageModelsSeededVersion: Int = 0,
)

class ProviderPrefs(
    context: Context,
    scope: AppScope,
) {
    private val dataStore = context.settingsStore

    val flow: StateFlow<ProviderPrefsData> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { p ->
            ProviderPrefsData(
                providers = p[SettingsStore.PROVIDERS]?.let {
                    JsonInstant.decodeFromString<List<ProviderSetting>>(it)
                } ?: DEFAULT_PROVIDERS,
                imageModelsSeededVersion = if (p[SettingsStore.SEEDED_IMAGE_MODELS_V1] == true) 1 else 0,
            )
        }
        .distinctUntilChanged()
        .toMutableStateFlow(scope, ProviderPrefsData())

    suspend fun update(transform: (ProviderPrefsData) -> ProviderPrefsData) {
        val current = flow.value
        val next = transform(current)
        if (next == current) return
        dataStore.edit { p ->
            p[SettingsStore.PROVIDERS] = JsonInstant.encodeToString(next.providers)
            if (next.imageModelsSeededVersion > 0) {
                p[SettingsStore.SEEDED_IMAGE_MODELS_V1] = true
            }
        }
    }
}
