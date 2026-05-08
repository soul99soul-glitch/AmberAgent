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
import me.rerere.rikkahub.data.datastore.AgentRuntimeSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.memory.dream.MemoryDreamApplier
import me.rerere.rikkahub.data.memory.dream.MemoryDreamPlan
import me.rerere.rikkahub.data.memory.dream.MemoryDreamPlanner
import me.rerere.rikkahub.data.memory.export.MemoryImportExportManager
import me.rerere.rikkahub.data.memory.model.MemoryCandidateStatus
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.MemoryRepository
import java.io.File

class SettingAgentMemoryVM(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val memoryDreamPlanner: MemoryDreamPlanner,
    private val memoryDreamApplier: MemoryDreamApplier,
    private val memoryImportExportManager: MemoryImportExportManager,
) : ViewModel() {
    private val _dreamPlan = MutableStateFlow<MemoryDreamPlan?>(null)
    val dreamPlan: StateFlow<MemoryDreamPlan?> = _dreamPlan.asStateFlow()

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

    fun planDream() {
        viewModelScope.launch {
            _memoryTaskRunning.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    memoryDreamPlanner.plan()
                }
            }.onSuccess { plan ->
                _dreamPlan.value = plan
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
        val plan = _dreamPlan.value ?: return
        viewModelScope.launch {
            _memoryTaskRunning.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    memoryDreamApplier.apply(plan)
                }
            }.onSuccess {
                _dreamPlan.value = null
                _operationMessage.value = "已应用 Dream 整理建议"
            }.onFailure { error ->
                _operationMessage.value = "应用 Dream 建议失败：${error.message ?: error::class.java.simpleName}"
            }
            _memoryTaskRunning.value = false
        }
    }

    fun dismissDreamPlan() {
        _dreamPlan.value = null
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
