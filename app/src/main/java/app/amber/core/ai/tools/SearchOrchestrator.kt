package app.amber.core.ai.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.core.settings.Settings
import app.amber.core.utils.toLocalString
import app.amber.search.BingSearchService
import app.amber.search.DuckDuckGoSearchService
import app.amber.search.HackerNewsSearchService
import app.amber.search.JinaSearchService
import app.amber.search.SearchCommonOptions
import app.amber.search.SearchResult
import app.amber.search.SearchService
import app.amber.search.SearchServiceOptions
import app.amber.search.WikipediaSearchService
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Locale
import kotlin.math.max
import kotlin.uuid.Uuid

internal object SearchOrchestrator {
    private const val MAX_SOURCES = 5
    private const val MAX_SOURCE_VARIANT_CALLS = 10
    private val allowedTopics = setOf("general", "news", "market", "technical", "finance")
    private val allowedTimeRanges = setOf("day", "week", "month", "year", "any")
    private val allowedDepths = setOf("quick", "standard", "deep")

    suspend fun search(
        settings: Settings,
        params: JsonObject,
        executor: OrchestratorSearchExecutor = ::executeSourceSearch,
    ): JsonObject = coroutineScope {
        val query = params.string("query") ?: params.string("q") ?: error("query is required")
        val topic = params.string("topic")?.lowercase(Locale.ROOT)?.takeIf { it in allowedTopics } ?: "general"
        val depth = params.string("depth")?.lowercase(Locale.ROOT)?.takeIf { it in allowedDepths } ?: "standard"
        val explicitTimeRange = params.string("time_range")?.lowercase(Locale.ROOT)?.takeIf { it in allowedTimeRanges }
        val timeRange = explicitTimeRange ?: if (topic == "news") "week" else "any"
        val recencyDays = params.int("recency_days")?.coerceIn(1, 366)
        val maxResults = params.int("max_results")
            ?.coerceIn(1, 40)
            ?: settings.searchCommonOptions.resultSize.coerceIn(1, 40)
        val allowWebView = params.boolean("allow_webview") ?: false
        val requestedServices = params.serviceSelectors()
        val sources = buildSources(settings, requestedServices, query = query, topic = topic).take(MAX_SOURCES)

        if (sources.isEmpty()) {
            return@coroutineScope buildJsonObject {
                put("status", "error")
                put("query", query)
                put("error", "No search sources are enabled. Enable built-in free sources or at least one configured search service.")
                put("items", JsonArray(emptyList()))
                put("sources", JsonArray(emptyList()))
            }
        }

        val variants = buildQueryVariants(
            query = query,
            topic = topic,
            timeRange = timeRange,
            recencyDays = recencyDays,
            depth = depth,
        )
        val perCallSize = max(4, ((maxResults + sources.size - 1) / sources.size) + 3)
            .coerceIn(4, 20)
        val calls = buildSourceCalls(
            sources = sources,
            variants = variants,
            originalQuery = query,
            topic = topic,
            timeRange = timeRange,
            recencyDays = recencyDays,
            maxResults = perCallSize,
        )

        val sourceResults = calls.map { request ->
            async {
                val result = runCatching {
                    executor(request, settings.searchCommonOptions.copy(resultSize = request.maxResults))
                }.getOrElse { Result.failure(it) }
                SourceSearchResult(request = request, result = result)
            }
        }.map { it.await() }

        val merged = mergeResults(
            results = sourceResults,
            originalQuery = query,
            topic = topic,
            timeRange = timeRange,
        ).take(maxResults)
        val anyFailure = sourceResults.any { it.result.isFailure }
        val shouldOfferWebView = settings.searchGoogleWebViewFallbackEnabled &&
            (allowWebView || depth == "deep" || merged.size < maxResults.coerceAtMost(3))

        buildJsonObject {
            put(
                "status",
                when {
                    merged.isNotEmpty() && anyFailure -> "partial"
                    merged.isNotEmpty() -> "ok"
                    sourceResults.isNotEmpty() && anyFailure -> "empty"
                    else -> "empty"
                }
            )
            put("query", query)
            put("topic", topic)
            put("time_range", timeRange)
            put("depth", depth)
            recencyDays?.let { put("recency_days", it) }
            // Per-item image budget capped globally to 5 across the whole response so
            // we don't bloat the JSON for the LLM with dozens of CDN URLs. We compute
            // `toEmit` first (= min of what the item actually has after de-dup and
            // what's left in the global budget), pass it to toJson as the cap, then
            // decrement the budget by exactly that. Previous impl computed `emitted`
            // off the un-budgeted distinct list, which made the budget functionally
            // useless after item 1 burned through it.
            var imagesBudget = 5
            put("items", buildJsonArray {
                merged.forEachIndexed { index, item ->
                    val available = item.images.distinct().size
                    val toEmit = minOf(available, imagesBudget).coerceAtLeast(0)
                    add(item.toJson(index + 1, maxImages = toEmit))
                    imagesBudget = (imagesBudget - toEmit).coerceAtLeast(0)
                }
            })
            // SearchImageInjectorTransformer reads items[].images downstream and lays
            // out the images itself in fenced `search-images` blocks. We tell the LLM
            // explicitly NOT to embed images via `![](url)` Markdown — earlier we
            // instructed the opposite ("please embed these images") and the model
            // dutifully obeyed, producing a layer of raw `![]()` images that the
            // standard markdown renderer can't normalise (inconsistent sizes, large
            // grey placeholder rectangles on load failure). The transformer now owns
            // image layout end-to-end; the LLM only writes prose + citations.
            val allImages = merged.flatMap { it.images }.distinct().take(5)
            put("total_images", allImages.size)
            if (allImages.isNotEmpty()) {
                put(
                    "image_instruction",
                    "搜索结果包含 ${allImages.size} 张相关图片，AmberAgent 已经自动" +
                        "把它们渲染在回复里。请**不要**在你的回复正文中再用 ![](url) " +
                        "Markdown 语法插入这些图片；只需要写好文字内容并附上 " +
                        "[citation,domain](id) 引用即可，AmberAgent 会按引用自动" +
                        "把对应图片插到段落附近。",
                )
            }
            put("sources", sourceStatusJson(sourceResults = sourceResults, sources = sources, calls = calls))
            put("query_variants", buildJsonArray {
                variants.forEach { add(JsonPrimitive(it)) }
            })
            if (shouldOfferWebView) {
                put("webview_fallback", webViewFallback(query))
            }
            if (merged.isEmpty()) {
                put(
                    "message",
                    if (shouldOfferWebView) {
                        "No ordinary source produced results. Use webview_search_open with the suggested fallback URL, or enable more search services."
                    } else {
                        "No source produced parseable results. Enable Google WebView fallback or another search service."
                    }
                )
            }
        }
    }

