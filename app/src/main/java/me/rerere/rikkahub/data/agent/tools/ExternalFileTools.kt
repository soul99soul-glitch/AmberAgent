package me.rerere.rikkahub.data.agent.tools

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.AgentToolActivityStore
import me.rerere.rikkahub.data.agent.system.AgentPermissionBroker
import me.rerere.rikkahub.data.agent.system.AgentPermissionStatus
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.io.File

private const val EXTERNAL_READ_DEFAULT_MAX_CHARS = 65_536
private const val EXTERNAL_READ_HARD_MAX_CHARS = 262_144

class ExternalFileTools(
    private val settingsStore: SettingsStore,
    private val permissionBroker: AgentPermissionBroker,
    private val activityStore: AgentToolActivityStore,
) {
    fun getTools(): List<Tool> = listOf(
        listTool,
        readTool,
        writeTool,
        deleteTool,
    )

    private val listTool = Tool(
        name = "external_file_list",
        description = "List files under a user-allowlisted external storage path. Requires Android all files access and an allowlist root.",
        parameters = {
            obj(
                "path" to stringProp("Absolute external file path under an allowlisted root."),
                "limit" to intProp("Maximum entries. Defaults to 100."),
                required = listOf("path"),
            )
        },
        execute = { input ->
            track("external_file_list", "列出外部文件", input) {
                withContext(Dispatchers.IO) {
                    val dir = resolveAllowed(input.requiredString("path"))
                    require(dir.isDirectory) { "path is not a directory: ${dir.path}" }
                    val limit = (input.int("limit") ?: 100).coerceIn(1, 500)
                    textJson {
                        put("path", dir.path)
                        put("entries", buildJsonArray {
                            dir.listFiles()
                                .orEmpty()
                                .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                                .take(limit)
                                .forEach { file ->
                                    add(buildJsonObject {
                                        put("path", file.path)
                                        put("name", file.name)
                                        put("directory", file.isDirectory)
                                        if (file.isFile) put("size_bytes", file.length())
                                    })
                                }
                        })
                    }
                }
            }
        }
    )

    private val readTool = Tool(
        name = "external_file_read",
        description = "Read a UTF-8 text file under a user-allowlisted external storage path.",
        parameters = {
            obj(
                "path" to stringProp("Absolute external file path under an allowlisted root."),
                "max_chars" to intProp("Maximum characters to return. Defaults to 65536; hard limit 262144."),
                required = listOf("path"),
            )
        },
        execute = { input ->
            track("external_file_read", "读取外部文件", input) {
                withContext(Dispatchers.IO) {
                    val file = resolveAllowed(input.requiredString("path"))
                    require(file.isFile) { "path is not a file: ${file.path}" }
                    val content = file.readText(Charsets.UTF_8)
                    val maxChars = (input.int("max_chars") ?: EXTERNAL_READ_DEFAULT_MAX_CHARS)
                        .coerceIn(1, EXTERNAL_READ_HARD_MAX_CHARS)
                    textJson {
                        put("path", file.path)
                        put("content", content.take(maxChars))
                        put("total_size_chars", content.length)
                        put("truncated", content.length > maxChars)
                        put("max_chars", maxChars)
                    }
                }
            }
        }
    )

    private val writeTool = Tool(
        name = "external_file_write",
        description = "Write a UTF-8 text file under a user-allowlisted external storage path. Requires approval.",
        parameters = {
            obj(
                "path" to stringProp("Absolute external file path under an allowlisted root."),
                "content" to stringProp("UTF-8 text content."),
                "append" to boolProp("Append instead of overwrite. Defaults to false."),
                required = listOf("path", "content"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            track("external_file_write", "写入外部文件", input.safeWritePreview()) {
                withContext(Dispatchers.IO) {
                    val file = resolveAllowed(input.requiredString("path"))
                    file.parentFile?.mkdirs()
                    if (input.boolean("append") == true) {
                        file.appendText(input.requiredString("content"), Charsets.UTF_8)
                    } else {
                        file.writeText(input.requiredString("content"), Charsets.UTF_8)
                    }
                    textJson {
                        put("path", file.path)
                        put("size_bytes", file.length())
                    }
                }
            }
        }
    )

    private val deleteTool = Tool(
        name = "external_file_delete",
        description = "Delete a file under a user-allowlisted external storage path. Requires approval.",
        parameters = {
            obj(
                "path" to stringProp("Absolute external file path under an allowlisted root."),
                required = listOf("path"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            track("external_file_delete", "删除外部文件", input) {
                withContext(Dispatchers.IO) {
                    val file = resolveAllowed(input.requiredString("path"))
                    require(file.isFile) { "path is not a file: ${file.path}" }
                    val deleted = file.delete()
                    textJson {
                        put("path", file.path)
                        put("deleted", deleted)
                    }
                }
            }
        }
    )

    private fun resolveAllowed(path: String): File {
        ensureReady()
        val file = File(path).canonicalFile
        require(file.isAbsolute) { "path must be absolute" }
        val roots = settingsStore.settingsFlow.value.agentRuntime.externalFileAccess.roots
            .mapNotNull { runCatching { File(it).canonicalFile }.getOrNull() }
        require(roots.isNotEmpty()) { "No external file access roots are configured" }
        val allowed = roots.any { root -> file.path == root.path || file.path.startsWith(root.path + File.separator) }
        require(allowed) { "Path is outside configured external file access roots" }
        return file
    }

    private fun ensureReady() {
        val setting = settingsStore.settingsFlow.value.agentRuntime.externalFileAccess
        require(setting.enabled) { "External file access is disabled in settings" }
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "All files access requires Android 11+" }
        require(permissionBroker.getStatus("manage_all_files") == AgentPermissionStatus.Granted) {
            "Android all files access is not granted"
        }
    }

    private suspend fun track(
        toolName: String,
        title: String,
        input: JsonElement,
        block: suspend () -> List<UIMessagePart>,
    ): List<UIMessagePart> {
        val toolCallId = activityStore.startTool(
            toolName = toolName,
            title = title,
            inputPreview = input.toString().take(1_200),
            runtime = "Android all files access",
        )
        return try {
            val result = block()
            activityStore.complete(toolCallId, result.previewText())
            result
        } catch (error: Throwable) {
            activityStore.fail(toolCallId, error)
            throw error
        }
    }

    private fun JsonElement.safeWritePreview(): JsonElement = buildJsonObject {
        put("path", string("path").orEmpty())
        put("append", boolean("append") ?: false)
        put("content_chars", string("content")?.length ?: 0)
    }

    private fun List<UIMessagePart>.previewText(): String =
        joinToString("\n") { part -> if (part is UIMessagePart.Text) part.text else part.toString() }
            .takeLast(1_600)

    private fun obj(vararg properties: Pair<String, JsonElement>, required: List<String>? = null) =
        InputSchema.Obj(
            properties = buildJsonObject { properties.forEach { (name, schema) -> put(name, schema) } },
            required = required,
        )

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun intProp(description: String) = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    private fun boolProp(description: String) = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }
}
