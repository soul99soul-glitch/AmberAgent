package me.rerere.rikkahub.data.agent.board.hotlist.deepread.template

class DeepReadTemplateValidationException(message: String) : IllegalArgumentException(message)

object DeepReadTemplateValidator {
    private val requiredHtmlPattern = Regex("""(?is)<\s*(html\b|!doctype\s+html)""")
    private val blockedPatterns = listOf(
        Regex("""(?is)<\s*script\b""") to "Deep Read templates do not allow JavaScript",
        Regex("""(?is)\son[a-z]+\s*=""") to "Event handlers are not allowed",
        Regex("""(?is)<\s*(iframe|object|embed|form|input|button|textarea|select)\b""") to "Interactive or embedded elements are not allowed",
        Regex("""(?is)<\s*link\b[^>]*\bhref\s*=\s*['"]?\s*(https?:|//|file:|content:)""") to "External stylesheets are not allowed",
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
    }
}
