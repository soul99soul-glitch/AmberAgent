package me.rerere.rikkahub.data.agent.board.hotlist.deepread

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class DeepReadEvidenceRegistry(initialUrls: Iterable<String> = emptyList()) {
    private val urls = ConcurrentHashMap.newKeySet<String>()
    private val evidenceTextByUrl = ConcurrentHashMap<String, String>()

    init {
        initialUrls.forEach(::mark)
    }

    fun mark(url: String, evidenceText: String? = null) {
        normalize(url)?.let { normalized ->
            urls += normalized
            val text = evidenceText?.normalizeEvidenceText()?.takeIf { it.length >= MIN_EVIDENCE_CHARS }
            if (text != null) {
                evidenceTextByUrl.merge(normalized, text) { old, incoming ->
                    "$old\n$incoming".takeLast(MAX_EVIDENCE_CHARS)
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
                val combinedContent = scraped.joinToString("\n") { it.second }
                requestedUrl?.let { mark(it, combinedContent) }
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
        val haystack = evidenceTextByUrl[normalizedUrl]?.normalizeEvidenceText() ?: return false
        val needle = excerpt.normalizeEvidenceText()
        return needle.length >= MIN_EVIDENCE_CHARS && needle in haystack
    }

    fun allowedUrls(): List<String> = urls.sorted()

    companion object {
        private const val MIN_EVIDENCE_CHARS = 8
        private const val MAX_EVIDENCE_CHARS = 30_000

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
                val evidence = listOfNotNull(
                    obj["title"]?.jsonPrimitive?.contentOrNull,
                    obj["text"]?.jsonPrimitive?.contentOrNull,
                ).joinToString("\n")
                mark(url, evidence)
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
}
