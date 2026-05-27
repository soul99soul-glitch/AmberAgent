package me.rerere.rikkahub.data.ai.generative

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Validator and runtime URL normalizer for the guizang HTML deck path.
 *
 * This deliberately does not share the normal widget sanitizer: guizang decks need their
 * original scripts, canvas, WebGL, and Motion One runtime to stay visually faithful. The
 * boundary here is instead a dedicated fullscreen WebView with no native JS bridge, a small
 * known runtime allowlist, and network filtering in the WebViewClient.
 */
object GuizangHtmlDeckValidator {
    const val RENDERER = "guizang_html"
    const val MAX_HTML_BYTES = 1_500_000
    const val LOCAL_RUNTIME_BASE = "https://amberagent.local/guizang/"
    const val LOCAL_MOTION_URL = "${LOCAL_RUNTIME_BASE}motion.min.js"
    const val LOCAL_LUCIDE_URL = "${LOCAL_RUNTIME_BASE}lucide.min.js"
    const val MOTION_ASSET_PATH = "generative-libs/guizang/motion.min.js"
    const val LUCIDE_ASSET_PATH = "generative-libs/guizang/lucide.min.js"

    private val json = Json { ignoreUnknownKeys = true }

    data class DeckSpec(
        val html: String,
        val source: String? = null,
        val allowRemoteImages: Boolean = true,
        val allowRemoteFonts: Boolean = true,
    )

    data class ValidationResult(
        val valid: Boolean,
        val reason: String? = null,
    )

    enum class RuntimeAsset(
        val assetPath: String,
        val mimeType: String,
    ) {
        MOTION(MOTION_ASSET_PATH, "application/javascript"),
        LUCIDE(LUCIDE_ASSET_PATH, "application/javascript"),
    }

