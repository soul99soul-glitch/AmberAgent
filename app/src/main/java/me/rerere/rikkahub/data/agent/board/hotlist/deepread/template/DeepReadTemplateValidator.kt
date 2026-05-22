package me.rerere.rikkahub.data.agent.board.hotlist.deepread.template

class DeepReadTemplateValidationException(message: String) : IllegalArgumentException(message)

object DeepReadTemplateValidator {
    private val requiredHtmlPattern = Regex("""(?is)<\s*(html\b|!doctype\s+html)""")
    private val blockedPatterns = listOf(
        Regex("""(?is)<\s*script\b""") to "Deep Read templates do not allow JavaScript",
        Regex("""(?is)\son[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""") to "Event handlers are not allowed",
        Regex("""(?is)<\s*(iframe|object|embed|form|input|button|textarea|select)\b""") to "Interactive or embedded elements are not allowed",
        Regex("""(?is)<\s*(svg|canvas|math|audio|video|source|picture|track)\b""") to "Vector, canvas, or media elements are not allowed",
        Regex("""(?is)<\s*meta\b[^>]*http-equiv\s*=\s*['"]?refresh""") to "Meta refresh is not allowed",
        Regex("""(?is)\b(srcset|poster)\s*=""") to "Responsive or media resource attributes are not allowed",
        Regex("""(?is)<\s*link\b[^>]*\bhref\s*=\s*['"]?\s*(https?:|//|file:|content:)""") to "External stylesheets are not allowed",
        Regex("""(?is)\bhref\s*=\s*(?:['"]\s*)?(https?:|//|file:|content:)""") to "Hard-coded external links are not allowed",
        Regex("""(?is)\bsrc\s*=\s*(?:['"]\s*)?(https?:|//|file:|content:)""") to "Hard-coded external resources are not allowed",
        Regex("""(?is)@import\b""") to "CSS imports are not allowed",
        Regex("""(?is)url\s*\(""") to "CSS URLs are not allowed",
        Regex("""(?is)(?:-webkit-)?image-set\s*\(""") to "CSS image sets are not allowed",
        Regex("""(?is)cross-fade\s*\(""") to "CSS resource functions are not allowed",
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
        validateStylePlaceholders(html)
        validateHeroImagePlaceholder(html)
        Regex("""(?is)\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
            .findAll(html)
            .firstOrNull { match ->
                match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim() != "{{hero_image_url}}"
            }
            ?.let { throw DeepReadTemplateValidationException("Template image src must use {{hero_image_url}}") }
        Regex("""(?is)\bhref\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)""")
            .findAll(html)
            .firstOrNull { match ->
                val value = match.value.substringAfter("=").trim().trim('"', '\'')
                value != "#" && !value.startsWith("#")
            }
            ?.let { throw DeepReadTemplateValidationException("Template links must come from {{extended_reading_html}}") }
    }

    private fun validateStylePlaceholders(html: String) {
        Regex("""(?is)<style\b[^>]*>(.*?)</style>""")
            .findAll(html)
            .forEach { styleBlock ->
                PLACEHOLDER_PATTERN.findAll(styleBlock.groupValues[1])
                    .firstOrNull { it.value != "{{font_css}}" }
                    ?.let {
                        throw DeepReadTemplateValidationException("Only {{font_css}} may be used inside <style>")
                    }
            }
        Regex("""(?is)\bstyle\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
            .findAll(html)
            .firstOrNull { match ->
                match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.contains("{{") == true
            }
            ?.let { throw DeepReadTemplateValidationException("Placeholders are not allowed inside style attributes") }
    }

    private fun validateHeroImagePlaceholder(html: String) {
        val allowedRanges = Regex("""(?is)<\s*img\b[^>]*\bsrc\s*=\s*(?:"(\{\{hero_image_url}})"|'(\{\{hero_image_url}})'|(\{\{hero_image_url}}))[^>]*>""")
            .findAll(html)
            .flatMap { match ->
                match.groups.drop(1).mapNotNull { group -> group?.range }
            }
            .toList()
        HERO_IMAGE_TOKEN.findAll(html)
            .firstOrNull { token -> allowedRanges.none { token.range.first in it } }
            ?.let { throw DeepReadTemplateValidationException("{{hero_image_url}} may only be used as an <img> src value") }
    }

    private val BLOCK_PLACEHOLDERS = listOf(
        "narrative_html",
        "timeline_html",
        "core_points_html",
        "diagram_html",
        "analysis_html",
        "extended_reading_html",
    )

    private val PLACEHOLDER_PATTERN = Regex("""\{\{[a-zA-Z0-9_]+}}""")
    private val HERO_IMAGE_TOKEN = Regex("""\{\{hero_image_url}}""")
}
