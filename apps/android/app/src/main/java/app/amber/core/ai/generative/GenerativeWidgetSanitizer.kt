package app.amber.core.ai.generative

import app.amber.core.settings.GenerativeUiSetting

enum class GenerativeWidgetSanitizeStatus {
    READY,
    EMPTY,
    TOO_LARGE,
    UNSAFE,
}

data class SanitizedGenerativeWidget(
    val status: GenerativeWidgetSanitizeStatus,
    val html: String = "",
    val reason: String? = null,
)

object GenerativeWidgetSanitizer {
    private const val MAX_TAG_COUNT = 420
    private const val MAX_TEXT_CHARS = 10_000

    private val scriptBlock = Regex("""<script\b[\s\S]*?</script\s*>""", RegexOption.IGNORE_CASE)
    private val scriptOpen = Regex("""<script\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val dangerousBlock = Regex(
        """<\s*(iframe|object|embed|form|foreignObject)\b[\s\S]*?</\s*\1\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val dangerousVoid = Regex(
        """<\s*(iframe|object|embed|meta|link|base|foreignObject)\b[^>]*\/?>""",
        RegexOption.IGNORE_CASE,
    )
    private val eventAttribute = Regex(
        """\s+on[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>"']*)""",
        RegexOption.IGNORE_CASE,
    )
    private val urlAttribute = Regex(
        """\s+(href|xlink:href|src|srcset|poster|background|action|srcdoc)\s*=\s*("[^"]*"|'[^']*'|[^\s>"']*)""",
        RegexOption.IGNORE_CASE,
    )
    private val fontFace = Regex("""@font-face\s*\{[\s\S]*?\}""", RegexOption.IGNORE_CASE)
    private val cssImport = Regex("""@import\s+[^;]+;?""", RegexOption.IGNORE_CASE)
    private val javascriptStyleUrl = Regex("""url\(\s*(['"]?)\s*javascript:[^)]+\)""", RegexOption.IGNORE_CASE)
    private val externalStyleUrl = Regex("""url\(\s*(['"]?)\s*(https?:|//)[^)]+\)""", RegexOption.IGNORE_CASE)
    private val unsafeStyleUrl = Regex("""url\(\s*(['"]?)(?!data:image/|#)[^)]+\)""", RegexOption.IGNORE_CASE)
    private val fixedPositionStyle = Regex("""position\s*:\s*(fixed|-webkit-sticky|sticky)\s*;?""", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("""<\s*[a-zA-Z][^>]*>""")
    private val stripTags = Regex("""<[^>]+>""")

    fun sanitize(code: String, setting: GenerativeUiSetting): SanitizedGenerativeWidget {
        if (code.isBlank()) {
            return SanitizedGenerativeWidget(GenerativeWidgetSanitizeStatus.EMPTY)
        }
        if (code.length > setting.maxWidgetCodeChars.coerceAtLeast(1_000)) {
            return SanitizedGenerativeWidget(GenerativeWidgetSanitizeStatus.TOO_LARGE, reason = "code length")
        }

        val sanitized = code
            .replace(scriptBlock, "")
            .replace(scriptOpen, "")
            .replace(dangerousBlock, "")
            .replace(dangerousVoid, "")
            .replace(eventAttribute, "")
            .replace(fontFace, "")
            .replace(cssImport, "")
            .replace(javascriptStyleUrl, "none")
            .replace(externalStyleUrl, "none")
            .replace(unsafeStyleUrl, "none")
            .replace(fixedPositionStyle, "position:relative;")
            .replace(urlAttribute) { match ->
                val attr = match.groupValues[1].lowercase()
                val rawValue = match.groupValues[2].trim()
                val value = rawValue.trim('"', '\'').trim()
                val safe = when (attr) {
                    "href" -> value.startsWith("#") ||
                        value.startsWith("https://", ignoreCase = true) ||
                        value.startsWith("http://", ignoreCase = true)
                    "xlink:href" -> value.startsWith("#")
                    "src" -> isSafeDataImage(value)
                    else -> false
                }
                if (safe) match.value else ""
            }
            .trim()

        if (tagRegex.findAll(sanitized).count() > MAX_TAG_COUNT) {
            return SanitizedGenerativeWidget(GenerativeWidgetSanitizeStatus.TOO_LARGE, reason = "tag count")
        }
        if (sanitized.replace(stripTags, "").length > MAX_TEXT_CHARS) {
            return SanitizedGenerativeWidget(GenerativeWidgetSanitizeStatus.TOO_LARGE, reason = "text length")
        }
        safetyViolation(sanitized)?.let { reason ->
            return SanitizedGenerativeWidget(GenerativeWidgetSanitizeStatus.UNSAFE, reason = reason)
        }

        return if (sanitized.isBlank()) {
            SanitizedGenerativeWidget(GenerativeWidgetSanitizeStatus.EMPTY)
        } else {
            SanitizedGenerativeWidget(
                status = GenerativeWidgetSanitizeStatus.READY,
                html = sanitized,
            )
        }
    }

    private fun safetyViolation(html: String): String? {
        val lower = html.lowercase()
        val compact = lower.replace(Regex("""[\u0000-\u001f\u00a0\u2000-\u200f\ufeff\s]+"""), "")
        return when {
            "<script" in compact -> "script"
            Regex("""<\s*(iframe|object|embed|form|meta|link|base|foreignobject)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(html) -> "dangerous tag"
            Regex("""\son[a-z]+\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(html) -> "event handler"
            "javascript:" in compact -> "javascript url"
            "data:text/html" in compact -> "html data url"
            Regex("""\s(srcdoc|action|srcset|poster|background)\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(html) -> "external resource"
            Regex("""\ssrc\s*=\s*(?!["']?data:image/(png|jpeg|jpg|gif|webp);base64,)""", RegexOption.IGNORE_CASE).containsMatchIn(html) -> "external resource"
            "data:image/svg" in compact -> "svg data image"
            Regex("""url\(""", RegexOption.IGNORE_CASE).containsMatchIn(html) &&
                !Regex("""url\(\s*(['"]?)data:image/(png|jpeg|jpg|gif|webp);base64,""", RegexOption.IGNORE_CASE).containsMatchIn(html) &&
                !Regex("""url\(\s*(['"]?)#""").containsMatchIn(html) -> "css url"
            else -> null
        }
    }

    private fun isSafeDataImage(value: String): Boolean =
        Regex("""^data:image/(png|jpeg|jpg|gif|webp);base64,[a-z0-9+/=\s]+$""", RegexOption.IGNORE_CASE)
            .matches(value)
}
