package app.amber.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.amber.search.SearchResult.SearchResultItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

object DuckDuckGoSearchService {
    const val name: String = "DuckDuckGo"

    suspend fun search(
        query: String,
        commonOptions: SearchCommonOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val lite = Jsoup.connect("https://lite.duckduckgo.com/lite/?q=$encoded")
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                .referrer("https://duckduckgo.com/")
                .timeout(10_000)
                .get()
            val liteResults = parseLite(lite, commonOptions.resultSize)
            if (liteResults.isNotEmpty()) {
                return@runCatching SearchResult(items = liteResults)
            }

            val html = Jsoup.connect("https://html.duckduckgo.com/html/?q=$encoded")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                .referrer("https://duckduckgo.com/")
                .timeout(10_000)
                .get()
            val htmlResults = parseHtml(html, commonOptions.resultSize)
            if (htmlResults.isNotEmpty()) {
                return@runCatching SearchResult(items = htmlResults)
            }

            val pageText = (lite.text() + " " + html.text()).lowercase()
            require(!pageText.contains("bots use duckduckgo") && !pageText.contains("anomaly-modal")) {
                "DuckDuckGo requires human verification"
            }
            error("DuckDuckGo returned no parseable organic results")
        }
    }

    private fun parseLite(doc: org.jsoup.nodes.Document, limit: Int): List<SearchResultItem> {
        val anchors = doc.select("a.result-link")
                .filterNot { it.parents().any { parent -> parent.hasClass("result-sponsored") } }

        return anchors.mapNotNull { anchor ->
            val title = anchor.text().trim()
            val href = decodeDuckDuckGoUrl(anchor.attr("href")).trim()
            if (title.isBlank() || !href.startsWith("http")) return@mapNotNull null
            SearchResultItem(
                title = title,
                url = href,
                text = anchor.resultSnippet(),
            )
        }.distinctBy { it.url }.take(limit)
    }

    private fun parseHtml(doc: org.jsoup.nodes.Document, limit: Int): List<SearchResultItem> {
        return doc.select(".result").mapNotNull { result ->
            val anchor = result.selectFirst("a.result__a") ?: result.selectFirst("h2 a") ?: return@mapNotNull null
            val title = anchor.text().trim()
            val href = decodeDuckDuckGoUrl(anchor.attr("href")).trim()
            if (title.isBlank() || !href.startsWith("http")) return@mapNotNull null
            SearchResultItem(
                title = title,
                url = href,
                text = result.select(".result__snippet, .result__body").text().trim(),
            )
        }.distinctBy { it.url }.take(limit)
    }

    private fun Element.resultSnippet(): String {
        val row = parent()?.parent()
        val nextRows = generateSequence(row?.nextElementSibling()) { it.nextElementSibling() }
            .take(3)
            .toList()
        return nextRows.firstNotNullOfOrNull { candidate ->
            candidate.select("td.result-snippet").text().trim().takeIf { it.isNotBlank() }
        }.orEmpty()
    }

    private fun decodeDuckDuckGoUrl(rawHref: String): String {
        val href = rawHref.trim()
        val normalized = when {
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "https://duckduckgo.com$href"
            else -> href
        }
        return runCatching {
            val uri = URI(normalized)
            val uddg = uri.rawQuery
                ?.split("&")
                ?.firstOrNull { it.substringBefore("=") == "uddg" }
                ?.substringAfter("=", "")
            if (uddg.isNullOrBlank()) normalized else URLDecoder.decode(uddg, "UTF-8")
        }.getOrDefault(normalized)
    }
}
