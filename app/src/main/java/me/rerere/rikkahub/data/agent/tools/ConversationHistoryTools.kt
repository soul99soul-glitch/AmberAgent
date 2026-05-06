package me.rerere.rikkahub.data.agent.tools

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.history.SessionAccessGrantStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

class ConversationHistoryTools(
    private val conversationRepo: ConversationRepository,
    private val currentConversationProvider: suspend () -> Conversation,
    private val grantStore: SessionAccessGrantStore,
) {
    fun tools(): List<Tool> = listOf(
        sessionListTool(),
        sessionSearchTool(),
        sessionReadTool(),
        sessionExpandTool(),
    )

    private fun sessionListTool() = Tool(
        name = "session_list",
        description = "List historical AmberAgent sessions by metadata only. Use this before asking to read old session content.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", stringProp("Optional title keyword."))
                    put("scope", stringProp("current_assistant or all. Default current_assistant."))
                    put("limit", intProp("Maximum sessions, default 12, capped at 50."))
                }
            )
        },
        execute = { input ->
            val query = input.obj["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val scope = input.scope()
            val limit = input.obj["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 50) ?: 12
            val current = currentConversationProvider()
            val sessions = if (query.isBlank()) {
                if (scope == "all") {
                    conversationRepo.getRecentConversations(limit)
                } else {
                    conversationRepo.getRecentConversations(current.assistantId, limit)
                }
            } else {
                val flow = if (scope == "all") {
                    conversationRepo.searchConversations(query)
                } else {
                    conversationRepo.searchConversationsOfAssistant(current.assistantId, query)
                }
                flow.first().take(limit)
            }
            listOf(UIMessagePart.Text(buildJsonObject {
                put("status", "ok")
                put("scope", scope)
                put("query", query)
                put("sessions", buildJsonArray {
                    sessions.forEach { add(it.toSessionSummary()) }
                })
            }.toString()))
        }
    )

    private fun sessionSearchTool() = Tool(
        name = "session_search",
        description = "Search snippets across historical sessions. Returns short excerpts, not full transcripts.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", stringProp("Keyword query to search in historical transcript text."))
                    put("scope", stringProp("current_assistant or all. Default current_assistant."))
                    put("session_ids", arrayProp("Optional list of session ids to restrict search."))
                    put("limit", intProp("Maximum hits, default 10, capped at 30."))
                },
                required = listOf("query")
            )
        },
        execute = { input ->
            val query = input.obj["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val scope = input.scope()
            val limit = input.obj["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 30) ?: 10
            val sessionIds = input.stringArray("session_ids").toSet()
            val currentAssistantId = currentConversationProvider().assistantId
            val hits = mutableListOf<me.rerere.rikkahub.data.db.fts.MessageSearchResult>()
            for (hit in conversationRepo.searchMessages(query)) {
                if (sessionIds.isNotEmpty() && hit.conversationId !in sessionIds) continue
                if (scope != "all") {
                    val hitConversation = runCatching {
                        conversationRepo.getConversationById(Uuid.parse(hit.conversationId))
                    }.getOrNull()
                    if (hitConversation?.assistantId != currentAssistantId) continue
                }
                hits += hit
                if (hits.size >= limit) break
            }
            listOf(UIMessagePart.Text(buildJsonObject {
                put("status", "ok")
                put("scope", scope)
                put("query", query)
                put("hits", buildJsonArray {
                    hits.forEach { hit ->
                        add(buildJsonObject {
                            put("session_id", hit.conversationId)
                            put("title", hit.title)
                            put("message_id", hit.messageId)
                            put("node_id", hit.nodeId)
                            put("updated_at", hit.updateAt.toEpochMilli())
                            put("snippet", hit.snippet.take(1_200))
                        })
                    }
                })
            }.toString()))
        }
    )

    private fun sessionReadTool() = Tool(
        name = "session_read",
        description = "Read a bounded transcript from a specified historical session. Requires approval unless a valid session access grant is supplied.",
        needsApproval = true,
        allowsAutoApproval = true,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Historical session id returned by session_list or session_search."))
                    put("grant_id", stringProp("Optional SessionAccessGrant id for history subagents."))
                    put("max_chars", intProp("Maximum transcript chars, default 20000, capped at 60000."))
                    put("max_messages", intProp("Maximum messages, default 80, capped at 200."))
                    put("include_tools", boolProp("Include compact tool input/output previews. Default false."))
                },
                required = listOf("session_id")
            )
        },
        execute = execute@ { input ->
            val sessionId = input.obj["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val grantId = input.obj["grant_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val requestedChars = input.obj["max_chars"]?.jsonPrimitive?.intOrNull?.coerceIn(1_000, 60_000) ?: 20_000
            val maxMessages = input.obj["max_messages"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 200) ?: 80
            val includeTools = input.obj["include_tools"]?.jsonPrimitive?.booleanOrNull ?: false
            val grantValidation = validateGrant(grantId, sessionId, requestedChars)
            if (grantValidation is GrantCheck.Denied) {
                return@execute listOf(UIMessagePart.Text(errorPayload("grant_denied", grantValidation.reason).toString()))
            }
            val maxChars = (grantValidation as GrantCheck.Allowed).maxChars
            val conversation = loadConversation(sessionId)
                ?: return@execute listOf(UIMessagePart.Text(errorPayload("not_found", "Unknown session_id: $sessionId").toString()))
            val messages = conversation.messageNodes.flatMap { it.messages }.take(maxMessages)
            val transcript = buildTranscript(messages, includeTools, maxChars)
            if (grantId.isNotBlank()) grantStore.recordUse(grantId, transcript.length)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("status", "ok")
                put("session", conversation.toSessionSummary())
                put("message_count", messages.size)
                put("max_chars", maxChars)
                put("truncated", transcript.length >= maxChars)
                put("transcript", transcript)
            }.toString()))
        }
    )

    private fun sessionExpandTool() = Tool(
        name = "session_expand",
        description = "Expand original messages around a message_id or node_id in a specified historical session. Requires approval unless a valid grant is supplied.",
        needsApproval = true,
        allowsAutoApproval = true,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", stringProp("Historical session id."))
                    put("source_id", stringProp("Message id or node id to expand."))
                    put("grant_id", stringProp("Optional SessionAccessGrant id for history subagents."))
                    put("radius", intProp("Neighboring message radius, default 2, capped at 8."))
                    put("max_chars", intProp("Maximum output chars, default 20000, capped at 60000."))
                    put("include_tools", boolProp("Include compact tool previews. Default false."))
                },
                required = listOf("session_id", "source_id")
            )
        },
        execute = execute@ { input ->
            val sessionId = input.obj["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val sourceId = input.obj["source_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val grantId = input.obj["grant_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val radius = input.obj["radius"]?.jsonPrimitive?.intOrNull?.coerceIn(0, 8) ?: 2
            val requestedChars = input.obj["max_chars"]?.jsonPrimitive?.intOrNull?.coerceIn(1_000, 60_000) ?: 20_000
            val includeTools = input.obj["include_tools"]?.jsonPrimitive?.booleanOrNull ?: false
            val grantValidation = validateGrant(grantId, sessionId, requestedChars)
            if (grantValidation is GrantCheck.Denied) {
                return@execute listOf(UIMessagePart.Text(errorPayload("grant_denied", grantValidation.reason).toString()))
            }
            val maxChars = (grantValidation as GrantCheck.Allowed).maxChars
            val conversation = loadConversation(sessionId)
                ?: return@execute listOf(UIMessagePart.Text(errorPayload("not_found", "Unknown session_id: $sessionId").toString()))
            val indexed = conversation.messageNodes.flatMapIndexed { nodeIndex, node ->
                node.messages.mapIndexed { messageIndex, message ->
                    IndexedHistoryMessage(
                        nodeId = node.id.toString(),
                        nodeIndex = nodeIndex,
                        messageIndex = messageIndex,
                        message = message,
                    )
                }
            }
            val matchIndex = indexed.indexOfFirst { it.message.id.toString() == sourceId || it.nodeId == sourceId }
            if (matchIndex < 0) {
                return@execute listOf(UIMessagePart.Text(errorPayload("not_found", "source_id not found in session.").toString()))
            }
            val selected = indexed.subList(
                (matchIndex - radius).coerceAtLeast(0),
                (matchIndex + radius + 1).coerceAtMost(indexed.size)
            )
            val transcript = buildTranscript(selected.map { it.message }, includeTools, maxChars)
            if (grantId.isNotBlank()) grantStore.recordUse(grantId, transcript.length)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("status", "ok")
                put("session", conversation.toSessionSummary())
                put("source_id", sourceId)
                put("messages", buildJsonArray {
                    selected.forEach { item ->
                        add(buildJsonObject {
                            put("node_id", item.nodeId)
                            put("node_index", item.nodeIndex)
                            put("message_index", item.messageIndex)
                            put("message_id", item.message.id.toString())
                            put("role", item.message.role.name.lowercase())
                            put("text", formatMessage(item.message, includeTools).take(8_000))
                        })
                    }
                })
                put("truncated", transcript.length >= maxChars)
            }.toString()))
        }
    )

    private fun validateGrant(grantId: String, sessionId: String, requestedChars: Int): GrantCheck {
        if (grantId.isBlank()) return GrantCheck.Allowed(requestedChars)
        return when (val validation = grantStore.validate(grantId, sessionId, requestedChars)) {
            is SessionAccessGrantStore.GrantValidation.Allowed -> GrantCheck.Allowed(validation.allowedChars)
            is SessionAccessGrantStore.GrantValidation.Denied -> GrantCheck.Denied(validation.reason)
        }
    }

    private suspend fun loadConversation(sessionId: String): Conversation? =
        runCatching { conversationRepo.getConversationById(Uuid.parse(sessionId)) }.getOrNull()

    private fun buildTranscript(messages: List<UIMessage>, includeTools: Boolean, maxChars: Int): String {
        val builder = StringBuilder()
        for (message in messages) {
            val line = buildString {
                append("[")
                append(message.role.name.lowercase())
                append(" ")
                append(message.id)
                append("] ")
                append(formatMessage(message, includeTools))
            }.trim()
            if (line.isBlank()) continue
            if (builder.length + line.length + 2 > maxChars) {
                builder.append("\n...[truncated]...")
                break
            }
            builder.append(line).append("\n\n")
        }
        return builder.toString().trim().take(maxChars)
    }

    private fun formatMessage(message: UIMessage, includeTools: Boolean): String {
        val parts = message.parts.mapNotNull { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                is UIMessagePart.Tool -> if (includeTools) {
                    val outputText = part.output.filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n") { it.text }
                    "[tool:${part.toolName} input=${part.input.take(800)} output_tail=${outputText.takeLast(1_200)}]"
                } else {
                    "[tool:${part.toolName} executed=${part.isExecuted}]"
                }
                is UIMessagePart.Image -> "[image]"
                is UIMessagePart.Video -> "[video]"
                is UIMessagePart.Audio -> "[audio]"
                is UIMessagePart.Document -> "[document:${part.fileName}]"
                is UIMessagePart.Reasoning -> "[reasoning:${part.reasoning.take(300)}]"
                else -> null
            }
        }
        return parts.joinToString("\n").trim()
    }

    private fun Conversation.toSessionSummary() = buildJsonObject {
        put("session_id", id.toString())
        put("assistant_id", assistantId.toString())
        put("title", title)
        put("created_at", createAt.toEpochMilli())
        put("updated_at", updateAt.toEpochMilli())
        put("updated_date", DATE_FORMATTER.format(updateAt.atZone(ZoneId.systemDefault())))
        put("message_nodes", messageNodes.size)
        put("is_pinned", isPinned)
    }

    private fun errorPayload(code: String, message: String) = buildJsonObject {
        put("status", "failed")
        put("code", code)
        put("error", message)
    }

    private val JsonElement.obj get() = jsonObject

    private fun JsonElement.scope(): String =
        obj["scope"]?.jsonPrimitive?.contentOrNull?.takeIf { it == "all" } ?: "current_assistant"

    private fun JsonElement.stringArray(name: String): List<String> =
        runCatching { obj[name]?.jsonArray }.getOrNull()
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
            .orEmpty()

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

    private fun arrayProp(description: String) = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", buildJsonObject { put("type", "string") })
    }

    private data class IndexedHistoryMessage(
        val nodeId: String,
        val nodeIndex: Int,
        val messageIndex: Int,
        val message: UIMessage,
    )

    private sealed interface GrantCheck {
        data class Allowed(val maxChars: Int) : GrantCheck
        data class Denied(val reason: String) : GrantCheck
    }

    private companion object {
        val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    }
}
