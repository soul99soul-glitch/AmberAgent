package app.amber.core.memory.prompt

import app.amber.core.memory.model.MemoryRecord
import app.amber.core.model.MemoryKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

object MemoryPromptBuilder {
    fun buildMemoryContext(
        records: List<MemoryRecord>,
        debug: Boolean = false,
        debugDetails: Map<Int, String> = emptyMap(),
        locale: Locale = Locale.ENGLISH,
        today: LocalDate = LocalDate.now(),
        userProfile: String? = null,
    ): String {
        if (records.isEmpty() && userProfile.isNullOrBlank()) return ""
        return buildString {
            appendLine("<memory_context>")
            appendLine("The following memories are relevant to the current request. If they conflict with the current user message, follow the current message.")
            appendLine("Today is $today; use it when reasoning about relative dates inside memories.")
            appendLine("Use app locale ${locale.toLanguageTag().ifBlank { Locale.ENGLISH.toLanguageTag() }} for user-facing text.")
            userProfile?.trim()?.takeIf { it.isNotEmpty() }?.let { profile ->
                appendLine("<user_profile>")
                appendLine(profile)
                appendLine("</user_profile>")
            }
            records.forEachIndexed { index, record ->
                append("- ")
                append("[")
                append(record.scope.wireName)
                append("/")
                append(record.kind.wireName)
                if (record.pinned) append("/pinned")
                append(" · ")
                append(recordDate(record.updatedAt))
                append("] ")
                if (record.kind == MemoryKind.TOPIC &&
                    !record.topicTitle.isNullOrBlank()
                ) {
                    append(record.topicTitle)
                    append(": ")
                }
                append(record.content.trim().replace("\n", " "))
                record.expiresAt?.let { expires ->
                    append(" (expires ")
                    append(recordDate(expires))
                    append(")")
                }
                if (debug) {
                    append(" (id=")
                    append(record.id)
                    append(", confidence=")
                    append("%.2f".format(record.confidence))
                    debugDetails[record.id]?.let { details ->
                        append(", ")
                        append(details)
                    }
                    append(")")
                }
                if (index != records.lastIndex) appendLine()
            }
            appendLine()
            append("</memory_context>")
        }
    }

    internal fun recordDate(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs.takeIf { it > 0 } ?: System.currentTimeMillis())
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
}