    private val slideElementRegex = Regex(
        """<(?:section|article|div)\b(?=[^>]*(?:\bclass\s*=\s*(?:"[^"]*\bslide\b[^"]*"|'[^']*\bslide\b[^']*'|[^\s>]*\bslide\b[^\s>]*)|\bdata-slide(?:\s|=|>)))[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val slideBlockRegex = Regex(
        """(?is)<(?:section|article)\b(?=[^>]*(?:\bclass\s*=\s*(?:"[^"]*\bslide\b[^"]*"|'[^']*\bslide\b[^']*'|[^\s>]*\bslide\b[^\s>]*)|\bdata-slide(?:\s|=|>)))[^>]*>.*?</(?:section|article)>""",
        RegexOption.IGNORE_CASE,
    )
    private val deckContainerWithoutIdRegex = Regex(
        """<(?:(?:div)|(?:main))\b(?=[^>]*(?:\bclass\s*=\s*(?:"[^"]*\bdeck\b[^"]*"|'[^']*\bdeck\b[^']*'|[^\s>]*\bdeck\b[^\s>]*)|\bdata-(?:guizang-)?deck(?:\s|=|>)))(?![^>]*\bid\s*=)[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val blockedPatterns = listOf(
        BlockedPattern(Regex("""file://""", RegexOption.IGNORE_CASE), "file:// is not allowed"),
        BlockedPattern(Regex("""content://""", RegexOption.IGNORE_CASE), "content:// is not allowed"),
        BlockedPattern(Regex("""intent://""", RegexOption.IGNORE_CASE), "intent:// is not allowed"),
        BlockedPattern(Regex("""android_asset://""", RegexOption.IGNORE_CASE), "android_asset:// is not allowed"),
        BlockedPattern(Regex("""\baddJavascriptInterface\b""", RegexOption.IGNORE_CASE), "native bridge access is not allowed"),
        BlockedPattern(Regex("""\bAmberWidget\b""", RegexOption.IGNORE_CASE), "AmberWidget bridge access is not allowed"),
        BlockedPattern(Regex("""\bwindow\.open\s*\(""", RegexOption.IGNORE_CASE), "popups are not allowed"),
        BlockedPattern(Regex("""\btarget\s*=\s*(['"])_blank\1""", RegexOption.IGNORE_CASE), "new windows are not allowed"),
        BlockedPattern(Regex("""<a\b[^>]*\bdownload(?:\s|=|>)""", RegexOption.IGNORE_CASE), "downloads are not allowed"),
        BlockedPattern(Regex("""<iframe\b|<object\b|<embed\b""", RegexOption.IGNORE_CASE), "embedded frames are not allowed"),
        BlockedPattern(Regex("""<form\b|<input\b[^>]*\btype\s*=\s*(['"])file\1""", RegexOption.IGNORE_CASE), "form/file input is not allowed"),
        BlockedPattern(Regex("""\bfetch\s*\(""", RegexOption.IGNORE_CASE), "network fetch is not allowed"),
        BlockedPattern(Regex("""\bXMLHttpRequest\b""", RegexOption.IGNORE_CASE), "XMLHttpRequest is not allowed"),
        BlockedPattern(Regex("""\bWebSocket\s*\(""", RegexOption.IGNORE_CASE), "WebSocket is not allowed"),
        BlockedPattern(Regex("""\bEventSource\s*\(""", RegexOption.IGNORE_CASE), "EventSource is not allowed"),
        BlockedPattern(Regex("""\bnavigator\.serviceWorker\b""", RegexOption.IGNORE_CASE), "service workers are not allowed"),
        BlockedPattern(Regex("""\bnavigator\.geolocation\b""", RegexOption.IGNORE_CASE), "geolocation is not allowed"),
        BlockedPattern(Regex("""\bgetUserMedia\s*\(""", RegexOption.IGNORE_CASE), "camera/microphone access is not allowed"),
        BlockedPattern(Regex("""\bNotification\.requestPermission\b""", RegexOption.IGNORE_CASE), "notifications are not allowed"),
        BlockedPattern(Regex("""\bmodulepreload\b""", RegexOption.IGNORE_CASE), "module preloads are not allowed"),
    )

    private val scriptSrcRegex = Regex(
        """<script\b[^>]*\bsrc\s*=\s*(['"])(.*?)\1""",
        RegexOption.IGNORE_CASE,
    )
    private val dynamicImportRegex = Regex(
        """\bimport\s*\(\s*(['"])(.*?)\1\s*\)""",
        RegexOption.IGNORE_CASE,
    )
    private val moduleFromRegex = Regex(
        """\bfrom\s*(['"])(.*?)\1""",
        RegexOption.IGNORE_CASE,
    )

    private data class BlockedPattern(
        val regex: Regex,
        val reason: String,
    )

    fun normalizeSpecJson(specJson: String): DeckSpec? {
        val obj = runCatching { json.parseToJsonElement(specJson) as? JsonObject }.getOrNull()
            ?: return null
        val html = obj.stringOrNull("html")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return DeckSpec(
            html = html,
            source = obj.stringOrNull("source"),
            allowRemoteImages = obj.booleanOrDefault("allowRemoteImages", true),
            allowRemoteFonts = obj.booleanOrDefault("allowRemoteFonts", true),
        )
    }

    fun validateSpecJson(specJson: String): ValidationResult {
        val spec = normalizeSpecJson(specJson) ?: return ValidationResult(false, "expected spec.html")
        return validateDeck(spec)
    }

    fun validateDeck(spec: DeckSpec): ValidationResult = validateHtml(spec.html)

    fun validateHtml(html: String): ValidationResult {
        if (html.toByteArray(Charsets.UTF_8).size > MAX_HTML_BYTES) {
            return ValidationResult(false, "html too large: max ${MAX_HTML_BYTES / 1000}KB")
        }
        val runtimeHtml = prepareRuntimeHtml(html)
        if (!slideElementRegex.containsMatchIn(runtimeHtml)) {
            return ValidationResult(false, "expected at least one slide element: <section class=\"slide ...\">")
        }
        blockedPatterns.firstOrNull { it.regex.containsMatchIn(runtimeHtml) }?.let {
            return ValidationResult(false, it.reason)
        }
        validateExternalScripts(runtimeHtml).let { result ->
            if (!result.valid) return result
        }
        return ValidationResult(true)
    }

    fun prepareRuntimeHtml(html: String): String =
        normalizeDeckStructure(rewriteRuntimeUrls(html))

    fun rewriteRuntimeUrls(html: String): String =
        rewriteRuntimeUrls(html, LOCAL_MOTION_URL, LOCAL_LUCIDE_URL)

    fun rewriteRuntimeUrlsForArchive(html: String): String =
        normalizeDeckStructure(rewriteRuntimeUrls(html, "assets/motion.min.js", "assets/lucide.min.js"))

    fun runtimeAssetForUrl(url: String): RuntimeAsset? {
        val normalized = rewriteRuntimeUrls(url.trim())
        if (isKnownMotionUrl(normalized)) return RuntimeAsset.MOTION
        if (isKnownLucideUrl(normalized)) return RuntimeAsset.LUCIDE
        return null
    }

    fun isAllowedRemoteImage(url: String): Boolean {
        val lower = url.substringBefore('?').lowercase()
        return lower.startsWith("https://") &&
            listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".avif").any(lower::endsWith)
    }

    fun isAllowedRemoteFontOrStylesheet(url: String): Boolean {
        val lower = url.substringBefore('?').lowercase()
        if (lower.startsWith("https://fonts.googleapis.com/")) return true
        if (lower.startsWith("https://fonts.gstatic.com/")) return true
        return lower.startsWith("https://") &&
            listOf(".css", ".woff", ".woff2", ".ttf", ".otf").any(lower::endsWith)
    }

    private fun rewriteRuntimeUrls(
        html: String,
        motionUrl: String,
        lucideUrl: String,
    ): String =
        html
            .replace(
                Regex(
                    """https://unpkg\.com/lucide(?:@[^/"'`\s<>]+)?(?:/dist/umd/lucide(?:\.min)?\.js)?""",
                    RegexOption.IGNORE_CASE,
                ),
                lucideUrl,
            )
            .replace(
                Regex(
                    """https://cdn\.jsdelivr\.net/npm/lucide(?:@[^/"'`\s<>]+)?(?:/dist/umd/lucide(?:\.min)?\.js)?""",
                    RegexOption.IGNORE_CASE,
                ),
                lucideUrl,
            )
            .replace(
                Regex(
                    """https://cdn\.jsdelivr\.net/npm/motion(?:@[^/"'`\s<>]+)?/\+esm""",
                    RegexOption.IGNORE_CASE,
                ),
                motionUrl,
            )
            .replace(
                Regex(
                    """https://unpkg\.com/motion(?:@[^/"'`\s<>]+)?(?:/\+esm)?""",
                    RegexOption.IGNORE_CASE,
                ),
                motionUrl,
            )
            .replace(
                Regex("""(?<=['"])(?:\./)?assets/motion\.min\.js(?=['"])""", RegexOption.IGNORE_CASE),
                motionUrl,
            )
            .replace(
                Regex("""(?<=['"])(?:\./)?assets/lucide\.min\.js(?=['"])""", RegexOption.IGNORE_CASE),
                lucideUrl,
            )

    private fun normalizeDeckStructure(html: String): String {
        if (Regex("""\bid\s*=\s*(['"])deck\1""", RegexOption.IGNORE_CASE).containsMatchIn(html)) return html
        deckContainerWithoutIdRegex.find(html)?.let { match ->
            val openTag = match.value
            return html.replaceRange(match.range, openTag.dropLast(1) + """ id="deck">""")
        }
        val slideBlocks = slideBlockRegex.findAll(html).toList()
        if (slideBlocks.isEmpty()) return html
        val first = slideBlocks.first().range.first
        val last = slideBlocks.last().range.last
        val slides = slideBlocks.joinToString(separator = "\n") { it.value }
        return buildString {
            append(html.substring(0, first))
            append("<div id=\"deck\">\n")
            append(slides)
            append("\n</div>")
            append(html.substring(last + 1))
        }
    }

    private fun validateExternalScripts(html: String): ValidationResult {
        scriptSrcRegex.findAll(html).forEach { match ->
            val url = match.groupValues[2]
            if (runtimeAssetForUrl(url) == null) {
                return ValidationResult(false, "external script is not a guizang runtime asset: ${url.take(80)}")
            }
        }
        dynamicImportRegex.findAll(html).forEach { match ->
            val url = match.groupValues[2]
            if (looksLikeExternalScriptReference(url) && runtimeAssetForUrl(url) == null) {
                return ValidationResult(false, "dynamic import is not a guizang runtime asset: ${url.take(80)}")
            }
        }
        moduleFromRegex.findAll(html).forEach { match ->
            val url = match.groupValues[2]
            if (looksLikeExternalScriptReference(url) && runtimeAssetForUrl(url) == null) {
                return ValidationResult(false, "module import is not a guizang runtime asset: ${url.take(80)}")
            }
        }
        return ValidationResult(true)
    }

    private fun looksLikeExternalScriptReference(value: String): Boolean {
        val lower = value.lowercase()
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.endsWith(".js") ||
            lower.endsWith(".mjs") ||
            lower.contains("/+esm")
    }

    private fun isKnownMotionUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower == LOCAL_MOTION_URL ||
            lower.endsWith("/assets/motion.min.js") ||
            lower == "./assets/motion.min.js" ||
            lower == "assets/motion.min.js"
    }

    private fun isKnownLucideUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower == LOCAL_LUCIDE_URL ||
            lower.endsWith("/assets/lucide.min.js") ||
            lower == "./assets/lucide.min.js" ||
            lower == "assets/lucide.min.js"
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.booleanOrDefault(key: String, default: Boolean): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
}
