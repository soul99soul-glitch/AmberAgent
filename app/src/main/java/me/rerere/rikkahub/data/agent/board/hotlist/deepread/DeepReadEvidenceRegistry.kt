package me.rerere.rikkahub.data.agent.board.hotlist.deepread

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class DeepReadEvidenceRegistry(initialUrls: Iterable<String> = emptyList()) {
    private val urls = ConcurrentHashMap.newKeySet<String>()
    private val evidenceTextByUrl = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()

    init {
        initialUrls.forEach(::mark)
    }

    fun mark(url: String, evidenceText: String? = null) {
        normalize(url)?.let { normalized ->
            urls += normalized
            val text = evidenceText?.normalizeEvidenceText()?.takeIf { it.length >= MIN_EVIDENCE_CHARS }
            if (text != null) {
                val segments = evidenceTextByUrl.getOrPut(normalized) { CopyOnWriteArrayList() }
                segments += text.takeLast(MAX_EVIDENCE_CHARS)
                while (segments.size > MAX_EVIDENCE_SEGMENTS_PER_URL) {
                    segments.removeAt(0)
                }
            }
        }
    }

    fun markToolResult(toolName: String, input: kotlinx.serialization.json.JsonElement, parts: List<UIMessagePart>) {
        when (toolName) {
            "scrape_web" -> {
                val requestedUrl = runCatching { input.jsonObject["url"]?.jsonPrimitive?.contentOrNull }
                    .getOrNull()
                val scraped = parts.filterIsInstance<UIMessagePart.Text>()
                    .flatMap { part -> scrapeResultItems(part.text) }
                requestedUrl?.let { url ->
                    val normalizedRequestedUrl = normalize(url)
                    val requestedContent = scraped
                        .filter { (scrapedUrl, _) -> normalize(scrapedUrl) == normalizedRequestedUrl }
                        .joinToString("\n") { it.second }
                    mark(url, requestedContent)
                }
                scraped.forEach { (url, content) -> mark(url, content) }
            }
            "search_web" -> {
                parts.filterIsInstance<UIMessagePart.Text>().forEach { part ->
                    markSearchResultItems(part.text)
                }
            }
        }
    }

    fun isAllowed(url: String): Boolean =
        normalize(url)?.let { it in urls } == true

    fun containsEvidence(url: String, excerpt: String): Boolean {
        val normalizedUrl = normalize(url) ?: return false
        val segments = evidenceTextByUrl[normalizedUrl]?.toList().orEmpty()
        val needle = excerpt.normalizeEvidenceText()
        if (needle.length < MIN_EVIDENCE_CHARS) return false
        return segments.any { segment -> segment.containsEvidenceSegment(needle) }
    }

    private fun String.containsEvidenceSegment(needle: String): Boolean {
        val haystack = normalizeEvidenceText()
        if (needle in haystack) return true

        val requiredTokens = needle.criticalEvidenceTokens()
        val compactHaystack = haystack.compactEvidenceTextWithIndex()
        val compactNeedle = needle.compactEvidenceText()
        if (compactNeedle.length < MIN_EVIDENCE_CHARS) return false
        val compactStart = compactHaystack.text.indexOf(compactNeedle)
        if (compactStart >= 0) {
            return requiredTokens.isEmpty() || haystack
                .substringForCompactMatch(compactHaystack, compactStart, compactNeedle.length)
                .containsAllCriticalTokens(requiredTokens)
        }

        return compactNeedle.hasEnoughChunkOverlapWith(
            haystack = compactHaystack.text,
            requiredTokens = requiredTokens,
        )
    }

    fun allowedUrls(): List<String> = urls.sorted()

    companion object {
        private const val MIN_EVIDENCE_CHARS = 8
        private const val FUZZY_EVIDENCE_MIN_CHARS = 18
        private const val FUZZY_EVIDENCE_WINDOW_PADDING = 18
        private const val MAX_EVIDENCE_CHARS = 30_000
        private const val MAX_EVIDENCE_SEGMENTS_PER_URL = 48
        private val COMPACT_SKIP_REGEX = Regex("[\\p{P}\\p{S}\\s]")
        private val CRITICAL_EVIDENCE_TOKEN_REGEX = Regex(
            listOf(
                """[A-Za-z]{1,16}[- ]?\d[A-Za-z0-9+.-]*""",
                """\d+\s*[A-Za-z]{1,16}[A-Za-z0-9+.-]*""",
                """[+-]?\d+(?:[.,]\d+)?\s*(?:""" +
                    """百分点|个基点|基点|bps?|%|％|人民币|美元|美金|港元|日元|欧元|元|万元|亿元|""" +
                    """mAh|mah|GB|G|TB|英寸|寸|mm|cm|kg|g|MHz|GHz|Hz|W|kW|万|亿|倍|代|号)?""",
                """[零一二三四五六七八九十百千万亿两半]+\s*(?:""" +
                    """个百分点|个基点|基点|人民币|美元|美金|港元|日元|欧元|元|万元|亿元|""" +
                    """mAh|mah|GB|G|TB|英寸|寸|mm|cm|kg|g|MHz|GHz|Hz|W|kW|万|亿|倍|代|号)""",
            ).joinToString("|")
        )

        fun normalize(url: String): String? {
            val trimmed = url.trim().trimEnd('.', ',', ';', ')', ']', '}', '"', '\'')
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
            return runCatching {
                val uri = URI(trimmed)
                val scheme = uri.scheme?.lowercase() ?: return null
                val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
                val path = uri.rawPath.orEmpty().ifBlank { "/" }
                val query = uri.rawQuery?.let { "?$it" }.orEmpty()
                "$scheme://$host$path$query".trimEnd('/')
            }.getOrNull()
        }
    }

    private fun markSearchResultItems(text: String) {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return
        root["items"]
            ?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.forEach { item ->
                val obj = runCatching { item.jsonObject }.getOrNull() ?: return@forEach
                val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                mark(url)
                obj["text"]?.jsonPrimitive?.contentOrNull?.let { evidence ->
                    mark(url, evidence)
                }
            }
    }

    private fun scrapeResultItems(text: String): List<Pair<String, String>> {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return emptyList()
        return root["urls"]
            ?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.mapNotNull { item ->
                val obj = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
                val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                url to content
            }
            .orEmpty()
    }

    private fun String.normalizeEvidenceText(): String =
        replace(Regex("\\s+"), " ").trim()

    private fun String.compactEvidenceText(): String =
        compactEvidenceTextWithIndex().text

    private fun String.compactEvidenceTextWithIndex(): CompactEvidenceText {
        val compactText = StringBuilder(length)
        val originalIndexes = ArrayList<Int>(length)
        forEachIndexed { index, char ->
            if (!COMPACT_SKIP_REGEX.matches(char.toString())) {
                val normalized = char.toString().lowercase()
                compactText.append(normalized)
                repeat(normalized.length) {
                    originalIndexes += index
                }
            }
        }
        return CompactEvidenceText(
            text = compactText.toString(),
            originalIndexes = originalIndexes,
        )
    }

    private fun String.substringForCompactMatch(
        compactText: CompactEvidenceText,
        compactStart: Int,
        compactLength: Int,
    ): String {
        val compactEnd = compactStart + compactLength - 1
        val originalStart = compactText.originalIndexes.getOrNull(compactStart) ?: return ""
        val originalEndInclusive = compactText.originalIndexes.getOrNull(compactEnd) ?: originalStart
        return substring(originalStart, (originalEndInclusive + 1).coerceAtMost(length))
    }

    private fun String.hasEnoughChunkOverlapWith(
        haystack: String,
        requiredTokens: Set<String>,
    ): Boolean {
        if (length < FUZZY_EVIDENCE_MIN_CHARS) return false
        val chunkSize = if (length >= 32) 8 else 6
        val chunks = windowed(size = chunkSize, step = chunkSize / 2, partialWindows = false)
            .filter { it.length == chunkSize }
            .distinct()
        if (chunks.size < 2) return false
        val windowSize = (length + FUZZY_EVIDENCE_WINDOW_PADDING).coerceAtMost(haystack.length)
        if (windowSize <= 0) return false
        val step = (windowSize / 3).coerceAtLeast(1)
        return haystack.windowed(size = windowSize, step = step, partialWindows = true).any { window ->
            requiredTokens.all { it in window } &&
                chunks.count { it in window }.let { hits ->
                    hits >= 3 && hits * 10 >= chunks.size * 7
                }
        }
    }

    private fun String.criticalEvidenceTokens(): Set<String> =
        CRITICAL_EVIDENCE_TOKEN_REGEX.findAll(this)
            .map { match -> match.value.normalizeCriticalEvidenceToken() }
            .filter { it.isNotBlank() }
            .toSet()

    private fun String.normalizeCriticalEvidenceToken(): String {
        val normalized = lowercase()
            .replace("％", "%")
            .replace(",", "")
            .replace(Regex("\\s+"), "")
        return if (normalized.any { it.isLetter() }) {
            normalized.replace(Regex("[._-]+"), "")
        } else {
            normalized
        }
    }

    private fun String.containsAllCriticalTokens(requiredTokens: Set<String>): Boolean =
        criticalEvidenceTokens().containsAll(requiredTokens)

    private data class CompactEvidenceText(
        val text: String,
        val originalIndexes: List<Int>,
    )

}
