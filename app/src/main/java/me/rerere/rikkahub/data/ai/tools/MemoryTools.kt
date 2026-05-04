package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate

fun buildMemoryTools(
    json: Json,
    onList: suspend (String) -> List<AssistantMemory>,
    onCreation: suspend (String, String) -> AssistantMemory,
    onUpdate: suspend (Int, String) -> AssistantMemory,
    onDelete: suspend (Int) -> Unit
): List<Tool> = listOf(
    Tool(
        name = "memory_list",
        description = "List AmberAgent memory entries by type: core, short_term, long_term, or all.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("type", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("core")
                                add("short_term")
                                add("long_term")
                                add("all")
                            }
                        )
                        put("description", "Memory type to list. Defaults to all.")
                    })
                }
            )
        },
        execute = { input ->
            val type = input.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "all"
            require(type in setOf("core", "short_term", "long_term", "all")) {
                "type must be core, short_term, long_term, or all"
            }
            val entries = if (type == "all") {
                listOf("core", "short_term", "long_term").flatMap { scope -> onList(scope).map { scope to it } }
            } else {
                onList(type).map { type to it }
            }
            val payload = buildJsonObject {
                put("type", type)
                put("count", entries.size)
                put("memories", buildJsonArray {
                    entries.forEach { (scope, memory) ->
                        add(memory.toJson(scope))
                    }
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
    Tool(
        name = "memory_write",
        description = "Create a new AmberAgent memory entry. Core and long-term memory should be stable and important; short-term memory is for current project/task continuity.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("type", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("core")
                                add("short_term")
                                add("long_term")
                            }
                        )
                        put("description", "Memory type. Defaults to long_term.")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "Memory content.")
                    })
                    put("source", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional source note.")
                    })
                },
                required = listOf("content")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            val type = input.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "long_term"
            require(type in setOf("core", "short_term", "long_term")) {
                "type must be core, short_term, or long_term"
            }
            val content = input.jsonObject["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
            val source = input.jsonObject["source"]?.jsonPrimitive?.contentOrNull
            val finalContent = if (source.isNullOrBlank()) content else "$content\nSource: $source"
            val payload = json.encodeToJsonElement(AssistantMemory.serializer(), onCreation(type, finalContent))
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
    Tool(
        name = "memory_delete",
        description = "Delete an AmberAgent memory entry by id. This is high risk and always requires explicit approval.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "Memory id to delete.")
                    })
                },
                required = listOf("id")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            val id = input.jsonObject["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
            onDelete(id)
            val payload = buildJsonObject {
                put("success", true)
                put("id", id)
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
    Tool(
        name = "memory_tool",
        description = """
            The memory tool stores layered information across AmberAgent conversations.
            Use `action` to control the operation: `create` (add), `edit` (update), `delete` (remove).
            Use `scope` for create:
            - `core`: durable identity, behavior rules, or explicit facts the user wants injected everywhere.
            - `short_term`: concise summaries of the active project or recent conversations.
            - `long_term`: stable preferences, recurring interests, plans, and factual context.
            - No relevant record: `create` + `content`
            - Existing relevant record: `edit` + `id` + `content`
            - Outdated/irrelevant record: `delete` + `id`
            Memories will automatically appear in later conversations when the corresponding memory module is enabled.
            Do not store sensitive information (e.g., ethnicity, religion, sexual orientation, political views, sex life, criminal records).
            You may store: preferred name, preferences, plans, work-related notes, chat style preferences, first chat time, etc.
            Do not show memory content directly in the conversation unless the user explicitly asks.
            Today is ${LocalDate.now().toLocalString(true)}.
            Similar memories should be merged; prefer updating existing records.

            Examples:
            {"action":"create","scope":"long_term","content":"User prefers brief replies and is more active on weekends."}
            {"action":"create","scope":"short_term","content":"Current thread is about building AmberAgent Android agent features."}
            {"action":"edit","id":12,"content":"User’s preferred name updated to “A-Xing”, prefers Chinese replies."}
            {"action":"delete","id":7}
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("create")
                                add("edit")
                                add("delete")
                            }
                        )
                        put("description", "Operation to perform: create, edit, or delete")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "The id of the memory record (required for edit/delete)")
                    })
                    put("scope", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("core")
                                add("short_term")
                                add("long_term")
                            }
                        )
                        put("description", "The memory scope for create. Defaults to long_term.")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The content of the memory record (required for create/edit)")
                    })
                },
                required = listOf("action")
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            val payload = when (action) {
                "create" -> {
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    val scope = params["scope"]?.jsonPrimitive?.contentOrNull ?: "long_term"
                    require(scope in setOf("core", "short_term", "long_term")) {
                        "scope must be one of [core, short_term, long_term]"
                    }
                    json.encodeToJsonElement(AssistantMemory.serializer(), onCreation(scope, content))
                }

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    json.encodeToJsonElement(AssistantMemory.serializer(), onUpdate(id, content))
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    onDelete(id)
                    buildJsonObject {
                        put("success", true)
                        put("id", id)
                    }
                }

                else -> error("unknown action: $action, must be one of [create, edit, delete]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)

private fun AssistantMemory.toJson(scope: String) = buildJsonObject {
    put("id", id)
    put("type", scope)
    put("content", content)
}
