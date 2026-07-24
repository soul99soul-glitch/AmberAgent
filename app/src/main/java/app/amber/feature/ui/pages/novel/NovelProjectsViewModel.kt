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

data class NovelProjectsUiState(
    val projects: List<NovelProjectSummary> = emptyList(),
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val busy: Boolean = false,
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
        _state.value = _state.value.copy(errorMessage = message)
    }

    fun importPackage(bytes: ByteArray, replaceProjectId: NovelProjectId? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, errorMessage = null)
            try {
                val outcome = novelCreation.perform(
                    NovelIntent.ImportPackage(bytes = bytes, replaceProjectId = replaceProjectId),
                )
                if (outcome is NovelOutcome.ProjectImported) {
                    _openProjectId.tryEmit(outcome.projectID.rawValue)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val msg = error.message.orEmpty()
                if (replaceProjectId == null && msg.contains("already exists", ignoreCase = true)) {
                    // Offer replace: parse project id from package via re-import with first list match is weak;
                    // surface actionable error instead of silent fail.
                    _state.value = _state.value.copy(
                        errorMessage = "项目已存在。请先删除同 id 项目，或在导入时选择覆盖（暂用：删除后重导）。$msg",
                    )
                } else {
                    _state.value = _state.value.copy(errorMessage = error.message)
                }
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    suspend fun exportPackage(projectId: NovelProjectId): Pair<String, ByteArray>? {
        return try {
            when (val snap = novelCreation.snapshot(NovelQuery.ProjectPackage(projectId))) {
                is NovelSnapshot.PackageBytes -> snap.fileName to snap.bytes
                else -> null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.value = _state.value.copy(errorMessage = error.message)
            null
        }
    }

    suspend fun exportMarkdown(projectId: NovelProjectId, branchId: app.amber.feature.novel.model.NovelBranchId): Pair<String, String>? {
        return try {
            when (val snap = novelCreation.snapshot(NovelQuery.BranchMarkdown(projectId, branchId))) {
                is NovelSnapshot.Markdown -> snap.fileName to snap.content
                else -> null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.value = _state.value.copy(errorMessage = error.message)
            null
        }
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
