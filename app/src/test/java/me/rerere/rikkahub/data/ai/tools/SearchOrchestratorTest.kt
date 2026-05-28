package app.amber.core.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.core.settings.Settings
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchResult
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchOrchestratorTest {
    @Test
    fun usesBuiltInSourcesWithoutConfiguredApiServices() {
        val settings = Settings(
            searchServices = emptyList(),
            searchEnabledServiceIds = emptyList(),
        )

        val sources = SearchOrchestrator.buildSources(settings)

        assertTrue(sources.any { it.id == "jina_builtin" })
        assertTrue(sources.any { it.id == "duckduckgo_builtin" })
        assertTrue(sources.any { it.id == "bing_builtin" })
        assertTrue(sources.any { it.id == "wikipedia_builtin" })
        assertTrue(sources.any { it.id == "hackernews_builtin" })
    }

    @Test
    fun keepsPartialResultWhenOnePublicSourceFails() = runBlocking {
        val settings = Settings(
            searchServices = emptyList(),
            searchEnabledServiceIds = emptyList(),
            searchCommonOptions = SearchCommonOptions(resultSize = 5),
        )

        val payload = SearchOrchestrator.search(
            settings = settings,
            params = buildJsonObject {
                put("query", "AI news")
                put("topic", "news")
                put("time_range", "day")
            },
            executor = { request, _ ->
                if (request.source.id == "duckduckgo_builtin") {
                    Result.failure(IllegalStateException("blocked by test"))
                } else {
                    success("Fresh AI News", "https://example.com/ai?utm_source=test")
                }
            },
        )

        assertEquals("partial", payload["status"]!!.jsonPrimitive.content)
        assertEquals(1, payload["items"]!!.jsonArray.size)
        val sources = payload["sources"]!!.jsonArray
        assertTrue(sources.any { it.jsonObject["status"]!!.jsonPrimitive.content == "error" })
        assertTrue(sources.any { it.jsonObject["status"]!!.jsonPrimitive.content == "ok" })
        val errorText = sources.joinToString()
        assertFalse(errorText.contains("IllegalStateException"))
    }

    @Test
    fun eachSourceReceivesPrimaryQueryBeforeVariantsConsumeBudget() = runBlocking {
        val configured = listOf(
            SearchServiceOptions.TavilyOptions(apiKey = "test"),
            SearchServiceOptions.BraveOptions(apiKey = "test"),
            SearchServiceOptions.ExaOptions(apiKey = "test"),
            SearchServiceOptions.SerperOptions(apiKey = "test"),
            SearchServiceOptions.SerpApiOptions(apiKey = "test"),
        )
        val settings = Settings(
            searchServices = configured,
            searchEnabledServiceIds = configured.map { it.id },
            searchBuiltinJinaEnabled = false,
            searchBuiltinDuckDuckGoEnabled = false,
            searchBuiltinBingEnabled = false,
            searchBuiltinWikipediaEnabled = false,
            searchBuiltinHackerNewsEnabled = false,
            searchCommonOptions = SearchCommonOptions(resultSize = 10),
        )
        val seen = mutableListOf<String>()

        val payload = SearchOrchestrator.search(
            settings = settings,
            params = buildJsonObject {
                put("query", "小米17销量 最新")
                put("topic", "market")
                put("depth", "deep")
            },
            executor = { request, _ ->
                seen += request.source.id
                success(
                    title = request.source.name,
                    url = "https://example.com/${request.source.id}/${request.variantIndex}",
                )
            },
        )

        configured.forEach { options ->
            assertTrue("source ${options.id} was not called", options.id.toString() in seen)
        }
        val sources = payload["sources"]!!.jsonArray
        assertEquals(5, sources.size)
        assertTrue(sources.all { it.jsonObject["called"]!!.jsonPrimitive.content == "true" })
        assertTrue(sources.all { it.jsonObject["variant_count"]!!.jsonPrimitive.content.toInt() >= 1 })
    }

    @Test
    fun verticalSourcesOnlyJoinApplicableQueries() {
        val settings = Settings(searchServices = emptyList(), searchEnabledServiceIds = emptyList())

        val technicalSources = SearchOrchestrator.buildSources(
            settings = settings,
            query = "Claude Code MCP 开源 项目",
            topic = "technical",
        )
        val entitySources = SearchOrchestrator.buildSources(
            settings = settings,
            query = "OpenAI 是什么",
            topic = "general",
        )
        val marketSources = SearchOrchestrator.buildSources(
            settings = settings,
            query = "小米17销量 最新",
            topic = "market",
        )

        assertTrue(technicalSources.any { it.id == "hackernews_builtin" })
        assertTrue(entitySources.any { it.id == "wikipedia_builtin" })
        assertFalse(marketSources.any { it.id == "hackernews_builtin" })
        assertFalse(marketSources.any { it.id == "wikipedia_builtin" })
    }

    @Test
    fun allOrdinarySourcesFailWithoutWebViewDoesNotThrow() = runBlocking {
        val settings = Settings(
            searchServices = emptyList(),
            searchEnabledServiceIds = emptyList(),
            searchGoogleWebViewFallbackEnabled = true,
        )

        val payload = SearchOrchestrator.search(
            settings = settings,
            params = buildJsonObject {
                put("query", "blocked search")
                put("allow_webview", false)
            },
            executor = { _, _ -> Result.failure(IllegalStateException("blocked")) },
        )

        assertEquals("empty", payload["status"]!!.jsonPrimitive.content)
        assertEquals(0, payload["items"]!!.jsonArray.size)
        assertTrue(payload["sources"] is JsonArray)
    }

    @Test
    fun allowWebViewAddsFallbackSuggestions() = runBlocking {
        val settings = Settings(
            searchServices = emptyList(),
            searchEnabledServiceIds = emptyList(),
            searchGoogleWebViewFallbackEnabled = true,
        )

        val payload = SearchOrchestrator.search(
            settings = settings,
            params = buildJsonObject {
                put("query", "OpenAI news")
                put("allow_webview", true)
            },
            executor = { _, _ -> Result.failure(IllegalStateException("blocked")) },
        )

        val fallback = payload["webview_fallback"]!!.jsonObject
        val suggestions = fallback["suggestions"]!!.jsonArray
        assertTrue(suggestions.any { it.jsonObject["source_service"]!!.jsonPrimitive.content == "google_webview" })
    }

    @Test
    fun buildsNewsAndMarketQueryVariants() {
        val news = SearchOrchestrator.buildQueryVariants(
            query = "OpenAI release",
            topic = "news",
            timeRange = "day",
            recencyDays = null,
            depth = "standard",
        )
        val market = SearchOrchestrator.buildQueryVariants(
            query = "小米17销量 最新",
            topic = "market",
            timeRange = "any",
            recencyDays = null,
            depth = "standard",
        )

        assertTrue(news.any { it.contains("today") && it.contains("news") })
        assertTrue(market.any { it.contains("market share") && it.contains("Canalys") })
    }

    @Test
    fun canonicalUrlDropsTrackingParams() {
        val canonical = SearchOrchestrator.canonicalizeUrl(
            "https://www.example.com/path/?utm_source=x&gclid=y&a=1"
        )

        assertEquals("https://example.com/path?a=1", canonical)
    }

    private fun success(title: String, url: String): Result<SearchResult> {
        return Result.success(
            SearchResult(
                items = listOf(
                    SearchResult.SearchResultItem(
                        title = title,
                        url = url,
                        text = "snippet",
                    )
                )
            )
        )
    }
}
