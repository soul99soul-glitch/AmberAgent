package me.rerere.rikkahub.data.agent.board.hotlist

import app.amber.feature.board.hotlist.deepread.DeepReadImageCandidate
import app.amber.feature.board.hotlist.deepread.DeepReadImageQuality
import app.amber.feature.board.hotlist.deepread.DeepReadImageScorer
import app.amber.feature.board.hotlist.deepread.IMAGE_CONFIDENCE_HERO
import app.amber.feature.board.hotlist.deepread.IMAGE_CONFIDENCE_INLINE
import app.amber.feature.board.hotlist.deepread.IMAGE_CONFIDENCE_REJECT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DeepReadImageScorerTest {
    @Test
    fun searchResultImageWithoutDirectContextNeverBecomesHero() {
        val topic = "小米 八败两胜 特斯拉 PPT 发布会 图"
        val scored = DeepReadImageScorer.score(
            topicTitle = topic,
            candidate = candidate(
                imageUrl = "https://cdn.example.net/xiaomi-tesla-babai-liangsheng-ppt.jpg",
                sourceUrl = "https://cdn.example.net/p/123",
                sourceTitle = topic,
                pageTitle = topic,
                candidateKind = "search_result_image",
                query = topic,
            ),
        )

        assertNotEquals(IMAGE_CONFIDENCE_HERO, scored.confidence)
        assertEquals(IMAGE_CONFIDENCE_INLINE, scored.confidence)
    }

    @Test
    fun noSpaceChineseSceneImageCanBecomeHeroWhenDirectContextMatches() {
        val scored = DeepReadImageScorer.score(
            topicTitle = "小米昨天发布会说自己对特斯拉八败两胜",
            candidate = candidate(
                imageUrl = "https://cdn.example.com/xiaomi-tesla-babai-liangsheng-ppt.jpg",
                sourceUrl = "https://news.example.com/xiaomi-launch",
                sourceTitle = "小米发布会回应特斯拉竞争",
                pageTitle = "小米发布会回应特斯拉竞争",
                alt = "小米 八败两胜 特斯拉 PPT 发布会",
                nearbyText = "现场演示文稿出现八败两胜页面，雷军谈小米和特斯拉的对比。",
                candidateKind = "article_image",
            ),
        )

        assertEquals(IMAGE_CONFIDENCE_HERO, scored.confidence)
    }

    @Test
    fun ogImageWithoutImageLevelContextNeverBecomesHero() {
        val topic = "小米 八败两胜 特斯拉 PPT 发布会 图"
        val scored = DeepReadImageScorer.score(
            topicTitle = topic,
            candidate = candidate(
                imageUrl = "https://static.36kr.com/share-default.png",
                sourceUrl = "https://36kr.com/p/123",
                sourceTitle = topic,
                pageTitle = topic,
                candidateKind = "og_image",
                query = topic,
            ),
        )

        assertNotEquals(IMAGE_CONFIDENCE_HERO, scored.confidence)
        assertEquals(IMAGE_CONFIDENCE_INLINE, scored.confidence)
    }

    @Test
    fun ogImageWithSingleSceneTokenUrlStillNeedsMoreImageEvidence() {
        val topic = "小米 八败两胜 特斯拉 PPT 发布会 图"
        val scored = DeepReadImageScorer.score(
            topicTitle = topic,
            candidate = candidate(
                imageUrl = "https://static.36kr.com/ppt.jpg",
                sourceUrl = "https://36kr.com/p/123",
                sourceTitle = topic,
                pageTitle = topic,
                candidateKind = "og_image",
                query = topic,
            ),
        )

        assertNotEquals(IMAGE_CONFIDENCE_HERO, scored.confidence)
        assertEquals(IMAGE_CONFIDENCE_INLINE, scored.confidence)
    }

    @Test
    fun ogImageUrlSceneEvidenceCanBecomeHero() {
        val topic = "小米 八败两胜 特斯拉 PPT 发布会 图"
        val scored = DeepReadImageScorer.score(
            topicTitle = topic,
            candidate = candidate(
                imageUrl = "https://img.example.com/xiaomi-tesla-babai-liangsheng-ppt-launch.jpg",
                sourceUrl = "https://news.example.com/p/123",
                sourceTitle = topic,
                pageTitle = topic,
                candidateKind = "og_image",
            ),
        )

        assertEquals(IMAGE_CONFIDENCE_HERO, scored.confidence)
    }

    @Test
    fun hotlistImageWithoutImageLevelContextNeverBecomesHero() {
        val topic = "小米 八败两胜 特斯拉 PPT 发布会 图"
        val scored = DeepReadImageScorer.score(
            topicTitle = topic,
            candidate = candidate(
                imageUrl = "https://img.example.com/share-default.jpg",
                sourceUrl = "https://news.example.com/p/123",
                sourceTitle = topic,
                pageTitle = topic,
                candidateKind = "hotlist_image",
                query = topic,
            ),
        )

        assertNotEquals(IMAGE_CONFIDENCE_HERO, scored.confidence)
        assertEquals(IMAGE_CONFIDENCE_INLINE, scored.confidence)
    }

    @Test
    fun genericAltTextDoesNotUnlockArticleImageHero() {
        val topic = "小米 八败两胜 特斯拉 PPT 发布会 图"
        val scored = DeepReadImageScorer.score(
            topicTitle = topic,
            candidate = candidate(
                imageUrl = "https://cdn.example.com/share-default.jpg",
                sourceUrl = "https://news.example.com/p/123",
                sourceTitle = topic,
                pageTitle = topic,
                alt = "新闻配图",
                nearbyText = "点击查看大图",
                candidateKind = "article_image",
                query = topic,
            ),
        )

        assertNotEquals(IMAGE_CONFIDENCE_HERO, scored.confidence)
        assertEquals(IMAGE_CONFIDENCE_INLINE, scored.confidence)
    }

    @Test
    fun fallbackSourceImageDoesNotTreatTitleAsImageContext() {
        val scored = DeepReadImageScorer.fallbackCandidate(
            topicTitle = "小米 八败两胜 特斯拉 PPT 发布会 图",
            imageUrl = "https://cdn.example.com/share-default.jpg",
            sourceUrl = "https://news.example.com/p/123",
            sourceTitle = "小米 八败两胜 特斯拉 PPT 发布会 图",
            source = "news.example.com",
        )

        assertNotEquals(IMAGE_CONFIDENCE_HERO, scored.confidence)
        assertEquals(IMAGE_CONFIDENCE_INLINE, scored.confidence)
    }

    @Test
    fun logoAndIconAssetsAreAlwaysRejected() {
        val scored = DeepReadImageScorer.score(
            topicTitle = "三十六氪 小米 发布会",
            candidate = candidate(
                imageUrl = "https://static.36kr.com/logo.svg",
                sourceUrl = "https://36kr.com/p/123",
                sourceTitle = "三十六氪 小米 发布会",
                pageTitle = "三十六氪 小米 发布会",
                alt = "36氪",
                nearbyText = "小米发布会新闻",
                candidateKind = "og_image",
                quality = DeepReadImageQuality(
                    width = 128,
                    height = 128,
                    contentType = "image/svg+xml",
                    byteSize = 2048,
                ),
            ),
        )

        assertEquals(IMAGE_CONFIDENCE_REJECT, scored.confidence)
    }

    private fun candidate(
        imageUrl: String,
        sourceUrl: String,
        sourceTitle: String,
        candidateKind: String,
        pageTitle: String? = null,
        alt: String? = null,
        nearbyText: String? = null,
        query: String? = null,
        quality: DeepReadImageQuality = DeepReadImageQuality(
            width = 1280,
            height = 720,
            contentType = "image/jpeg",
            byteSize = 120_000,
        ),
    ): DeepReadImageCandidate =
        DeepReadImageCandidate(
            imageUrl = imageUrl,
            sourceUrl = sourceUrl,
            sourceTitle = sourceTitle,
            pageTitle = pageTitle,
            alt = alt,
            nearbyText = nearbyText,
            candidateKind = candidateKind,
            sourceService = "test",
            query = query,
            quality = quality,
        )
}
