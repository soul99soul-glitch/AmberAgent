package app.amber.core.memory.prompt

import app.amber.core.memory.model.MemoryRecord
import java.util.Locale

object MemoryPromptBuilder {
    fun buildMemoryContext(
        records: List<MemoryRecord>,
        debug: Boolean = false,
        debugDetails: Map<Int, String> = emptyMap(),
        locale: Locale = Locale.ENGLISH,
    ): String {
        if (records.isEmpty()) return ""
        return buildString {
            appendLine("<memory_context>")
            appendLine("The following memories are relevant to the current request. If they conflict with the current user message, follow the current message.")
            appendLine("Use app locale ${locale.toLanguageTag().ifBlank { Locale.ENGLISH.toLanguageTag() }} for user-facing text.")
            records.forEachIndexed { index, record ->
                append("- ")
                append("[")
                append(record.scope.wireName)
                append("/")
                append(record.kind.wireName)
                if (record.pinned) append("/pinned")
                append("] ")
                append(record.content.trim().replace("\n", " "))
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
}
