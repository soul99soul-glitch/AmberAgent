package app.amber.core.settings.prefs

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
import app.amber.core.infra.AppScope
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.DEFAULT_PRESET_THEME_ID
import app.amber.core.settings.DisplaySetting
import app.amber.core.settings.PreferencesKeys
import app.amber.core.settings.toMutableStateFlow

data class UIPrefsData(
    val dynamicColor: Boolean = false,
    val themeId: String = DEFAULT_PRESET_THEME_ID,
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
        themeId = p[PreferencesKeys.THEME_ID] ?: DEFAULT_PRESET_THEME_ID,
        developerMode = p[PreferencesKeys.DEVELOPER_MODE] == true,
        displaySetting = (p[PreferencesKeys.DISPLAY_SETTING] ?: "{}")
            .decodeJsonOrNull<DisplaySetting>() ?: DisplaySetting(),
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
