package app.amber.feature.ui.pages.novel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.amber.feature.novelworkspace.NovelWorkspaceExchange
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.novelworkspace.NovelWorkspaceProjectSummary
import java.io.ByteArrayInputStream
import java.util.UUID

data class NovelProjectsUiState(
    val projects: List<NovelWorkspaceProjectSummary> = emptyList(),
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val busy: Boolean = false,
)

/**
 * Novel projects list — reads the markdown-workspace registry only. The legacy JSON
 * engine is removed; the workspace format is the single source of truth.
 */
class NovelProjectsViewModel(
    private val workspaceRepository: NovelWorkspaceProjectRepository,
    private val workspaceMigrationService: app.amber.feature.novel.workspace.NovelWorkspaceMigrationService,
    private val legacyRepository: app.amber.feature.novel.persistence.NovelProjectPersisting,
) : ViewModel() {
    private val _state = MutableStateFlow(NovelProjectsUiState())
    val state: StateFlow<NovelProjectsUiState> = _state.asStateFlow()

    private val _openWorkspaceProjectId = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openWorkspaceProjectId: SharedFlow<String> = _openWorkspaceProjectId.asSharedFlow()

    init {
        // Cutover: any legacy-format book still on disk is migrated on first open so it
        // shows up in the workspace list; originals stay untouched as rollback copies.
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { workspaceMigrationService.migrateAll() }
            }.getOrNull()
            // Surface migration failures instead of silently dropping books from the list.
            val failed = result?.failed ?: 0
            if (failed > 0) {
                _state.value = _state.value.copy(
                    errorMessage = "有 $failed 本书迁移失败（原文件仍在磁盘），可在设置里检查",
                )
            }
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorMessage = null)
            try {
                val projects = withContext(Dispatchers.IO) {
                    workspaceRepository.listProjects()
                }
                _state.value = _state.value.copy(
                    projects = projects,
                    loading = false,
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

    /** Create a blank markdown-workspace book and open its workspace page. */
    fun createBlankWorkspace(name: String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null, statusMessage = null)
            try {
                val result = withContext(Dispatchers.IO) {
                    workspaceRepository.createBlank(name = name.trim())
                }
                showStatus("已创建工作区书籍「$name」")
                _openWorkspaceProjectId.tryEmit(result.projectDirectory.name.uppercase())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(errorMessage = error.message ?: "创建工作区书籍失败")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun renameProject(projectId: String, newName: String) {
        if (_state.value.busy) return
        val name = newName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            try {
                withContext(Dispatchers.IO) { workspaceRepository.renameProject(projectId, name) }
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(errorMessage = error.message)
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun delete(projectId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            try {
                withContext(Dispatchers.IO) {
                    workspaceRepository.delete(projectId)
                    // Also remove the legacy original, or the first-open migration would
                    // resurrect the deleted book from its untouched legacy copy.
                    runCatching {
                        val legacyId = app.amber.feature.novel.model.NovelProjectId.parse(projectId)
                        val legacy = legacyRepository.loadProject(legacyId)
                        legacyRepository.deleteProject(legacyId, legacy.document.project.revision)
                    }
                }
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(errorMessage = error.message)
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /** Import a workspace zip as a fresh project (always a new id). */
    fun importZip(bytes: ByteArray, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null, statusMessage = null)
            try {
                val files = withContext(Dispatchers.IO) {
                    NovelWorkspaceExchange.readZipFiles(ByteArrayInputStream(bytes))
                }
                val projectId = UUID.randomUUID().toString().uppercase()
                val result = withContext(Dispatchers.IO) {
                    workspaceRepository.install(projectId, files)
                }
                showStatus("已导入工作区书籍")
                onSuccess()
                _openWorkspaceProjectId.tryEmit(result.projectDirectory.name.uppercase())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(errorMessage = error.message ?: "导入失败")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /** Export the project tree as a workspace zip. */
    fun exportZip(projectId: String, onResult: (String, ByteArray) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null, statusMessage = null)
            try {
                val bytes = withContext(Dispatchers.IO) {
                    NovelWorkspaceExchange.exportZipBytes(workspaceRepository.projectDirectory(projectId))
                }
                onResult("$projectId.zip", bytes)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(errorMessage = error.message ?: "导出失败")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun reportError(message: String) {
        _state.value = _state.value.copy(errorMessage = message, statusMessage = null, busy = false)
    }

    fun reportStatus(message: String) {
        showStatus(message)
    }

    /** Status toasts are transient: show, then clear after a beat (device-observed:
     *  the "已创建" banner lingered indefinitely and obscured the list header). */
    private fun showStatus(message: String) {
        _state.value = _state.value.copy(statusMessage = message, errorMessage = null)
        viewModelScope.launch {
            kotlinx.coroutines.delay(3_000)
            if (_state.value.statusMessage == message) {
                _state.value = _state.value.copy(statusMessage = null)
            }
        }
    }

    fun beginImportRead() {
        _state.value = _state.value.copy(busy = true, errorMessage = null, statusMessage = null)
    }

    fun endImportRead() {
        _state.value = _state.value.copy(busy = false)
    }

    fun clearMessages() {
        _state.value = _state.value.copy(errorMessage = null, statusMessage = null)
    }
}
