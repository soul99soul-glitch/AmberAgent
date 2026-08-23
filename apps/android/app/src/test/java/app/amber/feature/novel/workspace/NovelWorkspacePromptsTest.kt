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
}
