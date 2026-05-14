package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchResult
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import java.net.URI
import java.time.LocalDate
import java.util.Locale
import kotlin.uuid.Uuid

internal object SearchAggregator {
    private const val MAX_PARALLEL_SERVICES = 3
    private val allowedTopics = setOf("general", "news", "finance")
    private val allowedTimeRanges = setOf("day", "week", "month", "year", "any")

    suspend fun search(
        settings: Settings,
        params: JsonObject,
        executor: SearchExecutor = ::executeServiceSearch,
    ): JsonObject = coroutineScope {
        val query = params.string("query") ?: params.string("q") ?: error("query is required")
        val topic = params.string("topic")?.takeIf { it in allowedTopics } ?: "general"
        val explicitTimeRange = params.string("time_range")?.takeIf { it in allowedTimeRanges }
        val timeRange = explicitTimeRange ?: if (topic == "news") "week" else "any"
        val recencyDays = params.int("recency_days")?.coerceIn(1, 366)
        val maxResults = params.int("max_results")
            ?.coerceIn(1, 30)
            ?: settings.searchCommonOptions.resultSize.coerceIn(1, 30)
        val requestedServices = params.serviceSelectors()
        val candidates = enabledServices(settings, requestedServices).take(MAX_PARALLEL_SERVICES)

        if (candidates.isEmpty()) {
            return@coroutineScope buildJsonObject {
                put("status", "error")
                put("error", "No enabled search services are available. Enable at least one search service in Search Service settings.")
                put("items", JsonArray(emptyList()))
                put("sources", JsonArray(emptyList()))
            }
        }

        val perServiceSize = (maxResults + candidates.size - 1) / candidates.size + 3
        val calls = candidates.mapIndexed { sourceIndex, options ->
            async {
                val serviceName = options.serviceName()
                val serviceParams = buildServiceParams(
                    query = query,
                    topic = topic,
                    timeRange = timeRange,
                    recencyDays = recencyDays,
                    options = options,
                )
                val result = runCatching {
                    executor(
                        options,
                        serviceParams,
                        settings.searchCommonOptions.copy(resultSize = perServiceSize.coerceIn(1, 20)),
                    )
                }.getOrElse { Result.failure(it) }
                SearchSourceResult(
                    options = options,
                    sourceIndex = sourceIndex,
                    serviceName = serviceName,
                    params = serviceParams,
                    result = result,
                )
            }
        }.map { it.await() }

        val items = mergeResults(
            results = calls,
            query = query,
            topic = topic,
            timeRange = timeRange,
        ).take(maxResults)

        buildJsonObject {
            put("status", if (items.isNotEmpty()) "ok" else "empty")
            put("query", query)
            put("topic", topic)
            put("time_range", timeRange)
            recencyDays?.let { put("recency_days", it) }
            put("service_count", candidates.size)
            // Global image budget: max 5 images across all items in one response.
            var imagesBudget = 5
            put("items", buildJsonArray {
                items.forEachIndexed { index, item ->
                    val emittedImages = item.images.distinct().take(imagesBudget.coerceIn(0, 5)).size
                    add(item.toJson(index + 1, maxImages = imagesBudget))
                    imagesBudget = (imagesBudget - emittedImages).coerceAtLeast(0)
                }
            })
            // Aggregate all unique images across items into a top-level block
            // so the LLM can't miss them buried inside individual items.
            val allImages = items.flatMap { it.images }.distinct().take(5)
            put("total_images", allImages.size)
            if (allImages.isNotEmpty()) {
                put("image_instruction", "搜索结果包含 ${allImages.size} 张相关图片。" +
                    "请在回复中使用这些图片：1) 在回复顶部用一行并排展示缩略图 " +
                    "![img1](url1) ![img2](url2)；2) 在正文合适的位置插入图片 " +
                    "![简短描述](url)，放在相关段落之后。最多使用 5 张图片。")
                put("available_images", buildJsonArray {
                    allImages.forEach { add(JsonPrimitive(it)) }
                })
            }
            put("sources", buildJsonArray {
                calls.forEach { source ->
                    add(source.toJson())
                }
            })
        }
    }

