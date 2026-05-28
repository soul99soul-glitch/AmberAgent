package app.amber.feature.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.workspace.WorkspaceManager

class WorkspaceTools(
    private val workspaceManager: WorkspaceManager,
    private val activityStore: AgentToolActivityStore,
) {
    fun getTools(): List<Tool> = listOf(
        fileListTool,
        fileReadTool,
        fileWriteTool,
        fileEditTool,
        fileSearchTool,
        fileMoveTool,
    )

    private val fileListTool = Tool(
        name = "file_list",
        description = "List files and folders under the user-authorized /workspace directory.",
        parameters = {
            obj(
                "path" to stringProp("Workspace-relative path. Defaults to /workspace.")
            )
        },
        execute = { input ->
            trackWorkspaceTool("file_list", "列出 workspace", input) {
                val path = input.string("path") ?: "."
                val entries = workspaceManager.list(path)
                textJson {
                    put("workspace_configured", workspaceManager.state.value.configured)
                    put("path", path)
                    put("entries", buildJsonArray {
                        entries.forEach { entry ->
                            add(buildJsonObject {
                                put("path", entry.path)
                                put("name", entry.name)
                                put("directory", entry.directory)
                                entry.sizeBytes?.let { put("size_bytes", it) }
                                entry.mimeType?.let { put("mime_type", it) }
                            })
                        }
                    })
                }
            }
        }
    )

    private val fileReadTool = Tool(
        name = "file_read",
        description = "Read a UTF-8 text file from /workspace. Common locations: notes/, reports/, ppt/, scripts/, data/.",
        parameters = {
            obj(
                "path" to stringProp("Workspace-relative file path to read."),
                "max_chars" to integerProp("Maximum characters to return. Defaults to 65536; hard limit is 262144."),
                required = listOf("path")
            )
        },
        execute = { input ->
            trackWorkspaceTool("file_read", "读取文件", input) {
                val path = input.requiredString("path")
                val content = workspaceManager.readText(path)
                listOf(UIMessagePart.Text(buildFileReadJson(path, content, input.int("max_chars")).toString()))
            }
        }
    )

    private val fileWriteTool = Tool(
        name = "file_write",
        description = "Write UTF-8 text to a file in /workspace. Creates parent folders. Put files in the matching subdirectory:\n- notes/ for .md, .txt, Markdown notes and documentation\n- reports/ for analysis reports, briefings, summaries\n- ppt/ or slides/ for presentation slides, slide specs\n- scripts/ for code, scripts, config files (.py, .sh, .json, .kt)\n- data/ for datasets, CSV, JSON data files\n- officepro/ for Feishu office documents and drafts\nOnly put files at the workspace root when none of the above apply.",
        parameters = {
            obj(
                "path" to stringProp("Workspace-relative file path to write."),
                "content" to stringProp("UTF-8 text content to write."),
                "append" to booleanProp("Append instead of replacing the file content. Defaults to false."),
                required = listOf("path", "content")
            )
        },
        needsApproval = true,
        execute = { input ->
            trackWorkspaceTool("file_write", "写入文件", input) {
                val entry = workspaceManager.writeText(
                    relativePath = input.requiredString("path"),
                    content = input.requiredString("content"),
                    append = input.boolean("append") ?: false
                )
                textJson {
                    put("path", entry.path)
                    put("size_bytes", entry.sizeBytes ?: 0L)
                }
            }
        }
    )

    private val fileEditTool = Tool(
        name = "file_edit",
        description = "Edit a text file in /workspace by replacing exact old_text with new_text.",
        parameters = {
            obj(
                "path" to stringProp("Workspace-relative file path to edit."),
                "old_text" to stringProp("Exact text to replace."),
                "new_text" to stringProp("Replacement text."),
                "replace_all" to booleanProp("Replace every match instead of the first match. Defaults to false."),
                required = listOf("path", "old_text", "new_text")
            )
        },
        needsApproval = true,
        execute = { input ->
            trackWorkspaceTool("file_edit", "编辑文件", input) {
                val result = workspaceManager.editText(
                    relativePath = input.requiredString("path"),
                    oldText = input.requiredString("old_text"),
                    newText = input.requiredString("new_text"),
                    replaceAll = input.boolean("replace_all") ?: false
                )
                textJson {
                    put("path", result.path)
                    put("replace_count", result.replaceCount)
                }
            }
        }
    )

    private val fileSearchTool = Tool(
        name = "file_search",
        description = "Search UTF-8 text files in /workspace for a query string.",
        parameters = {
            obj(
                "query" to stringProp("Text query to search for."),
                "path" to stringProp("Workspace-relative directory or file. Defaults to /workspace."),
                "max_results" to integerProp("Maximum results to return. Defaults to 50."),
                required = listOf("query")
            )
        },
        execute = { input ->
            trackWorkspaceTool("file_search", "搜索 workspace", input) {
                val results = workspaceManager.search(
                    query = input.requiredString("query"),
                    relativePath = input.string("path") ?: ".",
                    maxResults = input.int("max_results") ?: 50
                )
                textJson {
                    put("results", buildJsonArray {
                        results.forEach { result ->
                            add(buildJsonObject {
                                put("path", result.path)
                                put("line_number", result.lineNumber)
                                put("preview", result.preview)
                            })
                        }
                    })
                }
            }
        }
    )

    private val fileMoveTool = Tool(
        name = "file_move",
        description = "Move or rename a file/folder inside /workspace.",
        parameters = {
            obj(
                "source_path" to stringProp("Existing workspace-relative path."),
                "target_path" to stringProp("New workspace-relative path."),
                required = listOf("source_path", "target_path")
            )
        },
        needsApproval = true,
        execute = { input ->
            trackWorkspaceTool("file_move", "移动文件", input) {
                val entry = workspaceManager.move(
                    sourcePath = input.requiredString("source_path"),
                    targetPath = input.requiredString("target_path")
                )
                textJson {
                    put("path", entry.path)
                    put("directory", entry.directory)
                }
            }
        }
    )

    private suspend fun trackWorkspaceTool(
        toolName: String,
        title: String,
        input: JsonElement,
        block: suspend () -> List<UIMessagePart>,
    ): List<UIMessagePart> {
        val toolCallId = activityStore.startTool(
            toolName = toolName,
            title = title,
            inputPreview = input.activityInputPreview(toolName),
            runtime = "SAF workspace",
            workspace = "/workspace",
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

    private fun obj(vararg properties: Pair<String, JsonElement>, required: List<String>? = null) =
        InputSchema.Obj(
            properties = buildJsonObject {
                properties.forEach { (name, schema) -> put(name, schema) }
            },
            required = required
        )

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

    private fun JsonElement.activityInputPreview(toolName: String): String =
        when (toolName) {
            "file_write" -> buildJsonObject {
                put("path", string("path").orEmpty())
                put("append", boolean("append") ?: false)
                put("content_chars", string("content")?.length ?: 0)
            }.toString()
            "file_edit" -> buildJsonObject {
                put("path", string("path").orEmpty())
                put("old_text_chars", string("old_text")?.length ?: 0)
                put("new_text_chars", string("new_text")?.length ?: 0)
                put("replace_all", boolean("replace_all") ?: false)
            }.toString()
            else -> toString()
        }
}

internal const val FILE_READ_DEFAULT_MAX_CHARS = 65_536
internal const val FILE_READ_HARD_MAX_CHARS = 262_144

internal fun normalizeFileReadMaxChars(requested: Int?): Int {
    if (requested == null) return FILE_READ_DEFAULT_MAX_CHARS
    require(requested > 0) { "max_chars must be greater than 0" }
    return requested.coerceAtMost(FILE_READ_HARD_MAX_CHARS)
}

internal fun buildFileReadJson(
    path: String,
    content: String,
    requestedMaxChars: Int?,
) = buildJsonObject {
    val maxChars = normalizeFileReadMaxChars(requestedMaxChars)
    val truncated = content.length > maxChars
    put("path", path)
    put("content", if (truncated) content.take(maxChars) else content)
    put("total_size_chars", content.length)
    put("truncated", truncated)
    put("max_chars", maxChars)
}
