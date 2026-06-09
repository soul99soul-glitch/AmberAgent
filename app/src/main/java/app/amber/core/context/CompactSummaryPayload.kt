package app.amber.core.context

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal data class CompactSummaryPayload(
    val schemaVersion: Int,
    val timelineSummary: String,
    val handoffMarkdown: String,
    val coveredCompactIds: List<String>,
    val sourceMessageIds: List<String>,
    val createdAt: Long,
)

internal object CompactSummaryPayloads {
    const val SCHEMA_VERSION = 2

    private const val MAX_TIMELINE_SUMMARY_CHARS = 1_200
    private const val MAX_HANDOFF_CHARS = 24_000
    private val json = Json { ignoreUnknownKeys = true }
    private val whitespace = Regex("\\s+")
    private val sentenceEnd = Regex("""[。！？.!?]+""")
    private val legacyKeys = listOf(
        "goals",
        "facts",
        "decisions",
        "open_tasks",
        "failed_attempts",
        "tool_results",
        "entities",
        "timeline",
        "source_message_ids",
    )
    private val payloadKeys = setOf(
        "schema_version",
        "timeline_summary",
        "handoff_markdown",
        "covered_compact_ids",
        "source_message_ids",
        "created_at",
    )

    fun remapCoveredCompactIds(summary: String, idMapping: Map<String, String>): String {
        if (idMapping.isEmpty()) return summary
        val cleaned = cleanRaw(summary)
        val located = locateJsonObject(cleaned) ?: return summary
        val obj = runCatching { json.parseToJsonElement(located.jsonText).jsonObject }.getOrNull()
            ?: return summary
        val hasV2Shape = obj["timeline_summary"] != null || obj["handoff_markdown"] != null ||
            obj.intValue("schema_version") == SCHEMA_VERSION
        if (!hasV2Shape) return summary
        val remappedCoveredIds = obj.stringList("covered_compact_ids")
            .mapNotNull { id -> idMapping[id] }
            .distinct()
        val remapped = buildJsonObject {
            obj.forEach { (key, value) ->
                when (key) {
                    "covered_compact_ids" -> Unit
                    "handoff_markdown" -> put(
                        key,
                        JsonPrimitive(
                            value.stringValue()
                                ?.replaceCompactIdReferences(idMapping)
                                ?: value.toString().replaceCompactIdReferences(idMapping)
                        )
                    )
                    else -> put(key, value)
                }
            }
            putStringArray("covered_compact_ids", remappedCoveredIds)
        }.toString()
        val preamble = cleaned.substring(0, located.startIndex).trim()
        return if (preamble.isBlank()) remapped else "$preamble\n$remapped"
    }

    fun normalizeModelOutput(
        parser: Json,
        summary: String,
        sourceMessageIds: List<String>,
        coveredCompactIds: List<String>,
        createdAt: Long,
    ): String? {
        val cleaned = cleanRaw(summary)
        if (cleaned.isBlank()) return null

        val located = locateJsonObject(cleaned)
        val parsed = located?.jsonText
            ?.let { runCatching { parser.parseToJsonElement(it).jsonObject }.getOrNull() }
        val preamble = located
            ?.let { cleaned.substring(0, it.startIndex) }
            ?.cleanHumanText()
            .orEmpty()

        val timelineSummary = when {
            parsed != null -> timelineFromParsed(parsed, preamble)
            cleaned.looksLikeJsonFragment() -> "Conversation history was compacted, but the model returned malformed JSON."
            else -> cleaned.cleanHumanText().ifBlank {
                "Conversation history was compacted, but the model returned no readable summary."
            }
        }.coerceTimelineSummary()
        val handoffMarkdown = when {
            parsed != null -> parsed.stringValue("handoff_markdown")
                ?.cleanMarkdown()
                ?.takeIf { it.isNotBlank() }
                ?: handoffFromLegacy(parsed, timelineSummary, sourceMessageIds)
            else -> plainTextHandoff(timelineSummary, sourceMessageIds)
        }.take(MAX_HANDOFF_CHARS)

        return buildJsonObject {
            put("schema_version", SCHEMA_VERSION)
            put("timeline_summary", timelineSummary)
            put("handoff_markdown", handoffMarkdown)
            putStringArray("covered_compact_ids", coveredCompactIds.distinct())
            putStringArray("source_message_ids", sourceMessageIds.distinct())
            put("created_at", createdAt)
            parsed?.let { putLegacyFields(it, sourceMessageIds) }
        }.toString()
    }

