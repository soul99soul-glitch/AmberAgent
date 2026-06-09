package app.amber.feature.board.hotlist

import app.amber.feature.board.TodayBoardHotListFilterMode
import java.util.Locale

fun HotListDashboard.applyInterestFilter(
    keywords: List<String>,
    mode: TodayBoardHotListFilterMode,
): HotListDashboard {
    val normalizedKeywords = normalizeHotListFocusKeywords(keywords)
    if (mode == TodayBoardHotListFilterMode.ALL || normalizedKeywords.isEmpty()) return this

    val topicMatches = topics.mapIndexed { index, topic ->
        IndexedTopic(topic = topic, index = index, focused = topic.matchesAny(normalizedKeywords))
    }
    val filteredTopics = when (mode) {
        TodayBoardHotListFilterMode.ALL -> topics
        TodayBoardHotListFilterMode.FOCUS_FIRST -> topicMatches
            .sortedWith(compareByDescending<IndexedTopic> { it.focused }.thenBy { it.index })
            .map { it.topic }

        TodayBoardHotListFilterMode.FOCUS_ONLY -> topicMatches
            .filter { it.focused }
            .map { it.topic }
    }

    val filteredProviders = providers.mapNotNull { provider ->
        val indexedItems = provider.items.mapIndexed { index, item ->
            IndexedItem(item = item, index = index, focused = item.matchesAny(normalizedKeywords))
        }
        val items = when (mode) {
            TodayBoardHotListFilterMode.ALL -> provider.items
            TodayBoardHotListFilterMode.FOCUS_FIRST -> indexedItems
                .sortedWith(compareByDescending<IndexedItem> { it.focused }.thenBy { it.index })
                .map { it.item }

            TodayBoardHotListFilterMode.FOCUS_ONLY -> indexedItems
                .filter { it.focused }
                .map { it.item }
        }
        if (mode == TodayBoardHotListFilterMode.FOCUS_ONLY && items.isEmpty() && provider.error.isNullOrBlank()) {
            null
        } else {
            provider.copy(items = items)
        }
    }

    return copy(
        topics = filteredTopics,
        providers = filteredProviders,
        lastUpdatedAt = filteredProviders.maxOfOrNull { it.fetchedAt }
            ?: filteredTopics.maxOfOrNull { it.latestFetchedAt }
            ?: lastUpdatedAt,
    )
}

fun normalizeHotListFocusKeywords(keywords: Iterable<String>): List<String> =
    keywords
        .flatMap { keyword ->
            keyword.split(',', '，', '、', ';', '；', '\n', '\t')
        }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase(Locale.ROOT) }
        .take(80)

private fun HotTopic.matchesAny(keywords: List<String>): Boolean =
    searchableText().containsAny(keywords)

private fun HotListItem.matchesAny(keywords: List<String>): Boolean =
    listOfNotNull(title, category, heat, url).joinToString(" ").containsAny(keywords)

private fun HotTopic.searchableText(): String =
    buildString {
        append(title)
        sources.forEach { source ->
            append(' ')
            append(source.title)
            append(' ')
            append(source.providerName)
            if (!source.heat.isNullOrBlank()) {
                append(' ')
                append(source.heat)
            }
        }
    }

private fun String.containsAny(keywords: List<String>): Boolean {
    val normalizedText = lowercase(Locale.ROOT)
    return keywords.any { keyword ->
        val normalizedKeyword = keyword.lowercase(Locale.ROOT)
        if (normalizedKeyword.isShortAsciiKeyword()) {
            Regex("(?<![a-z0-9])${Regex.escape(normalizedKeyword)}(?![a-z0-9])")
                .containsMatchIn(normalizedText)
        } else {
            normalizedText.contains(normalizedKeyword)
        }
    }
}

private fun String.isShortAsciiKeyword(): Boolean =
    length <= 3 && all { it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '-' }

private data class IndexedTopic(
    val topic: HotTopic,
    val index: Int,
    val focused: Boolean,
)

private data class IndexedItem(
    val item: HotListItem,
    val index: Int,
    val focused: Boolean,
)
