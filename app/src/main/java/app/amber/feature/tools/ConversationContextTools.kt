package app.amber.feature.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.context.ConversationContextEngine
import app.amber.core.settings.Settings
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.settings.toCompactPolicy
import app.amber.core.model.Conversation

class ConversationContextTools(
    private val contextEngine: ConversationContextEngine,
    private val conversationProvider: suspend () -> Conversation,
    private val settingsProvider: suspend () -> Settings,
    private val modelProvider: suspend () -> app.amber.ai.provider.Model?,
) {
    fun tools(): List<Tool> = listOf(
        contextStatusTool(),
        contextSearchTool(),
        contextExpandTool(),
        contextCompactTool(),
    )

    private fun contextStatusTool() = Tool(
        name = "conversation_context_status",
        description = "Inspect current conversation context pressure, compact summaries, and next automatic compression action.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = {
            val conversation = conversationProvider()
            val settings = settingsProvider()
            val payload = contextEngine.status(conversation, settings, modelProvider())
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )

    private fun contextSearchTool() = Tool(
        name = "conversation_search",
        description = "Search this conversation's original transcript and compact summaries. Use before asking the user to repeat old details.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Keyword to search in original transcript and compact summaries")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum results, default 8")
                    })
                },
                required = listOf("query")
            )
        },
        execute = { input ->
            val query = input.jsonObject["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val limit = input.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 8
            val conversation = conversationProvider()
            val results = contextEngine.search(conversation.id, query, limit.coerceIn(1, 20))
            val payload = buildJsonObject {
                put("status", "ok")
                put("query", query)
                put("results", buildJsonArray {
                    results.forEach { result ->
                        add(
                            buildJsonObject {
                                put("source", result.source)
                                put("id", result.id)
                                result.nodeIndex?.let { put("node_index", it) }
                                put("preview", result.preview)
                            }
                        )
                    }
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )

    private fun contextExpandTool() = Tool(
        name = "conversation_expand",
        description = "Expand a compact summary id or source message id back to original transcript snippets.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("source_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Compact summary id or original message id")
                    })
                    put("radius", buildJsonObject {
                        put("type", "integer")
                        put("description", "When expanding a message id, include this many neighboring messages, default 2")
                    })
                },
                required = listOf("source_id")
            )
        },
        execute = { input ->
            val sourceId = input.jsonObject["source_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val radius = input.jsonObject["radius"]?.jsonPrimitive?.intOrNull ?: 2
            val conversation = conversationProvider()
            val messages = contextEngine.expand(conversation.id, sourceId, radius)
            val payload = buildJsonObject {
                put("status", "ok")
                put("source_id", sourceId)
                put("messages", buildJsonArray {
                    messages.forEach { message ->
                        add(
                            buildJsonObject {
                                put("id", message.id.toString())
                                put("role", message.role.name.lowercase())
                                put("text", message.toText().take(12_000))
                            }
                        )
                    }
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )

    private fun contextCompactTool() = Tool(
        name = "conversation_compact",
        description = "Manually compact older context into a structured, expandable summary without deleting original messages.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("additional_prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional instructions about what to preserve")
                    })
                }
            )
        },
        execute = { input ->
            val conversation = conversationProvider()
            val settings = settingsProvider()
            val additionalPrompt = input.jsonObject["additional_prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val result = contextEngine.compactConversation(
                conversation = conversation,
                settings = settings,
                policy = settings.agentRuntime.contextCompaction.toCompactPolicy().copy(enabled = true),
                model = settings.getCurrentChatModel(),
                reason = "manual_compact_tool",
                additionalPrompt = additionalPrompt,
                force = true,
            )
            val payload = buildJsonObject {
                put("status", result.status)
                result.summaryId?.let { put("summary_id", it) }
                put("source_message_count", result.sourceMessageCount)
                put("estimated_tokens_before", result.estimatedTokensBefore)
                put("estimated_tokens_after", result.estimatedTokensAfter)
                result.error?.let { put("error", it) }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
}
