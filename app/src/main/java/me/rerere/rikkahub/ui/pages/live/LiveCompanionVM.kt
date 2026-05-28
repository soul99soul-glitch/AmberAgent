package me.rerere.rikkahub.ui.pages.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.amber.feature.live.LiveModeManager
import app.amber.feature.live.LiveModeUiState
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator

class LiveCompanionVM(
    private val settingsStore: SettingsAggregator,
    private val liveModeManager: LiveModeManager,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    val state: StateFlow<LiveModeUiState> = liveModeManager.state

    fun start() {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    agentRuntime = settings.agentRuntime.copy(
                        liveMode = settings.agentRuntime.liveMode.copy(enabled = true)
                    )
                )
            }
            liveModeManager.start()
        }
    }

    fun pauseOrResume() {
        if (state.value.paused) {
            liveModeManager.resume()
        } else {
            liveModeManager.pause()
        }
    }

    fun stop() {
        liveModeManager.stop()
    }

    fun refreshNow() {
        liveModeManager.refreshNow()
    }

    fun submitFocusInstruction(instruction: String) {
        liveModeManager.submitFocusInstruction(instruction)
    }

    fun exportCurrentCard(): String? = liveModeManager.exportCurrentCard()

    fun setAutoRefresh(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    agentRuntime = settings.agentRuntime.copy(
                        liveMode = settings.agentRuntime.liveMode.copy(autoRefresh = enabled)
                    )
                )
            }
        }
    }

    fun setVoiceInputEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    agentRuntime = settings.agentRuntime.copy(
                        liveMode = settings.agentRuntime.liveMode.copy(voiceInputEnabled = enabled)
                    )
                )
            }
        }
    }

    override fun onCleared() {
        liveModeManager.stop()
        super.onCleared()
    }
}
