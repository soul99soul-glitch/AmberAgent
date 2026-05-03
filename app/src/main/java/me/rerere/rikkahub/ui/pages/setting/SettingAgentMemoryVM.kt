package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.AgentRuntimeSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.MemoryRepository

class SettingAgentMemoryVM(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    val memories: StateFlow<List<AssistantMemory>> = memoryRepository.getGlobalMemoriesFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val shortTermMemories: StateFlow<List<AssistantMemory>> = memoryRepository.getShortTermMemoriesFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val longTermMemories: StateFlow<List<AssistantMemory>> = memoryRepository.getLongTermMemoriesFlow()
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
}
