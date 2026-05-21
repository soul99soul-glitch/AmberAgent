package me.rerere.rikkahub.data.agent.board.hotlist.deepread.template

class DeepReadTemplateValidationException(message: String) : IllegalArgumentException(message)

object DeepReadTemplateValidator {
    private val requiredHtmlPattern = Regex("""(?is)<\s*(html\b|!doctype\s+html)""")
    private val blockedPatterns = listOf(
        Regex("""(?is)<\s*script\b""") to "Deep Read templates do not allow JavaScript",
        Regex("""(?is)\son[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""") to "Event handlers are not allowed",
        Regex("""(?is)<\s*(iframe|object|embed|form|input|button|textarea|select)\b""") to "Interactive or embedded elements are not allowed",
        Regex("""(?is)<\s*meta\b[^>]*http-equiv\s*=\s*['"]?refresh""") to "Meta refresh is not allowed",
        Regex("""(?is)<\s*link\b[^>]*\bhref\s*=\s*['"]?\s*(https?:|//|file:|content:)""") to "External stylesheets are not allowed",
        Regex("""(?is)\bhref\s*=\s*(?:['"]\s*)?(https?:|//|file:|content:)""") to "Hard-coded external links are not allowed",
        Regex("""(?is)\bsrc\s*=\s*(?:['"]\s*)?(https?:|//|file:|content:)""") to "Hard-coded external resources are not allowed",
        Regex("""(?is)@import\b""") to "CSS imports are not allowed",
        Regex("""(?is)url\s*\(\s*(['"]?)\s*(https?:|//|file:|content:)""") to "External CSS URLs are not allowed",
        Regex("""(?is)\b(fetch|XMLHttpRequest|WebSocket|EventSource|localStorage|sessionStorage|indexedDB|eval)\b""") to "Browser APIs are not allowed",
        Regex("""(?is)\b(window|globalThis)\s*\[""") to "Computed global access is not allowed",
    )

    fun validate(html: String): DeepReadTemplateValidationResult =
        try {
            validateOrThrow(html)
            DeepReadTemplateValidationResult(ok = true)
        } catch (error: DeepReadTemplateValidationException) {
            DeepReadTemplateValidationResult(ok = false, error = error.message)
        }

    fun validateOrThrow(html: String) {
        val sizeBytes = html.encodeToByteArray().size
        if (sizeBytes > DeepReadTemplateLimits.MAX_HTML_BYTES) {
            throw DeepReadTemplateValidationException("Template is too large: $sizeBytes bytes")
        }
        if (!requiredHtmlPattern.containsMatchIn(html)) {
            throw DeepReadTemplateValidationException("Template must include <html> or <!DOCTYPE html>")
        }
        blockedPatterns.firstOrNull { (pattern, _) -> pattern.containsMatchIn(html) }?.let { (_, reason) ->
            throw DeepReadTemplateValidationException(reason)
        }
        validatePlaceholderSlots(html)
    }

    private fun validatePlaceholderSlots(html: String) {
        BLOCK_PLACEHOLDERS.forEach { placeholder ->
            val token = Regex.escape("{{$placeholder}}")
            if (Regex("""(?is)<[^>]*$token[^>]*>""").containsMatchIn(html)) {
                throw DeepReadTemplateValidationException("Block placeholder {{$placeholder}} must not be used inside a tag")
            }
            if (Regex("""(?is)<style\b[^>]*>.*$token.*</style>""").containsMatchIn(html)) {
                throw DeepReadTemplateValidationException("Block placeholder {{$placeholder}} must not be used inside <style>")
            }
        }
        Regex("""(?is)\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
            .findAll(html)
            .firstOrNull { match ->
                match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim() != "{{hero_image_url}}"
            }
            ?.let { throw DeepReadTemplateValidationException("Template image src must use {{hero_image_url}}") }
        val allowedHref = Regex("""(?is)\bhref\s*=\s*['"]#['"]|\bhref\s*=\s*['"]\{\{[^}]+}}['"]""")
        Regex("""(?is)\bhref\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)""")
            .findAll(html)
            .firstOrNull { !allowedHref.matches(it.value) }
            ?.let { throw DeepReadTemplateValidationException("Template links must come from {{extended_reading_html}}") }
    }

    private val BLOCK_PLACEHOLDERS = listOf(
        "timeline_html",
        "core_points_html",
        "analysis_html",
        "extended_reading_html",
    )
}
