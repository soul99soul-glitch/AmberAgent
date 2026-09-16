package app.amber.core.memory.prompt

import app.amber.ai.ui.UIMessage
import app.amber.core.memory.model.MemoryRecord

object MemoryExtractionPrompt {
    fun build(
        messages: List<UIMessage>,
        sourceMessageIds: List<String>,
        locale: String,
        existingMemories: List<MemoryRecord> = emptyList(),
    ): String {
        val content = messages.joinToString("\n\n") { message ->
            """
            message_id: ${message.id}
            role: ${message.role.name.lowercase()}
            text: ${message.toText().take(4_000)}
            """.trimIndent()
        }
        val existingBlock = if (existingMemories.isEmpty()) {
            ""
        } else {
            """

            Existing memories that may overlap (id / scope / kind / content):
            ${existingMemories.joinToString("\n") { record ->
                "[#${record.id}] ${record.scope.wireName}/${record.kind.wireName} " +
                    record.content.replace('\n', ' ').take(160)
            }}
            """.trimIndent()
        }
        return """
            You extract durable memory candidates for AmberAgent.
            Locale: $locale

            Return only valid JSON:
            {
              "candidates": [
                {
                  "content": "short useful memory",
                  "scope": "short_term|long_term|core",
                  "kind": "user|feedback|project|reference|routine|note",
                  "confidence": 0.0,
                  "reason": "why this is worth remembering",
                  "expires_in_days": null,
                  "action": "add",
                  "update_memory_id": null
                }
              ]
            }

            Rules:
            - Extract only information likely useful in future conversations.
            - Do not store sensitive personal data unless the user explicitly asked to remember it.
            - Prefer short_term/project for active work and long_term for stable preferences.
            - For temporary plans, trips, meetings, deadlines, or short-lived preferences, set expires_in_days.
            - Keep expires_in_days null for stable user preferences, feedback, core facts, historical facts, and uncertain relative dates.
            - Do not invent facts.
            - At most 5 candidates.
            - "action" is "add" (default) or "update". Use "update" with "update_memory_id" set to an id from the existing-memories list when the new information refines, corrects, or supersedes that memory, instead of adding a near-duplicate. An update changes only the content; the memory keeps its own scope and kind.
            - Use these source_message_ids when relevant: ${sourceMessageIds.joinToString(", ")}.
            $existingBlock

            Conversation:
            $content
        """.trimIndent()
    }
}
