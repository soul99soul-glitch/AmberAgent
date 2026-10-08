package app.amber.feature.ui.pages.novel

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.amber.agent.R
import app.amber.feature.novel.workspace.NovelWorkspaceWriteEntry
import app.amber.feature.novel.workspace.NovelWorkspaceWriteProposal
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h720dp-xxhdpi")
class NovelMarkdownReviewSheetsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun proposalKeepsFullTextEditsAndAllowsDismissWhileLocked() {
        val proposedBody = "A long passage. ".repeat(30) + "FULL_PROPOSED_END"
        val originalBody = "Original passage. ".repeat(30) + "FULL_ORIGINAL_END"
        val proposed = "---\nid: model-id\ntitle: Ignored model rename\n---\n\n$proposedBody"
        val original = "---\nid: author-id\ntitle: Author chapter title\n---\n\n$originalBody"
        val initial = NovelWorkspaceWriteProposal(
            id = "review", projectDirectory = File("unused"), branchId = "main", branchSlug = "main",
            baseHeadId = "head", baseTreeDigest = "tree",
            entries = listOf(NovelWorkspaceWriteEntry("branches/main/chapters/1.md", proposed, "Author review")),
            createdAt = Instant.EPOCH,
        )
        val proposal = mutableStateOf(initial)
        val locked = mutableStateOf(false)
        var saved: List<NovelWorkspaceWriteEntry>? = null
        var approved = false
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                MarkdownProposalReviewSheet(
                    proposal = proposal.value, busy = false, writeLocked = locked.value,
                    errorMessage = null, readRaw = { original },
                    onSave = { entries, done ->
                        saved = entries
                        proposal.value = proposal.value.copy(entries = entries)
                        done()
                    },
                    onApprove = { approved = true; it() }, onReject = {},
                    onDismiss = { dismissed = true },
                )
            }
        }
        compose.onNodeWithText(originalBody).assertExists()
        compose.onNodeWithText("Author chapter title").assertExists()
        compose.onNodeWithTag("novel-proposal-body").assertTextEquals(proposedBody)
        compose.onNodeWithTag("novel-proposal-body").assertTextContains("FULL_PROPOSED_END", substring = true)
        compose.onNodeWithTag("novel-proposal-body").performTextReplacement("Author edited the complete proposal")
        compose.onNodeWithText(compose.activity.getString(R.string.novel_save_proposal)).performClick()
        compose.runOnIdle {
            assertEquals("Author edited the complete proposal", saved?.single()?.content?.let {
                NovelWorkspaceMarkdown.parseFile(it).body
            })
            assertEquals(initial.entries.single().path, saved?.single()?.path)
            assertEquals(initial.entries.single().reason, saved?.single()?.reason)
            assertFalse(approved)
            locked.value = true
        }
        compose.onNodeWithTag("novel-proposal-body").assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.novel_confirm_write)).assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, dismissed) }
    }

    @Test
    fun changingFileClearsPreviousPreviewAndDisablesApprovalUntilItsReadCompletes() {
        val firstPath = "branches/main/chapters/1.md"
        val secondPath = "branches/main/chapters/2.md"
        val secondRead = CompletableDeferred<String>()
        val proposal = NovelWorkspaceWriteProposal(
            id = "async-review", projectDirectory = File("unused"), branchId = "main", branchSlug = "main",
            baseHeadId = "head", baseTreeDigest = "tree",
            entries = listOf(
                NovelWorkspaceWriteEntry(firstPath, "First proposed text", null),
                NovelWorkspaceWriteEntry(secondPath, "Second proposed text", null),
            ),
            createdAt = Instant.EPOCH,
        )
        compose.setContent {
            MaterialTheme {
                MarkdownProposalReviewSheet(
                    proposal = proposal, busy = false, writeLocked = false, errorMessage = null,
                    readRaw = { path -> if (path == firstPath) "First current text" else secondRead.await() },
                    onSave = { _, done -> done() }, onApprove = { it() }, onReject = {}, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("First current text").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.novel_review_file_index, 1, 2)).performClick()
        compose.onNodeWithText(secondPath).performClick()
        compose.onNodeWithText("First current text").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.novel_review_reading)).assertExists()
        compose.onNodeWithTag("novel-proposal-body").assertTextEquals("Second proposed text").assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.novel_confirm_write)).assertIsNotEnabled()
        compose.runOnIdle { secondRead.complete("Second current text") }
        compose.onNodeWithText("Second current text").assertExists()
        compose.onNodeWithTag("novel-proposal-body").assertIsEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.novel_confirm_write)).assertIsEnabled()
    }

    @Test
    fun editingProposalBodyPreservesOriginalPlanHeaderBeforeApproval() {
        val header = """
            ---
            id: author-plan
            kind: chapterPlan
            title: Confirmed chapter plan
            # Author annotations stay with the plan.
            beats:
              - Reveal the letter
              - Keep the alliance uncertain
            futureRelations:
              - {with: character-a, type: alliance}
            futureMetadata:
              source: author
            ---
        """.trimIndent()
        val original = "$header\n\nOriginal plan body\n"
        val editedBody = "Author-approved plan body\n\nKeep the planned revelation."
        val initial = NovelWorkspaceWriteProposal(
            id = "plan-review", projectDirectory = File("unused"), branchId = "main", branchSlug = "main",
            baseHeadId = "confirmed-head", baseTreeDigest = "confirmed-tree",
            entries = listOf(NovelWorkspaceWriteEntry("branches/main/plan/this-chapter.md", original, "Author plan refinement")),
            createdAt = Instant.EPOCH,
        )
        val proposal = mutableStateOf(initial)
        val actions = mutableListOf<String>()
        var saved: NovelWorkspaceWriteEntry? = null
        var approved: NovelWorkspaceWriteEntry? = null
        compose.setContent {
            MaterialTheme {
                MarkdownProposalReviewSheet(
                    proposal = proposal.value, busy = false, writeLocked = false, errorMessage = null,
                    readRaw = { original },
                    onSave = { entries, done ->
                        actions += "save"
                        saved = entries.single()
                        proposal.value = proposal.value.copy(entries = entries)
                        done()
                    },
                    onApprove = { done -> actions += "approve"; approved = saved; done() },
                    onReject = {}, onDismiss = { actions += "dismiss" },
                )
            }
        }
        compose.onNodeWithTag("novel-proposal-body").performTextReplacement(editedBody)
        compose.onNodeWithText(compose.activity.getString(R.string.novel_confirm_write)).performClick()
        compose.runOnIdle {
            assertEquals(listOf("save", "approve", "dismiss"), actions)
            assertEquals("$header\n\n$editedBody\n", approved?.content)
            assertEquals(initial.entries.single().path, approved?.path)
            assertEquals(initial.entries.single().reason, approved?.reason)
            assertEquals(initial.baseHeadId, proposal.value.baseHeadId)
            assertEquals(initial.baseTreeDigest, proposal.value.baseTreeDigest)
        }
    }
}
