package app.amber.core.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.Settings
import app.amber.core.utils.JsonInstantPretty
import app.amber.core.utils.toLocalString
import app.amber.search.JinaSearchService
import app.amber.search.ScrapedResult
import app.amber.search.SearchService
import app.amber.search.SearchServiceOptions
import java.time.LocalDate
import java.util.Locale

fun createSearchTools(
    settings: Settings,
    includeWebViewFallbackGuidance: Boolean = true,
    locale: Locale = Locale.getDefault(),
): Set<Tool> {
    return buildSet {
        val enabledServices = SearchAggregator.enabledServices(settings)
        val enabledServiceNames = enabledServices.joinToString { SearchServiceOptions.TYPES[it::class] ?: "Search" }
        val builtinStatus = listOfNotNull(
            "Jina Search/Reader".takeIf { settings.searchBuiltinJinaEnabled },
            "DuckDuckGo".takeIf { settings.searchBuiltinDuckDuckGoEnabled },
            "Bing".takeIf { settings.searchBuiltinBingEnabled },
            "Wikipedia".takeIf { settings.searchBuiltinWikipediaEnabled },
            "Hacker News".takeIf { settings.searchBuiltinHackerNewsEnabled },
            "Google WebView fallback".takeIf { includeWebViewFallbackGuidance && settings.searchGoogleWebViewFallbackEnabled },
        ).joinToString().ifBlank { "none" }
        val webViewFallbackGuidance = if (includeWebViewFallbackGuidance) {
            "If ordinary sources are blocked or weak, set `allow_webview=true` or call webview_search_open using webview_fallback suggestions."
        } else {
            "WebView fallback tools are not available in this tool set; do not call webview_* tools from search results."
        }
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
                    Do not pass `services` by default. Only pass it when the user explicitly requests a source or after search_sources_status returns a matching id/name/accepted_selectors value.
                    If snippets are not enough, call scrape_web on the most relevant source pages before answering.
                    $webViewFallbackGuidance
                    Today is ${LocalDate.now().toLocalString(true, locale)}.

                    Response format:
                    - items[].id (short id), title, url, text, source_service, source_services, duplicate_count
                    - items[].images[] (optional): relevant image URLs from the search results
                    - sources[].service, service_id, accepted_selectors, status, result_count, error
                    - If status=error with available_sources, retry once without services or with one exact selector from available_sources.

                    Citations:
                    - Prefer natural Markdown source links, e.g. `[Reuters](https://www.reuters.com/...)`, after the sentence.
                    - Multiple source links are allowed.
                    - Legacy `[citation,domain](id)` citations are still accepted for compatibility.
                    - If no results are cited, omit source links.

                    IMPORTANT — Images:
                    Do not embed images with Markdown image syntax like `![](url)`.
                    Do not write internal image-rendering fences or code blocks.
                    If items include images, AmberAgent handles the visual rendering separately.
                    Your job is to write the answer text and attach source links for the sources you used.

                    Example:
                    The capital of France is Paris. [example.com](https://example.com/paris)
                    The population is about 2.1 million. [example.com](https://example.com/paris) [example2.com](https://example2.com/france)
                    """.trimIndent(),
                parameters = {
                    searchWebParameters(includeWebViewFallbackGuidance)
                },
                execute = {
                    val results = SearchOrchestrator.search(
                        settings = settings,
                        params = it.jsonObject,
                        includeWebViewFallback = includeWebViewFallbackGuidance,
                        locale = locale,
                    )
                    listOf(UIMessagePart.Text(results.toString()))
                }
            )
        )

        add(
            Tool(
                name = "search_sources_status",
                description = "Return enabled Search Orchestrator sources, including configured API sources, built-in free public sources, accepted selector strings for search_web services, and WebView fallback status.",
                parameters = {
                    InputSchema.Obj(properties = buildJsonObject { })
                },
                execute = {
                    listOf(
                        UIMessagePart.Text(
                            SearchOrchestrator.status(
                                settings = settings,
                                includeWebViewFallback = includeWebViewFallbackGuidance,
                            ).toString()
                        )
                    )
                }
            )
        )

        add(
            Tool(
                name = "search_strategy_explain",
                description = "Explain how search_web would rewrite this query, choose sources, and decide whether WebView fallback is available. It does not perform a search.",
                parameters = {
                    searchWebParameters(includeWebViewFallbackGuidance)
                },
                execute = {
                    listOf(
                        UIMessagePart.Text(
                            SearchOrchestrator.explain(
                                settings = settings,
                                params = it.jsonObject,
                                includeWebViewFallback = includeWebViewFallbackGuidance,
                                locale = locale,
                            ).toString()
                        )
                    )
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

private fun searchWebParameters(includeWebViewFallbackGuidance: Boolean): InputSchema {
    val depthDescription = if (includeWebViewFallbackGuidance) {
        "search depth: quick uses fewer variants, standard rewrites queries, deep adds more variants and WebView fallback hints"
    } else {
        "search depth: quick uses fewer variants, standard rewrites queries, deep adds more variants"
    }
    val allowWebViewDescription = if (includeWebViewFallbackGuidance) {
        "whether to return WebView search fallback suggestions when ordinary sources are weak"
    } else {
        "ignored in this tool set because WebView fallback tools are unavailable; leave false"
    }
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
                put("description", depthDescription)
                put("enum", buildJsonArray {
                    add("quick")
                    add("standard")
                    add("deep")
                })
            })
            put("allow_webview", buildJsonObject {
                put("type", "boolean")
                put("description", allowWebViewDescription)
            })
            put("services", buildJsonObject {
                put("type", "array")
                put("description", "strict optional filter. Omit by default. Values must be exact id/name/accepted_selectors from search_sources_status or available_sources.")
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
