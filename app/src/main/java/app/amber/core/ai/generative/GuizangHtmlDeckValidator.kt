package app.amber.core.ai.generative

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Validator and runtime URL normalizer for the full-featured HTML deck path.
 *
 * This deliberately does not share the normal widget sanitizer: rich HTML decks need their
 * original scripts, canvas, WebGL, and Motion One runtime to stay visually faithful. The
 * boundary here is instead a dedicated fullscreen WebView with no native JS bridge, a small
 * known runtime allowlist, and network filtering in the WebViewClient.
 */
object GuizangHtmlDeckValidator {
    const val RENDERER = "full_html"
    const val LEGACY_RENDERER = "guizang_html"
    const val MAX_HTML_BYTES = 1_500_000
    const val LOCAL_RUNTIME_BASE = "https://amberagent.local/full-html/"
    const val LEGACY_LOCAL_RUNTIME_BASE = "https://amberagent.local/guizang/"
    const val LOCAL_MOTION_URL = "${LOCAL_RUNTIME_BASE}motion.min.js"
    const val LOCAL_LUCIDE_URL = "${LOCAL_RUNTIME_BASE}lucide.min.js"
    const val LEGACY_LOCAL_MOTION_URL = "${LEGACY_LOCAL_RUNTIME_BASE}motion.min.js"
    const val LEGACY_LOCAL_LUCIDE_URL = "${LEGACY_LOCAL_RUNTIME_BASE}lucide.min.js"
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

    fun isRenderer(renderer: String?): Boolean {
        val lower = renderer?.lowercase() ?: return false
        return lower == RENDERER || lower == LEGACY_RENDERER
    }

