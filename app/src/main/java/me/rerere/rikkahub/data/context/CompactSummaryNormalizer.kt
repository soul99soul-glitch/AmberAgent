package me.rerere.rikkahub.data.context

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal object CompactSummaryNormalizer {
    private val requiredKeys = listOf(
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

    fun normalizeOrNull(json: Json, summary: String, sourceMessageIds: List<String>): String? {
        val trimmed = summary.trim()
        if (trimmed.isEmpty()) return null

        val cleaned = trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        val candidate = if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
        val parsed = runCatching { json.parseToJsonElement(candidate).jsonObject }.getOrNull()
            ?: return fallbackPlainTextSummaryJson(summary, sourceMessageIds)

        val normalizedJson = buildJsonObject {
            requiredKeys.forEach { key ->
                if (key == "source_message_ids") {
                    putSourceMessageIds(sourceMessageIds)
                } else {
                    val value = parsed[key]
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
            parsed.forEach { (key, value) ->
                if (key !in requiredKeys) put(key, value)
            }
        }.toString()

        val preamble = if (start > 0) cleaned.substring(0, start).trim() else ""
        val cleanedPreamble = preamble
            .replace("```json", " ")
            .replace("```", " ")
            .replace("[Summary of previous conversation]", " ")
            .replace("[Summary]", " ")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(240)
        return if (cleanedPreamble.isNotEmpty()) "$cleanedPreamble\n$normalizedJson" else normalizedJson
    }

    fun fallbackPlainTextSummaryJson(summary: String, sourceMessageIds: List<String>): String {
        val cleaned = summary
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .take(12_000)
        val fallback = buildJsonObject {
            put("goals", buildJsonArray {})
            put("facts", buildJsonArray {
                add(cleaned.ifBlank { "Conversation segment was compressed, but the model returned an unstructured summary." })
            })
            put("decisions", buildJsonArray {})
            put("open_tasks", buildJsonArray {})
            put("failed_attempts", buildJsonArray {})
            put("tool_results", buildJsonArray {})
            put("entities", buildJsonArray {})
            put("timeline", buildJsonArray {})
            putSourceMessageIds(sourceMessageIds)
        }
        return fallback.toString()
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putSourceMessageIds(sourceMessageIds: List<String>) {
        put("source_message_ids", buildJsonArray {
            sourceMessageIds.forEach { add(it) }
        })
    }
}
