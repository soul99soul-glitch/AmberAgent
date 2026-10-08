package app.amber.feature.board.hotlist

import kotlinx.serialization.Serializable

object HotListProviderIds {
    const val WEIBO = "weibo"
    const val ZHIHU = "zhihu"
    const val BILIBILI = "bilibili"
    const val HACKER_NEWS = "hacker_news"
    const val ARXIV_AI = "arxiv_ai"
    const val INFOQ_AI = "infoq_ai"
    const val HUGGINGFACE_PAPERS = "huggingface_papers"
    const val GITHUB_TRENDING_AI = "github_trending_ai"
    const val DOUYIN = "douyin"
    const val BAIDU = "baidu"
    const val TOUTIAO = "toutiao"
    const val KR36 = "36kr"

    val DEFAULT_ENABLED: Set<String> = setOf(BILIBILI, HACKER_NEWS)
}

const val HOT_LIST_TOPIC_CACHE_LIMIT = 80
const val HOT_LIST_TOPIC_DISPLAY_LIMIT = 20

/** Topic-id prefix for user-created deep-read topics (kept out of the hot-list dashboard). */
const val CUSTOM_TOPIC_ID_PREFIX = "custom-"

/** Provider id on [HotTopicSource] for user-provided seeds (pasted text, links, files). */
const val CUSTOM_TOPIC_PROVIDER_ID = "custom_source"

/** Mirrors MAX_SEED_SOURCES in DeepReadSourcePrefetcher: extra seeds are dropped. */
const val MAX_CUSTOM_SEED_SOURCES = 4

/** Minimum body length for a pasted/extracted text seed to survive seed filtering. */
const val MIN_CUSTOM_SEED_TEXT_CHARS = 60

@Serializable
data class HotListItem(
    val rank: Int,
    val title: String,
    val displayTitle: String? = null,
    val heat: String? = null,
    val url: String? = null,
    val category: String? = null,
    val images: List<String> = emptyList(),
)

@Serializable
data class HotListResult(
    val items: List<HotListItem>,
    val fetchedAt: Long,
)

@Serializable
data class HotTopicSource(
    val providerId: String,
    val providerName: String,
    val rank: Int,
    val title: String,
    val displayTitle: String? = null,
    val url: String? = null,
    val heat: String? = null,
    val images: List<String> = emptyList(),
    /**
     * Inline body text for user-provided seeds (pasted text, extracted file text).
     * When non-blank it replaces the synthetic hot-list preamble as the seed content.
     */
    val content: String? = null,
)

@Serializable
data class HotTopic(
    val id: String,
    val title: String,
    val sources: List<HotTopicSource>,
    val sourceCount: Int,
    val bestRank: Int,
    val latestFetchedAt: Long,
    /**
     * Generation/display template picked at topic creation (iOS task.templateId).
     * Null on dashboard topics: they follow the board setting at generation time.
     */
    val deepReadTemplateId: String? = null,
)

data class HotListProviderSnapshot(
    val providerId: String,
    val providerName: String,
    val items: List<HotListItem>,
    val fetchedAt: Long,
    val stale: Boolean = false,
    val error: String? = null,
)

data class HotListDashboard(
    val topics: List<HotTopic>,
    val providers: List<HotListProviderSnapshot>,
    val lastUpdatedAt: Long,
    val enabledSourceCount: Int = HotListProviderIds.DEFAULT_ENABLED.size,
) {
    val isEmpty: Boolean get() = topics.isEmpty() && providers.isEmpty()
    val hasContent: Boolean get() = topics.isNotEmpty() || providers.any { it.items.isNotEmpty() }
    val hasEnabledSources: Boolean get() = enabledSourceCount > 0
    val shouldShowSkeleton: Boolean get() = hasEnabledSources && isEmpty
}

val HotListItem.presentationTitle: String
    get() = displayTitle?.takeIf { it.isNotBlank() } ?: title

val HotTopicSource.presentationTitle: String
    get() = displayTitle?.takeIf { it.isNotBlank() } ?: title

/**
 * One user-provided seed for a custom deep-read topic: a pasted text block,
 * a web link, or text extracted from an imported file.
 */
data class DeepReadSeedInput(
    val title: String,
    val providerName: String,
    val url: String? = null,
    val content: String? = null,
)

fun HotListDashboard.filterEnabledSources(enabledSourceIds: Set<String>): HotListDashboard {
    if (enabledSourceIds.isEmpty()) {
        return HotListDashboard(emptyList(), emptyList(), 0L, enabledSourceCount = 0)
    }

    val filteredProviders = providers.filter { it.providerId in enabledSourceIds }
    val filteredTopics = topics.mapNotNull { topic ->
        val sources = topic.sources.filter { it.providerId in enabledSourceIds }
        if (sources.isEmpty()) return@mapNotNull null
        topic.copy(
            sources = sources,
            sourceCount = sources.map { it.providerId }.distinct().size,
            bestRank = sources.minOfOrNull { it.rank } ?: Int.MAX_VALUE,
        )
    }.sortedWith(
        compareByDescending<HotTopic> { it.sourceCount }
            .thenBy { it.bestRank }
            .thenByDescending { it.latestFetchedAt }
    )

    return HotListDashboard(
        topics = filteredTopics,
        providers = filteredProviders,
        lastUpdatedAt = filteredProviders.maxOfOrNull { it.fetchedAt }
            ?: filteredTopics.maxOfOrNull { it.latestFetchedAt }
            ?: 0L,
        enabledSourceCount = enabledSourceIds.size,
    )
}
