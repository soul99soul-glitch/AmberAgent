package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelEventId
import app.amber.feature.novel.model.NovelStoryEventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NovelParagraphAndMatcherTest {
    @Test
    fun splitParagraphs_andDefaultSelection() {
        val paragraphs = NovelParagraphSelection.splitParagraphs("第一段。\n\n第二段。\n\n第三段。")
        assertEquals(3, paragraphs.size)
        val selected = NovelParagraphSelection.defaultSelectedIds(paragraphs)
        assertEquals(3, selected.size)
        assertEquals(
            "第一段。\n\n第二段。",
            NovelParagraphSelection.joinSelected(paragraphs, setOf(paragraphs[0].id, paragraphs[1].id)),
        )
    }

    @Test
    fun splitParagraphs_blankLineSeparators_keepIntraParagraphNewlines() {
        val paragraphs = NovelParagraphSelection.splitParagraphs(
            "第一行\n第一段续\n\n第二段\n\n  \n第三段",
        )
        assertEquals(3, paragraphs.size)
        assertEquals("第一行\n第一段续", paragraphs[0].text)
        assertEquals("第二段", paragraphs[1].text)
        assertEquals("第三段", paragraphs[2].text)
    }

    @Test
    fun suggestTarget_defaults() {
        assertEquals(
            NovelParagraphSelection.SuggestedTarget.CreateFirstChapter,
            NovelParagraphSelection.suggestTarget(chapterCount = 0, isWholeChapter = true),
        )
        assertEquals(
            NovelParagraphSelection.SuggestedTarget.CreateNextChapter,
            NovelParagraphSelection.suggestTarget(chapterCount = 1, isWholeChapter = true),
        )
        assertEquals(
            NovelParagraphSelection.SuggestedTarget.AppendCurrentChapter,
            NovelParagraphSelection.suggestTarget(chapterCount = 1, isWholeChapter = false),
        )
    }

    @Test
    fun characterMatcher_prefersExactThenContains_bySequenceDesc() {
        val events = listOf(
            event(1, "Hero arrives", listOf("Hero")),
            event(2, "Someone like hero appears", listOf("Young Hero")),
            event(3, "Unrelated", listOf("Villain")),
            event(4, "Hero leaves", listOf("Hero", "Town")),
        )
        val matches = NovelCharacterEventMatcher.matchExperiences("Hero", events)
        assertEquals(3, matches.size)
        assertTrue(matches[0].exact)
        assertEquals(4L, matches[0].event.sequence)
        assertEquals(1L, matches[1].event.sequence)
        assertTrue(matches.any { !it.exact && it.event.sequence == 2L })
    }

    private fun event(seq: Long, summary: String, refs: List<String>) = NovelStoryEventRecord(
        id = NovelEventId.generate(),
        sequence = seq,
        kind = "plot",
        summary = summary,
        entityReferences = refs,
        createdAt = Instant.EPOCH,
    )
}
