package app.amber.feature.board.hotlist

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.feature.board.boardRequestBodies
import app.amber.feature.board.boardRequestHeaders
import app.amber.core.settings.Settings
import app.amber.core.settings.findProvider
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.resolveTaskChatModel
import java.util.Locale
import kotlin.uuid.Uuid

class HotListTitleLocalizer(
    private val settingsStore: SettingsAggregator,
    private val providerCatalog: ProviderCatalog,
    private val json: Json,
) {
    suspend fun localize(
        snapshots: List<HotListProviderSnapshot>,
        previousSnapshots: Map<String, HotListProviderSnapshot> = emptyMap(),
        locale: Locale = Locale.getDefault(),
    ): List<HotListProviderSnapshot> {
        val reusedSnapshots = reuseCachedTitles(snapshots, previousSnapshots, locale)
        val targets = reusedSnapshots.flatMapIndexed { snapshotIndex, snapshot ->
            snapshot.items.mapIndexedNotNull { itemIndex, item ->
                if (item.needsLocalizedDisplayTitle(locale)) {
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
        val translations = translateTitles(settings, targets, locale)
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
        locale: Locale,
    ): List<HotListProviderSnapshot> =
        snapshots.map { snapshot ->
            val cachedTitles = previousSnapshots[snapshot.providerId]
                ?.items
                .orEmpty()
                .mapNotNull { item ->
                    val display = item.displayTitle
                        ?.takeIf { it.isNotBlank() && it.isCompatibleDisplayTitle(locale) }
                        ?: return@mapNotNull null
                    item.cacheKey()?.let { key -> key to display }
                }
                .toMap()
            if (cachedTitles.isEmpty()) {
                snapshot
            } else {
                snapshot.copy(
                    items = snapshot.items.map { item ->
                        if (item.displayTitle.orEmpty().isCompatibleDisplayTitle(locale)) {
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
        locale: Locale,
    ): Map<String, String> {
        val model = resolveModel(settings) ?: return emptyMap()
        val provider = model.findProvider(settings.providers) ?: return emptyMap()
        val prompt = buildPrompt(targets, locale)
        return try {
            val response = withTimeout(MODEL_TIMEOUT_MS) {
                providerCatalog.text(provider).complete(
                    providerSetting = provider,
                    messages = listOf(
                        UIMessage.system(
                            if (locale.isChineseLocale()) {
                                "你是 AmberAgent 的中文资讯标题编辑。仅输出合法 JSON。"
                            } else {
                                "You are AmberAgent's news-title editor. Translate titles into concise natural English and output valid JSON only."
                            }
                        ),
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
        return boardModel
            ?: settings.resolveTaskChatModel(settings.chatModelId)
    }

    private fun buildPrompt(targets: List<TranslationTarget>, locale: Locale): String = buildString {
        if (locale.isChineseLocale()) {
            appendLine("请把下面热榜标题改写成自然简洁的${locale.displayLanguageForPrompt()}展示标题。")
            appendLine("- 不要改变专有名词、产品名、公司名和仓库名。")
            appendLine("- GitHub 仓库标题可以写成「GitHub 项目：owner/repo」。")
            appendLine("- 不要加入原标题之外的新事实。")
            appendLine("- 每条 28 个字以内，保留必要英文名。")
            appendLine("- 仅输出 JSON：{\"items\":[{\"id\":\"0:1\",\"title\":\"中文标题\"}]}")
        } else {
            appendLine("Rewrite the following hot-list titles as concise, natural English display titles.")
            appendLine("- Do not change proper nouns, product names, company names, or repository names.")
            appendLine("- A GitHub repository title may be phrased as \"GitHub project: owner/repo\".")
            appendLine("- Do not add facts that are absent from the original title.")
            appendLine("- Keep each title within 80 characters and retain necessary original names.")
            appendLine("- Output JSON only: {\"items\":[{\"id\":\"0:1\",\"title\":\"English title\"}]}")
        }
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

    private fun HotListItem.needsLocalizedDisplayTitle(locale: Locale): Boolean {
        val cjk = title.countCjk()
        val latin = title.countLatin()
        return if (locale.isChineseLocale()) {
            !displayTitle.orEmpty().isCompatibleDisplayTitle(locale) && cjk < 2 && latin >= 4
        } else {
            !displayTitle.orEmpty().isCompatibleDisplayTitle(locale) && cjk >= 2
        }
    }

    private fun String.isCompatibleDisplayTitle(locale: Locale): Boolean =
        isNotBlank() && if (locale.isChineseLocale()) countCjk() >= 2 else countCjk() < 2

    private fun Locale.isChineseLocale(): Boolean = language.equals("zh", ignoreCase = true)

    private fun Locale.displayLanguageForPrompt(): String =
        if (country.equals("TW", ignoreCase = true) || script.equals("Hant", ignoreCase = true)) {
            "繁體中文"
        } else {
            "简体中文"
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
