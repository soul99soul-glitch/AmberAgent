package app.amber.feature.ui.pages.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.amber.agent.data.db.entity.LiveCardEntity
import app.amber.core.memory.model.MemoryCandidate
import app.amber.core.memory.store.MemoryRepository
import app.amber.core.model.MemoryKind
import app.amber.core.model.MemoryScope
import app.amber.feature.live.LiveAnalysisMode
import app.amber.feature.live.LiveFillResult
import app.amber.feature.live.LiveModeManager
import app.amber.feature.live.LiveModeUiState
import app.amber.feature.live.LiveScene
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator

class LiveCompanionVM(
    private val settingsStore: SettingsAggregator,
    private val liveModeManager: LiveModeManager,
    private val memoryRepository: MemoryRepository,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    val state: StateFlow<LiveModeUiState> = liveModeManager.state

    val savedCards: StateFlow<List<LiveCardEntity>> = liveModeManager.savedCards
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 保存当前卡片（蓝图 §7.3 P1-3）；结果经 onResult 回馈（Toast 在页面层）。 */
    fun saveCard(onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(liveModeManager.saveCurrentCard()) }
    }

    fun deleteSavedCard(id: Long) {
        viewModelScope.launch { liveModeManager.deleteSavedCard(id) }
    }

    /** 记住此事（蓝图 §7.3 P1-5）：用户明确选择才入 candidate 审核链；
     *  三元区分——内容是"伴随观察 · 模型推断"，reason 标注来源应用，createdAt 即观察时间。 */
    fun rememberCurrentCard() {
        val s = state.value
        val card = s.card ?: return
        if (card.watching.isBlank()) return
        viewModelScope.launch {
            runCatching {
                memoryRepository.addCandidate(
                    MemoryCandidate(
                        content = buildString {
                            append("【伴随观察 · 模型推断】").append(card.watching)
                            card.keyPoints.forEach { append("\n- ").append(it) }
                        },
                        scope = MemoryScope.LONG_TERM,
                        kind = MemoryKind.NOTE,
                        confidence = 0.6f,
                        reason = "用户从伴随卡片手动保存（来源应用：" +
                            "${s.currentAppLabel.ifBlank { s.currentPackage }}）",
                    ),
                )
            }
        }
    }

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

    fun setAnalysisMode(mode: LiveAnalysisMode) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    agentRuntime = settings.agentRuntime.copy(
                        liveMode = settings.agentRuntime.liveMode.copy(analysisMode = mode)
                    )
                )
            }
        }
    }

    /** 当前观察 App 的自动建议开关（蓝图 §7.4 P2：逐 App 开启，默认关）。 */
    fun setAutoSuggestForCurrentApp(enabled: Boolean) {
        val pkg = state.value.currentPackage
        if (pkg.isBlank()) return
        viewModelScope.launch {
            settingsStore.update { settings ->
                val packages = settings.agentRuntime.liveMode.autoSuggestPackages.toMutableSet()
                if (enabled) packages += pkg else packages -= pkg
                settings.copy(
                    agentRuntime = settings.agentRuntime.copy(
                        liveMode = settings.agentRuntime.liveMode.copy(autoSuggestPackages = packages)
                    )
                )
            }
        }
    }

    /** 当前观察 App 的场景覆盖（蓝图 §7.3 P1-9 场景配置化）；null = 清除覆盖跟随默认。 */
    fun setSceneOverride(packageName: String, scene: LiveScene?) {
        if (packageName.isBlank()) return
        viewModelScope.launch {
            settingsStore.update { settings ->
                val overrides = settings.agentRuntime.liveMode.sceneOverrides.toMutableMap()
                if (scene == null) overrides.remove(packageName) else overrides[packageName] = scene.name.lowercase()
                settings.copy(
                    agentRuntime = settings.agentRuntime.copy(
                        liveMode = settings.agentRuntime.liveMode.copy(sceneOverrides = overrides)
                    )
                )
            }
        }
    }

    // 伴随会话不随页面 VM 销毁而停止（蓝图 v3 §7.2 P0-3）：
    // Manager 是进程级 single（AgentInfraModule.kt:67），停止语义=页面主开关 off / 气泡长按。
}
