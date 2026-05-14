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
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.settingsStore
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
    context: Context,
    scope: AppScope,
) {
    private val dataStore = context.settingsStore

    val flow: StateFlow<UIPrefsData> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { p ->
            UIPrefsData(
                dynamicColor = p[SettingsStore.DYNAMIC_COLOR] ?: false,
                themeId = p[SettingsStore.THEME_ID] ?: PresetThemes[0].id,
                developerMode = p[SettingsStore.DEVELOPER_MODE] == true,
                displaySetting = JsonInstant.decodeFromString(
                    p[SettingsStore.DISPLAY_SETTING] ?: "{}"
                ),
                launchCount = p[SettingsStore.LAUNCH_COUNT] ?: 0,
                sponsorAlertDismissedAt = p[SettingsStore.SPONSOR_ALERT_DISMISSED_AT] ?: 0,
            )
        }
        .distinctUntilChanged()
        .toMutableStateFlow(scope, UIPrefsData())

    suspend fun update(transform: (UIPrefsData) -> UIPrefsData) {
        val current = flow.value
        val next = transform(current)
        if (next == current) return
        dataStore.edit { p ->
            p[SettingsStore.DYNAMIC_COLOR] = next.dynamicColor
            p[SettingsStore.THEME_ID] = next.themeId
            p[SettingsStore.DEVELOPER_MODE] = next.developerMode
            p[SettingsStore.DISPLAY_SETTING] = JsonInstant.encodeToString(next.displaySetting)
            p[SettingsStore.LAUNCH_COUNT] = next.launchCount
            p[SettingsStore.SPONSOR_ALERT_DISMISSED_AT] = next.sponsorAlertDismissedAt
        }
    }
}
