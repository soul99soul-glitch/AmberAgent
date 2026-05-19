package me.rerere.rikkahub.data.agent.board.hotlist

import me.rerere.rikkahub.data.agent.board.hotlist.deepread.CorePoint
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepAnalysis
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadOutput
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.ReadingLink
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.hasEnoughChinese
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

    @Test
    fun detectsMostlyEnglishDeepReadOutput() {
        val output = DeepReadOutput(
            summary = "The model returned a mostly English article that should be repaired before display.",
            corePoints = listOf(CorePoint("A long English point", "More English detail about the source and background.")),
            analysis = DeepAnalysis(implications = "This should be rewritten into Chinese."),
            extendedReading = listOf(ReadingLink("Original English title", "https://example.com", "example")),
        )

        assertFalse(output.hasEnoughChinese())
    }

    @Test
    fun acceptsChineseDeepReadOutput() {
        val output = DeepReadOutput(
            summary = "这是一段中文深度阅读摘要，包含足够多的中文内容用于展示。",
            corePoints = listOf(CorePoint("核心判断", "这里是中文支撑材料。")),
            analysis = DeepAnalysis(implications = "这会影响后续产品和产业判断。"),
        )

        assertTrue(output.hasEnoughChinese())
    }
}