    fun fallbackPayload(
        summary: String,
        sourceMessageIds: List<String>,
        coveredCompactIds: List<String> = emptyList(),
        createdAt: Long = System.currentTimeMillis(),
        sourceContent: String = "",
        carriedHandoffMarkdown: String = "",
    ): String {
        val timelineSummary = summary.cleanHumanText()
            .takeUnless { summary.cleanRawForFallback().looksLikeJsonFragment() }
            .orEmpty()
            .ifBlank { fallbackTimelineFromSource(sourceContent) }
            .ifBlank { fallbackGenericSummary(sourceContent) }
            .coerceTimelineSummary()
        val safeCoveredCompactIds = coveredCompactIds
            .takeIf { carriedHandoffMarkdown.isNotBlank() }
            .orEmpty()
        return buildJsonObject {
            put("schema_version", SCHEMA_VERSION)
            put("timeline_summary", timelineSummary)
            put(
                "handoff_markdown",
                plainTextHandoff(timelineSummary, sourceMessageIds, carriedHandoffMarkdown).take(MAX_HANDOFF_CHARS)
            )
            putStringArray("covered_compact_ids", safeCoveredCompactIds.distinct())
            putStringArray("source_message_ids", sourceMessageIds.distinct())
            put("created_at", createdAt)
        }.toString()
    }

    fun parse(summary: String): CompactSummaryPayload? {
        val cleaned = cleanRaw(summary)
        val located = locateJsonObject(cleaned) ?: return null
        val obj = runCatching { json.parseToJsonElement(located.jsonText).jsonObject }.getOrNull()
            ?: return null
        val hasV2Shape = obj["timeline_summary"] != null || obj["handoff_markdown"] != null ||
            obj.intValue("schema_version") == SCHEMA_VERSION
        if (!hasV2Shape) return null
        val preamble = cleaned.substring(0, located.startIndex).cleanHumanText()
        val timeline = timelineFromParsed(obj, preamble).coerceTimelineSummary()
        val handoff = obj.stringValue("handoff_markdown")
            ?.cleanMarkdown()
            ?.takeIf { it.isNotBlank() }
            ?: handoffFromLegacy(obj, timeline, obj.stringList("source_message_ids"))
        return CompactSummaryPayload(
            schemaVersion = obj.intValue("schema_version") ?: SCHEMA_VERSION,
            timelineSummary = timeline,
            handoffMarkdown = handoff,
            coveredCompactIds = obj.stringList("covered_compact_ids"),
            sourceMessageIds = obj.stringList("source_message_ids"),
            createdAt = obj.longValue("created_at") ?: 0L,
        )
    }

    fun timelineSummary(summary: String): String? {
        parse(summary)?.timelineSummary?.takeIf { it.isNotBlank() }?.let { return it }
        val cleaned = cleanRaw(summary)
        if (cleaned.isBlank()) return null
        val located = locateJsonObject(cleaned)
        val preamble = located
            ?.let { cleaned.substring(0, it.startIndex).cleanHumanText() }
            .orEmpty()
        if (preamble.isNotBlank()) return preamble.coerceTimelineSummary()
        val parsed = located?.jsonText
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        if (parsed != null) return timelineFromParsed(parsed, "").coerceTimelineSummary()
        if (cleaned.looksLikeJsonFragment()) return null
        return cleaned.cleanHumanText().takeIf { it.isNotBlank() }?.coerceTimelineSummary()
    }

    fun searchableText(summary: String): String {
        val payload = parse(summary)
        if (payload != null) {
            return buildString {
                appendLine(payload.timelineSummary)
                appendLine(payload.handoffMarkdown)
            }.trim()
        }
        return timelineSummary(summary) ?: summary
    }

    fun injectionText(compact: ConversationCompact): String =
        injectionText(
            id = compact.id,
            summary = compact.summary,
            sourceMessageIds = compact.sourceMessageIds,
        )

