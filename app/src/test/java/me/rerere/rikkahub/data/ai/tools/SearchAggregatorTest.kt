package app.amber.core.ai.tools

import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAggregatorTest {
    @Test
    fun filtersEnabledServicesAndRequestedSubset() {
        val bing = SearchServiceOptions.BingLocalOptions()
        val tavily = SearchServiceOptions.TavilyOptions(apiKey = "key")
        val settings = Settings(
            searchServices = listOf(bing, tavily),
            searchEnabledServiceIds = listOf(tavily.id),
        )

        assertEquals(listOf(tavily), SearchAggregator.enabledServices(settings))
        assertEquals(listOf(tavily), SearchAggregator.enabledServices(settings, listOf("tavily")))
        assertEquals(emptyList<SearchServiceOptions>(), SearchAggregator.enabledServices(settings, listOf("bing")))
    }

    @Test
    fun keepsPartialResultsWhenOneServiceFails() = runBlocking {
        val bing = SearchServiceOptions.BingLocalOptions()
        val brave = SearchServiceOptions.BraveOptions(apiKey = "key")
        val settings = Settings(
            searchServices = listOf(bing, brave),
            searchEnabledServiceIds = listOf(bing.id, brave.id),
            searchCommonOptions = SearchCommonOptions(resultSize = 5),
        )

        val payload = SearchAggregator.search(
            settings = settings,
            params = buildJsonObject {
                put("query", "AI news")
                put("topic", "news")
                put("time_range", "day")
            },
            executor = { options, _, _ ->
                if (options.id == bing.id) {
                    Result.failure(IllegalStateException("bing failed"))
                } else {
                    Result.success(
                        SearchResult(
                            items = listOf(
                                SearchResult.SearchResultItem(
                                    title = "Fresh AI News",
                                    url = "https://example.com/ai",
                                    text = "AI update",
                                )
                            )
                        )
                    )
                }
            },
        )

        assertEquals("ok", payload["status"]!!.jsonPrimitive.content)
        assertEquals(1, payload["items"]!!.jsonArray.size)
        val sources = payload["sources"]!!.jsonArray
        assertTrue(sources.any { it.jsonObject["status"]!!.jsonPrimitive.content == "error" })
        assertTrue(sources.any { it.jsonObject["status"]!!.jsonPrimitive.content == "ok" })
    }

    @Test
    fun keepsPartialResultsWhenOneServiceThrows() = runBlocking {
        val bing = SearchServiceOptions.BingLocalOptions()
        val brave = SearchServiceOptions.BraveOptions(apiKey = "key")
        val settings = Settings(
            searchServices = listOf(bing, brave),
            searchEnabledServiceIds = listOf(bing.id, brave.id),
            searchCommonOptions = SearchCommonOptions(resultSize = 5),
        )

        val payload = SearchAggregator.search(
            settings = settings,
            params = buildJsonObject {
                put("query", "AI news")
                put("topic", "news")
                put("time_range", "day")
            },
            executor = { options, _, _ ->
                if (options.id == bing.id) {
                    error("bing threw")
                } else {
                    Result.success(
                        SearchResult(
                            items = listOf(
                                SearchResult.SearchResultItem(
                                    title = "Fresh AI News",
                                    url = "https://example.com/ai",
                                    text = "AI update",
                                )
                            )
                        )
                    )
                }
            },
        )

        assertEquals("ok", payload["status"]!!.jsonPrimitive.content)
        assertEquals(1, payload["items"]!!.jsonArray.size)
        val sources = payload["sources"]!!.jsonArray
        assertTrue(sources.any { it.jsonObject["status"]!!.jsonPrimitive.content == "error" })
        assertTrue(sources.any { it.jsonObject["status"]!!.jsonPrimitive.content == "ok" })
    }

    @Test
    fun deduplicatesCanonicalUrlsAcrossSources() = runBlocking {
        val bing = SearchServiceOptions.BingLocalOptions()
        val brave = SearchServiceOptions.BraveOptions(apiKey = "key")
        val settings = Settings(
            searchServices = listOf(bing, brave),
            searchEnabledServiceIds = listOf(bing.id, brave.id),
        )

        val payload = SearchAggregator.search(
            settings = settings,
            params = buildJsonObject {
                put("query", "same article")
                put("max_results", 10)
            },
            executor = { options, _, _ ->
                val url = if (options.id == bing.id) {
                    "https://www.example.com/news/article?utm_source=x"
                } else {
                    "https://example.com/news/article"
                }
                Result.success(
                    SearchResult(
                        items = listOf(
                            SearchResult.SearchResultItem(
                                title = "Same Article",
                                url = url,
                                text = "snippet",
                            )
                        )
                    )
                )
            },
        )

        val items = payload["items"]!!.jsonArray
        assertEquals(1, items.size)
        assertEquals("2", items.first().jsonObject["duplicate_count"]!!.jsonPrimitive.content)
    }

    @Test
    fun addsTimeWindowToServicesWithoutNativeTopic() {
        val params = SearchAggregator.buildServiceParams(
            query = "OpenAI release",
            topic = "news",
            timeRange = "day",
            recencyDays = null,
            options = SearchServiceOptions.BingLocalOptions(),
        )

        val query = params["query"]!!.jsonPrimitive.content
        assertTrue(query.contains("OpenAI release"))
        assertTrue(query.contains("news"))
        assertTrue(query.contains("today"))
    }

    @Test
    fun keepsNativeTopicForTavily() {
        val params = SearchAggregator.buildServiceParams(
            query = "OpenAI release",
            topic = "news",
            timeRange = "day",
            recencyDays = null,
            options = SearchServiceOptions.TavilyOptions(apiKey = "key"),
        )

        assertEquals("OpenAI release", params["query"]!!.jsonPrimitive.content)
        assertEquals("news", params["topic"]!!.jsonPrimitive.content)
    }
}