    fun status(settings: Settings): JsonObject {
        val enabledConfigured = SearchAggregator.enabledServices(settings)
        return buildJsonObject {
            put("enabled", settings.enableWebSearch)
            put("configured_service_count", settings.searchServices.size)
            put("enabled_configured_service_count", enabledConfigured.size)
            put("builtin_duckduckgo_enabled", settings.searchBuiltinDuckDuckGoEnabled)
            put("builtin_bing_enabled", settings.searchBuiltinBingEnabled)
            put("google_webview_fallback_enabled", settings.searchGoogleWebViewFallbackEnabled)
            put("sources", buildJsonArray {
                buildSources(settings).forEach { source ->
                    add(
                        buildJsonObject {
                            put("id", source.id)
                            put("name", source.name)
                            put("kind", source.kind)
                            put("builtin", source.builtin)
                            put("priority", source.priority)
                            source.apiKeyConfigured()?.let { configured ->
                                put("api_key_configured", configured)
                            } ?: put("api_key_configured", JsonNull)
                        }
                    )
                }
            })
        }
    }

    fun explain(settings: Settings, params: JsonObject): JsonObject {
        val query = params.string("query") ?: params.string("q") ?: ""
        val topic = params.string("topic")?.lowercase(Locale.ROOT)?.takeIf { it in allowedTopics } ?: "general"
        val depth = params.string("depth")?.lowercase(Locale.ROOT)?.takeIf { it in allowedDepths } ?: "standard"
        val explicitTimeRange = params.string("time_range")?.lowercase(Locale.ROOT)?.takeIf { it in allowedTimeRanges }
        val timeRange = explicitTimeRange ?: if (topic == "news") "week" else "any"
        val recencyDays = params.int("recency_days")?.coerceIn(1, 366)
        val allowWebView = params.boolean("allow_webview") ?: false
        val requestedServices = params.serviceSelectors()
        val sources = buildSources(settings, requestedServices, query = query, topic = topic).take(MAX_SOURCES)
        val variants = if (query.isBlank()) emptyList() else buildQueryVariants(query, topic, timeRange, recencyDays, depth)
        return buildJsonObject {
            put("query", query)
            put("topic", topic)
            put("time_range", timeRange)
            put("depth", depth)
            put("source_count", sources.size)
            put("sources", buildJsonArray {
                sources.forEach { source ->
                    add(
                        buildJsonObject {
                            put("id", source.id)
                            put("name", source.name)
                            put("kind", source.kind)
                            put("builtin", source.builtin)
                            put("reason", source.reason)
                        }
                    )
                }
            })
            put("query_variants", buildJsonArray {
                variants.forEach { add(JsonPrimitive(it)) }
            })
            put("webview_fallback_would_be_available", settings.searchGoogleWebViewFallbackEnabled && (allowWebView || depth == "deep"))
        }
    }

