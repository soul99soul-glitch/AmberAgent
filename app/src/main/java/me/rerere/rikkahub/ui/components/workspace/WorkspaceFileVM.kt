package me.rerere.rikkahub.ui.components.workspace

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.agent.workspace.WorkspaceManager
import java.io.File

data class WorkspaceFileItem(
    val path: String,
    val name: String,
    val sizeBytes: Long?,
    val lastModified: Long,
    val extension: String,
    val isDirectory: Boolean = false,
)

class WorkspaceFileVM(
    private val context: Context,
    private val workspaceManager: WorkspaceManager,
) : ViewModel() {
    private val mirrorDir get() = workspaceManager.mirrorDir

    private val _recent = MutableStateFlow<List<WorkspaceFileItem>>(emptyList())
    val recent: StateFlow<List<WorkspaceFileItem>> = _recent.asStateFlow()

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _currentDirItems = MutableStateFlow<List<WorkspaceFileItem>>(emptyList())
    val currentDirItems: StateFlow<List<WorkspaceFileItem>> = _currentDirItems.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _recent.value = buildRecent()
            _currentDirItems.value = listDir(_currentPath.value)
            _loading.value = false
        }
    }

    fun navigateTo(relativePath: String) {
        _currentPath.value = relativePath
        viewModelScope.launch {
            _currentDirItems.value = listDir(relativePath)
        }
    }

    fun navigateUp() {
        val current = _currentPath.value
        if (current.isEmpty()) return
        val parent = current.substringBeforeLast('/', "").let {
            if (it == current) "" else it
        }
        navigateTo(parent)
    }

    /**
     * Delete a single file inside the workspace mirror. Folders are intentionally not
     * supported here — deleting a folder full of files via long-press would be a
     * surprising amount of damage from one gesture; route through the terminal/file
     * tools for that. Returns silently on failure (permission, missing, etc.) and just
     * refreshes the listings, so a stale row disappears either way.
     */
    fun deleteFile(relativePath: String) {
        if (relativePath.isBlank()) return
        viewModelScope.launch {
            // Routed through WorkspaceManager so the delete acquires the same mirrorMutex
            // that withMirrorSync / copyUriToUploads use — otherwise an in-flight terminal
            // sync could re-copy the file from SAF immediately after we deleted it, or
            // overwrite a partial state. Failures are logged silently here; the refresh()
            // below will re-list the dir so the user sees whether the file is gone.
            val ok = runCatching { workspaceManager.deleteWorkspaceFile(relativePath) }
                .onFailure { Log.w("WorkspaceFileVM", "deleteFile($relativePath) threw", it) }
                .getOrDefault(false)
            if (!ok) {
                Log.w("WorkspaceFileVM", "deleteFile($relativePath) returned false (missing or refused)")
            }
            refresh()
        }
    }

    fun shareableFile(relativePath: String): File? {
        if (relativePath.isBlank()) return null
        return runCatching {
            val root = mirrorDir.canonicalFile
            val target = mirrorDir.resolve(relativePath).canonicalFile
            if (!target.path.startsWith(root.path + File.separator) || !target.isFile) {
                null
            } else {
                target
            }
        }.getOrNull()
    }

    private suspend fun buildRecent(): List<WorkspaceFileItem> = withContext(Dispatchers.IO) {
        val root = mirrorDir
        if (!root.exists() || !root.isDirectory) return@withContext emptyList()
        root.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .map { file ->
                WorkspaceFileItem(
                    path = file.relativeTo(root).invariantSeparatorsPath,
                    name = file.name,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    extension = file.extension.lowercase(),
                    isDirectory = false,
                )
            }
            .sortedByDescending { it.lastModified }
            .take(80)
            .toList()
    }

    private suspend fun listDir(relativePath: String): List<WorkspaceFileItem> = withContext(Dispatchers.IO) {
        val root = mirrorDir
        if (!root.exists() || !root.isDirectory) return@withContext emptyList()
        val target = if (relativePath.isEmpty()) root else root.resolve(relativePath)
        if (!target.exists() || !target.isDirectory) return@withContext emptyList()
        val entries = target.listFiles()?.filter { !it.name.startsWith(".") } ?: return@withContext emptyList()
        entries.map { f ->
            WorkspaceFileItem(
                path = f.relativeTo(root).invariantSeparatorsPath,
                name = f.name,
                sizeBytes = if (f.isFile) f.length() else null,
                lastModified = f.lastModified(),
                extension = if (f.isFile) f.extension.lowercase() else "",
                isDirectory = f.isDirectory,
            )
        }.sortedWith(
            compareByDescending<WorkspaceFileItem> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
    }
}