    fun injectionText(id: String, summary: String, sourceMessageIds: List<String>): String {
        val payload = parse(summary)
        val handoff = payload?.handoffMarkdown?.takeIf { it.isNotBlank() }
            ?: timelineSummary(summary)
            ?: summary.cleanHumanText()
        val covered = payload?.coveredCompactIds.orEmpty()
        return buildString {
            appendLine("[Conversation compact handoff: $id]")
            appendLine("Source message ids: ${sourceMessageIds.joinToString(", ")}")
            if (covered.isNotEmpty()) {
                appendLine("Covered compact ids: ${covered.joinToString(", ")}")
            }
            appendLine()
            append(handoff.trim())
        }.trim()
    }

    fun validCompletedCompacts(
        activeCompacts: List<ConversationCompact>,
        existingMessageIds: Set<String>,
    ): List<ConversationCompact> =
        activeCompacts
            .filter { compact ->
                compact.status == "completed" &&
                    compact.sourceMessageIds.isNotEmpty() &&
                    compact.sourceMessageIds.all { it in existingMessageIds }
            }
            .sortedWith(compareBy<ConversationCompact> { it.sourceEndIndex }.thenBy { it.createdAt })

    fun selectCompactsForInjection(
        activeCompacts: List<ConversationCompact>,
        existingMessageIds: Set<String>,
    ): List<ConversationCompact> {
        val completed = validCompletedCompacts(activeCompacts, existingMessageIds)
        if (completed.isEmpty()) return emptyList()
        val latestPayloadCompact = completed.asReversed()
            .firstOrNull { parse(it.summary)?.handoffMarkdown?.isNotBlank() == true }
            ?: return completed
        val byId = completed.associateBy { it.id }
        val covered = transitiveCoveredIds(latestPayloadCompact, byId)
        return completed
            .filter { it.id != latestPayloadCompact.id && it.id !in covered } + latestPayloadCompact
    }

    fun isHighQualityPayload(summary: String): Boolean {
        val payload = parse(summary) ?: return false
        return payload.handoffMarkdown.length >= 80 && sentenceCount(payload.timelineSummary) >= 4
    }

    fun sentenceCount(text: String): Int {
        val cleaned = text.cleanHumanText()
        if (cleaned.isBlank()) return 0
        val count = sentenceEnd.findAll(cleaned).count()
        return count.coerceAtLeast(1)
    }

    private fun transitiveCoveredIds(
        compact: ConversationCompact,
        byId: Map<String, ConversationCompact>,
    ): Set<String> {
        val seen = linkedSetOf<String>()

        fun visit(id: String) {
            if (!seen.add(id)) return
            byId[id]?.let { parent ->
                parse(parent.summary)?.coveredCompactIds.orEmpty().forEach(::visit)
            }
        }

        parse(compact.summary)?.coveredCompactIds.orEmpty().forEach(::visit)
        return seen
    }

    private fun timelineFromParsed(obj: JsonObject, preamble: String): String {
        obj.stringValue("timeline_summary")?.cleanHumanText()?.takeIf { it.isNotBlank() }?.let { return it }
        obj.stringValue("display_summary")?.cleanHumanText()?.takeIf { it.isNotBlank() }?.let { return it }
        obj.stringValue("summary")?.cleanHumanText()?.takeIf { it.isNotBlank() }?.let { return it }

        val sentences = mutableListOf<String>()
        preamble.takeIf { it.isNotBlank() }?.let { sentences += it }
        fun addSection(label: String, key: String) {
            if (sentences.size >= 5) return
            val values = obj.stringList(key).map { it.cleanHumanText() }.filter { it.isNotBlank() }
            if (values.isNotEmpty()) {
                sentences += "$label: ${values.take(3).joinToString("; ")}"
            }
        }
        addSection("Goals", "goals")
        addSection("Facts", "facts")
        addSection("Decisions", "decisions")
        addSection("Open tasks", "open_tasks")
        addSection("Tool results", "tool_results")
        addSection("Timeline", "timeline")
        addSection("Entities", "entities")
        return sentences.take(5).joinToString(". ").ensureTerminalPeriod()
            .ifBlank { "Conversation history was compacted into a continuation handoff." }
    }

