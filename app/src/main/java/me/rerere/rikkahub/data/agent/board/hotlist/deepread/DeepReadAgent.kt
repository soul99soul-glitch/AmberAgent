package me.rerere.rikkahub.data.agent.board.hotlist.deepread

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.agent.board.hotlist.HotListRepository
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.datastore.resolveTaskChatModel
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchResult
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import java.net.URI
import kotlin.uuid.Uuid

class DeepReadAgent(
    private val settingsStore: SettingsAggregator,
    private val providerManager: ProviderManager,
    private val hotListRepository: HotListRepository,
    private val json: Json,
) {
    suspend fun run(
        topicId: String,
        topicTitle: String,
        force: Boolean = false,
    ): Result<DeepReadOutput> {
        if (!force) {
            hotListRepository.getFreshDeepRead(topicId)?.let { return Result.success(it) }
        }

        val settings = settingsStore.settingsFlow.value
        val sources = collectSources(settings, topicTitle)
        if (sources.isEmpty()) {
            return Result.failure(IllegalStateException("没有可用搜索服务或搜索结果为空"))
        }

        val prompt = DeepReadPrompt.build(topicTitle, sources)
        val raw = callModel(settings, prompt)
            ?: return Result.failure(IllegalStateException(lastCallFailureReason ?: "模型调用失败"))
        val parsed = DeepReadOutputParser.parse(raw, json)
            ?: return Result.failure(IllegalStateException("深度阅读 JSON 解析失败"))
        val output = DeepReadSanitizer.sanitize(parsed, sources)
        if (!output.hasReadableArticle()) {
            return Result.failure(IllegalStateException("深度阅读内容不足，请稍后重试"))
        }
        hotListRepository.saveDeepRead(topicId, topicTitle, output)
        return Result.success(output)
    }

    private suspend fun collectSources(settings: Settings, topicTitle: String): List<DeepReadSource> = coroutineScope {
        val enabled = settings.enabledDeepReadSearchServices()
        if (enabled.isEmpty()) return@coroutineScope emptyList()

        val searchResults = enabled.map { service ->
            async {
                try {
                    searchWithService(service, topicTitle)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Result.failure(error)
                }
            }
        }.awaitAll()
            .mapNotNull { it.getOrNull() }
            .flatMap { it.items }
            .distinctBy { it.url }
            .take(25)

        val top = searchResults.take(10)
        top.map { item ->
            async {
                val scraped = enabled.firstNotNullOfOrNull { options ->
                    try {
                        scrapeWithService(options, item.url)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        null
                    }
                }
                DeepReadSource(
                    title = item.title,
                    url = item.url,
                    source = domainOf(item.url),
                    content = (scraped ?: item.text).take(2_000),
                    publishedAt = item.publishedAt,
                    images = item.images.take(3),
                )
            }
        }.awaitAll()
            .filter { it.title.isNotBlank() && it.url.isNotBlank() && it.content.isNotBlank() }
    }

    private suspend fun searchWithService(
        options: SearchServiceOptions,
        topicTitle: String,
    ): Result<SearchResult> {
        val params = buildJsonObject {
            put("query", topicTitle)
            put("topic", "news")
        }
        val service = SearchService.getService(options)
        return service.search(params, SearchCommonOptions(resultSize = 8), options)
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

    private var lastCallFailureReason: String? = null

    private suspend fun callModel(settings: Settings, prompt: String): String? {
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
            val response = providerManager.getProviderByType(provider).generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.system("你是 AmberAgent 的新闻深读编辑。仅输出合法 JSON。"),
                    UIMessage.user(prompt),
                ),
                params = TextGenerationParams(
                    model = model,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            )
            response.choices.firstOrNull()?.message?.toText()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "deep read model call failed", error)
            lastCallFailureReason = error.message?.take(100) ?: error::class.simpleName
            null
        }
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

    companion object {
        private const val TAG = "DeepReadAgent"
    }
}

internal fun Settings.enabledDeepReadSearchServices(): List<SearchServiceOptions> =
    searchServices
        .filter { service -> searchEnabledServiceIds.any { it == service.id } }
        .take(4)
