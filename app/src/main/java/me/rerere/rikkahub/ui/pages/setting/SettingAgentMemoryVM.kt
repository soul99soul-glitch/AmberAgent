package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.memory.dream.MemoryDreamApplier
import app.amber.core.memory.dream.MemoryDreamPlanSource
import app.amber.core.memory.dream.MemoryDreamPlanStore
import app.amber.core.memory.dream.MemoryDreamPlanner
import app.amber.core.memory.dream.MemoryDreamScheduler
import app.amber.core.memory.dream.PersistedMemoryDreamPlan
import app.amber.core.memory.export.MemoryImportExportManager
import app.amber.core.memory.model.MemoryCandidateStatus
import app.amber.core.model.AssistantMemory
import app.amber.core.repository.MemoryRepository
import java.io.File

class SettingAgentMemoryVM(
    private val settingsStore: SettingsAggregator,
    private val memoryRepository: MemoryRepository,
    private val memoryDreamPlanner: MemoryDreamPlanner,
    private val memoryDreamApplier: MemoryDreamApplier,
    private val memoryDreamPlanStore: MemoryDreamPlanStore,
    private val memoryDreamScheduler: MemoryDreamScheduler,
    private val memoryImportExportManager: MemoryImportExportManager,
) : ViewModel() {
    private val _memoryTaskRunning = MutableStateFlow(false)
    val memoryTaskRunning: StateFlow<Boolean> = _memoryTaskRunning.asStateFlow()

    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage: StateFlow<String?> = _operationMessage.asStateFlow()

    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    val memories: StateFlow<List<AssistantMemory>> = memoryRepository.getGlobalMemoriesFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val shortTermMemories: StateFlow<List<AssistantMemory>> = memoryRepository.getShortTermMemoriesFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val longTermMemories: StateFlow<List<AssistantMemory>> = memoryRepository.getLongTermMemoriesFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val pendingCandidates = memoryRepository.getPendingCandidatesFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val recentMemoryEvents = memoryRepository.getRecentEventsFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val dreamPlan: StateFlow<PersistedMemoryDreamPlan?> = memoryDreamPlanStore.pendingPlanFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    fun updateAgentRuntime(update: (AgentRuntimeSetting) -> AgentRuntimeSetting) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(agentRuntime = update(settings.agentRuntime))
            }
        }
    }

    fun addMemory(memory: AssistantMemory, bucket: String = MemoryRepository.GLOBAL_MEMORY_ID) {
        viewModelScope.launch {
            memoryRepository.addMemory(
                assistantId = bucket,
                content = memory.content,
            )
        }
    }

    fun updateMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            memoryRepository.updateContent(id = memory.id, content = memory.content)
        }
    }

    fun deleteMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(memory.id)
        }
    }

    fun acceptCandidate(id: String) {
        viewModelScope.launch {
            memoryRepository.acceptCandidate(id)
        }
    }

    fun ignoreCandidate(id: String) {
        viewModelScope.launch {
            val candidate = memoryRepository.getAllCandidates().firstOrNull { it.id == id } ?: return@launch
            memoryRepository.updateCandidate(candidate.copy(status = MemoryCandidateStatus.IGNORED))
        }
    }

    fun triggerDreamNow() {
        viewModelScope.launch {
            memoryDreamScheduler.runOnce()
            _operationMessage.value = "已触发一次 Daydream 后台整理（结果通过通知和上方「待审核」区域反馈）"
        }
    }

    fun planDream() {
        viewModelScope.launch {
            _memoryTaskRunning.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val plan = memoryDreamPlanner.plan()
                    if (plan.hasChanges) {
                        memoryDreamPlanStore.savePending(plan, MemoryDreamPlanSource.MANUAL)
                    }
                    plan
                }
            }.onSuccess { plan ->
                _operationMessage.value = if (plan.hasChanges) {
                    "已生成 Dream 整理建议"
                } else {
                    "没有发现需要整理的记忆"
                }
            }.onFailure { error ->
                _operationMessage.value = "Dream 整理失败：${error.message ?: error::class.java.simpleName}"
            }
            _memoryTaskRunning.value = false
        }
    }

    fun applyDreamPlan() {
        val persistedPlan = dreamPlan.value ?: return
        viewModelScope.launch {
            _memoryTaskRunning.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val appliedPlan = memoryDreamApplier.apply(persistedPlan.plan)
                    if (appliedPlan.hasChanges) {
                        memoryDreamPlanStore.markApplied(persistedPlan.id)
                    } else {
                        memoryDreamPlanStore.markDismissed(persistedPlan.id)
                    }
                    appliedPlan
                }
            }.onSuccess { appliedPlan ->
                _operationMessage.value = if (appliedPlan.hasChanges) {
                    "已应用 Dream 整理建议"
                } else {
                    "没有可安全应用的 Dream 建议"
                }
            }.onFailure { error ->
                _operationMessage.value = "应用 Dream 建议失败：${error.message ?: error::class.java.simpleName}"
            }
            _memoryTaskRunning.value = false
        }
    }

    fun dismissDreamPlan() {
        val persistedPlan = dreamPlan.value ?: return
        viewModelScope.launch {
            memoryDreamPlanStore.markDismissed(persistedPlan.id)
        }
    }

    fun exportMemories(directory: File) {
        viewModelScope.launch {
            _memoryTaskRunning.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    memoryImportExportManager.exportTo(directory)
                }
            }.onSuccess { result ->
                _operationMessage.value = "已导出 ${result.memoryCount} 条记忆到 ${result.root.absolutePath}"
            }.onFailure { error ->
                _operationMessage.value = "导出失败：${error.message ?: error::class.java.simpleName}"
            }
            _memoryTaskRunning.value = false
        }
    }

    fun importMemories(root: File) {
        viewModelScope.launch {
            _memoryTaskRunning.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    memoryImportExportManager.importFrom(root)
                }
            }.onSuccess { result ->
                _operationMessage.value = "已导入 ${result.importedCount} 条记忆"
            }.onFailure { error ->
                _operationMessage.value = "导入失败：${error.message ?: error::class.java.simpleName}"
            }
            _memoryTaskRunning.value = false
        }
    }

    fun consumeOperationMessage() {
        _operationMessage.value = null
    }
}
