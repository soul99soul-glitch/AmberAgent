package app.amber.feature.board.hotlist

import app.amber.feature.board.hotlist.deepread.DeepAnalysis
import app.amber.feature.board.hotlist.deepread.CorePoint
import app.amber.feature.board.hotlist.deepread.DeepReadImageCandidate
import app.amber.feature.board.hotlist.deepread.DeepQuote
import app.amber.feature.board.hotlist.deepread.DeepReadImageAsset
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.feature.board.hotlist.deepread.DeepReadSanitizer
import app.amber.feature.board.hotlist.deepread.DeepReadSource
import app.amber.feature.board.hotlist.deepread.IMAGE_CONFIDENCE_HERO
import app.amber.feature.board.hotlist.deepread.IMAGE_CONFIDENCE_INLINE
import app.amber.feature.board.hotlist.deepread.ReadingLink
import app.amber.feature.board.hotlist.deepread.TimelineEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DeepReadSanitizerTest {
    @Test
    fun removesModelInventedLinksAndImages() {
        val source = DeepReadSource(
            title = "Real",
            url = "https://news.example.com/a",
            source = "news.example.com",
            content = "body with a real quoted sentence from the source",
            publishedAt = null,
            images = listOf("https://news.example.com/a.jpg"),
        )
        val parsed = DeepReadOutput(
            summary = "summary",
            analysis = DeepAnalysis(
                quotes = listOf(
                    DeepQuote("real quoted sentence from the source", "Real"),
                    DeepQuote("invented quotation that is absent", "Fake"),
                )
            ),
            heroImageUrl = "https://invented.example.com/fake.jpg",
            references = listOf(
                ReadingLink("Fake", "https://invented.example.com/fake", "fake"),
                ReadingLink("Real", source.url, source.source),
            ),
            extendedReading = listOf(ReadingLink("Fake", "https://invented.example.com/fake", "fake")),
        )

        val sanitized = DeepReadSanitizer.sanitize(parsed, listOf(source), "AI 热点")

        assertNull(sanitized.heroImageUrl)
        assertFalse(sanitized.references.any { it.url.contains("invented") })
        assertEquals(listOf("real quoted sentence from the source"), sanitized.analysis.quotes.map { it.text })
        assertEquals(listOf(source.url), sanitized.extendedReading.map { it.url })
    }

    @Test
    fun keepsOnlySourceImagesAndValidSectionBindings() {
        val sourceImage = "https://news.example.com/photo.jpg"
        val source = DeepReadSource(
            title = "Real",
            url = "https://news.example.com/a",
            source = "news.example.com",
            content = "body with a real quoted sentence from the source",
            publishedAt = null,
            images = listOf(
                sourceImage,
                "https://news.example.com/logo.png",
                "https://news.example.com/tracking-pixel.gif",
            ),
            imageCandidates = listOf(
                imageCandidate(sourceImage, IMAGE_CONFIDENCE_INLINE),
            ),
        )
        val parsed = DeepReadOutput(
            summary = "这是中文摘要，足够用于深度阅读",
            timeline = listOf(
                TimelineEvent(
                    date = "2026-05-20",
                    event = "事件进入关键节点",
                    imageUrl = sourceImage,
                    imageCaption = "来源现场图片",
                ),
                TimelineEvent(
                    date = "2026-05-21",
                    event = "模型绑定了不存在的图片",
                    imageUrl = "https://fake.example.com/made-up.jpg",
                    imageCaption = "这张图不该保留",
                ),
            ),
            corePoints = listOf(
                CorePoint(
                    point = "关键观点",
                    supporting = "这是中文支撑信息",
                    imageUrl = "https://fake.example.com/core.jpg",
                    imageCaption = "这张图也不该保留",
                ),
            ),
            analysis = DeepAnalysis(),
            imageAssets = listOf(
                DeepReadImageAsset(
                    url = sourceImage,
                    caption = "来源图片说明",
                    source = "news.example.com",
                    relatedTimelineIndex = 0,
                ),
                DeepReadImageAsset(
                    url = "https://fake.example.com/made-up.jpg",
                    caption = "模型瞎编图片",
                    relatedTimelineIndex = 99,
                ),
            ),
        )

        val sanitized = DeepReadSanitizer.sanitize(parsed, listOf(source), "AI 热点")

        assertEquals(listOf(sourceImage), sanitized.imageAssets.map { it.url })
        assertNull(sanitized.heroImageUrl)
        assertEquals(sourceImage, sanitized.timeline?.get(0)?.imageUrl)
        assertEquals("来源现场图片", sanitized.timeline?.get(0)?.imageCaption)
        assertEquals(null, sanitized.timeline?.get(1)?.imageUrl)
        assertEquals(null, sanitized.corePoints?.first()?.imageUrl)
    }

    @Test
    fun deduplicatesInlineImagesButAllowsHeroReuseOnce() {
        val image = "https://news.example.com/photo.jpg?width=1200"
        val sameImage = "https://news.example.com/photo.jpg?width=640"
        val otherImage = "https://news.example.com/other.jpg"
        val source = DeepReadSource(
            title = "Real",
            url = "https://news.example.com/a",
            source = "news.example.com",
            content = "body",
            publishedAt = null,
            images = listOf(image, sameImage, otherImage),
            imageCandidates = listOf(
                imageCandidate(image, IMAGE_CONFIDENCE_HERO, score = 80),
                imageCandidate(sameImage, IMAGE_CONFIDENCE_HERO, score = 76),
                imageCandidate(otherImage, IMAGE_CONFIDENCE_INLINE, score = 24),
            ),
        )
        val parsed = DeepReadOutput(
            summary = "这是中文摘要，足够用于深度阅读",
            heroImageUrl = image,
            timeline = listOf(
                TimelineEvent(
                    date = "2026-02",
                    event = "第一处正文配图可以复用 hero",
                    imageUrl = image,
                    imageCaption = "第一张来源图片",
                ),
                TimelineEvent(
                    date = "2026-05",
                    event = "同一图片再次出现时应该清掉",
                    imageUrl = sameImage,
                    imageCaption = "重复图片说明",
                ),
            ),
            corePoints = listOf(
                CorePoint(
                    point = "另一个模块也不能重复同图",
                    supporting = "正文已经出现过这张图片",
                    imageUrl = image,
                    imageCaption = "重复核心图",
                ),
                CorePoint(
                    point = "不同图片可以保留",
                    supporting = "这是一张不同的来源图片",
                    imageUrl = otherImage,
                    imageCaption = "第二张来源图片",
                ),
            ),
            analysis = DeepAnalysis(),
            imageAssets = listOf(
                DeepReadImageAsset(url = image, caption = "Hero 图片"),
                DeepReadImageAsset(url = sameImage, caption = "重复图片"),
                DeepReadImageAsset(url = otherImage, caption = "其他图片"),
            ),
        )

        val sanitized = DeepReadSanitizer.sanitize(parsed, listOf(source), "AI 热点")

        assertEquals(image, sanitized.heroImageUrl)
        assertEquals(IMAGE_CONFIDENCE_HERO, sanitized.heroImageConfidence)
        assertEquals(image, sanitized.timeline?.get(0)?.imageUrl)
        assertEquals(null, sanitized.timeline?.get(1)?.imageUrl)
        assertEquals(null, sanitized.corePoints?.get(0)?.imageUrl)
        assertEquals(otherImage, sanitized.corePoints?.get(1)?.imageUrl)
        assertEquals(listOf(image, otherImage), sanitized.imageAssets.map { it.url })
    }

    @Test
    fun keepsDifferentImagesWhenQueryCarriesIdentity() {
        val imageA = "https://cdn.example.com/image?media_id=1&width=1200"
        val imageB = "https://cdn.example.com/image?media_id=2&width=1200"
        val source = DeepReadSource(
            title = "Real",
            url = "https://news.example.com/a",
            source = "news.example.com",
            content = "body",
            publishedAt = null,
            images = listOf(imageA, imageB),
            imageCandidates = listOf(
                imageCandidate(imageA, IMAGE_CONFIDENCE_INLINE, score = 30),
                imageCandidate(imageB, IMAGE_CONFIDENCE_INLINE, score = 29),
            ),
        )
        val parsed = DeepReadOutput(
            summary = "这是中文摘要，足够用于深度阅读",
            timeline = listOf(
                TimelineEvent(
                    date = "2026-02",
                    event = "第一张身份参数图片",
                    imageUrl = imageA,
                    imageCaption = "第一张来源图片",
                ),
                TimelineEvent(
                    date = "2026-05",
                    event = "第二张身份参数图片",
                    imageUrl = imageB,
                    imageCaption = "第二张来源图片",
                ),
            ),
            analysis = DeepAnalysis(),
            imageAssets = listOf(
                DeepReadImageAsset(url = imageA, caption = "第一张图片"),
                DeepReadImageAsset(url = imageB, caption = "第二张图片"),
            ),
        )

        val sanitized = DeepReadSanitizer.sanitize(parsed, listOf(source), "AI 热点")

        assertEquals(imageA, sanitized.timeline?.get(0)?.imageUrl)
        assertEquals(imageB, sanitized.timeline?.get(1)?.imageUrl)
        assertEquals(listOf(imageA, imageB), sanitized.imageAssets.map { it.url })
    }

    @Test
    fun rejectsLogoAsHeroEvenWhenModelSelectsIt() {
        val logo = "https://static.36kr.com/logo.png"
        val source = DeepReadSource(
            title = "三十六氪报道",
            url = "https://36kr.com/p/123",
            source = "36kr.com",
            content = "body",
            publishedAt = null,
            images = listOf(logo),
            imageCandidates = listOf(imageCandidate(logo, "reject", score = 1, riskFlags = listOf("site_brand_asset"))),
        )
        val parsed = DeepReadOutput(
            summary = "这是中文摘要，足够用于深度阅读",
            heroImageUrl = logo,
            imageAssets = listOf(DeepReadImageAsset(url = logo, caption = "不该出现")),
        )

        val sanitized = DeepReadSanitizer.sanitize(parsed, listOf(source), "三十六氪报道")

        assertNull(sanitized.heroImageUrl)
        assertEquals(emptyList<String>(), sanitized.imageAssets.map { it.url })
    }

    @Test
    fun strongTitleMatchedImageCanBecomeHero() {
        val hero = "https://cdn.example.com/xiaomi-tesla-babai-liangsheng-ppt.jpg"
        val source = DeepReadSource(
            title = "小米发布会回应八败两胜与特斯拉对比",
            url = "https://news.example.com/xiaomi",
            source = "news.example.com",
            content = "小米发布会展示八败两胜 PPT，并提及特斯拉。",
            publishedAt = null,
            images = listOf(hero),
            imageCandidates = listOf(imageCandidate(hero, IMAGE_CONFIDENCE_HERO, score = 82)),
        )
        val parsed = DeepReadOutput(
            summary = "这是中文摘要，足够用于深度阅读",
            heroImageUrl = hero,
            imageAssets = listOf(DeepReadImageAsset(url = hero, caption = "小米发布会 PPT")),
        )

        val sanitized = DeepReadSanitizer.sanitize(parsed, listOf(source), "小米 八败两胜 特斯拉 PPT 发布会 图")

        assertEquals(hero, sanitized.heroImageUrl)
        assertEquals(IMAGE_CONFIDENCE_HERO, sanitized.heroImageConfidence)
    }

    @Test
    fun fallbackSourceImageDoesNotBecomeHeroFromTitleAlone() {
        val weakImage = "https://cdn.example.com/share-default.jpg"
        val source = DeepReadSource(
            title = "小米 八败两胜 特斯拉 PPT 发布会 图",
            url = "https://news.example.com/xiaomi",
            source = "news.example.com",
            content = "小米发布会展示八败两胜 PPT，并提及特斯拉。",
            publishedAt = null,
            images = listOf(weakImage),
            imageCandidates = emptyList(),
        )
        val parsed = DeepReadOutput(
            summary = "这是中文摘要，足够用于深度阅读",
            heroImageUrl = weakImage,
            imageAssets = listOf(DeepReadImageAsset(url = weakImage, caption = "弱相关默认图")),
        )

        val sanitized = DeepReadSanitizer.sanitize(parsed, listOf(source), "小米 八败两胜 特斯拉 PPT 发布会 图")

        assertNull(sanitized.heroImageUrl)
    }

    private fun imageCandidate(
        url: String,
        confidence: String,
        score: Int = 24,
        riskFlags: List<String> = emptyList(),
    ): DeepReadImageCandidate =
        DeepReadImageCandidate(
            imageUrl = url,
            sourceUrl = "https://news.example.com/a",
            sourceTitle = "来源标题",
            candidateKind = "article_image",
            sourceService = "news.example.com",
            confidence = confidence,
            score = score,
            riskFlags = riskFlags,
        )
}