    internal fun buildSources(
        settings: Settings,
        requestedServices: List<String> = emptyList(),
        query: String? = null,
        topic: String = "general",
    ): List<OrchestratorSource> {
        val requested = requestedServices.map { it.lowercase(Locale.ROOT).trim() }.filter { it.isNotBlank() }
        fun allowed(id: String, name: String): Boolean {
            if (requested.isEmpty()) return true
            val lowerName = name.lowercase(Locale.ROOT)
            return requested.any { selector ->
                id.lowercase(Locale.ROOT).startsWith(selector) ||
                    lowerName == selector ||
                    lowerName.contains(selector)
            }
        }

        val configured = SearchAggregator.enabledServices(settings, requestedServices)
            .mapIndexed { index, options ->
                OrchestratorSource.Configured(
                    options = options,
                    priority = index,
                )
            }
        val hasConfiguredBing = configured.any { it.options is SearchServiceOptions.BingLocalOptions }
        val builtins = buildList {
            if (settings.searchBuiltinJinaEnabled && allowed("jina_builtin", "Jina")) {
                add(OrchestratorSource.BuiltInJinaSearch)
            }
            if (settings.searchBuiltinDuckDuckGoEnabled && allowed("duckduckgo_builtin", "DuckDuckGo")) {
                add(OrchestratorSource.BuiltInDuckDuckGo)
            }
            if (settings.searchBuiltinBingEnabled && !hasConfiguredBing && allowed("bing_builtin", "Bing")) {
                add(OrchestratorSource.BuiltInBing)
            }
            if (settings.searchBuiltinWikipediaEnabled && allowed("wikipedia_builtin", "Wikipedia")) {
                add(OrchestratorSource.BuiltInWikipedia)
            }
            if (settings.searchBuiltinHackerNewsEnabled && allowed("hackernews_builtin", "Hacker News")) {
                add(OrchestratorSource.BuiltInHackerNews)
            }
        }
        return (configured + builtins)
            .filter { source -> source.applicableFor(query = query, topic = topic) }
            .sortedBy { it.priority }
    }

