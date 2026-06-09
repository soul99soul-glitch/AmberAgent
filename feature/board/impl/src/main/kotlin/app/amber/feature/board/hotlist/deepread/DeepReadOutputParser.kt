package app.amber.feature.board.hotlist.deepread

import kotlinx.serialization.json.Json

object DeepReadOutputParser {
    fun parse(raw: String, json: Json): DeepReadOutput? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val tolerantJson = Json(json) {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = true
            coerceInputValues = true
        }
        return jsonCandidates(trimmed)
            .asSequence()
            .map { it.cleanupJsonCandidate() }
            .distinct()
            .firstNotNullOfOrNull { candidate ->
                runCatching { tolerantJson.decodeFromString<DeepReadOutput>(candidate) }.getOrNull()
            }
    }

    private fun jsonCandidates(text: String): List<String> = buildList {
        add(text)
        CODE_FENCE.findAll(text).forEach { match ->
            match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::add)
        }
        addAll(extractBalancedObjects(text))
    }

    private fun extractBalancedObjects(text: String): List<String> {
        val objects = mutableListOf<String>()
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false

        text.forEachIndexed { index, char ->
            if (escaped) {
                escaped = false
                return@forEachIndexed
            }
            if (char == '\\' && inString) {
                escaped = true
                return@forEachIndexed
            }
            if (char == '"') {
                inString = !inString
                return@forEachIndexed
            }
            if (inString) return@forEachIndexed

            when (char) {
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> {
                    if (depth > 0) depth--
                    if (depth == 0 && start >= 0) {
                        objects += text.substring(start, index + 1)
                        start = -1
                    }
                }
            }
        }
        return objects
    }

    private fun String.cleanupJsonCandidate(): String =
        trim()
            .removePrefix("json")
            .trim()
            .removeSurrounding("```")
            .trim()
            .replace(Regex(",\\s*([}\\]])"), "$1")

    private val CODE_FENCE = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
}