    fun enabledServices(
        settings: Settings,
        requestedServices: List<String> = emptyList(),
    ): List<SearchServiceOptions> {
        val enabledIds = settings.searchEnabledServiceIds.toSet()
        val enabled = settings.searchServices.filter { it.id in enabledIds }
        if (requestedServices.isEmpty()) return enabled

        val selectors = requestedServices.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }
        if (selectors.isEmpty()) return enabled
        return enabled.filter { options ->
            val id = options.id.toString().lowercase(Locale.ROOT)
            val name = options.serviceName().lowercase(Locale.ROOT)
            selectors.any { selector ->
                id == selector || id.startsWith(selector) || name == selector
            }
        }
    }

    fun buildServiceParams(
        query: String,
        topic: String,
        timeRange: String,
        recencyDays: Int?,
        options: SearchServiceOptions,
    ): JsonObject {
        val nativeTopic = options.supportsNativeTopic()
        val effectiveQuery = if (nativeTopic) {
            query
        } else {
            enhanceQuery(query, topic, timeRange, recencyDays)
        }
        return buildJsonObject {
            put("query", effectiveQuery)
            if (nativeTopic) {
                put("topic", topic)
            }
            if (options is SearchServiceOptions.FirecrawlOptions && topic == "news") {
                put("sources", JsonArray(listOf(JsonPrimitive("news"), JsonPrimitive("web"))))
            }
        }
    }

    fun canonicalizeUrl(url: String): String {
        return runCatching {
            val uri = URI(url.trim())
            val scheme = (uri.scheme ?: "https").lowercase(Locale.ROOT)
            val host = (uri.host ?: "").lowercase(Locale.ROOT).removePrefix("www.")
            val path = (uri.rawPath ?: "").trimEnd('/').ifBlank { "/" }
            val query = uri.rawQuery
                ?.split("&")
                ?.filter { part ->
                    val key = part.substringBefore("=").lowercase(Locale.ROOT)
                    key !in setOf("fbclid", "gclid", "igshid", "mc_cid", "mc_eid") &&
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

    fun normalizedTitle(title: String): String {
        return title.lowercase(Locale.ROOT)
            .replace(Regex("[\\p{Punct}\\s]+"), "")
            .take(80)
    }

    private fun mergeResults(
        results: List<SearchSourceResult>,
        query: String,
        topic: String,
        timeRange: String,
    ): List<AggregatedSearchItem> {
        val byKey = LinkedHashMap<String, AggregatedSearchItem>()
        val queryTokens = query.lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }
            .take(8)

        results.forEach { source ->
            val result = source.result.getOrNull() ?: return@forEach
            result.items.forEachIndexed { index, item ->
                val canonical = canonicalizeUrl(item.url)
                val titleKey = source.domain(item.url) + "|" + normalizedTitle(item.title)
                val key = if (canonical.isNotBlank()) canonical else titleKey
                val existing = byKey[key] ?: byKey[titleKey]
                if (existing == null) {
                    val sourceBoost = (results.size - source.sourceIndex) * 10
                    val hitBoost = queryTokens.count { token ->
                        item.title.lowercase(Locale.ROOT).contains(token) ||
                                item.text.lowercase(Locale.ROOT).contains(token)
                    } * 3
                    val freshnessBoost = if (topic == "news" && timeRange != "any") 8 else 0
                    byKey[key] = AggregatedSearchItem(
                        title = item.title,
                        url = item.url,
                        text = item.text,
                        sourceService = source.serviceName,
                        sourceServices = linkedSetOf(source.serviceName),
                        sourceRank = index + 1,
                        duplicateCount = 1,
                        score = sourceBoost + hitBoost + freshnessBoost - index,
                        images = item.images.take(5),
                    )
                } else {
                    existing.sourceServices.add(source.serviceName)
                    existing.duplicateCount += 1
                    existing.score += 8
                    if (item.text.length > existing.text.length) {
                        existing.text = item.text
                    }
                    // Merge images from duplicate sources, rebuild list to stay immutable-safe
                    if (item.images.isNotEmpty() && existing.images.size < 5) {
                        val merged = (existing.images + item.images).distinct().take(5)
                        existing.images = merged
                    }
                }
            }
        }
        return byKey.values.sortedWith(
            compareByDescending<AggregatedSearchItem> { it.score }
                .thenByDescending { it.duplicateCount }
                .thenBy { it.sourceRank }
        )
    }

    private fun enhanceQuery(
        query: String,
        topic: String,
        timeRange: String,
        recencyDays: Int?,
    ): String {
        val today = LocalDate.now().toLocalString(true)
        val rangeText = when {
            recencyDays != null -> "last $recencyDays days"
            timeRange == "day" -> "today $today"
            timeRange == "week" -> "this week $today"
            timeRange == "month" -> "this month $today"
            timeRange == "year" -> "this year $today"
            else -> null
        }
        return listOfNotNull(
            query,
            if (topic == "news") "news" else null,
            rangeText,
        ).joinToString(" ")
    }

    private suspend fun executeServiceSearch(
        options: SearchServiceOptions,
        params: JsonObject,
        commonOptions: SearchCommonOptions,
    ): Result<SearchResult> {
        val service = SearchService.getService(options)
        return service.search(
            params = params,
            commonOptions = commonOptions,
            serviceOptions = options,
        )
    }

    private fun SearchServiceOptions.supportsNativeTopic(): Boolean {
        return this is SearchServiceOptions.TavilyOptions
    }

    private fun SearchServiceOptions.serviceName(): String {
        return SearchServiceOptions.TYPES[this::class] ?: "Search"
    }

    private fun JsonObject.string(name: String): String? {
        return this[name]?.jsonPrimitive?.contentOrNull
    }

    private fun JsonObject.int(name: String): Int? {
        return this[name]?.jsonPrimitive?.intOrNull
    }

    private fun JsonObject.serviceSelectors(): List<String> {
        val raw = this["services"] ?: return emptyList()
        return when {
            raw is JsonArray -> raw.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            raw is JsonPrimitive -> raw.contentOrNull?.split(",")
            else -> emptyList()
        }?.map { it.trim() }.orEmpty()
    }
}

internal typealias SearchExecutor = suspend (
    SearchServiceOptions,
    JsonObject,
    SearchCommonOptions,
) -> Result<SearchResult>

private data class SearchSourceResult(
    val options: SearchServiceOptions,
    val sourceIndex: Int,
    val serviceName: String,
    val params: JsonObject,
    val result: Result<SearchResult>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("service", serviceName)
        put("service_id", options.id.toString())
        put("status", if (result.isSuccess) "ok" else "error")
        put("result_count", result.getOrNull()?.items?.size ?: 0)
        result.exceptionOrNull()?.message?.let { put("error", it.take(500)) }
        put("query", params["query"] ?: JsonNull)
    }

    fun domain(url: String): String {
        return runCatching {
            URI(url).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        }.getOrDefault("")
    }
}

private data class AggregatedSearchItem(
    val title: String,
    val url: String,
    var text: String,
    val sourceService: String,
    val sourceServices: LinkedHashSet<String>,
    val sourceRank: Int,
    var duplicateCount: Int,
    var score: Int,
    var images: List<String> = emptyList(),
) {
    fun toJson(index: Int, maxImages: Int = 5): JsonElement = buildJsonObject {
        put("id", Uuid.random().toString().take(6))
        put("index", index)
        put("title", title)
        put("url", url)
        put("text", text.take(2_000))
        put("source_service", sourceService)
        put("source_services", buildJsonArray {
            sourceServices.forEach { add(JsonPrimitive(it)) }
        })
        put("source_rank", sourceRank)
        put("duplicate_count", duplicateCount)
        put("rank_score", score)
        put("published_at", JsonNull)
        val cappedImages = images.distinct().take(maxImages.coerceIn(0, 5))
        if (cappedImages.isNotEmpty()) {
            put("images", buildJsonArray {
                cappedImages.forEach { add(JsonPrimitive(it)) }
            })
        }
    }
}
