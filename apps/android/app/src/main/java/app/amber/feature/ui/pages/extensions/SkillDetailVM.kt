package app.amber.feature.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.amber.core.ai.mcp.McpImportApplyResult
import app.amber.core.ai.mcp.McpImportPreparation
import app.amber.core.ai.mcp.McpImportPreview
import app.amber.core.ai.mcp.McpImportTransaction
import app.amber.core.ai.mcp.RealMcpConnectPreflight
import app.amber.core.ai.mcp.parseMcpServersFromJson
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.files.SkillFrontmatterParser
import app.amber.core.files.SkillManager
import app.amber.feature.runtime.CapabilityBackedCasLedger
import app.amber.feature.runtime.CapabilityPermissionStore
import java.io.File
import java.util.UUID

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
    private val capabilityPermissionStore: CapabilityPermissionStore,
) : ViewModel() {

    private val _tree = MutableStateFlow<List<SkillFileNode>>(emptyList())
    val tree = _tree.asStateFlow()

    private val _mcpConfig = MutableStateFlow<SkillMcpConfigState?>(null)
    val mcpConfig = _mcpConfig.asStateFlow()

    private val _mcpImportPreview = MutableStateFlow<McpImportPreview?>(null)
    val mcpImportPreview = _mcpImportPreview.asStateFlow()

    private data class PendingMcpImport(
        val file: File,
        val digest: String,
    )

    private var pendingMcpImport: PendingMcpImport? = null

    private var skillName = ""

    fun init(name: String) {
        if (skillName == name) return
        clearMcpImportPreview()
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

    fun previewMcpConfig(onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            clearMcpImportPreview()
            val skillDir = skillManager.getSkillDir(skillName)
            val mcpFile = skillDir?.resolve(MCP_CONFIG_FILE)
            if (mcpFile == null || !mcpFile.exists()) {
                withContext(Dispatchers.Main) { onResult("这个 Skill 没有 mcp.json") }
                return@launch
            }

            val rawJson = try {
                mcpFile.readText()
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    onResult("无法读取 mcp.json：${error.message ?: error.javaClass.simpleName}")
                }
                return@launch
            }
            val transaction = createMcpImportTransaction()
            val preparation = transaction.prepare(rawJson)
            val ready = when (preparation) {
                is McpImportPreparation.Ready -> preparation
                is McpImportPreparation.Rejected -> {
                    withContext(Dispatchers.Main) {
                        onResult(preparation.errors.joinToString("\n"))
                    }
                    return@launch
                }
            }
            pendingMcpImport = PendingMcpImport(mcpFile, ready.preview.digest)
            _mcpImportPreview.value = ready.preview
            withContext(Dispatchers.Main) {
                onResult(null)
            }
        }
    }

    fun confirmMcpConfig(onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val pending = pendingMcpImport
            if (pending == null) {
                withContext(Dispatchers.Main) { onResult("请先预览并确认 MCP 导入内容") }
                return@launch
            }

            val rawJson = try {
                pending.file.readText()
            } catch (error: Exception) {
                clearMcpImportPreview()
                withContext(Dispatchers.Main) {
                    onResult("无法读取 mcp.json：${error.message ?: error.javaClass.simpleName}")
                }
                return@launch
            }
            val transaction = createMcpImportTransaction()
            val preparation = transaction.prepare(rawJson)
            val ready = when (preparation) {
                is McpImportPreparation.Ready -> preparation
                is McpImportPreparation.Rejected -> {
                    clearMcpImportPreview()
                    withContext(Dispatchers.Main) {
                        onResult(preparation.errors.joinToString("\n"))
                    }
                    return@launch
                }
            }
            if (ready.preview.digest != pending.digest) {
                clearMcpImportPreview()
                withContext(Dispatchers.Main) {
                    onResult("mcp.json 在预览后发生变化，请重新预览并确认")
                }
                return@launch
            }

            // This approval is reached only from the user's confirm action;
            // apply then re-prepares the same freshly-read candidate.
            val sessionId = UUID.randomUUID().toString()
            transaction.approve(ready.preview, sessionId, source = "skill_ui")
            val result = transaction.apply(rawJson, sessionId)
            clearMcpImportPreview()
            withContext(Dispatchers.Main) {
                onResult(
                    when (result) {
                        is McpImportApplyResult.Applied -> "已导入 ${result.serverCount} 个 MCP 服务器"
                        is McpImportApplyResult.Stale -> result.reason
                        is McpImportApplyResult.Rejected -> result.errors.joinToString("\n")
                    }
                )
            }
        }
    }

    fun clearMcpImportPreview() {
        pendingMcpImport = null
        _mcpImportPreview.value = null
    }

    private fun createMcpImportTransaction() = McpImportTransaction(
        preflight = RealMcpConnectPreflight(),
        approvalLedger = CapabilityBackedCasLedger(capabilityPermissionStore),
        existingServerNames = {
            settingsStore.settingsFlow.value.mcpServers
                .map { it.commonOptions.name }
                .toSet()
        },
        publish = { configs ->
            settingsStore.update { settings ->
                settings.copy(mcpServers = settings.mcpServers + configs)
            }
        },
    )

    private companion object {
        const val MCP_CONFIG_FILE = "mcp.json"
    }
}
