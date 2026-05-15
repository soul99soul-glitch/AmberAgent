package me.rerere.rikkahub.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.parseMcpServersFromJson
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillManager
import java.io.File

data class SkillFile(
    val file: File,
    val relativePath: String,
)

sealed class SkillFileNode {
    data class FileNode(val skillFile: SkillFile) : SkillFileNode()
    data class DirNode(
        val name: String,
        val relativePath: String,
        val children: List<SkillFileNode>,
    ) : SkillFileNode()
}

data class SkillMcpConfigState(
    val serverCount: Int,
    val error: String? = null,
)

class SkillDetailVM(
    private val skillManager: SkillManager,
    private val settingsStore: SettingsAggregator,
) : ViewModel() {

    private val _tree = MutableStateFlow<List<SkillFileNode>>(emptyList())
    val tree = _tree.asStateFlow()

    private val _mcpConfig = MutableStateFlow<SkillMcpConfigState?>(null)
    val mcpConfig = _mcpConfig.asStateFlow()

    private var skillName = ""

    fun init(name: String) {
        if (skillName == name) return
        skillName = name
        loadFiles()
    }

    fun loadFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = skillManager.getSkillDir(skillName) ?: return@launch
            _tree.value = buildTree(dir, dir)
            _mcpConfig.value = readMcpConfigState(dir)
        }
    }

    private fun readMcpConfigState(dir: File): SkillMcpConfigState? {
        val mcpFile = dir.resolve(MCP_CONFIG_FILE)
        if (!mcpFile.exists()) return null
        return runCatching {
            val configs = parseMcpServersFromJson(mcpFile.readText())
            if (configs.isEmpty()) {
                SkillMcpConfigState(serverCount = 0, error = "mcp.json 中没有有效的 MCP 服务器配置")
            } else {
                SkillMcpConfigState(serverCount = configs.size)
            }
        }.getOrElse { error ->
            SkillMcpConfigState(serverCount = 0, error = "mcp.json 解析失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun buildTree(root: File, dir: File): List<SkillFileNode> {
        val items = dir.listFiles()?.toList() ?: return emptyList()
        val files = items
            .filter { it.isFile }
            .sortedWith(compareBy({ it.name != "SKILL.md" }, { it.name }))
            .map { f -> SkillFileNode.FileNode(SkillFile(f, f.relativeTo(root).path)) }
        val dirs = items
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .map { d -> SkillFileNode.DirNode(d.name, d.relativeTo(root).path, buildTree(root, d)) }
        return dirs + files
    }

    fun readFile(skillFile: SkillFile): String = skillFile.file.readText()

    // Returns null on success, error message on failure
    fun saveFile(relativePath: String, content: String, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            if (relativePath == "SKILL.md") {
                val name = SkillFrontmatterParser.parse(content)["name"]
                if (name != skillName) {
                    withContext(Dispatchers.Main) { onResult("不允许修改技能名称（name 字段必须为 \"$skillName\"）") }
                    return@launch
                }
            }
            val success = skillManager.saveSkillFile(skillName, relativePath, content)
            loadFiles()
            withContext(Dispatchers.Main) { onResult(if (success) null else "保存失败") }
        }
    }

    fun deleteFile(skillFile: SkillFile, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = skillManager.deleteSkillFile(skillName, skillFile.relativePath)
            if (success) loadFiles()
            withContext(Dispatchers.Main) { onResult(success) }
        }
    }

    fun importMcpConfig(onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val skillDir = skillManager.getSkillDir(skillName)
            val mcpFile = skillDir?.resolve(MCP_CONFIG_FILE)
            if (mcpFile == null || !mcpFile.exists()) {
                withContext(Dispatchers.Main) { onResult("这个 Skill 没有 mcp.json") }
                return@launch
            }

            val configs = runCatching { parseMcpServersFromJson(mcpFile.readText()) }
                .getOrElse { error ->
                    withContext(Dispatchers.Main) {
                        onResult("mcp.json 解析失败：${error.message ?: error.javaClass.simpleName}")
                    }
                    return@launch
                }

            if (configs.isEmpty()) {
                withContext(Dispatchers.Main) { onResult("mcp.json 中没有有效的 MCP 服务器配置") }
                return@launch
            }

            var importedCount = 0
            settingsStore.update { settings ->
                val existingKeys = settings.mcpServers.map { it.importKey() }.toSet()
                val newConfigs = configs.filter { it.importKey() !in existingKeys }
                importedCount = newConfigs.size
                settings.copy(mcpServers = settings.mcpServers + newConfigs)
            }

            withContext(Dispatchers.Main) {
                val message = if (importedCount > 0) {
                    "已导入 $importedCount 个 MCP 服务器"
                } else {
                    "这些 MCP 服务器已经存在"
                }
                onResult(message)
            }
        }
    }

    private fun McpServerConfig.importKey(): String = when (this) {
        is McpServerConfig.SseTransportServer -> "sse|${commonOptions.name}|$url"
        is McpServerConfig.StreamableHTTPServer -> "streamable_http|${commonOptions.name}|$url"
    }

    private companion object {
        const val MCP_CONFIG_FILE = "mcp.json"
    }
}
