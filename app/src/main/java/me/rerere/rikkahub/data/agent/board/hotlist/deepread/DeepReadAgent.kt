package me.rerere.rikkahub.data.agent.board.hotlist.deepread

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.agent.board.hotlist.HotTopicSource
import me.rerere.rikkahub.data.agent.board.hotlist.HotListRepository
import me.rerere.rikkahub.data.agent.board.hotlist.presentationTitle
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.datastore.resolveTaskChatModel
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchResult
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLEncoder
import kotlin.uuid.Uuid

class DeepReadAgent(
    private val settingsStore: SettingsAggregator,
    private val providerManager: ProviderManager,
    private val hotListRepository: HotListRepository,
    private val json: Json,
    private val client: OkHttpClient,
) {
    suspend fun run(
        topicId: String,
        topicTitle: String,
        force: Boolean = false,
    ): Result<DeepReadOutput> {
        val seedSources = hotListRepository.getHotTopic(topicId)
            ?.sources
            .orEmpty()
            .toDeepReadSources(topicTitle)
        if (!force) {
            hotListRepository.getFreshDeepRead(topicId)?.let { cached ->
                return Result.success(DeepReadSanitizer.sanitize(cached, seedSources, topicTitle))
            }
        }

        val settings = settingsStore.settingsFlow.value
        val sources = collectSources(settings, topicTitle, seedSources)
        if (sources.isEmpty()) {
            return Result.failure(IllegalStateException("没有可用搜索服务或搜索结果为空"))
        }

        val prompt = DeepReadPrompt.build(topicTitle, sources)
        val raw = callModel(settings, prompt)
            ?: return Result.failure(IllegalStateException(lastCallFailureReason ?: "深度阅读生成失败，请稍后重试"))
        val parsed = DeepReadOutputParser.parse(raw, json)
        if (parsed == null) {
            Log.w(TAG, "deep read JSON parse failed; using source fallback")
        }
        var shouldCache = parsed != null
        var output = parsed
            ?.let { DeepReadSanitizer.sanitize(it, sources, topicTitle) }
            ?.takeIf { it.hasReadableArticle() }
            ?: run {
                shouldCache = false
                fallbackOutput(topicTitle, sources, if (parsed == null) "模型输出格式不稳定，已切换为来源兜底稿" else null)
            }
        if (!output.hasEnoughChinese()) {
            output = repairChinese(settings, topicTitle, sources, output)
                ?: output.withChineseFallback(topicTitle, sources).also { shouldCache = false }
        }
        if (!output.hasReadableArticle()) {
            return Result.failure(IllegalStateException("深度阅读内容不足，请稍后重试"))
        }
        if (shouldCache) {
            hotListRepository.saveDeepRead(topicId, topicTitle, output)
        }
        return Result.success(output)
    }

    private suspend fun collectSources(
        settings: Settings,
        topicTitle: String,
        seedSources: List<DeepReadSource>,
    ): List<DeepReadSource> =
        withTimeoutOrNull(SOURCE_COLLECTION_TIMEOUT_MS) {
            coroutineScope {
                val enabled = settings.enabledDeepReadSearchServices().take(MAX_SEARCH_SERVICES)
                if (enabled.isEmpty()) return@coroutineScope seedSources.take(MAX_SOURCES)
                val queries = buildDeepReadQueries(topicTitle)

                val searchResults = enabled.flatMap { service ->
                    queries.map { query ->
                        async {
                            try {
                                withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                                    searchWithService(service, query, SEARCH_RESULTS_PER_QUERY)
                                        .getOrNull()
                                        ?.let { result -> SearchBucket(query, result.items) }
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

                val scrapeService = enabled.firstOrNull()
                val enriched = searchResults.mapIndexed { index, item ->
                    async {
                        val shouldScrape = scrapeService != null && index < MAX_SCRAPE_RESULTS
                        val scraped = if (shouldScrape) {
                            try {
                                withTimeoutOrNull(SCRAPE_TIMEOUT_MS) {
                                    scrapeWithService(scrapeService, item.url)
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Throwable) {
                                null
                            }
                        } else {
                            null
                        }
                        val metaImage = if (index < MAX_META_IMAGE_RESULTS) {
                            try {
                                fetchOpenGraphImage(item.url)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Throwable) {
                                null
                            }
                        } else {
                            null
                        }
                        DeepReadSource(
                            title = item.title,
                            url = item.url,
                            source = domainOf(item.url),
                            content = (scraped ?: item.text).ifBlank { item.title }.take(SOURCE_EXCERPT_LIMIT),
                            publishedAt = item.publishedAt,
                            images = (item.images + listOfNotNull(metaImage))
                                .filter { it.startsWith("http") }
                                .distinct()
                                .take(3),
                        )
                    }
                }.awaitAll()
                    .filter { it.title.isNotBlank() && it.url.isNotBlank() && it.content.isNotBlank() }

                (seedSources + enriched)
                    .distinctBy { source -> source.url.ifBlank { source.title } }
                    .take(MAX_SOURCES)
            }
        } ?: seedSources.take(MAX_SOURCES)

    private fun interleaveSearchResults(buckets: List<SearchBucket>): List<SearchResult.SearchResultItem> {
        val queryGroups = buckets
            .groupBy { it.query }
            .values
            .map { group -> group.flatMap { it.items }.distinctBy { it.url } }
            .filter { it.isNotEmpty() }
        val merged = mutableListOf<SearchResult.SearchResultItem>()
        var index = 0
        while (merged.size < MAX_SEARCH_RESULTS && queryGroups.any { index < it.size }) {
            queryGroups.forEach { items ->
                if (merged.size < MAX_SEARCH_RESULTS && index < items.size) {
                    val item = items[index]
                    if (merged.none { it.url == item.url }) {
                        merged += item
                    }
                }
            }
            index++
        }
        return merged
    }

    private fun buildDeepReadQueries(topicTitle: String): List<String> =
        listOf(
            topicTitle,
            "$topicTitle 前因后果 时间线 背景",
            "$topicTitle 核心矛盾 争议 影响",
            "$topicTitle background timeline context controversy",
        ).distinct()

    private fun List<HotTopicSource>.toDeepReadSources(topicTitle: String): List<DeepReadSource> =
        map { source ->
            val url = source.url?.takeIf { it.startsWith("http") }
                ?: "https://www.google.com/search?q=${URLEncoder.encode(source.presentationTitle, "UTF-8")}"
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

    private suspend fun fetchOpenGraphImage(url: String): String? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(OG_IMAGE_TIMEOUT_MS) {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withTimeoutOrNull null
                    response.body.charStream().use { reader ->
                        val buffer = CharArray(OG_IMAGE_HTML_CHAR_LIMIT)
                        val read = reader.read(buffer).coerceAtLeast(0)
                        String(buffer, 0, read).extractOpenGraphImage()
                    }
                }
            }
        }

    private var lastCallFailureReason: String? = null

    private suspend fun callModel(
        settings: Settings,
        prompt: String,
        systemInstruction: String = "你是 AmberAgent 的新闻深读编辑。仅输出合法 JSON。",
        maxTokens: Int = 1_800,
    ): String? {
        val model = resolveModel(settings)
        if (model == null) {
            lastCallFailureReason = "请先配置聊天模型（设置 → 模型）"
            return null
        }
        val provider = model.findProvider(settings.providers)
        if (provider == null) {
            lastCallFailureReason = "模型 ${model.displayName} 的提供商不可用"
            return null
        }
        lastCallFailureReason = null
        return try {
            val response = withTimeout(MODEL_TIMEOUT_MS) {
                providerManager.getProviderByType(provider).generateText(
                    providerSetting = provider,
                    messages = listOf(
                        UIMessage.system(systemInstruction),
                        UIMessage.user(prompt),
                    ),
                    params = TextGenerationParams(
                        model = model,
                        temperature = 0.2f,
                        maxTokens = maxTokens,
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                    ),
                )
            }
            response.choices.firstOrNull()?.message?.toText()
        } catch (error: TimeoutCancellationException) {
            Log.w(TAG, "deep read model call timed out")
            lastCallFailureReason = "模型生成超时，请稍后重试"
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "deep read model call failed", error)
            lastCallFailureReason = formatDeepReadModelFailure(error)
            null
        }
    }

    private suspend fun repairChinese(
        settings: Settings,
        topicTitle: String,
        sources: List<DeepReadSource>,
        output: DeepReadOutput,
    ): DeepReadOutput? {
        val raw = callModel(
            settings = settings,
            prompt = DeepReadPrompt.repairChinese(topicTitle, json.encodeToString(output)),
            systemInstruction = "你是 AmberAgent 的中文深度阅读修稿编辑。仅输出合法 JSON。",
            maxTokens = 1_600,
        ) ?: return null
        return DeepReadOutputParser.parse(raw, json)
            ?.let { DeepReadSanitizer.sanitize(it, sources, topicTitle) }
            ?.takeIf { it.hasReadableArticle() && it.hasEnoughChinese() }
    }

    private fun resolveModel(settings: Settings): me.rerere.ai.provider.Model? {
        val boardModelIdStr = settings.agentRuntime.todayBoard.boardModelId
        val specific = boardModelIdStr
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?.let { settings.resolveTaskChatModel(it) }
        return specific ?: settings.resolveTaskChatModel(settings.chatModelId)
    }

    private fun domainOf(url: String): String? =
        runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()

    private fun fallbackOutput(
        topicTitle: String,
        sources: List<DeepReadSource>,
        reason: String? = null,
    ): DeepReadOutput {
        val cleanedSources = sources
            .filter { it.title.isNotBlank() || it.content.isNotBlank() }
            .take(MAX_SOURCES)
        val reading = cleanedSources
            .filter { it.url.startsWith("http") }
            .mapIndexed { index, source -> ReadingLink(source.chineseSafeTitle(topicTitle, index), source.url, source.source) }
            .distinctBy { it.url }
            .take(8)
        val sourceNames = cleanedSources.mapNotNull { it.source }.distinct().take(4)
        val imageAssets = cleanedSources
            .flatMap { source ->
                source.images.map { image ->
                    DeepReadImageAsset(
                        url = image,
                        caption = "与「$topicTitle」相关的来源图片",
                        source = source.source,
                        qualityHint = "context",
                    )
                }
            }
            .filter { it.url.startsWith("http") }
            .distinctBy { it.url }
            .take(6)
        val summary = buildString {
            append("围绕「")
            append(topicTitle)
            append("」，当前深度阅读已先基于")
            append(if (sourceNames.isEmpty()) "热榜和搜索来源" else sourceNames.joinToString("、"))
            append("整理出基础脉络。")
            if (!reason.isNullOrBlank()) {
                append(reason)
                append("。")
            }
            append("当前页面会优先呈现可确认的中文脉络，来源链接统一收在扩展阅读中。")
        }
        val points = buildFallbackCorePoints(topicTitle, cleanedSources, sourceNames)
        return DeepReadOutput(
            topicType = "event",
            summary = summary,
            keyEntities = sourceNames,
            corePoints = points,
            heroImageUrl = imageAssets.firstOrNull()?.url,
            heroCaption = imageAssets.firstOrNull()?.caption,
            imageAssets = imageAssets,
            analysis = DeepAnalysis(
                coreDispute = "当前可抓取信息仍偏薄，暂时只能确认话题热度和若干来源线索，尚不足以支撑完整因果链判断。",
                perspectives = cleanedSources.take(3).map { source ->
                    Perspective(
                        holder = source.source ?: domainOf(source.url),
                        viewpoint = source.content.toChineseReadableSnippet().ifBlank {
                            "该来源提供了与话题相关的线索，但当前摘要不足以单独形成明确判断。"
                        },
                    )
                },
                implications = "如果需要更完整的深读，系统需要抓到更多正文级材料；在此之前，页面会把来源保留在扩展阅读中，避免用占位文字伪装成分析。",
                quotes = emptyList(),
            ),
            extendedReading = reading,
            references = reading,
        )
    }

    private fun buildFallbackCorePoints(
        topicTitle: String,
        sources: List<DeepReadSource>,
        sourceNames: List<String>,
    ): List<CorePoint> {
        if (sources.isEmpty()) {
            return listOf(
                CorePoint(
                    point = "可用信息不足以形成稳定脉络",
                    supporting = "当前还没有抓到足够的来源正文，因此先保留话题入口，避免把来源列表误写成深度分析。",
                )
            )
        }
        val sourceText = if (sourceNames.isEmpty()) "热榜和搜索来源" else sourceNames.joinToString("、")
        return listOf(
            CorePoint(
                point = "这个话题已经进入多来源关注区",
                supporting = "系统已从$sourceText 捕捉到相关线索，但现有摘要仍偏碎片化，需要进一步抽取正文才能形成完整时间轴。",
            ),
            CorePoint(
                point = "当前最重要的是分辨事实、背景与评论",
                supporting = "页面会把可追溯链接放在扩展阅读中；关键脉络只保留已经能被中文消化的判断，避免把英文标题或来源域名当作正文。",
            ),
            CorePoint(
                point = "后续深读应围绕「$topicTitle」补齐因果链",
                supporting = "更理想的稿件需要回答：事件从哪里开始、为什么现在被关注、各方争议是什么、后续可能影响谁。",
            ),
        )
    }

    private fun String.toReadableSnippet(maxLength: Int = 180): String =
        replace(Regex("\\s+"), " ")
            .trim()
            .take(maxLength)

    private fun String.toChineseReadableSnippet(maxLength: Int = 180): String {
        val snippet = toReadableSnippet(maxLength)
        if (snippet.hasCjk() || snippet.count { it in 'a'..'z' || it in 'A'..'Z' } < 50) return snippet
        return "该来源提供了相关背景线索，但当前抓取摘要不足以直接改写成可靠中文正文。"
    }

    private fun DeepReadOutput.withChineseFallback(topicTitle: String, sources: List<DeepReadSource>): DeepReadOutput =
        copy(
            summary = summary.takeIf { it.hasCjk() } ?: "围绕「$topicTitle」，当前深度阅读已整理出主要来源，页面正文会优先以中文脉络呈现。",
            corePoints = corePoints.orEmpty().mapIndexed { index, point ->
                CorePoint(
                    point = point.point.takeIf { it.hasCjk() } ?: "关于「$topicTitle」的关键线索 ${index + 1}",
                    supporting = point.supporting?.toChineseReadableSnippet()
                        ?: "这条线索需要继续结合来源正文来判断，不能只按原文标题复述。",
                )
            },
            analysis = analysis.copy(
                coreDispute = analysis.coreDispute?.takeIf { it.hasCjk() }
                    ?: "当前信息仍以外部来源摘要为主，完整背景需要等待更多稳定来源补齐。",
                perspectives = analysis.perspectives.mapIndexed { index, perspective ->
                    perspective.copy(
                        viewpoint = perspective.viewpoint.takeIf { it.hasCjk() }
                            ?: sources.getOrNull(index)?.content?.toChineseReadableSnippet()
                            ?: "来源显示这是值得继续跟进的技术和产业话题。",
                    )
                },
                implications = analysis.implications?.takeIf { it.hasCjk() }
                    ?: "这份内容优先保证中文可读和来源可追溯；点击扩展阅读仍会跳转到原始页面。",
            ),
        )

    private fun DeepReadSource.chineseSafeTitle(topicTitle: String, index: Int): String =
        title.takeIf { it.hasCjk() } ?: "关于「$topicTitle」的相关报道（${source ?: domainOf(url) ?: "外部站点"}）"

    private fun String.extractOpenGraphImage(): String? =
        listOf(
            Regex("""property=["']og:image["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""name=["']twitter:image["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""content=["']([^"']+)["'][^>]*property=["']og:image["']""", RegexOption.IGNORE_CASE),
        ).asSequence()
            .flatMap { pattern -> pattern.findAll(this).map { it.groupValues[1] } }
            .firstOrNull { it.startsWith("http") }

    private fun String.hasCjk(): Boolean = any { it in '\u4e00'..'\u9fff' }

    companion object {
        private const val TAG = "DeepReadAgent"
        private const val SOURCE_COLLECTION_TIMEOUT_MS = 18_000L
        private const val SEARCH_TIMEOUT_MS = 7_000L
        private const val SCRAPE_TIMEOUT_MS = 4_000L
        private const val OG_IMAGE_TIMEOUT_MS = 2_000L
        private const val OG_IMAGE_HTML_CHAR_LIMIT = 192_000
        private const val MODEL_TIMEOUT_MS = 70_000L
        private const val MAX_SEARCH_SERVICES = 2
        private const val MAX_SEARCH_RESULTS = 8
        private const val SEARCH_RESULTS_PER_QUERY = 3
        private const val MAX_SCRAPE_RESULTS = 4
        private const val MAX_META_IMAGE_RESULTS = 4
        private const val MAX_SEED_SOURCES = 4
        private const val MAX_SOURCES = 8
        private const val SOURCE_EXCERPT_LIMIT = 900
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"
    }
}

private data class SearchBucket(
    val query: String,
    val items: List<SearchResult.SearchResultItem>,
)

internal fun Settings.enabledDeepReadSearchServices(): List<SearchServiceOptions> =
    searchServices
        .filter { service -> searchEnabledServiceIds.any { it == service.id } }
        .take(4)

internal fun formatDeepReadModelFailure(error: Throwable): String =
    normalizeDeepReadFailureMessage(error.message.orEmpty())
        .ifBlank { error::class.simpleName ?: "模型请求失败" }

internal fun normalizeDeepReadFailureMessage(rawMessage: String): String {
    val message = rawMessage.replace(Regex("\\s+"), " ").trim()
    if (message.isBlank()) return ""
    val httpCode = Regex("""(?i)(?:response|http)\D{0,12}(\d{3})""")
        .find(message)
        ?.groupValues
        ?.getOrNull(1)
    val providerMessage = extractJsonStringField(message, "message")
        ?: extractJsonStringField(message, "error")
        ?: message
    val status = extractJsonStringField(message, "status")
    val reason = when (httpCode) {
        "400" -> "模型请求被拒绝"
        "401", "403" -> "模型鉴权或权限失败"
        "408" -> "模型请求超时"
        "429" -> "模型额度或频率受限"
        in setOf("500", "502", "503", "504") -> "模型服务暂时不可用"
        else -> "深度阅读生成失败"
    }
    val suggestion = deepReadFailureSuggestion(httpCode, providerMessage, status)
    return buildString {
        append(reason)
        if (httpCode != null) append("（HTTP ").append(httpCode).append("）")
        if (status != null) append("：").append(status)
        append("\n")
        append(providerMessage.take(800))
        if (suggestion.isNotBlank()) {
            append("\n\n")
            append(suggestion)
        }
    }
}

private fun deepReadFailureSuggestion(httpCode: String?, providerMessage: String, status: String?): String {
    val lower = "$providerMessage $status".lowercase()
    return when {
        httpCode == "400" && listOf("reject", "rejected", "safety", "policy", "blocked", "unsafe").any { it in lower } ->
            "可能是来源正文或提示词触发了模型安全策略。可以换一个模型，或减少来源正文后重试。"
        httpCode == "400" ->
            "请求被模型服务判定为无效。建议换模型或重新生成一次。"
        httpCode in setOf("401", "403") ->
            "请检查这个模型的 API Key、Base URL、模型名和账号权限。"
        httpCode == "429" ->
            "当前模型可能达到额度或频率限制，稍后重试或切换模型。"
        httpCode in setOf("500", "502", "503", "504") ->
            "这是模型服务端错误，稍后重试通常可以恢复。"
        else -> ""
    }
}

private fun extractJsonStringField(text: String, field: String): String? {
    val pattern = Regex(""""${Regex.escape(field)}"\s*:\s*"((?:\\.|[^"\\])*)"""")
    return pattern.find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?.jsonUnescape()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

private fun String.jsonUnescape(): String =
    replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
