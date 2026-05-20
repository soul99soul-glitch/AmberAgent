package me.rerere.rikkahub.data.agent.board.hotlist.deepread

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import kotlin.uuid.Uuid

class DeepReadAgent(
    private val settingsStore: SettingsAggregator,
    private val providerManager: ProviderManager,
    private val hotListRepository: HotListRepository,
    private val json: Json,
    private val client: OkHttpClient,
) {
    private val topicMutexes = mutableMapOf<String, Mutex>()
    private val mutexRegistryLock = Any()

    private fun mutexFor(topicId: String): Mutex = synchronized(mutexRegistryLock) {
        topicMutexes.getOrPut(topicId) { Mutex() }
    }

    suspend fun run(
        topicId: String,
        topicTitle: String,
        force: Boolean = false,
    ): Result<DeepReadOutput> = mutexFor(topicId).withLock {
        runUnlocked(topicId, topicTitle, force)
    }

    suspend fun runSection(
        topicId: String,
        topicTitle: String,
        stage: DeepReadGenerationStage,
    ): Result<DeepReadOutput> = mutexFor(topicId).withLock {
        runSectionUnlocked(topicId, topicTitle, stage)
    }

    private suspend fun runUnlocked(
        topicId: String,
        topicTitle: String,
        force: Boolean,
    ): Result<DeepReadOutput> {
        val seedSources = hotListRepository.getHotTopic(topicId)
            ?.sources
            .orEmpty()
            .toDeepReadSources(topicTitle)

        val starting = if (force) {
            hotListRepository.clearDeepRead(topicId)
            DeepReadOutput()
        } else {
            hotListRepository.getFreshDeepRead(topicId, title = topicTitle)
                ?.let { DeepReadSanitizer.sanitize(it, seedSources, topicTitle) }
                ?.withInferredSectionStates()
                ?: DeepReadOutput()
        }
        if (!force && starting.isComplete()) {
            return Result.success(starting)
        }

        val settings = settingsStore.settingsFlow.value
        val sources = collectSources(settings, topicTitle, seedSources)
        if (sources.isEmpty()) {
            val message = "没有抓到足够的正文来源，无法生成深度阅读。请检查搜索源或稍后重试。"
            val firstPending = DeepReadGenerationStage.entries
                .firstOrNull { starting.statusOf(it) != DeepReadSectionStatus.READY }
                ?: DeepReadGenerationStage.OVERVIEW
            val marked = starting.withSectionStatus(firstPending, DeepReadSectionStatus.FAILED, message)
            hotListRepository.saveDeepRead(topicId, topicTitle, marked)
            return Result.failure(IllegalStateException(message))
        }

        var output = starting
        var firstFailure: Throwable? = null
        DeepReadGenerationStage.entries.forEach { stage ->
            if (output.statusOf(stage) == DeepReadSectionStatus.READY) return@forEach
            val stageResult = runStage(
                settings = settings,
                topicId = topicId,
                topicTitle = topicTitle,
                sources = sources,
                stage = stage,
                existing = output,
            )
            when (stageResult) {
                is StageOutcome.Success -> output = stageResult.output
                is StageOutcome.Failure -> {
                    output = stageResult.output
                    if (firstFailure == null) firstFailure = IllegalStateException(stageResult.message)
                }
            }
        }

        val failure = firstFailure
        if (failure != null) {
            return Result.failure(failure)
        }

        // Best-effort Chinese repair only when all sections READY but content is mostly English.
        if (output.isComplete() && !output.hasEnoughChinese()) {
            repairChinese(settings, topicTitle, sources, output)?.let { repaired ->
                val finalized = repaired.copy(sectionStates = output.sectionStates)
                    .let { it.copy(generationComplete = it.isComplete()) }
                hotListRepository.saveDeepRead(topicId, topicTitle, finalized)
                output = finalized
            }
        }

        return Result.success(output)
    }

    private suspend fun runSectionUnlocked(
        topicId: String,
        topicTitle: String,
        stage: DeepReadGenerationStage,
    ): Result<DeepReadOutput> {
        val seedSources = hotListRepository.getHotTopic(topicId)
            ?.sources
            .orEmpty()
            .toDeepReadSources(topicTitle)
        val current = hotListRepository.getFreshDeepRead(topicId, title = topicTitle)
            ?.let { DeepReadSanitizer.sanitize(it, seedSources, topicTitle) }
            ?.withInferredSectionStates()
            ?: DeepReadOutput()
        val settings = settingsStore.settingsFlow.value
        val sources = collectSources(settings, topicTitle, seedSources)
        if (sources.isEmpty()) {
            val message = "没有抓到足够的正文来源，无法重试该段。"
            val marked = current.withSectionStatus(stage, DeepReadSectionStatus.FAILED, message)
            hotListRepository.saveDeepRead(topicId, topicTitle, marked)
            return Result.failure(IllegalStateException(message))
        }
        return when (val outcome = runStage(settings, topicId, topicTitle, sources, stage, current)) {
            is StageOutcome.Success -> Result.success(outcome.output)
            is StageOutcome.Failure -> Result.failure(IllegalStateException(outcome.message))
        }
    }

    private suspend fun runStage(
        settings: Settings,
        topicId: String,
        topicTitle: String,
        sources: List<DeepReadSource>,
        stage: DeepReadGenerationStage,
        existing: DeepReadOutput,
    ): StageOutcome {
        val running = existing.withSectionStatus(stage, DeepReadSectionStatus.RUNNING)
        hotListRepository.saveDeepRead(topicId, topicTitle, running)

        val previousJson = if (existing.hasAnyReadySection()) {
            json.encodeToString(existing.copy(sectionStates = emptyMap(), generationComplete = false))
        } else {
            null
        }
        val callOutcome = callStageModel(
            settings = settings,
            topicTitle = topicTitle,
            sources = sources,
            stage = stage,
            previousJson = previousJson,
        )
        if (callOutcome.raw == null) {
            val message = callOutcome.errorMessage ?: "${stage.label}生成失败，请稍后重试。"
            val failed = existing.withSectionStatus(stage, DeepReadSectionStatus.FAILED, message)
            hotListRepository.saveDeepRead(topicId, topicTitle, failed)
            return StageOutcome.Failure(failed, message)
        }

        val raw = callOutcome.raw
        val parsed = DeepReadOutputParser.parse(raw, json)
            ?: repairJson(settings, topicTitle, raw)
        if (parsed == null) {
            val message = "${stage.label}输出格式不稳定，请重试或切换模型。"
            val failed = existing.withSectionStatus(stage, DeepReadSectionStatus.FAILED, message)
            hotListRepository.saveDeepRead(topicId, topicTitle, failed)
            return StageOutcome.Failure(failed, message)
        }

        val merged = DeepReadSanitizer.sanitize(existing.mergeWith(parsed), sources, topicTitle)
            .withSectionStatus(stage, DeepReadSectionStatus.READY)
        hotListRepository.saveDeepRead(topicId, topicTitle, merged)
        return StageOutcome.Success(merged)
    }

    private sealed interface StageOutcome {
        val output: DeepReadOutput
        data class Success(override val output: DeepReadOutput) : StageOutcome
        data class Failure(override val output: DeepReadOutput, val message: String) : StageOutcome
    }

    private data class StageCallResult(val raw: String?, val errorMessage: String?)

    private suspend fun collectSources(
        settings: Settings,
        topicTitle: String,
        seedSources: List<DeepReadSource>,
    ): List<DeepReadSource> =
        withTimeoutOrNull(SOURCE_COLLECTION_TIMEOUT_MS) {
            coroutineScope {
                val enabled = settings.enabledDeepReadSearchServices().take(MAX_SEARCH_SERVICES)
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
                val seedEnriched = seedSources.map { source ->
                    async { enrichSeedSource(source) }
                }.awaitAll()
                    .filter { it.content.trim().length >= MIN_SOURCE_CHARS }
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
                            content = (scraped ?: direct ?: item.text).ifBlank { item.title }.take(SOURCE_EXCERPT_LIMIT),
                            publishedAt = item.publishedAt,
                            images = (item.images + listOfNotNull(metaImage))
                                .filter { it.startsWith("http") }
                                .distinct()
                                .take(3),
                        )
                    }
                }.awaitAll()
                    .filter { it.title.isNotBlank() && it.url.isNotBlank() && it.content.trim().length >= MIN_SOURCE_CHARS }

                val combined = (enriched + seedEnriched)
                    .distinctBy { source -> source.url.ifBlank { source.title } }
                    .take(MAX_SOURCES)
                if (combined.isEmpty()) {
                    emptyList()
                } else {
                    combined
                }
            }
        } ?: emptyList()

    private suspend fun enrichSeedSource(source: DeepReadSource): DeepReadSource {
        val direct = source.url.takeIf { it.startsWith("http") }?.let { url ->
            withTimeoutOrNull(DIRECT_FETCH_TIMEOUT_MS) { fetchReadableText(url) }
        }
        val metaImage = source.url.takeIf { it.startsWith("http") }?.let { url ->
            withTimeoutOrNull(OG_IMAGE_TIMEOUT_MS) { fetchOpenGraphImage(url) }
        }
        return source.copy(
            content = (direct ?: source.content).take(SOURCE_EXCERPT_LIMIT),
            images = (source.images + listOfNotNull(metaImage)).distinct().take(3),
        )
    }

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

    private fun buildDeepReadQueries(topicTitle: String): List<String> {
        val lower = topicTitle.lowercase()
        val queries = mutableListOf(
            topicTitle,
            "$topicTitle 前因后果 时间线 背景",
            "$topicTitle 核心矛盾 争议 影响",
            "$topicTitle background timeline context controversy",
        )
        if (listOf("gemini", "google", "openai", "claude", "deepseek", "gpt", "llm", "大模型", "模型", "flash").any { it in lower }) {
            queries += "$topicTitle 发布 价格 跑分 性能 评价"
            queries += "$topicTitle pricing benchmark performance model card"
            queries += "$topicTitle official announcement availability API pricing"
        }
        if ("gemini" in lower || "google" in lower) {
            queries += "Google I/O 2026 $topicTitle Gemini announcement pricing benchmarks"
            queries += "$topicTitle site:blog.google"
            queries += "$topicTitle site:deepmind.google model card"
        }
        return queries.distinct()
    }

    private fun List<HotTopicSource>.toDeepReadSources(topicTitle: String): List<DeepReadSource> =
        map { source ->
            val url = source.url?.takeIf { it.startsWith("http") }.orEmpty()
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

    private suspend fun fetchReadableText(url: String): String? =
        withContext(Dispatchers.IO) {
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

    private suspend fun callStageModel(
        settings: Settings,
        topicTitle: String,
        sources: List<DeepReadSource>,
        stage: DeepReadGenerationStage,
        previousJson: String?,
    ): StageCallResult {
        val prompt = DeepReadPrompt.buildStage(
            topicTitle = topicTitle,
            sources = sources,
            stage = stage,
            previousJson = previousJson,
        )
        Log.i(TAG, "deep read stage ${stage.name} start sources=${sources.size} promptChars=${prompt.length}")
        val initial = callModel(
            settings = settings,
            prompt = prompt,
            maxTokens = stage.maxTokens(),
        )
        if (initial.raw != null || stage != DeepReadGenerationStage.OVERVIEW) return initial

        val compactPrompt = DeepReadPrompt.buildStage(
            topicTitle = topicTitle,
            sources = sources,
            stage = stage,
            previousJson = null,
            compact = true,
        )
        Log.w(TAG, "deep read overview failed; retrying compact promptChars=${compactPrompt.length}")
        return callModel(
            settings = settings,
            prompt = compactPrompt,
            maxTokens = OVERVIEW_COMPACT_MAX_TOKENS,
        )
    }

    private suspend fun callModel(
        settings: Settings,
        prompt: String,
        systemInstruction: String = "你是 AmberAgent 的新闻深读编辑。仅输出合法 JSON。",
        maxTokens: Int = DEEP_READ_MODEL_MAX_TOKENS,
    ): StageCallResult {
        val model = resolveModel(settings)
            ?: return StageCallResult(null, "请先配置聊天模型（设置 → 模型）")
        val provider = model.findProvider(settings.providers)
            ?: return StageCallResult(null, "模型 ${model.displayName} 的提供商不可用")
        return try {
            val response = try {
                generateDeepReadText(provider, model, systemInstruction, prompt, maxTokens)
            } catch (error: Throwable) {
                if (maxTokens > DEEP_READ_PROVIDER_SAFE_MAX_TOKENS && error.looksLikeMaxTokenRejection()) {
                    Log.w(TAG, "deep read model rejected maxTokens=$maxTokens; retrying with $DEEP_READ_PROVIDER_SAFE_MAX_TOKENS", error)
                    generateDeepReadText(provider, model, systemInstruction, prompt, DEEP_READ_PROVIDER_SAFE_MAX_TOKENS)
                } else {
                    throw error
                }
            }
            val text = response.choices.firstOrNull()?.message?.toText()
            if (text.isNullOrBlank()) {
                StageCallResult(null, "模型返回空内容，请重试或切换模型")
            } else {
                StageCallResult(text, null)
            }
        } catch (error: TimeoutCancellationException) {
            Log.w(TAG, "deep read model call timed out")
            StageCallResult(null, "模型生成超时，请稍后重试")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "deep read model call failed", error)
            StageCallResult(null, formatDeepReadModelFailure(error))
        }
    }

    private suspend fun generateDeepReadText(
        provider: me.rerere.ai.provider.ProviderSetting,
        model: me.rerere.ai.provider.Model,
        systemInstruction: String,
        prompt: String,
        maxTokens: Int,
    ) = withTimeout(MODEL_TIMEOUT_MS) {
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

    private fun Throwable.looksLikeMaxTokenRejection(): Boolean {
        val text = message.orEmpty().lowercase()
        return listOf(
            "max_tokens",
            "max output",
            "maxoutputtokens",
            "output token",
            "maximum token",
            "too many tokens",
        ).any { it in text }
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
            maxTokens = DEEP_READ_REPAIR_MAX_TOKENS,
        ).raw ?: return null
        return DeepReadOutputParser.parse(raw, json)
            ?.let { DeepReadSanitizer.sanitize(it, sources, topicTitle) }
            ?.takeIf { it.hasEnoughChinese() }
    }

    private suspend fun repairJson(
        settings: Settings,
        topicTitle: String,
        rawOutput: String,
    ): DeepReadOutput? {
        val raw = callModel(
            settings = settings,
            prompt = DeepReadPrompt.repairJson(topicTitle, rawOutput.take(RAW_REPAIR_LIMIT)),
            systemInstruction = "你是 AmberAgent 的 JSON 修复器。仅输出合法 JSON。",
            maxTokens = DEEP_READ_REPAIR_MAX_TOKENS,
        ).raw ?: return null
        return DeepReadOutputParser.parse(raw, json)
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

    private fun String.extractOpenGraphImage(): String? =
        listOf(
            Regex("""property=["']og:image["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""name=["']twitter:image["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""content=["']([^"']+)["'][^>]*property=["']og:image["']""", RegexOption.IGNORE_CASE),
        ).asSequence()
            .flatMap { pattern -> pattern.findAll(this).map { it.groupValues[1] } }
            .firstOrNull { it.startsWith("http") }

    private fun String.hasCjk(): Boolean = any { it in '\u4e00'..'\u9fff' }

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

    private fun DeepReadOutput?.mergeWith(update: DeepReadOutput): DeepReadOutput {
        val base = this ?: DeepReadOutput()
        val cleaned = update.withoutPromptPlaceholders()
        return base.copy(
            topicType = cleaned.topicType.takeIf { it.isRealStageContent() } ?: base.topicType,
            summary = cleaned.summary.takeIf { it.isRealStageContent() } ?: base.summary,
            keyEntities = cleaned.keyEntities.ifEmpty { base.keyEntities },
            timeline = cleaned.timeline?.takeIf { it.isNotEmpty() } ?: base.timeline,
            corePoints = cleaned.corePoints?.takeIf { it.isNotEmpty() } ?: base.corePoints,
            analysis = cleaned.analysis.takeIf { analysis ->
                !analysis.coreDispute.isNullOrBlank() ||
                    !analysis.implications.isNullOrBlank() ||
                    analysis.perspectives.isNotEmpty() ||
                    analysis.quotes.isNotEmpty()
            } ?: base.analysis,
            extendedReading = cleaned.extendedReading.ifEmpty { base.extendedReading },
            heroImageQuery = cleaned.heroImageQuery?.takeIf { it.isRealStageContent() } ?: base.heroImageQuery,
            heroImageUrl = cleaned.heroImageUrl?.takeIf { it.startsWith("http") } ?: base.heroImageUrl,
            heroCaption = cleaned.heroCaption?.takeIf { it.isRealStageContent() } ?: base.heroCaption,
            imageAssets = cleaned.imageAssets.ifEmpty { base.imageAssets },
            references = cleaned.references.ifEmpty { base.references },
        )
    }

    private fun DeepReadOutput.withoutPromptPlaceholders(): DeepReadOutput = copy(
        keyEntities = keyEntities.filter { it.isRealStageContent() },
        timeline = timeline
            ?.mapNotNull { event ->
                val cleanedEvent = event.copy(
                    date = event.date.takeIf { it.isRealStageContent() }.orEmpty(),
                    event = event.event.takeIf { it.isRealStageContent() }.orEmpty(),
                    imageUrl = event.imageUrl?.takeIf { it.startsWith("http") },
                    imageCaption = event.imageCaption?.takeIf { it.isRealStageContent() },
                )
                cleanedEvent.takeIf { it.event.isNotBlank() }
            }
            ?.takeIf { it.isNotEmpty() },
        corePoints = corePoints
            ?.mapNotNull { point ->
                val cleanedPoint = point.copy(
                    point = point.point.takeIf { it.isRealStageContent() }.orEmpty(),
                    supporting = point.supporting?.takeIf { it.isRealStageContent() },
                    imageUrl = point.imageUrl?.takeIf { it.startsWith("http") },
                    imageCaption = point.imageCaption?.takeIf { it.isRealStageContent() },
                )
                cleanedPoint.takeIf { it.point.isNotBlank() || !it.supporting.isNullOrBlank() }
            }
            ?.takeIf { it.isNotEmpty() },
        analysis = analysis.copy(
            coreDispute = analysis.coreDispute?.takeIf { it.isRealStageContent() },
            implications = analysis.implications?.takeIf { it.isRealStageContent() },
            perspectives = analysis.perspectives
                .mapNotNull { perspective ->
                    val cleaned = perspective.copy(
                        viewpoint = perspective.viewpoint.takeIf { it.isRealStageContent() }.orEmpty(),
                        holder = perspective.holder?.takeIf { it.isRealStageContent() },
                    )
                    cleaned.takeIf { it.viewpoint.isNotBlank() }
                },
            quotes = analysis.quotes
                .mapNotNull { quote ->
                    val cleaned = quote.copy(
                        text = quote.text.takeIf { it.isRealStageContent() }.orEmpty(),
                        attribution = quote.attribution?.takeIf { it.isRealStageContent() },
                    )
                    cleaned.takeIf { it.text.isNotBlank() }
                },
        ),
        extendedReading = extendedReading
            .filter { it.title.isRealStageContent() && it.url.startsWith("http") }
            .map { it.copy(source = it.source?.takeIf { source -> source.isRealStageContent() }) },
        heroImageQuery = heroImageQuery?.takeIf { it.isRealStageContent() },
        heroImageUrl = heroImageUrl?.takeIf { it.startsWith("http") },
        heroCaption = heroCaption?.takeIf { it.isRealStageContent() },
        imageAssets = imageAssets
            .filter { it.url.startsWith("http") }
            .map { asset ->
                asset.copy(
                    caption = asset.caption?.takeIf { it.isRealStageContent() },
                    source = asset.source?.takeIf { it.isRealStageContent() },
                    relatedEntities = asset.relatedEntities.filter { it.isRealStageContent() },
                    qualityHint = asset.qualityHint?.takeIf { it.isRealStageContent() },
                )
            },
        references = references
            .filter { it.title.isRealStageContent() && it.url.startsWith("http") }
            .map { it.copy(source = it.source?.takeIf { source -> source.isRealStageContent() }) },
    )

    private fun String.isRealStageContent(): Boolean {
        val text = trim()
        if (text.isBlank()) return false
        if (text in setOf(
            "继承上一阶段",
            "继承并补充",
            "保留已有导语",
            "日期或时间",
            "连贯叙事事件",
            "关键脉络",
            "为什么重要",
            "核心分歧，可为空",
            "观点",
            "持有方",
            "影响分析，可为空",
            "短引用，不超过40字",
            "来源或人物",
            "中文标题",
            "URL",
            "来源",
            "适合查找真实新闻配图的搜索词",
            "只能使用来源 images 中的 URL，可为空",
            "图片说明，可为空",
            "中文图注",
            "实体",
            "event|opinion|product|person",
        )) return false
        return !text.contains("可为空") && !text.contains("继承")
    }

    companion object {
        private const val TAG = "DeepReadAgent"
        private const val SOURCE_COLLECTION_TIMEOUT_MS = 24_000L
        private const val SEARCH_TIMEOUT_MS = 7_000L
        private const val SCRAPE_TIMEOUT_MS = 4_000L
        private const val OG_IMAGE_TIMEOUT_MS = 2_000L
        private const val DIRECT_FETCH_TIMEOUT_MS = 4_000L
        private const val DIRECT_FETCH_MAX_BYTES = 768_000L
        private const val OG_IMAGE_HTML_CHAR_LIMIT = 192_000
        private const val MODEL_TIMEOUT_MS = 70_000L
        private const val DEEP_READ_MODEL_MAX_TOKENS = 40_000
        private const val OVERVIEW_COMPACT_MAX_TOKENS = 1_200
        private const val DEEP_READ_REPAIR_MAX_TOKENS = 40_000
        private const val DEEP_READ_PROVIDER_SAFE_MAX_TOKENS = 12_000
        private const val MAX_SEARCH_SERVICES = 2
        private const val MAX_SEARCH_RESULTS = 10
        private const val SEARCH_RESULTS_PER_QUERY = 3
        private const val MAX_SCRAPE_RESULTS = 6
        private const val MAX_META_IMAGE_RESULTS = 4
        private const val MAX_SEED_SOURCES = 4
        private const val MAX_SOURCES = 10
        private const val SOURCE_EXCERPT_LIMIT = 1_600
        private const val MIN_SOURCE_CHARS = 280
        private const val RAW_REPAIR_LIMIT = 18_000
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"
    }
}

private data class SearchBucket(
    val query: String,
    val items: List<SearchResult.SearchResultItem>,
)

private fun DeepReadGenerationStage.maxTokens(): Int = when (this) {
    DeepReadGenerationStage.OVERVIEW -> 1_800
    DeepReadGenerationStage.NARRATIVE -> 4_000
    DeepReadGenerationStage.ANALYSIS -> 4_000
    DeepReadGenerationStage.EXTENDED_READING -> 2_000
}

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
