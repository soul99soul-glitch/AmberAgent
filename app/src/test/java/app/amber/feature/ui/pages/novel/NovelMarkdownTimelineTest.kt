package app.amber.feature.ui.pages.novel

import app.amber.ai.core.MessageRole
import app.amber.feature.novel.workspace.NovelWorkspaceWriteEntry
import app.amber.feature.novel.workspace.NovelWorkspaceWriteProposal
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import app.amber.feature.novelworkspace.NovelWorkspaceCommit

class NovelMarkdownTimelineTest {
    @Test
    fun `messages drafts and approvals share chronological order instead of separate blocks`() {
        val state = NovelMarkdownWorkspaceUiState(
            messages = listOf(message("early", 1), message("latest", 5)),
            drafts = listOf(NovelMarkdownDraftUi("drafts/one.md", "Draft", "Prose", Instant.ofEpochSecond(2))),
            proposals = listOf(NovelWorkspaceWriteProposal(
                id = "approval", projectDirectory = File("book"), branchId = "branch", branchSlug = "main",
                baseHeadId = "head", baseTreeDigest = "tree",
                entries = listOf(NovelWorkspaceWriteEntry("chapters/one.md", "Revised", reason = null)),
                createdAt = Instant.ofEpochSecond(3),
            )),
        )
        assertEquals(listOf("message-latest", "proposal-approval", "draft-drafts/one.md", "message-early"),
            novelMarkdownTimeline(state).map { it.key })
        val unchangedKeys = novelMarkdownTimeline(state.copy(busy = true, messages =
            state.messages.map { it.copy(content = "Updated content") })).map { it.key }
        assertEquals(novelMarkdownTimeline(state).map { it.key }, unchangedKeys)
    }

    @Test
    fun `equal timestamp messages retain durable session sequence in reverse layout`() {
        val state = NovelMarkdownWorkspaceUiState(messages = listOf(
            message("question", 0).copy(role = MessageRole.USER), message("answer", 0),
        ))
        assertEquals(listOf("message-answer", "message-question"), novelMarkdownTimeline(state).map { it.key })
    }

    @Test
    fun `reused draft path starts after the collection deletion and dirty new draft uses file time`() {
        val path = "drafts/one.md"
        fun commit(id: String, second: Long, exists: Boolean) = NovelWorkspaceCommit(
            id = id, createdAt = Instant.ofEpochSecond(second), message = "Test", treeSHA256 = "tree",
            files = if (exists) mapOf(path to id) else emptyMap(),
        )
        val ancestry = listOf(commit("old", 1, true), commit("collected", 2, false),
            commit("regenerated", 3, true), commit("edited", 4, true))
        assertEquals(Instant.ofEpochSecond(3), novelMarkdownDraftCreatedAt(ancestry, path, Instant.ofEpochSecond(5)))
        assertEquals(Instant.ofEpochSecond(5), novelMarkdownDraftCreatedAt(ancestry.take(2), path, Instant.ofEpochSecond(5)))
    }

    private fun message(id: String, seconds: Long) =
        NovelMarkdownMessageUi(id, MessageRole.ASSISTANT, id, createdAt = Instant.ofEpochSecond(seconds))
}
