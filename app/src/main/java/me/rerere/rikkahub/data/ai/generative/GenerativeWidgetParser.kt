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
        val specJson: String? = null,
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

    private val partialFenceEndRegex = Regex("""(?m)^[ \t]*`{3}[ \t]*(?:show-widget|widget|generative-ui)[^\r\n]*$""")

    // Finds the LAST partial fence — if the model emits two ```show-widget markers
    // the last one is the incomplete streaming fence. Also tolerates trailing CRLF/whitespace.
    private fun hasPartialWidgetFenceAtEnd(text: String): Boolean {
        val normalized = text.trimEnd()
        return partialFenceEndRegex.findAll(normalized).lastOrNull()?.let {
            it.range.last >= normalized.lastIndex - 2
        } == true
    }

    private fun partialFenceMatch(text: String): String? =
        partialFenceEndRegex.find(text)?.let { text.substring(it.range.first) }

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

        val trailing = content.substring(cursor)
        if (streaming && !foundWidgetMarker && hasPartialWidgetFenceAtEnd(trailing)) {
            appendTextSegment(segments, trailing.substringBefore(partialFenceMatch(trailing)!!))
            segments += GenerativeWidgetSegment.Loading
            return segments
        }
        appendTextSegment(segments, trailing)
        return if (foundWidgetMarker) segments else listOf(GenerativeWidgetSegment.Text(content))
    }

    private fun appendTextSegment(segments: MutableList<GenerativeWidgetSegment>, content: String) {
        if (content.isNotBlank()) {
            segments += GenerativeWidgetSegment.Text(content.trim())
        }
    }

    private fun parseWidgetJson(jsonText: String): GenerativeWidgetSegment.Widget? {
        val parsed = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull()
        val rendererRaw = parsed?.stringOrNull("renderer")
        val renderer = rendererRaw?.lowercase()?.takeIf {
            it in setOf("html", "chart", "diagram", "vchart", "slides")
        }
        // If renderer field is present but unrecognized (e.g. model emits "svg" / "structure" /
        // "flowchart"), fall back to rendering widget_code as plain html instead of rejecting the
        // whole widget. The sanitizer still strips unsafe content; rejecting outright leaves the
        // user staring at raw JSON in a code block.
        val specElement = if (parsed == null) {
            null
        } else {
            parsed["spec"] ?: if (renderer == "slides") parsed["slides"] ?: parsed["pages"] else null
        }
        val renderedCode = GenerativeWidgetRenderer.render(renderer, specElement)
        val rawWidgetCode = parsed?.stringOrNull("widget_code")
            ?: extractJsonStringValue(jsonText, "widget_code", allowUnclosed = false)
        val title = parsed?.stringOrNull("title")
            ?: extractJsonStringValue(jsonText, "title", allowUnclosed = false)
        val specJson = when (renderer) {
            "slides" -> specElement?.let { VChartSpecValidator.normalizeSlidesSpecJson(it.toString()) }
            else -> specElement?.toString()
        }
        val code = when (renderer) {
            "vchart", "slides" -> renderedCode ?: rawWidgetCode
            "html" -> rawWidgetCode
            null -> rawWidgetCode  // old widgets without renderer field
            else -> renderedCode ?: rawWidgetCode
        }
        val renderableCode = code?.takeIf { isRenderableWidgetCode(it, complete = true) } ?: return null
        return GenerativeWidgetSegment.Widget(
            title = normalizeWidgetTitle(title),
            widgetCode = renderableCode,
            complete = true,
            renderer = renderer ?: "html",
            actions = parseActions(parsed),
            specJson = specJson,
        )
    }

    private fun parsePartialWidget(jsonText: String): GenerativeWidgetSegment.Widget? {
        // If widget_code is present (SVG/HTML widgets), use it for streaming preview
        val code = extractJsonStringValue(jsonText, "widget_code", allowUnclosed = true)
            ?.takeIf { it.isNotBlank() }
        if (code != null) {
            if (!isRenderableWidgetCode(code, complete = false)) return null
            return GenerativeWidgetSegment.Widget(
                title = normalizeWidgetTitle(extractJsonStringValue(jsonText, "title", allowUnclosed = true)),
                widgetCode = code,
                complete = false,
            )
        }
        // For renderer-based widgets (slides, vchart) that have no widget_code during streaming,
        // generate a placeholder card so the user sees progress instead of a generic loading spinner.
        val renderer = extractJsonStringValue(jsonText, "renderer", allowUnclosed = true)
            ?.lowercase()?.takeIf { it in setOf("vchart", "slides") }
        if (renderer != null) {
            val title = normalizeWidgetTitle(extractJsonStringValue(jsonText, "title", allowUnclosed = true))
            // For slides, try to extract completed slide objects from the partial JSON array
            // so the user sees a progressively updating preview during streaming.
            if (renderer == "slides") {
                val partialSlides = extractPartialSlides(jsonText)
                if (partialSlides.isNotEmpty()) {
                    val count = partialSlides.size
                    val countLabel = if (count >= 24) "24+ 页" else "$count 页"
                    val slidePreview = buildString {
                        appendLine("<svg width=\"100%\" viewBox=\"0 0 680 ${80 + count * 30}\" xmlns=\"http://www.w3.org/2000/svg\">")
                        appendLine("<rect width=\"100%\" height=\"100%\" fill=\"#f0fdf4\" rx=\"10\" stroke=\"#bbf7d0\"/>")
                        appendLine("<text x=\"340\" y=\"36\" text-anchor=\"middle\" font-size=\"15\" fill=\"#166534\">生成幻灯片: $countLabel</text>")
                        partialSlides.take(5).forEachIndexed { i, slide ->
                            val slideTitle = (slide as? kotlinx.serialization.json.JsonObject)
                                ?.get("title")
                                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                                ?: "第${i+1}页"
                            val escaped = slideTitle.replace("&", "&amp;").replace("<", "&lt;")
                            appendLine("<text x=\"48\" y=\"${72 + i * 24}\" font-size=\"13\" fill=\"#065f46\">${i+1}. $escaped</text>")
                        }
                        if (count > 5) {
                            appendLine("<text x=\"340\" y=\"${72 + 5 * 24 + 12}\" text-anchor=\"middle\" font-size=\"12\" fill=\"#86efac\">还有 ${count - 5} 页...</text>")
                        }
                        appendLine("</svg>")
                    }
                    return GenerativeWidgetSegment.Widget(
                        title = title,
                        widgetCode = slidePreview,
                        complete = false,
                        renderer = renderer,
                    )
                }
            }
            val label = if (renderer == "slides") "幻灯片" else "图表"
            val titleText = title ?: "正在生成"
            val placeholder = """<svg width="100%" viewBox="0 0 680 100" xmlns="http://www.w3.org/2000/svg"><rect width="100%" height="100%" fill="#f0f9ff" rx="10" stroke="#bae6fd"/><text x="340" y="42" text-anchor="middle" font-size="15" fill="#0369a1">生成$label...</text><text x="340" y="68" text-anchor="middle" font-size="13" fill="#7dd3fc">$titleText</text></svg>"""
            return GenerativeWidgetSegment.Widget(
                title = title,
                widgetCode = placeholder,
                complete = false,
                renderer = renderer,
            )
        }
        return null
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

    // Custom JSON boundary finder because kotlinx.serialization has no incremental API.
    // Used during streaming to locate the closing brace of the widget JSON object
    // before the full model output is complete.
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

    /**
     * Scans partial JSON for completed slide objects inside a "spec" array.
     * Tracks brace/string state: an object at depth=1 (inside the spec array)
     * that properly closes represents one complete slide.
     */
    private fun extractPartialSlides(jsonText: String): List<kotlinx.serialization.json.JsonElement> {
        val specKey = "\"spec\""
        val specStart = jsonText.indexOf(specKey)
        if (specStart < 0) return emptyList()

        // Find the opening '[' of the spec array
        val arrayStart = jsonText.indexOf('[', specStart + specKey.length)
        if (arrayStart < 0) return emptyList()

        val slides = mutableListOf<kotlinx.serialization.json.JsonElement>()
        var objectStart = -1
        var depth = 0
        var arrayDepth = 0
        var inString = false
        var escaped = false

        for (i in arrayStart until jsonText.length) {
            val char = jsonText[i]
            if (escaped) { escaped = false; continue }
            if (char == '\\' && inString) { escaped = true; continue }
            if (char == '"') { inString = !inString; continue }
            if (inString) continue

            when (char) {
                '[' -> { arrayDepth++ }
                ']' -> { arrayDepth--; if (arrayDepth == 0) break }
                '{' -> {
                    depth++
                    if (arrayDepth == 1 && depth == 1 && objectStart < 0) {
                        objectStart = i
                    }
                }
                '}' -> {
                    depth--
                    if (arrayDepth == 1 && depth == 0 && objectStart >= 0) {
                        val candidate = jsonText.substring(objectStart, i + 1)
                        val parsed = runCatching { json.parseToJsonElement(candidate) }
                            .getOrNull() ?: continue
                        if (parsed is kotlinx.serialization.json.JsonObject) {
                            slides.add(parsed)
                        }
                        objectStart = -1
                        if (slides.size >= 24) break
                    }
                }
            }
        }
        return slides
    }

    private fun skipTrailingFence(text: String, start: Int): Int {
        val trailing = Regex("""\s*\n?`{3}\s*""").find(text, start)
        if (trailing != null && trailing.range.first == start) {
            return trailing.range.last + 1
        }
        return start
    }

    // Partial-stream string extraction. Malformed \u sequences (e.g. \u123 with 3 chars)
    // keep the literal 'u' — this is intentional: the partial value is a best-effort preview
    // that gets replaced when the full JSON arrives and parseWidgetJson runs the real parser.
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