    private fun handoffFromLegacy(
        obj: JsonObject,
        timelineSummary: String,
        sourceMessageIds: List<String>,
    ): String = buildString {
        appendLine("## Goal")
        appendLegacyLines(obj, "goals", fallback = "Continue the conversation using the compacted history.")
        appendLine()
        appendLine("## Constraints")
        appendLegacyLines(obj, "entities", fallback = "Preserve user preferences, concrete names, files, commands, and decisions.")
        appendLine()
        appendLine("## Progress")
        appendLine("- ${timelineSummary.cleanHumanText()}")
        appendLegacyLines(obj, "timeline", prefix = "- ")
        appendLine()
        appendLine("## Decisions")
        appendLegacyLines(obj, "decisions", fallback = "No explicit decisions were captured.")
        appendLine()
        appendLine("## Current State")
        appendLegacyLines(obj, "facts", fallback = "Use the compacted source messages as prior context.")
        appendLegacyLines(obj, "tool_results", prefix = "- ")
        appendLine()
        appendLine("## Next Steps")
        appendLegacyLines(obj, "open_tasks", fallback = "Continue from the latest user request.")
        appendLine()
        appendLine("## Critical Context")
        appendLegacyLines(obj, "failed_attempts", fallback = "No failed attempts were captured.")
        if (sourceMessageIds.isNotEmpty()) {
            appendLine("- Source message ids: ${sourceMessageIds.joinToString(", ")}")
        }
        appendLine()
        appendLine("## Relevant Files")
        appendLine("- None captured unless named above.")
    }.trim()

    private fun plainTextHandoff(
        summary: String,
        sourceMessageIds: List<String>,
        carriedHandoffMarkdown: String = "",
    ): String = buildString {
        appendLine("## Goal")
        appendLine("- Continue the conversation using the compacted history.")
        appendLine()
        appendLine("## Constraints")
        appendLine("- Preserve the user's stated preferences and unresolved requests.")
        appendLine()
        appendLine("## Progress")
        appendLine("- ${summary.cleanHumanText()}")
        appendLine()
        appendLine("## Decisions")
        appendLine("- No structured decisions were captured.")
        appendLine()
        appendLine("## Current State")
        appendLine("- The previous conversation segment has been compacted.")
        appendLine()
        appendLine("## Next Steps")
        appendLine("- Continue from the latest visible user request.")
        appendLine()
        appendLine("## Critical Context")
        if (sourceMessageIds.isNotEmpty()) {
            appendLine("- Source message ids: ${sourceMessageIds.joinToString(", ")}")
        } else {
            appendLine("- No source message ids were captured.")
        }
        appendLine()
        appendLine("## Relevant Files")
        appendLine("- None captured unless named above.")
        val carried = carriedHandoffMarkdown.cleanMarkdown().takeIf { it.isNotBlank() }
        if (carried != null) {
            appendLine()
            appendLine("## Previous Compact Handoffs")
            appendLine(carried)
        }
    }.trim()

