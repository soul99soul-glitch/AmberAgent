package me.rerere.rikkahub.data.datastore.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.PreferencesKeys
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.toMutableStateFlow

data class UIPrefsData(
    val dynamicColor: Boolean = false,
    val themeId: String = PresetThemes[0].id,
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val launchCount: Int = 0,
    val sponsorAlertDismissedAt: Int = 0,
)

class UIPrefs(
    private val dataStore: DataStore<Preferences>,
    scope: AppScope,
) {
    internal val rawFlow: Flow<UIPrefsData> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { readFrom(it) }
        .distinctUntilChanged()

    val flow: StateFlow<UIPrefsData> = rawFlow
        .toMutableStateFlow(scope, UIPrefsData())

    suspend fun update(transform: (UIPrefsData) -> UIPrefsData) {
        dataStore.edit { p ->
            val current = readFrom(p)
            val next = transform(current)
            if (next == current) return@edit
            writeTo(p, next)
        }
    }

    private fun readFrom(p: Preferences): UIPrefsData = UIPrefsData(
        dynamicColor = p[PreferencesKeys.DYNAMIC_COLOR] ?: false,
        themeId = p[PreferencesKeys.THEME_ID] ?: PresetThemes[0].id,
        developerMode = p[PreferencesKeys.DEVELOPER_MODE] == true,
        displaySetting = JsonInstant.decodeFromString(
            p[PreferencesKeys.DISPLAY_SETTING] ?: "{}"
        ),
        launchCount = p[PreferencesKeys.LAUNCH_COUNT] ?: 0,
        sponsorAlertDismissedAt = p[PreferencesKeys.SPONSOR_ALERT_DISMISSED_AT] ?: 0,
    )

    private fun writeTo(p: androidx.datastore.preferences.core.MutablePreferences, data: UIPrefsData) {
        p[PreferencesKeys.DYNAMIC_COLOR] = data.dynamicColor
        p[PreferencesKeys.THEME_ID] = data.themeId
        p[PreferencesKeys.DEVELOPER_MODE] = data.developerMode
        p[PreferencesKeys.DISPLAY_SETTING] = JsonInstant.encodeToString(data.displaySetting)
        p[PreferencesKeys.LAUNCH_COUNT] = data.launchCount
        p[PreferencesKeys.SPONSOR_ALERT_DISMISSED_AT] = data.sponsorAlertDismissedAt
    }
}
