package me.rerere.rikkahub.data.agent.board.hotlist.deepread

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.agent.board.hotlist.HotListRepository
import me.rerere.rikkahub.data.agent.board.hotlist.HotTopicSource
import me.rerere.rikkahub.data.agent.board.hotlist.presentationTitle
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchResult
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Pre-fetches sources for DeepRead before the model loop runs.
 *
 * The new RunManager flow used to let the hidden agent drive search_web /
 * scrape_web entirely on its own, which pushed every fetch through a slow
 * reasoning round-trip. This collector reproduces the old DeepReadAgent
 * parallel search + scrape + OG-image pass under a 36s wall budget and hands
 * the result to the prompt builder, so the model only has to read pre-baked
 * sources and optionally fill gaps.
 */
class DeepReadSourcePrefetcher(
    private val settingsStore: SettingsAggregator,
    private val hotListRepository: HotListRepository,
    private val client: OkHttpClient,
) {

    private data class CacheEntry(
        val sources: List<DeepReadSource>,
        val timestamp: Long,
    )

    // In-memory TTL cache so back-to-back generateStages calls for the same
    // topic (most commonly: primary run + scheduleBackgroundFill of missing
    // stages) do not re-pay the 36s search+scrape budget. Keyed by topic +
    // seedUrl since the seed changes what we anchor on.
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun collect(
        topicId: String,
        topicTitle: String,
        seedUrl: String? = null,
        force: Boolean = false,
    ): List<DeepReadSource> {
        val cacheKey = "$topicId|${seedUrl.orEmpty()}"
        val now = System.currentTimeMillis()
        if (!force) {
            cache[cacheKey]?.let { entry ->
                val age = now - entry.timestamp
                if (age < CACHE_TTL_MS) {
                    Log.i(TAG, "deep read prefetch cache hit topic=$topicId age=${age}ms")
                    return entry.sources
                }
                cache.remove(cacheKey)
            }
        } else {
            cache.remove(cacheKey)
        }

        val settings = settingsStore.settingsFlow.value
        val seedSources = buildSeedSources(topicId, topicTitle, seedUrl)
        val seedFallback = seedSources.filter { it.isUsableSeedSource() }

        val collected = withTimeoutOrNull(SOURCE_COLLECTION_TIMEOUT_MS) {
            coroutineScope {
                val enabled = settings.enabledDeepReadSearchServices()
                if (enabled.isEmpty()) {
                    Log.w(TAG, "deep read prefetch: no enabled search services; falling back to seeds only")
                    return@coroutineScope seedFallback
                }
                val queries = buildDeepReadQueries(topicTitle)
                Log.i(
                    TAG,
                    "deep read prefetch services=${enabled.joinToString { it.deepReadServiceLabel() }} " +
                        "queries=${queries.size} seeds=${seedSources.size}",
                )

                val searchResults = enabled.flatMap { service ->
                    queries.map { query ->
                        async {
                            try {
                                withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                                    searchWithService(service, query, SEARCH_RESULTS_PER_QUERY)
                                        .getOrNull()
                                        ?.let { result ->
                                            SearchBucket(
                                                query = query,
                                                items = result.items.withSearchAnswer(
                                                    answer = result.answer,
                                                    serviceName = service.deepReadServiceLabel(),
                                                ),
                                            )
                                        }
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                Log.w(TAG, "deep read search failed: ${service.id} query=$query", error)
                                null
                            }
                        }
                    }
                }.awaitAll()
                    .filterNotNull()
                    .let(::interleaveSearchResults)
                Log.i(TAG, "deep read prefetch search results=${searchResults.size}")

                val scrapeServices = enabled.filter { it.supportsDeepReadScrape() }
                val seedEnriched = seedSources.map { source ->
                    async { enrichSeedSource(source, topicTitle) }
                }.awaitAll()
                    .filter { it.isUsableSeedSource() }
                val enriched = searchResults.mapIndexed { index, hit ->
                    async {
                        val item = hit.item
                        val shouldScrape = scrapeServices.isNotEmpty() && index < MAX_SCRAPE_RESULTS
                        val scraped = if (shouldScrape) {
                            scrapeWithServices(scrapeServices, item.url)
                        } else {
                            null
                        }
                        val direct = if (scraped.isNullOrBlank()) {
                            try {
                                withTimeoutOrNull(DIRECT_FETCH_TIMEOUT_MS) {
                                    fetchReadableText(item.url)
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Throwable) {
                                null
                            }
                        } else {
                            null
                        }
                        val pageImages = if (index < MAX_META_IMAGE_RESULTS) {
                            try {
                                fetchPageImages(item.url)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Throwable) {
                                emptyList()
                            }
                        } else {
                            emptyList()
                        }
                        val content = sourceContentForSearchResult(item, scraped, direct).take(SOURCE_EXCERPT_LIMIT)
                        val imageCandidates = buildImageCandidates(
                            topicTitle = topicTitle,
                            sourceUrl = item.url,
                            sourceTitle = item.title,
                            sourceService = domainOf(item.url),
                            query = hit.query,
                            rank = index + 1,
                            searchImages = item.images,
                            pageImages = pageImages,
                            sourceText = content,
                        )
                        DeepReadSource(
                            title = item.title,
                            url = item.url,
                            source = domainOf(item.url),
                            content = content,
                            publishedAt = item.publishedAt,
                            images = imageCandidates
                                .filter { it.confidence != IMAGE_CONFIDENCE_REJECT }
                                .sortedByDescending { it.score }
                                .map { it.imageUrl }
                                .distinct()
                                .take(6),
                            imageCandidates = imageCandidates,
                        )
                    }
                }.awaitAll()
                    .filter { it.isUsableCollectedSource() }
                Log.i(
                    TAG,
                    "deep read prefetch usable=${enriched.size} seeds=${seedEnriched.size} " +
                        "scrape=${scrapeServices.joinToString { it.deepReadServiceLabel() }.ifBlank { "none" }}",
                )

                (seedEnriched + enriched)
                    .distinctBy { source -> source.url.ifBlank { source.title } }
                    .take(MAX_SOURCES)
            }
        } ?: seedFallback

        // Only cache non-empty results: an empty list is a transient failure
        // and the next call should retry rather than serve emptiness from
        // cache. Caching after the 36s budget so a re-entry within TTL skips
        // the full prefetch.
        if (collected.isNotEmpty()) {
            evictIfFull()
            cache[cacheKey] = CacheEntry(sources = collected, timestamp = now)
        }
        return collected
    }

    private fun evictIfFull() {
        if (cache.size < CACHE_MAX_ENTRIES) return
        // Best-effort LRU: snapshot entries, find the oldest by timestamp,
        // remove. Concurrent inserts may briefly push us over the cap; that's
        // bounded and self-corrects on the next insert.
        val oldestKey = cache.entries.minByOrNull { it.value.timestamp }?.key
        oldestKey?.let { cache.remove(it) }
    }

    private suspend fun buildSeedSources(
        topicId: String,
        topicTitle: String,
        seedUrl: String?,
    ): List<DeepReadSource> {
        val hotSeeds = hotListRepository.getHotTopic(topicId)
            ?.sources
            .orEmpty()
            .toDeepReadSources(topicTitle)
        val userSeed = seedUrl
            ?.takeIf { it.isHttpOrHttpsUrl() }
            ?.let { url ->
                DeepReadSource(
                    title = topicTitle,
                    url = url,
                    source = domainOf(url),
                    content = "",
                    publishedAt = null,
                    images = emptyList(),
                )
            }
        return (listOfNotNull(userSeed) + hotSeeds)
            .distinctBy { it.url.ifBlank { it.title } }
    }

    private suspend fun enrichSeedSource(source: DeepReadSource, topicTitle: String): DeepReadSource {
        val direct = source.url.takeIf { it.isHttpOrHttpsUrl() }?.let { url ->
            try {
                withTimeoutOrNull(DIRECT_FETCH_TIMEOUT_MS) { fetchReadableText(url) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "deep read seed fetch failed: $url", error)
                null
            }
        }
        val pageImages = source.url.takeIf { it.isHttpOrHttpsUrl() }?.let { url ->
            try {
                withTimeoutOrNull(OG_IMAGE_TIMEOUT_MS) { fetchPageImages(url) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "deep read seed og image fetch failed: $url", error)
                emptyList()
            }
        }.orEmpty()
        val content = (direct ?: source.content).take(SOURCE_EXCERPT_LIMIT)
        val imageCandidates = buildImageCandidates(
            topicTitle = topicTitle,
            sourceUrl = source.url,
            sourceTitle = source.title,
            sourceService = source.source,
            query = null,
            rank = null,
            searchImages = source.images,
            pageImages = pageImages,
            sourceText = content,
            defaultKind = "hotlist_image",
        )
        return source.copy(
            content = content,
            images = imageCandidates
                .filter { it.confidence != IMAGE_CONFIDENCE_REJECT }
                .sortedByDescending { it.score }
                .map { it.imageUrl }
                .distinct()
                .take(6),
            imageCandidates = imageCandidates,
        )
    }

    private suspend fun scrapeWithServices(
        services: List<SearchServiceOptions>,
        url: String,
    ): String? {
        for (service in services) {
            val scraped = try {
                withTimeoutOrNull(SCRAPE_TIMEOUT_MS) { scrapeWithService(service, url) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "deep read scrape failed: ${service.deepReadServiceLabel()} url=$url", error)
                null
            }
            if (!scraped.isNullOrBlank()) return scraped
        }
        return null
    }

    private suspend fun searchWithService(
        options: SearchServiceOptions,
        topicTitle: String,
        resultSize: Int,
    ): Result<SearchResult> {
        val params = buildJsonObject {
            put("query", topicTitle)
            put("topic", "news")
        }
        val service = SearchService.getService(options)
        return service.search(params, SearchCommonOptions(resultSize = resultSize), options)
    }

    private suspend fun scrapeWithService(options: SearchServiceOptions, url: String): String? {
        val params = buildJsonObject { put("url", url) }
        val service = SearchService.getService(options)
        return service.scrape(params, SearchCommonOptions(resultSize = 1), options)
            .getOrNull()
            ?.urls
            ?.firstOrNull()
            ?.content
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun fetchPageImages(url: String): List<PageImage> = withContext(Dispatchers.IO) {
        withTimeoutOrNull(OG_IMAGE_TIMEOUT_MS) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withTimeoutOrNull emptyList()
                response.body.charStream().use { reader ->
                    val buffer = CharArray(OG_IMAGE_HTML_CHAR_LIMIT)
                    val read = reader.read(buffer).coerceAtLeast(0)
                    String(buffer, 0, read).extractPageImages()
                }
            }
        }
    }.orEmpty()

    private suspend fun fetchReadableText(url: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.peekBody(DIRECT_FETCH_MAX_BYTES).string()
            body.extractReadableText().takeIf { it.length >= MIN_SOURCE_CHARS }?.take(SOURCE_EXCERPT_LIMIT)
        }
    }

    private fun sourceContentForSearchResult(
        item: SearchResult.SearchResultItem,
        scraped: String?,
        direct: String?,
    ): String {
        val fullText = scraped ?: direct
        if (!fullText.isNullOrBlank()) return fullText
        val snippet = item.text.trim()
        return buildString {
            append(item.title.trim())
            if (snippet.isNotBlank()) {
                append("\n搜索摘要：")
                append(snippet)
            }
        }
    }

    private suspend fun buildImageCandidates(
        topicTitle: String,
        sourceUrl: String,
        sourceTitle: String,
        sourceService: String?,
        query: String?,
        rank: Int?,
        searchImages: List<String>,
        pageImages: List<PageImage>,
        sourceText: String,
        defaultKind: String = "search_result_image",
    ): List<DeepReadImageCandidate> {
        val searchCandidates = searchImages.mapIndexedNotNull { index, imageUrl ->
            imageUrl.takeIf { it.isHttpOrHttpsUrl() }?.let {
                DeepReadImageCandidate(
                    imageUrl = it,
                    sourceUrl = sourceUrl,
                    sourceTitle = sourceTitle,
                    pageTitle = sourceTitle,
                    candidateKind = defaultKind,
                    sourceService = sourceService,
                    query = query,
                    rank = rank ?: index + 1,
                )
            }
        }
        val pageCandidates = pageImages.mapIndexed { index, image ->
            DeepReadImageCandidate(
                imageUrl = image.url,
                sourceUrl = sourceUrl,
                sourceTitle = sourceTitle,
                pageTitle = sourceTitle,
                alt = image.alt,
                nearbyText = image.nearbyText,
                candidateKind = image.kind,
                sourceService = sourceService,
                query = query,
                rank = rank ?: index + 1,
            )
        }
        return (pageCandidates + searchCandidates)
            .distinctBy { it.imageUrl.trim() }
            .take(MAX_IMAGE_CANDIDATES_PER_SOURCE)
            .map { candidate ->
                val withQuality = candidate.copy(quality = probeImageQuality(candidate.imageUrl))
                DeepReadImageScorer.score(topicTitle, withQuality)
            }
            .sortedWith(compareByDescending<DeepReadImageCandidate> { it.confidence == IMAGE_CONFIDENCE_HERO }
                .thenByDescending { it.confidence == IMAGE_CONFIDENCE_INLINE }
                .thenByDescending { it.score })
    }

    private suspend fun probeImageQuality(url: String): DeepReadImageQuality = withContext(Dispatchers.IO) {
        val probed = runCatching {
            withTimeoutOrNull(IMAGE_PROBE_TIMEOUT_MS) {
                val request = Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .build()
                client.newCall(request).execute().use { response ->
                    DeepReadImageQuality(
                        contentType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(),
                        byteSize = response.header("Content-Length")?.toLongOrNull(),
                    )
                }
            }
        }.getOrNull()
        mergeQualityHints(probed ?: DeepReadImageQuality(), url.imageQualityHints())
    }

    private fun mergeQualityHints(
        probed: DeepReadImageQuality,
        hinted: DeepReadImageQuality,
    ): DeepReadImageQuality =
        DeepReadImageQuality(
            width = probed.width ?: hinted.width,
            height = probed.height ?: hinted.height,
            contentType = probed.contentType ?: hinted.contentType,
            byteSize = probed.byteSize ?: hinted.byteSize,
        )

    private fun String.imageQualityHints(): DeepReadImageQuality {
        val lower = lowercase()
        val queryWidth = Regex("""(?:[?&](?:w|width)=)(\d{2,5})""").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val queryHeight = Regex("""(?:[?&](?:h|height)=)(\d{2,5})""").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val pathSize = Regex("""(?:^|[^\d])(\d{2,5})[xX](\d{2,5})(?:[^\d]|$)""")
            .find(this)
            ?.let { match -> match.groupValues[1].toIntOrNull() to match.groupValues[2].toIntOrNull() }
        val extType = when (lower.substringBefore('?').substringAfterLast('.', "")) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "ico" -> "image/x-icon"
            "svg" -> "image/svg+xml"
            else -> null
        }
        return DeepReadImageQuality(
            width = queryWidth ?: pathSize?.first,
            height = queryHeight ?: pathSize?.second,
            contentType = extType,
        )
    }

    private fun DeepReadSource.isUsableCollectedSource(): Boolean {
        if (title.isBlank() || url.isBlank()) return false
        val trimmed = content.trim()
        if (trimmed.length >= MIN_SOURCE_CHARS) return true
        return "搜索摘要：" in trimmed && trimmed.length >= MIN_SEARCH_SNIPPET_SOURCE_CHARS
    }

    private fun DeepReadSource.isUsableSeedSource(): Boolean {
        if (title.isBlank()) return false
        val trimmed = content.trim()
        return trimmed.length >= MIN_SEED_SOURCE_CHARS
    }

    private fun List<SearchResult.SearchResultItem>.withSearchAnswer(
        answer: String?,
        serviceName: String,
    ): List<SearchResult.SearchResultItem> {
        val summary = answer?.trim()?.takeIf { it.length >= MIN_SEARCH_ANSWER_CHARS } ?: return this
        if (isEmpty()) return this
        val first = first()
        val content = buildString {
            if (first.text.isNotBlank()) {
                append(first.text.trim())
                append("\n")
            }
            append("搜索服务综合摘要（")
            append(serviceName)
            append("）：")
            append(summary)
        }
        return listOf(first.copy(text = content)) + drop(1)
    }

    private fun interleaveSearchResults(buckets: List<SearchBucket>): List<SearchHit> {
        val queryGroups = buckets
            .map { bucket -> bucket.items.distinctBy { it.url }.map { item -> SearchHit(bucket.query, item) } }
            .filter { it.isNotEmpty() }
        val merged = mutableListOf<SearchHit>()
        var index = 0
        while (merged.size < MAX_SEARCH_RESULTS && queryGroups.any { index < it.size }) {
            queryGroups.forEach { hits ->
                if (merged.size < MAX_SEARCH_RESULTS && index < hits.size) {
                    val hit = hits[index]
                    if (merged.none { it.item.url == hit.item.url }) {
                        merged += hit
                    }
                }
            }
            index++
        }
        return merged
    }

    private fun buildDeepReadQueries(topicTitle: String): List<String> {
        val lower = topicTitle.lowercase()
        val currentYear = java.time.LocalDate.now().year
        val queries = mutableListOf(
            topicTitle,
            "$topicTitle $currentYear 最新 今日",
            "$topicTitle 前因后果 时间线 背景",
            "$topicTitle 时间线 事件梳理 最新进展",
            "$topicTitle 官方 声明 通报",
            "$topicTitle 核心矛盾 争议 影响",
            "$topicTitle 各方反应 国际影响 专家解读",
            "$topicTitle background timeline context controversy",
            "$topicTitle latest news $currentYear",
            "$topicTitle official statement reactions analysis implications",
            "$topicTitle 图片 现场图 截图",
            "$topicTitle 发布会 PPT 演示 文稿 图片",
            "$topicTitle screenshot presentation slide keynote",
        )
        if (listOf("发布会", "ppt", "演示", "截图", "小米", "特斯拉", "八败两胜").any { it in lower || it in topicTitle }) {
            queries += "$topicTitle PPT 截图 发布会 图"
            queries += "$topicTitle 现场图 演示文稿"
        }
        if (listOf("gemini", "google", "openai", "claude", "deepseek", "gpt", "llm", "大模型", "模型", "flash").any { it in lower }) {
            queries += "$topicTitle 发布 价格 跑分 性能 评价"
            queries += "$topicTitle pricing benchmark performance model card"
            queries += "$topicTitle official announcement availability API pricing"
        }
        if ("gemini" in lower || "google" in lower) {
            queries += "Google I/O $currentYear $topicTitle Gemini announcement pricing benchmarks"
            queries += "$topicTitle site:blog.google"
            queries += "$topicTitle site:deepmind.google model card"
        }
        return queries.distinct()
    }

    private fun List<HotTopicSource>.toDeepReadSources(topicTitle: String): List<DeepReadSource> =
        map { source ->
            val url = source.url?.takeIf { it.isHttpOrHttpsUrl() }.orEmpty()
            DeepReadSource(
                title = source.presentationTitle,
                url = url,
                source = source.providerName,
                content = buildString {
                    append("热榜话题：")
                    append(topicTitle)
                    append("。来源：")
                    append(source.providerName)
                    append(" 第")
                    append(source.rank)
                    append(" 名，标题：")
                    append(source.presentationTitle)
                    if (!source.heat.isNullOrBlank()) {
                        append("，热度：")
                        append(source.heat)
                    }
                    append("。")
                },
                publishedAt = null,
                images = source.images,
            )
        }.take(MAX_SEED_SOURCES)

    private fun domainOf(url: String): String? =
        runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()

    private fun String.extractPageImages(): List<PageImage> {
        val og = listOf(
            Regex("""property=["']og:image["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE) to "og_image",
            Regex("""name=["']twitter:image["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE) to "twitter_image",
            Regex("""content=["']([^"']+)["'][^>]*property=["']og:image["']""", RegexOption.IGNORE_CASE) to "og_image",
        ).flatMap { (pattern, kind) ->
            pattern.findAll(this).map { match -> PageImage(match.groupValues[1].htmlUnescape(), kind) }
        }
        val articleImages = Regex("""(?is)<img\b[^>]*>""")
            .findAll(this)
            .mapNotNull { match ->
                val tag = match.value
                val src = tag.attr("src")
                    ?: tag.attr("data-src")
                    ?: tag.attr("data-original")
                    ?: tag.attr("data-lazy-src")
                    ?: return@mapNotNull null
                if (!src.isHttpOrHttpsUrl()) return@mapNotNull null
                PageImage(
                    url = src.htmlUnescape(),
                    kind = "article_image",
                    alt = tag.attr("alt")?.htmlUnescape(),
                    nearbyText = tag.attr("title")?.htmlUnescape(),
                )
            }
            .take(8)
            .toList()
        return (og + articleImages)
            .filter { it.url.isHttpOrHttpsUrl() }
            .distinctBy { it.url }
            .take(10)
    }

    private fun String.attr(name: String): String? =
        Regex("""(?is)\b${Regex.escape(name)}\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""")
            .find(this)
            ?.let { match ->
                match.groupValues.drop(2).firstOrNull { it.isNotBlank() }?.trim()
            }

    private fun String.extractReadableText(): String =
        replace(Regex("(?is)<(script|style|noscript|svg|canvas)\\b.*?</\\1>"), " ")
            .replace(Regex("(?is)<br\\s*/?>"), "\n")
            .replace(Regex("(?is)</(p|div|section|article|li|h[1-6])>"), "\n")
            .replace(Regex("(?is)<[^>]+>"), " ")
            .htmlUnescape()
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n\\s*\\n+"), "\n")
            .lines()
            .map { it.trim() }
            .filter { line -> line.length >= 18 }
            .distinct()
            .joinToString("\n")
            .trim()

    private fun String.htmlUnescape(): String =
        replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")

    companion object {
        private const val TAG = "DeepReadSourcePrefetcher"
        private const val SOURCE_COLLECTION_TIMEOUT_MS = 36_000L
        private const val SEARCH_TIMEOUT_MS = 8_000L
        private const val SCRAPE_TIMEOUT_MS = 5_000L
        private const val OG_IMAGE_TIMEOUT_MS = 2_000L
        private const val IMAGE_PROBE_TIMEOUT_MS = 1_500L
        private const val DIRECT_FETCH_TIMEOUT_MS = 5_000L
        private const val DIRECT_FETCH_MAX_BYTES = 768_000L
        private const val OG_IMAGE_HTML_CHAR_LIMIT = 192_000
        // Sized to closely match MAX_SOURCES (12) downstream. Keeping ~2 extra in
        // MAX_SEARCH_RESULTS gives interleaveSearchResults a small buffer for
        // dedup; scraping all 14 would waste ~30% of the wall budget since only
        // 12 ever reach the model.
        private const val MAX_SEARCH_RESULTS = 14
        private const val SEARCH_RESULTS_PER_QUERY = 4
        private const val MAX_SCRAPE_RESULTS = 8
        private const val MAX_META_IMAGE_RESULTS = 6
        private const val MAX_IMAGE_CANDIDATES_PER_SOURCE = 6
        private const val MAX_SEED_SOURCES = 4
        // Aligned with RunManager.PROMPT_SOURCE_LIMIT (12). Returning more than the
        // prompt shows would just hide tail sources from the model with no benefit.
        private const val MAX_SOURCES = 12
        private const val SOURCE_EXCERPT_LIMIT = 2_400
        private const val MIN_SOURCE_CHARS = 280
        private const val MIN_SEARCH_SNIPPET_SOURCE_CHARS = 80
        private const val MIN_SEARCH_ANSWER_CHARS = 80
        private const val MIN_SEED_SOURCE_CHARS = 15
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"

        // Cache TTL is short relative to a news cycle but long enough to cover
        // a primary run plus its scheduleBackgroundFill completion. Size cap
        // prevents the cache from growing unbounded over a long app session.
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
        private const val CACHE_MAX_ENTRIES = 16
    }
}