    private fun fallbackTimelineFromSource(sourceContent: String): String {
        val snippets = sourceContent.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("message_id:") || it.startsWith("role:") || it.startsWith("reasoning_marker:") }
            .map { line ->
                line.removePrefix("text:")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                    .take(180)
            }
            .filter { it.isNotBlank() }
            .distinct()
            .take(4)
            .toList()
        if (snippets.isEmpty()) return ""
        val chinese = sourceContent.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        return if (chinese) {
            buildList {
                add("已压缩的历史包含 ${snippets.size} 段可读内容。")
                add("开头内容包括：“${snippets.getOrElse(0) { "无可读文本" }}”。")
                add("后续内容包括：“${snippets.getOrElse(1) { snippets.first() }}”。")
                add("这段历史已经被写入 handoff，后续回复会以摘要形式保留它。")
                add("如果需要追溯细节，可以通过原始消息继续展开。")
            }.joinToString("")
        } else {
            buildList {
                add("The compacted history contains ${snippets.size} readable snippets.")
                add("It begins with: \"${snippets.getOrElse(0) { "no readable text" }}\".")
                add("It also includes: \"${snippets.getOrElse(1) { snippets.first() }}\".")
                add("This segment has been preserved in the handoff for continuation.")
                add("Original messages can still be expanded if details are needed.")
            }.joinToString(" ")
        }
    }

    private fun fallbackGenericSummary(sourceContent: String): String {
        val chinese = sourceContent.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        return if (chinese) {
            "历史消息已经压缩完成。模型没有返回合格的结构化摘要。系统已保留被压缩消息的 ID 和基础 handoff。后续对话会继续使用这段压缩上下文。需要时仍可展开原始消息。"
        } else {
            "Conversation history was compacted. The model did not return a valid structured summary. The system preserved source message ids and a basic handoff. Future turns can continue from this compacted context. Original messages remain expandable if needed."
        }
    }

    private fun String.coerceTimelineSummary(): String =
        cleanHumanText()
            .take(MAX_TIMELINE_SUMMARY_CHARS)
            .trim()
            .ensureTerminalPeriod()

    private fun String.cleanMarkdown(): String =
        replace("```markdown", " ")
            .replace("```md", " ")
            .replace("```", " ")
            .trim()

    private fun String.cleanHumanText(): String =
        replace("```json", " ")
            .replace("```markdown", " ")
            .replace("```md", " ")
            .replace("```", " ")
            .replace("[Summary of previous conversation]", " ")
            .replace("[Summary]", " ")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .let { whitespace.replace(it, " ") }
            .trim()

    private fun String.ensureTerminalPeriod(): String {
        if (isBlank()) return this
        val last = last()
        return if (last in listOf('.', '!', '?', '。', '！', '？')) this else "$this."
    }

    private fun cleanRaw(summary: String): String =
        summary.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

    private fun String.cleanRawForFallback(): String = cleanRaw(this)

    private fun String.replaceCompactIdReferences(idMapping: Map<String, String>): String =
        idMapping.entries.fold(this) { text, (oldId, newId) -> text.replace(oldId, newId) }

    private fun String.looksLikeJsonFragment(): Boolean {
        val trimmed = trimStart()
        return trimmed.startsWith("{") ||
            trimmed.startsWith("[") ||
            "\"timeline_summary\"" in trimmed ||
            "\"handoff_markdown\"" in trimmed ||
            "\"schema_version\"" in trimmed
    }

    private fun locateJsonObject(text: String): LocatedJson? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return LocatedJson(startIndex = start, jsonText = text.substring(start, end + 1))
    }

    private fun JsonObjectBuilder.putStringArray(key: String, values: List<String>) {
        put(key, buildJsonArray {
            values.filter { it.isNotBlank() }.forEach { add(it) }
        })
    }

    private fun JsonObjectBuilder.putLegacyFields(obj: JsonObject, sourceMessageIds: List<String>) {
        legacyKeys.forEach { key ->
            if (key == "source_message_ids") {
                putStringArray(key, sourceMessageIds)
            } else {
                val value = obj[key]
                put(
                    key,
                    when (value) {
                        null -> buildJsonArray {}
                        is JsonArray -> value
                        else -> buildJsonArray { add(value) }
                    }
                )
            }
        }
        obj.forEach { (key, value) ->
            if (key !in legacyKeys && key !in payloadKeys) put(key, value)
        }
    }

    private fun JsonObject.appendLegacyLines(
        builder: StringBuilder,
        key: String,
        prefix: String = "- ",
        fallback: String? = null,
    ) {
        val values = stringList(key).map { it.cleanHumanText() }.filter { it.isNotBlank() }
        if (values.isEmpty()) {
            fallback?.let { builder.appendLine("$prefix$it") }
        } else {
            values.take(8).forEach { builder.appendLine("$prefix$it") }
        }
    }

    private fun StringBuilder.appendLegacyLines(
        obj: JsonObject,
        key: String,
        prefix: String = "- ",
        fallback: String? = null,
    ) {
        obj.appendLegacyLines(this, key, prefix, fallback)
    }

    private fun JsonObject.stringValue(key: String): String? = this[key].stringValue()

    private fun JsonObject.intValue(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun JsonObject.longValue(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun JsonElement?.stringValue(): String? = when (this) {
        is JsonPrimitive -> contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        is JsonArray -> firstNotNullOfOrNull { it.stringValue() }
        is JsonObject -> listOf("summary", "text", "content", "description", "title", "value")
            .firstNotNullOfOrNull { get(it).stringValue() }
        else -> null
    }

    private fun JsonObject.stringList(key: String): List<String> = this[key].stringList()

    private fun JsonElement?.stringList(): List<String> = when (this) {
        is JsonArray -> flatMap { it.stringList() }
        is JsonPrimitive -> contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
        is JsonObject -> stringValue()?.let(::listOf).orEmpty()
        else -> emptyList()
    }

    private data class LocatedJson(
        val startIndex: Int,
        val jsonText: String,
    )
}
