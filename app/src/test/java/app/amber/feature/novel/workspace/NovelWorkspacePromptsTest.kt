package app.amber.feature.novel.workspace

import org.junit.Assert.assertTrue
import org.junit.Test

class NovelWorkspacePromptsTest {

    @Test
    fun `discipline preamble names the five primitives and the gate`() {
        val prompt = NovelWorkspacePrompts.WORKSPACE_DISCIPLINE
        for (tool in listOf(
            "novel_workspace_list",
            "novel_workspace_read",
            "novel_workspace_grep",
            "novel_workspace_status",
            "novel_workspace_write",
        )) {
            assertTrue("missing $tool", prompt.contains(tool))
        }
        assertTrue(prompt.contains("审批卡"))
        assertTrue(prompt.contains("不要发明新的 novel_*"))
    }

    @Test
    fun `quickstart embeds the seed`() {
        val prompt = NovelWorkspacePrompts.quickStart("历史", "陈桥兵变前夜")
        assertTrue(prompt.contains("历史"))
        assertTrue(prompt.contains("陈桥兵变前夜"))
        assertTrue(prompt.contains("setting/characters/"))
    }

    @Test
    fun `prose draft granularity changes the goal`() {
        val continuation = NovelWorkspacePrompts.proseDraft(NovelWorkspacePrompts.ProseGranularity.CONTINUATION)
        val whole = NovelWorkspacePrompts.proseDraft(NovelWorkspacePrompts.ProseGranularity.WHOLE_CHAPTER)
        assertTrue(continuation.contains("续写一段"))
        assertTrue(whole.contains("一整章"))
        assertTrue(continuation.contains("drafts/"))
    }

    @Test
    fun `ghostwrite prompt carries ordinal and plan`() {
        val prompt = NovelWorkspacePrompts.ghostwriteChapter(24, "## 位置\n\n汴京")
        assertTrue(prompt.contains("第 24 章"))
        assertTrue(prompt.contains("汴京"))
    }

    @Test
    fun `polish chapter prompt embeds body, ordinal, path, preference and fact-freeze rules`() {
        val prompt = NovelWorkspacePrompts.polishChapter(
            chapterOrdinal = 5,
            chapterPath = "branches/主线/chapters/005-夜泊.md",
            chapterBody = "夜色沉沉，赵大伏在辕门外。",
            writingPreference = "文风冷峻克制；单章 2000-3000 字。",
        )
        assertTrue(prompt.contains(NovelWorkspacePrompts.WORKSPACE_DISCIPLINE))
        assertTrue(prompt.contains("第 5 章"))
        assertTrue(prompt.contains("branches/主线/chapters/005-夜泊.md"))
        assertTrue(prompt.contains("夜色沉沉，赵大伏在辕门外。"))
        assertTrue(prompt.contains("文风冷峻克制；单章 2000-3000 字。"))
        // Fact-freeze instructions are the contract of the mode.
        assertTrue(prompt.contains("情节事实"))
        assertTrue(prompt.contains("时间线"))
        assertTrue(prompt.contains("完全一致"))
        assertTrue(prompt.contains("±20%"))
        // Whole chapter is written back to the same locked path; nothing else may be touched.
        assertTrue(prompt.contains("写回 branches/主线/chapters/005-夜泊.md"))
        assertTrue(prompt.contains("novel_workspace_write"))
        // The host owns the plot pointer: the model must not touch plot files.
        assertTrue(prompt.contains("plot/"))
    }

    @Test
    fun `polish chapter prompt tolerates blank preference and empty body`() {
        val prompt = NovelWorkspacePrompts.polishChapter(
            chapterOrdinal = 2,
            chapterPath = "branches/主线/chapters/002-启程.md",
            chapterBody = "",
            writingPreference = "",
        )
        assertTrue(prompt.contains("（空章节）"))
        assertTrue(!prompt.contains("setting/writing）"))
    }

}