    private val slideElementRegex = Regex(
        """<(?:section|article|div)\b(?=[^>]*(?:\bclass\s*=\s*(?:"[^"]*\bslide\b[^"]*"|'[^']*\bslide\b[^']*'|[^\s>]*\bslide\b[^\s>]*)|\bdata-slide(?:\s|=|>)))[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val slideBlockRegex = Regex(
        """(?is)<(?:section|article)\b(?=[^>]*(?:\bclass\s*=\s*(?:"[^"]*\bslide\b[^"]*"|'[^']*\bslide\b[^']*'|[^\s>]*\bslide\b[^\s>]*)|\bdata-slide(?:\s|=|>)))[^>]*>.*?</(?:section|article)>""",
        RegexOption.IGNORE_CASE,
    )
    private val deckContainerOpenTagRegex = Regex(
        """<(div|main)\b(?=[^>]*(?:\bid\s*=\s*(?:"deck"|'deck')|\bclass\s*=\s*(?:"[^"]*\b(?:deck|slides)\b[^"]*"|'[^']*\b(?:deck|slides)\b[^']*'|[^\s>]*\b(?:deck|slides)\b[^\s>]*)|\bdata-(?:guizang-)?deck(?:\s|=|>)))[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val socialDeckContainerOpenTagRegex = Regex(
        """<(div|main)\b(?=[^>]*(?:\bid\s*=\s*(?:"card-set"|'card-set')|\bclass\s*=\s*(?:"[^"]*\b(?:card-set|poster-set|social-card-set)\b[^"]*"|'[^']*\b(?:card-set|poster-set|social-card-set)\b[^']*'|[^\s>]*\b(?:card-set|poster-set|social-card-set)\b[^\s>]*)))[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val anyIdAttributeRegex = Regex("""\bid\s*=""", RegexOption.IGNORE_CASE)
    private val classAttributeRegex = Regex(
        """\bclass\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""",
        RegexOption.IGNORE_CASE,
    )
    private val dataGuizangDeckRegex = Regex("""\bdata-guizang-deck(?:\s|=|>)""", RegexOption.IGNORE_CASE)
    private val socialPageOpenTagRegex = Regex("""<(section|article|div)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val pageBoundaryTagRegex = Regex("""</?\s*(section|article|div)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val socialPageIntentRegex = Regex("""\b(?:poster|xhs|wide|square|social-card)\b""", RegexOption.IGNORE_CASE)
    private val socialCardBlockRegex = Regex(
        """(?is)<(?:section|article)\b(?=[^>]*\bclass\s*=\s*(?:"[^"]*\b(?:poster|xhs|wide|square|social-card)\b[^"]*"|'[^']*\b(?:poster|xhs|wide|square|social-card)\b[^']*'|[^\s>]*\b(?:poster|xhs|wide|square|social-card)\b[^\s>]*))[^>]*>.*?</(?:section|article)>""",
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
        """<script\b[^>]*\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""",
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

    private data class OrphanPageBlock(
        val range: IntRange,
        val value: String,
        val socialCard: Boolean,
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
        if (!hasSlideLikeContent(runtimeHtml)) {
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
            .replace(LEGACY_LOCAL_MOTION_URL, motionUrl, ignoreCase = true)
            .replace(LEGACY_LOCAL_LUCIDE_URL, lucideUrl, ignoreCase = true)

    private fun normalizeDeckStructure(html: String): String {
        val normalizedContainers = normalizeDeckContainerSlides(normalizeSocialCardDeck(html))
        if (normalizedContainers != html) return normalizedContainers
        val orphanBlocks = collectOrphanPageBlocks(html)
        if (orphanBlocks.isEmpty()) return html
        val first = orphanBlocks.first().range.first
        val last = orphanBlocks.last().range.last
        val slides = orphanBlocks.joinToString(separator = "\n") { block ->
            if (block.socialCard) {
                normalizeSocialCardPageBlock(block.value)
            } else {
                block.value
            }
        }
        return buildString {
            append(html.substring(0, first))
            append("<div id=\"deck\">\n")
            append(slides)
            append("\n</div>")
            append(html.substring(last + 1))
        }
    }

    private fun hasSlideLikeContent(html: String): Boolean =
        slideElementRegex.containsMatchIn(html)

    private fun normalizeDeckContainerSlides(html: String): String =
        replaceElementBlocks(html, deckContainerOpenTagRegex) { openTag, body, closeTag ->
            val opening = addDeckIdIfMissing(openTag)
            val normalizedBody = normalizeDirectChildPageOpenTags(body) { tagName, pageOpenTag ->
                when (tagName) {
                    "section", "article" -> addClassTokenToOpeningTag(pageOpenTag, "slide")
                    else -> pageOpenTag
                }
            }
            opening + normalizedBody + closeTag
        }

    private fun normalizeSocialCardDeck(html: String): String =
        replaceElementBlocks(html, socialDeckContainerOpenTagRegex) { openTag, body, closeTag ->
            val opening = addDataGuizangDeck(openTag)
            val normalizedBody = normalizeDirectChildPageOpenTags(body) { tagName, pageOpenTag ->
                val shouldNormalize = tagName != "div" || hasSocialPageIntent(pageOpenTag)
                if (shouldNormalize) {
                    addClassTokenToOpeningTag(pageOpenTag, "slide", "social-card")
                } else {
                    pageOpenTag
                }
            }
            opening + normalizedBody + closeTag
        }

    private fun collectOrphanPageBlocks(html: String): List<OrphanPageBlock> {
        val blocks = mutableListOf<OrphanPageBlock>()

        fun add(candidate: OrphanPageBlock) {
            val index = blocks.indexOfFirst { existing -> existing.range.overlaps(candidate.range) }
            if (index < 0) {
                blocks += candidate
                return
            }
            val existing = blocks[index]
            if (candidate.socialCard && !existing.socialCard) {
                blocks[index] = existing.copy(socialCard = true)
            }
        }

        slideBlockRegex.findAll(html).forEach { match ->
            add(OrphanPageBlock(match.range, match.value, socialCard = false))
        }
        socialCardBlockRegex.findAll(html).forEach { match ->
            add(OrphanPageBlock(match.range, match.value, socialCard = true))
        }
        return blocks.sortedBy { it.range.first }
    }

    private fun IntRange.overlaps(other: IntRange): Boolean =
        first <= other.last && other.first <= last

    private fun normalizeDirectChildPageOpenTags(
        html: String,
        transform: (tagName: String, openTag: String) -> String,
    ): String {
        val output = StringBuilder()
        var cursor = 0
        var depth = 0
        pageBoundaryTagRegex.findAll(html).forEach { match ->
            val tag = match.value
            val tagName = match.groupValues[1].lowercase()
            val closing = tag.trimStart().startsWith("</")
            output.append(html.substring(cursor, match.range.first))
            if (closing) {
                depth = (depth - 1).coerceAtLeast(0)
                output.append(tag)
            } else {
                output.append(if (depth == 0) transform(tagName, tag) else tag)
                if (!tag.trimEnd().endsWith("/>")) depth += 1
            }
            cursor = match.range.last + 1
        }
        if (output.isEmpty()) return html
        output.append(html.substring(cursor))
        return output.toString()
    }

    private fun replaceElementBlocks(
        html: String,
        openTagRegex: Regex,
        transform: (openTag: String, body: String, closeTag: String) -> String,
    ): String {
        val output = StringBuilder()
        var emitCursor = 0
        var searchCursor = 0
        while (searchCursor < html.length) {
            val match = openTagRegex.find(html, searchCursor) ?: break
            val tagName = match.groupValues[1]
            val closeRange = findMatchingCloseTag(html, tagName, match.range.last + 1)
            if (closeRange == null) {
                searchCursor = match.range.last + 1
                continue
            }
            output.append(html.substring(emitCursor, match.range.first))
            output.append(
                transform(
                    match.value,
                    html.substring(match.range.last + 1, closeRange.first),
                    html.substring(closeRange.first, closeRange.last + 1),
                )
            )
            emitCursor = closeRange.last + 1
            searchCursor = emitCursor
        }
        if (output.isEmpty()) return html
        output.append(html.substring(emitCursor))
        return output.toString()
    }

    private fun findMatchingCloseTag(
        html: String,
        tagName: String,
        startIndex: Int,
    ): IntRange? {
        val tagRegex = Regex("""</?\s*${Regex.escape(tagName)}\b[^>]*>""", RegexOption.IGNORE_CASE)
        var depth = 1
        var cursor = startIndex
        while (cursor < html.length) {
            val match = tagRegex.find(html, cursor) ?: return null
            val value = match.value.trimStart()
            if (value.startsWith("</")) {
                depth -= 1
                if (depth == 0) return match.range
            } else if (!value.endsWith("/>")) {
                depth += 1
            }
            cursor = match.range.last + 1
        }
        return null
    }

    private fun addClassTokenToOpeningTag(openTag: String, vararg classTokens: String): String {
        val tokens = classTokens.filter { it.isNotBlank() }
        if (tokens.isEmpty()) return openTag
        val classMatch = classAttributeRegex.find(openTag) ?: return appendAttribute(
            openTag,
            "class=\"${tokens.joinToString(" ")}\"",
        )
        val existingClass = classMatch.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
        val existingTokens = existingClass.split(Regex("""\s+""")).filter { it.isNotBlank() }
        val missingTokens = tokens.filter { token ->
            existingTokens.none { it.equals(token, ignoreCase = true) }
        }
        if (missingTokens.isEmpty()) return openTag
        val updatedClass = (existingTokens + missingTokens).joinToString(" ")
        return openTag.replaceRange(classMatch.range, "class=\"$updatedClass\"")
    }

    private fun addDeckIdIfMissing(openTag: String): String =
        if (anyIdAttributeRegex.containsMatchIn(openTag)) {
            openTag
        } else {
            appendAttribute(openTag, "id=\"deck\"")
        }

    private fun addDataGuizangDeck(openTag: String): String =
        if (dataGuizangDeckRegex.containsMatchIn(openTag)) {
            openTag
        } else {
            appendAttribute(openTag, "data-guizang-deck")
        }

    private fun appendAttribute(openTag: String, attribute: String): String {
        val end = openTag.lastIndexOf('>')
        if (end < 0) return openTag
        val insertAt = if (end > 0 && openTag[end - 1] == '/') end - 1 else end
        return openTag.substring(0, insertAt) + " " + attribute + openTag.substring(insertAt)
    }

    private fun hasSocialPageIntent(openTag: String): Boolean =
        classAttributeRegex.find(openTag)
            ?.groupValues
            ?.drop(1)
            ?.firstOrNull { it.isNotBlank() }
            ?.let { socialPageIntentRegex.containsMatchIn(it) }
            ?: false

    private fun normalizeSocialCardPageBlock(block: String): String =
        socialPageOpenTagRegex.find(block)?.let { match ->
            block.replaceRange(match.range, addClassTokenToOpeningTag(match.value, "slide", "social-card"))
        } ?: block

    private fun validateExternalScripts(html: String): ValidationResult {
        scriptSrcRegex.findAll(html).forEach { match ->
            val url = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
            if (runtimeAssetForUrl(url) == null) {
                return ValidationResult(false, "external script is not an allowed full_html runtime asset: ${url.take(80)}")
            }
        }
        dynamicImportRegex.findAll(html).forEach { match ->
            val url = match.groupValues[2]
            if (looksLikeExternalScriptReference(url) && runtimeAssetForUrl(url) == null) {
                return ValidationResult(false, "dynamic import is not an allowed full_html runtime asset: ${url.take(80)}")
            }
        }
        moduleFromRegex.findAll(html).forEach { match ->
            val url = match.groupValues[2]
            if (looksLikeExternalScriptReference(url) && runtimeAssetForUrl(url) == null) {
                return ValidationResult(false, "module import is not an allowed full_html runtime asset: ${url.take(80)}")
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
            lower == LEGACY_LOCAL_MOTION_URL ||
            lower == "./assets/motion.min.js" ||
            lower == "assets/motion.min.js"
    }

    private fun isKnownLucideUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower == LOCAL_LUCIDE_URL ||
            lower == LEGACY_LOCAL_LUCIDE_URL ||
            lower == "./assets/lucide.min.js" ||
            lower == "assets/lucide.min.js"
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.booleanOrDefault(key: String, default: Boolean): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
}