private data class SearchBucket(
    val query: String,
    val items: List<SearchResult.SearchResultItem>,
)

private data class SearchHit(
    val query: String,
    val item: SearchResult.SearchResultItem,
)

private data class PageImage(
    val url: String,
    val kind: String,
    val alt: String? = null,
    val nearbyText: String? = null,
)

private fun Settings.enabledDeepReadSearchServices(): List<SearchServiceOptions> =
    searchServices
        .mapIndexed { index, service -> index to service }
        .filter { (_, service) -> searchEnabledServiceIds.any { it == service.id } }
        .sortedWith(
            compareByDescending<Pair<Int, SearchServiceOptions>> { (_, service) -> service.deepReadSearchPriority() }
                .thenBy { (index, _) -> index }
        )
        .map { (_, service) -> service }
        .plusDeepReadFallbacks()

private const val MAX_DEEP_READ_SEARCH_SERVICES = 6

private fun List<SearchServiceOptions>.plusDeepReadFallbacks(): List<SearchServiceOptions> {
    if (isEmpty()) return this
    val fallbacks = buildList<SearchServiceOptions> {
        if (none { it.isUsableDeepReadSearchFallback() }) {
            add(SearchServiceOptions.BingLocalOptions())
        }
        if (none { it.supportsDeepReadScrape() }) {
            add(SearchServiceOptions.JinaOptions())
        }
    }
    val primaryLimit = (MAX_DEEP_READ_SEARCH_SERVICES - fallbacks.size).coerceAtLeast(1)
    return (take(primaryLimit) + fallbacks).distinctBy { it.deepReadServiceKey() }
}

