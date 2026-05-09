package me.rerere.rikkahub.data.ai.generative

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface GenerativeWidgetSegment {
    data class Text(val content: String) : GenerativeWidgetSegment

    data class Widget(
        val title: String?,
        val widgetCode: String,
        val complete: Boolean,
        val renderer: String = "html",
        val actions: List<GenerativeWidgetAction> = emptyList(),
    ) : GenerativeWidgetSegment

    data object Loading : GenerativeWidgetSegment
}

data class GenerativeWidgetAction(
    val id: String,
    val label: String,
    val instruction: String,
) {
    fun toUserPrompt(widgetTitle: String?): String = buildString {
        append("请基于上一张生成式 UI")
        widgetTitle?.takeIf { it.isNotBlank() }?.let { append("「").append(it).append("」") }
        append("继续处理：")
        append(instruction)
    }
}

object GenerativeWidgetParser {
    private val markerRegex = Regex("""(?m)^[ \t]*```[ \t]*(?:show-widget|widget|generative-ui)[^\r\n]*\r?\n""")
    private val renderableTagRegex = Regex(
        """<\s*(?:svg|div|section|article|table|ul|ol|p|figure|main|aside|header|footer)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val placeholderPhrases = listOf(
        "human readable title",
        "<svg or static",
        "svg or static html",
        "static html/css",
        "<svg 或",
        "placeholder",
        "replace me",
        "todo",
    )
    private val json = Json { ignoreUnknownKeys = true }

    fun containsWidgetFence(content: String): Boolean = markerRegex.containsMatchIn(content)

    fun hasRenderableWidget(content: String): Boolean =
        parse(content, streaming = false).any { it is GenerativeWidgetSegment.Widget }

    fun parse(content: String, streaming: Boolean): List<GenerativeWidgetSegment> {
        val segments = mutableListOf<GenerativeWidgetSegment>()
        var cursor = 0
        var foundWidgetMarker = false

        while (cursor < content.length) {
            val marker = markerRegex.find(content, cursor) ?: break
            foundWidgetMarker = true
            appendTextSegment(segments, content.substring(cursor, marker.range.first))

            val afterMarker = marker.range.last + 1
            val jsonStart = content.indexOf('{', afterMarker)
            if (jsonStart == -1 || jsonStart > afterMarker + 80) {
                if (streaming) {
                    segments += GenerativeWidgetSegment.Loading
                    return segments
                }
                appendTextSegment(segments, content.substring(marker.range.first))
                return segments
            }

            val jsonEnd = findJsonEnd(content, jsonStart)
            if (jsonEnd >= 0) {
                val jsonText = content.substring(jsonStart, jsonEnd + 1)
                val widget = parseWidgetJson(jsonText)
                if (widget != null) {
                    segments += widget.copy(complete = true)
                    cursor = skipTrailingFence(content, jsonEnd + 1)
                } else {
                    val fallbackEnd = skipTrailingFence(content, jsonEnd + 1)
                    appendTextSegment(segments, content.substring(marker.range.first, fallbackEnd))
                    cursor = fallbackEnd
                }
                continue
            }

            if (streaming) {
                val partial = parsePartialWidget(content.substring(jsonStart))
                if (partial != null) {
                    segments += partial
                } else {
                    segments += GenerativeWidgetSegment.Loading
                }
                return segments
            }

            appendTextSegment(segments, content.substring(marker.range.first))
            return segments
        }

        appendTextSegment(segments, content.substring(cursor))
        return if (foundWidgetMarker) segments else listOf(GenerativeWidgetSegment.Text(content))
    }

    private fun appendTextSegment(segments: MutableList<GenerativeWidgetSegment>, content: String) {
        if (content.isNotBlank()) {
            segments += GenerativeWidgetSegment.Text(content.trim())
        }
    }

    private fun parseWidgetJson(jsonText: String): GenerativeWidgetSegment.Widget? {
        val parsed = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull()
        val renderer = parsed?.stringOrNull("renderer")?.lowercase()?.takeIf {
            it in setOf("html", "chart", "diagram")
        } ?: "html"
        val renderedCode = GenerativeWidgetRenderer.render(renderer, parsed?.get("spec"))
        val rawWidgetCode = parsed?.stringOrNull("widget_code")
            ?: extractJsonStringValue(jsonText, "widget_code", allowUnclosed = false)
        val code = if (renderer == "html") rawWidgetCode else renderedCode ?: rawWidgetCode
        val renderableCode = code?.takeIf { isRenderableWidgetCode(it, complete = true) } ?: return null
        val title = parsed?.stringOrNull("title")
            ?: extractJsonStringValue(jsonText, "title", allowUnclosed = false)
        return GenerativeWidgetSegment.Widget(
            title = normalizeWidgetTitle(title),
            widgetCode = renderableCode,
            complete = true,
            renderer = renderer,
            actions = parseActions(parsed),
        )
    }

    private fun parsePartialWidget(jsonText: String): GenerativeWidgetSegment.Widget? {
        val code = extractJsonStringValue(jsonText, "widget_code", allowUnclosed = true)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        if (!isRenderableWidgetCode(code, complete = false)) return null
        return GenerativeWidgetSegment.Widget(
            title = normalizeWidgetTitle(extractJsonStringValue(jsonText, "title", allowUnclosed = true)),
            widgetCode = code,
            complete = false,
        )
    }

    private fun normalizeWidgetTitle(title: String?): String? =
        title
            ?.compactSpaces()
            ?.takeIf { it.isNotBlank() }
            ?.takeUnless { titleText ->
                placeholderPhrases.any { phrase ->
                    titleText.contains(phrase, ignoreCase = true)
                }
            }

    private fun isRenderableWidgetCode(code: String?, complete: Boolean): Boolean {
        val compact = code?.trim().orEmpty()
        if (compact.isBlank()) return false
        val lower = compact.lowercase()
        if (placeholderPhrases.any { it in lower }) return false
        if (lower == "<svg>" || lower == "<div>" || lower == "<svg></svg>" || lower == "<div></div>") {
            return complete.not()
        }
        if (!renderableTagRegex.containsMatchIn(compact)) return false
        if (complete && compact.count { it == '<' } < 2) return false
        return true
    }

    private fun findJsonEnd(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val char = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (char == '\\' && inString) {
                escaped = true
                continue
            }
            if (char == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            when (char) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun skipTrailingFence(text: String, start: Int): Int {
        val trailing = Regex("""\s*\n?`{3}\s*""").find(text, start)
        if (trailing != null && trailing.range.first == start) {
            return trailing.range.last + 1
        }
        return start
    }

    private fun extractJsonStringValue(source: String, key: String, allowUnclosed: Boolean): String? {
        val keyRegex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"")
        val match = keyRegex.find(source) ?: return null
        var index = match.range.last + 1
        val output = StringBuilder()
        var escaped = false
        while (index < source.length) {
            val char = source[index++]
            if (escaped) {
                output.append(
                    when (char) {
                        '"' -> '"'
                        '\\' -> '\\'
                        '/' -> '/'
                        'b' -> '\b'
                        'f' -> '\u000C'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> {
                            val hex = source.substring(index, (index + 4).coerceAtMost(source.length))
                            if (hex.length == 4 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                                index += 4
                                hex.toInt(16).toChar()
                            } else {
                                'u'
                            }
                        }
                        else -> char
                    }
                )
                escaped = false
                continue
            }
            when (char) {
                '\\' -> escaped = true
                '"' -> return output.toString()
                else -> output.append(char)
            }
        }
        return if (allowUnclosed) output.toString() else null
    }

    private fun parseActions(parsed: JsonObject?): List<GenerativeWidgetAction> =
        parsed?.get("actions")
            ?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.mapNotNull(::parseAction)
            ?.take(MAX_ACTIONS)
            .orEmpty()

    private fun parseAction(element: JsonElement): GenerativeWidgetAction? {
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return null
        val label = obj.stringOrNull("label")
            ?.compactSpaces()
            ?.takeIf { it.length in 1..20 }
            ?: return null
        val instruction = obj.stringOrNull("instruction")
            ?.compactSpaces()
            ?.takeIf { it.length in 1..240 }
            ?.takeUnless(::hasBlockedActionInstruction)
            ?: return null
        val rawId = obj.stringOrNull("id")?.compactSpaces().orEmpty()
        val id = rawId
            .ifBlank { label }
            .replace(Regex("""[^A-Za-z0-9_\-\u4e00-\u9fa5]"""), "-")
            .take(48)
            .ifBlank { return null }
        return GenerativeWidgetAction(
            id = id,
            label = label,
            instruction = instruction,
        )
    }

    private fun hasBlockedActionInstruction(instruction: String): Boolean {
        val lower = instruction.lowercase()
        return listOf(
            "<system",
            "</system",
            "ignore previous",
            "system prompt",
            "developer message",
            "tool call",
            "http://",
            "https://",
            "打开链接",
            "执行工具",
        ).any { it in lower }
    }

    private fun String.compactSpaces(): String = trim().replace(Regex("""\s+"""), " ")

    private fun JsonObject.stringOrNull(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private const val MAX_ACTIONS = 3
}
