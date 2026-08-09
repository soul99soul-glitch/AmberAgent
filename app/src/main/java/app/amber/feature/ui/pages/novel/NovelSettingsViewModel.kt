package app.amber.feature.ui.pages.novel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amber.feature.novel.NovelCreation
import app.amber.feature.novel.NovelGhostwriteBatchController
import app.amber.feature.novel.NovelGhostwriteCoordinator
import app.amber.feature.novel.NovelIntent
import app.amber.feature.novel.NovelInterruptReason
import app.amber.feature.novel.NovelInterruptRequest
import app.amber.feature.novel.NovelQuery
import app.amber.feature.novel.NovelSnapshot
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelBranchLifecycle
import app.amber.feature.novel.model.NovelBranchRecord
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelProjectLoadAccess
import app.amber.feature.novel.model.NovelProjectModelPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Settings-only VM: mutates project via [NovelCreation], never starts/interrupts runs.
 * Separate from [NovelWorkspaceViewModel] so opening settings cannot RouteExit-kill generation.
 */
data class NovelSettingsUiState(
    val loading: Boolean = true,
    val document: NovelProjectDocumentV1? = null,
    val access: NovelProjectLoadAccess = NovelProjectLoadAccess.ReadWrite,
    val selectedBranchId: NovelBranchId? = null,
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
)

