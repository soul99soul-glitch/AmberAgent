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
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveLoginSnapshot
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveManager
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveSearchResult
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveState
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveWebEndpoints

class ICloudDriveTools(
    private val manager: ICloudDriveManager,
    private val activityStore: AgentToolActivityStore,
) {
    fun getTools(): List<Tool> = listOf(
        statusTool,
        listTool,
        statTool,
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
            val login = manager.loginSnapshot()
            textJson {
                putState(state)
                putLoginSnapshot(login)
                put("next_action", manager.nextAction(state))
                put("login_url", ICloudDriveWebEndpoints.GLOBAL.loginUrl)
                put("china_login_url", ICloudDriveWebEndpoints.CHINA.loginUrl)
                put("note", "This experimental Android-only iCloud Web Mount supports global iCloud and China iCloud WebView sessions. It never stores the Apple ID password.")
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
                    put("max_entries", integerProp("Maximum entries to return. Defaults to 100; hard limit 500."))
                }
            )
        },
        execute = { input ->
            trackICloudTool("icloud_list", "列出 iCloud", input) {
                val result = manager.list(
                    path = input.string("path") ?: ".",
                    maxEntries = input.int("max_entries") ?: 100,
                )
                textJson {
                    put("ok", true)
                    putState(result.state)
                    put("path", result.path)
                    put("total_entries", result.value.totalEntries)
                    put("truncated", result.value.truncated)
                    putEntries(result.value.entries)
                }
            }
        },
    )

    private val statTool = Tool(
        name = "icloud_stat",
        description = "Inspect one iCloud Drive file or folder by Vault-relative path or node_ref returned from icloud_list/search.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", stringProp("Vault-relative path. Optional when node_ref is supplied."))
                    put("node_ref", stringProp("Opaque node_ref returned by icloud_list or icloud_search."))
                }
            )
        },
        execute = { input ->
            trackICloudTool("icloud_stat", "查看 iCloud 节点", input) {
                val result = manager.stat(
                    path = input.string("path"),
                    nodeRef = input.string("node_ref"),
                )
                textJson {
                    put("ok", true)
                    putState(result.state)
                    put("path", result.path)
                    put("match_level", result.value.matchLevel)
                    putEntry("entry", result.value.entry)
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
                    put("path", stringProp("Vault-relative file path to read. Optional when node_ref is supplied."))
                    put("node_ref", stringProp("Opaque node_ref returned by icloud_list or icloud_search."))
                    put("start_char", integerProp("Start offset for chunked reads. Defaults to 0."))
                    put("max_chars", integerProp("Maximum characters to return. Defaults to 65536; hard limit 262144."))
                },
            )
        },
        execute = { input ->
            trackICloudTool("icloud_read", "读取 iCloud", input) {
                val result = manager.readText(
                    path = input.string("path"),
                    nodeRef = input.string("node_ref"),
                )
                val startChar = (input.int("start_char") ?: 0).coerceAtLeast(0)
                val maxChars = (input.int("max_chars") ?: 65_536).coerceIn(1, 262_144)
                val content = result.value.content
                val endExclusive = (startChar + maxChars).coerceAtMost(content.length)
                val slice = if (startChar >= content.length) "" else content.substring(startChar, endExclusive)
                textJson {
                    put("ok", true)
                    putState(result.state)
                    put("path", result.path)
                    put("match_level", result.value.matchLevel)
                    putEntry("entry", result.value.entry)
                    put("content", slice)
                    put("start_char", startChar)
                    put("total_size_chars", content.length)
                    put("truncated", endExclusive < content.length)
                    if (endExclusive < content.length) put("next_start_char", endExclusive)
                    put("max_chars", maxChars)
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
                    put("ok", true)
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
                    put("ok", true)
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
            classifyICloudError(error)?.let { classified ->
                val payload = textJson {
                    put("ok", false)
                    putState(manager.state.value)
                    put(
                        "error",
                        buildJsonObject {
                            put("code", classified.code)
                            put("message", classified.message)
                            put("hint", classified.hint)
                        },
                    )
                }
                activityStore.complete(toolCallId, payload.previewText())
                return payload
            }
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

    private fun kotlinx.serialization.json.JsonObjectBuilder.putLoginSnapshot(login: ICloudDriveLoginSnapshot) {
        put("login_detected", login.loginDetected)
        put("endpoint_hint", login.endpointHint?.id ?: "unknown")
        put("endpoint_display_name", login.endpointHint?.displayName ?: "unknown")
        put("upload_token_detected", login.hasUploadToken)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putEntries(entries: List<ICloudDriveEntry>) {
        put(
            "entries",
            buildJsonArray {
                entries.forEach { entry ->
                    add(entry.toJson())
                }
            },
        )
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putEntry(key: String, entry: ICloudDriveEntry) {
        put(key, entry.toJson())
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putSearchResults(results: List<ICloudDriveSearchResult>) {
        put(
            "results",
            buildJsonArray {
                results.forEach { result ->
                    add(
                        buildJsonObject {
                            put("path", result.path)
                            result.nodeRef?.let { put("node_ref", it) }
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

    private fun ICloudDriveEntry.toJson() = buildJsonObject {
        put("path", path)
        nodeRef?.let { put("node_ref", it) }
        put("name", name)
        put("directory", directory)
        sizeBytes?.let { put("size_bytes", it) }
    }

    private data class ClassifiedICloudError(
        val code: String,
        val message: String,
        val hint: String,
    )

    private fun classifyICloudError(error: Throwable): ClassifiedICloudError? {
        val message = error.message ?: error.toString()
        val lower = message.lowercase()
        return when {
            "cookie" in lower || "not authenticated" in lower || "missing dsinfo" in lower ->
                ClassifiedICloudError("login_required", message, "Open iCloud login, finish 2FA, then run icloud_status or the read probe.")
            "device consent" in lower || "pcs" in lower ->
                ClassifiedICloudError("device_consent_required", message, "Approve the Apple private access prompt, then run the read probe again.")
            "path not found" in lower ->
                ClassifiedICloudError("path_not_found", message, "Run icloud_list on the parent folder or use a node_ref from icloud_search/list.")
            "not a directory" in lower ->
                ClassifiedICloudError("not_directory", message, "Call icloud_stat to confirm the target type, then read the file or list its parent.")
            "not a file" in lower || "missing docwsid" in lower ->
                ClassifiedICloudError("not_text_file", message, "Call icloud_stat first; only text-like files can be read by icloud_read.")
            "write is disabled" in lower || "write probe" in lower ->
                ClassifiedICloudError("write_probe_required", message, "Run the iCloud write probe in settings before calling icloud_write.")
            "target already exists" in lower || "already exists" in lower ->
                ClassifiedICloudError("conflict", message, "Pass overwrite=true only if replacing the file is intended.")
            "webauth-validate" in lower || "upload token" in lower ->
                ClassifiedICloudError("upload_token_missing", message, "Reopen iCloud login so the upload validation cookie is refreshed, then run the write probe.")
            "session validation failed" in lower || "service is not available" in lower || "request failed" in lower ->
                ClassifiedICloudError("endpoint_unavailable", message, "Try the other iCloud login region, then run the read probe again.")
            else -> null
        }
    }
}
