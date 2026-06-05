package app.amber.core.memory.prompt

import app.amber.ai.ui.UIMessage

object MemoryExtractionPrompt {
    fun build(messages: List<UIMessage>, sourceMessageIds: List<String>, locale: String): String {
        val content = messages.joinToString("\n\n") { message ->
            """
            message_id: ${message.id}
            role: ${message.role.name.lowercase()}
            text: ${message.toText().take(4_000)}
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
                  "expires_in_days": null
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
            - Use these source_message_ids when relevant: ${sourceMessageIds.joinToString(", ")}.

            Conversation:
            $content
        """.trimIndent()
    }
}
