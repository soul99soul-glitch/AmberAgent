package app.amber.feature.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.ai.provider.ProviderSetting

class SettingVM(
    private val settingsStore: SettingsAggregator,
) :
    ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(transform)
        }
    }

    suspend fun importProviders(providers: List<ProviderSetting>) {
        settingsStore.update { current ->
            current.copy(providers = providers + current.providers)
        }
    }
}
