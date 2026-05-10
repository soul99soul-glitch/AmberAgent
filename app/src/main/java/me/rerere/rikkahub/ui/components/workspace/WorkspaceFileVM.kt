package me.rerere.rikkahub.ui.components.workspace

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class WorkspaceFileItem(
    val path: String,
    val name: String,
    val sizeBytes: Long?,
    val lastModified: Long,
    val extension: String,
    val isDirectory: Boolean = false,
)

class WorkspaceFileVM(private val context: Context) : ViewModel() {
    private val mirrorDir get() = context.filesDir.resolve("amberagent/workspace-mirror")

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
