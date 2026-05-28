package me.rerere.rikkahub.data.agent.board.hotlist

import app.amber.feature.board.TodayBoardHotListFilterMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HotListAggregatorTest {
    private val aggregator = HotListAggregator()

    @Test
    fun mergesChineseAndEnglishAliasesWhenTwoEntitiesMatch() {
        val topics = aggregator.aggregate(
            listOf(
                snapshot("weibo", "微博", item(1, "马斯克起诉 OpenAI")),
                snapshot("hn", "HN", item(3, "Musk sues OpenAI over new deal")),
            )
        )

        assertEquals(1, topics.size)
        assertEquals(2, topics.first().sourceCount)
    }

    @Test
    fun doesNotMergeDifferentEventsThatShareOneEntity() {
        val topics = aggregator.aggregate(
            listOf(
                snapshot("weibo", "微博", item(1, "苹果发布 iOS 19")),
                snapshot("zhihu", "知乎", item(2, "苹果股价下跌")),
            )
        )

        assertEquals(2, topics.size)
    }

    @Test
    fun doesNotOverMergeShortChineseTitles() {
        val topics = aggregator.aggregate(
            listOf(
                snapshot("weibo", "微博", item(1, "苹果发布")),
                snapshot("zhihu", "知乎", item(2, "苹果股价")),
            )
        )

        assertEquals(2, topics.size)
    }

    @Test
    fun preservesRawProviderOrderInsideProviderSnapshot() {
        val snapshot = snapshot(
            "bilibili",
            "B站",
            item(1, "OpenAI 发布新模型"),
            item(2, "小米汽车更新"),
        )

        assertEquals(listOf(1, 2), snapshot.items.map { it.rank })
        assertTrue(aggregator.aggregate(listOf(snapshot)).isNotEmpty())
    }

    @Test
    fun usesChineseDisplayTitleWithoutLosingOriginalSourceTitle() {
        val topics = aggregator.aggregate(
            listOf(
                snapshot(
                    "github",
                    "GitHub AI",
                    HotListItem(
                        rank = 1,
                        title = "sponsors / explore",
                        displayTitle = "GitHub 项目：sponsors/explore",
                        url = "https://example.com/repo",
                    ),
                )
            )
        )

        val topic = topics.single()
        assertEquals("GitHub 项目：sponsors/explore", topic.title)
        assertEquals("sponsors / explore", topic.sources.single().title)
        assertEquals("GitHub 项目：sponsors/explore", topic.sources.single().presentationTitle)
    }

    @Test
    fun dashboardFilterRemovesDisabledProviderCachesAndTopicSources() {
        val topic = HotTopic(
            id = "topic",
            title = "马斯克起诉 OpenAI",
            sources = listOf(
                HotTopicSource("weibo", "微博", rank = 1, title = "马斯克起诉 OpenAI"),
                HotTopicSource("hn", "HN", rank = 4, title = "Musk sues OpenAI"),
            ),
            sourceCount = 2,
            bestRank = 1,
            latestFetchedAt = 200L,
        )
        val dashboard = HotListDashboard(
            topics = listOf(topic),
            providers = listOf(
                snapshot("weibo", "微博", item(1, "马斯克起诉 OpenAI")),
                snapshot("hn", "HN", item(4, "Musk sues OpenAI")),
            ),
            lastUpdatedAt = 200L,
        )

        val filtered = dashboard.filterEnabledSources(setOf("hn"))

        assertEquals(listOf("hn"), filtered.providers.map { it.providerId })
        assertEquals(listOf("hn"), filtered.topics.single().sources.map { it.providerId })
        assertEquals(1, filtered.topics.single().sourceCount)
        assertEquals(4, filtered.topics.single().bestRank)
    }

    @Test
    fun dashboardFilterReturnsEmptyStateWhenNoSourcesEnabled() {
        val dashboard = HotListDashboard(
            topics = listOf(
                HotTopic("topic", "title", emptyList(), sourceCount = 1, bestRank = 1, latestFetchedAt = 100L)
            ),
            providers = listOf(snapshot("weibo", "微博", item(1, "title"))),
            lastUpdatedAt = 100L,
        )

        val filtered = dashboard.filterEnabledSources(emptySet())

        assertTrue(filtered.isEmpty)
        assertTrue(!filtered.hasEnabledSources)
        assertTrue(!filtered.shouldShowSkeleton)
    }

    @Test
    fun focusOnlyCanFindAiTopicOutsideTopTenPool() {
        val topics = (1..30).map { rank ->
            HotTopic(
                id = "topic-$rank",
                title = if (rank == 30) "具身智能机器人新进展" else "普通热点 $rank",
                sources = listOf(HotTopicSource("bilibili", "B站", rank = rank, title = "普通热点 $rank")),
                sourceCount = 1,
                bestRank = rank,
                latestFetchedAt = 100L,
            )
        }
        val dashboard = HotListDashboard(
            topics = topics,
            providers = listOf(snapshot("bilibili", "B站", *topics.map { item(it.bestRank, it.title) }.toTypedArray())),
            lastUpdatedAt = 100L,
        )

        val filtered = dashboard.applyInterestFilter(listOf("机器人"), TodayBoardHotListFilterMode.FOCUS_ONLY)

        assertEquals(listOf("具身智能机器人新进展"), filtered.topics.map { it.title })
        assertEquals(listOf("具身智能机器人新进展"), filtered.providers.single().items.map { it.title })
    }

    @Test
    fun focusFirstKeepsOtherHotTopicsAfterMatches() {
        val dashboard = HotListDashboard(
            topics = listOf(
                HotTopic("normal", "普通热点", emptyList(), sourceCount = 1, bestRank = 1, latestFetchedAt = 100L),
                HotTopic("ai", "OpenAI 发布新能力", emptyList(), sourceCount = 1, bestRank = 20, latestFetchedAt = 100L),
            ),
            providers = emptyList(),
            lastUpdatedAt = 100L,
        )

        val filtered = dashboard.applyInterestFilter(listOf("OpenAI"), TodayBoardHotListFilterMode.FOCUS_FIRST)

        assertEquals(listOf("OpenAI 发布新能力", "普通热点"), filtered.topics.map { it.title })
    }

    @Test
    fun shortAsciiFocusKeywordUsesTokenBoundary() {
        val dashboard = HotListDashboard(
            topics = listOf(
                HotTopic("claim", "Company claim denied", emptyList(), sourceCount = 1, bestRank = 1, latestFetchedAt = 100L),
                HotTopic("ai", "AI robot demo", emptyList(), sourceCount = 1, bestRank = 2, latestFetchedAt = 100L),
            ),
            providers = emptyList(),
            lastUpdatedAt = 100L,
        )

        val filtered = dashboard.applyInterestFilter(listOf("AI"), TodayBoardHotListFilterMode.FOCUS_ONLY)

        assertEquals(listOf("AI robot demo"), filtered.topics.map { it.title })
    }

    private fun item(rank: Int, title: String) =
        HotListItem(rank = rank, title = title, url = "https://example.com/$rank")

    private fun snapshot(providerId: String, name: String, vararg items: HotListItem) =
        HotListProviderSnapshot(
            providerId = providerId,
            providerName = name,
            items = items.toList(),
            fetchedAt = 100L,
        )
}
