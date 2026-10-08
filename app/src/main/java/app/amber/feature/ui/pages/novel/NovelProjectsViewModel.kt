package app.amber.feature.ui.pages.novel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amber.agent.R
import app.amber.core.utils.appLocale
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
import app.amber.feature.novelworkspace.NovelWorkspaceBookExport
import app.amber.feature.novelworkspace.NovelWorkspaceExchange
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceIoError
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.novelworkspace.NovelWorkspaceProjectSummary
import app.amber.feature.novel.domain.NovelError
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.workspace.NovelWorkspaceProjectImport

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
    private val context: Context,
    private val restoreBridge: app.amber.feature.novel.workspace.NovelWorkspaceRestoreBridge? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(NovelProjectsUiState())
    val state: StateFlow<NovelProjectsUiState> = _state.asStateFlow()

    private val _openWorkspaceProjectId = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openWorkspaceProjectId: SharedFlow<String> = _openWorkspaceProjectId.asSharedFlow()

    init {
        restoreBridge?.let { bridge ->
            viewModelScope.launch {
                var observedEpoch = bridge.state.value.epoch
                bridge.state.collect { restored ->
                    if (restored.restoring) _state.value = _state.value.copy(loading = true, busy = true)
                    else if (restored.epoch != observedEpoch) {
                        _state.value = _state.value.copy(busy = false)
                        refresh()
                    }
                    observedEpoch = restored.epoch
                }
            }
        }
        // Cutover: any legacy-format book still on disk is migrated on first open so it
        // shows up in the workspace list; originals stay untouched as rollback copies.
        val initialEpoch = app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.currentEpoch()
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    workspaceMigrationService.migrateAll(restoreEpoch = initialEpoch)
                }
                // Surface migration failures instead of silently dropping books from the list.
                if (result.failed > 0) {
                    _state.value = _state.value.copy(
                        errorMessage = context.getString(R.string.novel_migration_failed_count, result.failed),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    errorMessage = error.message ?: context.getString(R.string.error_title_operation),
                )
            }
            refresh()
        }
    }

    fun refresh() {
        val expectedEpoch = app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.currentEpoch()
        viewModelScope.launch {
            app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.write(expectedEpoch) { Unit }
            _state.value = _state.value.copy(loading = true)
            try {
                val projects = withContext(Dispatchers.IO) {
                    app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.write(expectedEpoch) {
                        workspaceRepository.listProjects()
                    }
                }
                app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.write(expectedEpoch) { Unit }
                _state.value = _state.value.copy(
                    projects = projects,
                    loading = false,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    errorMessage = error.message ?: context.getString(R.string.error_title_operation),
                )
            }
        }
    }

    /** Create a blank markdown-workspace book and open its workspace page. */
    fun createBlankWorkspace(name: String) {
        if (_state.value.busy) return
        val expectedEpoch = app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.currentEpoch()
        viewModelScope.launch {
            app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.write(expectedEpoch) { Unit }
            _state.value = _state.value.copy(busy = true, errorMessage = null, statusMessage = null)
            try {
                val result = withContext(Dispatchers.IO) {
                    app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.write(expectedEpoch) {
                        workspaceRepository.createBlank(name = name.trim())
                    }
                }
                app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.write(expectedEpoch) { Unit }
                _openWorkspaceProjectId.tryEmit(result.projectDirectory.name.uppercase())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    errorMessage = error.message ?: context.getString(R.string.workspace_save_failed),
                )
            } finally {
                if (app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.isCurrent(expectedEpoch)) _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun renameProject(projectId: String, newName: String) {
        if (_state.value.busy) return
        val name = newName.trim()
        if (name.isEmpty()) return
        val expectedEpoch = app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.currentEpoch()
        viewModelScope.launch {
            app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.write(expectedEpoch) { Unit }
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            try {
                withContext(Dispatchers.IO) {
                    app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.write(expectedEpoch) {
                        workspaceRepository.renameProject(projectId, name)
                    }
                }
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(errorMessage = error.message)
            } finally {
                if (app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.isCurrent(expectedEpoch)) _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun delete(projectId: String) {
        if (_state.value.busy) return
        val expectedEpoch = app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.currentEpoch()
        viewModelScope.launch {
            app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.write(expectedEpoch) { Unit }
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            try {
                withContext(Dispatchers.IO) {
                    if (restoreBridge != null) restoreBridge.withWriter(expectedEpoch) {
                        deleteAtEpoch(projectId, expectedEpoch)
                    } else deleteAtEpoch(projectId, expectedEpoch)
                }
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(errorMessage = error.message)
            } finally {
                if (app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.isCurrent(expectedEpoch)) _state.value = _state.value.copy(busy = false)
            }
        }
    }

    private suspend fun deleteAtEpoch(projectId: String, expectedEpoch: Long) {
        app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.write(expectedEpoch) {
            val directory = workspaceRepository.projectDirectory(projectId)
            if (NovelWorkspaceGhostwriteJobs.listActive(directory).isNotEmpty()) {
                throw NovelWorkspaceIoError("当前项目仍有代笔批次运行，请先让批次完成或取消后再删除")
            }
        }
        // Remove the migration source first, so reopening cannot resurrect a deleted book.
        try {
            val legacyId = NovelProjectId.parse(projectId)
            val legacy = legacyRepository.loadProject(legacyId)
            legacyRepository.deleteProject(legacyId, legacy.document.project.revision)
        } catch (_: NovelError.ProjectNotFound) {
            // Workspace-only books have no migration source.
        }
        app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.write(expectedEpoch) {
            workspaceRepository.delete(projectId)
        }
    }

    /** Import a workspace ZIP or .ambernovel package as a fresh project. */
    fun importProject(bytes: ByteArray, onSuccess: () -> Unit) {
        val expectedEpoch = app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.currentEpoch()
        viewModelScope.launch {
            app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.write(expectedEpoch) { Unit }
            _state.value = _state.value.copy(busy = true, errorMessage = null, statusMessage = null)
            try {
                val result = withContext(Dispatchers.IO) {
                    NovelWorkspaceProjectImport.importProject(
                        bytes,
                        workspaceRepository,
                        restoreEpoch = expectedEpoch,
                    )
                }
                app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.write(expectedEpoch) { Unit }
                showStatus(context.getString(R.string.export_import_success))
                onSuccess()
                app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.write(expectedEpoch) { Unit }
                _openWorkspaceProjectId.tryEmit(result.projectDirectory.name.uppercase())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    errorMessage = error.message?.let { context.getString(R.string.export_import_failed, it) }
                        ?: context.getString(R.string.export_import_failed, context.getString(R.string.novel_unknown_reason)),
                )
            } finally {
                if (app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.isCurrent(expectedEpoch)) _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /** Export the project tree as a workspace zip. */
    fun exportZip(projectId: String, onResult: (String, ByteArray) -> Unit) {
        val expectedEpoch = app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.currentEpoch()
        viewModelScope.launch {
            app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.write(expectedEpoch) { Unit }
            _state.value = _state.value.copy(busy = true, errorMessage = null, statusMessage = null)
            try {
                val bytes = withContext(Dispatchers.IO) {
                    NovelWorkspaceExchange.exportZipBytes(workspaceRepository.projectDirectory(projectId))
                }
                app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.write(expectedEpoch) { Unit }
                onResult("$projectId.zip", bytes)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    errorMessage = error.message ?: context.getString(R.string.novel_export_write_failed),
                )
            } finally {
                if (app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.isCurrent(expectedEpoch)) _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /** Assemble the exportable book for the project's ACTIVE branch（.amber/branch.json，
     *  缺失回退主线）；null with an error banner on failure. */
    suspend fun exportBook(projectId: String, format: NovelWorkspaceBookExport.Format): ByteArray? {
        val expectedEpoch = app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.currentEpoch()
        if (_state.value.busy) return null
        _state.value = _state.value.copy(busy = true, errorMessage = null, statusMessage = null)
        return try {
            withContext(Dispatchers.IO) {
                val directory = workspaceRepository.projectDirectory(projectId)
                NovelWorkspaceBookExport.exportBytes(
                    directory,
                    format,
                    app.amber.feature.novelworkspace.NovelWorkspaceBranches.activeSlug(directory),
                    locale = context.appLocale(),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.value = _state.value.copy(
                errorMessage = error.message ?: context.getString(R.string.novel_export_write_failed),
            )
            null
        } finally {
            if (app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary.isCurrent(expectedEpoch)) _state.value = _state.value.copy(busy = false)
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
