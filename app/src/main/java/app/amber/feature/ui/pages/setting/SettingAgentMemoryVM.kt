package app.amber.feature.ui.pages.setting

import android.app.Application
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
import app.amber.agent.R
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
import app.amber.core.memory.model.MemoryEvent
import app.amber.core.memory.model.MemoryEventType
import app.amber.core.model.AssistantMemory
import app.amber.core.repository.MemoryRepository
import java.io.File

internal const val LOW_CONFIDENCE_CANDIDATE_THRESHOLD = 0.60f

class SettingAgentMemoryVM(
    private val context: Application,
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

    fun ignoreLowConfidenceCandidates() {
        viewModelScope.launch {
            val candidates = memoryRepository.getAllCandidates()
                .filter { candidate ->
                    candidate.status == MemoryCandidateStatus.PENDING &&
                        candidate.confidence < LOW_CONFIDENCE_CANDIDATE_THRESHOLD
                }
            candidates.forEach { candidate ->
                memoryRepository.updateCandidate(candidate.copy(status = MemoryCandidateStatus.IGNORED))
            }
            if (candidates.isNotEmpty()) {
                memoryRepository.addEvent(
                    MemoryEvent(
                        type = MemoryEventType.CANDIDATE_IGNORED,
                        message = "Batch ignored ${candidates.size} pending candidates with confidence < " +
                            LOW_CONFIDENCE_CANDIDATE_THRESHOLD,
                    )
                )
            }
            _operationMessage.value = if (candidates.isEmpty()) {
                context.getString(R.string.memory_ignore_low_confidence_none)
            } else {
                context.getString(R.string.memory_ignored_low_confidence, candidates.size)
            }
        }
    }

    fun triggerDreamNow() {
        viewModelScope.launch {
            memoryDreamScheduler.runOnce()
            _operationMessage.value = context.getString(R.string.memory_daydream_triggered)
        }
    }

    fun planDream() {
        viewModelScope.launch {
            _memoryTaskRunning.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val plan = memoryDreamPlanner.plan()
                    val replacedPending = plan.hasChanges && memoryDreamPlanStore.getPendingPlan() != null
                    if (plan.hasChanges) {
                        memoryDreamPlanStore.savePending(plan, MemoryDreamPlanSource.MANUAL)
                    }
                    plan to replacedPending
                }
            }.onSuccess { (plan, replacedPending) ->
                _operationMessage.value = if (plan.hasChanges) {
                    if (replacedPending) {
                        context.getString(R.string.memory_dream_plan_replaced)
                    } else {
                        context.getString(R.string.memory_dream_plan_generated)
                    }
                } else {
                    context.getString(R.string.memory_no_maintenance_needed)
                }
            }.onFailure { error ->
                _operationMessage.value = context.getString(
                    R.string.memory_dream_plan_failed,
                    error.message ?: error::class.java.simpleName,
                )
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
                    context.getString(R.string.memory_dream_plan_applied)
                } else {
                    context.getString(R.string.memory_no_safe_dream_plan)
                }
            }.onFailure { error ->
                _operationMessage.value = context.getString(
                    R.string.memory_dream_apply_failed,
                    error.message ?: error::class.java.simpleName,
                )
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
                _operationMessage.value = context.getString(
                    R.string.memory_exported,
                    result.memoryCount,
                    result.root.absolutePath,
                )
            }.onFailure { error ->
                _operationMessage.value = context.getString(
                    R.string.memory_export_failed,
                    error.message ?: error::class.java.simpleName,
                )
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
                _operationMessage.value = context.getString(
                    R.string.memory_imported,
                    result.importedCount,
                )
            }.onFailure { error ->
                _operationMessage.value = context.getString(
                    R.string.memory_import_failed,
                    error.message ?: error::class.java.simpleName,
                )
            }
            _memoryTaskRunning.value = false
        }
    }

    fun consumeOperationMessage() {
        _operationMessage.value = null
    }
}
