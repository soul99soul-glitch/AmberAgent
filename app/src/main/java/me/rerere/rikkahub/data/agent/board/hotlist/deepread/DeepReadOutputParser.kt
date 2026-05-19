package me.rerere.rikkahub.data.agent.board.hotlist.deepread

import kotlinx.serialization.json.Json

object DeepReadOutputParser {
    fun parse(raw: String, json: Json): DeepReadOutput? {
        val trimmed = raw.trim()
        val jsonText = if (trimmed.startsWith("{")) {
            trimmed
        } else {
            Regex("\\{[\\s\\S]*\\}").find(trimmed)?.value ?: return null
        }
        return runCatching { json.decodeFromString<DeepReadOutput>(jsonText) }.getOrNull()
    }
}
