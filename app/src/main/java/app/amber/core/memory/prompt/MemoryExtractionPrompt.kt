package app.amber.core.memory.prompt

import app.amber.ai.ui.UIMessage
import app.amber.core.memory.model.MemoryRecord
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.datetime.toJavaLocalDateTime

object MemoryExtractionPrompt {
    fun build(
        messages: List<UIMessage>,
        sourceMessageIds: List<String>,
        locale: String,
        existingMemories: List<MemoryRecord> = emptyList(),
        today: LocalDate = LocalDate.now(),
    ): String {
        val content = messages.joinToString("\n\n") { message ->
            """
            message_id: ${message.id}
            role: ${message.role.name.lowercase()}
            created_at: ${message.createdAt.toJavaLocalDateTime().format(MESSAGE_TIME_FORMAT)}
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
            Today is $today; resolve relative dates ("next Wednesday", "下周三") into absolute dates.

            Return only valid JSON:
            {
              "candidates": [
                {
                  "content": "short useful memory, understandable without the conversation",
                  "scope": "short_term|long_term|core",
                  "kind": "user|feedback|project|reference|routine|note",
                  "confidence": 0.0,
                  "reason": "why this is worth remembering",
                  "evidence": "verbatim quote copied unchanged from a user message",
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
            - "evidence" is required: copy the user's exact words that justify the memory, unchanged (no translation or paraphrase). Keep it as short as possible while still proving the fact.
            - "content" may rewrite the fact into a standalone sentence, but every number, proper noun, and Latin-alphabet term in it must appear in the evidence or elsewhere in the conversation. Assistant messages are read-only context for resolving references like "the second option" — extract only what the user stated or confirmed.
            - At most 5 candidates.
            - "action" is "add" (default), "update", "invalidate", or "confirm", always with "update_memory_id" set to an id from the existing-memories list for the non-add actions:
              - "update": the new information refines, corrects, or supersedes that memory. The old record is archived and replaced by your new content (history is preserved).
              - "invalidate": that memory is no longer true or the user retracted it; it will be archived. Put the reason in "content".
              - "confirm": the user re-affirmed an existing memory with no new information; it only reinforces the record, "content" may restate the memory.
            - Use these source_message_ids when relevant: ${sourceMessageIds.joinToString(", ")}.
            $existingBlock

            Conversation:
            $content
        """.trimIndent()
    }

    private val MESSAGE_TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
}
