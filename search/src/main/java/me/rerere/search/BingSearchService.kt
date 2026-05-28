package me.rerere.search
import app.amber.search.R

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

object BingSearchService : SearchService<SearchServiceOptions.BingLocalOptions> {
    override val name: String = "Bing"

    @Composable
    override fun Description() {
        Text(stringResource(R.string.bing_desc))
    }

    override val parameters: InputSchema?
        get() = InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override val scrapingParameters: InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BingLocalOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val url = "https://www.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8")
            val locale = Locale.getDefault()
            val acceptLanguage = "${locale.language}-${locale.country},${locale.language}"
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                )
                .header("Accept-Language", acceptLanguage)
                .header("Accept-Encoding", "gzip, deflate, sdch")
                .header("Accept-Charset", "utf-8")
                .header("Connection", "keep-alive")
                .referrer("https://www.bing.com/")
                .cookie("SRCHHPGUSR", "ULSR=1")
                .timeout(10_000)
                .get()

            val pageText = doc.text()
            require(!pageText.contains("verify you are human", ignoreCase = true) &&
                    !pageText.contains("unusual traffic", ignoreCase = true) &&
                    !pageText.contains("captcha", ignoreCase = true)) {
                "Bing blocked the request with a verification page"
            }

            val primary = doc.select("li.b_algo").mapNotNull(::parseBingResult)
            val fallback = if (primary.isEmpty()) parseFallbackLinks(doc.select("main h2 a, #b_results h2 a, h2 a")) else emptyList()
            val results = (primary + fallback)
                .filter { it.title.isNotBlank() && it.url.startsWith("http") }
                .distinctBy { it.url }
                .take(commonOptions.resultSize)

            require(results.isNotEmpty()) {
                "Search failed: no results found"
            }

            SearchResult(items = results)
        }
    }

    private fun parseBingResult(element: Element): SearchResultItem? {
        val anchor = element.selectFirst("h2 a") ?: return null
        val title = anchor.text().trim()
        val link = decodeBingUrl(anchor.attr("href")).trim()
        val snippet = element.select(".b_caption p, .b_snippet, p").firstOrNull()?.text().orEmpty().trim()
        return SearchResultItem(
            title = title,
            url = link,
            text = snippet
        )
    }

    private fun parseFallbackLinks(anchors: List<Element>): List<SearchResultItem> {
        return anchors.mapNotNull { anchor ->
            val title = anchor.text().trim()
            val link = decodeBingUrl(anchor.attr("href")).trim()
            if (title.isBlank() || !link.startsWith("http")) return@mapNotNull null
            SearchResultItem(
                title = title,
                url = link,
                text = anchor.parent()?.parent()?.text().orEmpty().take(500),
            )
        }
    }

    private fun decodeBingUrl(raw: String): String {
        return runCatching {
            val uri = URI(raw)
            val target = uri.rawQuery
                ?.split("&")
                ?.firstOrNull { it.substringBefore("=") in setOf("u", "url") }
                ?.substringAfter("=", "")
            if (target.isNullOrBlank()) raw else URLDecoder.decode(target, "UTF-8")
        }.getOrDefault(raw)
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BingLocalOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Bing"))
    }
}