    private fun buildSourceCalls(
        sources: List<OrchestratorSource>,
        variants: List<String>,
        originalQuery: String,
        topic: String,
        timeRange: String,
        recencyDays: Int?,
        maxResults: Int,
    ): List<SourceSearchRequest> {
        if (sources.isEmpty() || variants.isEmpty()) return emptyList()
        val calls = mutableListOf<SourceSearchRequest>()
        sources.forEachIndexed { sourceIndex, source ->
            calls += SourceSearchRequest(
                source = source,
                sourceIndex = sourceIndex,
                variantIndex = 0,
                query = variants.first(),
                originalQuery = originalQuery,
                topic = topic,
                timeRange = timeRange,
                recencyDays = recencyDays,
                maxResults = maxResults,
            )
        }
        var variantIndex = 1
        while (calls.size < MAX_SOURCE_VARIANT_CALLS && variantIndex < variants.size) {
            sources.forEachIndexed { sourceIndex, source ->
                if (calls.size >= MAX_SOURCE_VARIANT_CALLS) return@forEachIndexed
                calls += SourceSearchRequest(
                    source = source,
                    sourceIndex = sourceIndex,
                    variantIndex = variantIndex,
                    query = variants[variantIndex],
                    originalQuery = originalQuery,
                    topic = topic,
                    timeRange = timeRange,
                    recencyDays = recencyDays,
                    maxResults = maxResults,
                )
            }
            variantIndex++
        }
        return calls
    }

    internal fun buildQueryVariants(
        query: String,
        topic: String,
        timeRange: String,
        recencyDays: Int?,
        depth: String,
    ): List<String> {
        val today = LocalDate.now().toLocalString(true)
        val variants = linkedSetOf(query.trim())
        val timeText = when {
            recencyDays != null -> "last $recencyDays days"
            timeRange == "day" -> "today $today"
            timeRange == "week" -> "this week $today"
            timeRange == "month" -> "this month $today"
            timeRange == "year" -> "this year $today"
            else -> null
        }
        if (topic == "news" || timeRange != "any") {
            variants.add(listOfNotNull(query, "news", timeText).joinToString(" "))
        }
        if (topic == "market" || looksLikeMarketQuery(query)) {
            variants.add("$query market share shipment sales Counterpoint Canalys IDC")
        }
        if (topic == "technical") {
            variants.add("$query documentation GitHub issue release")
        }
        if (topic == "finance") {
            variants.add("$query finance stock market latest")
        }
        if (depth == "deep") {
            variants.add("$query site:reuters.com OR site:apnews.com OR site:bloomberg.com OR site:canalys.com OR site:idc.com")
        }
        val limit = when (depth) {
            "quick" -> 1
            "deep" -> 4
            else -> 3
        }
        return variants.filter { it.isNotBlank() }.take(limit)
    }

    private fun looksLikeMarketQuery(query: String): Boolean {
        val lower = query.lowercase(Locale.ROOT)
        return listOf("销量", "出货", "市场份额", "份额", "sales", "shipment", "market share").any {
            lower.contains(it)
        }
    }

    private suspend fun executeSourceSearch(
        request: SourceSearchRequest,
        commonOptions: SearchCommonOptions,
    ): Result<SearchResult> {
        return when (val source = request.source) {
            OrchestratorSource.BuiltInJinaSearch -> {
                JinaSearchService.search(
                    params = request.serviceParams(),
                    commonOptions = commonOptions,
                    serviceOptions = SearchServiceOptions.JinaOptions(),
                )
            }
            OrchestratorSource.BuiltInDuckDuckGo -> DuckDuckGoSearchService.search(request.query, commonOptions)
            OrchestratorSource.BuiltInBing -> {
                BingSearchService.search(
                    params = request.serviceParams(),
                    commonOptions = commonOptions,
                    serviceOptions = SearchServiceOptions.BingLocalOptions(),
                )
            }
            OrchestratorSource.BuiltInWikipedia -> WikipediaSearchService.search(request.query, commonOptions)
            OrchestratorSource.BuiltInHackerNews -> HackerNewsSearchService.search(request.query, commonOptions)

            is OrchestratorSource.Configured -> {
                val service = SearchService.getService(source.options)
                service.search(
                    params = request.serviceParams(),
                    commonOptions = commonOptions,
                    serviceOptions = source.options,
                )
            }
        }
    }

