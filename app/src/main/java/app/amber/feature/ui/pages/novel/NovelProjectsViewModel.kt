package app.amber.feature.ui.pages.novel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import app.amber.feature.novel.NovelCreation
import app.amber.feature.novel.NovelIntent
import app.amber.feature.novel.NovelQuery
import app.amber.feature.novel.NovelSnapshot
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelProjectCreationMode
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelProjectSummary
import app.amber.feature.novel.model.NovelQuickStartSeed
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NovelImportConflict(
    val bytes: ByteArray,
    val existingProjectId: NovelProjectId,
    val existingName: String,
) {
    override fun equals(other: Any?): Boolean =
        other is NovelImportConflict &&
            existingProjectId == other.existingProjectId &&
            existingName == other.existingName &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int =
        31 * (31 * existingProjectId.hashCode() + existingName.hashCode()) + bytes.contentHashCode()
}

data class NovelProjectsUiState(
    val projects: List<NovelProjectSummary> = emptyList(),
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val busy: Boolean = false,
    val importConflict: NovelImportConflict? = null,
)

class NovelProjectsViewModel(
    private val novelCreation: NovelCreation,
) : ViewModel() {
    private val _state = MutableStateFlow(NovelProjectsUiState())
    val state: StateFlow<NovelProjectsUiState> = _state.asStateFlow()

    private val _openProjectId = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openProjectId: SharedFlow<String> = _openProjectId.asSharedFlow()

    init {
        viewModelScope.launch {
            novelCreation.projectList.collect { projects ->
                _state.value = _state.value.copy(projects = projects)
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorMessage = null)
            try {
                novelCreation.refreshProjects()
                _state.value = _state.value.copy(
                    loading = false,
                    projects = novelCreation.projectList.value,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    errorMessage = error.message ?: "Failed to load projects",
                )
            }
        }
    }

    fun createBlank(name: String) = create(name, NovelProjectCreationMode.Blank, null)

    fun createQuickStart(name: String, genre: String, coreIdea: String) =
        create(
            name = name,
            mode = NovelProjectCreationMode.QuickStart,
            seed = NovelQuickStartSeed(genre = genre, coreIdea = coreIdea),
        )

    fun rename(projectId: NovelProjectId, name: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            try {
                novelCreation.perform(NovelIntent.RenameProject(projectId, name))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(errorMessage = error.message)
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun delete(projectId: NovelProjectId) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            try {
                novelCreation.perform(NovelIntent.DeleteProject(projectId))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(errorMessage = error.message)
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun reportError(message: String) {
        _state.value = _state.value.copy(errorMessage = message, statusMessage = null, busy = false)
    }

    fun reportStatus(message: String) {
        _state.value = _state.value.copy(statusMessage = message, errorMessage = null)
    }

    fun beginImportRead() {
        _state.value = _state.value.copy(busy = true, errorMessage = null, statusMessage = null)
    }

    fun endImportRead() {
        // importPackage will set busy again; only clear if not already importing.
        if (_state.value.importConflict == null) {
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(errorMessage = null, statusMessage = null)
    }

    fun dismissImportConflict() {
        _state.value = _state.value.copy(importConflict = null)
    }

    fun importPackage(bytes: ByteArray, replaceProjectId: NovelProjectId? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                busy = true,
                errorMessage = null,
                statusMessage = null,
                importConflict = null,
            )
            try {
                val outcome = novelCreation.perform(
                    NovelIntent.ImportPackage(bytes = bytes, replaceProjectId = replaceProjectId),
                )
                if (outcome is NovelOutcome.ProjectImported) {
                    val label = when (outcome.disposition) {
                        app.amber.feature.novel.model.NovelProjectImportDisposition.Replaced ->
                            "已替换并打开项目"
                        app.amber.feature.novel.model.NovelProjectImportDisposition.Created ->
                            "已导入并打开项目"
                        app.amber.feature.novel.model.NovelProjectImportDisposition.KeptBoth ->
                            "已导入为新项目"
                    }
                    _state.value = _state.value.copy(statusMessage = label)
                    _openProjectId.tryEmit(outcome.projectID.rawValue)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val msg = error.message.orEmpty()
                val existingId = extractExistingProjectId(error)
                if (replaceProjectId == null && existingId != null) {
                    val name = _state.value.projects.firstOrNull { it.id == existingId }?.name
                        ?: "同 ID 项目"
                    _state.value = _state.value.copy(
                        importConflict = NovelImportConflict(
                            bytes = bytes,
                            existingProjectId = existingId,
                            existingName = name,
                        ),
                    )
                } else {
                    _state.value = _state.value.copy(
                        errorMessage = humanizeNovelError(null, msg.ifBlank { error.toString() }),
                    )
                }
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun confirmReplaceImport() {
        val conflict = _state.value.importConflict ?: return
        importPackage(conflict.bytes, replaceProjectId = conflict.existingProjectId)
    }

    fun confirmKeepBothImport() {
        val conflict = _state.value.importConflict ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                busy = true,
                errorMessage = null,
                statusMessage = null,
                importConflict = null,
            )
            try {
                val outcome = novelCreation.perform(
                    NovelIntent.ImportPackage(
                        bytes = conflict.bytes,
                        keepBoth = true,
                    ),
                )
                if (outcome is NovelOutcome.ProjectImported) {
                    _state.value = _state.value.copy(statusMessage = "已保留两份并打开新项目")
                    _openProjectId.tryEmit(outcome.projectID.rawValue)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    errorMessage = humanizeNovelError(null, error.message),
                )
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun exportPackage(projectId: NovelProjectId, onResult: (String, ByteArray) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null, statusMessage = null)
            try {
                when (val snap = novelCreation.snapshot(NovelQuery.ProjectPackage(projectId))) {
                    is NovelSnapshot.PackageBytes -> {
                        // Status is reported only after SAF write succeeds (caller).
                        onResult(snap.fileName, snap.bytes)
                    }
                    else -> _state.value = _state.value.copy(errorMessage = "导出失败")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    errorMessage = humanizeNovelError(null, error.message),
                )
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun exportMarkdown(project: NovelProjectSummary, onResult: (String, String) -> Unit) {
        val branchId = project.mainBranchID
        if (branchId == null) {
            _state.value = _state.value.copy(errorMessage = "项目没有主分支，无法导出 Markdown")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null, statusMessage = null)
            try {
                when (val snap = novelCreation.snapshot(NovelQuery.BranchMarkdown(project.id, branchId))) {
                    is NovelSnapshot.Markdown -> {
                        // List export is main branch; status after SAF write.
                        onResult(snap.fileName, snap.content)
                    }
                    else -> _state.value = _state.value.copy(errorMessage = "导出失败")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    errorMessage = humanizeNovelError(null, error.message),
                )
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    private fun extractExistingProjectId(error: Exception): NovelProjectId? {
        // NovelError.ProjectAlreadyExists(message includes id) or typed error.
        val typed = error as? app.amber.feature.novel.domain.NovelError.ProjectAlreadyExists
        if (typed != null) return typed.projectId
        val msg = error.message.orEmpty()
        // Fallback: "... project <uuid> already exists"
        val match = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        ).find(msg) ?: return null
        return runCatching { NovelProjectId.parse(match.value) }.getOrNull()
    }

    private fun create(
        name: String,
        mode: NovelProjectCreationMode,
        seed: NovelQuickStartSeed?,
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            try {
                val outcome = novelCreation.perform(
                    NovelIntent.CreateProject(
                        name = name,
                        mode = mode,
                        quickStartSeed = seed,
                    ),
                )
                if (outcome is NovelOutcome.ProjectCreated) {
                    _openProjectId.tryEmit(outcome.projectID.rawValue)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(errorMessage = error.message)
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }
}
