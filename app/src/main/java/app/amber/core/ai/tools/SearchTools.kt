package app.amber.core.ai.tools

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
import app.amber.core.settings.Settings
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.search.JinaSearchService
import me.rerere.search.ScrapedResult
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import java.time.LocalDate

fun createSearchTools(settings: Settings): Set<Tool> {
    return buildSet {
        val enabledServices = SearchAggregator.enabledServices(settings)
        val enabledServiceNames = enabledServices.joinToString { SearchServiceOptions.TYPES[it::class] ?: "Search" }
        val builtinStatus = listOfNotNull(
            "Jina Search/Reader".takeIf { settings.searchBuiltinJinaEnabled },
            "DuckDuckGo".takeIf { settings.searchBuiltinDuckDuckGoEnabled },
            "Bing".takeIf { settings.searchBuiltinBingEnabled },
            "Wikipedia".takeIf { settings.searchBuiltinWikipediaEnabled },
            "Hacker News".takeIf { settings.searchBuiltinHackerNewsEnabled },
            "Google WebView fallback".takeIf { settings.searchGoogleWebViewFallbackEnabled },
        ).joinToString().ifBlank { "none" }
        add(
            Tool(
                name = "search_web",
                description = """
                    Search the web through AmberAgent Search Orchestrator.
                    It uses enabled API services first, then built-in public/vertical sources such as Jina, DuckDuckGo, Bing HTML fallback, Wikipedia, and Hacker News as fallback/cross-check.
                    Use this when the user asks for the latest news, current facts, or needs verification.
                    Enabled configured services: ${enabledServiceNames.ifBlank { "none" }}.
                    Built-in sources: $builtinStatus.
                    For news/current events, set `topic=news` and choose `time_range` (`day` for today/latest, `week` for recent).
                    For market/sales/share questions, set `topic=market`; the orchestrator will generate English market-data variants.
                    Generate focused keywords and run multiple searches when the topic is broad or likely to have gaps.
                    If snippets are not enough, call scrape_web on the most relevant source pages before answering.
                    If ordinary sources are blocked or weak, set `allow_webview=true` or call webview_search_open using webview_fallback suggestions.
                    Today is ${LocalDate.now().toLocalString(true)}.

                    Response format:
                    - items[].id (short id), title, url, text, source_service, source_services, duplicate_count
                    - items[].images[] (optional): relevant image URLs from the search results
                    - sources[].service, status, result_count, error

                    Citations:
                    - After using results, add `[citation,domain](id)` after the sentence.
                    - Multiple citations are allowed.
                    - If no results are cited, omit citations.

                    IMPORTANT — Images:
                    When the search result JSON contains `available_images` and `image_instruction`, you MUST embed those images in your response using Markdown image syntax.
                    Follow the `image_instruction` field exactly. The typical format is:
                    1. A thumbnail row at the top: ![](url1) ![](url2) on one line
                    2. Full images inline after relevant paragraphs: ![brief description](url)
                    Do NOT ignore available images. Do NOT describe images in text instead of embedding them.
                    Maximum 5 images. If no `available_images` field exists, skip images entirely.

                    Example:
                    The capital of France is Paris. [citation,example.com](abc123)
                    The population is about 2.1 million. [citation,example.com](abc123) [citation,example2.com](def456)
                    """.trimIndent(),
                parameters = {
                    searchWebParameters()
                },
                execute = {
                    val results = SearchOrchestrator.search(settings, it.jsonObject)
                    listOf(UIMessagePart.Text(results.toString()))
                }
            )
        )

        add(
            Tool(
                name = "search_sources_status",
                description = "Return enabled Search Orchestrator sources, including configured API sources, built-in free public sources, and WebView fallback status.",
                parameters = {
                    InputSchema.Obj(properties = buildJsonObject { })
                },
                execute = {
                    listOf(UIMessagePart.Text(SearchOrchestrator.status(settings).toString()))
                }
            )
        )

        add(
            Tool(
                name = "search_strategy_explain",
                description = "Explain how search_web would rewrite this query, choose sources, and decide whether WebView fallback is available. It does not perform a search.",
                parameters = {
                    searchWebParameters()
                },
                execute = {
                    listOf(UIMessagePart.Text(SearchOrchestrator.explain(settings, it.jsonObject).toString()))
                }
            )
        )

        val scrapeServices = scrapeEnabledServices(settings)
        if (scrapeServices.isNotEmpty() || settings.searchBuiltinJinaEnabled) {
            add(
                Tool(
                    name = "scrape_web",
                    description = """
                        Scrape a URL for detailed page content.
                        Built-in Jina Reader is available without an API key and is preferred when no configured scraping service is selected.
                        Use this when the user requests content from a specific page or when search snippets are insufficient.
                        Avoid using it for common questions unless the user asks.
                        """.trimIndent(),
                    parameters = {
                        scrapeWebParameters()
                    },
                    execute = {
                        val options = resolveScrapeService(settings, it.jsonObject)
                        val result = if (options is SearchServiceOptions.JinaOptions) {
                            JinaSearchService.scrape(
                                params = it.jsonObject,
                                commonOptions = settings.searchCommonOptions,
                                serviceOptions = options,
                            )
                        } else {
                            val service = SearchService.getService(options)
                            service.scrape(
                                params = it.jsonObject,
                                commonOptions = settings.searchCommonOptions,
                                serviceOptions = options,
                            )
                        }
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
                    add("market")
                    add("technical")
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
            put("depth", buildJsonObject {
                put("type", "string")
                put("description", "search depth: quick uses fewer variants, standard rewrites queries, deep adds more variants and WebView fallback hints")
                put("enum", buildJsonArray {
                    add("quick")
                    add("standard")
                    add("deep")
                })
            })
            put("allow_webview", buildJsonObject {
                put("type", "boolean")
                put("description", "whether to return WebView search fallback suggestions when ordinary sources are weak")
            })
            put("services", buildJsonObject {
                put("type", "array")
                put("description", "optional enabled service names or ids to use")
                put("items", buildJsonObject {
                    put("type", "string")
                })
            })
            put("preferred_sources", buildJsonObject {
                put("type", "array")
                put("description", "optional source names or ids to prefer; alias of services")
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
            ?: SearchServiceOptions.JinaOptions().takeIf { settings.searchBuiltinJinaEnabled }
    } else {
        SearchAggregator.enabledServices(settings, listOf(requested))
            .firstOrNull { SearchService.getService(it).scrapingParameters != null }
            ?: SearchServiceOptions.JinaOptions().takeIf {
                settings.searchBuiltinJinaEnabled && listOf("jina", "jina reader", "jina_reader", "jina builtin")
                    .any { selector -> requested.lowercase().contains(selector) }
            }
    }
    return selected ?: error("No enabled search service supports scraping. Enable Jina Reader or another scraping-capable search service in settings.")
}
