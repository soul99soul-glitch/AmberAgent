package me.rerere.rikkahub.data.agent.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.AgentToolActivityStore
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveCapability
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveEntry
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveManager
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveSearchResult
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveState

class ICloudDriveTools(
    private val manager: ICloudDriveManager,
    private val activityStore: AgentToolActivityStore,
) {
    fun getTools(): List<Tool> = listOf(
        statusTool,
        listTool,
        readTool,
        writeTool,
        searchTool,
    )

    private val statusTool = Tool(
        name = "icloud_status",
        description = "Show AmberAgent's experimental iCloud Drive Web Mount status, capability, and configured Vault path.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            val state = manager.state.value
            textJson {
                putState(state)
                put("login_url", "https://www.icloud.com/iclouddrive")
                put("note", "This is an experimental Android-only iCloud Web Mount. It uses the WebView iCloud.com session and never stores the Apple ID password.")
            }
        },
    )

    private val listTool = Tool(
        name = "icloud_list",
        description = "List files and folders inside the configured iCloud Drive Vault path. Paths are Vault-relative.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", stringProp("Vault-relative directory. Defaults to the configured Vault root."))
                }
            )
        },
        execute = { input ->
            trackICloudTool("icloud_list", "列出 iCloud", input) {
                val result = manager.list(input.string("path") ?: ".")
                textJson {
                    putState(result.state)
                    put("path", result.path)
                    putEntries(result.value)
                }
            }
        },
    )

    private val readTool = Tool(
        name = "icloud_read",
        description = "Read a UTF-8 text file from the configured iCloud Drive Vault path. Good for Obsidian Markdown notes.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", stringProp("Vault-relative file path to read."))
                },
                required = listOf("path"),
            )
        },
        execute = { input ->
            trackICloudTool("icloud_read", "读取 iCloud", input) {
                val result = manager.readText(input.requiredString("path"))
                textJson {
                    putState(result.state)
                    put("path", result.path)
                    put("content", result.value)
                }
            }
        },
    )

    private val writeTool = Tool(
        name = "icloud_write",
        description = "Write UTF-8 text to the configured iCloud Drive Vault path after the iCloud write probe has passed.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", stringProp("Vault-relative file path to write."))
                    put("content", stringProp("UTF-8 text content to write."))
                    put("overwrite", booleanProp("Replace an existing file by moving the old item to iCloud trash first. Defaults to false."))
                },
                required = listOf("path", "content"),
            )
        },
        needsApproval = true,
        execute = { input ->
            trackICloudTool("icloud_write", "写入 iCloud", input.safeInputPreview()) {
                val result = manager.writeText(
                    path = input.requiredString("path"),
                    content = input.requiredString("content"),
                    overwrite = input.boolean("overwrite") ?: false,
                )
                textJson {
                    putState(result.state)
                    put("path", result.path)
                    put("size_bytes", result.value.sizeBytes ?: 0L)
                }
            }
        },
    )

    private val searchTool = Tool(
        name = "icloud_search",
        description = "Search UTF-8 text files inside the configured iCloud Drive Vault path.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", stringProp("Text query to search for."))
                    put("path", stringProp("Vault-relative directory. Defaults to the configured Vault root."))
                    put("max_results", integerProp("Maximum results to return. Defaults to 20."))
                },
                required = listOf("query"),
            )
        },
        execute = { input ->
            trackICloudTool("icloud_search", "搜索 iCloud", input) {
                val result = manager.search(
                    query = input.requiredString("query"),
                    path = input.string("path") ?: ".",
                    maxResults = input.int("max_results") ?: 20,
                )
                textJson {
                    putState(result.state)
                    put("path", result.path)
                    putSearchResults(result.value)
                }
            }
        },
    )

    private suspend fun trackICloudTool(
        toolName: String,
        title: String,
        input: JsonElement,
        block: suspend () -> List<UIMessagePart>,
    ): List<UIMessagePart> {
        val toolCallId = activityStore.startTool(
            toolName = toolName,
            title = title,
            inputPreview = input.toString(),
            runtime = "iCloud Web Mount",
            workspace = "/icloud",
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

    private fun kotlinx.serialization.json.JsonObjectBuilder.putState(state: ICloudDriveState) {
        put("status", state.status.wireName)
        put("capability", state.capability.wireName)
        put("enabled", state.enabled)
        put("vault_path", state.vaultPath)
        put("write_enabled", state.capability == ICloudDriveCapability.READ_WRITE)
        state.message?.let { put("message", it) }
        put("updated_at_ms", state.updatedAtMillis)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putEntries(entries: List<ICloudDriveEntry>) {
        put(
            "entries",
            buildJsonArray {
                entries.forEach { entry ->
                    add(
                        buildJsonObject {
                            put("path", entry.path)
                            put("name", entry.name)
                            put("directory", entry.directory)
                            entry.sizeBytes?.let { put("size_bytes", it) }
                        }
                    )
                }
            },
        )
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putSearchResults(results: List<ICloudDriveSearchResult>) {
        put(
            "results",
            buildJsonArray {
                results.forEach { result ->
                    add(
                        buildJsonObject {
                            put("path", result.path)
                            put("line_number", result.lineNumber)
                            put("preview", result.preview)
                        }
                    )
                }
            },
        )
    }

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun booleanProp(description: String) = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    private fun integerProp(description: String) = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    private fun List<UIMessagePart>.previewText(): String =
        joinToString("\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> part.toString()
            }
        }.takeLast(1_600)

    private fun JsonElement.safeInputPreview(): JsonElement =
        buildJsonObject {
            put("path", string("path").orEmpty())
            put("overwrite", boolean("overwrite") ?: false)
            put("content_chars", string("content")?.length ?: 0)
        }
}
