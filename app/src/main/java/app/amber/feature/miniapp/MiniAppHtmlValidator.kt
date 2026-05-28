package app.amber.feature.miniapp

class MiniAppValidationException(message: String) : IllegalArgumentException(message)

object MiniAppHtmlValidator {
    private val requiredHtmlPattern = Regex("""(?is)<\s*(html\b|!doctype\s+html)""")
    private val blockedPatterns = listOf(
        Regex("""(?is)<\s*script\b[^>]*\bsrc\s*=""") to "External scripts are not allowed",
        Regex("""(?is)<\s*(iframe|object|embed|form)\b""") to "Embedded/submit-capable elements are not allowed",
        Regex("""(?is)<\s*(img|source)\b[^>]*\bsrcset\s*=""") to "Image srcset is not supported",
        Regex("""(?is)<\s*link\b[^>]*\bhref\s*=\s*['"]?\s*https?:""") to "External stylesheets are not allowed",
        Regex("""(?is)@import\s+(['"]?\s*)?(https?:|//|file:|content:)""") to "CSS imports are not allowed",
        Regex("""(?is)url\s*\(\s*(['"]?)\s*(https?:|//|file:|content:)""") to "External CSS URLs are not allowed",
        Regex("""(?is)\beval\s*\(""") to "eval() is not allowed",
        Regex("""(?is)\bnew\s+Function\b""") to "new Function is not allowed",
        Regex("""(?is)\bimport\s*\(""") to "dynamic import() is not allowed",
        Regex("""(?is)\bimport\s+['"]""") to "static import is not allowed",
        Regex("""(?is)\bWebSocket\b""") to "WebSocket is not allowed",
        Regex("""(?is)\bEventSource\b""") to "EventSource is not allowed",
        Regex("""(?is)\bXMLHttpRequest\b""") to "XMLHttpRequest is not allowed",
        Regex("""(?is)\blocalStorage\b""") to "localStorage is not allowed",
        Regex("""(?is)\bsessionStorage\b""") to "sessionStorage is not allowed",
        Regex("""(?is)\bindexedDB\b""") to "indexedDB is not allowed",
        Regex("""(?is)\bnavigator\s*\.\s*geolocation\b""") to "geolocation is not allowed",
        Regex("""(?is)\bnavigator\s*\.\s*mediaDevices\b""") to "mediaDevices is not allowed",
        Regex("""(?is)\bnavigator\s*\.\s*clipboard\b""") to "native clipboard is not allowed",
        Regex("""(?is)\bnavigator\s*\[\s*['"]\s*(geolocation|mediaDevices|clipboard)\s*['"]\s*\]""") to "computed access to blocked navigator APIs is not allowed",
        Regex("""(?is)\bwindow\s*\[\s*['"]\s*(fetch|XMLHttpRequest|WebSocket|EventSource|localStorage|sessionStorage|indexedDB)\s*['"]\s*\]""") to "computed access to blocked browser APIs is not allowed",
        Regex("""(?is)\bglobalThis\s*\[\s*['"]\s*(fetch|XMLHttpRequest|WebSocket|EventSource|localStorage|sessionStorage|indexedDB)\s*['"]\s*\]""") to "computed access to blocked browser APIs is not allowed",
        Regex("""(?is)(^|[^A-Za-z0-9_$\.])fetch\s*\(""") to "native fetch() is not allowed",
    )
    private val quotedImageResourcePattern =
        Regex("""(?is)<\s*(img|source)\b[^>]*\b(src|srcset)\s*=\s*(['"])(.*?)\3""")
    private val unquotedImageResourcePattern =
        Regex("""(?is)<\s*(img|source)\b[^>]*\b(src|srcset)\s*=\s*([^\s"'=<>`]+)""")

    fun validate(html: String) {
        val sizeBytes = html.encodeToByteArray().size
        if (sizeBytes > MINI_APP_MAX_HTML_BYTES) {
            throw MiniAppValidationException("HTML is too large: $sizeBytes bytes")
        }
        if (!requiredHtmlPattern.containsMatchIn(html)) {
            throw MiniAppValidationException("HTML must include <html> or <!DOCTYPE html>")
        }
        blockedPatterns.firstOrNull { (pattern, _) -> pattern.containsMatchIn(html) }?.let { (_, reason) ->
            throw MiniAppValidationException(reason)
        }
        if (hasInvalidImageResource(html)) {
            throw MiniAppValidationException("MiniApp images must use data:image or https URLs")
        }
    }

    private fun hasInvalidImageResource(html: String): Boolean {
        val quotedInvalid = quotedImageResourcePattern.findAll(html).any { match ->
            !match.groupValues[4].trim().isAllowedImageUrl()
        }
        if (quotedInvalid) return true
        return unquotedImageResourcePattern.findAll(html).any { match ->
            !match.groupValues[3].trim().isAllowedImageUrl()
        }
    }

    private fun String.isAllowedImageUrl(): Boolean {
        return startsWith("data:image/", ignoreCase = true) || startsWith("https://", ignoreCase = true)
    }
}
