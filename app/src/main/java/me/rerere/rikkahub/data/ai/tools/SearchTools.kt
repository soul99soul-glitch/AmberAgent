package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.search.ScrapedResult
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import java.time.LocalDate

fun createSearchTools(settings: Settings): Set<Tool> {
    return buildSet {
        val enabledServices = SearchAggregator.enabledServices(settings)
        val enabledServiceNames = enabledServices.joinToString { SearchServiceOptions.TYPES[it::class] ?: "Search" }
        add(
            Tool(
                name = "search_web",
                description = """
                    Search the web with all enabled search services, merge duplicated results, and report per-source status.
                    Use this when the user asks for the latest news, current facts, or needs verification.
                    Enabled services: ${enabledServiceNames.ifBlank { "none" }}.
                    For news/current events, set `topic=news` and choose `time_range` (`day` for today/latest, `week` for recent).
                    Generate focused keywords and run multiple searches when the topic is broad or likely to have gaps.
                    If snippets are not enough, call scrape_web on the most relevant source pages before answering.
                    Today is ${LocalDate.now().toLocalString(true)}.

                    Response format:
                    - items[].id (short id), title, url, text, source_service, source_services, duplicate_count
                    - sources[].service, status, result_count, error

                    Citations:
                    - After using results, add `[citation,domain](id)` after the sentence.
                    - Multiple citations are allowed.
                    - If no results are cited, omit citations.

                    Example:
                    The capital of France is Paris. [citation,example.com](abc123)
                    The population is about 2.1 million. [citation,example.com](abc123) [citation,example2.com](def456)
                    """.trimIndent(),
                parameters = {
                    searchWebParameters()
                },
                execute = {
                    val results = SearchAggregator.search(settings, it.jsonObject)
                    listOf(UIMessagePart.Text(results.toString()))
                }
            )
        )

        val scrapeServices = scrapeEnabledServices(settings)
        if (scrapeServices.isNotEmpty()) {
            add(
                Tool(
                    name = "scrape_web",
                    description = """
                        Scrape a URL for detailed page content.
                        Use this when the user requests content from a specific page or when search snippets are insufficient.
                        Avoid using it for common questions unless the user asks.
                        """.trimIndent(),
                    parameters = {
                        scrapeWebParameters()
                    },
                    execute = {
                        val options = resolveScrapeService(settings, it.jsonObject)
                        val service = SearchService.getService(options)
                        val result = service.scrape(
                            params = it.jsonObject,
                            commonOptions = settings.searchCommonOptions,
                            serviceOptions = options,
                        )
                        val payload = JsonInstantPretty.encodeToJsonElement(
                            ScrapedResult.serializer(),
                            result.getOrThrow(),
                        ).jsonObject
                        listOf(UIMessagePart.Text(payload.toString()))
                    }
                ))
        }
    }
}

private fun searchWebParameters(): InputSchema {
    return InputSchema.Obj(
        properties = buildJsonObject {
            put("query", buildJsonObject {
                put("type", "string")
                put("description", "search keyword")
            })
            put("topic", buildJsonObject {
                put("type", "string")
                put("description", "search topic")
                put("enum", buildJsonArray {
                    add("general")
                    add("news")
                    add("finance")
                })
            })
            put("time_range", buildJsonObject {
                put("type", "string")
                put("description", "recency window for current/news searches")
                put("enum", buildJsonArray {
                    add("day")
                    add("week")
                    add("month")
                    add("year")
                    add("any")
                })
            })
            put("recency_days", buildJsonObject {
                put("type", "integer")
                put("description", "optional exact recency window in days")
            })
            put("max_results", buildJsonObject {
                put("type", "integer")
                put("description", "maximum merged results to return")
            })
            put("services", buildJsonObject {
                put("type", "array")
                put("description", "optional enabled service names or ids to use")
                put("items", buildJsonObject {
                    put("type", "string")
                })
            })
        },
        required = listOf("query")
    )
}

private fun scrapeWebParameters(): InputSchema {
    return InputSchema.Obj(
        properties = buildJsonObject {
            put("url", buildJsonObject {
                put("type", "string")
                put("description", "url to scrape")
            })
            put("service", buildJsonObject {
                put("type", "string")
                put("description", "optional enabled service name or id that supports scraping")
            })
        },
        required = listOf("url")
    )
}

private fun scrapeEnabledServices(settings: Settings): List<SearchServiceOptions> {
    return SearchAggregator.enabledServices(settings)
        .filter { SearchService.getService(it).scrapingParameters != null }
}

private fun resolveScrapeService(settings: Settings, input: JsonObject): SearchServiceOptions {
    val requested = input["service"]?.jsonPrimitive?.contentOrNull
    val candidates = scrapeEnabledServices(settings)
    val selected = if (requested.isNullOrBlank()) {
        settings.searchServices.getOrNull(settings.searchServiceSelected)
            ?.takeIf { selected -> candidates.any { it.id == selected.id } }
            ?: candidates.firstOrNull()
    } else {
        SearchAggregator.enabledServices(settings, listOf(requested))
            .firstOrNull { SearchService.getService(it).scrapingParameters != null }
    }
    return selected ?: error("No enabled search service supports scraping. Enable a scraping-capable search service in settings.")
}
