package me.rerere.rikkahub.data.agent.board.hotlist

import me.rerere.rikkahub.data.agent.board.hotlist.deepread.CorePoint
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepAnalysis
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadOutput
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.ReadingLink
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.hasReadableArticle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepReadOutputQualityTest {
    @Test
    fun rejectsEmptyModelObjectEvenWhenLinksExist() {
        val output = DeepReadOutput(
            summary = "",
            extendedReading = listOf(ReadingLink("source", "https://example.com", "example.com")),
        )

        assertFalse(output.hasReadableArticle())
    }

    @Test
    fun acceptsSummaryWithGeneratedBodySection() {
        val output = DeepReadOutput(
            summary = "这是一个有明确上下文和可读正文的深度阅读摘要。",
            corePoints = listOf(CorePoint("核心论点", "支撑材料")),
            analysis = DeepAnalysis(),
        )

        assertTrue(output.hasReadableArticle())
    }
}
