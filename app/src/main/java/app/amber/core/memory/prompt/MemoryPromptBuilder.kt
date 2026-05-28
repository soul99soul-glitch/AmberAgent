package app.amber.core.memory.prompt

import app.amber.core.memory.model.MemoryRecord

object MemoryPromptBuilder {
    fun buildMemoryContext(records: List<MemoryRecord>, debug: Boolean = false): String {
        if (records.isEmpty()) return ""
        return buildString {
            appendLine("<memory_context>")
            appendLine("以下是与当前请求相关的记忆；若与当前用户消息冲突，以当前用户消息为准。")
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
                    append(")")
                }
                if (index != records.lastIndex) appendLine()
            }
            appendLine()
            append("</memory_context>")
        }
    }
}
