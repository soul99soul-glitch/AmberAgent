package me.rerere.rikkahub.data.memory.export

import me.rerere.rikkahub.data.memory.model.MemoryKind
import me.rerere.rikkahub.data.memory.model.MemoryRecord
import me.rerere.rikkahub.data.memory.model.MemoryScope
import me.rerere.rikkahub.data.memory.store.bucketForScope
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MemoryFrontmatterCodec {
    fun encode(record: MemoryRecord): String = buildString {
        appendLine("---")
        appendLine("id: ${quote(record.id.toString())}")
        appendLine("kind: ${quote(record.kind.wireName)}")
        appendLine("scope: ${quote(record.scope.wireName)}")
        appendLine("confidence: ${record.confidence}")
        appendLine("created_at: ${quote(formatTime(record.createdAt))}")
        appendLine("updated_at: ${quote(formatTime(record.updatedAt))}")
        record.expiresAt?.let { appendLine("expires_at: ${quote(formatTime(it))}") }
        record.sourceConversationId?.let { appendLine("source_conversation_id: ${quote(it)}") }
        appendLine("source_message_ids: [${record.sourceMessageIds.joinToString(", ") { quote(it) }}]")
        appendLine("pinned: ${record.pinned}")
        appendLine("archived: ${record.archived}")
        appendLine("---")
        appendLine()
        appendLine(record.content)
    }

    fun decode(text: String): MemoryRecord {
        val parts = text.split("---", limit = 3)
        require(parts.size >= 3) { "Invalid memory frontmatter" }
        val frontmatter = parts[1].lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf(':')
                if (index < 0) null else line.take(index).trim() to line.drop(index + 1).trim().trim('"')
            }
            .toMap()
        val content = parts[2].trim()
        val scope = MemoryScope.fromWireName(frontmatter["scope"])
        val kind = MemoryKind.fromWireName(frontmatter["kind"])
        return MemoryRecord(
            id = frontmatter["id"]?.toIntOrNull() ?: 0,
            content = content,
            scope = scope,
            kind = kind,
            assistantId = bucketForScope(scope),
            sourceConversationId = frontmatter["source_conversation_id"],
            sourceMessageIds = parseInlineList(frontmatter["source_message_ids"].orEmpty()),
            confidence = frontmatter["confidence"]?.toFloatOrNull() ?: 1f,
            pinned = frontmatter["pinned"] == "true",
            archived = frontmatter["archived"] == "true",
            createdAt = frontmatter["created_at"]?.let { Instant.parse(it).toEpochMilli() } ?: System.currentTimeMillis(),
            updatedAt = frontmatter["updated_at"]?.let { Instant.parse(it).toEpochMilli() } ?: System.currentTimeMillis(),
            expiresAt = frontmatter["expires_at"]?.let { Instant.parse(it).toEpochMilli() },
        )
    }

    private fun formatTime(timeMs: Long): String =
        Instant.ofEpochMilli(timeMs.takeIf { it > 0 } ?: System.currentTimeMillis())
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun parseInlineList(value: String): List<String> =
        Regex("\"((?:\\\\.|[^\"])*)\"").findAll(value)
            .map { match -> match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }
            .toList()
}
