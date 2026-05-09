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
)

data class WorkspaceGroup(
    val label: String,
    val dirPrefix: String,
    val items: List<WorkspaceFileItem>,
    val collapsed: Boolean = false,
)

class WorkspaceFileVM(private val context: Context) : ViewModel() {
    private val mirrorDir get() = context.filesDir.resolve("amberagent/workspace-mirror")

    private val _groups = MutableStateFlow<List<WorkspaceGroup>>(emptyList())
    val groups: StateFlow<List<WorkspaceGroup>> = _groups.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _groups.value = buildGroups()
            _loading.value = false
        }
    }

    private suspend fun buildGroups(): List<WorkspaceGroup> = withContext(Dispatchers.IO) {
        val root = mirrorDir
        if (!root.exists() || !root.isDirectory) return@withContext emptyList()

        val allFiles = mutableListOf<WorkspaceFileItem>()
        root.walkTopDown().forEach { file ->
            if (file.isFile && !file.name.startsWith(".")) {
                allFiles.add(
                    WorkspaceFileItem(
                        path = file.relativeTo(root).path,
                        name = file.name,
                        sizeBytes = file.length(),
                        lastModified = file.lastModified(),
                        extension = file.extension.lowercase(),
                    )
                )
            }
        }

        val groupDefs = listOf(
            "notes" to "笔记",
            "reports" to "报告",
            "slides" to "幻灯片",
            "ppt" to "幻灯片",
            "officepro" to "飞书办公",
            "downloads" to "下载",
            "extracted" to "解压文件",
            "previews" to "预览",
        )

        val knownDirs = groupDefs.map { it.first }.toSet()
        val grouped = linkedMapOf<String, MutableList<WorkspaceFileItem>>()
        val other = mutableListOf<WorkspaceFileItem>()

        allFiles.sortedByDescending { it.lastModified }.forEach { file ->
            val dir = file.path.substringBefore("/", "").lowercase()
            if (dir in knownDirs) {
                val key = groupDefs.first { it.first == dir }.second
                grouped.getOrPut(key) { mutableListOf() }.add(file)
            } else {
                other.add(file)
            }
        }

        val result = mutableListOf<WorkspaceGroup>()
        groupDefs.map { it.second }.distinct().forEach { label ->
            grouped[label]?.let { files ->
                if (files.isNotEmpty()) {
                    val prefix = files.first().path.substringBefore("/", "")
                    result.add(WorkspaceGroup(label, prefix, files))
                }
            }
        }
        if (other.isNotEmpty()) {
            result.add(WorkspaceGroup("其他", "other", other))
        }
        result
    }
}