private fun SearchServiceOptions.isUsableDeepReadSearchFallback(): Boolean = when (this) {
    is SearchServiceOptions.BingLocalOptions,
    is SearchServiceOptions.JinaOptions -> true
    is SearchServiceOptions.SearXNGOptions -> url.isNotBlank()
    else -> false
}

private fun SearchServiceOptions.supportsDeepReadScrape(): Boolean =
    runCatching { SearchService.getService(this).scrapingParameters != null }.getOrDefault(false)

private fun String.isHttpOrHttpsUrl(): Boolean =
    startsWith("http://") || startsWith("https://")

private fun SearchServiceOptions.deepReadServiceKey(): String = when (this) {
    is SearchServiceOptions.BingLocalOptions -> "bing"
    is SearchServiceOptions.JinaOptions -> "jina"
    is SearchServiceOptions.SearXNGOptions -> "searxng:${url.trimEnd('/')}"
    else -> id.toString()
}

private fun SearchServiceOptions.deepReadServiceLabel(): String =
    SearchServiceOptions.TYPES.entries
        .firstOrNull { (type, _) -> type.isInstance(this) }
        ?.value
        ?: this::class.simpleName.orEmpty().ifBlank { "unknown" }

private fun SearchServiceOptions.deepReadSearchPriority(): Int = when (this) {
    is SearchServiceOptions.PerplexityOptions -> if (apiKey.isNotBlank()) 100 else 0
    is SearchServiceOptions.TavilyOptions -> if (apiKey.isNotBlank()) 95 else 0
    is SearchServiceOptions.ZhipuOptions -> if (apiKey.isNotBlank()) 90 else 0
    is SearchServiceOptions.BraveOptions -> if (apiKey.isNotBlank()) 85 else 0
    is SearchServiceOptions.ExaOptions -> if (apiKey.isNotBlank()) 82 else 0
    is SearchServiceOptions.SerperOptions -> if (apiKey.isNotBlank()) 80 else 0
    is SearchServiceOptions.SerpApiOptions -> if (apiKey.isNotBlank()) 78 else 0
    is SearchServiceOptions.MetasoOptions -> if (apiKey.isNotBlank()) 76 else 0
    is SearchServiceOptions.BochaOptions -> if (apiKey.isNotBlank()) 74 else 0
    is SearchServiceOptions.FirecrawlOptions -> if (apiKey.isNotBlank()) 72 else 0
    is SearchServiceOptions.JinaOptions -> if (apiKey.isNotBlank()) 70 else 25
    is SearchServiceOptions.LinkUpOptions -> if (apiKey.isNotBlank()) 68 else 0
    is SearchServiceOptions.RikkaHubOptions -> if (apiKey.isNotBlank()) 66 else 0
    is SearchServiceOptions.GrokOptions -> if (apiKey.isNotBlank()) 64 else 0
    is SearchServiceOptions.SearXNGOptions -> if (url.isNotBlank()) 20 else 0
    is SearchServiceOptions.BingLocalOptions -> 10
    is SearchServiceOptions.OllamaOptions -> if (apiKey.isNotBlank()) 5 else 0
}
