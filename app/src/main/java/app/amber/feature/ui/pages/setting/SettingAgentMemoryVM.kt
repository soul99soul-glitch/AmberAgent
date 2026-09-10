package app.amber.feature.ui.pages.setting

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
import kotlin.coroutines.cancellation.CancellationException
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
import app.amber.core.memory.store.bucketForScope
import app.amber.core.memory.store.MemoryStaleException
import app.amber.core.model.AssistantMemory
import app.amber.core.repository.MemoryRepository
import java.io.File

internal const val LOW_CONFIDENCE_CANDIDATE_THRESHOLD = 0.60f

internal enum class MemoryMutationOperation {
    CREATE,
    UPDATE,
    DELETE,
}

/**
 * State for the settings page's memory writes. A draft is kept in every
 * non-terminal failure state so the UI never has to reconstruct user input
 * after a database error or a stale revision.
 */
internal sealed interface MemoryMutationState {
    data object Idle : MemoryMutationState

    data class Saving(
        val draft: AssistantMemory,
        val operation: MemoryMutationOperation,
    ) : MemoryMutationState

    data class Deleting(
        val draft: AssistantMemory,
    ) : MemoryMutationState

    data class Saved(
        val memoryId: Int,
        val operation: MemoryMutationOperation,
    ) : MemoryMutationState

    data class Deleted(
        val memoryId: Int,
    ) : MemoryMutationState

    data class Conflict(
        val operation: MemoryMutationOperation,
        val draft: AssistantMemory,
        val latest: AssistantMemory?,
        val expectedRevision: Long,
        val actualRevision: Long,
    ) : MemoryMutationState

    data class Failed(
        val operation: MemoryMutationOperation,
        val draft: AssistantMemory,
        val message: String,
    ) : MemoryMutationState
}

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

    private val _memoryMutation = MutableStateFlow<MemoryMutationState>(MemoryMutationState.Idle)
    internal val memoryMutation: StateFlow<MemoryMutationState> = _memoryMutation.asStateFlow()

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

    fun addMemory(memory: AssistantMemory, bucket: String = bucketForScope(memory.scope)) {
        if (!beginMemoryMutation(memory, MemoryMutationOperation.CREATE)) return
        viewModelScope.launch {
            try {
                val created = memoryRepository.addMemory(
                    scope = memory.scope,
                    kind = memory.kind,
                    assistantId = bucket,
                    content = memory.content,
                    sourceConversationId = memory.sourceConversationId,
                    sourceMessageIds = memory.sourceMessageIds,
                    supersedesIds = memory.supersedesIds,
                    expiresAt = memory.expiresAt,
                    confidence = memory.confidence,
                    pinned = memory.pinned,
                    sourceRunId = memory.sourceRunId,
                    sourceTrigger = memory.sourceTrigger,
                )
                _memoryMutation.value = MemoryMutationState.Saved(
                    memoryId = created.id,
                    operation = MemoryMutationOperation.CREATE,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failMemoryMutation(MemoryMutationOperation.CREATE, memory, error)
            }
        }
    }

    fun updateMemory(memory: AssistantMemory) {
        if (!beginMemoryMutation(memory, MemoryMutationOperation.UPDATE)) return
        viewModelScope.launch {
            try {
                val updated = memoryRepository.updateMemoryCas(memory).memory
                _memoryMutation.value = MemoryMutationState.Saved(
                    memoryId = updated.id,
                    operation = MemoryMutationOperation.UPDATE,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: MemoryStaleException) {
                val latest = try {
                    memoryRepository.getMemoryById(memory.id)
                } catch (refreshError: CancellationException) {
                    throw refreshError
                } catch (refreshError: Exception) {
                    failMemoryMutation(MemoryMutationOperation.UPDATE, memory, refreshError)
                    return@launch
                }
                _memoryMutation.value = MemoryMutationState.Conflict(
                    operation = MemoryMutationOperation.UPDATE,
                    draft = memory,
                    latest = latest,
                    expectedRevision = error.expectedRevision,
                    actualRevision = error.actualRevision,
                )
            } catch (error: Exception) {
                failMemoryMutation(MemoryMutationOperation.UPDATE, memory, error)
            }
        }
    }

    fun deleteMemory(memory: AssistantMemory) {
        if (!beginMemoryMutation(memory, MemoryMutationOperation.DELETE)) return
        viewModelScope.launch {
            try {
                memoryRepository.deleteMemoryCas(memory.id, memory.revision)
                _memoryMutation.value = MemoryMutationState.Deleted(memory.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MemoryStaleException) {
                val latest = try {
                    memoryRepository.getMemoryById(memory.id)
                } catch (refreshError: CancellationException) {
                    throw refreshError
                } catch (refreshError: Exception) {
                    failMemoryMutation(MemoryMutationOperation.DELETE, memory, refreshError)
                    return@launch
                }
                _memoryMutation.value = MemoryMutationState.Conflict(
                    operation = MemoryMutationOperation.DELETE,
                    draft = memory,
                    latest = latest,
                    expectedRevision = error.expectedRevision,
                    actualRevision = error.actualRevision,
                )
            } catch (error: Exception) {
                failMemoryMutation(MemoryMutationOperation.DELETE, memory, error)
            }
        }
    }

    private fun beginMemoryMutation(
        draft: AssistantMemory,
        operation: MemoryMutationOperation,
    ): Boolean {
        if (_memoryMutation.value is MemoryMutationState.Saving ||
            _memoryMutation.value is MemoryMutationState.Deleting
        ) {
            return false
        }
        _memoryMutation.value = if (operation == MemoryMutationOperation.DELETE) {
            MemoryMutationState.Deleting(draft)
        } else {
            MemoryMutationState.Saving(draft, operation)
        }
        return true
    }

    private fun failMemoryMutation(
        operation: MemoryMutationOperation,
        draft: AssistantMemory,
        error: Exception,
    ) {
        _memoryMutation.value = MemoryMutationState.Failed(
            operation = operation,
            draft = draft,
            message = error.message ?: error::class.simpleName.orEmpty(),
        )
    }

    /** Clear a terminal/conflict state after the page has handled it. */
    fun consumeMemoryMutation() {
        _memoryMutation.value = MemoryMutationState.Idle
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
                "没有低置信候选需要忽略"
            } else {
                "已忽略 ${candidates.size} 条低置信候选"
            }
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
                    val replacedPending = plan.hasChanges && memoryDreamPlanStore.getPendingPlan() != null
                    if (plan.hasChanges) {
                        memoryDreamPlanStore.savePending(plan, MemoryDreamPlanSource.MANUAL)
                    }
                    plan to replacedPending
                }
            }.onSuccess { (plan, replacedPending) ->
                _operationMessage.value = if (plan.hasChanges) {
                    if (replacedPending) {
                        "已生成 Dream 整理建议，上一份待审核建议已作废"
                    } else {
                        "已生成 Dream 整理建议"
                    }
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
