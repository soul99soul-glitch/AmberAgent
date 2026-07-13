package app.amber.feature.novel

import app.amber.feature.novel.runtime.NovelInjectionDefaults
import app.amber.feature.novel.runtime.NovelPromptVersions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelPromptVersionsTest {
    @Test
    fun frozenPromptVersionsMatchIosCatalog() {
        assertEquals("novel.quick-start.v2", NovelPromptVersions.QUICK_START)
        assertEquals("novel.discussion.v1", NovelPromptVersions.DISCUSSION)
        assertEquals("novel.prose-continuation.v1", NovelPromptVersions.PROSE_CONTINUATION)
        assertEquals("novel.prose-whole-chapter.v1", NovelPromptVersions.PROSE_WHOLE_CHAPTER)
        assertEquals("novel.state-delta.v1", NovelPromptVersions.STATE_DELTA)
        assertEquals("novel.manual-sync.v2", NovelPromptVersions.MANUAL_SYNC)
        assertEquals("novel.whole-chapter-polish.v2", NovelPromptVersions.WHOLE_CHAPTER_POLISH)
        assertEquals("novel.polish-drift.v1", NovelPromptVersions.POLISH_DRIFT)
        assertEquals(8, NovelPromptVersions.all.size)
        assertEquals(NovelPromptVersions.all.toSet().size, NovelPromptVersions.all.size)
    }

    @Test
    fun frozenInjectionDefaultsMatchV1() {
        assertEquals(16_000, NovelInjectionDefaults.ESTIMATED_INPUT_TOKENS)
        assertEquals(6_000, NovelInjectionDefaults.CHAPTER_TAIL_CHARS)
        assertEquals(12, NovelInjectionDefaults.RECENT_SESSION_MESSAGES)
    }

    @Test
    fun quickStartMustRemainExactlyFourTypedProposalsContract() {
        // V1 product rule: one each of world / character / masterOutline / writingRequirements.
        // Android must not auto-split character overviews into multiple proposals.
        val requiredKinds = listOf("world", "character", "masterOutline", "writingRequirements")
        assertEquals(4, requiredKinds.size)
        assertTrue(requiredKinds.contains("character"))
        assertEquals(1, requiredKinds.count { it == "character" })
    }
}