    private fun SourceSearchRequest.serviceParams(): JsonObject {
        return when (source) {
            OrchestratorSource.BuiltInJinaSearch,
            OrchestratorSource.BuiltInDuckDuckGo,
            OrchestratorSource.BuiltInBing -> buildJsonObject {
                put("query", query)
            }

            OrchestratorSource.BuiltInWikipedia,
            OrchestratorSource.BuiltInHackerNews -> buildJsonObject {
                put("query", query)
            }

            is OrchestratorSource.Configured -> SearchAggregator.buildServiceParams(
                query = query,
                topic = providerTopic(topic),
                timeRange = timeRange,
                recencyDays = recencyDays,
                options = source.options,
            )
        }
    }

    private fun providerTopic(topic: String): String {
        return when (topic) {
            "news" -> "news"
            "finance" -> "finance"
            else -> "general"
        }
    }

    private fun mergeResults(
        results: List<SourceSearchResult>,
        originalQuery: String,
        topic: String,
        timeRange: String,
    ): List<OrchestratedSearchItem> {
        val byKey = LinkedHashMap<String, OrchestratedSearchItem>()
        val byTitle = LinkedHashMap<String, OrchestratedSearchItem>()
        val queryTokens = originalQuery.lowercase(Locale.ROOT)
            .split(Regex("[\\s/,_，。:：\\-]+"))
            .filter { it.length >= 2 }
            .take(10)

        results.forEach { sourceResult ->
            val result = sourceResult.result.getOrNull() ?: return@forEach
            result.items.forEachIndexed { rank, item ->
                val canonical = canonicalizeUrl(item.url)
                val domain = domain(item.url)
                val titleKey = domain + "|" + normalizedTitle(item.title)
                val existing = byKey[canonical] ?: byTitle[titleKey]
                if (existing == null) {
                    val hitBoost = queryTokens.count { token ->
                        item.title.contains(token, ignoreCase = true) ||
                            item.text.contains(token, ignoreCase = true)
                    } * 3
                    val freshnessScore = freshnessScore(item.publishedAt, topic, timeRange)
                    val score = (60 - sourceResult.request.sourceIndex * 5 - sourceResult.request.variantIndex * 3 - rank) +
                        hitBoost +
                        freshnessScore
                    val created = OrchestratedSearchItem(
                        title = item.title,
                        url = item.url,
                        text = item.text,
                        domain = domain,
                        sourceService = sourceResult.request.source.name,
                        sourceServices = linkedSetOf(sourceResult.request.source.name),
                        sourceRank = rank + 1,
                        duplicateCount = 1,
                        score = score,
                        publishedAt = item.publishedAt,
                        freshnessScore = freshnessScore,
                        // Carry per-item image URLs from the search service (Brave et al.)
                        // through the merge layer so the downstream
                        // SearchImageInjectorTransformer can match them to citations.
                        // Dedup at creation too (some services emit the same URL as
                        // thumbnail.src and thumbnail.original).
                        images = item.images.distinct().take(5),
                    )
                    byKey[canonical] = created
                    byTitle[titleKey] = created
                } else {
                    existing.sourceServices.add(sourceResult.request.source.name)
                    existing.duplicateCount += 1
                    existing.score += 12
                    if (item.text.length > existing.text.length) {
                        existing.text = item.text
                    }
                    if (existing.publishedAt == null && item.publishedAt != null) {
                        existing.publishedAt = item.publishedAt
                        existing.freshnessScore = freshnessScore(item.publishedAt, topic, timeRange)
                    }
                    // Merge images from duplicate sources up to the cap, dedup-by-URL.
                    if (item.images.isNotEmpty() && existing.images.size < 5) {
                        existing.images = (existing.images + item.images).distinct().take(5)
                    }
                }
            }
        }

        return byKey.values.sortedWith(
            compareByDescending<OrchestratedSearchItem> { it.score }
                .thenByDescending { it.duplicateCount }
                .thenByDescending { it.freshnessScore }
                .thenBy { it.sourceRank }
        )
    }

