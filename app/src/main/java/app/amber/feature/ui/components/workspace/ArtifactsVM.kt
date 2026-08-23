package app.amber.feature.ui.components.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amber.agent.data.workspace.Artifact
import app.amber.agent.data.workspace.ArtifactRepository
import app.amber.agent.data.workspace.ReparseResult
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Registry-backed list/detail state for the Workspace "Artifacts" tab (P3-01).
 * Mutations (delete / reparse) are only reachable from UI gated by the
 * `workspace_artifacts_v2` flag; the list itself stays visible read-only
 * (rollback rules §17.2).
 */
class ArtifactsVM(
    private val repository: ArtifactRepository,
) : ViewModel() {
    val artifacts: StateFlow<List<Artifact>> = repository.observeAll
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun delete(artifactId: String) {
        viewModelScope.launch { repository.delete(artifactId) }
    }

    suspend fun reparse(artifactId: String): ReparseResult = repository.reparse(artifactId)

    suspend fun referenceCount(artifactId: String): Int = repository.referenceCount(artifactId)

    suspend fun sourceAvailable(artifact: Artifact): Boolean = repository.sourceAvailable(artifact)
}
