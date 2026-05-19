package me.rerere.rikkahub.data.agent.board.hotlist

import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepAnalysis
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepQuote
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadOutput
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadSanitizer
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadSource
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.ReadingLink
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

        val sanitized = DeepReadSanitizer.sanitize(parsed, listOf(source))

        assertEquals("https://news.example.com/a.jpg", sanitized.heroImageUrl)
        assertFalse(sanitized.references.any { it.url.contains("invented") })
        assertEquals(listOf("real quoted sentence from the source"), sanitized.analysis.quotes.map { it.text })
        assertEquals(listOf(source.url), sanitized.extendedReading.map { it.url })
    }
}