    internal fun canonicalizeUrl(url: String): String {
        return runCatching {
            val uri = URI(url.trim())
            val scheme = (uri.scheme ?: "https").lowercase(Locale.ROOT)
            val host = (uri.host ?: "").lowercase(Locale.ROOT).removePrefix("www.")
            val path = (uri.rawPath ?: "").trimEnd('/').ifBlank { "/" }
            val query = uri.rawQuery
                ?.split("&")
                ?.filter { part ->
                    val key = part.substringBefore("=").lowercase(Locale.ROOT)
                    key !in setOf("fbclid", "gclid", "igshid", "mc_cid", "mc_eid", "yclid") &&
                        !key.startsWith("utm_")
                }
                ?.sorted()
                ?.joinToString("&")
                ?.takeIf { it.isNotBlank() }
            buildString {
                append(scheme).append("://").append(host).append(path)
                if (query != null) append('?').append(query)
            }
        }.getOrElse {
            url.trim().lowercase(Locale.ROOT)
        }
    }

    internal fun normalizedTitle(title: String): String {
        return title.lowercase(Locale.ROOT)
            .replace(Regex("[\\p{Punct}\\s]+"), "")
            .take(80)
    }

    private fun freshnessScore(publishedAt: String?, topic: String, timeRange: String): Int {
        if (topic != "news" || timeRange == "any") return 0
        return if (publishedAt.isNullOrBlank()) 0 else 12
    }

    private fun domain(url: String): String {
        return runCatching {
            URI(url).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        }.getOrDefault("")
    }

    private fun webViewFallback(query: String): JsonObject {
        return buildJsonObject {
            put("status", "available")
            put("note", "Use webview_search_open to open a visible search results page when ordinary sources are weak or blocked.")
            put("suggestions", buildJsonArray {
                add(webViewFallbackItem("google_webview", "google", query))
                add(webViewFallbackItem("duckduckgo_webview", "duckduckgo", query))
                add(webViewFallbackItem("bing_webview", "bing", query))
            })
        }
    }

    private fun webViewFallbackItem(source: String, engine: String, query: String): JsonObject {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = when (engine) {
            "duckduckgo" -> "https://duckduckgo.com/?q=$encoded"
            "bing" -> "https://www.bing.com/search?q=$encoded"
            else -> "https://www.google.com/search?q=$encoded"
        }
        return buildJsonObject {
            put("source_service", source)
            put("engine", engine)
            put("url", url)
        }
    }

    private fun sanitizeError(throwable: Throwable): String {
        val raw = throwable.message ?: throwable::class.simpleName ?: "Search source failed"
        return raw
            .replace(Regex("\\b[a-zA-Z0-9_.]+Exception\\b"), "error")
            .replace(Regex("\\b[a-zA-Z0-9_.]+Error\\b"), "error")
            .take(500)
    }

    private fun sourceStatusJson(
        sourceResults: List<SourceSearchResult>,
        sources: List<OrchestratorSource>,
        calls: List<SourceSearchRequest>,
    ): JsonArray = buildJsonArray {
        sources.forEach { source ->
            val attemptedCalls = calls.filter { it.source.id == source.id }
            val attemptedResults = sourceResults.filter { it.request.source.id == source.id }
            val successCount = attemptedResults.count { it.result.isSuccess }
            val failureCount = attemptedResults.count { it.result.isFailure }
            val resultCount = attemptedResults.sumOf { it.result.getOrNull()?.items?.size ?: 0 }
            add(
                buildJsonObject {
                    put("service", source.name)
                    put("service_id", source.id)
                    put("source_kind", source.kind)
                    put("builtin", source.builtin)
                    put("called", attemptedCalls.isNotEmpty())
                    put("variant_count", attemptedCalls.size)
                    put("result_count", resultCount)
                    put(
                        "status",
                        when {
                            attemptedCalls.isEmpty() -> "skipped"
                            successCount > 0 && failureCount > 0 -> "partial"
                            successCount > 0 -> "ok"
                            else -> "error"
                        }
                    )
                    if (attemptedCalls.isEmpty()) {
                        put("skipped_reason", "not selected, not applicable, or call budget exhausted")
                    }
                    attemptedResults.firstOrNull { it.result.isFailure }
                        ?.result
                        ?.exceptionOrNull()
                        ?.let { put("error", sanitizeError(it)) }
                    put("queries", buildJsonArray {
                        attemptedCalls.map { it.query }.distinct().forEach { add(JsonPrimitive(it)) }
                    })
                }
            )
        }
    }

