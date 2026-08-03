package app.amber.feature.novel

import app.amber.feature.novel.runtime.NovelInjectionDefaults
import app.amber.feature.novel.runtime.NovelPromptVersions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelPromptVersionsTest {
    @Test
    fun frozenPromptVersionsMatchIosCatalog() {
        assertEquals("novel.quick-start.v3", NovelPromptVersions.QUICK_START)
        assertEquals("novel.discussion.v1", NovelPromptVersions.DISCUSSION)
        assertEquals("novel.prose-continuation.v1", NovelPromptVersions.PROSE_CONTINUATION)
        assertEquals("novel.prose-whole-chapter.v1", NovelPromptVersions.PROSE_WHOLE_CHAPTER)
        assertEquals("novel.state-delta.v1", NovelPromptVersions.STATE_DELTA)
        assertEquals("novel.manual-sync.v2", NovelPromptVersions.MANUAL_SYNC)
        assertEquals("novel.whole-chapter-polish.v2", NovelPromptVersions.WHOLE_CHAPTER_POLISH)
        assertEquals("novel.whole-chapter-regeneration.v1", NovelPromptVersions.WHOLE_CHAPTER_REGENERATION)
        assertEquals("novel.polish-drift.v1", NovelPromptVersions.POLISH_DRIFT)
        assertEquals("novel.continuity-audit.v1", NovelPromptVersions.CONTINUITY_AUDIT)
        assertEquals("novel.discussion-archive.v1", NovelPromptVersions.DISCUSSION_ARCHIVE)
        assertEquals(11, NovelPromptVersions.all.size)
        assertEquals(NovelPromptVersions.all.toSet().size, NovelPromptVersions.all.size)
    }

    @Test
    fun frozenInjectionDefaultsMatchV1() {
        assertEquals(16_000, NovelInjectionDefaults.ESTIMATED_INPUT_TOKENS)
        assertEquals(6_000, NovelInjectionDefaults.CHAPTER_TAIL_CHARS)
        assertEquals(12, NovelInjectionDefaults.RECENT_SESSION_MESSAGES)
    }

    @Test
    fun quickStartTypedProposalKindsContract_allowsMultipleCharacters() {
        // v3: world / masterOutline / writingRequirements remain single-kind slots;
        // character may be 1..N proposals (one person per entry).
        val singleSlotKinds = listOf("world", "masterOutline", "writingRequirements")
        assertEquals(3, singleSlotKinds.size)
        assertEquals(1, singleSlotKinds.count { it == "world" })
        assertTrue(NovelPromptVersions.QUICK_START.endsWith(".v3"))
        assertTrue(NovelPromptVersions.QUICK_START.contains("quick-start"))
    }
}
