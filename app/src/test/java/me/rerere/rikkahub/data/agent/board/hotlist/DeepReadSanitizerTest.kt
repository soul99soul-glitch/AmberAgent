package me.rerere.rikkahub.data.agent.board.hotlist

import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepAnalysis
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.CorePoint
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepQuote
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadImageAsset
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadOutput
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadSanitizer
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadSource
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.ReadingLink
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.TimelineEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        assertEquals("https://news.example.com/a.jpg", sanitized.heroImageUrl)
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
}