    private fun JsonObject.string(name: String): String? {
        return this[name]?.jsonPrimitive?.contentOrNull
    }

    private fun JsonObject.int(name: String): Int? {
        return this[name]?.jsonPrimitive?.intOrNull
    }

    private fun JsonObject.boolean(name: String): Boolean? {
        return this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
    }

    private fun JsonObject.serviceSelectors(): List<String> {
        val raw = this["preferred_sources"] ?: this["services"] ?: return emptyList()
        return (when (raw) {
            is JsonArray -> raw.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            is JsonPrimitive -> raw.contentOrNull?.split(",")
            else -> emptyList()
        } ?: emptyList()).map { it.trim() }.filter { it.isNotBlank() }
    }

    internal data class SourceSearchRequest(
        val source: OrchestratorSource,
        val sourceIndex: Int,
        val variantIndex: Int,
        val query: String,
        val originalQuery: String,
        val topic: String,
        val timeRange: String,
        val recencyDays: Int?,
        val maxResults: Int,
    )

    private data class SourceSearchResult(
        val request: SourceSearchRequest,
        val result: Result<SearchResult>,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("service", request.source.name)
            put("service_id", request.source.id)
            put("source_kind", request.source.kind)
            put("builtin", request.source.builtin)
            put("status", if (result.isSuccess) "ok" else "error")
            put("result_count", result.getOrNull()?.items?.size ?: 0)
            result.exceptionOrNull()?.let { put("error", sanitizeError(it)) }
            put("query", request.query)
            put("variant_index", request.variantIndex)
        }
    }

    internal sealed class OrchestratorSource {
        abstract val id: String
        abstract val name: String
        abstract val kind: String
        abstract val builtin: Boolean
        abstract val priority: Int
        abstract val reason: String

        data object BuiltInDuckDuckGo : OrchestratorSource() {
            override val id = "duckduckgo_builtin"
            override val name = "DuckDuckGo"
            override val kind = "public_html"
            override val builtin = true
            override val priority = 200
            override val reason = "Built-in free public recall source"
        }

        data object BuiltInJinaSearch : OrchestratorSource() {
            override val id = "jina_builtin"
            override val name = "Jina Search"
            override val kind = "public_api"
            override val builtin = true
            override val priority = 90
            override val reason = "Built-in Jina search source; API key optional"
        }

        data object BuiltInBing : OrchestratorSource() {
            override val id = "bing_builtin"
            override val name = "Bing HTML 兜底"
            override val kind = "public_html"
            override val builtin = true
            override val priority = 210
            override val reason = "Built-in free public recall source"
        }

        data object BuiltInWikipedia : OrchestratorSource() {
            override val id = "wikipedia_builtin"
            override val name = "Wikipedia"
            override val kind = "vertical_knowledge"
            override val builtin = true
            override val priority = 230
            override val reason = "Vertical source for entity and background knowledge"
        }

        data object BuiltInHackerNews : OrchestratorSource() {
            override val id = "hackernews_builtin"
            override val name = "Hacker News"
            override val kind = "vertical_technical"
            override val builtin = true
            override val priority = 220
            override val reason = "Vertical source for technical and open-source discussions"
        }

        data class Configured(
            val options: SearchServiceOptions,
            override val priority: Int,
        ) : OrchestratorSource() {
            override val id = options.id.toString()
            override val name = SearchServiceOptions.TYPES[options::class] ?: "Search"
            override val kind = "configured"
            override val builtin = false
            override val reason = "Enabled configured search service"
        }
    }

    private fun OrchestratorSource.applicableFor(query: String?, topic: String): Boolean {
        if (query == null) return true
        if (this !is OrchestratorSource.BuiltInWikipedia && this !is OrchestratorSource.BuiltInHackerNews) {
            return true
        }
        val lower = query.orEmpty().lowercase(Locale.ROOT)
        return when (this) {
            is OrchestratorSource.BuiltInWikipedia -> {
                topic == "general" && listOf("是什么", "百科", "wiki", "wikipedia", "who is", "what is", "definition", "介绍")
                    .any { lower.contains(it) }
            }
            is OrchestratorSource.BuiltInHackerNews -> {
                topic == "technical" || listOf("github", "开源", "developer", "claude code", "codex", "mcp", "llm", "ai agent", "hacker news")
                    .any { lower.contains(it) }
            }
        }
    }

    private fun OrchestratorSource.apiKeyConfigured(): Boolean? {
        val options = (this as? OrchestratorSource.Configured)?.options ?: return null
        return when (options) {
            is SearchServiceOptions.BraveOptions -> options.apiKey.isNotBlank()
            is SearchServiceOptions.BochaOptions -> options.apiKey.isNotBlank()
            is SearchServiceOptions.ExaOptions -> options.apiKey.isNotBlank()
            is SearchServiceOptions.FirecrawlOptions -> options.apiKey.isNotBlank()
            is SearchServiceOptions.GrokOptions -> options.apiKey.isNotBlank()
            is SearchServiceOptions.JinaOptions -> options.apiKey.isNotBlank()
            is SearchServiceOptions.LinkUpOptions -> options.apiKey.isNotBlank()
            is SearchServiceOptions.MetasoOptions -> options.apiKey.isNotBlank()
            is SearchServiceOptions.OllamaOptions -> options.apiKey.isNotBlank()
            is SearchServiceOptions.PerplexityOptions -> options.apiKey.isNotBlank()
            is SearchServiceOptions.AmberAgentSearchOptions -> options.apiKey.isNotBlank()
            is SearchServiceOptions.SerperOptions -> options.apiKey.isNotBlank()
            is SearchServiceOptions.SerpApiOptions -> options.apiKey.isNotBlank()
            is SearchServiceOptions.TavilyOptions -> options.apiKey.isNotBlank()
            is SearchServiceOptions.ZhipuOptions -> options.apiKey.isNotBlank()
            is SearchServiceOptions.BingLocalOptions,
            is SearchServiceOptions.SearXNGOptions -> null
        }
    }

    private data class OrchestratedSearchItem(
        val title: String,
        val url: String,
        var text: String,
        val domain: String,
        val sourceService: String,
        val sourceServices: LinkedHashSet<String>,
        val sourceRank: Int,
        var duplicateCount: Int,
        var score: Int,
        var publishedAt: String?,
        var freshnessScore: Int,
        // Image URLs surfaced by the underlying search service (Brave / Tavily /
        // SearXNG return per-item thumbnails). Capped at 5 per item; merged across
        // duplicate sources in mergeResults.
        var images: List<String> = emptyList(),
    ) {
        fun toJson(index: Int, maxImages: Int = 5): JsonElement = buildJsonObject {
            put("id", Uuid.random().toString().take(6))
            put("index", index)
            put("title", title)
            put("url", url)
            put("domain", domain)
            put("text", text.take(2_000))
            put("source_service", sourceService)
            put("source_services", buildJsonArray {
                sourceServices.forEach { add(JsonPrimitive(it)) }
            })
            put("source_rank", sourceRank)
            put("duplicate_count", duplicateCount)
            put("rank_score", score)
            put("freshness_score", freshnessScore)
            put("verified_by_scrape", false)
            if (publishedAt == null) {
                put("published_at", JsonNull)
                put("published_at_unknown", true)
            } else {
                put("published_at", publishedAt)
                put("published_at_unknown", false)
            }
            val cappedImages = images.distinct().take(maxImages.coerceIn(0, 5))
            if (cappedImages.isNotEmpty()) {
                put("images", buildJsonArray {
                    cappedImages.forEach { add(JsonPrimitive(it)) }
                })
            }
        }
    }
}

internal typealias OrchestratorSearchExecutor = suspend (
    SearchOrchestrator.SourceSearchRequest,
    SearchCommonOptions,
) -> Result<SearchResult>