class NovelSettingsViewModel(
    projectId: String,
    private val novelCreation: NovelCreation,
    private val uiSession: NovelProjectUiSession,
    private val ghostwriteCoordinator: NovelGhostwriteCoordinator,
    private val ghostwriteBatchController: NovelGhostwriteBatchController,
) : ViewModel() {
    val projectId: NovelProjectId = NovelProjectId.parse(projectId)
    private val _state = MutableStateFlow(NovelSettingsUiState())
    val state: StateFlow<NovelSettingsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { refreshSuspend() }
    }

    private suspend fun refreshSuspend() {
        _state.value = _state.value.copy(loading = true, errorMessage = null)
        runCatching {
            when (val snap = novelCreation.snapshot(NovelQuery.Project(projectId))) {
                is NovelSnapshot.Project -> {
                    val doc = snap.document
                    val active = { id: NovelBranchId ->
                        doc.branches.any { it.id == id && it.lifecycle == NovelBranchLifecycle.Active }
                    }
                    val branchId = listOfNotNull(
                        uiSession.selectedBranch(projectId.rawValue),
                        _state.value.selectedBranchId,
                        doc.project.mainBranchID,
                    ).firstOrNull(active) ?: doc.project.mainBranchID
                    branchId?.let { uiSession.setSelectedBranch(projectId.rawValue, it) }
                    _state.value = _state.value.copy(
                        loading = false,
                        document = doc,
                        access = snap.access,
                        selectedBranchId = branchId,
                    )
                }
                else -> error("Unexpected snapshot")
            }
        }.onFailure { e ->
            _state.value = _state.value.copy(
                loading = false,
                errorMessage = humanizeNovelError(null, e.message),
            )
        }
    }

    fun selectBranch(branchId: NovelBranchId) {
        val doc = _state.value.document ?: return
        if (branchId != _state.value.selectedBranchId && ghostwriteCoordinator.owns(projectId)) {
            _state.value = _state.value.copy(errorMessage = "代笔正在运行，请先返回创作页暂停")
            return
        }
        viewModelScope.launch {
            if (!ensureNoActiveBatch()) return@launch
            if (doc.branches.none {
                    it.id == branchId && it.lifecycle == NovelBranchLifecycle.Active
                }
            ) {
                _state.value = _state.value.copy(errorMessage = "分支不存在")
                return@launch
            }
            // Workspace may still be streaming on the old branch; stop so UI cannot mix sessions.
            if (branchId != _state.value.selectedBranchId) {
                novelCreation.interrupt(
                    NovelInterruptRequest(projectId = projectId, reason = NovelInterruptReason.User),
                )
            }
            uiSession.setSelectedBranch(projectId.rawValue, branchId)
            _state.value = _state.value.copy(selectedBranchId = branchId, errorMessage = null)
        }
    }

    fun currentBranch(): NovelBranchRecord? {
        val doc = _state.value.document ?: return null
        val id = _state.value.selectedBranchId ?: return null
        return doc.branches.firstOrNull { it.id == id }
    }

    fun renameBranch(branchId: NovelBranchId, name: String) {
        mutate {
            novelCreation.perform(NovelIntent.RenameBranch(projectId, branchId, name))
        }
    }

    fun setMainBranch(branchId: NovelBranchId) {
        mutate {
            novelCreation.perform(NovelIntent.SetMainBranch(projectId, branchId))
        }
    }

    fun forkFromHead(name: String) {
        val branch = currentBranch() ?: return
        mutate {
            val outcome = novelCreation.perform(
                NovelIntent.ForkBranch(
                    projectId = projectId,
                    sourceBranchId = branch.id,
                    checkpointId = branch.headCheckpointID,
                    name = name,
                ),
            )
            if (outcome is app.amber.feature.novel.model.NovelOutcome.BranchForked) {
                uiSession.setSelectedBranch(projectId.rawValue, outcome.branchID)
                _state.value = _state.value.copy(
                    selectedBranchId = outcome.branchID,
                    statusMessage = "已切换到分支「$name」",
                    errorMessage = null,
                )
            }
        }
    }

    fun reportError(message: String) {
        _state.value = _state.value.copy(errorMessage = message, statusMessage = null)
    }

    fun reportStatus(message: String) {
        _state.value = _state.value.copy(statusMessage = message, errorMessage = null)
    }

    fun undoHead() {
        val branchId = _state.value.selectedBranchId ?: return
        mutate {
            novelCreation.perform(NovelIntent.UndoHead(projectId, branchId))
        }
    }

    fun setModelPolicy(
        policy: NovelProjectModelPolicy,
        purpose: app.amber.feature.novel.domain.NovelModelPolicyPurpose =
            app.amber.feature.novel.domain.NovelModelPolicyPurpose.Creation,
    ) {
        mutate {
            novelCreation.perform(NovelIntent.SetModelPolicy(projectId, policy, purpose))
        }
    }

    fun clearStateSyncModelPolicy() {
        mutate {
            novelCreation.perform(NovelIntent.ClearStateSyncModelPolicy(projectId))
        }
    }

    fun setPolishPreference(text: String) {
        mutate {
            novelCreation.perform(NovelIntent.SetPolishPreference(projectId, text))
        }
    }

    fun exportMarkdown(onResult: (String, String) -> Unit) {
        val branchId = _state.value.selectedBranchId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null, statusMessage = null)
            runCatching {
                when (val snap = novelCreation.snapshot(NovelQuery.BranchMarkdown(projectId, branchId))) {
                    is NovelSnapshot.Markdown -> onResult(snap.fileName, snap.content)
                    else -> _state.value = _state.value.copy(errorMessage = "导出失败")
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    private fun mutate(block: suspend () -> Unit) {
        if (ghostwriteCoordinator.owns(projectId)) {
            _state.value = _state.value.copy(errorMessage = "代笔正在运行，请先返回创作页暂停")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            if (!ensureNoActiveBatch()) {
                _state.value = _state.value.copy(busy = false)
                return@launch
            }
            runCatching { block() }
                .onFailure { e ->
                    _state.value = _state.value.copy(errorMessage = humanizeNovelError(null, e.message))
                }
            refreshSuspend()
            _state.value = _state.value.copy(busy = false)
        }
    }

    private suspend fun ensureNoActiveBatch(): Boolean = try {
        val active = ghostwriteBatchController.activeForProject(projectId)
        if (active.isNotEmpty()) {
            _state.value = _state.value.copy(
                errorMessage = "连续代笔批次尚未结束，请返回创作页取消批次后再修改",
            )
            false
        } else {
            true
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        _state.value = _state.value.copy(
            errorMessage = "连续代笔账本异常；为避免影响后台任务，当前修改已阻止",
        )
        false
    }

    // Intentionally no onCleared interrupt — settings must not kill workspace generation.
}
