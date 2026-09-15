package app.amber.feature.ui.pages.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.amber.feature.live.LiveFillResult
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

    fun setCompanionModel(modelId: String?) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    agentRuntime = settings.agentRuntime.copy(
                        liveMode = settings.agentRuntime.liveMode.copy(companionModelId = modelId)
                    )
                )
            }
        }
    }

    fun fillDraft(): LiveFillResult = liveModeManager.fillCurrentDraft()

    fun setBubbleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    agentRuntime = settings.agentRuntime.copy(
                        liveMode = settings.agentRuntime.liveMode.copy(bubbleEnabled = enabled)
                    )
                )
            }
        }
    }

    // 伴随会话不随页面 VM 销毁而停止（蓝图 v3 §7.2 P0-3）：
    // Manager 是进程级 single（AgentInfraModule.kt:67），停止语义=页面主开关 off / 气泡长按。
}
