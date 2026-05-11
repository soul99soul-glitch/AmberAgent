package me.rerere.rikkahub.data.agent.board.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Best-effort parser for [BoardAgentOutput].
 *
 * Tolerates:
 *  - Fenced code blocks (```json ... ```)
 *  - Leading / trailing prose before or after the JSON
 *  - Missing fields (defaults are applied via @Serializable defaults)
 *  - Extra unknown fields (ignoreUnknownKeys)
 *
 * Returns null when no usable JSON object can be recovered. Callers should fall back
 * to the previous board snapshot in that case rather than clearing the user's view.
 */
object BoardOutputParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parse(raw: String): BoardAgentOutput? {
        val cleaned = cleanFences(raw).trim().takeIf { it.isNotBlank() } ?: return null
        val obj = extractRootObject(cleaned) ?: return null

        // Try direct deserialization first (fast path when the model obeys the schema).
        runCatching { return json.decodeFromString(BoardAgentOutput.serializer(), obj.toString()) }

        // Fallback: manual field-by-field extraction. Wrap in runCatching so type drift
        // (e.g. summary returned as an object, items returned as a string) degrades to
        // null rather than propagating an uncaught exception to the worker.
        return runCatching {
            val summary = (obj["summary"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val items = (obj["items"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.map(::decodeItem)
                .orEmpty()
            BoardAgentOutput(summary = summary, items = items)
        }.getOrNull()
    }

    private fun decodeItem(obj: JsonObject): BoardAgentItem = BoardAgentItem(
        id = obj.stringOrEmpty("id"),
        title = obj.stringOrEmpty("title"),
        source_type = obj.stringOrEmpty("source_type"),
        source_ref = obj.stringOrEmpty("source_ref"),
        urgency = obj.stringOrEmpty("urgency").ifBlank { "medium" },
        category = obj.stringOrEmpty("category").ifBlank { "attention" },
        reason = obj.stringOrEmpty("reason"),
        suggestion = obj.stringOrEmpty("suggestion"),
        signal_time = obj.longOrZero("signal_time"),
    )

    private fun JsonObject.stringOrEmpty(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()

    private fun JsonObject.longOrZero(key: String): Long {
        val prim = this[key] as? JsonPrimitive ?: return 0L
        return prim.longOrNull ?: prim.contentOrNull?.toLongOrNull() ?: 0L
    }

    private fun cleanFences(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```json", ignoreCase = true)) {
            s = s.removePrefix("```json").removePrefix("```JSON").trimStart()
        } else if (s.startsWith("```")) {
            s = s.removePrefix("```").trimStart()
        }
        if (s.endsWith("```")) {
            s = s.removeSuffix("```").trimEnd()
        }
        return s
    }

    private fun extractRootObject(s: String): JsonObject? {
        // Locate the outermost {...}. Naive matching via first { and last } is enough for
        // well-formed responses; we guard parse errors at the top level.
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val candidate = s.substring(start, end + 1)
        return runCatching { json.parseToJsonElement(candidate).jsonObject }.getOrNull()
    }
}
