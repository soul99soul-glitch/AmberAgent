package app.amber.feature.board.hotlist

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import app.amber.feature.board.boardRequestBodies
import app.amber.feature.board.boardRequestHeaders
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.datastore.resolveTaskChatModel
import kotlin.uuid.Uuid

class HotListTitleLocalizer(
    private val settingsStore: SettingsAggregator,
    private val providerManager: ProviderManager,
    private val json: Json,
) {
    suspend fun localize(
        snapshots: List<HotListProviderSnapshot>,
        previousSnapshots: Map<String, HotListProviderSnapshot> = emptyMap(),
    ): List<HotListProviderSnapshot> {
        val reusedSnapshots = reuseCachedTitles(snapshots, previousSnapshots)
        val targets = reusedSnapshots.flatMapIndexed { snapshotIndex, snapshot ->
            snapshot.items.mapIndexedNotNull { itemIndex, item ->
                if (item.needsChineseDisplayTitle()) {
                    TranslationTarget(
                        id = "$snapshotIndex:$itemIndex",
                        source = snapshot.providerName,
                        title = item.title,
                    )
                } else {
                    null
                }
            }
        }.take(MAX_TRANSLATED_TITLES)
        if (targets.isEmpty()) return reusedSnapshots

        val settings = settingsStore.settingsFlow.value
        val translations = translateTitles(settings, targets)
        if (translations.isEmpty()) return reusedSnapshots

        return reusedSnapshots.mapIndexed { snapshotIndex, snapshot ->
            snapshot.copy(
                items = snapshot.items.mapIndexed { itemIndex, item ->
                    translations["$snapshotIndex:$itemIndex"]
                        ?.takeIf { it.isNotBlank() }
                        ?.let { item.copy(displayTitle = it) }
                        ?: item
                }
            )
        }
    }

    private fun reuseCachedTitles(
        snapshots: List<HotListProviderSnapshot>,
        previousSnapshots: Map<String, HotListProviderSnapshot>,
    ): List<HotListProviderSnapshot> =
        snapshots.map { snapshot ->
            val cachedTitles = previousSnapshots[snapshot.providerId]
                ?.items
                .orEmpty()
                .mapNotNull { item ->
                    val display = item.displayTitle?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    item.cacheKey()?.let { key -> key to display }
                }
                .toMap()
            if (cachedTitles.isEmpty()) {
                snapshot
            } else {
                snapshot.copy(
                    items = snapshot.items.map { item ->
                        if (!item.displayTitle.isNullOrBlank()) {
                            item
                        } else {
                            item.cacheKey()?.let(cachedTitles::get)?.let { item.copy(displayTitle = it) } ?: item
                        }
                    }
                )
            }
        }

    private suspend fun translateTitles(
        settings: Settings,
        targets: List<TranslationTarget>,
    ): Map<String, String> {
        val model = resolveModel(settings) ?: return emptyMap()
        val provider = model.findProvider(settings.providers) ?: return emptyMap()
        val prompt = buildPrompt(targets)
        return try {
            val response = withTimeout(MODEL_TIMEOUT_MS) {
                providerManager.getProviderByType(provider).generateText(
                    providerSetting = provider,
                    messages = listOf(
                        UIMessage.system("你是 AmberAgent 的中文资讯标题编辑。仅输出合法 JSON。"),
                        UIMessage.user(prompt),
                    ),
                    params = TextGenerationParams(
                        model = model,
                        maxTokens = 1_200,
                        customHeaders = model.boardRequestHeaders(settings.providers),
                        customBody = model.boardRequestBodies(settings.providers),
                    ),
                )
            }
            val raw = response.choices.firstOrNull()?.message?.toText().orEmpty()
            parseTranslations(raw)
        } catch (error: TimeoutCancellationException) {
            Log.w(TAG, "hot list title translation timed out")
            emptyMap()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "hot list title translation failed", error)
            emptyMap()
        }
    }

    private fun resolveModel(settings: Settings): Model? {
        val boardModel = settings.agentRuntime.todayBoard.boardModelId
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?.let { settings.resolveTaskChatModel(it) }
        return settings.findModelById(settings.translateModeId)
            ?: boardModel
            ?: settings.resolveTaskChatModel(settings.chatModelId)
    }

    private fun buildPrompt(targets: List<TranslationTarget>): String = buildString {
        appendLine("请把下面热榜标题改写成自然简洁的简体中文展示标题。")
        appendLine("- 不要改变专有名词、产品名、公司名和仓库名。")
        appendLine("- GitHub 仓库标题可以写成「GitHub 项目：owner/repo」。")
        appendLine("- 不要加入原标题之外的新事实。")
        appendLine("- 每条 28 个汉字以内，保留必要英文名。")
        appendLine("- 仅输出 JSON：{\"items\":[{\"id\":\"0:1\",\"title\":\"中文标题\"}]}")
        appendLine()
        targets.forEach { target ->
            appendLine("${target.id} | ${target.source} | ${target.title}")
        }
    }

    private fun parseTranslations(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val tolerantJson = Json(json) {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = true
            coerceInputValues = true
        }
        return jsonObjectCandidates(raw)
            .asSequence()
            .map { it.trim().replace(Regex(",\\s*([}\\]])"), "$1") }
            .firstNotNullOfOrNull { candidate ->
                runCatching {
                    tolerantJson.decodeFromString<TranslationResponse>(candidate)
                        .items
                        .mapNotNull { item ->
                            val title = item.title.cleanDisplayTitle().takeIf { it.isNotBlank() }
                            if (title == null) null else item.id to title
                        }
                        .toMap()
                }.getOrNull()
            }
            .orEmpty()
    }

    private fun jsonObjectCandidates(text: String): List<String> = buildList {
        add(text)
        CODE_FENCE.findAll(text).forEach { match ->
            match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::add)
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) add(text.substring(start, end + 1))
    }

    private fun HotListItem.needsChineseDisplayTitle(): Boolean {
        val display = displayTitle.orEmpty()
        if (display.countCjk() >= 2) return false
        val cjk = title.countCjk()
        val latin = title.countLatin()
        return cjk < 2 && latin >= 4
    }

    private fun HotListItem.cacheKey(): String? =
        (url?.takeIf { it.isNotBlank() } ?: title.takeIf { it.isNotBlank() })
            ?.lowercase()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()

    private fun String.cleanDisplayTitle(): String =
        replace(Regex("\\s+"), " ")
            .trim()
            .trim('"', '\'', '“', '”')
            .take(MAX_TITLE_LENGTH)

    private fun String.countCjk(): Int = count { it in '\u4e00'..'\u9fff' }

    private fun String.countLatin(): Int = count { it in 'a'..'z' || it in 'A'..'Z' }

    @Serializable
    private data class TranslationResponse(
        val items: List<TranslatedTitle> = emptyList(),
    )

    @Serializable
    private data class TranslatedTitle(
        val id: String,
        @SerialName("title")
        val title: String,
    )

    private data class TranslationTarget(
        val id: String,
        val source: String,
        val title: String,
    )

    private companion object {
        private const val TAG = "HotListTitleLocalizer"
        private const val MODEL_TIMEOUT_MS = 22_000L
        private const val MAX_TRANSLATED_TITLES = 60
        private const val MAX_TITLE_LENGTH = 80
        private val CODE_FENCE = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    }
}
