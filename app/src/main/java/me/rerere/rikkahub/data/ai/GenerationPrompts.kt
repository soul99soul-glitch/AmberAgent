package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalDate

internal fun buildAgentSoulPrompt(soulMarkdown: String) =
    soulMarkdown.trim().takeIf { it.isNotBlank() }?.let { soul ->
        buildString {
            appendLine()
            appendLine("**AmberAgent Soul / agents.md**")
            appendLine("The following app-level behavior guide is injected into every conversation:")
            appendLine("<agents_md>")
            appendLine(soul)
            appendLine("</agents_md>")
        }
    }.orEmpty()

internal fun buildMemoryPrompt(
    title: String,
    description: String,
    memories: List<AssistantMemory>,
) =
    buildString {
        if (memories.isEmpty()) return@buildString
        appendLine()
        append("**")
        append(title)
        append("**")
        appendLine()
        append(description)
        appendLine()
        val json = buildJsonArray {
            memories.forEach { memory ->
                add(buildJsonObject {
                    put("id", memory.id)
                    put("content", memory.content)
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }

internal fun buildCoreMemoryPrompt(memories: List<AssistantMemory>) =
    buildMemoryPrompt(
        title = "Core Memories",
        description = "These are durable AmberAgent core memories stored in the global bucket. Treat them as explicit user-approved context.",
        memories = memories,
    )

internal fun buildShortTermMemoryPrompt(memories: List<AssistantMemory>) =
    buildMemoryPrompt(
        title = "Short-Term Memories",
        description = "These are concise recent task summaries. Use them for continuity, but prefer the current conversation when there is conflict.",
        memories = memories,
    )

internal fun buildLongTermMemoryPrompt(memories: List<AssistantMemory>) =
    buildMemoryPrompt(
        title = "Long-Term Memories",
        description = "These are stable preferences, recurring interests, plans, and facts distilled for use across future conversations.",
        memories = memories,
    )

internal suspend fun buildRecentChatsPrompt(
    assistant: Assistant,
    conversationRepo: ConversationRepository
): String {
    val recentConversations = conversationRepo.getRecentConversations(
        assistantId = assistant.id,
        limit = 10,
    )
    if (recentConversations.isNotEmpty()) {
        return buildString {
            appendLine()
            append("**Recent Chats**")
            appendLine()
            append("These are some of the user's recent conversations. You can use them to understand user preferences:")
            appendLine()
            val json = buildJsonArray {
                recentConversations.forEach { conversation ->
                    add(buildJsonObject {
                        put("title", conversation.title)
                        put("last_chat", conversation.updateAt.toLocalDate())
                    })
                }
            }
            append(JsonInstantPretty.encodeToString(json))
            appendLine()
        }
    }
    return ""
}

internal suspend fun buildRecentChatsPrompt(conversationRepo: ConversationRepository): String {
    val recentConversations = conversationRepo.getRecentConversations(limit = 10)
    if (recentConversations.isNotEmpty()) {
        return buildString {
            appendLine()
            append("**Recent Chats**")
            appendLine()
            append("These are some of the user's recent conversations across AmberAgent. You can use them to understand user preferences:")
            appendLine()
            val json = buildJsonArray {
                recentConversations.forEach { conversation ->
                    add(buildJsonObject {
                        put("title", conversation.title)
                        put("last_chat", conversation.updateAt.toLocalDate())
                    })
                }
            }
            append(JsonInstantPretty.encodeToString(json))
            appendLine()
        }
    }
    return ""
}
